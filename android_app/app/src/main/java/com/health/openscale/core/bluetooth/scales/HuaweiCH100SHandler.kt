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
import com.health.openscale.core.bluetooth.libs.EtekcityLib
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
import kotlin.math.min

/**
 * Honor Smart Scale CH100S body fat scale (Chipsea CST34M97 chipset).
 *
 * Protocol reverse-engineered from the Huawei Body Fat Scale companion app
 * (com.huawei.overseas.ah100) and verified by BLE packet capture.
 *
 * Key differences from the CH100 (AH100):
 * - Measurement frames are AES-CTR encrypted (using the initial key, IV resets per packet).
 * - After AES decryption + MAC-XOR, the frame layout is:
 *     [userId, weightLE(2), fatLE(2), yearLE(2), month, day, hour, min, sec, dow, impedanceLE(2)]
 * - USER_INFO (CMD 0x09) is encrypted with MAC-XOR first, then AES-CTR (initial key).
 * - Body composition (water%, muscle%, bone, BMR, visceral fat) is computed app-side
 *   from impedance using BIA formulas, as the scale only transmits weight, fat%, and impedance.
 */
class HuaweiCH100SHandler : ScaleDeviceHandler() {

    // --- BLE identifiers ------------------------------------------------------

    private val SERVICE = uuid16(0xFAA0)
    private val CHAR_TX = uuid16(0xFAA1)
    private val CHAR_RX = uuid16(0xFAA2)

    private var sessionMac: String? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.US)
        if (name != "CH100S") return null

        sessionMac = device.address

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ
        )
        return DeviceSupport(
            displayName = "Honor Smart Scale (CH100S)",
            capabilities = caps,
            implemented = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Crypto constants -----------------------------------------------------

    private val AES_KEY = hexToBytes("3D A2 78 4A FB 87 B1 2A 98 0F DE 34 56 73 21 56")
    private val AES_IV  = hexToBytes("4E F7 64 32 2F DA 76 32 12 3D EB 87 90 FE A2 19")

    // --- Session state --------------------------------------------------------

    private var authCode: ByteArray = ByteArray(0)
    private var triesAuth = 0
    private var authorised = false
    private var userInfoRetries = 0
    private var lastWeightTenthKg: Int = -1

    private var pendingType: Byte = 0x00
    private var pendingFirst: ByteArray? = null

    // --- Notification opcodes -------------------------------------------------

    private val OP_WAKEUP       = 0x00.toByte()
    private val OP_SLEEP        = 0x01.toByte()
    private val OP_UNITS_SET    = 0x02.toByte()
    private val OP_CLOCK        = 0x08.toByte()
    private val OP_VERSION      = 0x0C.toByte()
    private val OP_MEAS_P1      = 0x0E.toByte()
    private val OP_MEAS_P2      = 0x8E.toByte()
    private val OP_HIST_P1      = 0x10.toByte()
    private val OP_HIST_P2      = 0x90.toByte()
    private val OP_HIST_DONE    = 0x19.toByte()
    private val OP_USER_CHANGED = 0x20.toByte()
    private val OP_AUTH_RESULT  = 0x26.toByte()
    private val OP_BIND_OK      = 0x27.toByte()

    // --- Commands -------------------------------------------------------------

    private val CMD_SET_UNIT    = 2.toByte()
    private val CMD_SET_CLOCK   = 8.toByte()
    private val CMD_USER_INFO   = 9.toByte()
    private val CMD_GET_RECORD  = 11.toByte()
    private val CMD_GET_VERSION = 12.toByte()
    private val CMD_FAT_ACK     = 19.toByte()
    private val CMD_AUTH        = 36.toByte()
    private val CMD_BIND        = 37.toByte()

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        authCode = buildAuthToken(user.id)
        setNotifyOn(SERVICE, CHAR_RX)
        triesAuth = 0
        authorised = false
        userInfoRetries = 0
        pendingType = 0x00
        pendingFirst = null
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_RX || data.size < 3) return
        val op = data[2]
        val payload = macXor(data.copyOfRange(3, data.size))

        when (op) {
            OP_WAKEUP -> {
                if (!authorised) sendPlain(CMD_AUTH, authCode)
            }

            OP_AUTH_RESULT -> {
                if (payload.isNotEmpty() && payload[0].toInt() == 1) {
                    authorised = true
                    sendPlain(CMD_SET_UNIT, byteArrayOf(0x01))
                    sendSetTime()
                    sendUserInfo(user, lastWeightTenthKg.takeIf { it > 0 })
                    sendPlain(CMD_GET_VERSION, byteArrayOf())
                    userInfoRetries = 0
                    userInfo(R.string.bt_info_step_on_scale)
                } else {
                    if (triesAuth++ < 2) sendPlain(CMD_AUTH, authCode)
                    else sendPlain(CMD_BIND, authCode)
                }
            }

            OP_USER_CHANGED -> {
                if (userInfoRetries++ < 5) {
                    sendUserInfo(user, lastWeightTenthKg.takeIf { it > 0 })
                }
            }

            OP_MEAS_P1, OP_HIST_P1 -> {
                if (data[0] == 0xBC.toByte()) {
                    pendingType = op
                    pendingFirst = data
                }
            }

            OP_MEAS_P2, OP_HIST_P2 -> {
                if (data[0] == 0xBC.toByte()) {
                    val first = pendingFirst
                    val type = pendingType
                    pendingFirst = null
                    pendingType = 0x00
                    if (first != null) handleEncryptedPair(first, data, type)
                }
            }

            OP_HIST_DONE -> { /* history upload complete */ }

            OP_UNITS_SET, OP_CLOCK, OP_VERSION, OP_SLEEP, OP_BIND_OK -> { /* ack */ }

            else -> logD("Unhandled op 0x%02X".format(op))
        }
    }

    // --- Measurement decryption & parsing ------------------------------------

    private fun handleEncryptedPair(first: ByteArray, second: ByteArray, type: Byte) {
        val rawP1 = first.copyOfRange(3, first.size)
        val rawP2 = second.copyOfRange(3, second.size)

        // AES-CTR decrypt each 16-byte part separately (IV resets per part, initial key)
        val decP1 = try { aesCtr(rawP1) } catch (e: GeneralSecurityException) {
            logW("AES P1: ${e.message}"); rawP1
        }
        val decP2 = try { aesCtr(rawP2) } catch (e: GeneralSecurityException) {
            logW("AES P2: ${e.message}"); rawP2
        }

        // MAC-XOR deobfuscate each part, then concatenate
        val data = concat(macXor(decP1), macXor(decP2))
        logD("Decrypted (${data.size}b): ${hex(data, 0, min(data.size, 20))}…")

        when (type) {
            OP_MEAS_P1 -> parseAndPublish(data)
            OP_HIST_P1 -> {
                parseAndPublish(data)
                sendPlain(CMD_GET_RECORD, byteArrayOf(0x01))
            }
        }
    }

    /**
     * Decrypted measurement frame layout (32 bytes, useful portion 0-14):
     *
     * | Offset | Field                | Encoding                    |
     * |--------|----------------------|-----------------------------|
     * | 0      | User ID              | uint8                       |
     * | 1-2    | Weight               | LE uint16, tenths of kg     |
     * | 3-4    | Body fat %           | LE uint16, tenths of %      |
     * | 5-6    | Year                 | LE uint16                   |
     * | 7      | Month                | uint8                       |
     * | 8      | Day                  | uint8                       |
     * | 9      | Hour                 | uint8                       |
     * | 10     | Minute               | uint8                       |
     * | 11     | Second               | uint8                       |
     * | 12     | Day of week          | uint8                       |
     * | 13-14  | Impedance            | LE uint16, ohms             |
     */
    private fun parseAndPublish(data: ByteArray) {
        if (data.size < 15) {
            logW("Frame too short: ${data.size}")
            return
        }

        val userId    = data[0].toInt() and 0xFF
        val weight    = u16le(data, 1) / 10.0f
        val fat       = u16le(data, 3) / 10.0f
        val impedance = u16le(data, 13)

        val dt = try {
            val y = u16le(data, 5); val mo = data[7].toInt() and 0xFF
            val d = data[8].toInt() and 0xFF; val h = data[9].toInt() and 0xFF
            val mi = data[10].toInt() and 0xFF; val s = data[11].toInt() and 0xFF
            if (y in 2020..2099 && mo in 1..12 && d in 1..31) {
                Calendar.getInstance().apply { set(y, mo - 1, d, h, mi, s) }.time
            } else Date()
        } catch (_: Exception) { Date() }

        lastWeightTenthKg = (weight * 10).toInt()

        val m = ScaleMeasurement().apply {
            this.userId = userId
            this.dateTime = dt
            this.weight = weight
            this.fat = fat
            if (impedance in 1..3999) {
                this.impedance = impedance.toDouble()
                // Water%, muscle%, bone, BMR, visceral fat are not sent by the scale.
                // Compute app-side from impedance using BIA formulas (Chipsea chipset).
                val user = currentAppUser()
                val lib = EtekcityLib(
                    gender = user.gender,
                    age = user.age,
                    weightKg = weight.toDouble(),
                    heightM = user.bodyHeight.toDouble() / 100.0,
                    impedance = impedance.toDouble()
                )
                this.water = lib.water.toFloat()
                this.muscle = lib.skeletalMusclePercentage.toFloat()
                this.bone = lib.boneMass.toFloat()
                this.bmr = lib.basalMetabolicRate.toFloat()
                this.visceralFat = lib.visceralFat.toFloat()
            }
        }
        publish(m)
        logI("Measurement: $weight kg, fat=$fat%, imp=$impedance Ω, user=$userId @ ${ts(dt)}")
        sendPlain(CMD_FAT_ACK, byteArrayOf(0x00))
    }

    // --- Commands -------------------------------------------------------------

    private fun sendSetTime() {
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        sendPlain(CMD_SET_CLOCK, byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            (((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1).toByte()
        ))
    }

    private fun sendUserInfo(user: ScaleUser, weightTenthKg: Int?) {
        val sexBit = if (user.gender.isMale()) 0x00 else 0x80
        val age = user.age and 0x7F
        val w = (weightTenthKg ?: (user.initialWeight * 10f).toInt()).coerceAtLeast(0)
        val payload = ByteArrayOutputStream().apply {
            write(authCode)
            write((age or sexBit) and 0xFF)
            write(user.bodyHeight.toInt() and 0xFF)
            write(0x00)
            write(le16(w))
            write(le16(0xFFFF))
        }.toByteArray()
        sendEncrypted(CMD_USER_INFO, payload)
    }

    // --- Wire helpers ---------------------------------------------------------

    private fun sendPlain(cmd: Byte, payload: ByteArray) {
        val header = byteArrayOf(0xDB.toByte(), payload.size.toByte(), cmd)
        writeTo(SERVICE, CHAR_TX, concat(header, macXor(payload)), withResponse = true)
    }

    private fun sendEncrypted(cmd: Byte, payload: ByteArray) {
        val obfuscated = macXor(payload)
        val enc = try { aesCtr(obfuscated) } catch (e: GeneralSecurityException) {
            logW("AES encrypt: ${e.message}"); return
        }
        val header = byteArrayOf(0xDC.toByte(), payload.size.toByte(), cmd)
        writeTo(SERVICE, CHAR_TX, concat(header, enc), withResponse = true)
    }

    // --- Crypto ---------------------------------------------------------------

    private fun macXor(raw: ByteArray): ByteArray {
        val mac = macStringToBytes(sessionMac ?: "00:00:00:00:00:00")
        if (mac.isEmpty()) return raw
        val out = raw.copyOf()
        for (i in out.indices) out[i] = (out[i].toInt() xor (mac[i % mac.size].toInt() and 0xFF)).toByte()
        return out
    }

    private fun aesCtr(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
        return cipher.doFinal(data)
    }

    // --- Utils ----------------------------------------------------------------

    private fun buildAuthToken(appUserId: Int): ByteArray {
        val auth = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, (appUserId and 0xFF).toByte())
        var x = 0; for (b in auth) x = x xor (b.toInt() and 0xFF)
        auth[5] = (x and 0xFF).toByte()
        return auth
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun macStringToBytes(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "")
        if (clean.length != 12) return ByteArray(0)
        return ByteArray(6) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun hexToBytes(s: String): ByteArray {
        val c = s.replace(" ", "")
        return ByteArray(c.length / 2) { i -> c.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun concat(a: ByteArray, b: ByteArray) =
        ByteArray(a.size + b.size).also {
            System.arraycopy(a, 0, it, 0, a.size)
            System.arraycopy(b, 0, it, a.size, b.size)
        }

    private fun hex(b: ByteArray, off: Int = 0, len: Int = b.size): String =
        (off until (off + len)).joinToString(" ") { "%02X".format(b[it]) }

    private fun ts(d: Date) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
}
