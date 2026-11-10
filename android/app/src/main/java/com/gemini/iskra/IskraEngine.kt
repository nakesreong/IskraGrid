package com.gemini.iskra

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    val historyStore = TelemetryHistoryStore(prefs)
    val dtekManager = DtekScheduleManager(prefs, secrets, executor) { notifySnapshot() }
    val ratoController = RatoController(prefs, shelly, smartLife, oukitel, executor) { notifySnapshot() }
    val automation = AutomationModule(prefs, oukitel, executor) { notifySnapshot() }

    @Volatile private var isPolling: Boolean = false
    private var lastTickMs: Long = prefs.getLong("last_persisted_tick_ms", System.currentTimeMillis())
    var currentMode: String = prefs.getString("last_persisted_mode", "GRID") ?: "GRID"
    var modeStartMs: Long = historyStore.getLatestIntervalStartMs(
        prefs.getLong("mode_start_ms", System.currentTimeMillis() - 25 * 60_000L)
    )
    var outageStartMs: Long = prefs.getLong("outage_start_ms", System.currentTimeMillis())

    var onStateUpdated: ((JSONObject) -> Unit)? = null

    companion object {
        @Volatile private var instance: IskraEngine? = null
        fun getInstance(context: Context): IskraEngine {
            return instance ?: synchronized(this) {
                instance ?: IskraEngine(context).also { instance = it }
            }
        }
    }

    // Delegated methods for MainActivity / IskraBridge compatibility
    fun forceFetchDtekSchedule() = dtekManager.forceFetch()
    fun saveGeneratorConfig(jsonStr: String) = ratoController.saveGeneratorConfig(jsonStr)
    fun markOilChanged(usedHoursSinceChange: Double) = ratoController.markOilChanged(usedHoursSinceChange)
    fun markSparkChanged(usedHoursSinceChange: Double) = ratoController.markSparkChanged(usedHoursSinceChange)
    fun startSmartRatoShutdown(customCooldownSec: Int? = null) = ratoController.startSmartShutdown(customCooldownSec)
    fun killRatoImmediately() = ratoController.killImmediately()
    fun cancelSmartShutdown() = ratoController.cancelShutdown()
    fun saveAutomationConfig(jsonStr: String) = automation.updateConfig(jsonStr)

    fun setManualChargeLimit(limit: Int) {
        automation.onManualChargeLimitSet(limit, oukitel.state.batteryPct)
        oukitel.setAcChargingLimit(limit)
        notifySnapshot()
    }

    fun resetTelemetryHistory() {
        modeStartMs = historyStore.resetHistory(currentMode, oukitel.state)
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
            dtekManager.pollIfNeeded(now)

            try { fOukitel.get(8, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { fShelly.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { fSmart.get(8, TimeUnit.SECONDS) } catch (_: Exception) {}

            // CRITICAL FIX 1: socket_2 is physically plugged into the Rato generator!
            if (!shelly.state.online) {
                smartLife.state.socket2.online = false
                smartLife.state.socket2.switchOn = false
                smartLife.state.socket2.voltageV = 0.0
                smartLife.state.socket2.powerW = 0.0
                smartLife.state.socket2.currentMa = 0
            }

            // CRITICAL FIX 2: Instant DTEK blackout detection via Oukitel LAN input!
            val ratoRunning = shelly.state.online
            if (!ratoRunning && oukitel.state.online && oukitel.state.totalInputW < 25 && oukitel.state.totalOutputW > 20) {
                smartLife.state.socket1.online = false
                smartLife.state.socket1.switchOn = false
                smartLife.state.socket1.voltageV = 0.0
                smartLife.state.socket1.powerW = 0.0
                smartLife.state.socket1.currentMa = 0
                smartLife.state.gridOnline = false
            }

            val gridUp = if (smartLife.state.cloudReachable) {
                smartLife.state.gridOnline || (!ratoRunning && oukitel.state.online && oukitel.state.totalInputW > 120)
            } else {
                !ratoRunning && oukitel.state.online && oukitel.state.totalInputW > 120
            }
            smartLife.state.gridOnline = gridUp

            val lastPersistedSoc = prefs.getInt("last_persisted_soc", 50)
            val effectiveSoc = if (oukitel.state.online) oukitel.state.batteryPct else lastPersistedSoc

            // CRITICAL: Do NOT flip into BLACKOUT_ZERO on a transient phone Wi-Fi <-> LTE handover
            // when Oukitel still had plenty of battery (> 5%)! Only enter BLACKOUT_ZERO when the station
            // actually drained to <= 1% with AC off, or went offline after already draining to <= 5%.
            val allDeadZero = !ratoRunning && !gridUp && (
                (oukitel.state.online && oukitel.state.batteryPct <= 1 && !oukitel.state.acSwitch && oukitel.state.totalOutputW == 0) ||
                (!oukitel.state.online && lastPersistedSoc <= 5)
            )

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

            if (gapMs > 120_000L) {
                historyStore.reconcileLongGap(prevTick, now, newMode, currentMode, shelly.state, oukitel.state)
            }

            val prevMode = currentMode
            if (newMode != currentMode) {
                currentMode = newMode
                modeStartMs = now
                prefs.edit()
                    .putLong("mode_start_ms", modeStartMs)
                    .putString("last_persisted_mode", currentMode)
                    .apply()
            }

            // Evaluate edge-triggered automation rules (Warmup guard, Grid boost, Soft Top-Off 95%->25%)
            automation.evaluate(prevMode, newMode, oukitel.state)

            if (gridUp) {
                outageStartMs = now
                prefs.edit().putLong("outage_start_ms", outageStartMs).apply()
            }

            if (ratoRunning && dtSec > 0) {
                ratoController.addMotoSeconds(dtSec)
            }

            historyStore.updatePowerIntervals(now, newMode, oukitel.state, effectiveSoc)
            // Keep modeStartMs strictly synchronized with the active merged interval start!
            modeStartMs = historyStore.getLatestIntervalStartMs(modeStartMs)
            historyStore.recordTelemetrySample(now, newMode, oukitel.state, shelly.state, smartLife.state, effectiveSoc)

            val ed = prefs.edit()
                .putLong("last_persisted_tick_ms", now)
                .putLong("mode_start_ms", modeStartMs)
                .putString("last_persisted_mode", newMode)
            if (oukitel.state.online && oukitel.state.batteryPct > 0) {
                ed.putInt("last_persisted_soc", oukitel.state.batteryPct)
            }
            ed.apply()

            val snap = buildStateSnapshot()
            onStateUpdated?.invoke(snap)
            return snap
        } finally {
            isPolling = false
        }
    }

    fun notifySnapshot() {
        onStateUpdated?.invoke(buildStateSnapshot())
    }

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

            put("rato", ratoController.buildRatoJson(genRunSec))
            put("automation", automation.buildJson(currentMode, ouk.batteryPct))

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

            put("history", historyStore.buildHistoryJson())

            dtekManager.getScheduleJson()?.let { put("dtekSchedule", it) }
        }
    }
}
