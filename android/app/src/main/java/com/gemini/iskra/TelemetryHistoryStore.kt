package com.gemini.iskra

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class TelemetryHistoryStore(
    private val prefs: SharedPreferences
) {
    private var lastSampleMs: Long = 0L

    init {
        initRealIntervalsV3()
        cleanPreShellyIntervalV4()
        healGlitchIntervals()
    }

    fun getLatestIntervalStartMs(fallbackMs: Long): Long {
        return try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            if (arr.length() > 0) {
                arr.getJSONObject(arr.length() - 1).optLong("startMs", fallbackMs)
            } else fallbackMs
        } catch (_: Exception) {
            fallbackMs
        }
    }

    fun healGlitchIntervals() {
        try {
            val raw = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val cleaned = mutableListOf<JSONObject>()
            for (i in 0 until raw.length()) {
                val cur = raw.getJSONObject(i)
                val s = cur.optLong("startMs", 0L)
                val e = cur.optLong("endMs", 0L)
                if (e <= 1794235670000L) continue
                val dur = e - s
                if (dur < 45_000L && i < raw.length() - 1) {
                    continue
                }
                cleaned.add(cur)
            }

            val merged = JSONArray()
            for (cur in cleaned) {
                if (merged.length() > 0) {
                    val prev = merged.getJSONObject(merged.length() - 1)
                    if (prev.optString("mode") == cur.optString("mode")) {
                        val prevDur = max(1L, prev.optLong("endMs") - prev.optLong("startMs"))
                        val curDur = max(1L, cur.optLong("endMs") - cur.optLong("startMs"))
                        val totDur = prevDur + curDur
                        val wIn = ((prev.optInt("avgInW") * prevDur + cur.optInt("avgInW") * curDur) / totDur).toInt()
                        val wOut = ((prev.optInt("avgOutW") * prevDur + cur.optInt("avgOutW") * curDur) / totDur).toInt()
                        prev.put("endMs", max(prev.optLong("endMs"), cur.optLong("endMs")))
                        prev.put("endSoc", cur.optInt("endSoc", prev.optInt("endSoc")))
                        if (wIn > 0) prev.put("avgInW", wIn)
                        if (wOut > 0) prev.put("avgOutW", wOut)
                        continue
                    } else if (cur.optLong("startMs") - prev.optLong("endMs") in 1..180_000L) {
                        prev.put("endMs", cur.optLong("startMs"))
                    }
                }
                merged.put(cur)
            }
            if (merged.length() > 0) {
                val last = merged.getJSONObject(merged.length() - 1)
                val modeStart = last.optLong("startMs", 0L)
                if (modeStart > 0L) {
                    prefs.edit().putLong("mode_start_ms", modeStart).apply()
                }
            }
            prefs.edit().putString("power_intervals_v3", merged.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun cleanPreShellyIntervalV4() {
        if (prefs.getBoolean("clean_pre_1648_v4", false)) return
        try {
            val raw = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val filtered = JSONArray()
            for (i in 0 until raw.length()) {
                val obj = raw.getJSONObject(i)
                val endMs = obj.optLong("endMs", 0L)
                if (endMs > 1794235700000L) {
                    filtered.put(obj)
                }
            }
            prefs.edit()
                .putString("power_intervals_v3", filtered.toString())
                .putBoolean("clean_pre_1648_v4", true)
                .apply()
        } catch (_: Exception) {}
    }

    private fun initRealIntervalsV3() {
        if (prefs.getBoolean("real_telemetry_v3", false)) return
        val now = System.currentTimeMillis()

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 15)
        val t1215 = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 16)
        cal.set(Calendar.MINUTE, 48)
        val t1648 = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 17)
        cal.set(Calendar.MINUTE, 35)
        val t1735 = cal.timeInMillis

        val intervals = JSONArray().apply {
            if (now > t1648) {
                put(JSONObject().apply {
                    put("mode", "BATTERY")
                    put("startMs", t1215)
                    put("endMs", t1648)
                    put("startSoc", 95)
                    put("endSoc", 29)
                    put("avgInW", 0)
                    put("avgOutW", 430)
                })
            }
            if (now > t1735) {
                put(JSONObject().apply {
                    put("mode", "GENERATOR")
                    put("startMs", t1648)
                    put("endMs", t1735)
                    put("startSoc", 29)
                    put("endSoc", 57)
                    put("avgInW", 945)
                    put("avgOutW", 445)
                })
                put(JSONObject().apply {
                    put("mode", "GRID")
                    put("startMs", t1735)
                    put("endMs", now)
                    put("startSoc", 57)
                    put("endSoc", 85)
                    put("avgInW", 1648)
                    put("avgOutW", 448)
                })
            }
        }

        val cleanCurve = JSONArray()
        try {
            val oldCurve = JSONArray(prefs.getString("telemetry_curve_json", "[]"))
            for (i in 0 until oldCurve.length()) {
                val pt = oldCurve.getJSONObject(i)
                val ts = pt.optLong("ts", now)
                if (ts >= t1735) {
                    pt.put("genV", 0.0)
                    pt.put("mode", "GRID")
                }
                cleanCurve.put(pt)
            }
        } catch (_: Exception) {}

        prefs.edit()
            .putString("power_intervals_v3", intervals.toString())
            .putString("telemetry_curve_json", cleanCurve.toString())
            .putString("last_persisted_mode", "GRID")
            .putLong("mode_start_ms", t1735)
            .putLong("last_persisted_tick_ms", now)
            .putLong("total_moto_seconds", 2820L)
            .putBoolean("real_telemetry_v3", true)
            .apply()
    }

    fun appendOrExtendInterval(
        arr: JSONArray,
        mode: String,
        startMs: Long,
        endMs: Long,
        startSoc: Int,
        endSoc: Int,
        inW: Int,
        outW: Int
    ) {
        if (arr.length() > 0) {
            val last = arr.getJSONObject(arr.length() - 1)
            val lastMode = last.optString("mode")
            if (lastMode == mode) {
                val prevDur = max(1L, last.optLong("endMs") - last.optLong("startMs"))
                val addDur = max(1L, endMs - last.optLong("endMs"))
                val totDur = prevDur + addDur
                last.put("endMs", max(last.optLong("endMs"), endMs))
                if (endSoc > 0 || mode == "BLACKOUT_ZERO") {
                    last.put("endSoc", endSoc)
                }
                if (inW > 0) {
                    val prevIn = last.optInt("avgInW", inW)
                    last.put("avgInW", ((prevIn * prevDur + inW * addDur) / totDur).toInt())
                }
                if (outW > 0) {
                    val prevOut = last.optInt("avgOutW", outW)
                    last.put("avgOutW", ((prevOut * prevDur + outW * addDur) / totDur).toInt())
                }
                return
            } else if (startMs - last.optLong("endMs", startMs) in 1..300_000L) {
                last.put("endMs", startMs)
            }
        }
        arr.put(JSONObject().apply {
            put("mode", mode)
            put("startMs", startMs)
            put("endMs", endMs)
            put("startSoc", startSoc)
            put("endSoc", endSoc)
            put("avgInW", inW)
            put("avgOutW", outW)
        })
    }

    fun reconcileLongGap(
        fromMs: Long,
        toMs: Long,
        resumedMode: String,
        currentMode: String,
        shellyState: ShellyTelemetry,
        oukState: OukitelTelemetry
    ) {
        try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val lastSoc = prefs.getInt("last_persisted_soc", 50)
            if (resumedMode == "GENERATOR" && shellyState.online && shellyState.uptimeSec > 0) {
                val genStartMs = max(fromMs, toMs - shellyState.uptimeSec * 1000L)
                if (genStartMs > fromMs + 60_000L) {
                    val offlineMode = if (lastSoc <= 5) "BLACKOUT_ZERO" else "BATTERY"
                    appendOrExtendInterval(
                        arr, offlineMode, fromMs, genStartMs,
                        lastSoc, if (offlineMode == "BLACKOUT_ZERO") 0 else lastSoc,
                        0, if (offlineMode == "BLACKOUT_ZERO") 0 else 400
                    )
                }
                appendOrExtendInterval(
                    arr, "GENERATOR", genStartMs, toMs,
                    lastSoc, oukState.batteryPct,
                    oukState.totalInputW, oukState.totalOutputW
                )
            } else {
                val gapMode = if (lastSoc <= 5 || resumedMode == "BLACKOUT_ZERO") "BLACKOUT_ZERO" else currentMode
                appendOrExtendInterval(
                    arr, gapMode, fromMs, toMs,
                    lastSoc, oukState.batteryPct,
                    oukState.totalInputW, oukState.totalOutputW
                )
            }
            prefs.edit().putString("power_intervals_v3", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun updatePowerIntervals(nowMs: Long, mode: String, oukState: OukitelTelemetry) {
        try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val soc = oukState.batteryPct
            val inW = oukState.totalInputW
            val outW = oukState.totalOutputW

            appendOrExtendInterval(arr, mode, nowMs, nowMs, soc, soc, inW, outW)

            val trimmed = if (arr.length() > 1500) {
                val next = JSONArray()
                for (i in (arr.length() - 1500) until arr.length()) {
                    next.put(arr.getJSONObject(i))
                }
                next
            } else arr

            prefs.edit().putString("power_intervals_v3", trimmed.toString()).apply()
        } catch (_: Exception) {}
    }

    fun recordTelemetrySample(
        nowMs: Long,
        mode: String,
        oukState: OukitelTelemetry,
        shellyState: ShellyTelemetry,
        smartState: SmartLifeState
    ) {
        if (nowMs - lastSampleMs < 20_000L) return
        lastSampleMs = nowMs

        try {
            val curveArr = JSONArray(prefs.getString("telemetry_curve_json", "[]"))
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val isZero = (mode == "BLACKOUT_ZERO")
            val pt = JSONObject().apply {
                put("t", sdf.format(Date(nowMs)))
                put("ts", nowMs)
                put("batt", if (isZero) 0 else oukState.batteryPct)
                put("inW", if (isZero) 0 else oukState.totalInputW)
                put("acInW", if (isZero) 0 else oukState.acInputW)
                put("outW", if (isZero) 0 else oukState.totalOutputW)
                put("genV", if (shellyState.online) smartState.socket2.voltageV else 0.0)
                put("gridV", if (smartState.gridOnline) smartState.socket1.voltageV else 0.0)
                put("tempC", oukState.tempC)
                put("mode", mode)
            }
            val nextArr = JSONArray()
            val startIdx = max(0, curveArr.length() - 4499)
            for (i in startIdx until curveArr.length()) {
                nextArr.put(curveArr.getJSONObject(i))
            }
            nextArr.put(pt)
            prefs.edit().putString("telemetry_curve_json", nextArr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun resetHistory(currentMode: String, oukState: OukitelTelemetry): Long {
        val now = System.currentTimeMillis()
        val soc = oukState.batteryPct
        val intervals = JSONArray().apply {
            put(JSONObject().apply {
                put("mode", currentMode)
                put("startMs", now)
                put("endMs", now)
                put("startSoc", soc)
                put("endSoc", soc)
                put("avgInW", oukState.totalInputW)
                put("avgOutW", oukState.totalOutputW)
            })
        }
        prefs.edit()
            .putString("power_intervals_v3", intervals.toString())
            .putString("telemetry_curve_json", "[]")
            .putLong("mode_start_ms", now)
            .putLong("last_persisted_tick_ms", now)
            .apply()
        return now
    }

    fun buildHistoryJson(): JSONObject {
        return JSONObject().apply {
            put("intervals", JSONArray(prefs.getString("power_intervals_v3", "[]")))
            put("curve", JSONArray(prefs.getString("telemetry_curve_json", "[]")))
        }
    }
}
