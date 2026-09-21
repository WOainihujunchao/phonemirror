# 保持 WebRTC 原生接口（google-webrtc 含 .so，无需混淆其 JNI 符号）
-keep class org.webrtc.** { *; }
