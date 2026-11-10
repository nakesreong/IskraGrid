package com.gemini.iskra

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class IskraEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("iskra_prefs", Context.MODE_PRIVATE)
    private val secrets: IskraSecrets = SecretsLoader.load(appContext)
    private val executor = Executors.newCachedThreadPool()

    val oukitel = OukitelClient(
        hostIp = prefs.getString("oukitel_ip", secrets.oukitelIp) ?: secrets.oukitelIp,
        email = secrets.oukitelEmail,
        password = secrets.oukitelPassword,
        productKey = secrets.oukitelProductKey,
        deviceKey = secrets.oukitelDeviceKey,
        authKeyB64 = secrets.oukitelAuthKeyB64,
        initialChargeLimit = prefs.getInt("oukitel_target_limit", 45),
        onLimitChanged = { prefs.edit().putInt("oukitel_target_limit", it).apply() }
    )
    val shelly = ShellyClient(
        ip = prefs.getString("shelly_ip", secrets.shellyIp) ?: secrets.shellyIp
    )
    val smartLife = SmartLifeClient(prefs, secrets)

    // Smart Shutdown State Machine
    @Volatile var shutdownPhase: String = "IDLE" // IDLE, UNLOADING, COOLDOWN, KILLING, STOPPED, ERROR
    @Volatile var shutdownRemainingSec: Int = 0
    @Volatile var shutdownTotalSec: Int = 20
    @Volatile var shutdownMessage: String = ""
    @Volatile private var shutdownCancelled: Boolean = false

    // Tracking timers & Moto-hours
    @Volatile private var isPolling: Boolean = false
    private var lastTickMs: Long = prefs.getLong("last_persisted_tick_ms", System.currentTimeMillis())
    private var lastSampleMs: Long = 0L
    private var lastDtekFetchMs: Long = 0L
    var currentMode: String = prefs.getString("last_persisted_mode", "GRID") ?: "GRID" // GRID, GENERATOR, BATTERY, BLACKOUT_ZERO
    var modeStartMs: Long = prefs.getLong("mode_start_ms", System.currentTimeMillis() - 25 * 60_000L)
    var outageStartMs: Long = prefs.getLong("outage_start_ms", System.currentTimeMillis())
    var totalMotoSeconds: Long = prefs.getLong("total_moto_seconds", 2820L)
    var cooldownConfigSec: Int = prefs.getInt("cooldown_sec", 20)

    var onStateUpdated: ((JSONObject) -> Unit)? = null

    init {
        initRealIntervalsV3()
        cleanPreShellyIntervalV4()
        healGlitchIntervals()
        seedDefaultDtekSchedule()
        executor.execute {
            fetchDtekSchedule()
        }
    }

    private fun seedDefaultDtekSchedule() {
        val slotsNov10 = """[{"start":0,"end":210,"type":"Definite"},{"start":210,"end":450,"type":"NotPlanned"},{"start":450,"end":900,"type":"Definite"},{"start":900,"end":1050,"type":"NotPlanned"},{"start":1050,"end":1440,"type":"Definite"}]"""
        if (!prefs.contains("dtek_slots_2026-11-10")) {
            prefs.edit().putString("dtek_slots_2026-11-10", slotsNov10).apply()
        }
        // Also ensure existing cached dtek_schedule_json has fallbackSlots attached if slots is empty
        val existing = prefs.getString("dtek_schedule_json", null)
        if (!existing.isNullOrEmpty()) {
            try {
                val obj = JSONObject(existing)
                attachFallbackSlots(obj)
                prefs.edit().putString("dtek_schedule_json", obj.toString()).apply()
            } catch (_: Exception) {}
            return
        }
        val seed = """
        {
          "today": {
            "slots": $slotsNov10,
            "date": "2026-11-10T00:00:00+02:00",
            "status": "ScheduleApplies"
          },
          "tomorrow": {
            "slots": [],
            "date": "2026-11-11T00:00:00+02:00",
            "status": "WaitingForSchedule"
          },
          "updatedOn": "2026-11-10T08:32:21+00:00",
          "group": "${secrets.dtekDefaultGroup}",
          "address": "${secrets.dtekAddressLabel}",
          "fetchedAtMs": ${System.currentTimeMillis()}
        }
        """.trimIndent()
        prefs.edit().putString("dtek_schedule_json", seed).apply()
    }

    private fun attachFallbackSlots(schedObj: JSONObject) {
        for (dayKey in listOf("today", "tomorrow")) {
            val dayObj = schedObj.optJSONObject(dayKey) ?: continue
            val dateIso = dayObj.optString("date", "").take(10)
            if (dateIso.isEmpty()) continue
            val slots = dayObj.optJSONArray("slots") ?: JSONArray()
            if (slots.length() > 0) {
                prefs.edit().putString("dtek_slots_$dateIso", slots.toString()).apply()
            } else {
                val cached = prefs.getString("dtek_slots_$dateIso", null)
                if (!cached.isNullOrEmpty()) {
                    try {
                        dayObj.put("fallbackSlots", JSONArray(cached))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun forceFetchDtekSchedule() {
        executor.execute {
            lastDtekFetchMs = System.currentTimeMillis()
            fetchDtekSchedule()
        }
    }

    fun saveGeneratorConfig(jsonStr: String) {
        try {
            val o = JSONObject(jsonStr)
            val ed = prefs.edit()
            if (o.has("model")) ed.putString("gen_model", o.optString("model", "Rato R3000 (R210 212cc)"))
            if (o.has("nomKw")) ed.putFloat("gen_nom_kw", o.optDouble("nomKw", 2.7).toFloat())
            if (o.has("maxKw")) ed.putFloat("gen_max_kw", o.optDouble("maxKw", 3.0).toFloat())
            if (o.has("tankL")) ed.putFloat("gen_tank_l", o.optDouble("tankL", 14.5).toFloat())
            if (o.has("oilVolL")) ed.putFloat("gen_oil_vol_l", o.optDouble("oilVolL", 0.55).toFloat())
            if (o.has("fuelFullLh")) ed.putFloat("gen_fuel_full_lh", o.optDouble("fuelFullLh", 1.8).toFloat())
            if (o.has("fuelHalfLh")) ed.putFloat("gen_fuel_half_lh", o.optDouble("fuelHalfLh", 1.2).toFloat())
            if (o.has("oilIntervalH")) ed.putInt("gen_oil_interval_h", o.optInt("oilIntervalH", 50))
            if (o.has("sparkIntervalH")) ed.putInt("gen_spark_interval_h", o.optInt("sparkIntervalH", 100))
            if (o.has("totalMotoHours") && !o.isNull("totalMotoHours")) {
                val mh = o.optDouble("totalMotoHours", -1.0)
                if (mh >= 0.0) {
                    totalMotoSeconds = (mh * 3600.0).toLong()
                    ed.putLong("total_moto_seconds", totalMotoSeconds)
                }
            }
            ed.apply()
            notifySnapshot()
        } catch (_: Exception) {}
    }

    fun markOilChanged(usedHoursSinceChange: Double) {
        val usedSec = (max(0.0, usedHoursSinceChange) * 3600.0).toLong()
        val resetPoint = (totalMotoSeconds - usedSec).coerceAtLeast(0L)
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        prefs.edit()
            .putLong("gen_oil_reset_moto_sec", resetPoint)
            .putString("gen_oil_reset_date", if (usedHoursSinceChange > 0.1) "$dateStr (-${usedHoursSinceChange}ч)" else dateStr)
            .apply()
        notifySnapshot()
    }

    fun markSparkChanged(usedHoursSinceChange: Double) {
        val usedSec = (max(0.0, usedHoursSinceChange) * 3600.0).toLong()
        val resetPoint = (totalMotoSeconds - usedSec).coerceAtLeast(0L)
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        prefs.edit()
            .putLong("gen_spark_reset_moto_sec", resetPoint)
            .putString("gen_spark_reset_date", if (usedHoursSinceChange > 0.1) "$dateStr (-${usedHoursSinceChange}ч)" else dateStr)
            .apply()
        notifySnapshot()
    }

    private fun healGlitchIntervals() {
        try {
            val raw = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            // Pass 1: Filter out pre-Shelly noise and micro-glitches (< 45s) unless it is the very last active interval
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

            // Pass 2: Merge adjacent intervals with the exact same mode (and stitch across any remaining tiny gaps)
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
                modeStartMs = last.optLong("startMs", modeStartMs)
                prefs.edit().putLong("mode_start_ms", modeStartMs).apply()
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
                // Remove any interval that ended at or before 16:48 (1794235680000L) before first Shelly start
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

    companion object {
        @Volatile private var instance: IskraEngine? = null
        fun getInstance(context: Context): IskraEngine {
            return instance ?: synchronized(this) {
                instance ?: IskraEngine(context).also { instance = it }
            }
        }
    }

    private fun initRealIntervalsV3() {
        if (prefs.getBoolean("real_telemetry_v3", false)) return
        val now = System.currentTimeMillis()

        // Construct today's real timestamps (09.11.2026)
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

        // Clean bogus 233.9V from existing curve points when Rato was off
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

        currentMode = "GRID"
        modeStartMs = t1735
        lastTickMs = now

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

    fun resetTelemetryHistory() {
        val now = System.currentTimeMillis()
        val soc = oukitel.state.batteryPct
        val intervals = JSONArray().apply {
            put(JSONObject().apply {
                put("mode", currentMode)
                put("startMs", now)
                put("endMs", now)
                put("startSoc", soc)
                put("endSoc", soc)
                put("avgInW", oukitel.state.totalInputW)
                put("avgOutW", oukitel.state.totalOutputW)
            })
        }
        modeStartMs = now
        prefs.edit()
            .putString("power_intervals_v3", intervals.toString())
            .putString("telemetry_curve_json", "[]")
            .putLong("mode_start_ms", now)
            .putLong("last_persisted_tick_ms", now)
            .apply()
        notifySnapshot()
    }

    fun pollAllSync(): JSONObject {
        if (isPolling) {
            val snap = buildStateSnapshot()
            onStateUpdated?.invoke(snap)
            return snap
        }
        isPolling = true
        try {
            val now = System.currentTimeMillis()
            val fOukitel = executor.submit { oukitel.refresh() }
            val fShelly = executor.submit { shelly.refresh() }
            val fSmart = executor.submit { smartLife.refresh() }
            if (now - lastDtekFetchMs > 300_000L || !prefs.contains("dtek_schedule_json")) {
                lastDtekFetchMs = now
                executor.execute { fetchDtekSchedule() }
            }

            try { fOukitel.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            try { fShelly.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            try { fSmart.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}

            // CRITICAL FIX 1: socket_2 is physically plugged into the Rato generator!
            // If Shelly (192.168.86.192 on the generator) is offline, socket_2 is 100% unpowered (обесточен)!
            if (!shelly.state.online) {
                smartLife.state.socket2.online = false
                smartLife.state.socket2.switchOn = false
                smartLife.state.socket2.voltageV = 0.0
                smartLife.state.socket2.powerW = 0.0
                smartLife.state.socket2.currentMa = 0
            }

            // CRITICAL FIX 2: Instant DTEK blackout detection via Oukitel LAN input!
            // When Rato is off, Oukitel is plugged into socket_1 (DTEK). Even at 100% SOC, UPS bypass draws ~400W.
            // The moment DTEK cuts power, Oukitel totalInputW drops to 0W on LAN within 1s, whereas Tuya Cloud lags ~45s.
            val ratoRunning = shelly.state.online
            if (!ratoRunning && oukitel.state.online && oukitel.state.totalInputW < 25 && oukitel.state.totalOutputW > 20) {
                smartLife.state.socket1.online = false
                smartLife.state.socket1.switchOn = false
                smartLife.state.socket1.voltageV = 0.0
                smartLife.state.socket1.powerW = 0.0
                smartLife.state.socket1.currentMa = 0
                smartLife.state.gridOnline = false
            }

            // Determine 4 exact physical states (works both with Internet and in 100% Offline LAN mode!)
            val gridUp = if (smartLife.state.cloudReachable) {
                smartLife.state.gridOnline || (!ratoRunning && oukitel.state.online && oukitel.state.totalInputW > 120)
            } else {
                // Internet is down: if Shelly is off and Oukitel receives >120W AC input, it's from DTEK Grid!
                !ratoRunning && oukitel.state.online && oukitel.state.totalInputW > 120
            }
            smartLife.state.gridOnline = gridUp

            val allDeadZero = !ratoRunning && !gridUp &&
                (!oukitel.state.online || (oukitel.state.batteryPct <= 1 && !oukitel.state.acSwitch && oukitel.state.totalOutputW == 0))

            val newMode = when {
                ratoRunning -> "GENERATOR"
                gridUp -> "GRID"
                allDeadZero -> "BLACKOUT_ZERO"
                else -> "BATTERY"
            }

            val prevTick = lastTickMs
            val gapMs = (now - prevTick).coerceAtLeast(0L)
            val dtSec = (gapMs / 1000L).coerceIn(0L, 120L)
            lastTickMs = now

            // Reconcile long offline gap (e.g., everything died to 0% or phone/router was off)
            if (gapMs > 120_000L) {
                reconcileLongGap(prevTick, now, newMode)
            }

            if (newMode != currentMode) {
                // On cold generator start, if limit was left at 100% (Grid), set to 25% warmup once.
                // Never override manual user choices while running!
                if (newMode == "GENERATOR" && oukitel.state.acChargingLimit > 75) {
                    executor.execute {
                        try { oukitel.setAcChargingLimit(25) } catch (_: Exception) {}
                    }
                }
                currentMode = newMode
                modeStartMs = now
                prefs.edit()
                    .putLong("mode_start_ms", modeStartMs)
                    .putString("last_persisted_mode", currentMode)
                    .apply()
            }

            if (gridUp) {
                outageStartMs = now
                prefs.edit().putLong("outage_start_ms", outageStartMs).apply()
            }

            if (ratoRunning && dtSec > 0) {
                totalMotoSeconds += dtSec
                prefs.edit().putLong("total_moto_seconds", totalMotoSeconds).apply()
            }

            updatePowerIntervals(now, newMode)
            recordTelemetrySample(now, newMode)

            prefs.edit()
                .putLong("last_persisted_tick_ms", now)
                .putString("last_persisted_mode", newMode)
                .putInt("last_persisted_soc", oukitel.state.batteryPct)
                .apply()

            val snap = buildStateSnapshot()
            onStateUpdated?.invoke(snap)
            return snap
        } finally {
            isPolling = false
        }
    }

    private fun appendOrExtendInterval(
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

    private fun reconcileLongGap(fromMs: Long, toMs: Long, resumedMode: String) {
        try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val lastSoc = prefs.getInt("last_persisted_soc", 50)
            // If generator is running now and Shelly reports uptimeSec, split gap into [fromMs..genStart] and [genStart..toMs]
            if (resumedMode == "GENERATOR" && shelly.state.online && shelly.state.uptimeSec > 0) {
                val genStartMs = max(fromMs, toMs - shelly.state.uptimeSec * 1000L)
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
                    lastSoc, oukitel.state.batteryPct,
                    oukitel.state.totalInputW, oukitel.state.totalOutputW
                )
            } else {
                val gapMode = if (lastSoc <= 5 || resumedMode == "BLACKOUT_ZERO") "BLACKOUT_ZERO" else currentMode
                appendOrExtendInterval(
                    arr, gapMode, fromMs, toMs,
                    lastSoc, oukitel.state.batteryPct,
                    oukitel.state.totalInputW, oukitel.state.totalOutputW
                )
            }
            prefs.edit().putString("power_intervals_v3", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun updatePowerIntervals(nowMs: Long, mode: String) {
        try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val soc = oukitel.state.batteryPct
            val inW = oukitel.state.totalInputW
            val outW = oukitel.state.totalOutputW

            appendOrExtendInterval(arr, mode, nowMs, nowMs, soc, soc, inW, outW)

            // Keep up to 1500 intervals (~months of minute-exact transitions)
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

    private fun recordTelemetrySample(nowMs: Long, mode: String) {
        if (nowMs - lastSampleMs < 20_000L) return
        lastSampleMs = nowMs

        try {
            val curveArr = JSONArray(prefs.getString("telemetry_curve_json", "[]"))
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val isZero = (mode == "BLACKOUT_ZERO")
            val pt = JSONObject().apply {
                put("t", sdf.format(Date(nowMs)))
                put("ts", nowMs)
                put("batt", if (isZero) 0 else oukitel.state.batteryPct)
                put("inW", if (isZero) 0 else oukitel.state.totalInputW)
                put("acInW", if (isZero) 0 else oukitel.state.acInputW)
                put("outW", if (isZero) 0 else oukitel.state.totalOutputW)
                put("genV", if (shelly.state.online) smartLife.state.socket2.voltageV else 0.0)
                put("gridV", if (smartLife.state.gridOnline) smartLife.state.socket1.voltageV else 0.0)
                put("tempC", oukitel.state.tempC)
                put("mode", mode)
            }
            val nextArr = JSONArray()
            // Keep up to 4500 points (~25+ hours at 20s resolution)
            val startIdx = max(0, curveArr.length() - 4499)
            for (i in startIdx until curveArr.length()) {
                nextArr.put(curveArr.getJSONObject(i))
            }
            nextArr.put(pt)
            prefs.edit().putString("telemetry_curve_json", nextArr.toString()).apply()
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------
    // Smart 2-Phase Rato Shutdown Sequence
    // -----------------------------------------------------------------------

    fun startSmartRatoShutdown(customCooldownSec: Int? = null) {
        if (shutdownPhase == "UNLOADING" || shutdownPhase == "COOLDOWN" || shutdownPhase == "KILLING") return
        val waitSec = (customCooldownSec ?: cooldownConfigSec).coerceIn(5, 120)
        cooldownConfigSec = waitSec
        prefs.edit().putInt("cooldown_sec", waitSec).apply()
        shutdownTotalSec = waitSec
        shutdownRemainingSec = waitSec
        shutdownCancelled = false
        shutdownPhase = "UNLOADING"
        shutdownMessage = "Фаза 1: Отключаем умную розетку линии Rato (socket_2)..."
        notifySnapshot()

        executor.execute {
            try {
                // Отключаем только умную розетку socket_2, не сбрасывая выбранный лимит зарядки на станции
                val s2Off = smartLife.sendSwitchCommand("socket_2", "switch_1", false)
                if (!s2Off) {
                    try { Thread.sleep(300L) } catch (_: Exception) {}
                    smartLife.sendSwitchCommand("socket_2", "switch_1", false)
                }

                shutdownPhase = "COOLDOWN"
                for (rem in waitSec downTo 1) {
                    if (shutdownCancelled) {
                        shutdownPhase = "IDLE"
                        shutdownMessage = "Остановка генератора отменена"
                        notifySnapshot()
                        return@execute
                    }
                    shutdownRemainingSec = rem
                    shutdownMessage = "Фаза 2: Холостой ход без нагрузки ($rem сек)..."
                    notifySnapshot()
                    Thread.sleep(1000L)
                }

                if (shutdownCancelled) {
                    shutdownPhase = "IDLE"
                    notifySnapshot()
                    return@execute
                }

                executeShellyKillPulse()
            } catch (e: Exception) {
                shutdownPhase = "ERROR"
                shutdownMessage = "Ошибка при остановке: ${e.message}"
                notifySnapshot()
            }
        }
    }

    fun killRatoImmediately() {
        shutdownCancelled = true
        executor.execute {
            // Сразу отключаем линию Rato (socket_2), не трогая процент зарядки станции, и зажимаем Shelly
            executor.execute {
                smartLife.sendSwitchCommand("socket_2", "switch_1", false)
            }
            executeShellyKillPulse()
        }
    }

    fun cancelSmartShutdown() {
        shutdownCancelled = true
        shutdownPhase = "IDLE"
        shutdownRemainingSec = 0
        shutdownMessage = "Остановка отменена — включаем обратно розетку Rato (socket_2)"
        executor.execute {
            smartLife.sendSwitchCommand("socket_2", "switch_1", true)
            notifySnapshot()
        }
    }

    private fun executeShellyKillPulse() {
        shutdownPhase = "KILLING"
        shutdownRemainingSec = 0
        shutdownMessage = "Фаза 3: Удерживаем глушение Shelly до полной остановки маховика..."
        notifySnapshot()

        var anyOk = false
        var consecutiveDead = 0
        val startKillMs = System.currentTimeMillis()

        // Безотрывная очередь удержания (каждые 300 мс на протяжении ~7.5 сек + toggle_after=10),
        // чтобы реле Shelly ни на миллисекунду не размыкалось, пока маховик Rato крутится по инерции
        for (pulse in 1..25) {
            val ok = shelly.triggerKillRelay(on = true, toggleAfterSec = 10, timeoutMs = 900)
            if (ok) {
                anyOk = true
                consecutiveDead = 0
            } else {
                consecutiveDead++
            }
            val elapsedMs = System.currentTimeMillis() - startKillMs
            shutdownMessage = "Фаза 3: Удержание глушения Shelly (импульс #$pulse)..."
            notifySnapshot()

            // Если реле уже схватило (anyOk) и после 3.5+ сек удержания Shelly перестал отвечать 4 раза подряд —
            // значит альтернатор генератора полностью остановился и обесточил Shelly
            if (anyOk && elapsedMs >= 3500L && consecutiveDead >= 4) {
                break
            }
            try { Thread.sleep(300L) } catch (_: Exception) {}
        }

        shelly.refresh()
        if (!anyOk && shelly.state.online) {
            shutdownPhase = "ERROR"
            shutdownMessage = "Не удалось достучаться до Shelly (${shelly.state.ip})!"
            notifySnapshot()
            return
        }

        oukitel.refresh()

        shutdownPhase = "STOPPED"
        shutdownMessage = "Генератор Rato полностью заглушен!"
        notifySnapshot()

        Thread.sleep(5000L)
        if (shutdownPhase == "STOPPED") {
            shutdownPhase = "IDLE"
            shutdownMessage = ""
            notifySnapshot()
        }
    }

    fun notifySnapshot() {
        onStateUpdated?.invoke(buildStateSnapshot())
    }

    // -----------------------------------------------------------------------
    // Build Complete JSON State for UI & Foreground Notification
    // -----------------------------------------------------------------------

    fun buildStateSnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val ouk = oukitel.state
        val sh = shelly.state
        val sl = smartLife.state

        val ratoRunning = sh.online
        val genRunSec = if (sh.online && sh.uptimeSec > 0) {
            sh.uptimeSec
        } else if (ratoRunning) {
            ((now - modeStartMs) / 1000L).toInt().coerceAtLeast(0)
        } else 0

        val darkSec = if (sl.gridOnline) 0L else ((now - outageStartMs) / 1000L).coerceAtLeast(0L)
        val modeSec = ((now - modeStartMs) / 1000L).coerceAtLeast(0L)

        val cycleTargetSec = if (currentMode == "GENERATOR") 3600 else 7200
        val cycleElapsedSec = if (currentMode == "GENERATOR") genRunSec else modeSec.toInt()
        val cycleProgressPct = ((cycleElapsedSec.toDouble() / cycleTargetSec.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)

        val motoHours = totalMotoSeconds / 3600.0
        val nomKw = prefs.getFloat("gen_nom_kw", 2.7f).toDouble().coerceAtLeast(0.5)
        val maxKw = prefs.getFloat("gen_max_kw", 3.0f).toDouble().coerceAtLeast(nomKw)
        val tankL = prefs.getFloat("gen_tank_l", 14.5f).toDouble()
        val oilVolL = prefs.getFloat("gen_oil_vol_l", 0.55f).toDouble()
        val fuelFullLh = prefs.getFloat("gen_fuel_full_lh", 1.8f).toDouble().coerceAtLeast(0.2)
        val fuelHalfLh = prefs.getFloat("gen_fuel_half_lh", 1.2f).toDouble().coerceAtLeast(0.1)
        val oilIntervalH = prefs.getInt("gen_oil_interval_h", 50).coerceAtLeast(10)
        val sparkIntervalH = prefs.getInt("gen_spark_interval_h", 100).coerceAtLeast(20)
        val genModel = prefs.getString("gen_model", "Rato R3000 (R210 212cc)") ?: "Rato R3000 (R210 212cc)"

        val oilResetMotoSec = prefs.getLong("gen_oil_reset_moto_sec", 0L)
        val sparkResetMotoSec = prefs.getLong("gen_spark_reset_moto_sec", 0L)
        val oilUsedHours = max(0.0, (totalMotoSeconds - oilResetMotoSec) / 3600.0)
        val sparkUsedHours = max(0.0, (totalMotoSeconds - sparkResetMotoSec) / 3600.0)
        val oilRemainingHours = max(0.0, oilIntervalH.toDouble() - oilUsedHours)
        val sparkRemainingHours = max(0.0, sparkIntervalH.toDouble() - sparkUsedHours)
        val estimatedFuelLiters = motoHours * fuelHalfLh

        val internetOnline = sl.cloudReachable || ouk.connectionMode.contains("Cloud", ignoreCase = true)

        return JSONObject().apply {
            put("timestamp", now)
            put("currentMode", currentMode)
            put("internetOnline", internetOnline)
            put("modeDurationSec", modeSec)
            put("darkDurationSec", darkSec)
            put("cycleTargetSec", cycleTargetSec)
            put("cycleElapsedSec", cycleElapsedSec)
            put("cycleProgressPct", cycleProgressPct)

            put("oukitel", JSONObject().apply {
                put("online", ouk.online)
                put("connectionMode", ouk.connectionMode)
                put("hostIp", ouk.hostIp)
                put("batteryPct", ouk.batteryPct)
                put("totalInputW", ouk.totalInputW)
                put("totalOutputW", ouk.totalOutputW)
                put("acInputW", ouk.acInputW)
                put("dcInputW", ouk.dcInputW)
                put("acOutputW", ouk.acOutputW)
                put("dcOutputW", ouk.dcOutputW)
                put("remainMinutes", ouk.remainMinutes)
                put("remainChargeMinutes", ouk.remainChargeMinutes)
                put("tempC", ouk.tempC)
                put("acSwitch", ouk.acSwitch)
                put("dcSwitch", ouk.dcSwitch)
                put("ledStatus", ouk.ledStatus)
                put("acChargingLimit", ouk.acChargingLimit)
                put("wifiSignal", ouk.wifiSignal)
            })

            put("rato", JSONObject().apply {
                put("running", ratoRunning)
                put("shellyOnline", sh.online)
                put("shellyIp", sh.ip)
                put("uptimeSec", genRunSec)
                put("tempC", sh.tempC)
                put("rssi", sh.rssi)
                put("relayOutput", sh.relayOutput)
                put("motoHours", (motoHours * 10.0).roundToInt() / 10.0)
                put("oilUsedHours", (oilUsedHours * 10.0).roundToInt() / 10.0)
                put("oilRemainingHours", (oilRemainingHours * 10.0).roundToInt() / 10.0)
                put("oilResetDate", prefs.getString("gen_oil_reset_date", "С начала учёта") ?: "С начала учёта")
                put("sparkUsedHours", (sparkUsedHours * 10.0).roundToInt() / 10.0)
                put("sparkRemainingHours", (sparkRemainingHours * 10.0).roundToInt() / 10.0)
                put("sparkResetDate", prefs.getString("gen_spark_reset_date", "Прошлая зима") ?: "Прошлая зима")
                put("fuelLiters", (estimatedFuelLiters * 10.0).roundToInt() / 10.0)
                put("cooldownConfigSec", cooldownConfigSec)
                put("shutdownPhase", shutdownPhase)
                put("shutdownRemainingSec", shutdownRemainingSec)
                put("shutdownTotalSec", shutdownTotalSec)
                put("shutdownMessage", shutdownMessage)
                put("config", JSONObject().apply {
                    put("model", genModel)
                    put("nomKw", nomKw)
                    put("maxKw", maxKw)
                    put("tankL", tankL)
                    put("oilVolL", oilVolL)
                    put("fuelFullLh", fuelFullLh)
                    put("fuelHalfLh", fuelHalfLh)
                    put("oilIntervalH", oilIntervalH)
                    put("sparkIntervalH", sparkIntervalH)
                })
            })

            put("smartLife", JSONObject().apply {
                put("cloudReachable", sl.cloudReachable)
                put("gridOnline", sl.gridOnline)
                put("gridSource", sl.gridSource)
                put("chandelierOnlineCount", sl.chandelierOnlineCount)
                put("socket1", JSONObject().apply {
                    put("online", sl.socket1.online)
                    put("switchOn", sl.socket1.switchOn)
                    put("powerW", sl.socket1.powerW)
                    put("voltageV", sl.socket1.voltageV)
                })
                put("socket2", JSONObject().apply {
                    // Strictly 0 when Shelly is offline!
                    val s2Up = sh.online && sl.socket2.online
                    put("online", s2Up)
                    put("switchOn", s2Up && sl.socket2.switchOn)
                    put("powerW", if (s2Up) sl.socket2.powerW else 0.0)
                    put("voltageV", if (s2Up) sl.socket2.voltageV else 0.0)
                })
                put("snek", JSONObject().apply {
                    put("online", sl.snekOnline)
                    put("light", sl.snekLight)
                    put("heat", sl.snekHeat)
                    put("wifiLamp", sl.wifiLamp)
                    put("laptop", sl.laptopPlug)
                })
                put("co", JSONObject().apply {
                    put("online", sl.coOnline)
                    put("status", sl.coStatus)
                    put("ppm", sl.coPpm)
                    put("batteryPct", sl.coBatteryPct)
                })
                put("climate", JSONObject().apply {
                    put("tempC", sl.roomTempC)
                    put("humidityPct", sl.roomHumidityPct)
                    put("co2Ppm", sl.roomCo2Ppm)
                })
                put("backlightOn", sl.backlightOn)
                put("ledBulbOn", sl.ledBulbOn)
                put("purifierOn", sl.purifierOn)
            })

            put("history", JSONObject().apply {
                put("intervals", JSONArray(prefs.getString("power_intervals_v3", "[]")))
                put("curve", JSONArray(prefs.getString("telemetry_curve_json", "[]")))
            })

            val dtekRaw = prefs.getString("dtek_schedule_json", null)
            if (!dtekRaw.isNullOrEmpty()) {
                try {
                    put("dtekSchedule", JSONObject(dtekRaw))
                } catch (_: Exception) {}
            }
        }
    }

    private fun fetchDtekSchedule() {
        try {
            var groupKey = prefs.getString("dtek_group_key", secrets.dtekDefaultGroup) ?: secrets.dtekDefaultGroup
            if (secrets.dtekStreetId > 0 && secrets.dtekHouseId > 0) {
                try {
                    val gUrl = "https://app.yasno.ua/api/blackout-service/public/shutdowns/addresses/v2/group" +
                        "?regionId=${secrets.dtekRegionId}&streetId=${secrets.dtekStreetId}&houseId=${secrets.dtekHouseId}&dsoId=${secrets.dtekDsoId}"
                    val gConn = java.net.URL(gUrl).openConnection() as java.net.HttpURLConnection
                    gConn.connectTimeout = 5000
                    gConn.readTimeout = 5000
                    gConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (gConn.responseCode == 200) {
                        val gObj = JSONObject(gConn.inputStream.bufferedReader().readText())
                        val g = gObj.optInt("group", 33)
                        val sg = gObj.optInt("subgroup", 1)
                        groupKey = "$g.$sg"
                        prefs.edit().putString("dtek_group_key", groupKey).apply()
                    }
                    gConn.disconnect()
                } catch (_: Exception) {}
            }

            // 2. Fetch planned-outages for DTEK region/DSO
            val pUrl = "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/${secrets.dtekRegionId}/dsos/${secrets.dtekDsoId}/planned-outages"
            val pConn = java.net.URL(pUrl).openConnection() as java.net.HttpURLConnection
            pConn.connectTimeout = 6000
            pConn.readTimeout = 6000
            pConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (pConn.responseCode == 200) {
                val allObj = JSONObject(pConn.inputStream.bufferedReader().readText())
                val mySched = allObj.optJSONObject(groupKey) ?: allObj.optJSONObject(secrets.dtekDefaultGroup)
                if (mySched != null) {
                    mySched.put("group", groupKey)
                    mySched.put("address", secrets.dtekAddressLabel)
                    mySched.put("fetchedAtMs", System.currentTimeMillis())
                    attachFallbackSlots(mySched)
                    prefs.edit().putString("dtek_schedule_json", mySched.toString()).apply()
                    notifySnapshot()
                }
            }
            pConn.disconnect()
        } catch (_: Exception) {}
    }
}
