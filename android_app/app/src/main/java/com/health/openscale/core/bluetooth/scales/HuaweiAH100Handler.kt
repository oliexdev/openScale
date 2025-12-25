/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.byteArrayOf
import kotlin.math.min

/**
 * Huawei AH100 (a.k.a. CH100) body fat scale.
 *
 * Protocol highlights:
 * - Two custom characteristics (write TX 0xFAA1, notify RX 0xFAA2) on service 0xFAA0.
 * - Packets are "obfuscated" by XOR with device MAC and some are AES-CTR encrypted.
 * - Auth flow:
 *      1) Wait for WAKEUP (0x00), then send AUTH (cmd 36) with 7-byte user ID token.
 *      2) On AUTH OK, derive session "magicKey" and proceed (unit/time/userinfo).
 * - Measurements come as two-part encrypted frames (0x8E/0x90 second part).
 */
class HuaweiAH100Handler : ScaleDeviceHandler() {

    // --- Identifiers ----------------------------------------------------------

    private val SERVICE = uuid16(0xFAA0)
    private val CHAR_TX = uuid16(0xFAA1) // write
    private val CHAR_RX = uuid16(0xFAA2) // notify

    // We need the device MAC for the XOR obfuscation. We cache it when supportFor() recognizes a device.
    private var sessionMac: String? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.US)
        // Known advert name for AH100
        if (name != "CH100") return null

        sessionMac = device.address // cache address for obfuscation

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ
        )
        val implemented = setOf(
            DeviceCapability.BODY_COMPOSITION, // Weight, fat%, and impedance
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            // We parse history frames; triggering full history pull is partial here.
            DeviceCapability.HISTORY_READ
        )
        return DeviceSupport(
            displayName = "Huawei AH100",
            capabilities = caps,
            implemented = implemented,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Session state --------------------------------------------------------

    // Auth/session keys
    private var authCode: ByteArray = ByteArray(0)
    private val initialKey = hexToBytes("3D A2 78 4A FB 87 B1 2A 98 0F DE 34 56 73 21 56")
    private val initialIv  = hexToBytes("4E F7 64 32 2F DA 76 32 12 3D EB 87 90 FE A2 19")
    private var magicKey: ByteArray? = null
    private var triesAuth = 0
    private var authorised = false
    private var scaleAwake = false
    private var lastMeasuredWeightTenthKg: Int = -1

    // Two-part receive buffer (encrypted measurement/history)
    private var pendingType: Byte = 0x00
    private var pendingFirst: ByteArray? = null

    // --- Notification opcodes (data[2]) --------------------------------------

    private val NTFY_WAKEUP                    = 0x00.toByte()
    private val NTFY_GO_SLEEP                  = 0x01.toByte()
    private val NTFY_UNITS_SET                 = 0x02.toByte()
    private val NTFY_SCALE_CLOCK               = 0x08.toByte()
    private val NTFY_SCALE_VERSION             = 0x0C.toByte()
    private val NTFY_MEASUREMENT               = 0x0E.toByte()
    private val NTFY_MEASUREMENT2              = 0x8E.toByte()
    private val NTFY_MEASUREMENT_WEIGHT        = 0x0F.toByte()
    private val NTFY_HISTORY_RECORD            = 0x10.toByte()
    private val NTFY_HISTORY_RECORD2           = 0x90.toByte()
    private val NTFY_HISTORY_UPLOAD_DONE       = 0x19.toByte()
    private val NTFY_USER_CHANGED              = 0x20.toByte()
    private val NTFY_AUTH_RESULT               = 0x26.toByte()
    private val NTFY_BIND_OK                   = 0x27.toByte()

    // --- Commands (cmd byte) --------------------------------------------------

    private val CMD_SET_UNIT                   = 2.toByte()
    private val CMD_SET_SCALE_CLOCK            = 8.toByte()
    private val CMD_USER_INFO                  = 9.toByte()
    private val CMD_GET_RECORD                 = 11.toByte()
    private val CMD_GET_VERSION                = 12.toByte()
    private val CMD_FAT_RESULT_ACK             = 19.toByte()
    private val CMD_AUTH                       = 36.toByte()
    private val CMD_BIND_USER                  = 37.toByte()
    private val CMD_HEARTBEAT                  = 32.toByte()

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // Prepare auth token (7 bytes with checksum like in legacy)
        authCode = buildAuthToken(user.id)

        // Enable notifications on RX and wait for WAKEUP before we start AUTH
        setNotifyOn(SERVICE, CHAR_RX)
        triesAuth = 0
        authorised = false
        scaleAwake = false
        pendingType = 0x00
        pendingFirst = null

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_RX || data.size < 3) return

        // Obfuscated (MAC XOR) payload starts at byte 3
        val obfPayload = data.copyOfRange(3, data.size)
        val payload = obfuscate(obfPayload)
        val op = data[2]

        logD("NTFY op=%02X len=${payload.size}".format(op))

        when (op) {
            NTFY_WAKEUP -> {
                scaleAwake = true
                if (!authorised) {
                    sendAuth()
                }
            }
            NTFY_AUTH_RESULT -> {
                if (payload.isNotEmpty() && payload[0].toInt() == 1) {
                    authorised = true
                    // magicKey = obfuscate(authCode) + tail(initialKey[7..])
                    val obfAuth = obfuscate(authCode)
                    val keyTail = initialKey.copyOfRange(7, initialKey.size)
                    magicKey = concat(obfAuth, keyTail)
                    // Proceed with basic setup then wait for weight
                    sendSetUnit(user.scaleUnit)
                    sendSetTime()
                    sendUserInfo(user, lastMeasuredWeightTenthKg.takeIf { it > 0 })
                    sendGetVersion()
                    userInfo(R.string.bt_info_step_on_scale)
                } else {
                    if (triesAuth++ < 2) {
                        sendAuth()
                    } else {
                        // Fallback: try bind, then auth again
                        sendBind()
                    }
                }
            }

            NTFY_UNITS_SET,
            NTFY_SCALE_CLOCK,
            NTFY_SCALE_VERSION,
            NTFY_HISTORY_UPLOAD_DONE,
            NTFY_GO_SLEEP -> {
                // benign acks / state changes – nothing to do
            }

            // Two-part encrypted measurement
            NTFY_MEASUREMENT, NTFY_HISTORY_RECORD -> {
                if (data[0] == 0xBC.toByte()) {
                    pendingType = op
                    pendingFirst = data
                }
            }
            NTFY_MEASUREMENT2, NTFY_HISTORY_RECORD2 -> {
                if (data[0] == 0xBC.toByte()) {
                    val first = pendingFirst
                    val type = pendingType
                    pendingFirst = null
                    pendingType = 0x00
                    if (first != null) {
                        handleEncryptedPair(first, data, type)
                    }
                }
            }

            NTFY_MEASUREMENT_WEIGHT -> {
                // not used; full composition comes via encrypted pair
            }

            NTFY_USER_CHANGED -> {
                // Scale wants user info again (e.g., after restart)
                sendUserInfo(user, lastMeasuredWeightTenthKg.takeIf { it > 0 })
            }

            else -> {
                logD("Unhandled op 0x%02X".format(op))
            }
        }
    }

    // --- Commands -------------------------------------------------------------

    private fun sendAuth() = sendCmd(CMD_AUTH, authCode)

    private fun sendBind() = sendCmd(CMD_BIND_USER, authCode)

    private fun sendHeartbeat() = sendCmd(CMD_HEARTBEAT, byteArrayOf())

    private fun sendSetUnit(unit: WeightUnit) {
        // Protocol comment says: 1 = kg, 2 = lb. We stick to kg for now.
        sendCmd(CMD_SET_UNIT, byteArrayOf(0x01))
    }

    private fun sendSetTime() {
        val c = Calendar.getInstance()
        // [loYear, hiYear, month(1..12), day, hour, min, sec, dow(1..7 Mon..Sun)]
        val year = c.get(Calendar.YEAR)
        val payload = byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            // Java dow: SUN=1..SAT=7 → protocol expects MON=1..SUN=7; this mapping is approximate
            (((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1).toByte()
        )
        sendCmd(CMD_SET_SCALE_CLOCK, payload)
    }

    private fun sendUserInfo(user: ScaleUser, weightTenthKg: Int?) {
        // payload = auth(7) + [age|sexBit, height, 0, weightLE(2), impedanceLE(2), 0x1C, 0xE2]
        val sexBit = (if (user.gender.isMale()) 0x00.toByte() else 0x80.toByte())
        val age = user.age.toByte()
        val height = user.bodyHeight.toInt().toByte()
        val w = (weightTenthKg ?: (user.initialWeight * 10f).toInt()).coerceAtLeast(0)
        val tail = ByteArrayOutputStream().apply {
            write(byteArrayOf((age.toInt() or sexBit.toInt()).toByte(), height, 0))
            write(le16(w))
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) // resistance unknown
            write(byteArrayOf(0x1C.toByte(), 0xE2.toByte())) // constant as in legacy
        }.toByteArray()

        val full = concat(authCode, tail)
        sendCmdEncrypted(CMD_USER_INFO, full)
    }

    private fun sendGetVersion() = sendCmd(CMD_GET_VERSION, byteArrayOf())

    private fun sendGetHistoryFirst() {
        // payload = auth + XOR(auth) (legacy did this to request count / first record)
        val chk = xorChecksum(authCode, 0, authCode.size)
        val pl = concat(authCode, byteArrayOf(chk))
        sendCmd(CMD_GET_RECORD, pl, explicitLen = 0x06) // legacy used 0x07-1
    }

    private fun sendGetHistoryNext() {
        sendCmd(CMD_GET_RECORD, byteArrayOf(0x01))
    }

    // --- Pair handling & parsing ---------------------------------------------

    private fun handleEncryptedPair(first: ByteArray, second: ByteArray, type: Byte) {
        val p1 = payload(first)
        val p2 = payload(second)
        val merged = concat(p1, p2)

        logD("XOR'd data (${merged.size}b): ${hex(merged, 0, min(merged.size, 32))}${if (merged.size>32) " …" else ""}")

        // Data is XOR-obfuscated only, NOT AES encrypted
        // Parse directly from the XOR'd data
        when (type) {
            NTFY_MEASUREMENT -> parseAndPublishMeasurement(merged)
            NTFY_HISTORY_RECORD -> {
                parseAndPublishMeasurement(merged)
                // Then request next; scale will stop with HISTORY_UPLOAD_DONE
                sendGetHistoryNext()
            }
        }
    }

    /**
     * Measurement format (XOR-obfuscated, 32 bytes merged from two 16-byte frames):
     *
     * Based on reverse engineering - data is XOR obfuscated only, NOT AES encrypted:
     * - Position 1: Weight (encoded as: weight_kg = (1457 - byte[1]) / 10)
     * - Position 2-3: Impedance (big-endian, ohms)
     * - Position 10: Visceral fat rating (whole number)
     * - Position 20: Skeletal muscle % (whole percent)
     * - Position 27: Body water % (whole percent)
     * - Position 31: User ID
     * - Timestamp: Position not yet identified (using current time)
     * - Body fat %: Position not yet identified
     */
    private fun parseAndPublishMeasurement(data: ByteArray) {
        if (data.size < 32) {
            logW("Measurement data too short: ${data.size} bytes")
            return
        }

        // Weight at position 1, encoded formula
        val weightByte = data[1].toInt() and 0xFF
        val weight = (1457 - weightByte) / 10.0f

        // Impedance at position 2-3, big-endian, ohms
        val impedance = u16be(data, 2)

        // Visceral fat at position 10
        val visceralFat = (data[10].toInt() and 0xFF).toFloat()

        // Skeletal muscle % at position 20
        val muscle = (data[20].toInt() and 0xFF).toFloat()

        // Body water % at position 27
        val water = (data[27].toInt() and 0xFF).toFloat()

        // User ID at position 31
        val userId = data[31].toInt() and 0xFF

        // Use current time since timestamp position not yet identified
        val dt = Date()

        lastMeasuredWeightTenthKg = (weight * 10).toInt()

        val m = ScaleMeasurement().apply {
            this.userId = userId
            this.dateTime = dt
            this.weight = weight
            this.muscle = muscle
            this.water = water
            this.visceralFat = visceralFat
            if (impedance > 0 && impedance < 4000) {
                this.impedance = impedance.toDouble()
            }
        }
        publish(m)
        logI("Measurement: ${weight} kg, muscle=${muscle}%, water=${water}%, visceral=${visceralFat}, impedance=${impedance} Ω, userId=$userId @ ${ts(dt)}")

        // Acknowledge measurement
        sendCmd(CMD_FAT_RESULT_ACK, byteArrayOf(0x00))
    }

    // --- Wire helpers ---------------------------------------------------------

    /** Send plain (MAC-XOR obfuscated) command. */
    private fun sendCmd(cmd: Byte, payload: ByteArray, explicitLen: Int? = null) {
        val len = explicitLen ?: payload.size
        val header = byteArrayOf(0xDB.toByte(), len.toByte(), cmd)
        val pkt = concat(header, obfuscate(payload))
        logD("→ CMD ${cmd.toUByte().toString(16)} len=$len")
        writeTo(SERVICE, CHAR_TX, pkt, withResponse = true)
    }

    /** Send encrypted (AES-CTR + MAC-XOR) command (used for USER_INFO). */
    private fun sendCmdEncrypted(cmd: Byte, payload: ByteArray) {
        val key = magicKey ?: run { logW("magicKey missing, drop encrypted cmd"); return }
        val enc = try { aesCtr(payload, key, initialIv) } catch (e: GeneralSecurityException) {
            logW("AES encrypt failed: ${e.message}"); return
        }
        val header = byteArrayOf(0xDC.toByte(), payload.size.toByte(), cmd)
        val pkt = concat(header, obfuscate(enc))
        logD("→ CMD* ${cmd.toUByte().toString(16)} len=${payload.size}")
        writeTo(SERVICE, CHAR_TX, pkt, withResponse = true)
    }

    /** Extract (and deobfuscate) payload area of a notified packet. */
    private fun payload(frame: ByteArray): ByteArray {
        if (frame.size <= 3) return ByteArray(0)
        return obfuscate(frame.copyOfRange(3, frame.size))
    }

    /** XOR with device MAC (repeated). */
    private fun obfuscate(raw: ByteArray): ByteArray {
        val mac = (sessionMac ?: "").ifEmpty { "00:00:00:00:00:00" }
        val macBytes = macStringToBytes(mac)
        val out = raw.copyOf()
        if (macBytes.isEmpty()) return out
        var i = 0
        for (idx in out.indices) {
            out[idx] = (out[idx].toInt() xor (macBytes[i].toInt() and 0xFF)).toByte()
            i++; if (i >= macBytes.size) i = 0
        }
        return out
    }

    // --- Utils ----------------------------------------------------------------

    private fun buildAuthToken(appUserId: Int): ByteArray {
        // 7 bytes: [0x11, 0x22, 0x33, 0x44, 0x55, chk, userId]
        val id = (appUserId and 0xFF).toByte()
        val auth = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, id)
        // Legacy set index 5 to XOR(auth[0..6]) so the overall XOR equals 0x00.
        auth[5] = xorChecksum(auth, 0, auth.size)
        return auth
    }

    private fun xorChecksum(buf: ByteArray, off: Int, len: Int): Byte {
        var x = 0
        for (i in off until (off + len)) x = x xor (buf[i].toInt() and 0xFF)
        return (x and 0xFF).toByte()
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u16le(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8))
    private fun u16be(b: ByteArray, off: Int) =
        (((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF))

    private fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val iv16 = ByteArray(16).apply { System.arraycopy(iv, 0, this, 0, min(16, iv.size)) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        return cipher.doFinal(data)
    }

    private fun macStringToBytes(mac: String): ByteArray {
        // Accept "AA:BB:..." or "AABB..." formats
        val clean = mac.replace(":", "").replace("-", "")
        if (clean.length != 12) return ByteArray(0)
        val out = ByteArray(6)
        for (i in 0 until 6) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "")
        val even = if (clean.length % 2 == 0) clean else "0$clean"
        return ByteArray(even.length / 2) { i ->
            even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size + b.size).also {
            System.arraycopy(a, 0, it, 0, a.size)
            System.arraycopy(b, 0, it, a.size, b.size)
        }

    private fun hex(b: ByteArray, off: Int = 0, len: Int = b.size): String {
        val sb = StringBuilder()
        for (i in off until (off + len)) {
            if (i > off) sb.append(' ')
            sb.append(String.format("%02X", b[i]))
        }
        return sb.toString()
    }

    private fun ts(d: Date) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
}
