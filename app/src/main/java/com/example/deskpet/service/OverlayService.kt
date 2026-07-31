package com.example.deskpet.service
import java.util.Calendar
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.deskpet.sensor.UsageTracker
import com.example.deskpet.sensor.ScreenshotObserver
import com.example.deskpet.sensor.BatteryMonitor
import com.example.deskpet.backend.SupabaseSync
import com.example.deskpet.behavior.BehaviorManager
import com.example.deskpet.emotion.HeatEngine
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AI-Live-Overflow 核心悬浮窗服务
 * 六层架构：基础层 + 手势系统 + 感知系统 + 表达系统 + 情绪引擎 + 行为逻辑
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_WIDTH_DP = 180
        private const val PET_HEIGHT_DP = 240

        // 连击计数配置：2秒内 3/5/8 次触发不同反应
        private const val COMBO_WINDOW = 2000L
        private const val COMBO_3 = 3
        private const val COMBO_5 = 5
        private const val COMBO_8 = 8

        // 快速切换检测：60秒内3个不同app
        private const val FAST_SWITCH_WINDOW = 60000L
        private const val FAST_SWITCH_COUNT = 3
    }

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    // 子系统
    private lateinit var usageTracker: UsageTracker
    private lateinit var screenshotObserver: ScreenshotObserver
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var heatEngine: HeatEngine
    private lateinit var behaviorManager: BehaviorManager
    private lateinit var supabaseSync: SupabaseSync

    // === 手势系统 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    // 连击计数
    private var tapCount = 0
    private var lastTapInCombo = 0L
    private var comboHandler = Handler(Looper.getMainLooper())

    // 快速切换检测
    private val appSwitchHistory = mutableListOf<Pair<String, Long>>()
    private var lastApp = ""

    // === 通知碎碎念 ===
    private var whisperHandler = Handler(Looper.getMainLooper())
    private val WHISPER_INTERVAL = 3600_000L // 1小时

    // === 喝水提醒 ===
    private var drinkHandler = Handler(Looper.getMainLooper())
    private val DRINK_INTERVAL = 7200_000L // 2小时

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🐾 我在这里~"))

        // 初始化子系统
        heatEngine = HeatEngine(this)
        supabaseSync = SupabaseSync(this)
        behaviorManager = BehaviorManager(this, null) // WebView 创建后再赋值
        usageTracker = UsageTracker(this)
        screenshotObserver = ScreenshotObserver(null) // WebView 创建后再赋值
        batteryMonitor = BatteryMonitor(this)

        setupOverlay()
        startWhisperRotation()
        startDrinkReminder()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_WIDTH_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            
            }// setAppCacheEnabled(true)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())

            // WebView 就绪后注入依赖并启动子系统
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 注入 Supabase 配置（由用户自定义）
                    injectSupabaseConfig()
                    // 启动感知子系统
                    usageTracker.start()
                    screenshotObserver = ScreenshotObserver(view)
                    screenshotObserver.start()
                    batteryMonitor.start()
                    // 更新行为管理器的 WebView 引用
                    behaviorManager = BehaviorManager(this@OverlayService, view)
                    heatEngine.setWebView(view)
                    behaviorManager.start()
                }
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun injectSupabaseConfig() {
        // 由用户替换为自己的 Supabase URL 和 Key
        overlayView?.evaluateJavascript(
            """
            window._supabaseUrl = '${SupabaseSync.SUPABASE_URL}';
            window._supabaseKey = '${SupabaseSync.SUPABASE_KEY}';
            """.trimIndent(), null
        )
    }

    // ============================
    // 手势系统 (Gesture System)
    // ============================
    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    } else {
                        // Fling 检测
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        val velocity = sqrt((dx * dx + dy * dy).toDouble())
                        if (velocity > 200 && elapsed < 400) {
                            onFling(dx, dy)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        tapCount++
        val now = System.currentTimeMillis()

        // 连击检测：2秒内连击计数
        if (now - lastTapInCombo > COMBO_WINDOW) {
            tapCount = 1
        }
        lastTapInCombo = now

        // 重置连击计时器
        comboHandler.removeCallbacksAndMessages(null)
        comboHandler.postDelayed({
            if (tapCount >= COMBO_8) {
                evaluateJavascript("petEngine.onTap(); petEngine.say('戳够了吧！','red'); petEngine.showEmotion('😵')")
                supabaseSync.logGesture("combo_8")
                heatEngine.addHeat(40)
            } else if (tapCount >= COMBO_5) {
                evaluateJavascript("petEngine.onTap(); petEngine.say('还戳！','red'); petEngine.showEmotion('😤')")
                supabaseSync.logGesture("combo_5")
                heatEngine.addHeat(25)
            } else if (tapCount >= COMBO_3) {
                evaluateJavascript("petEngine.onTap(); petEngine.say('再戳一下试试~','pink'); petEngine.showEmotion('😳')")
                supabaseSync.logGesture("combo_3")
                heatEngine.addHeat(15)
            } else {
                evaluateJavascript("petEngine.onTap()")
                supabaseSync.logGesture("tap")
                heatEngine.addHeat(5)
            }
            tapCount = 0
        }, COMBO_WINDOW)

        // 触感反馈
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun onDoubleTap() {
        evaluateJavascript("petEngine.onDoubleTap()")
        supabaseSync.logGesture("double_tap")
        heatEngine.addHeat(15)
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun onLongPress() {
        evaluateJavascript("petEngine.onLongPress()")
        supabaseSync.logGesture("long_press")
        heatEngine.addHeat(20)
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun onFling(dx: Int, dy: Int) {
        evaluateJavascript("petEngine.onFling($dx, $dy)")
        supabaseSync.logGesture("fling")
        heatEngine.addHeat(8)
    }

    // ============================
    // 表达系统 - 通知碎碎念
    // ============================
    private fun startWhisperRotation() {
        whisperHandler.postDelayed(object : Runnable {
            override fun run() {
                updateNotification()
                whisperHandler.postDelayed(this, WHISPER_INTERVAL)
            }
        }, WHISPER_INTERVAL)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 0..5 -> listOf("还不睡吗…", "熬夜会变丑哦", "晚安💤", "已经凌晨了诶！")
            hour in 6..8 -> listOf("早上好呀~", "新的一天！", "起床啦🌞")
            hour in 12..13 -> listOf("该吃饭啦~", "中午好！", "吃饱了吗？")
            hour in 22..23 -> listOf("快睡啦", "夜深了…", "再玩一会儿就睡吧")
            else -> listOf("我看着你呢~", "嘿嘿", "今天过得怎么样？", "想你了")
        }.random()
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾 桌宠")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ============================
    // 喝水提醒 (每2小时)
    // ============================
    private fun startDrinkReminder() {
        drinkHandler.postDelayed(object : Runnable {
            override fun run() {
                evaluateJavascript("petEngine.drinkReminder()")
                // 更新通知也提示喝水
                updateNotification()
                drinkHandler.postDelayed(this, DRINK_INTERVAL)
            }
        }, DRINK_INTERVAL)
    }

    // ============================
    // 感知系统 - 外部接口
    // ============================

    /** 前台 App 变化时由 UsageTracker 调用 */
    fun onAppChanged(pkgName: String) {
        if (pkgName == lastApp) return
        lastApp = pkgName

        // 快速切换检测
        val now = System.currentTimeMillis()
        appSwitchHistory.add(Pair(pkgName, now))
        appSwitchHistory.removeAll { now - it.second > FAST_SWITCH_WINDOW }

        if (appSwitchHistory.map { it.first }.distinct().size >= FAST_SWITCH_COUNT) {
            evaluateJavascript("petEngine.onFastSwitch()")
            appSwitchHistory.clear()
            heatEngine.addHeat(10)
        }

        evaluateJavascript("petEngine.onAppChanged('${pkgName.replace("'", "\\'")}')")
        supabaseSync.reportAppUsage(pkgName)
    }

    /** 截图检测触发 */
    fun onScreenshotDetected() {
        evaluateJavascript("petEngine.onScreenshot()")
        supabaseSync.logGesture("screenshot")
        heatEngine.addHeat(8)
    }

    /** 电池变化 */
    fun onBatteryChanged(level: Int, isCharging: Boolean) {
        evaluateJavascript("petEngine.onBatteryChanged($level, $isCharging)")
    }

    // ============================
    // AI 推送接口 (被 SupabaseSync 调用)
    // ============================
    fun pushFromAI(mood: String?, speech: String?, style: String?, heat: Int?) {
        val cmd = buildString {
            append("petEngine.pushFromAI({")
            mood?.let { append("mood:'${it.replace("'", "\\'")}',") }
            speech?.let { append("speech:'${it.replace("'", "\\'")}',") }
            style?.let { append("style:'${it.replace("'", "\\'")}',") }
            heat?.let { append("heat:$it,") }
            append("})")
        }
        evaluateJavascript(cmd)
    }

    // ============================
    // 工具方法
    // ============================
    private fun evaluateJavascript(script: String) {
        overlayView?.evaluateJavascript(script, null)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        usageTracker.stop()
        screenshotObserver.stop()
        batteryMonitor.stop()
        behaviorManager.stop()
        whisperHandler.removeCallbacksAndMessages(null)
        drinkHandler.removeCallbacksAndMessages(null)
        comboHandler.removeCallbacksAndMessages(null)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
