package com.example.deskpet.emotion

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Heat 情绪引擎
 * 热度值 0-100，屏幕叠加红色渐变，30秒衰减一格
 * 支持三级触发词响应
 */
class HeatEngine(private val service: Any) {

    private var webView: WebView? = null
    private var heatValue = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isDecaying = false

    // 三级触发词阈值
    companion object {
        // T1 激烈（高热度需要）
        const val TRIGGER_HIGH = 60
        // T2 温暖（中等热度）
        const val TRIGGER_MEDIUM = 30
        // T3 轻柔（低热度）
        const val TRIGGER_LOW = 10
    }

    fun setWebView(wv: WebView?) {
        webView = wv
    }

    fun getHeat(): Int = heatValue

    fun addHeat(value: Int) {
        heatValue = (heatValue + value).coerceIn(0, 100)
        updateOverlay()
        if (!isDecaying) startDecay()
    }

    fun setHeat(value: Int) {
        heatValue = value.coerceIn(0, 100)
        updateOverlay()
        if (!isDecaying) startDecay()
    }

    private fun updateOverlay() {
        webView?.evaluateJavascript(
            "window.petEngine && petEngine.heatValue = $heatValue && petEngine.updateHeatOverlay()",
            null
        )
    }

    private fun startDecay() {
        isDecaying = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (heatValue > 0) {
                    heatValue = (heatValue - 1).coerceAtLeast(0)
                    updateOverlay()
                    handler.postDelayed(this, 30000)
                } else {
                    isDecaying = false
                }
            }
        }, 30000)
    }

    /**
     * 触发词三级响应
     * @param level 1=激烈, 2=温暖, 3=轻柔
     * @param text 可选的自定义文本
     */
    fun triggerWord(level: Int, text: String? = null) {
        val mood = when (level) {
            1 -> "surprised"
            2 -> "love"
            else -> "shy"
        }
        val style = when (level) {
            1 -> "red"
            2 -> "pink"
            else -> "gray"
        }
        val bubble = text ?: when (level) {
            1 -> "！？！"
            2 -> "❤️"
            else -> "嗯…"
        }

        webView?.evaluateJavascript(
            "petEngine.triggerWord($level, '${bubble.replace("'", "\\'")}')", null
        )

        // 根据级别加不同热量
        when (level) {
            1 -> addHeat(30)
            2 -> addHeat(15)
            3 -> addHeat(5)
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }
}