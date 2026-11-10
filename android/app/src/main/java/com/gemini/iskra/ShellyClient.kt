package com.gemini.iskra

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ShellyTelemetry(
    var online: Boolean = false,
    var ip: String = "192.168.86.192",
    var uptimeSec: Int = 0,
    var tempC: Double = 0.0,
    var rssi: Int = -60,
    var relayOutput: Boolean = false,
    var lastUpdatedMs: Long = 0L
)

class ShellyClient(
    private var ip: String = "192.168.86.192"
) {
    val state = ShellyTelemetry(ip = ip)
    private var consecutiveFailures = 0

    fun setIp(newIp: String) {
        if (newIp.isNotBlank()) {
            ip = newIp.trim()
            state.ip = ip
        }
    }

    fun refresh(): ShellyTelemetry {
        val conn = (URL("http://$ip/rpc/Shelly.GetStatus").openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 2200
            conn.readTimeout = 2200
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val sys = json.optJSONObject("sys")
                val sw0 = json.optJSONObject("switch:0")
                val wifi = json.optJSONObject("wifi")
                consecutiveFailures = 0
                state.online = true
                state.uptimeSec = sys?.optInt("uptime", 0) ?: 0
                state.relayOutput = sw0?.optBoolean("output", false) ?: false
                state.tempC = sw0?.optJSONObject("temperature")?.optDouble("tC", 0.0) ?: 0.0
                state.rssi = wifi?.optInt("rssi", -60) ?: -60
                state.lastUpdatedMs = System.currentTimeMillis()
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    state.online = false
                    state.uptimeSec = 0
                    state.relayOutput = false
                }
            }
            state
        } catch (_: Exception) {
            consecutiveFailures++
            if (consecutiveFailures >= 3) {
                state.online = false
                state.uptimeSec = 0
                state.relayOutput = false
            }
            state
        } finally {
            conn.disconnect()
        }
    }

    fun triggerKillRelay(on: Boolean = true, toggleAfterSec: Int = 10, timeoutMs: Int = 1000): Boolean {
        val url = if (on && toggleAfterSec > 0) {
            "http://$ip/rpc/Switch.Set?id=0&on=true&toggle_after=$toggleAfterSec"
        } else {
            "http://$ip/rpc/Switch.Set?id=0&on=$on"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val ok = conn.responseCode == 200
            if (ok) state.relayOutput = on
            ok
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
