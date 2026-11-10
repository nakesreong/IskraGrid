package com.gemini.iskra

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

class DtekScheduleManager(
    private val prefs: SharedPreferences,
    private val secrets: IskraSecrets,
    private val executor: ExecutorService,
    private val onStateChanged: () -> Unit
) {
    private var lastFetchMs: Long = 0L

    init {
        seedDefaultSchedule()
        executor.execute {
            fetchScheduleSync()
        }
    }

    fun pollIfNeeded(nowMs: Long) {
        if (nowMs - lastFetchMs > 300_000L || !prefs.contains("dtek_schedule_json")) {
            lastFetchMs = nowMs
            executor.execute { fetchScheduleSync() }
        }
    }

    fun forceFetch() {
        executor.execute {
            lastFetchMs = System.currentTimeMillis()
            fetchScheduleSync()
        }
    }

    fun getScheduleJson(): JSONObject? {
        val raw = prefs.getString("dtek_schedule_json", null)
        if (!raw.isNullOrEmpty()) {
            try {
                return JSONObject(raw)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun seedDefaultSchedule() {
        val slotsNov10 = """[{"start":0,"end":210,"type":"Definite"},{"start":210,"end":450,"type":"NotPlanned"},{"start":450,"end":900,"type":"Definite"},{"start":900,"end":1050,"type":"NotPlanned"},{"start":1050,"end":1440,"type":"Definite"}]"""
        if (!prefs.contains("dtek_slots_2026-11-10")) {
            prefs.edit().putString("dtek_slots_2026-11-10", slotsNov10).apply()
        }
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

    private fun fetchScheduleSync() {
        try {
            var groupKey = prefs.getString("dtek_group_key", secrets.dtekDefaultGroup) ?: secrets.dtekDefaultGroup
            if (secrets.dtekStreetId > 0 && secrets.dtekHouseId > 0) {
                try {
                    val gUrl = "https://app.yasno.ua/api/blackout-service/public/shutdowns/addresses/v2/group" +
                        "?regionId=${secrets.dtekRegionId}&streetId=${secrets.dtekStreetId}&houseId=${secrets.dtekHouseId}&dsoId=${secrets.dtekDsoId}"
                    val gConn = URL(gUrl).openConnection() as HttpURLConnection
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

            val pUrl = "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/${secrets.dtekRegionId}/dsos/${secrets.dtekDsoId}/planned-outages"
            val pConn = URL(pUrl).openConnection() as HttpURLConnection
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
                    onStateChanged()
                }
            }
            pConn.disconnect()
        } catch (_: Exception) {}
    }
}
