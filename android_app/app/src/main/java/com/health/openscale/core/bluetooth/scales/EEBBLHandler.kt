package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Calendar
import java.util.UUID

/**
 * Handler for EEBBL Body Fat Scale (P1) - 8-electrode with handle.
 */
class EEBBLHandler : ScaleDeviceHandler() {

    private val SERVICE_UUID = uuid16(0xFFB0)
    private val CHAR_NOTIFY  = uuid16(0xFFB2)
    private val CHAR_WRITE   = uuid16(0xFFB1)

    private var lastRawWeight: Int? = null
    private var stableCount = 0
    private val REQUIRED_STABLE_COUNT = 8

    private var weightPublished = false
    private var publishedWeightKg: Float = 0f
    private var extraFinalPackets = 0

    // We publish weight *immediately* on stable detection.
    // Then we give the scale a short grace period to send body-comp data (if any),
    // and then we proactively disconnect. This keeps the scale in a clean idle state
    // and allows reliable user switching / assisted weighing.
    private val BODY_COMP_GRACE_PACKETS = 12       // short window after publishing weight to catch body-comp
    private val KEEP_ALIVE_FINAL_PACKETS = 80
    private val STEPPED_OFF_THRESHOLD_PACKETS = 5   // trigger disconnect sooner after weight drop

    private var pendingMeasurement: ScaleMeasurement? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
        if (name.contains("EEBBL") || name.contains("P1")) {
            return DeviceSupport(
                displayName = "EEBBL Body Fat Scale (P1) - 8-electrode",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.UNIT_CONFIG
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.UNIT_CONFIG,
                    DeviceCapability.BODY_COMPOSITION   // partial: weight reliable; full metrics need parse impl + good measurement log
                ),
                linkMode = LinkMode.CONNECT_GATT
            )
        }
        return null
    }

    override fun onConnected(user: ScaleUser) {
        logI("=== EEBBL connected (custom handler) ===")
        resetState()

        setNotifyOn(SERVICE_UUID, CHAR_NOTIFY)

        // Init sequence (sent quickly; will interleave with incoming measurement stream)
        writeInit(0xF7, 0, 0, 0)
        writeInit(0xFA, 0, 0, 0)

        val sex = if (user.gender.isMale()) 1 else 2
        val age = user.age
        val heightCm = user.bodyHeight.toInt().coerceIn(100, 220)
        writeInit(0xFB, sex, age, heightCm)

        val now = Calendar.getInstance()
        val yy = (now.get(Calendar.YEAR) - 2000).coerceIn(0, 99)
        val mm = now.get(Calendar.MONTH) + 1
        val dd = now.get(Calendar.DAY_OF_MONTH)
        writeInit(0xFD, yy, mm, dd)

        val HH = now.get(Calendar.HOUR_OF_DAY)
        val MM = now.get(Calendar.MINUTE)
        val SS = now.get(Calendar.SECOND)
        writeInit(0xFC, HH, MM, SS)

        writeInit(0xFE, 6, user.scaleUnit.toInt(), 0)

        userInfo(R.string.bt_info_step_on_scale)
    }

    private fun resetState() {
        lastRawWeight = null
        stableCount = 0
        weightPublished = false
        publishedWeightKg = 0f
        extraFinalPackets = 0
        pendingMeasurement = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        if (data.size < 20) return

        // Primary path: A2 measurement packets (all observed packets match  xx 07 00 A2 ... )
        if (data[1] == 0x07.toByte() && data[2] == 0x00.toByte() && data[3] == 0xA2.toByte()) {
            processA2Packet(data)
            return
        }

        // Fallback / alternative rich indication path (for possible future/official-app formats with embedded 1F 01 type markers)
        val weightIdx = findWeightPattern(data)
        if (weightIdx >= 0) {
            processRichIndicationPacket(data, weightIdx)
            return
        }

        // Unknown packet - log for debugging new firmware variants
        logD("Unknown notify packet on FFB2: ${data.joinToString(" ") { "%02X".format(it) }}")
    }

    private fun findWeightPattern(data: ByteArray): Int {
        for (i in 0 until data.size - 4) {
            if (data[i] == 0x1F.toByte() && data[i + 1] == 0x01.toByte() &&
                (data[i + 2].toInt() and 0xFF) in 0x01..0x03) return i
        }
        return -1
    }

    private fun processA2Packet(data: ByteArray) {
        val flag = data[4].toInt() and 0xFF
        val raw = ((data[6].toInt() and 0xFF) shl 16) or
                ((data[7].toInt() and 0xFF) shl 8) or
                (data[8].toInt() and 0xFF)
        val weight = raw / 1000.0f

        if (flag == 0x03) {
            // Final / stable measurement phase
            if (lastRawWeight == raw) {
                stableCount++
            } else {
                lastRawWeight = raw
                stableCount = 1
            }

            if (!weightPublished && stableCount >= REQUIRED_STABLE_COUNT && weight in 30f..250f) {
                pendingMeasurement = ScaleMeasurement().apply { this.weight = weight }
                logI("*** STABLE WEIGHT DETECTED: $weight kg (raw=$raw) — publishing + disconnecting immediately so measurement is tied to the correct user (fixes assisted weighing / baby mode) ***")
                publish(pendingMeasurement!!)
                weightPublished = true
                publishedWeightKg = weight
                pendingMeasurement = null
                requestDisconnect()   // Clean session end — next person presses Connect with the right user selected
            }

            if (weightPublished && pendingMeasurement != null) {
                extraFinalPackets++

                val extraBytes = data.sliceArray(9..18)
                val hasExtra = extraBytes.any { (it.toInt() and 0xFF) != 0 }

                // Always log the critical final packets so user can see if/when body comp data arrives
                if (hasExtra || extraFinalPackets <= 5 || extraFinalPackets % 5 == 0) {
                    logD("A2 FINAL #$extraFinalPackets w=${weight}kg hasExtra=$hasExtra extra=[${extraBytes.joinToString(" ") { "%02X".format(it) }}] last=${data.getOrNull(19)?.toInt()?.and(0xFF) ?: 0}")
                }

                if (hasExtra) {
                    publish(pendingMeasurement!!)
                    pendingMeasurement = null
                    weightPublished = false
                    requestDisconnect()
                    return
                }

                // Short grace period after we already published the weight.
                // If we haven't received body-comp data by now, we disconnect anyway.
                if (extraFinalPackets >= BODY_COMP_GRACE_PACKETS) {
                    logI("=== Grace period ended after publishing weight — disconnecting (body comp data not received or already processed) ===")
                    if (pendingMeasurement != null) {
                        // In case body-comp arrived in the last packets
                        publish(pendingMeasurement!!)
                        pendingMeasurement = null
                    }
                    weightPublished = false
                    requestDisconnect()
                    return
                }

                val steppedOff = raw < (publishedWeightKg * 1000 * 0.25).toInt() && extraFinalPackets > STEPPED_OFF_THRESHOLD_PACKETS
                if (extraFinalPackets >= KEEP_ALIVE_FINAL_PACKETS || steppedOff) {
                    if (pendingMeasurement != null) {
                        logI("Publishing on keepalive/steppedOff and disconnecting")
                        publish(pendingMeasurement!!)
                        pendingMeasurement = null
                    }
                    weightPublished = false
                    requestDisconnect()
                }
            }
        } else {
            // Live / in-progress measurement (flag 0x01 or other).
            // Allow a new measurement cycle if the user stepped off (weight dropped significantly).
            val significantDrop = weightPublished && publishedWeightKg > 0f &&
                    weight < (publishedWeightKg * 0.5f)   // stepped off or very light
            if (significantDrop || !weightPublished) {
                stableCount = 0
                lastRawWeight = null
                if (significantDrop) {
                    weightPublished = false
                    publishedWeightKg = 0f
                    pendingMeasurement = null
                    extraFinalPackets = 0
                    logD("Significant weight drop detected — resetting for new measurement")
                }
            }
            // Optional: log interesting live packets occasionally
            if (raw > 10000 && (stableCount % 20 == 0)) {
                logD("Live A2 packet flag=$flag raw=$raw (${weight}kg) bytes6-8=${data[6].toInt() and 0xFF} ${data[7].toInt() and 0xFF} ${data[8].toInt() and 0xFF}")
            }
        }
    }

    private fun processRichIndicationPacket(data: ByteArray, weightStartIdx: Int) {
        val raw = ((data[weightStartIdx + 2].toInt() and 0xFF) shl 16) or
                ((data[weightStartIdx + 3].toInt() and 0xFF) shl 8) or
                (data[weightStartIdx + 4].toInt() and 0xFF)
        val weight = raw / 1000.0f

        logI("*** RICH BODY-COMP INDICATION (alternative path) weight=$weight kg at idx=$weightStartIdx ***")

        if (pendingMeasurement == null) {
            pendingMeasurement = ScaleMeasurement().apply { this.weight = weight }
        } else {
            pendingMeasurement!!.weight = weight
        }

        publish(pendingMeasurement!!)
        // Do not null pending here in case more data comes; main path handles most cases
    }


    private fun writeInit(cmd: Int, b3: Int, b4: Int, b5: Int) {
        val buf = ByteArray(8)
        buf[0] = 0xAC.toByte()
        buf[1] = 0x02.toByte()
        buf[2] = (cmd and 0xFF).toByte()
        buf[3] = (b3 and 0xFF).toByte()
        buf[4] = (b4 and 0xFF).toByte()
        buf[5] = (b5 and 0xFF).toByte()
        buf[6] = 0xCC.toByte()
        val sum = ((buf[2].toInt() and 0xFF) + (buf[3].toInt() and 0xFF) +
                (buf[4].toInt() and 0xFF) + (buf[5].toInt() and 0xFF) +
                (buf[6].toInt() and 0xFF)) and 0xFF
        buf[7] = sum.toByte()
        writeTo(SERVICE_UUID, CHAR_WRITE, buf, withResponse = true)
    }
}
