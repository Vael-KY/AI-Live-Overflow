package com.example.deskpet.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.deskpet.service.OverlayService

/**
 * 电池状态监测
 * 监听充电/断电/低电量
 */
class BatteryMonitor(private val context: Context) {

    private var serviceRef: OverlayService? = null
    private var isRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val percentage = if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else -1

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            if (percentage >= 0) {
                serviceRef?.onBatteryChanged(percentage, isCharging)
            }
        }
    }

    fun setService(service: OverlayService) {
        serviceRef = service
    }

    fun start() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
            }
            context.registerReceiver(batteryReceiver, filter)
            isRegistered = true
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
            isRegistered = false
        }
    }
}