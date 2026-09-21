package com.phonemirror.sender

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonemirror.sender.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var mediaData: Intent? = null
    private var mediaResultCode = 0

    // 默认服务器（已预填，可自行改成你的地址）
    private val defaultServer = "https://111.229.194.208:3443"

    // 屏幕采集授权结果
    private val mediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            mediaResultCode = res.resultCode
            mediaData = res.data
            startMirrorService()
        } else {
            binding.tvStatus.text = "未授权屏幕采集，无法投屏"
        }
    }

    // 通知权限（Android 13+ 前台服务需要）
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchMediaProjection() else binding.tvStatus.text = "需要通知权限才能常驻前台"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("pm", MODE_PRIVATE)
        binding.etServer.setText(prefs.getString("server", defaultServer))
        binding.etRoom.setText(prefs.getString("room", "Kx7m2Qp9"))
        binding.etServer.setSelection(binding.etServer.text.length)

        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenService::class.java))
            binding.tvStatus.text = "已停止投屏"
        }

        if (ScreenService.isRunning) {
            binding.tvStatus.text = "正在投屏（房间 ${prefs.getString("room", "Kx7m2Qp9")}）"
        }
    }

    private fun onStartClick() {
        val server = binding.etServer.text.toString().trim()
        val room = binding.etRoom.text.toString().trim().ifEmpty { "Kx7m2Qp9" }
        if (server.isEmpty()) {
            binding.etServer.error = "请填写服务器地址"
            return
        }
        prefs.edit().putString("server", server).putString("room", room).apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchMediaProjection()
    }

    private fun launchMediaProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startMirrorService() {
        val intent = Intent(this, ScreenService::class.java).apply {
            putExtra("resultCode", mediaResultCode)
            putExtra("data", mediaData)
            putExtra("server", binding.etServer.text.toString().trim())
            putExtra("room", binding.etRoom.text.toString().trim().ifEmpty { "Kx7m2Qp9" })
        }
        ContextCompat.startForegroundService(this, intent)
        binding.tvStatus.text = "正在投屏（房间 ${prefs.getString("room", "Kx7m2Qp9")}）"
    }
}
