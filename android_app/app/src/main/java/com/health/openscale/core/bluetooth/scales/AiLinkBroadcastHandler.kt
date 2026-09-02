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

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.AiLinkLib
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

/**
 * AiLink / eLink **broadcast** body-fat scales.
 *
 * Sold under many private labels driven by the "AiLink" app (`com.pingwang.elink`); the unit this
 * was developed against advertises as `EL1` and is retailed in Portugal as "BALANÇA BODY SMART
 * C/ APP". These scales are non-connectable: they never accept a GATT link and instead repeat the
 * complete measurement inside an encrypted manufacturer record, so this handler runs in
 * [LinkMode.BROADCAST_ONLY] and all GATT hooks are deliberate no-ops.
 *
 * The wire format, the TEA key derivation and the payload layout are documented on [AiLinkLib],
 * which does the decoding; this class only decides *which* decoded frame is worth recording.
 *
 * ## Which frame gets published
 * The scale streams `status = 0` frames with a live weight while the user settles, then latches a
 * single `status = 0xFF` frame that carries the final weight **and** the impedance, and repeats
 * that frame for as long as it stays awake. Only the completed frame is published, and only once
 * per session — mirroring the vendor app, which likewise ignores everything until the impedance
 * measurement has finished.
 *
 * Body composition is derived by openScale's own [StandardImpedanceLib] from the reported
 * impedance rather than by porting the vendor's formulas, consistent with the other
 * impedance-reporting handlers.
 *
 * ## Why only the completed frame's impedance is trusted
 * The impedance field is **stale until the scale's BIA phase finishes**: frames sent while the
 * user is still settling repeat the *previous* measurement's value, which makes an unchanging
 * impedance during `status 0` look deceptively like a hardcoded constant. It is not — verified
 * on hardware by a controlled experiment: barefoot, the scale completed reporting 500 ohms;
 * standing on it through footwear, the same BIA phase ran, detected the broken circuit and
 * completed with `status 3` and 0 ohms.
 *
 * Two rules follow, both implemented in [onAdvertisement]: only ever read impedance from the
 * `status 0xFF` frame, and treat 0 as "the scale's BIA pass failed" — publish the weight but no
 * body composition, rather than feeding a bogus 0 into [StandardImpedanceLib].
 */
class AiLinkBroadcastHandler : ScaleDeviceHandler() {

    companion object {
        /**
         * Advertised service that marks the AiLink *broadcast* profile
         * (`BleConfig.UUID_SERVER_BROADCAST_AILINK` in the vendor SDK). Connectable AiLink
         * devices use other services, so this is what keeps the handler to broadcast scales.
         */
        private const val SERVICE_BROADCAST_SHORT = 0xF0A0

        /** Guard against nonsense decodes; the hardware is a ~180 kg consumer scale. */
        private const val WEIGHT_MIN_KG = 1.0f
        private const val WEIGHT_MAX_KG = 300.0f
    }

    private val serviceBroadcast: UUID = uuid16(SERVICE_BROADCAST_SHORT)

    /** Publishing is single-shot per session; the scale repeats the final frame indefinitely. */
    private var hasPublished = false

    // --- Device identification ----------------------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The advertised name ("EL1") is a meaningless vendor default that other eLink products
        // share, so match on the broadcast service plus a manufacturer record that actually
        // checksums as an AiLink frame. That pair is specific and, unlike the name, verifiable.
        if (!device.serviceUuids.contains(serviceBroadcast)) return null

        val mfr = device.manufacturerData ?: return null
        if (firstAiLinkFrame(mfr) == null) return null

        return DeviceSupport(
            displayName = "AiLink Body Fat Scale",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            linkMode = LinkMode.BROADCAST_ONLY,
        )
    }

    // --- GATT hooks (intentional no-ops — the device is non-connectable) -----------------------

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
    }

    // --- Broadcast reception ------------------------------------------------------------------

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        if (hasPublished) return BroadcastAction.IGNORED

        val mfr = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        val frame = firstAiLinkFrame(mfr) ?: return BroadcastAction.IGNORED

        if (!frame.isComplete) {
            // Live weight while the user settles, or an idle frame with no load at all.
            logD("status=0x%02X seq=%d raw=%d — waiting for the scale to finish"
                .format(frame.status, frame.sequence, frame.rawWeight))
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // The vendor app rejects completed frames that are negative, zero, or in a unit we have
        // never observed; do the same rather than record a wrong measurement.
        val weightKg = frame.usableWeightKg
        if (weightKg == null) {
            logW("completed frame is not usable (unit=${frame.weightUnit} raw=${frame.rawWeight} " +
                    "negative=${frame.isNegative}); ignoring")
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        if (weightKg < WEIGHT_MIN_KG || weightKg > WEIGHT_MAX_KG) {
            logW("implausible weight ${weightKg}kg; ignoring")
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
        }

        // The scale zeroes the impedance when its BIA pass fails (status 0x03 in the vendor app),
        // so only derive body composition when it actually reported one. Height and age are
        // required inputs: ScaleUser defaults bodyHeight to -1, and the equations still return
        // positive, plausible-looking numbers from it.
        if (frame.impedance > 0 && user.bodyHeight > 0f && user.age > 0) {
            measurement[MeasurementType.IMPEDANCE] = Ohm(frame.impedance.toFloat())

            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = frame.impedance.toDouble(),
            )

            measurement[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
            measurement[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
            measurement[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
            measurement[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
            measurement[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat())
            measurement[MeasurementType.LBM] = Kg(lib.fatFreeMassKg.toFloat())
        } else {
            logD("scale reported no impedance; publishing weight only")
        }

        logI("publishing weight=${weightKg}kg impedance=${frame.impedance} seq=${frame.sequence}")
        hasPublished = true
        publish(measurement)
        return BroadcastAction.CONSUMED_STOP
    }

    // --- Helpers ------------------------------------------------------------------------------

    /**
     * Returns the first manufacturer record in [mfr] that decodes as an AiLink frame.
     *
     * An advertisement may legitimately carry records from several vendors, and AiLink's
     * "company id" is really its CID/VID pair rather than a registered identifier — so rather
     * than trusting any single id, every record is offered to [AiLinkLib.parse] and the checksum
     * decides.
     */
    private fun firstAiLinkFrame(mfr: SparseArray<ByteArray>): AiLinkLib.Broadcast? {
        for (i in 0 until mfr.size()) {
            val record = mfr.valueAt(i) ?: continue
            AiLinkLib.parse(mfr.keyAt(i), record)?.let { return it }
        }
        return null
    }
}
