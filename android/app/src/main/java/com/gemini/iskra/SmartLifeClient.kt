package com.gemini.iskra

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SmartPlugInfo(
    var id: String,
    var name: String,
    var online: Boolean = false,
    var switchOn: Boolean = false,
    var powerW: Double = 0.0,
    var voltageV: Double = 0.0,
    var currentMa: Int = 0
)

data class SmartLifeState(
    var cloudReachable: Boolean = false,
    var gridOnline: Boolean = false,
    var gridSource: String = "socket_1",
    var socket1: SmartPlugInfo = SmartPlugInfo("socket_1", "socket_1 (Grid)"),
    var socket2: SmartPlugInfo = SmartPlugInfo("socket_2", "socket_2 (Generator)"),
    var snekOnline: Boolean = true,
    var snekLight: Boolean = false,
    var snekHeat: Boolean = true,
    var wifiLamp: Boolean = true,
    var laptopPlug: Boolean = true,
    var coOnline: Boolean = true,
    var coStatus: String = "normal",
    var coPpm: Int = 0,
    var coBatteryPct: Int = 99,
    var roomTempC: Double = 21.2,
    var roomHumidityPct: Double = 71.0,
    var roomCo2Ppm: Int = 373,
    var backlightOn: Boolean = true,
    var ledBulbOn: Boolean = true,
    var purifierOn: Boolean = false,
    var chandelierOnlineCount: Int = 0,
    var lastUpdatedMs: Long = 0L
)

class SmartLifeClient(
    private val prefs: SharedPreferences,
    private val secrets: IskraSecrets
) {
    private val clientId = "HA_3y9q4ak7g4ephrvke"
    private val endpoint = "https://apigw.tuyaeu.com"

    private var accessToken: String = prefs.getString("tuya_access_token_v3", secrets.tuyaAccessToken) ?: secrets.tuyaAccessToken
    private var refreshToken: String = prefs.getString("tuya_refresh_token_v3", secrets.tuyaRefreshToken) ?: secrets.tuyaRefreshToken
    private var expireTimeMs: Long = prefs.getLong("tuya_expire_ms_v3", 1794238720048L + 7200_000L)
    private var refreshing = false

    val state = SmartLifeState()

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun randomNonce(len: Int = 12): String {
        val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        return (1..len).map { chars.random() }.joinToString("")
    }

    private fun aesGcmEncrypt(rawData: String, secret: String): String {
        val nonce = randomNonce(12)
        val nonceBytes = nonce.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, nonceBytes)
        )
        val encrypted = cipher.doFinal(rawData.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonceBytes, Base64.NO_WRAP) +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(cipherDataB64: String, secret: String): String {
        val raw = Base64.decode(cipherDataB64, Base64.DEFAULT)
        val nonce = raw.copyOfRange(0, 12)
        val cipherText = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, nonce)
        )
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun request(
        method: String,
        path: String,
        params: Map<String, String>? = null,
        bodyObj: JSONObject? = null,
        includeToken: Boolean = true
    ): JSONObject? {
        if (includeToken) refreshTokenIfNeeded()

        val rid = UUID.randomUUID().toString()
        val hashKey = md5Hex(rid + refreshToken)
        val secret = hmacSha256Hex(rid, hashKey).substring(0, 16)

        var queryEncData = ""
        var urlStr = endpoint + path
        if (!params.isNullOrEmpty()) {
            val pJson = JSONObject()
            for ((k, v) in params) pJson.put(k, v)
            queryEncData = aesGcmEncrypt(pJson.toString(), secret)
            urlStr += "?encdata=" + URLEncoder.encode(queryEncData, "UTF-8")
        }

        var bodyEncData = ""
        var requestBodyStr: String? = null
        if (bodyObj != null && bodyObj.length() > 0) {
            bodyEncData = aesGcmEncrypt(bodyObj.toString(), secret)
            requestBodyStr = JSONObject().put("encdata", bodyEncData).toString()
        }

        val t = System.currentTimeMillis().toString()
        val signBuilder = StringBuilder()
        signBuilder.append("X-appKey=").append(clientId).append("||")
        signBuilder.append("X-requestId=").append(rid).append("||")
        signBuilder.append("X-time=").append(t)
        if (includeToken && accessToken.isNotEmpty()) {
            signBuilder.append("||X-token=").append(accessToken)
        }
        if (queryEncData.isNotEmpty()) signBuilder.append(queryEncData)
        if (bodyEncData.isNotEmpty()) signBuilder.append(bodyEncData)

        val sign = hmacSha256Hex(hashKey, signBuilder.toString())

        val conn = (URL(urlStr).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("X-appKey", clientId)
            conn.setRequestProperty("X-requestId", rid)
            conn.setRequestProperty("X-sid", "")
            conn.setRequestProperty("X-time", t)
            if (includeToken && accessToken.isNotEmpty()) conn.setRequestProperty("X-token", accessToken)
            conn.setRequestProperty("X-sign", sign)
            if (requestBodyStr != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(requestBodyStr.toByteArray(Charsets.UTF_8)) }
            }

            if (conn.responseCode != 200) return null
            val ret = JSONObject(conn.inputStream.bufferedReader().readText())
            if (!ret.optBoolean("success", false)) {
                if (ret.optInt("code") == 1010 || ret.optInt("code") == 1011) {
                    expireTimeMs = 0L // Force token refresh on next call
                }
                return null
            }
            val encResult = ret.optString("result", "")
            if (encResult.isNotEmpty()) {
                val dec = aesGcmDecrypt(encResult, secret)
                val trimmed = dec.trim()
                if (trimmed.startsWith("[")) {
                    ret.put("resultArray", JSONArray(trimmed))
                } else if (trimmed.startsWith("{")) {
                    ret.put("resultObj", JSONObject(trimmed))
                }
            }
            ret
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun refreshTokenIfNeeded() {
        if (refreshing) return
        val now = System.currentTimeMillis()
        if (expireTimeMs - 120_000L > now) return

        refreshing = true
        try {
            val res = request("GET", "/v1.0/m/token/$refreshToken", includeToken = false)
            val obj = res?.optJSONObject("resultObj")
            if (obj != null) {
                accessToken = obj.optString("accessToken", accessToken)
                refreshToken = obj.optString("refreshToken", refreshToken)
                val expSec = obj.optLong("expireTime", 7200L)
                val serverT = res.optLong("t", now)
                expireTimeMs = serverT + expSec * 1000L
                prefs.edit()
                    .putString("tuya_access_token_v3", accessToken)
                    .putString("tuya_refresh_token_v3", refreshToken)
                    .putLong("tuya_expire_ms_v3", expireTimeMs)
                    .apply()
            }
        } catch (_: Exception) {
        } finally {
            refreshing = false
        }
    }

    fun resolveDeviceId(aliasOrId: String): String {
        return when (aliasOrId) {
            "socket_1" -> secrets.socket1Id
            "socket_2" -> secrets.socket2Id
            "snek_strip" -> secrets.snekStripId
            "backlight_1" -> secrets.backlightId
            "led_bulb" -> secrets.ledBulbId
            "air_purifier" -> secrets.purifierId
            else -> aliasOrId
        }
    }

    fun refresh(): SmartLifeState {
        if (secrets.tuyaHomeId.isBlank() || secrets.tuyaHomeId == "00000000") {
            state.cloudReachable = false
            return state
        }
        val res = request(
            "GET",
            "/v1.0/m/life/ha/home/devices",
            mapOf("homeId" to secrets.tuyaHomeId)
        )
        val arr = res?.optJSONArray("resultArray")
        if (arr == null) {
            state.cloudReachable = false
            return state
        }
        state.cloudReachable = true
        var chandelierOnline = 0

        for (i in 0 until arr.length()) {
            val dev = arr.getJSONObject(i)
            val id = dev.optString("id")
            val online = dev.optBoolean("online", false)
            val statusArr = dev.optJSONArray("status") ?: JSONArray()
            val statusMap = mutableMapOf<String, Any>()
            for (j in 0 until statusArr.length()) {
                val st = statusArr.getJSONObject(j)
                val code = st.optString("code")
                val value = st.opt("value")
                if (code.isNotEmpty() && value != null) {
                    statusMap[code] = value
                }
            }

            when {
                id == secrets.socket1Id -> { // socket_1 (Utility Grid)
                    state.socket1.online = online
                    if (online) {
                        state.socket1.switchOn = (statusMap["switch_1"] as? Boolean) ?: false
                        state.socket1.powerW = ((statusMap["cur_power"] as? Number)?.toDouble() ?: 0.0) / 10.0
                        state.socket1.voltageV = ((statusMap["cur_voltage"] as? Number)?.toDouble() ?: 0.0) / 10.0
                        state.socket1.currentMa = (statusMap["cur_current"] as? Number)?.toInt() ?: 0
                    } else {
                        state.socket1.switchOn = false
                        state.socket1.powerW = 0.0
                        state.socket1.voltageV = 0.0
                        state.socket1.currentMa = 0
                    }
                }
                id == secrets.socket2Id -> { // socket_2 (Generator)
                    state.socket2.online = online
                    if (online) {
                        state.socket2.switchOn = (statusMap["switch_1"] as? Boolean) ?: false
                        state.socket2.powerW = ((statusMap["cur_power"] as? Number)?.toDouble() ?: 0.0) / 10.0
                        state.socket2.voltageV = ((statusMap["cur_voltage"] as? Number)?.toDouble() ?: 0.0) / 10.0
                        state.socket2.currentMa = (statusMap["cur_current"] as? Number)?.toInt() ?: 0
                    } else {
                        state.socket2.switchOn = false
                        state.socket2.powerW = 0.0
                        state.socket2.voltageV = 0.0
                        state.socket2.currentMa = 0
                    }
                }
                id == secrets.snekStripId -> { // snek_power_strip
                    state.snekOnline = online
                    state.snekLight = (statusMap["switch_1"] as? Boolean) ?: state.snekLight
                    state.snekHeat = (statusMap["switch_2"] as? Boolean) ?: state.snekHeat
                    state.wifiLamp = (statusMap["switch_3"] as? Boolean) ?: state.wifiLamp
                    state.laptopPlug = (statusMap["switch_4"] as? Boolean) ?: state.laptopPlug
                }
                id == secrets.coAlarmId -> { // Carbon monoxide alarm
                    state.coOnline = online
                    state.coStatus = (statusMap["co_status"] as? String) ?: "normal"
                    state.coPpm = (statusMap["co_value"] as? Number)?.toInt() ?: 0
                    state.coBatteryPct = (statusMap["battery_percentage"] as? Number)?.toInt() ?: 99
                }
                id == secrets.airboxId -> { // airbox_1
                    if (online) {
                        state.roomTempC = ((statusMap["va_temperature"] as? Number)?.toDouble() ?: 212.0) / 10.0
                        state.roomHumidityPct = ((statusMap["va_humidity"] as? Number)?.toDouble() ?: 710.0) / 10.0
                        state.roomCo2Ppm = (statusMap["co2_value"] as? Number)?.toInt() ?: 373
                    }
                }
                secrets.chandelierIds.contains(id) -> {
                    if (online) chandelierOnline++
                }
                id == secrets.backlightId -> {
                    state.backlightOn = online && ((statusMap["switch_led"] as? Boolean) ?: false)
                }
                id == secrets.ledBulbId -> {
                    state.ledBulbOn = online && ((statusMap["switch_led"] as? Boolean) ?: false)
                }
                id == secrets.purifierId -> {
                    state.purifierOn = online && ((statusMap["switch"] as? Boolean) ?: false)
                }
            }
        }

        state.chandelierOnlineCount = chandelierOnline
        // City grid is strictly determined by socket_1 (the wall outlet on DTEK city grid)
        state.gridOnline = state.socket1.online
        state.gridSource = if (state.socket1.online) {
            "socket_1 (${state.socket1.voltageV}V)"
        } else {
            "socket_1 OFFLINE"
        }
        state.lastUpdatedMs = System.currentTimeMillis()
        return state
    }

    fun sendSwitchCommand(aliasOrDeviceId: String, code: String, value: Boolean): Boolean {
        val realId = resolveDeviceId(aliasOrDeviceId)
        if (realId.isBlank()) return false
        val cmdObj = JSONObject().apply {
            put("code", code)
            put("value", value)
        }
        val body = JSONObject().put("commands", JSONArray().put(cmdObj))
        val res = request("POST", "/v1.1/m/thing/$realId/commands", null, body)
        return res != null
    }
}
