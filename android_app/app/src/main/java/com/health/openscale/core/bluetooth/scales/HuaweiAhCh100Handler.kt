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
 * Huawei AH100 / CH100 body-fat scale handler.
 *
 * The two products use the same Chipsea CST34M97-based hardware with an
 * identical wire protocol and only differ in the BLE advertisement name
 * (`AH100` vs `CH100`); [supportFor] matches either.
 *
 * The wire-protocol primitives — XOR obfuscation, AES-CTR, frame builders
 * and the measurement parser — are the [Companion] object at the bottom of
 * this file. They are pure Kotlin (no Android dependencies), so the parsing
 * logic is locked by the JVM unit tests in `HuaweiAhCh100HandlerTest` and we
 * don't have to re-prove it on a real scale.
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
class HuaweiAhCh100Handler : ScaleDeviceHandler() {

    // --- Identifiers --------------------------------------------------------

    private val SERVICE = uuid16(0xFAA0)
    private val CHAR_TX = uuid16(0xFAA1) // host -> scale: write
    private val CHAR_RX = uuid16(0xFAA2) // scale -> host: notify

    /**
     * BLE advertisement name -> UI display name. Both products use the same
     * Chipsea CST34M97 hardware, so one handler serves both.
     */
    private val supportedAdverts = mapOf(
        "AH100" to "Huawei AH100",
        "CH100" to "Huawei CH100",
    )

    // We need the scale MAC for the XOR obfuscation. Cache it from
    // ScannedDeviceInfo.address as soon as supportFor() approves.
    private var sessionMac: String? = null

    private fun macBytes(): ByteArray {
        val s = sessionMac
        if (s.isNullOrBlank()) {
            logW("sessionMac is null/blank; XOR-obfuscation will be a no-op and frames will not parse")
            return ByteArray(6)
        }
        return runCatching { macStringToBytes(s) }
            .onFailure { logW("Failed to parse sessionMac '$s': ${it.message}") }
            .getOrElse { ByteArray(6) }
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Some Huawei firmware revisions append trailing NULs / whitespace to
        // the advert name, and some Honor / OEM rebrands prefix the vendor
        // name. Strip those so our match is robust without overshooting into
        // the CH100S handler's territory.
        val raw = device.name
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
        val matchedAdvert = supportedAdverts.keys.firstOrNull { it in candidates } ?: return null
        if (cleaned != raw) {
            logD("supportFor: matched advert '$raw' -> '$cleaned' as '$matchedAdvert'")
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
            displayName = supportedAdverts.getValue(matchedAdvert),
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
        authCode = buildAuthToken(user.id)
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
        val deobfTail = deobfuscateTail(data, macBytes())

        logD("← op=0x%02X len=%d %s".format(op.toInt() and 0xFF, deobfTail.size, deobfTail.toHex(24)))

        when (op) {
            NTFY_WAKEUP -> {
                scaleAwake = true
                if (!authorised) sendAuth()
            }

            NTFY_AUTH_RESULT -> {
                if (deobfTail.isNotEmpty() && deobfTail[0].toInt() == 1) {
                    authorised = true
                    magicKey = deriveMagicKey(authCode, macBytes())
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
                if (data[0] == FRAME_NOTIFY_ENCRYPTED) {
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
                if (data[0] == FRAME_NOTIFY_ENCRYPTED) {
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
            val m = decodeFirstHalf(first, mk, macBytes())
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
            val m = decodeFirstHalf(first, mk, macBytes())
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

    private fun publishMeasurement(m: Measurement, viaSingleFrame: Boolean) {
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
            write(le16(w))
            // Resistance "unknown" sentinel; the scale measures and ignores
            // whatever we pass here.
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            write(byteArrayOf(0x1C.toByte(), 0xE2.toByte()))
        }.toByteArray()

        val full = authCode + tail
        sendCmdEncrypted(full)
    }

    private fun sendGetVersion() = sendCmd(CMD_GET_VERSION, byteArrayOf())

    @Suppress("unused") // hooked up when we re-enable history pulls
    private fun sendGetHistoryFirst() {
        // Legacy: payload is auth || xor(auth), but lengthByte on wire is
        // 7 (legacy used "0x07 - 1" + 1 inside AHsendCommand → 0x07).
        val chk = xorChecksum(authCode)
        val pl = authCode + byteArrayOf(chk)
        val frame = buildPlainCommand(CMD_GET_RECORD, pl, macBytes(), explicitLen = 0x07)
        writeTo(SERVICE, CHAR_TX, frame, withResponse = true)
    }

    private fun sendGetHistoryNext() {
        sendCmd(CMD_GET_RECORD, byteArrayOf(0x01))
    }

    // --- Wire helpers ------------------------------------------------------

    /** Send a plain (non-encrypted) command. */
    private fun sendCmd(cmd: Byte, payload: ByteArray) {
        val frame = buildPlainCommand(cmd, payload, macBytes())
        logD("→ CMD 0x%02X len=%d (plain)".format(cmd.toInt() and 0xFF, payload.size))
        writeTo(SERVICE, CHAR_TX, frame, withResponse = true)
    }

    /** Send an AES-CTR encrypted command (USER_INFO). */
    private fun sendCmdEncrypted(payload: ByteArray) {
        val mk = magicKey ?: run {
            logW("magicKey missing; dropping encrypted cmd 0x%02X".format(CMD_USER_INFO.toInt() and 0xFF))
            return
        }
        val frame = buildEncryptedCommand(CMD_USER_INFO, payload, mk, macBytes())
        logD("→ CMD* 0x%02X len=%d (encrypted)".format(CMD_USER_INFO.toInt() and 0xFF, payload.size))
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

    // --- Wire protocol ------------------------------------------------------

    /**
     * Decoded measurement, as returned by [parseMeasurement].
     */
    internal data class Measurement(
        val userId: Int,
        val weightKg: Float,
        val fatPct: Float,
        val impedanceOhm: Int,
        val dateTime: Date?,
        val rawDecrypted: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Measurement

            if (userId != other.userId) return false
            if (weightKg != other.weightKg) return false
            if (fatPct != other.fatPct) return false
            if (impedanceOhm != other.impedanceOhm) return false
            if (dateTime != other.dateTime) return false
            if (!rawDecrypted.contentEquals(other.rawDecrypted)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = userId
            result = 31 * result + weightKg.hashCode()
            result = 31 * result + fatPct.hashCode()
            result = 31 * result + impedanceOhm
            result = 31 * result + (dateTime?.hashCode() ?: 0)
            result = 31 * result + rawDecrypted.contentHashCode()
            return result
        }
    }

    /**
     * Wire-protocol primitives for the Huawei AH100 / CH100 body-fat scale.
     *
     * This is a faithful Kotlin port of the v2.5.4 Java handler that was the
     * last known-good implementation (see openScale issues #1206, #1276, #1280
     * for the regression history in the 3.x rewrite). All bit-fiddling lives
     * here and is exercised by `HuaweiAhCh100HandlerTest`, so the
     * [HuaweiAhCh100Handler] BLE state machine above can stay focused on the
     * connection lifecycle.
     *
     * Protocol summary:
     * - Service 0xFAA0; write TX 0xFAA1; notify RX 0xFAA2.
     * - Every payload is XOR-obfuscated with the scale's BLE MAC (repeating
     *   6-byte key); see [obfuscate]. This is symmetric.
     * - Some commands and all measurement frames are additionally AES-CTR
     *   encrypted with a session-derived [deriveMagicKey] and the fixed
     *   [INITIAL_IV]. AES is also symmetric in CTR mode.
     * - Frames host->scale: `[start, lengthByte, cmd, ...obfuscated payload...]`
     *   - start = [FRAME_PLAIN] (0xDB) for non-encrypted commands. v2.5.4 wrote
     *     `lengthByte = payload.size + 1`. The 3.x rewrite changed this to
     *     `payload.size`, which appears to be one of the regressions.
     *   - start = [FRAME_ENCRYPTED] (0xDC) for encrypted commands (only USER_INFO
     *     in practice). v2.5.4 wrote `lengthByte = payload.size`.
     * - Frames scale->host: `[start, lengthByte, op, ...obfuscated tail...]`
     *   - start = [FRAME_NOTIFY_PLAIN] (0xBD) for plain notifications.
     *   - start = [FRAME_NOTIFY_ENCRYPTED] (0xBC) for both halves of an
     *     encrypted measurement / history record. The first half carries op
     *     0x0E (live) or 0x10 (history); the second half carries 0x8E / 0x90.
     *
     * Measurement decoding:
     * - **Decrypt only the first half's deobfuscated tail** (this is what
     *   v2.5.4 does and what the captures confirm). The keystream of AES-CTR
     *   is contiguous so decrypting the merged buffer would give the same
     *   bytes for the first 16, but the second half then contains junk; we
     *   keep things simple and decrypt just what we need.
     * - The decrypted layout is documented on [parseMeasurement].
     */
    internal companion object {

        // ---------- Hard-coded keys / IV (v2.5.4) ------------------------------
        //
        // ByteArray is mutable so we keep the canonical bytes in private backing
        // fields and hand out fresh copies. Internal call sites work on the copy
        // they receive; mis-use by tests / future contributors cannot corrupt
        // the values for subsequent calls.

        private val INITIAL_KEY_BYTES: ByteArray =
            hexToBytes("3D A2 78 4A FB 87 B1 2A 98 0F DE 34 56 73 21 56")

        private val INITIAL_IV_BYTES: ByteArray =
            hexToBytes("4E F7 64 32 2F DA 76 32 12 3D EB 87 90 FE A2 19")

        /**
         * AES-128 key fragment. Returns a fresh copy on every access:
         * bytes [0..6] of `magicKey` come from the session (obfuscated auth
         * token); bytes [7..15] are the tail of this constant.
         */
        val INITIAL_KEY: ByteArray get() = INITIAL_KEY_BYTES.copyOf()

        /** AES-CTR IV. Returns a fresh copy on every access. */
        val INITIAL_IV: ByteArray get() = INITIAL_IV_BYTES.copyOf()

        // ---------- Frame start bytes -----------------------------------------

        /** Host->scale plain frame start (XOR-obfuscated payload only). */
        const val FRAME_PLAIN: Byte = 0xDB.toByte()

        /** Host->scale AES-encrypted frame start (USER_INFO etc). */
        const val FRAME_ENCRYPTED: Byte = 0xDC.toByte()

        /** Scale->host: first byte for the two halves of an encrypted measurement. */
        const val FRAME_NOTIFY_ENCRYPTED: Byte = 0xBC.toByte()

        /** Scale->host: first byte for plain notifications. */
        const val FRAME_NOTIFY_PLAIN: Byte = 0xBD.toByte()

        // ---------- Notification opcodes (data[2] from the scale) -------------

        const val NTFY_WAKEUP: Byte = 0x00
        const val NTFY_GO_SLEEP: Byte = 0x01
        const val NTFY_UNITS_SET: Byte = 0x02
        const val NTFY_SCALE_CLOCK: Byte = 0x08
        const val NTFY_SCALE_VERSION: Byte = 0x0C
        const val NTFY_MEASUREMENT: Byte = 0x0E
        const val NTFY_MEASUREMENT_WEIGHT: Byte = 0x0F
        const val NTFY_MEASUREMENT2: Byte = 0x8E.toByte()
        const val NTFY_HISTORY_RECORD: Byte = 0x10
        const val NTFY_HISTORY_RECORD2: Byte = 0x90.toByte()
        const val NTFY_HISTORY_UPLOAD_DONE: Byte = 0x19
        const val NTFY_USER_CHANGED: Byte = 0x20
        const val NTFY_AUTH_RESULT: Byte = 0x26
        const val NTFY_BIND_OK: Byte = 0x27

        // ---------- Command opcodes (cmd byte we send) ------------------------

        const val CMD_SET_UNIT: Byte = 2
        const val CMD_SET_SCALE_CLOCK: Byte = 8
        const val CMD_USER_INFO: Byte = 9
        const val CMD_GET_RECORD: Byte = 11
        const val CMD_GET_VERSION: Byte = 12
        const val CMD_FAT_RESULT_ACK: Byte = 19
        const val CMD_AUTH: Byte = 36
        const val CMD_BIND_USER: Byte = 37

        // ---------- Primitives -----------------------------------------------

        /**
         * XOR every byte of [data] with the scale's MAC bytes (repeating).
         *
         * Self-inverse: `obfuscate(obfuscate(x, mac), mac) == x`.
         *
         * @param mac 6-byte BLE address in display order (i.e. for "AA:BB:CC:DD:EE:FF",
         *   the bytes [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]).
         */
        fun obfuscate(data: ByteArray, mac: ByteArray): ByteArray {
            if (mac.isEmpty()) return data.copyOf()
            val out = data.copyOf()
            var m = 0
            for (i in out.indices) {
                if (m >= mac.size) m = 0
                out[i] = (out[i].toInt() xor (mac[m].toInt() and 0xFF)).toByte()
                m++
            }
            return out
        }

        /** AES/CTR/NoPadding. Symmetric: encrypt is the same operation as decrypt. */
        fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val iv16 = ByteArray(16).apply { System.arraycopy(iv, 0, this, 0, min(16, iv.size)) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
            return cipher.doFinal(data)
        }

        /** XOR checksum of [buf]'s bytes — used by AUTH token construction. */
        fun xorChecksum(buf: ByteArray, off: Int = 0, len: Int = buf.size): Byte {
            var x = 0
            for (i in off until (off + len)) x = x xor (buf[i].toInt() and 0xFF)
            return (x and 0xFF).toByte()
        }

        /**
         * Build the 7-byte AUTH token used in the handshake:
         *   `[0x11, 0x22, 0x33, 0x44, 0x55, chk, userId]`
         * where `chk` is chosen so the whole array XORs to zero.
         */
        fun buildAuthToken(userId: Int): ByteArray {
            val auth = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, (userId and 0xFF).toByte())
            auth[5] = xorChecksum(auth)
            return auth
        }

        /**
         * Derive the AES-128 magicKey for the session.
         *
         * v2.5.4 layout: `obfuscate(authCode, mac) || INITIAL_KEY[7..15]`.
         * Result is always 16 bytes (7 + 9).
         */
        fun deriveMagicKey(authCode: ByteArray, mac: ByteArray): ByteArray {
            require(authCode.size == 7) { "authCode must be 7 bytes; got ${authCode.size}" }
            val obfAuth = obfuscate(authCode, mac)
            val tail = INITIAL_KEY_BYTES.copyOfRange(7, INITIAL_KEY_BYTES.size)
            return obfAuth + tail
        }

        // ---------- Frame writers --------------------------------------------

        /**
         * Build a host->scale plain command frame (start byte [FRAME_PLAIN]).
         *
         * IMPORTANT: matches v2.5.4's `lengthByte = payload.size + 1`. The 3.x
         * rewrite used `payload.size` which appears to break the AUTH handshake
         * on at least some firmware revisions and is one of the regressions
         * tracked by issues #1206 / #1280.
         *
         * @param explicitLen overrides the length byte (legacy callers used
         *   this for `GET_RECORD` to encode 0x06 even though payload was 7 bytes).
         */
        fun buildPlainCommand(
            cmd: Byte,
            payload: ByteArray,
            mac: ByteArray,
            explicitLen: Int? = null
        ): ByteArray {
            val len = explicitLen ?: (payload.size + 1)
            val header = byteArrayOf(FRAME_PLAIN, len.toByte(), cmd)
            return header + obfuscate(payload, mac)
        }

        /**
         * Build a host->scale AES-encrypted command frame (start byte
         * [FRAME_ENCRYPTED]).
         *
         * Length byte equals plaintext payload size (matches v2.5.4's
         * `lengthByte = payload.size + 0`).
         */
        fun buildEncryptedCommand(
            cmd: Byte,
            payload: ByteArray,
            magicKey: ByteArray,
            mac: ByteArray,
            iv: ByteArray = INITIAL_IV
        ): ByteArray {
            val encrypted = aesCtr(payload, magicKey, iv)
            val header = byteArrayOf(FRAME_ENCRYPTED, payload.size.toByte(), cmd)
            return header + obfuscate(encrypted, mac)
        }

        // ---------- Notification helpers -------------------------------------

        /**
         * Strip the 3-byte header and de-obfuscate the tail of a scale->host frame.
         * Returns the bytes that, for plain notifications, are the actual
         * payload, or, for the encrypted halves, the bytes that need to be
         * fed through [aesCtr] before parsing.
         */
        fun deobfuscateTail(frame: ByteArray, mac: ByteArray): ByteArray {
            if (frame.size <= 3) return ByteArray(0)
            return obfuscate(frame.copyOfRange(3, frame.size), mac)
        }

        // ---------- Measurement decoding -------------------------------------

        /**
         * Parse the v2.5.4 byte layout from already-decrypted measurement bytes.
         *
         * Layout (little-endian unless noted):
         * ```
         * [0]      userId (1..10)
         * [1..2]   weight in tenth-kg (uint16 LE)
         * [3..4]   fat in tenth-percent (uint16 LE)
         * [5..6]   year (uint16 LE)
         * [7]      month, 1..12
         * [8]      day
         * [9]      hour
         * [10]     minute
         * [11]     second
         * [12]     weekday (informational, ignored)
         * [13..14] resistance / impedance in ohm (uint16 LE)
         * ```
         */
        fun parseMeasurement(decrypted: ByteArray): Measurement {
            require(decrypted.size >= 15) {
                "decrypted frame must be at least 15 bytes; got ${decrypted.size}"
            }
            val userId = decrypted[0].toInt() and 0xFF
            val weightTenth = u16le(decrypted, 1)
            val fatTenth = u16le(decrypted, 3)
            val year = u16le(decrypted, 5)
            val month = (decrypted[7].toInt() and 0xFF).coerceIn(1, 12)
            val day = decrypted[8].toInt() and 0xFF
            val hour = decrypted[9].toInt() and 0xFF
            val minute = decrypted[10].toInt() and 0xFF
            val second = decrypted[11].toInt() and 0xFF
            val impedance = u16le(decrypted, 13)

            val date: Date? = try {
                val cal = Calendar.getInstance().apply {
                    clear()
                    set(year, month - 1, day, hour, minute, second)
                }
                cal.time
            } catch (_: Throwable) {
                null
            }

            return Measurement(
                userId = userId,
                weightKg = weightTenth / 10f,
                fatPct = fatTenth / 10f,
                impedanceOhm = impedance,
                dateTime = date,
                rawDecrypted = decrypted.copyOf()
            )
        }

        /**
         * Convenience: take the *first half* of a two-part encrypted measurement
         * exactly as it arrived from the scale (3-byte header + obfuscated tail),
         * de-obfuscate, AES-CTR-decrypt with [magicKey] / [INITIAL_IV], and
         * parse via [parseMeasurement]. The second half is intentionally not
         * required — v2.5.4 ignored it and that captures-as-tested produces the
         * correct numbers.
         */
        fun decodeFirstHalf(firstHalfFrame: ByteArray, magicKey: ByteArray, mac: ByteArray): Measurement {
            val deobf = deobfuscateTail(firstHalfFrame, mac)
            val plain = aesCtr(deobf, magicKey, INITIAL_IV)
            return parseMeasurement(plain)
        }

        // ---------- Internal utils -------------------------------------------

        fun u16le(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        fun le16(v: Int): ByteArray =
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        fun hexToBytes(s: String): ByteArray {
            val clean = s.replace(" ", "").replace(":", "").replace("-", "")
            val even = if (clean.length % 2 == 0) clean else "0$clean"
            return ByteArray(even.length / 2) { i ->
                even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /** Convert "AA:BB:CC:DD:EE:FF" (or no separators) to 6 bytes in display order. */
        fun macStringToBytes(mac: String): ByteArray {
            val clean = mac.replace(":", "").replace("-", "")
            require(clean.length == 12) { "MAC must be 12 hex chars; got $mac" }
            return ByteArray(6) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
