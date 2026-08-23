package com.example.deskpet.behavior

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * 行为逻辑管理器
 * 管理：
 * - App 反应映射
 * - 快速切换检测 (已在 OverlayService 中实现)
 * - 喝水提醒 (已在 OverlayService 中实现)
 * - 唤醒过渡
 * - 20 分钟定时行为 (已在 JS 中实现)
 * - 连击计数 (已在 OverlayService 中实现)
 */
class BehaviorManager(private val context: Context, private var webView: WebView?) {

    private val handler = Handler(Looper.getMainLooper())

    // === App 反应映射 ===
    // 包名 -> 自定义反应
    private val appReactions = mapOf(
        "com.taobao" to AppReaction("tsundere", "又在逛淘宝！", "🛍️", "red"),
        "com.zhihu" to AppReaction("happy", "刷知乎呢？", "📖", "pink"),
        "com.bilibili" to AppReaction("happy", "看视频不叫我！", "📺", "pink"),
        "com.tencent.mm" to AppReaction("tsundere", "又在跟谁聊天！", "💬", "red"),
        "com.tencent.mobileqq" to AppReaction("tsundere", "QQ响了！", "💬", "red"),
        "com.sina.weibo" to AppReaction("tsundere", "刷微博不带我！", "📱", "red"),
        "com.eg.android.AlipayGphone" to AppReaction("happy", "要给我买什么！", "💰", "pink"),
        "com.netease.cloudmusic" to AppReaction("love", "听歌吗~一起！", "🎵", "pink"),
        "com.UCMobile" to AppReaction("happy", "在看什么呀", "👀", "pink"),
        "com.android.chrome" to AppReaction("idle", "在看网页呢", "🌐", null),
        "com.tencent.mtt" to AppReaction("idle", "用QQ浏览器呀", "🌐", null),
        "com.ss.android.ugc.aweme" to AppReaction("tsundere", "刷抖音不带我！", "🎵", "red"),
        "com.zhiliaoapp.musically" to AppReaction("tsundere", "刷TikTok呢", "🎵", "red"),
        "com.kuaishou.nebula" to AppReaction("tsundere", "刷快手不叫我", "🎵", "red")
    )

    fun setWebView(wv: WebView?) {
        webView = wv
    }

    /**
     * 根据包名获取反应配置
     */
    fun getReaction(pkgName: String): AppReaction? {
        // 精确匹配
        appReactions[pkgName]?.let { return it }
        // 模糊匹配
        for ((key, value) in appReactions) {
            if (pkgName.contains(key) || key.contains(pkgName)) {
                return value
            }
        }
        return null
    }

    fun start() {
        // 行为管理器随服务启动，不做额外操作
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    data class AppReaction(
        val mood: String,
        val text: String,
        val emoji: String,
        val style: String? = null
    )
}