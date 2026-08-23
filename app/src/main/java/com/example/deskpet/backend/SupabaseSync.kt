package com.example.deskpet.backend

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.deskpet.service.OverlayService
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase 后端同步
 * 负责：
 * 1. 上报手势日志 (gesture_log)
 * 2. 上报前台 App 使用 (app_usage)
 * 3. 轮询 AI 推送 (pet_state)
 * 4. 双保险：Realtime + 轮询
 */
class SupabaseSync(private val context: Context) {

    companion object {
        // ===== 用户需要替换为自己的 Supabase 配置 =====
        const val SUPABASE_URL = "https://your-project.supabase.co"
        const val SUPABASE_KEY = "your-anon-key"
        // ============================================

        private const val POLL_INTERVAL = 5000L
    }

    private var serviceRef: OverlayService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    fun setService(service: OverlayService) {
        serviceRef = service
    }

    // ========================
    // 1. 手势日志上报
    // ========================
    fun logGesture(type: String) {
        launch({
            val body = JSONObject().apply {
                put("gesture_type", type)
                put("timestamp", System.currentTimeMillis())
            }
            postToSupabase("gesture_log", body)
        })
    }

    // ========================
    // 2. App 使用上报
    // ========================
    fun reportAppUsage(packageName: String) {
        launch({
            val body = JSONObject().apply {
                put("package_name", packageName)
                put("started_at", System.currentTimeMillis())
            }
            postToSupabase("app_usage", body)
        })
    }

    // ========================
    // 3. 启动轮询
    // ========================
    fun startPolling() {
        if (isPolling) return
        isPolling = true
        handler.postDelayed(pollRunnable, POLL_INTERVAL)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            pollState()
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    /**
     * 轮询 pet_state 表，检查 AI 是否有新推送
     */
    private fun pollState() {
        launch({
            try {
                val url = URL("$SUPABASE_URL/rest/v1/pet_state?order=updated_at.desc&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // 解析 JSON 数组
                val jsonArray = org.json.JSONArray(response)
                if (jsonArray.length() > 0) {
                    val state = jsonArray.getJSONObject(0)
                    val stateKey = state.optString("state_key")
                    val stateValue = state.optString("state_value")

                    when (stateKey) {
                        "mood" -> {
                            serviceRef?.pushFromAI(stateValue, null, null, null)
                        }
                        "speech" -> {
                            serviceRef?.pushFromAI(null, stateValue, null, null)
                        }
                        "heat" -> {
                            try {
                                serviceRef?.pushFromAI(null, null, null, stateValue.toInt())
                            } catch (_: Exception) {}
                        }
                        "full_state" -> {
                            // 复杂状态：JSON 格式
                            try {
                                val obj = JSONObject(stateValue)
                                serviceRef?.pushFromAI(
                                    obj.optString("mood", null),
                                    obj.optString("speech", null),
                                    obj.optString("style", null),
                                    if (obj.has("heat")) obj.getInt("heat") else null
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
                // 安静失败
            }
        })
    }

    fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(pollRunnable)
    }

    // ========================
    // HTTP 工具
    // ========================
    private fun postToSupabase(table: String, body: JSONObject) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
            // 安静失败 (离线等)
        }
    }

    private fun launch(block: () -> Unit) {
        Thread(block).start()
    }
}