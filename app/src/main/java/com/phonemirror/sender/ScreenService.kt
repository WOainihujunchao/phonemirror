package com.phonemirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 被查看端前台服务：
 * - 通过屏幕采集（ScreenCapturerAndroid）捕获本机画面
 * - 经 WebSocket 信令 + WebRTC 推流给查看端
 * - 断网自动重连；授权一次后后台常驻，查看端随时可拉取画面（无需再次同意）
 */
class ScreenService : android.app.Service() {
    companion object {
        var isRunning = false
        const val CHANNEL_ID = "screen_mirror"
        const val NOTIF_ID = 1
    }

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private val pcMap = HashMap<String, PeerConnection>()
    private var videoSource: VideoSource? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var ws: WebSocket? = null
    private lateinit var client: OkHttpClient
    private var serverUrl = ""
    private var room = "Kx7m2Qp9"
    private var resultCode = 0
    private var mediaData: Intent? = null
    private var iceServerList: List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    private var reconnectDelay = 3000L
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("正在连接服务器…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        mediaData = intent?.getParcelableExtra("data")
        serverUrl = intent?.getStringExtra("server") ?: ""
        room = intent?.getStringExtra("room") ?: "Kx7m2Qp9"
        fetchConfigAndConnect()
        return android.app.Service.START_STICKY
    }

    private fun fetchConfigAndConnect() {
        val base = serverUrl.trim().trimEnd('/')
        val cfgUrl = if (base.startsWith("http")) base + "/api/config" else "https://$base/api/config"
        client.newCall(Request.Builder().url(cfgUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { connectSignaling() }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    try {
                        val arr = JSONObject(body).getJSONArray("iceServers")
                        val list = ArrayList<PeerConnection.IceServer>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val b = PeerConnection.IceServer.builder(o.getString("urls"))
                            if (o.has("username")) b.setUsername(o.getString("username"))
                            if (o.has("credential")) b.setCredential(o.getString("credential"))
                            list.add(b.createIceServer())
                        }
                        if (list.isNotEmpty()) iceServerList = list
                    } catch (e: Exception) {
                        Log.w("PM", "parse /api/config failed, use STUN only", e)
                    }
                }
                connectSignaling()
            }
        })
    }

    private fun connectSignaling() {
        val wsUrl = serverUrl.trim().trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelay = 3000L
                webSocket.send(
                    JSONObject().put("type", "join").put("room", room).put("role", "sender").toString()
                )
                updateNotif("已连接，等待查看端（房间 $room）")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scheduleReconnect() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scheduleReconnect() }
        }).also { ws = it }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        updateNotif("连接断开，${reconnectDelay / 1000}s 后重连…")
        handler.postDelayed({ if (isRunning) connectSignaling() }, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
    }

    private fun handle(text: String) {
        try {
            val m = JSONObject(text)
            when (m.getString("type")) {
                "offer" -> {
                    val viewerId = m.optString("viewerId")
                    val sdp = m.getJSONObject("sdp").getString("sdp")
                    val pc = getOrCreatePeer(viewerId)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p: SessionDescription?) {}
                        override fun onSetSuccess() {
                            pc.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(desc: SessionDescription?) {
                                    pc.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(p: SessionDescription?) {}
                                        override fun onSetSuccess() {
                                            ws?.send(
                                                JSONObject().put("type", "answer")
                                                    .put("viewerId", viewerId)
                                                    .put("sdp", JSONObject().put("type", "answer").put("sdp", desc?.description))
                                                    .toString()
                                            )
                                        }
                                        override fun onCreateFailure(p: String?) {}
                                        override fun onSetFailure(p: String?) {}
                                    }, desc)
                                }
                                override fun onCreateFailure(p: String?) {}
                                override fun onSetSuccess() {}
                                override fun onSetFailure(p: String?) {}
                            })
                        }
                        override fun onCreateFailure(p: String?) {}
                        override fun onSetFailure(p: String?) {}
                    }, SessionDescription(SessionDescription.Type.OFFER, sdp))
                }
                "ice" -> {
                    val viewerId = m.optString("viewerId")
                    val cand = m.optJSONObject("candidate")
                    if (cand != null) {
                        val c = IceCandidate(
                            cand.optString("sdpMid"),
                            cand.optInt("sdpMLineIndex"),
                            cand.optString("candidate")
                        )
                        pcMap[viewerId]?.addIceCandidate(c)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PM", "handle signaling error", e)
        }
    }

    private fun getOrCreatePeer(viewerId: String): PeerConnection {
        val existing = pcMap[viewerId]
        if (existing != null) return existing

        // 新版 WebRTC 已移除 SdpSemantics（默认即为 Unified Plan），不要再设置
        val cfg = PeerConnection.RTCConfiguration(iceServerList)
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {
                c?.let {
                    ws?.send(
                        JSONObject().put("type", "ice")
                            .put("viewerId", viewerId)
                            .put(
                                "candidate",
                                JSONObject().put("candidate", it.sdp).put("sdpMid", it.sdpMid)
                                    .put("sdpMLineIndex", it.sdpMLineIndex)
                            )
                            .toString()
                    )
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                state?.let { updateNotif("连接状态：$it（房间 $room）") }
            }
            override fun onSignalingChange(p: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(p: MediaStream?) {}
            override fun onRemoveStream(p: MediaStream?) {}
            override fun onDataChannel(p: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })!!
        pcMap[viewerId] = pc

        if (surfaceTextureHelper == null)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        if (videoSource == null) videoSource = factory.createVideoSource(false)
        if (capturer == null && mediaData != null) {
            capturer = ScreenCapturerAndroid(mediaData!!, object : MediaProjection.Callback() {
                override fun onStop() { updateNotif("对方已停止查看 / 屏幕采集已停止") }
            })
            capturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
            val dm = resources.displayMetrics
            capturer?.startCapture(dm.widthPixels, dm.heightPixels, 20)
        }
        val videoTrack = factory.createVideoTrack("video-$viewerId", videoSource)
        pc.addTrack(videoTrack)
        return pc
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机同屏 · 被查看端")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "同屏投屏", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { ws?.close(1000, "bye") } catch (e: Exception) {}
        try { capturer?.stopCapture() } catch (e: Exception) {}
        try { videoSource?.dispose() } catch (e: Exception) {}
        for (pc in pcMap.values) try { pc.close() } catch (e: Exception) {}
        pcMap.clear()
        try { eglBase.release() } catch (e: Exception) {}
    }
}
