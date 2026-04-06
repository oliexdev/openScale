package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo

class IcomonBroadcastHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase() ?: ""

        if (name.startsWith("AAA002")) {
            return DeviceSupport(
                displayName = "Icomon / e-volve (Broadcast)",
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION
                ),
                linkMode = LinkMode.BROADCAST_ONLY
            )
        }
        return null
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        // ScanResult's native method automatically finds 0xA0AC and strips those two ID bytes for us.
        val data = result.scanRecord?.getManufacturerSpecificData(0xA0AC)
            ?: return BroadcastAction.IGNORED

        // The remaining payload should be exactly 12 bytes (6 for MAC, 6 for data)
        if (data.size >= 12) {

            // Extract the target bytes
            val b1 = data[6].toInt() and 0xFF
            val b2 = data[7].toInt() and 0xFF
            val bStatus = data[8].toInt() and 0xFF
            val i1 = data[9].toInt() and 0xFF
            val i2 = data[10].toInt() and 0xFF

            // Apply the 0xA0 XOR Cipher
            val w1 = b1 xor 0xA0
            val w2 = b2 xor 0xA0
            val status = bStatus xor 0xA0
            val imp1 = i1 xor 0xA0
            val imp2 = i2 xor 0xA0

            // Combine and convert to Double to satisfy ScaleMeasurement requirements
            val weight = ((w1 shl 8) or w2) / 100.0f
            val impedance = ((imp1 shl 8) or imp2).toDouble()

            if (weight > 10.0) {
                // Status 2 means the scale has locked the final weight
                if (status == 2) {
                    val measurement = ScaleMeasurement().apply {
                        this.weight = weight
                        if (impedance > 0.0) {
                            this.impedance = impedance
                        }
                    }

                    // Send the finalized data to the app
                    publish(measurement)

                    // Tell the openScale adapter we are completely done scanning
                    return BroadcastAction.CONSUMED_STOP
                } else {
                    // The weight is still fluctuating. Tell the adapter to keep listening.
                    return BroadcastAction.CONSUMED_KEEP_SCANNING
                }
            }
        }

        // If the payload was malformed or < 10kg, ignore it and keep scanning
        return BroadcastAction.IGNORED
    }
}