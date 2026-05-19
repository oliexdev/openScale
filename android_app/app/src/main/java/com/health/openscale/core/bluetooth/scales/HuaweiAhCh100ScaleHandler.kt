/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_AUTH
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_BIND_USER
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_FAT_RESULT_ACK
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_GET_RECORD
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_GET_VERSION
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_SET_SCALE_CLOCK
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_SET_UNIT
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.CMD_USER_INFO
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_AUTH_RESULT
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_BIND_OK
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_GO_SLEEP
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_HISTORY_RECORD
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_HISTORY_RECORD2
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_HISTORY_UPLOAD_DONE
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_MEASUREMENT
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_MEASUREMENT2
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_MEASUREMENT_WEIGHT
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_SCALE_CLOCK
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_SCALE_VERSION
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_UNITS_SET
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_USER_CHANGED
import com.health.openscale.core.bluetooth.libs.HuaweiAhCh100Protocol.NTFY_WAKEUP
import com.health.openscale.core.service.ScannedDeviceInfo
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Shared Huawei AH100 / CH100 / Honor scale state machine.
 *
 * The two products use the same Chipsea CST34M97-based hardware with
 * identical wire protocol but different BLE advertisement names. Subclasses
 * (`HuaweiAH100Handler`, `HuaweiCH100Handler`) only override the advert name
 * matched in [supportFor]; everything else lives here.
 *
 * Wire-protocol primitives are in [HuaweiAhCh100Protocol]; that file is
 * pure Kotlin and JVM-testable, so the parsing logic is exercised by
 * `HuaweiAhCh100ProtocolTest` and we don't have to re-prove it on a real
 * scale.
 *
 * History (so future maintainers don't repeat past mistakes):
 *
 *  - openScale **v2.5.4** had a working Java handler. It AES-CTR-decrypted
 *    *only* the first half of the two-part measurement frame and parsed the
 *    documented byte layout. See `BluetoothHuaweiAH100.java` in tag v2.5.4.
 *  - The 3.x Kotlin rewrite (commit 2e7e708f and follow-ups) accidentally
 *    dropped the AES decryption entirely and replaced the byte layout with
 *    invented offsets. That produced the 138 kg / 180 % / year-3084 nonsense
 *    reported in #1206 / #1280.
 *  - Issue #1276 proposed a partial fix (decrypt the merged buffer instead
 *    of the first half) which is mathematically equivalent for the first 16
 *    bytes — but the contributor's account was deleted before it landed.
 *  - This handler restores the v2.5.4 behaviour exactly. It uses the default
 *    BLE tuning profile (Balanced, MTU bumped to 185); the legacy CST34M97
 *    firmware sometimes ships the whole composition in a single fat
 *    notification at that MTU instead of the paired 16-byte halves
 *    v2.5.4 saw at MTU 23. [tryDecodeFirstHalfImmediately] handles both
 *    shapes, so we don't need to pin the conservative profile.
 *  - Real-world note: when migrating from openScale 2.5.x or Huawei Health
 *    on the same phone, users may need to "Forget" the scale once in the
 *    Android Bluetooth settings. The link-layer encryption keys from the
 *    previous bond don't survive this protocol's re-pair flow and otherwise
 *    cause `CONNECTION_TERMINATED_MIC_FAILURE` after the first command.
 */
abstract class HuaweiAhCh100ScaleHandler : ScaleDeviceHandler() {

    // --- Identifiers --------------------------------------------------------

    private val SERVICE = uuid16(0xFAA0)
    private val CHAR_TX = uuid16(0xFAA1) // host -> scale: write
    private val CHAR_RX = uuid16(0xFAA2) // scale -> host: notify

    /** BLE advertisement name this concrete handler claims. */
    protected abstract val supportedAdvertName: String

    /** Display name shown in the UI for this concrete handler. */
    protected abstract val displayName: String

    // We need the scale MAC for the XOR obfuscation. Cache it from
    // ScannedDeviceInfo.address as soon as supportFor() approves.
    private var sessionMac: String? = null

    private fun macBytes(): ByteArray {
        val s = sessionMac
        if (s.isNullOrBlank()) {
            logW("sessionMac is null/blank; XOR-obfuscation will be a no-op and frames will not parse")
            return ByteArray(6)
        }
        return runCatching { HuaweiAhCh100Protocol.macStringToBytes(s) }
            .onFailure { logW("Failed to parse sessionMac '$s': ${it.message}") }
            .getOrElse { ByteArray(6) }
    }

    final override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Some Huawei firmware revisions append trailing NULs / whitespace to
        // the advert name, and some Honor / OEM rebrands prefix the vendor
        // name. Strip those so our match is robust without overshooting into
        // the CH100S handler's territory.
        val raw = device.name ?: return null
        val cleaned = raw
            .replace("\u0000", "")
            .trim()
            .uppercase(Locale.US)
        val candidates = setOf(
            cleaned,
            cleaned.removePrefix("HUAWEI ").trim(),
            cleaned.removePrefix("HONOR ").trim(),
            cleaned.substringBefore('-'),
            cleaned.substringBefore('_'),
            cleaned.substringBefore(' ')
        )
        if (supportedAdvertName !in candidates) return null
        if (cleaned != raw) {
            logD("supportFor: matched advert '$raw' -> '$cleaned' against target '$supportedAdvertName'")
        }

        sessionMac = device.address

        // The hardware *can* upload history records (see NTFY_HISTORY_RECORD
        // in onNotification + sendGetHistoryNext), but we don't actively
        // pull the full history yet — `sendGetHistoryFirst()` is wired but
        // not invoked from the lifecycle. Advertise it as a capability so
        // the UI knows the device supports it; mark only what's actually
        // hooked up as implemented.
        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ
        )
        val implemented = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC
        )
        return DeviceSupport(
            displayName = displayName,
            capabilities = caps,
            implemented = implemented,
            // Use the default Balanced tuning. The handler decodes the
            // measurement from the first half as soon as it arrives (see
            // tryDecodeFirstHalfImmediately), so it works whether the scale
            // sends paired 16-byte halves at MTU 23 or a single fat frame
            // at the negotiated MTU 185 — no need to force the legacy MTU.
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Session state ------------------------------------------------------

    private var authCode: ByteArray = ByteArray(0)
    private var magicKey: ByteArray? = null
    private var triesAuth = 0
    private var authorised = false
    private var scaleAwake = false
    private var scaleBound = false
    private var lastMeasuredWeightTenthKg: Int = -1

    // First half of an encrypted measurement, awaiting its 0x8E/0x90 sibling.
    private var pendingFirst: ByteArray? = null
    private var pendingType: Byte = 0x00

    // --- Lifecycle ---------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        authCode = HuaweiAhCh100Protocol.buildAuthToken(user.id)
        triesAuth = 0
        authorised = false
        scaleAwake = false
        scaleBound = false
        magicKey = null
        pendingFirst = null
        pendingType = 0x00

        setNotifyOn(SERVICE, CHAR_RX)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_RX || data.size < 3) return

        val op = data[2]
        // Plain notifications XOR their tail with the MAC; encrypted
        // measurement halves do too (then need an additional AES pass below).
        val deobfTail = HuaweiAhCh100Protocol.deobfuscateTail(data, macBytes())

        logD("← op=0x%02X len=%d %s".format(op.toInt() and 0xFF, deobfTail.size, deobfTail.toHex(24)))

        when (op) {
            NTFY_WAKEUP -> {
                scaleAwake = true
                if (!authorised) sendAuth()
            }

            NTFY_AUTH_RESULT -> {
                if (deobfTail.isNotEmpty() && deobfTail[0].toInt() == 1) {
                    authorised = true
                    magicKey = HuaweiAhCh100Protocol.deriveMagicKey(authCode, macBytes())
                    sendSetUnit()
                    sendSetTime()
                    sendUserInfo(user, lastMeasuredWeightTenthKg.takeIf { it > 0 })
                    sendGetVersion()
                    userInfo(R.string.bt_info_step_on_scale)
                } else {
                    if (triesAuth++ < 2) {
                        sendAuth()
                    } else {
                        // Fallback: BIND first, then AUTH again. The scale
                        // emits NTFY_BIND_OK on success which retries auth.
                        sendBind()
                    }
                }
            }

            NTFY_BIND_OK -> {
                scaleBound = true
                sendAuth()
            }

            NTFY_UNITS_SET,
            NTFY_SCALE_CLOCK,
            NTFY_SCALE_VERSION,
            NTFY_HISTORY_UPLOAD_DONE,
            NTFY_GO_SLEEP -> {
                // benign acks / state transitions
            }

            // First halves of two-part encrypted measurement / history frame.
            NTFY_MEASUREMENT, NTFY_HISTORY_RECORD -> {
                if (data[0] == HuaweiAhCh100Protocol.FRAME_NOTIFY_ENCRYPTED) {
                    pendingType = op
                    pendingFirst = data
                    // MTU resilience: if the first half already contains a
                    // full v2.5.4-shaped record (≥ 15 deobf bytes of plaintext),
                    // we can decode immediately. The scale on Balanced/Aggressive
                    // tuning sometimes packs the whole composition into one fat
                    // notification because the negotiated MTU is large enough.
                    if (deobfTail.size >= 15) {
                        tryDecodeFirstHalfImmediately(op)
                    }
                }
            }

            NTFY_MEASUREMENT2, NTFY_HISTORY_RECORD2 -> {
                if (data[0] == HuaweiAhCh100Protocol.FRAME_NOTIFY_ENCRYPTED) {
                    val first = pendingFirst
                    val type = pendingType
                    pendingFirst = null
                    pendingType = 0x00
                    if (first != null) {
                        decodeAndPublish(first, type)
                    }
                }
            }

            NTFY_MEASUREMENT_WEIGHT -> {
                // Stable-weight precursor; the encrypted pair carries the
                // composition we actually want. Ignore.
            }

            NTFY_USER_CHANGED -> {
                // Scale asks for user info again (e.g. after restart). The
                // payload is AES-encrypted with magicKey, so this is only
                // meaningful after AUTH succeeded; ignore otherwise.
                if (authorised) {
                    sendUserInfo(user, lastMeasuredWeightTenthKg.takeIf { it > 0 })
                } else {
                    logD("NTFY_USER_CHANGED received before auth; ignoring")
                }
            }

            else -> logD("Unhandled op 0x%02X".format(op.toInt() and 0xFF))
        }
    }

    private fun tryDecodeFirstHalfImmediately(type: Byte) {
        val first = pendingFirst ?: return
        val mk = magicKey ?: return
        try {
            val m = HuaweiAhCh100Protocol.decodeFirstHalf(first, mk, macBytes())
            publishMeasurement(m, viaSingleFrame = true)
            // We've consumed the data; if the second half ever arrives the
            // 0x8E branch will see pendingFirst = null and silently drop it.
            pendingFirst = null
            pendingType = 0x00
            if (type == NTFY_HISTORY_RECORD) sendGetHistoryNext()
        } catch (t: Throwable) {
            // Not enough bytes / decrypt error — fall through and wait for
            // the second half before publishing.
            logD("first-half early decode failed: ${t.message}")
        }
    }

    private fun decodeAndPublish(first: ByteArray, type: Byte) {
        val mk = magicKey ?: run {
            logW("magicKey missing; dropping measurement")
            return
        }
        try {
            val m = HuaweiAhCh100Protocol.decodeFirstHalf(first, mk, macBytes())
            publishMeasurement(m, viaSingleFrame = false)
        } catch (e: GeneralSecurityException) {
            logW("AES-CTR failed on measurement: ${e.message}")
            return
        } catch (e: IllegalArgumentException) {
            logW("Measurement parse failed: ${e.message}")
            return
        }
        if (type == NTFY_HISTORY_RECORD) sendGetHistoryNext()
    }

    private fun publishMeasurement(m: HuaweiAhCh100Protocol.Measurement, viaSingleFrame: Boolean) {
        lastMeasuredWeightTenthKg = (m.weightKg * 10f).toInt()

        val sm = ScaleMeasurement().apply {
            this.userId = m.userId
            this.dateTime = m.dateTime ?: Date()
            this.weight = m.weightKg
            this.fat = m.fatPct
            // The scale reports impedance but the v2.5.4 reference doesn't
            // derive water/muscle/bone from it; openScale's existing
            // StandardImpedanceLib can be wired in later for that.
            if (m.impedanceOhm in 1..3999) {
                this.impedance = m.impedanceOhm.toDouble()
            }
        }
        publish(sm)
        logI(
            "Measurement: ${m.weightKg} kg, fat=${m.fatPct}%, impedance=${m.impedanceOhm} Ω, " +
                "userId=${m.userId} @ ${m.dateTime?.let(::ts) ?: "now"} " +
                "(${if (viaSingleFrame) "single-frame" else "paired"})"
        )

        sendCmd(CMD_FAT_RESULT_ACK, byteArrayOf(0x00))
    }

    // --- Commands ----------------------------------------------------------

    private fun sendAuth() = sendCmd(CMD_AUTH, authCode)

    private fun sendBind() = sendCmd(CMD_BIND_USER, authCode)

    private fun sendSetUnit() {
        // Protocol: 1 = kg, 2 = lb. We always tell the scale kg; openScale
        // converts to user units in the UI layer.
        sendCmd(CMD_SET_UNIT, byteArrayOf(0x01))
    }

    private fun sendSetTime() {
        val c = Calendar.getInstance()
        // [loYear, hiYear, month(1..12), day, hour, min, sec, dow(1..7 Mon..Sun)]
        val year = c.get(Calendar.YEAR)
        // Java DOW: SUN=1..SAT=7; protocol expects MON=1..SUN=7.
        val dow = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val payload = byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            dow.toByte()
        )
        sendCmd(CMD_SET_SCALE_CLOCK, payload)
    }

    private fun sendUserInfo(user: ScaleUser, weightTenthKg: Int?) {
        // Encrypted USER_INFO payload format (matches v2.5.4 exactly):
        //   auth(7) || age|sexBit(1) || height(1) || 0x00(1) || weightLE(2)
        //          || resistanceLE(2) || 0x1C 0xE2 (2 constant)
        // Total = 14 bytes.
        val sexBit = if (user.gender.isMale()) 0x00 else 0x80
        val age = (user.age and 0xFF) or sexBit
        val height = user.bodyHeight.toInt() and 0xFF
        val w = (weightTenthKg ?: (user.initialWeight * 10f).toInt()).coerceAtLeast(0)
        val tail = ByteArrayOutputStream().apply {
            write(byteArrayOf(age.toByte(), height.toByte(), 0x00))
            write(HuaweiAhCh100Protocol.le16(w))
            // Resistance "unknown" sentinel; the scale measures and ignores
            // whatever we pass here.
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            write(byteArrayOf(0x1C.toByte(), 0xE2.toByte()))
        }.toByteArray()

        val full = authCode + tail
        sendCmdEncrypted(CMD_USER_INFO, full)
    }

    private fun sendGetVersion() = sendCmd(CMD_GET_VERSION, byteArrayOf())

    @Suppress("unused") // hooked up when we re-enable history pulls
    private fun sendGetHistoryFirst() {
        // Legacy: payload is auth || xor(auth), but lengthByte on wire is
        // 7 (legacy used "0x07 - 1" + 1 inside AHsendCommand → 0x07).
        val chk = HuaweiAhCh100Protocol.xorChecksum(authCode)
        val pl = authCode + byteArrayOf(chk)
        val frame = HuaweiAhCh100Protocol.buildPlainCommand(CMD_GET_RECORD, pl, macBytes(), explicitLen = 0x07)
        writeTo(SERVICE, CHAR_TX, frame, withResponse = true)
    }

    private fun sendGetHistoryNext() {
        sendCmd(CMD_GET_RECORD, byteArrayOf(0x01))
    }

    // --- Wire helpers ------------------------------------------------------

    /** Send a plain (non-encrypted) command. */
    private fun sendCmd(cmd: Byte, payload: ByteArray) {
        val frame = HuaweiAhCh100Protocol.buildPlainCommand(cmd, payload, macBytes())
        logD("→ CMD 0x%02X len=%d (plain)".format(cmd.toInt() and 0xFF, payload.size))
        writeTo(SERVICE, CHAR_TX, frame, withResponse = true)
    }

    /** Send an AES-CTR encrypted command (USER_INFO). */
    private fun sendCmdEncrypted(cmd: Byte, payload: ByteArray) {
        val mk = magicKey ?: run {
            logW("magicKey missing; dropping encrypted cmd 0x%02X".format(cmd.toInt() and 0xFF))
            return
        }
        val frame = HuaweiAhCh100Protocol.buildEncryptedCommand(cmd, payload, mk, macBytes())
        logD("→ CMD* 0x%02X len=%d (encrypted)".format(cmd.toInt() and 0xFF, payload.size))
        writeTo(SERVICE, CHAR_TX, frame, withResponse = true)
    }

    // --- Misc helpers ------------------------------------------------------

    private fun ts(d: Date): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)

    private fun ByteArray.toHex(maxBytes: Int): String {
        val sb = StringBuilder()
        val n = minOf(maxBytes, size)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append("%02X".format(this[i].toInt() and 0xFF))
        }
        if (size > n) sb.append(" …")
        return sb.toString()
    }
}
