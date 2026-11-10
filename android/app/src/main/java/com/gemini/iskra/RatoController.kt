package com.gemini.iskra

import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.roundToInt

class RatoController(
    private val prefs: SharedPreferences,
    private val shelly: ShellyClient,
    private val smartLife: SmartLifeClient,
    private val oukitel: OukitelClient,
    private val executor: ExecutorService,
    private val onStateChanged: () -> Unit
) {
    @Volatile var shutdownPhase: String = "IDLE" // IDLE, UNLOADING, COOLDOWN, KILLING, STOPPED, ERROR
    @Volatile var shutdownRemainingSec: Int = 0
    @Volatile var shutdownTotalSec: Int = 20
    @Volatile var shutdownMessage: String = ""
    @Volatile private var shutdownCancelled: Boolean = false

    var totalMotoSeconds: Long = prefs.getLong("total_moto_seconds", 2820L)
    var cooldownConfigSec: Int = prefs.getInt("cooldown_sec", 20)

    init {
        if (prefs.getLong("tank_full_mark_ms", 0L) == 0L) {
            val now = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(now))
            prefs.edit()
                .putLong("tank_full_mark_ms", now)
                .putLong("tank_full_mark_moto_sec", totalMotoSeconds)
                .putString("tank_full_mark_date", dateStr)
                .apply()
        }
    }

    fun markTankFull() {
        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(now))
        prefs.edit()
            .putLong("tank_full_mark_ms", now)
            .putLong("tank_full_mark_moto_sec", totalMotoSeconds)
            .putString("tank_full_mark_date", dateStr)
            .apply()
        onStateChanged()
    }

    fun addMotoSeconds(dtSec: Long) {
        if (dtSec > 0) {
            totalMotoSeconds += dtSec
            prefs.edit().putLong("total_moto_seconds", totalMotoSeconds).apply()
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
            if (o.has("markTankFull") && o.optBoolean("markTankFull", false)) {
                val now = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(now))
                ed.putLong("tank_full_mark_ms", now)
                ed.putLong("tank_full_mark_moto_sec", totalMotoSeconds)
                ed.putString("tank_full_mark_date", dateStr)
            }
            ed.apply()
            onStateChanged()
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
        onStateChanged()
    }

    fun markSparkChanged(usedHoursSinceChange: Double) {
        val usedSec = (max(0.0, usedHoursSinceChange) * 3600.0).toLong()
        val resetPoint = (totalMotoSeconds - usedSec).coerceAtLeast(0L)
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        prefs.edit()
            .putLong("gen_spark_reset_moto_sec", resetPoint)
            .putString("gen_spark_reset_date", if (usedHoursSinceChange > 0.1) "$dateStr (-${usedHoursSinceChange}ч)" else dateStr)
            .apply()
        onStateChanged()
    }

    fun startSmartShutdown(customCooldownSec: Int? = null) {
        if (shutdownPhase == "UNLOADING" || shutdownPhase == "COOLDOWN" || shutdownPhase == "KILLING") return
        val waitSec = (customCooldownSec ?: cooldownConfigSec).coerceIn(5, 120)
        cooldownConfigSec = waitSec
        prefs.edit().putInt("cooldown_sec", waitSec).apply()
        shutdownTotalSec = waitSec
        shutdownRemainingSec = waitSec
        shutdownCancelled = false
        shutdownPhase = "UNLOADING"
        shutdownMessage = "Фаза 1: Отключаем умную розетку линии Rato (socket_2)..."
        onStateChanged()

        executor.execute {
            try {
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
                        onStateChanged()
                        return@execute
                    }
                    shutdownRemainingSec = rem
                    shutdownMessage = "Фаза 2: Холостой ход без нагрузки ($rem сек)..."
                    onStateChanged()
                    Thread.sleep(1000L)
                }

                if (shutdownCancelled) {
                    shutdownPhase = "IDLE"
                    onStateChanged()
                    return@execute
                }

                executeShellyKillPulse()
            } catch (e: Exception) {
                shutdownPhase = "ERROR"
                shutdownMessage = "Ошибка при остановке: ${e.message}"
                onStateChanged()
            }
        }
    }

    fun killImmediately() {
        shutdownCancelled = true
        executor.execute {
            executor.execute {
                smartLife.sendSwitchCommand("socket_2", "switch_1", false)
            }
            executeShellyKillPulse()
        }
    }

    fun cancelShutdown() {
        shutdownCancelled = true
        shutdownPhase = "IDLE"
        shutdownRemainingSec = 0
        shutdownMessage = "Остановка отменена — включаем обратно розетку Rato (socket_2)"
        executor.execute {
            smartLife.sendSwitchCommand("socket_2", "switch_1", true)
            onStateChanged()
        }
    }

    private fun executeShellyKillPulse() {
        shutdownPhase = "KILLING"
        shutdownRemainingSec = 0
        shutdownMessage = "Фаза 3: Удерживаем глушение Shelly до полной остановки маховика..."
        onStateChanged()

        var anyOk = false
        var consecutiveDead = 0
        val startKillMs = System.currentTimeMillis()

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
            onStateChanged()

            if (anyOk && elapsedMs >= 3500L && consecutiveDead >= 4) {
                break
            }
            try { Thread.sleep(300L) } catch (_: Exception) {}
        }

        shelly.refresh()
        if (!anyOk && shelly.state.online) {
            shutdownPhase = "ERROR"
            shutdownMessage = "Не удалось достучаться до Shelly (${shelly.state.ip})!"
            onStateChanged()
            return
        }

        oukitel.refresh()

        shutdownPhase = "STOPPED"
        shutdownMessage = "Генератор Rato полностью заглушен!"
        onStateChanged()

        Thread.sleep(5000L)
        if (shutdownPhase == "STOPPED") {
            shutdownPhase = "IDLE"
            shutdownMessage = ""
            onStateChanged()
        }
    }

    fun buildRatoJson(genRunSec: Int): JSONObject {
        val sh = shelly.state
        val ratoRunning = sh.online
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

        val tankFullMarkMs = prefs.getLong("tank_full_mark_ms", System.currentTimeMillis())
        val tankFullMarkMotoSec = prefs.getLong("tank_full_mark_moto_sec", totalMotoSeconds)
        val tankFullUsedSec = max(0L, totalMotoSeconds - tankFullMarkMotoSec)
        val tankFullDate = prefs.getString("tank_full_mark_date", "10.11") ?: "10.11"

        return JSONObject().apply {
            put("running", ratoRunning)
            put("shellyOnline", sh.online)
            put("shellyIp", sh.ip)
            put("uptimeSec", genRunSec)
            put("tempC", sh.tempC)
            put("rssi", sh.rssi)
            put("relayOutput", sh.relayOutput)
            put("motoHours", (motoHours * 10.0).roundToInt() / 10.0)
            put("tankFullMarkMs", tankFullMarkMs)
            put("tankFullUsedSec", tankFullUsedSec)
            put("tankFullDate", tankFullDate)
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
        }
    }
}
