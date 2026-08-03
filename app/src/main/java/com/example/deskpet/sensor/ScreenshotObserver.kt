package com.example.deskpet.sensor

import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.io.File

/**
 * 截图检测 (FileObserver)
 * 监听截图目录新文件 -> 回调给 WebView
 */
class ScreenshotObserver(private var webView: WebView?) {

    private val observers = mutableListOf<FileObserver>()

    companion object {
        private val SCREENSHOT_PATHS = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .resolve("Screenshots").absolutePath,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                .resolve("Screenshots").absolutePath,
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        )
    }

    fun setWebView(wv: WebView?) {
        webView = wv
    }

    fun start() {
        for (path in SCREENSHOT_PATHS) {
            val dir = File(path)
            if (!dir.exists()) continue

            val observer = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isImageFile(path)) {
                        onScreenshotDetected()
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") ||
               lower.endsWith(".jpg") ||
               lower.endsWith(".jpeg") ||
               lower.endsWith(".gif")
    }

    private var lastNotify = 0L
    private fun onScreenshotDetected() {
        val now = System.currentTimeMillis()
        // 防止重复触发（5秒内只触发一次）
        if (now - lastNotify < 5000) return
        lastNotify = now

        Handler(Looper.getMainLooper()).post {
            webView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onScreenshot()", null
            )
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}