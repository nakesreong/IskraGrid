package com.gemini.iskra

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class OukitelTelemetry(
    var online: Boolean = false,
    var connectionMode: String = "Offline",
    var hostIp: String = "192.168.86.191",
    var batteryPct: Int = 0,
    var totalInputW: Int = 0,
    var totalOutputW: Int = 0,
    var acInputW: Int = 0,
    var dcInputW: Int = 0,
    var acOutputW: Int = 0,
    var dcOutputW: Int = 0,
    var remainMinutes: Int = 0,
    var remainChargeMinutes: Int = 0,
    var tempC: Int = 0,
    var acSwitch: Boolean = true,
    var dcSwitch: Boolean = false,
    var ledStatus: Int = 0,
    var acChargingLimit: Int = 100,
    var wifiSignal: Int = -45,
    var lastUpdatedMs: Long = 0L
)

class OukitelClient(
    private var hostIp: String = "192.168.86.191",
    private val email: String = "user@example.com",
    private val password: String = "CHANGE_ME",
    private val productKey: String = "p11uve",
    private val deviceKey: String = "000000000000",
    private var authKeyB64: String = "AAAAAAAAAAAAAAAAAAAAAA==",
    initialChargeLimit: Int = 45,
    private val onLimitChanged: ((Int) -> Unit)? = null
) {
    private val baseUrl = "https://iot-api.acceleronix.io"
    private val userDomain = "E.SP.4294967410"
    private val userDomainSecret = "3aRNUwWahjyANa7WfBK2wCCkxCexB6nXxKJwXxfePvzf"
    private val appId = "277"
    private val appVersion = "3.7.5"

    private var accessToken: String? = null
    private var tokenExpirySec: Long = 0L
    private var lastWakeMs: Long = 0L
    @Volatile private var targetChargeLimit: Int = initialChargeLimit
    private val lanLock = Any()

    val state = OukitelTelemetry(hostIp = hostIp, acChargingLimit = initialChargeLimit)

    fun setHostIp(ip: String) {
        if (ip.isNotBlank()) {
            hostIp = ip.trim()
            state.hostIp = hostIp
        }
    }

    // -----------------------------------------------------------------------
    // Binary LAN Framing & TTLV Helpers (Port 6607 / 6606)
    // -----------------------------------------------------------------------

    private fun authKeyBytes(): ByteArray = Base64.decode(authKeyB64, Base64.DEFAULT)

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(input.toByteArray(Charsets.UTF_8)))
    }

    private fun md5HexUpper(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return bytesToHex(md.digest(input.toByteArray(Charsets.UTF_8))).uppercase()
    }

    private fun aesEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val padLen = 16 - (plain.size % 16)
        val padded = ByteArray(plain.size + padLen)
        System.arraycopy(plain, 0, padded, 0, plain.size)
        for (i in plain.size until padded.size) padded[i] = padLen.toByte()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(padded)
    }

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val raw = cipher.doFinal(encrypted)
        if (raw.isEmpty()) return raw
        val n = raw.last().toInt() and 0xFF
        if (n in 1..16 && raw.size >= n) {
            var valid = true
            for (i in raw.size - n until raw.size) {
                if ((raw[i].toInt() and 0xFF) != n) {
                    valid = false
                    break
                }
            }
            if (valid) return raw.copyOfRange(0, raw.size - n)
        }
        return raw
    }

    private fun stuffFrame(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 8)
        out.write(raw, 0, 2)
        for (j in 2 until raw.size) {
            val b = raw[j].toInt() and 0xFF
            out.write(b)
            if (b == 0xAA && j + 1 < raw.size) {
                val next = raw[j + 1].toInt() and 0xFF
                if (next == 0x55 || next == 0xAA) {
                    out.write(0x55)
                }
            }
        }
        return out.toByteArray()
    }

    private fun destuffBytes(data: ByteArray, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        var sawAa = false
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            if (sawAa && b == 0x55) {
                sawAa = false
                continue
            }
            out.write(b)
            sawAa = (b == 0xAA)
        }
        return out.toByteArray()
    }

    private fun buildFrame(packetId: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val inner = ByteArray(4 + payload.size)
        inner[0] = ((packetId ushr 8) and 0xFF).toByte()
        inner[1] = (packetId and 0xFF).toByte()
        inner[2] = ((cmd ushr 8) and 0xFF).toByte()
        inner[3] = (cmd and 0xFF).toByte()
        System.arraycopy(payload, 0, inner, 4, payload.size)

        var sum = 0
        for (b in inner) sum += (b.toInt() and 0xFF)
        val checksum = sum and 0xFF
        val bodyLen = inner.size + 1

        val raw = ByteArray(5 + inner.size)
        raw[0] = 0xAA.toByte()
        raw[1] = 0xAA.toByte()
        raw[2] = ((bodyLen ushr 8) and 0xFF).toByte()
        raw[3] = (bodyLen and 0xFF).toByte()
        raw[4] = checksum.toByte()
        System.arraycopy(inner, 0, raw, 5, inner.size)
        return stuffFrame(raw)
    }

    private fun parseFrames(buf: ByteArray): List<Triple<Int, Int, ByteArray>> {
        val frames = mutableListOf<Triple<Int, Int, ByteArray>>()
        var i = 0
        while (i + 9 <= buf.size) {
            if ((buf[i].toInt() and 0xFF) != 0xAA || (buf[i + 1].toInt() and 0xFF) != 0xAA) {
                i++
                continue
            }
            val bodyLen = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
            val total = 4 + bodyLen
            if (i + total > buf.size) break
            val pid = ((buf[i + 5].toInt() and 0xFF) shl 8) or (buf[i + 6].toInt() and 0xFF)
            val cmd = ((buf[i + 7].toInt() and 0xFF) shl 8) or (buf[i + 8].toInt() and 0xFF)
            val payload = buf.copyOfRange(i + 9, i + total)
            frames.add(Triple(pid, cmd, payload))
            i += total
        }
        return frames
    }

    private fun ttlvEncodeBool(tag: Int, value: Boolean): ByteArray {
        val h = (tag shl 3) or (if (value) 1 else 0)
        return byteArrayOf(((h ushr 8) and 0xFF).toByte(), (h and 0xFF).toByte())
    }

    private fun ttlvEncodeInt(tag: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val h = (tag shl 3) or 2
        out.write((h ushr 8) and 0xFF)
        out.write(h and 0xFF)
        val neg = value < 0
        val v = kotlin.math.abs(value)
        val body = when {
            v == 0 -> byteArrayOf(0)
            v <= 0xFF -> byteArrayOf(v.toByte())
            v <= 0xFFFF -> byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            else -> byteArrayOf(
                ((v ushr 24) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                (v and 0xFF).toByte()
            )
        }
        val ctrl = (if (neg) 0x80 else 0) or ((body.size - 1) and 0x07)
        out.write(ctrl)
        out.write(body)
        return out.toByteArray()
    }

    private fun ttlvDecode(buf: ByteArray): Map<Int, Any> {
        val out = mutableMapOf<Int, Any>()
        var i = 0
        val n = buf.size
        while (i + 2 <= n) {
            val h = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            val tag = (h ushr 3) and 0x1FFF
            val typ = h and 0x07
            when (typ) {
                0, 1 -> out[tag] = (typ == 1)
                2 -> {
                    if (i >= n) break
                    val ctrl = buf[i].toInt() and 0xFF
                    i++
                    val sign = (ctrl ushr 7) and 1
                    val nbytes = (ctrl and 0x07) + 1
                    if (i + nbytes > n) break
                    var v = 0
                    for (b in 0 until nbytes) {
                        v = (v shl 8) or (buf[i + b].toInt() and 0xFF)
                    }
                    i += nbytes
                    if (sign == 1) v = -v
                    out[tag] = v
                }
                3, 5 -> {
                    if (i + 2 > n) break
                    val ln = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                    i += 2
                    if (i + ln > n) break
                    out[tag] = String(buf, i, ln, Charsets.UTF_8)
                    i += ln
                }
                4 -> {
                    if (i + 2 > n) break
                    val count = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                    i += 2
                    val sub = mutableMapOf<Int, Any>()
                    for (c in 0 until count) {
                        if (i + 2 > n) break
                        val h2 = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                        i += 2
                        val t2 = (h2 ushr 3) and 0x1FFF
                        val ty2 = h2 and 0x07
                        when (ty2) {
                            0, 1 -> sub[t2] = (ty2 == 1)
                            2 -> {
                                if (i >= n) break
                                val ctrl2 = buf[i].toInt() and 0xFF
                                i++
                                val sign2 = (ctrl2 ushr 7) and 1
                                val nbytes2 = (ctrl2 and 0x07) + 1
                                if (i + nbytes2 > n) break
                                var v2 = 0
                                for (b in 0 until nbytes2) {
                                    v2 = (v2 shl 8) or (buf[i + b].toInt() and 0xFF)
                                }
                                i += nbytes2
                                if (sign2 == 1) v2 = -v2
                                sub[t2] = v2
                            }
                            3, 5 -> {
                                if (i + 2 > n) break
                                val ln2 = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                                i += 2
                                if (i + ln2 > n) break
                                sub[t2] = String(buf, i, ln2, Charsets.UTF_8)
                                i += ln2
                            }
                        }
                    }
                    out[tag] = sub
                }
                else -> break
            }
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Direct LAN Session (Read & Write on TCP 6607)
    // -----------------------------------------------------------------------

    fun discoverOnLan(): String? {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 1200
            val probe = buildFrame(1000, 28720)
            val targets = listOf("255.255.255.255", "192.168.86.255")
            for (t in targets) {
                val addr = InetAddress.getByName(t)
                sock.send(DatagramPacket(probe, probe.size, addr, 6606))
            }
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            val clean = destuffBytes(pkt.data, pkt.length)
            for ((_, cmd, payload) in parseFrames(clean)) {
                if (cmd == 28721) {
                    val fields = ttlvDecode(payload)
                    val mac = (fields[4] as? String)?.lowercase()
                    val ip = (fields[5] as? String) ?: pkt.address.hostAddress
                    if (mac == deviceKey.lowercase() && !ip.isNullOrBlank()) {
                        hostIp = ip
                        state.hostIp = ip
                        return ip
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return null
    }

    private fun performLanTransaction(writePayload: ByteArray? = null): Boolean = synchronized(lanLock) {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(hostIp, 6607), 2200)
            sock.soTimeout = 2000
            val ins: InputStream = sock.getInputStream()
            val outs: OutputStream = sock.getOutputStream()
            val key = authKeyBytes()

            // 1. Send HELLO (28722)
            outs.write(buildFrame(1001, 28722))
            outs.flush()

            val rawBuf = ByteArray(4096)
            val n1 = ins.read(rawBuf)
            if (n1 <= 0) return false
            var nonce: String? = null
            for ((_, cmd, payload) in parseFrames(destuffBytes(rawBuf, n1))) {
                if (cmd == 28723) {
                    val fields = ttlvDecode(payload)
                    nonce = fields.values.firstOrNull { it is String } as? String
                }
            }
            if (nonce.isNullOrEmpty()) return false
            val iv = nonce.toByteArray(Charsets.UTF_8)

            // 2. Send LOGIN (28724)
            val tokBytes = sha256Hex(bytesToHex(key) + ";" + nonce).toByteArray(Charsets.UTF_8)
            val loginBody = ByteArray(4 + tokBytes.size)
            val hTag = (2 shl 3) or 3
            loginBody[0] = ((hTag ushr 8) and 0xFF).toByte()
            loginBody[1] = (hTag and 0xFF).toByte()
            loginBody[2] = ((tokBytes.size ushr 8) and 0xFF).toByte()
            loginBody[3] = (tokBytes.size and 0xFF).toByte()
            System.arraycopy(tokBytes, 0, loginBody, 4, tokBytes.size)
            outs.write(buildFrame(1002, 28724, loginBody))
            outs.flush()

            val n2 = ins.read(rawBuf)
            if (n2 <= 0) return false

            if (writePayload != null) {
                outs.write(buildFrame(1003, 19, aesEncrypt(key, iv, ttlvEncodeInt(100, 1))))
                outs.write(buildFrame(1004, 19, aesEncrypt(key, iv, writePayload)))
                outs.flush()
                try {
                    val nw = ins.read(rawBuf)
                    if (nw > 0) {
                        for ((_, cmd, payload) in parseFrames(destuffBytes(rawBuf, nw))) {
                            if (cmd == 20) applyTagsMap(ttlvDecode(aesDecrypt(key, iv, payload)))
                        }
                    }
                } catch (_: Exception) {}
                return true
            }

            // 3. Subscribe high-frequency (mode 3) & query tags
            outs.write(buildFrame(1003, 19, aesEncrypt(key, iv, ttlvEncodeInt(100, 3))))
            outs.write(buildFrame(1004, 35, aesEncrypt(key, iv, ByteArray(0))))
            outs.flush()

            for (round in 0 until 3) {
                try {
                    val nr = ins.read(rawBuf)
                    if (nr <= 0) break
                    for ((_, cmd, payload) in parseFrames(destuffBytes(rawBuf, nr))) {
                        if (cmd == 20) {
                            val dec = aesDecrypt(key, iv, payload)
                            val map = ttlvDecode(dec)
                            applyTagsMap(map)
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun applyTagsMap(map: Map<Int, Any>) {
        (map[1] as? Int)?.let { state.batteryPct = it }
        (map[2] as? Int)?.let { state.remainMinutes = it }
        (map[3] as? Int)?.let { state.remainChargeMinutes = it }
        (map[4] as? Int)?.let { state.totalInputW = it }
        (map[5] as? Int)?.let { state.totalOutputW = it }
        (map[10] as? Int)?.let { state.ledStatus = it }
        (map[11] as? Int)?.let { state.acInputW = it }
        (map[12] as? Int)?.let { state.dcInputW = it }
        (map[14] as? Int)?.let { state.tempC = it }
        state.acChargingLimit = targetChargeLimit
        (map[43] as? Boolean)?.let { state.acSwitch = it }
        (map[46] as? Boolean)?.let { state.dcSwitch = it }
    }

    // -----------------------------------------------------------------------
    // Cloud API (Acceleronix EU)
    // -----------------------------------------------------------------------

    private fun ensureCloudLogin(): Boolean {
        val nowSec = System.currentTimeMillis() / 1000
        if (!accessToken.isNullOrEmpty() && nowSec < tokenExpirySec - 120) return true

        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val randomStr = (1..16).map { chars.random() }.joinToString("")
        val md5 = md5HexUpper(randomStr)
        val aesKey = md5.substring(8, 24).toByteArray(Charsets.UTF_8)
        val iv = (md5.substring(16, 24) + md5.substring(8, 16)).toByteArray(Charsets.UTF_8)
        val pwdEnc = Base64.encodeToString(
            aesEncrypt(aesKey, iv, password.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val sig = sha256Hex(email + pwdEnc + randomStr + userDomainSecret)

        val form = mapOf(
            "email" to email,
            "pwd" to pwdEnc,
            "random" to randomStr,
            "userDomain" to userDomain,
            "signature" to sig
        ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val conn = (URL("$baseUrl/v2/enduser/enduserapi/emailPwdLogin").openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.doOutput = true
            conn.setRequestProperty("appversion", appVersion)
            conn.setRequestProperty("appsystemtype", "android")
            conn.setRequestProperty("appid", appId)
            conn.setRequestProperty("User-Agent", "okhttp/4.9.3")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

            val txt = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(txt)
            if (json.optInt("code") == 200) {
                val data = json.getJSONObject("data").getJSONObject("accessToken")
                accessToken = data.getString("token")
                tokenExpirySec = data.optLong("expirationTime", nowSec + 7000)
                true
            } else false
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun cloudControl(props: JSONArray): Boolean {
        if (!ensureCloudLogin()) return false
        val conn = (URL("$baseUrl/v2/binding/enduserapi/batchControlDevice").openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("appversion", appVersion)
            conn.setRequestProperty("appsystemtype", "android")
            conn.setRequestProperty("appid", appId)
            conn.setRequestProperty("Authorization", accessToken)
            conn.setRequestProperty("User-Agent", "okhttp/4.9.3")
            conn.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject().apply {
                put("data", props.toString())
                put("deviceList", JSONArray().put(JSONObject().apply {
                    put("deviceKey", deviceKey)
                    put("productKey", productKey)
                }))
                put("cacheTime", 60)
                put("isCache", 1)
                put("isCover", 1)
                put("dataFormat", 0)
                put("type", 2)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = res.optInt("code")
            if (code == 5032 || code == 401) {
                accessToken = null
                tokenExpirySec = 0L
            }
            code == 200
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun pollCloudTelemetry(retryOnAuth: Boolean = true): Boolean {
        if (!ensureCloudLogin()) return false
        val now = System.currentTimeMillis()
        if (now - lastWakeMs > 6_000L) {
            lastWakeMs = now
            val woke = cloudControl(JSONArray().put(JSONObject().put("high_frequency_reporting", 3)))
            if (!woke && accessToken == null && retryOnAuth) {
                ensureCloudLogin()
                cloudControl(JSONArray().put(JSONObject().put("high_frequency_reporting", 3)))
            }
            try { Thread.sleep(700L) } catch (_: Exception) {}
        }
        val url = "$baseUrl/v2/binding/enduserapi/getDeviceBusinessAttributes?pk=$productKey&dk=$deviceKey"
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("appversion", appVersion)
            conn.setRequestProperty("appsystemtype", "android")
            conn.setRequestProperty("appid", appId)
            conn.setRequestProperty("Authorization", accessToken)
            conn.setRequestProperty("User-Agent", "okhttp/4.9.3")

            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = res.optInt("code")
            if (code == 5032 || code == 401) {
                accessToken = null
                tokenExpirySec = 0L
                conn.disconnect()
                return if (retryOnAuth) pollCloudTelemetry(false) else false
            }
            if (code != 200) return false
            val data = res.optJSONObject("data") ?: return false
            val devData = data.optJSONObject("deviceData")
            if (devData != null) {
                state.wifiSignal = devData.optInt("signalStrength", state.wifiSignal)
            }
            val tsl = data.optJSONArray("customizeTslInfo") ?: return false
            for (i in 0 until tsl.length()) {
                val item = tsl.getJSONObject(i)
                val code = item.optString("resourceCode")
                val raw = item.optString("resourceValce")
                when (code) {
                    "battery_percentage" -> state.batteryPct = raw.toIntOrNull() ?: state.batteryPct
                    "total_input_power" -> state.totalInputW = raw.toIntOrNull() ?: state.totalInputW
                    "total_output_power" -> state.totalOutputW = raw.toIntOrNull() ?: state.totalOutputW
                    "ac_input" -> state.acInputW = raw.toIntOrNull() ?: state.acInputW
                    "dc_input" -> state.dcInputW = raw.toIntOrNull() ?: state.dcInputW
                    "remain_time" -> state.remainMinutes = raw.toIntOrNull() ?: state.remainMinutes
                    "remain_charging_time" -> state.remainChargeMinutes = raw.toIntOrNull() ?: state.remainChargeMinutes
                    "temp" -> state.tempC = raw.toIntOrNull() ?: state.tempC
                    "ac_charging_limit" -> {
                        // Cloud caches stale ac_charging_limit values when LAN TCP 6607 sets Tag 20 directly.
                        // Keep the authoritative user-selected targetChargeLimit instead of overwriting it.
                    }
                    "led_status" -> state.ledStatus = raw.toIntOrNull() ?: state.ledStatus
                    "ac_switch" -> state.acSwitch = raw.equals("true", ignoreCase = true) || raw == "1"
                    "dc_switch" -> state.dcSwitch = raw.equals("true", ignoreCase = true) || raw == "1"
                    "ac_data" -> {
                        try {
                            val obj = JSONObject(raw)
                            state.acOutputW = obj.optString("ac1_output").toIntOrNull() ?: state.totalOutputW
                        } catch (_: Exception) {}
                    }
                    "dc_data" -> {
                        try {
                            val obj = JSONObject(raw)
                            state.dcOutputW = obj.optString("car1_output").toIntOrNull() ?: 0
                        } catch (_: Exception) {}
                    }
                }
            }
            if (state.acInputW >= 950) {
                targetChargeLimit = 100
            }
            state.acChargingLimit = targetChargeLimit
            state.online = true
            if (state.connectionMode == "Offline") state.connectionMode = "Cloud"
            state.lastUpdatedMs = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    // -----------------------------------------------------------------------
    // Unified Poll & Hardware Control Commands
    // -----------------------------------------------------------------------

    private var lastLanDiscoverMs: Long = 0L

    fun refresh(): OukitelTelemetry {
        val cloudOk = pollCloudTelemetry()
        var lanOk = performLanTransaction(null)
        if (!lanOk && System.currentTimeMillis() - lastLanDiscoverMs > 30_000L) {
            lastLanDiscoverMs = System.currentTimeMillis()
            if (discoverOnLan() != null) {
                lanOk = performLanTransaction(null)
            }
        }
        state.online = lanOk || cloudOk
        state.connectionMode = when {
            lanOk && cloudOk -> "LAN + Cloud"
            lanOk -> "LAN Direct"
            cloudOk -> "Cloud"
            else -> "Offline"
        }
        if (state.online) {
            state.lastUpdatedMs = System.currentTimeMillis()
        }
        return state
    }

    fun setAcSwitch(on: Boolean): Boolean {
        state.acSwitch = on
        val lanOk = performLanTransaction(ttlvEncodeBool(43, on))
        val cloudOk = cloudControl(JSONArray().put(JSONObject().put("ac_switch", if (on) 1 else 0)))
        return lanOk || cloudOk
    }

    fun setDcSwitch(on: Boolean): Boolean {
        state.dcSwitch = on
        val lanOk = performLanTransaction(ttlvEncodeBool(46, on))
        val cloudOk = cloudControl(JSONArray().put(JSONObject().put("dc_switch", if (on) 1 else 0)))
        return lanOk || cloudOk
    }

    fun setLedStatus(mode: Int): Boolean {
        state.ledStatus = mode
        val lanOk = performLanTransaction(ttlvEncodeInt(10, mode))
        val cloudOk = cloudControl(JSONArray().put(JSONObject().put("led_status", mode)))
        return lanOk || cloudOk
    }

    fun setAcChargingLimit(limit: Int): Boolean {
        val clamped = if (limit <= 0) 0 else limit.coerceIn(1, 100)
        targetChargeLimit = clamped
        state.acChargingLimit = clamped
        onLimitChanged?.invoke(clamped)
        val lanOk = if (clamped >= 90) {
            val out = ByteArrayOutputStream()
            out.write(ttlvEncodeInt(20, 1))
            out.write(ttlvEncodeInt(20, 100))
            performLanTransaction(out.toByteArray())
        } else {
            performLanTransaction(ttlvEncodeInt(20, clamped))
        }
        val cloudOk = cloudControl(JSONArray().put(JSONObject().put("ac_charging_limit", clamped)))
        return lanOk || cloudOk
    }
}
