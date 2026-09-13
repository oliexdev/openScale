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
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.UUID
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

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
 *   [8]     rolling sequence counter
 *   [9-11]  model/vendor identifier
 *   [12-14] vendor flags / padding
 *   [15]    status flags
 *   [17-18] weight, little-endian uint16, 0.01 kg per count
 *   [19-20] impedance/resistance, little-endian uint16, ohms (on models with BIA)
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
 * - **Status 0x23 / 0x15** — used by the Renpho ES-26M-W / Yolanda BIA family, which runs
 *   status 0x02 / 0x14 while moving and latches to 0x23 or 0x15 when settled.
 *
 * Because a false "stable" writes a wrong weight into the user's history silently,
 * bit 0 / 0x23 is gated on device model signatures (see [FT26R_SIGNATURE] and
 * [isBiaCapable]); every other unrecognised AABB device keeps the original bit-5-only rule.
 */
internal object QnBroadcastAdv {

    const val COMPANY_ID = 0xFFFF

    private const val MAGIC_0 = 0xAA.toByte()
    private const val MAGIC_1 = 0xBB.toByte()
    private const val STATUS_IDX = 15
    private const val WEIGHT_LO = 17
    private const val WEIGHT_HI = 18
    private const val IMPEDANCE_LO = 19
    private const val IMPEDANCE_HI = 20
    private const val MIN_LEN = 19

    /** Bit 5: the original encoding, honoured for every AABB device. */
    const val FLAG_STABLE_BIT = 0x20

    /** Bit 0: FT-26R family stable flag (status 0x15). */
    const val FLAG_STABLE_BIT_FT26R = 0x01

    /** Status 0x23: Renpho ES-26M-W / Yolanda BIA stable flag. */
    const val STATUS_STABLE_YOLANDA = 0x23

    private const val WEIGHT_MIN_KG = 0.5f
    private const val WEIGHT_MAX_KG = 300f
    private const val IMPEDANCE_MIN_OHM = 200
    private const val IMPEDANCE_MAX_OHM = 1500

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
        val isFt26rFamily: Boolean,
        val isBiaCapable: Boolean,
        val impedanceOhm: Float? = null
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
     * True when [data] matches the Yolanda / Renpho BIA broadcast payload fingerprint:
     * - Minimum 21 bytes
     * - Not a weight-only FT-26R unit
     * - Fixed vendor padding [12-14] = [FF FF FF]
     */
    fun isBiaCapable(data: ByteArray): Boolean {
        if (data.size < 21 || hasFt26rSignature(data)) return false
        return data.size >= 15 &&
                data[12] == 0xFF.toByte() &&
                data[13] == 0xFF.toByte() &&
                data[14] == 0xFF.toByte()
    }

    /**
     * Extracts bio-impedance resistance from bytes [19-20] (little-endian uint16) if present.
     * Raw ADC counts on Yolanda/Renpho broadcast scales are reported in tenths of an ohm
     * (e.g. 5452 -> 545.2 Ohm).
     */
    private fun extractImpedance(data: ByteArray, biaCapable: Boolean): Float? {
        if (!biaCapable || data.size < IMPEDANCE_HI + 1) return null
        val raw = (data[IMPEDANCE_LO].toInt() and 0xFF) or
                ((data[IMPEDANCE_HI].toInt() and 0xFF) shl 8)
        return when {
            raw in 2000..15000 -> raw / 10.0f
            raw in 200..1500   -> raw.toFloat()
            else               -> null
        }
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
        val isBia = isBiaCapable(data)

        val stable = when {
            isFt26r -> (statusByte and (FLAG_STABLE_BIT or FLAG_STABLE_BIT_FT26R)) != 0
            isBia   -> statusByte == STATUS_STABLE_YOLANDA || (statusByte and (FLAG_STABLE_BIT or FLAG_STABLE_BIT_FT26R)) != 0
            else    -> (statusByte and FLAG_STABLE_BIT) != 0
        }

        return Frame(
            weightKg = weightKg,
            statusByte = statusByte,
            stable = stable,
            isFt26rFamily = isFt26r,
            isBiaCapable = isBia,
            impedanceOhm = extractImpedance(data, isBia)
        )
    }
}

/**
 * Handler for QN-lineage scales operating in non-connectable broadcast mode
 * (ADV_NONCONN_IND, Variant 3 of the ES-CS20M family, Renpho ES-26M-W, FITINDEX FT-26R).
 *
 * These devices advertise weight and optional bio-impedance data via BLE Manufacturer
 * Specific Data using the AABB protocol (Company ID 0xFFFF). They cannot be connected via
 * GATT and therefore never expose service UUIDs (0xFFE0 / 0xFFF0) in their advertisements.
 *
 * The wire format, including the per-model stable-flag rules and impedance extraction,
 * lives in [QnBroadcastAdv] so it can be unit tested without Android.
 *
 * Body composition is calculated using [TrisaBodyAnalyzeLib] when impedance is broadcast.
 *
 * Operated by [BroadcastScaleAdapter]; all GATT hooks are intentional no-ops.
 */
class QNHandlerBroadcast : ScaleDeviceHandler() {

    private var hasPublished = false
    private var firstStableTimeMs: Long? = null
    private var pendingMeasurement: ScaleMeasurement? = null

    companion object {
        private const val BIA_WAIT_TIMEOUT_MS = 2500L
    }

    // ── Device identification ─────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The sole reliable fingerprint for Variant 3 is the AABB magic header in
        // Manufacturer Specific Data (Company ID 0xFFFF). Name-based matching is
        // intentionally omitted: "renpho" and "qn-scale" names are also used by
        // connectable Variants 1 and 2, so a name match without the AABB payload
        // would cause this handler to shadow the GATT handlers for those devices.
        val mData = device.manufacturerData?.get(QnBroadcastAdv.COMPANY_ID) ?: return null
        if (!QnBroadcastAdv.hasAabbMagic(mData)) return null

        val isBia = QnBroadcastAdv.isBiaCapable(mData)
        val caps = if (isBia) {
            setOf(DeviceCapability.LIVE_WEIGHT_STREAM, DeviceCapability.BODY_COMPOSITION)
        } else {
            setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        }

        return DeviceSupport(
            displayName  = "QN Scale (Broadcast)",
            capabilities = caps,
            implemented  = caps,
            linkMode     = LinkMode.BROADCAST_ONLY
        )
    }

    // ── GATT hooks (intentional no-ops — device is non-connectable) ───────────

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
        firstStableTimeMs = null
        pendingMeasurement = null
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
                    "imp=${frame.impedanceOhm} ft26r=${frame.isFt26rFamily} bia=${frame.isBiaCapable}"
        )

        if (!frame.stable) return BroadcastAction.CONSUMED_KEEP_SCANNING

        val measurement = ScaleMeasurement().apply {
            userId   = user.id
            this[MeasurementType.WEIGHT] = Kg(frame.weightKg)
            dateTime = Date()

            frame.impedanceOhm?.let { imp ->
                this[MeasurementType.IMPEDANCE] = Ohm(imp)
                if (user.bodyHeight > 0f) {
                    val trisa = TrisaBodyAnalyzeLib(
                        if (user.gender.isMale()) 1 else 0,
                        user.age,
                        user.bodyHeight
                    )
                    val trisaImpedance = if (imp < 410f) 3.0f else 0.3f * (imp - 400f)
                    this[MeasurementType.BODY_FAT] = Percent(trisa.getFat(frame.weightKg, trisaImpedance))
                    this[MeasurementType.WATER] = Percent(trisa.getWater(frame.weightKg, trisaImpedance))
                    this[MeasurementType.MUSCLE] = Percent(trisa.getMuscle(frame.weightKg, trisaImpedance))
                    this[MeasurementType.BONE] = Kg(trisa.getBone(frame.weightKg, trisaImpedance))
                }
            }
        }

        // On weight-only scales or when impedance is already present, publish immediately.
        if (frame.impedanceOhm != null || !frame.isBiaCapable) {
            LogManager.i(TAG, "AABB stable weight ${"%.2f".format(frame.weightKg)} kg (impedance=${frame.impedanceOhm}) → publish")
            publish(measurement)
            hasPublished = true
            return BroadcastAction.CONSUMED_STOP
        }

        // On BIA scales, weight locks before impedance finishes computing.
        // Wait up to BIA_WAIT_TIMEOUT_MS for the impedance frame before falling back.
        val now = System.currentTimeMillis()
        val firstStable = firstStableTimeMs
        if (firstStable == null) {
            firstStableTimeMs = now
            pendingMeasurement = measurement
            LogManager.d(TAG, "AABB weight settled without impedance; waiting for BIA frame...")
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        if (now - firstStable < BIA_WAIT_TIMEOUT_MS) {
            pendingMeasurement = measurement
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        LogManager.i(TAG, "AABB BIA window timed out; publishing stable weight ${"%.2f".format(frame.weightKg)} kg without impedance")
        publish(pendingMeasurement ?: measurement)
        hasPublished = true
        return BroadcastAction.CONSUMED_STOP
    }
}
