package com.example.deskpet.sensor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.deskpet.service.OverlayService
import java.util.*

/**
 * 前台 App 检测 (UsageStatsManager)
 * 每3秒轮询，检测前台 App 变化 -> 回调给 OverlayService
 */
class UsageTracker(private val context: Context) {

    private var timer: Timer? = null
    private var lastApp: String = ""
    private var serviceRef: OverlayService? = null

    fun setService(service: OverlayService) {
        serviceRef = service
    }

    fun start() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val current = getForegroundApp()
                if (current.isNotEmpty() && current != lastApp) {
                    lastApp = current
                    serviceRef?.onAppChanged(current)
                }
            }
        }, 0, 3000)
    }

    private fun getForegroundApp(): String {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = UsageEvents.Event()
            var foreground = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foreground = event.packageName
                }
            }
            return foreground
        } catch (e: Exception) {
            return ""
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }
}