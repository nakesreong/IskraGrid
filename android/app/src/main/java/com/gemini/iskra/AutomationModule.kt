package com.gemini.iskra

import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

class AutomationModule(
    private val prefs: SharedPreferences,
    private val oukitel: OukitelClient,
    private val executor: ExecutorService,
    private val onStateChanged: () -> Unit
) {
    // 1. Soft Top-Off (LiFePO4 95% -> 25% quiet cell balancing)
    @Volatile var softTopOffEnabled: Boolean = prefs.getBoolean("auto_soft_topoff_enabled", true)
    @Volatile var softTopOffSoc: Int = prefs.getInt("auto_soft_topoff_soc", 95).coerceIn(80, 99)
    @Volatile var softTopOffTargetLimit: Int = prefs.getInt("auto_soft_topoff_limit", 25).coerceIn(0, 50)
    @Volatile private var softTopOffLatched: Boolean = prefs.getBoolean("auto_soft_topoff_latched", false)

    // 2. Cold Generator Start Warmup Guard (drops >75% to coldGenSafeLimit on transition to GENERATOR)
    @Volatile var genWarmupGuardEnabled: Boolean = prefs.getBoolean("auto_gen_warmup_enabled", true)
    @Volatile var coldGenSafeLimit: Int = prefs.getInt("auto_cold_gen_limit", 25).coerceIn(0, 50)

    // 3. Grid Restore Fast-Charge Boost (sets gridRestoreTargetLimit when DTEK Grid returns and battery < 90%)
    @Volatile var gridRestoreBoostEnabled: Boolean = prefs.getBoolean("auto_grid_restore_100_enabled", true)
    @Volatile var gridRestoreTargetLimit: Int = prefs.getInt("auto_grid_restore_limit", 100).coerceIn(25, 100)

    // 4. Generator Target SoC Alert (warns when battery reaches target SoC on Rato so fuel isn't wasted)
    @Volatile var genTargetAlertEnabled: Boolean = prefs.getBoolean("auto_gen_target_alert_enabled", true)
    @Volatile var genTargetSoc: Int = prefs.getInt("auto_gen_target_soc", 90).coerceIn(50, 100)
    @Volatile private var genTargetAlertLatched: Boolean = false

    @Volatile var lastEventText: String = prefs.getString("auto_last_event", "Ожидание событий автоматизации")
        ?: "Ожидание событий автоматизации"
    @Volatile var lastEventMs: Long = prefs.getLong("auto_last_event_ms", 0L)

    /**
     * Called when the user manually taps a charging limit button in the UI.
     * Human-in-the-loop rule: if the user manually selects >50% while battery is already >= softTopOffSoc,
     * we latch softTopOffLatched = true so automation NEVER overrides their manual choice!
     */
    fun onManualChargeLimitSet(limit: Int, currentSoc: Int) {
        if (currentSoc >= softTopOffSoc && limit > softTopOffTargetLimit) {
            softTopOffLatched = true
            prefs.edit().putBoolean("auto_soft_topoff_latched", true).apply()
        } else if (currentSoc < softTopOffSoc - 2) {
            softTopOffLatched = false
            prefs.edit().putBoolean("auto_soft_topoff_latched", false).apply()
        }
    }

    /**
     * Evaluates edge-triggered automation rules on each telemetry tick.
     */
    fun evaluate(prevMode: String, newMode: String, oukState: OukitelTelemetry) {
        val soc = oukState.batteryPct
        val curLimit = oukState.acChargingLimit

        // Reset latches when battery discharges below hysteresis thresholds
        if (softTopOffLatched && soc <= softTopOffSoc - 3) {
            softTopOffLatched = false
            prefs.edit().putBoolean("auto_soft_topoff_latched", false).apply()
        }
        if (genTargetAlertLatched && (newMode != "GENERATOR" || soc <= genTargetSoc - 3)) {
            genTargetAlertLatched = false
        }

        // Rule A: Transition into GENERATOR mode -> Warmup guard (only if limit was left high >75%)
        if (newMode != prevMode && newMode == "GENERATOR") {
            if (genWarmupGuardEnabled && curLimit > 75) {
                recordEvent("Старт Rato: лимит снижен с ${curLimit}% до ${coldGenSafeLimit}% (прогрев)")
                executor.execute {
                    try {
                        oukitel.setAcChargingLimit(coldGenSafeLimit)
                        onStateChanged()
                    } catch (_: Exception) {}
                }
                return
            }
        }

        // Rule B: Transition into GRID mode -> Fast-Charge boost if battery < softTopOffSoc - 2
        if (newMode != prevMode && newMode == "GRID") {
            if (gridRestoreBoostEnabled && soc in 1 until (softTopOffSoc - 2) && curLimit < gridRestoreTargetLimit) {
                recordEvent("Дали свет ДТЕК (заряд $soc%): авто-разгон зарядки до ${gridRestoreTargetLimit}%")
                executor.execute {
                    try {
                        oukitel.setAcChargingLimit(gridRestoreTargetLimit)
                        onStateChanged()
                    } catch (_: Exception) {}
                }
                return
            }
        }

        // Rule C: Soft Top-Off (LiFePO4 cell balancing at >= softTopOffSoc, e.g. 95% -> 25%)
        val isCharging = (newMode == "GRID" || newMode == "GENERATOR") && oukState.online && oukState.acInputW > 200
        if (softTopOffEnabled && !softTopOffLatched && isCharging && soc >= softTopOffSoc && curLimit > softTopOffTargetLimit) {
            softTopOffLatched = true
            prefs.edit().putBoolean("auto_soft_topoff_latched", true).apply()
            recordEvent("Мягкий финиш ($soc%): ток снижен с ${curLimit}% до ${softTopOffTargetLimit}% для балансировки")
            executor.execute {
                try {
                    oukitel.setAcChargingLimit(softTopOffTargetLimit)
                    onStateChanged()
                } catch (_: Exception) {}
            }
            return
        }

        // Rule D: Generator Target SoC Alert
        if (genTargetAlertEnabled && !genTargetAlertLatched && newMode == "GENERATOR" && soc >= genTargetSoc) {
            genTargetAlertLatched = true
            recordEvent("Генератор Rato зарядил станцию до $soc% — можно глушить!")
        }
    }

    fun updateConfig(jsonStr: String) {
        try {
            val o = JSONObject(jsonStr)
            val ed = prefs.edit()
            if (o.has("softTopOffEnabled")) {
                softTopOffEnabled = o.optBoolean("softTopOffEnabled", true)
                ed.putBoolean("auto_soft_topoff_enabled", softTopOffEnabled)
            }
            if (o.has("softTopOffSoc") || o.has("softTopOffThresholdPct")) {
                val v = if (o.has("softTopOffSoc")) o.optInt("softTopOffSoc", 95) else o.optInt("softTopOffThresholdPct", 95)
                softTopOffSoc = v.coerceIn(80, 99)
                ed.putInt("auto_soft_topoff_soc", softTopOffSoc)
            }
            if (o.has("softTopOffTargetLimit")) {
                softTopOffTargetLimit = o.optInt("softTopOffTargetLimit", 25).coerceIn(0, 50)
                ed.putInt("auto_soft_topoff_limit", softTopOffTargetLimit)
            }
            if (o.has("genWarmupGuardEnabled") || o.has("coldGenGuardEnabled")) {
                genWarmupGuardEnabled = if (o.has("genWarmupGuardEnabled")) {
                    o.optBoolean("genWarmupGuardEnabled", true)
                } else {
                    o.optBoolean("coldGenGuardEnabled", true)
                }
                ed.putBoolean("auto_gen_warmup_enabled", genWarmupGuardEnabled)
            }
            if (o.has("coldGenSafeLimit")) {
                coldGenSafeLimit = o.optInt("coldGenSafeLimit", 25).coerceIn(0, 50)
                ed.putInt("auto_cold_gen_limit", coldGenSafeLimit)
            }
            if (o.has("gridRestoreBoostEnabled")) {
                gridRestoreBoostEnabled = o.optBoolean("gridRestoreBoostEnabled", true)
                ed.putBoolean("auto_grid_restore_100_enabled", gridRestoreBoostEnabled)
            }
            if (o.has("gridRestoreTargetLimit")) {
                gridRestoreTargetLimit = o.optInt("gridRestoreTargetLimit", 100).coerceIn(25, 100)
                ed.putInt("auto_grid_restore_limit", gridRestoreTargetLimit)
            }
            if (o.has("genTargetAlertEnabled") || o.has("genTargetSocAlertEnabled")) {
                genTargetAlertEnabled = if (o.has("genTargetAlertEnabled")) {
                    o.optBoolean("genTargetAlertEnabled", true)
                } else {
                    o.optBoolean("genTargetSocAlertEnabled", true)
                }
                ed.putBoolean("auto_gen_target_alert_enabled", genTargetAlertEnabled)
            }
            if (o.has("genTargetSoc") || o.has("genTargetSocPct")) {
                val v = if (o.has("genTargetSoc")) o.optInt("genTargetSoc", 90) else o.optInt("genTargetSocPct", 90)
                genTargetSoc = v.coerceIn(50, 100)
                ed.putInt("auto_gen_target_soc", genTargetSoc)
            }
            // Reset latch if user just re-configured or enabled Soft Top-Off so it can immediately apply if >= threshold
            softTopOffLatched = false
            ed.putBoolean("auto_soft_topoff_latched", false)
            ed.apply()
            onStateChanged()
        } catch (_: Exception) {}
    }

    private fun recordEvent(msg: String) {
        val now = System.currentTimeMillis()
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        lastEventText = "$hm • $msg"
        lastEventMs = now
        prefs.edit()
            .putString("auto_last_event", lastEventText)
            .putLong("auto_last_event_ms", lastEventMs)
            .apply()
    }

    fun buildJson(currentMode: String, batteryPct: Int): JSONObject {
        val genReadyToStop = genTargetAlertEnabled && currentMode == "GENERATOR" && batteryPct >= genTargetSoc
        val cfgObj = JSONObject().apply {
            put("softTopOffEnabled", softTopOffEnabled)
            put("softTopOffThresholdPct", softTopOffSoc)
            put("softTopOffTargetLimit", softTopOffTargetLimit)
            put("coldGenGuardEnabled", genWarmupGuardEnabled)
            put("coldGenSafeLimit", coldGenSafeLimit)
            put("gridRestoreBoostEnabled", gridRestoreBoostEnabled)
            put("gridRestoreTargetLimit", gridRestoreTargetLimit)
            put("genTargetSocAlertEnabled", genTargetAlertEnabled)
            put("genTargetSocPct", genTargetSoc)
        }
        return JSONObject().apply {
            put("config", cfgObj)
            put("softTopOffEnabled", softTopOffEnabled)
            put("softTopOffSoc", softTopOffSoc)
            put("softTopOffTargetLimit", softTopOffTargetLimit)
            put("softTopOffActive", softTopOffLatched && batteryPct >= softTopOffSoc)
            put("genWarmupGuardEnabled", genWarmupGuardEnabled)
            put("coldGenSafeLimit", coldGenSafeLimit)
            put("gridRestoreBoostEnabled", gridRestoreBoostEnabled)
            put("gridRestoreTargetLimit", gridRestoreTargetLimit)
            put("genTargetAlertEnabled", genTargetAlertEnabled)
            put("genTargetSoc", genTargetSoc)
            put("genReadyToStop", genReadyToStop)
            put("lastEventText", lastEventText)
            put("lastEventMs", lastEventMs)
        }
    }
}
