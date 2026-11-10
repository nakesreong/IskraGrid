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
        authKeyB64 = secrets.oukitelAuthKeyB64
    )
    val shelly = ShellyClient(
        ip = prefs.getString("shelly_ip", secrets.shellyIp) ?: secrets.shellyIp
    )
    val smartLife = SmartLifeClient(prefs, secrets)

    // Smart Shutdown State Machine
    @Volatile var shutdownPhase: String = "IDLE" // IDLE, UNLOADING, COOLDOWN, KILLING, STOPPED, ERROR
    @Volatile var shutdownRemainingSec: Int = 0
    @Volatile var shutdownTotalSec: Int = 30
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
    var cooldownConfigSec: Int = prefs.getInt("cooldown_sec", 30)

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
        if (prefs.contains("dtek_schedule_json")) return
        val seed = """
        {
          "today": {
            "slots": [
              {"start": 0, "end": 120, "type": "NotPlanned"},
              {"start": 120, "end": 450, "type": "Definite"},
              {"start": 450, "end": 630, "type": "NotPlanned"},
              {"start": 630, "end": 1080, "type": "Definite"},
              {"start": 1080, "end": 1200, "type": "NotPlanned"},
              {"start": 1200, "end": 1440, "type": "Definite"}
            ],
            "date": "2026-11-09T00:00:00+02:00",
            "status": "ScheduleApplies"
          },
          "tomorrow": {
            "slots": [
              {"start": 0, "end": 210, "type": "Definite"},
              {"start": 210, "end": 450, "type": "NotPlanned"},
              {"start": 450, "end": 900, "type": "Definite"},
              {"start": 900, "end": 1050, "type": "NotPlanned"},
              {"start": 1050, "end": 1440, "type": "Definite"}
            ],
            "date": "2026-11-10T00:00:00+02:00",
            "status": "ScheduleApplies"
          },
          "updatedOn": "2026-11-09T19:43:29+00:00",
          "group": "${secrets.dtekDefaultGroup}",
          "address": "${secrets.dtekAddressLabel}",
          "fetchedAtMs": ${System.currentTimeMillis()}
        }
        """.trimIndent()
        prefs.edit().putString("dtek_schedule_json", seed).apply()
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
            val merged = JSONArray()
            var i = 0
            while (i < raw.length()) {
                val cur = raw.getJSONObject(i)
                val endMs = cur.optLong("endMs", 0L)
                if (endMs <= 1794235700000L) {
                    i++
                    continue
                }
                if (i + 2 < raw.length()) {
                    val mid = raw.getJSONObject(i + 1)
                    val nxt = raw.getJSONObject(i + 2)
                    val midDur = mid.optLong("endMs") - mid.optLong("startMs")
                    if (cur.optString("mode") == nxt.optString("mode") && midDur in 0..30_000L) {
                        cur.put("endMs", nxt.optLong("endMs"))
                        cur.put("endSoc", nxt.optInt("endSoc"))
                        merged.put(cur)
                        if (i + 2 == raw.length() - 1) {
                            modeStartMs = cur.optLong("startMs")
                            prefs.edit().putLong("mode_start_ms", modeStartMs).apply()
                        }
                        i += 3
                        continue
                    }
                }
                merged.put(cur)
                i++
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

    private fun reconcileLongGap(fromMs: Long, toMs: Long, resumedMode: String) {
        try {
            val arr = JSONArray(prefs.getString("power_intervals_v3", "[]"))
            val lastSoc = prefs.getInt("last_persisted_soc", 50)
            // If generator is running now and Shelly reports uptimeSec, split gap into [fromMs..genStart] and [genStart..toMs]
            if (resumedMode == "GENERATOR" && shelly.state.online && shelly.state.uptimeSec > 0) {
                val genStartMs = max(fromMs, toMs - shelly.state.uptimeSec * 1000L)
                if (genStartMs > fromMs + 60_000L) {
                    val offlineMode = if (lastSoc <= 5) "BLACKOUT_ZERO" else "BATTERY"
                    arr.put(JSONObject().apply {
                        put("mode", offlineMode)
                        put("startMs", fromMs)
                        put("endMs", genStartMs)
                        put("startSoc", lastSoc)
                        put("endSoc", if (offlineMode == "BLACKOUT_ZERO") 0 else lastSoc)
                        put("avgInW", 0)
                        put("avgOutW", if (offlineMode == "BLACKOUT_ZERO") 0 else 400)
                    })
                }
                arr.put(JSONObject().apply {
                    put("mode", "GENERATOR")
                    put("startMs", genStartMs)
                    put("endMs", toMs)
                    put("startSoc", lastSoc)
                    put("endSoc", oukitel.state.batteryPct)
                    put("avgInW", oukitel.state.totalInputW)
                    put("avgOutW", oukitel.state.totalOutputW)
                })
            } else {
                // Gap while everything was unpowered / offline
                val gapMode = if (lastSoc <= 5 || resumedMode == "BLACKOUT_ZERO") "BLACKOUT_ZERO" else currentMode
                arr.put(JSONObject().apply {
                    put("mode", gapMode)
                    put("startMs", fromMs)
                    put("endMs", toMs)
                    put("startSoc", lastSoc)
                    put("endSoc", oukitel.state.batteryPct)
                    put("avgInW", 0)
                    put("avgOutW", 0)
                })
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

            if (arr.length() > 0) {
                val last = arr.getJSONObject(arr.length() - 1)
                val lastMode = last.optString("mode")
                val lastEnd = last.optLong("endMs", nowMs)
                // If same mode and continuous within 5 minutes, extend interval
                if (lastMode == mode && (nowMs - lastEnd) <= 300_000L) {
                    last.put("endMs", nowMs)
                    if (soc > 0 || mode == "BLACKOUT_ZERO") {
                        last.put("endSoc", soc)
                    }
                    if (inW > 0) last.put("avgInW", inW)
                    if (outW > 0) last.put("avgOutW", outW)
                    prefs.edit().putString("power_intervals_v3", arr.toString()).apply()
                    return
                } else if ((nowMs - lastEnd) <= 300_000L) {
                    // Close previous interval cleanly at nowMs
                    last.put("endMs", nowMs)
                }
            }

            // Start new interval
            arr.put(JSONObject().apply {
                put("mode", mode)
                put("startMs", nowMs)
                put("endMs", nowMs)
                put("startSoc", soc)
                put("endSoc", soc)
                put("avgInW", inW)
                put("avgOutW", outW)
            })

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
        if (nowMs - lastSampleMs < 10_000L) return
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
            // Keep up to 1200 points for multi-hour / daily charting
            val startIdx = max(0, curveArr.length() - 1199)
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
        shutdownTotalSec = waitSec
        shutdownRemainingSec = waitSec
        shutdownCancelled = false
        shutdownPhase = "UNLOADING"
        shutdownMessage = "Фаза 1: Сбрасываем нагрузку зарядки Oukitel и socket_2..."
        notifySnapshot()

        executor.execute {
            try {
                oukitel.setAcChargingLimit(0)
                if (smartLife.state.socket2.online && smartLife.state.socket2.switchOn) {
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
                    shutdownMessage = "Фаза 2: Холостой ход и охлаждение Rato ($rem сек)..."
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
            oukitel.setAcChargingLimit(0)
            executeShellyKillPulse()
        }
    }

    fun cancelSmartShutdown() {
        shutdownCancelled = true
        shutdownPhase = "IDLE"
        shutdownRemainingSec = 0
        shutdownMessage = "Остановка отменена — возвращаем зарядку 100%"
        executor.execute {
            oukitel.setAcChargingLimit(100)
            notifySnapshot()
        }
    }

    private fun executeShellyKillPulse() {
        shutdownPhase = "KILLING"
        shutdownRemainingSec = 0
        shutdownMessage = "Фаза 3: Замыкаем реле глушения Shelly (192.168.86.192)..."
        notifySnapshot()

        var anyOk = false
        for (attempt in 1..5) {
            if (shelly.triggerKillRelay(true)) {
                anyOk = true
            }
            Thread.sleep(1800L)
            shelly.refresh()
            if (!shelly.state.online) {
                break
            }
        }

        if (!anyOk && shelly.state.online) {
            shutdownPhase = "ERROR"
            shutdownMessage = "Не удалось достучаться до Shelly (192.168.86.192)!"
            notifySnapshot()
            return
        }

        oukitel.refresh()

        shutdownPhase = "STOPPED"
        shutdownMessage = "Генератор Rato успешно заглушен!"
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
                    prefs.edit().putString("dtek_schedule_json", mySched.toString()).apply()
                    notifySnapshot()
                }
            }
            pConn.disconnect()
        } catch (_: Exception) {}
    }
}
