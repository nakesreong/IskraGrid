package com.gemini.iskra

import android.content.Context
import org.json.JSONObject

data class IskraSecrets(
    val oukitelIp: String = "192.168.86.191",
    val oukitelEmail: String = "user@example.com",
    val oukitelPassword: String = "CHANGE_ME",
    val oukitelProductKey: String = "p11uve",
    val oukitelDeviceKey: String = "000000000000",
    val oukitelAuthKeyB64: String = "AAAAAAAAAAAAAAAAAAAAAA==",
    val shellyIp: String = "192.168.86.192",
    val tuyaAccessToken: String = "YOUR_TUYA_ACCESS_TOKEN",
    val tuyaRefreshToken: String = "YOUR_TUYA_REFRESH_TOKEN",
    val tuyaHomeId: String = "00000000",
    val socket1Id: String = "YOUR_SOCKET1_DEVICE_ID",
    val socket2Id: String = "YOUR_SOCKET2_DEVICE_ID",
    val snekStripId: String = "YOUR_TERRARIUM_STRIP_ID",
    val coAlarmId: String = "YOUR_CO_SENSOR_ID",
    val airboxId: String = "YOUR_AIRBOX_ID",
    val chandelierIds: List<String> = emptyList(),
    val backlightId: String = "YOUR_BACKLIGHT_ID",
    val ledBulbId: String = "YOUR_LED_BULB_ID",
    val purifierId: String = "YOUR_AIR_PURIFIER_ID",
    val dtekRegionId: Int = 25,
    val dtekDsoId: Int = 902,
    val dtekStreetId: Int = 0,
    val dtekHouseId: Int = 0,
    val dtekDefaultGroup: String = "33.1",
    val dtekAddressLabel: String = "Kyiv, UA"
)

object SecretsLoader {
    fun load(context: Context): IskraSecrets {
        return try {
            val raw = context.assets.open("secrets.json").bufferedReader().use { it.readText() }
            val o = JSONObject(raw)
            val chArr = o.optJSONArray("chandelierIds")
            val chList = mutableListOf<String>()
            if (chArr != null) {
                for (i in 0 until chArr.length()) {
                    val v = chArr.optString(i)
                    if (v.isNotBlank()) chList.add(v)
                }
            }
            IskraSecrets(
                oukitelIp = o.optString("oukitelIp", "192.168.86.191"),
                oukitelEmail = o.optString("oukitelEmail", "user@example.com"),
                oukitelPassword = o.optString("oukitelPassword", "CHANGE_ME"),
                oukitelProductKey = o.optString("oukitelProductKey", "p11uve"),
                oukitelDeviceKey = o.optString("oukitelDeviceKey", "000000000000"),
                oukitelAuthKeyB64 = o.optString("oukitelAuthKeyB64", "AAAAAAAAAAAAAAAAAAAAAA=="),
                shellyIp = o.optString("shellyIp", "192.168.86.192"),
                tuyaAccessToken = o.optString("tuyaAccessToken", ""),
                tuyaRefreshToken = o.optString("tuyaRefreshToken", ""),
                tuyaHomeId = o.optString("tuyaHomeId", "00000000"),
                socket1Id = o.optString("socket1Id", ""),
                socket2Id = o.optString("socket2Id", ""),
                snekStripId = o.optString("snekStripId", ""),
                coAlarmId = o.optString("coAlarmId", ""),
                airboxId = o.optString("airboxId", ""),
                chandelierIds = chList,
                backlightId = o.optString("backlightId", ""),
                ledBulbId = o.optString("ledBulbId", ""),
                purifierId = o.optString("purifierId", ""),
                dtekRegionId = o.optInt("dtekRegionId", 25),
                dtekDsoId = o.optInt("dtekDsoId", 902),
                dtekStreetId = o.optInt("dtekStreetId", 0),
                dtekHouseId = o.optInt("dtekHouseId", 0),
                dtekDefaultGroup = o.optString("dtekDefaultGroup", "33.1"),
                dtekAddressLabel = o.optString("dtekAddressLabel", "Kyiv, UA")
            )
        } catch (_: Exception) {
            IskraSecrets()
        }
    }
}
