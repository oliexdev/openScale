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

import android.bluetooth.le.ScanResult
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.UUID
import com.health.openscale.core.data.Kg

/**
 * Parser for the QN/Renpho "AABB" broadcast advertisement (company ID 0xFFFF).
 *
 * Pure Kotlin, no Android dependencies, so the wire format can be tested on the JVM.
 *
 * Payload layout (indices are into the manufacturer-specific data, i.e. after the
 * 2-byte company ID has been stripped by ScanRecord):
 * ```
 *   [0-1]   AA BB      magic header
 *   [2-7]   device address
 *   [15]    status flags
 *   [17-18] weight, little-endian uint16, 0.01 kg per count
 * ```
 *
 * ## Stable-flag encodings
 *
 * The bit that marks a settled measurement is not the same on every model:
 *
 * - **Bit 5 (0x20)** — the encoding this handler was originally written against.
 * - **Bit 0 (0x01)** — used by the FITINDEX FT-26R family, which runs status 0x14
 *   while the weight is moving and latches to 0x15 when the display settles. These
 *   units never set 0x20 at all.
 *
 * Because a false "stable" writes a wrong weight into the user's history silently,
 * bit 0 is only honoured for payloads that also carry the FT-26R's fixed signature
 * (see [FT26R_SIGNATURE]); every other device keeps the original bit-5-only rule.
 */
internal object QnBroadcastAdv {

    const val COMPANY_ID = 0xFFFF

    private const val MAGIC_0 = 0xAA.toByte()
    private const val MAGIC_1 = 0xBB.toByte()
    private const val STATUS_IDX = 15
    private const val WEIGHT_LO = 17
    private const val WEIGHT_HI = 18
    private const val MIN_LEN = 19

    /** Bit 5: the original encoding, honoured for every AABB device. */
    const val FLAG_STABLE_BIT = 0x20

    /** Bit 0: FT-26R family only, gated on [FT26R_SIGNATURE]. */
    const val FLAG_STABLE_BIT_FT26R = 0x01

    private const val WEIGHT_MIN_KG = 0.5f
    private const val WEIGHT_MAX_KG = 300f

    /**
     * Bytes [19-21] of the FT-26R payload, constant across every captured frame from a
     * physical FT-26R-W (both weighing sessions and the idle advertisement) while the
     * counter, status and weight fields all varied around them.
     *
     * This is a conservative gate, not a claim about what the bytes mean: it is derived
     * from one unit, so it is used only to *widen* acceptance for devices that match it.
     * A device that does not match keeps the pre-existing bit-5 behaviour, so an
     * unrecognised AABB model can never be made worse by this.
     */
    private val FT26R_SIGNATURE = byteArrayOf(0x51, 0x0E, 0x03)
    private const val SIGNATURE_OFFSET = 19

    /** A decoded advertisement. [stable] means the scale reports the reading as final. */
    data class Frame(
        val weightKg: Float,
        val statusByte: Int,
        val stable: Boolean,
        val isFt26rFamily: Boolean
    )

    /** True when [data] starts with the AABB magic header that identifies this family. */
    fun hasAabbMagic(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1

    /** True when [data] carries the FT-26R's fixed byte signature at [SIGNATURE_OFFSET]. */
    fun hasFt26rSignature(data: ByteArray): Boolean {
        if (data.size < SIGNATURE_OFFSET + FT26R_SIGNATURE.size) return false
        return FT26R_SIGNATURE.indices.all { data[SIGNATURE_OFFSET + it] == FT26R_SIGNATURE[it] }
    }

    /**
     * Decodes [data], or returns null when it is not a usable AABB measurement frame:
     * too short, wrong magic, or a weight outside the plausible range (which is what
     * rejects the zero-weight idle advertisement).
     */
    fun parse(companyId: Int, data: ByteArray?): Frame? {
        if (companyId != COMPANY_ID || data == null) return null
        if (data.size < MIN_LEN) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null

        val rawWeight = (data[WEIGHT_LO].toInt() and 0xFF) or
                ((data[WEIGHT_HI].toInt() and 0xFF) shl 8)
        val weightKg = rawWeight / 100.0f
        if (weightKg < WEIGHT_MIN_KG || weightKg > WEIGHT_MAX_KG) return null

        val statusByte = data[STATUS_IDX].toInt() and 0xFF
        val isFt26r = hasFt26rSignature(data)

        val stableMask =
            if (isFt26r) FLAG_STABLE_BIT or FLAG_STABLE_BIT_FT26R else FLAG_STABLE_BIT

        return Frame(
            weightKg = weightKg,
            statusByte = statusByte,
            stable = (statusByte and stableMask) != 0,
            isFt26rFamily = isFt26r
        )
    }
}

/**
 * Handler for QN-lineage scales operating in non-connectable broadcast mode
 * (ADV_NONCONN_IND, Variant 3 of the ES-CS20M family).
 *
 * These devices advertise weight-only data via BLE Manufacturer Specific Data
 * using the AABB protocol (Company ID 0xFFFF). They cannot be connected via
 * GATT and therefore never expose service UUIDs (0xFFE0 / 0xFFF0) in their
 * advertisements.
 *
 * The wire format, including the per-model stable-flag rules, lives in
 * [QnBroadcastAdv] so it can be unit tested without Android.
 *
 * Body composition is not available without GATT/BIA.
 *
 * Operated by [BroadcastScaleAdapter]; all GATT hooks are intentional no-ops.
 */
class QNHandlerBroadcast : ScaleDeviceHandler() {

    private var hasPublished = false

    // ── Device identification ─────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The sole reliable fingerprint for Variant 3 is the AABB magic header in
        // Manufacturer Specific Data (Company ID 0xFFFF). Name-based matching is
        // intentionally omitted: "renpho" and "qn-scale" names are also used by
        // connectable Variants 1 and 2, so a name match without the AABB payload
        // would cause this handler to shadow the GATT handlers for those devices.
        val isAabb = device.manufacturerData
            ?.get(QnBroadcastAdv.COMPANY_ID)
            ?.let { QnBroadcastAdv.hasAabbMagic(it) } ?: false

        if (!isAabb) return null

        return DeviceSupport(
            displayName  = "QN Scale (Broadcast)",
            capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            implemented  = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode     = LinkMode.BROADCAST_ONLY
        )
    }

    // ── GATT hooks (intentional no-ops — device is non-connectable) ───────────

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
    }

    // ── Broadcast reception ───────────────────────────────────────────────────

    /**
     * Called by [BroadcastScaleAdapter] for every incoming advertisement from
     * the target address.
     *
     * Returns:
     *   [BroadcastAction.IGNORED]              — payload missing, wrong magic, or implausible weight
     *   [BroadcastAction.CONSUMED_KEEP_SCANNING] — weight present but measurement not yet stable
     *   [BroadcastAction.CONSUMED_STOP]        — stable weight published; adapter stops scanning
     */
    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        if (hasPublished) return BroadcastAction.IGNORED

        val record = result.scanRecord ?: return BroadcastAction.IGNORED

        // ScanRecord.getManufacturerSpecificData() strips the 2-byte Company ID
        // prefix, so data[0] is the first application byte (0xAA magic).
        val data = record.getManufacturerSpecificData(QnBroadcastAdv.COMPANY_ID)
            ?: return BroadcastAction.IGNORED

        val frame = QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, data)
        if (frame == null) {
            LogManager.d(TAG, "AABB frame not usable (length, magic or weight range); discarded")
            return BroadcastAction.IGNORED
        }

        LogManager.d(
            TAG,
            "AABB weight=${"%.2f".format(frame.weightKg)} kg stable=${frame.stable} " +
                    "status=0x${frame.statusByte.toString(16).padStart(2, '0')} " +
                    "ft26r=${frame.isFt26rFamily}"
        )

        if (!frame.stable) return BroadcastAction.CONSUMED_KEEP_SCANNING

        val measurement = ScaleMeasurement().apply {
            userId   = user.id
            this[MeasurementType.WEIGHT] = Kg(frame.weightKg)
            dateTime = Date()
        }

        LogManager.i(TAG, "AABB stable weight ${"%.2f".format(frame.weightKg)} kg → publish")
        publish(measurement)

        hasPublished = true
        return BroadcastAction.CONSUMED_STOP
    }
}
