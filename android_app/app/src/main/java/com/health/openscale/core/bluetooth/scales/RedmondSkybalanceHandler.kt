/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

class RedmondSkybalanceHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val model = MODEL_NAMES[device.name.trim().uppercase(Locale.US)] ?: return null
        return DeviceSupport(
            displayName = "REDMOND " + model,
            // TIME_SYNC and HISTORY_READ are potential capabilities, not implemented features.
            // TIME_SYNC was experimentally verified; HISTORY_READ remains a reverse-engineering lead.
            // The models have no body-composition sensors, so BODY_COMPOSITION is not applicable.
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.HISTORY_READ
            ),
            implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        finalPublishedForSession = false
        lastPreviewWeightKg = Float.NaN
        setNotifyOn(SVC_WEIGHT_SCALE, CHR_WEIGHT_MEASUREMENT)
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: ScaleUser
    ) {
        when (characteristic) {
            CHR_WEIGHT_MEASUREMENT -> {
                // A zero-weight frame is sent when the user steps off the scale. It is an empty
                // reading, not a malformed packet, so ignore it without logging a warning.
                if (data.size >= 3 && u16le(data, 1) == 0) return

                val parsed = parseWeightMeasurement(data) ?: run {
                    logW("REDMOND: invalid Weight Measurement payload ${data.toHexPreview(24)}")
                    return
                }

                if (!parsed.hasTimestamp) {
                    finalPublishedForSession = false

                    // Follow the established openScale pattern for streaming scales:
                    // surface live weight through userInfo(), but never publish() it.
                    // Throttle small changes to avoid flooding the UI with updates.
                    if (lastPreviewWeightKg.isNaN() ||
                        kotlin.math.abs(parsed.weightKg - lastPreviewWeightKg) >= LIVE_PREVIEW_STEP_KG
                    ) {
                        userInfo(
                            com.health.openscale.R.string.bluetooth_scale_info_measuring_weight,
                            parsed.weightKg
                        )
                        lastPreviewWeightKg = parsed.weightKg
                    }

                    logD("REDMOND ${parsed.weightKg} kg live frame without timestamp; not storing")
                    return
                }

                if (finalPublishedForSession) {
                    logD("Skipping repeated REDMOND final frame at ${parsed.measurement.dateTime}")
                    return
                }

                // The RS-744S device timestamp is stale in the real capture (2021-07-04).
                // Use the phone's current time for the saved openScale measurement.
                stampWithPhoneTime(parsed.measurement)
                finalPublishedForSession = true

                logD(
                    "REDMOND final measurement: " +
                        "${parsed.weightKg} kg at ${parsed.measurement.dateTime}"
                )
                publish(parsed.measurement)
            }

            else -> {
                logD(
                    "REDMOND unhandled characteristic $characteristic " +
                        "${data.toHexPreview(24)}"
                )
            }
        }
    }

    override fun onDisconnected() {
        finalPublishedForSession = false
        lastPreviewWeightKg = Float.NaN
    }

    internal data class ParsedWeight(
        val measurement: ScaleMeasurement,
        val weightKg: Float,
        val hasTimestamp: Boolean
    )

    internal fun parseWeightMeasurement(data: ByteArray): ParsedWeight? {
        if (data.size < 3) return null

        val flags = u8(data, 0)
        var offset = 1
        val isLb = (flags and 0x01) != 0
        val hasTimestamp = (flags and 0x02) != 0
        val hasUserId = (flags and 0x04) != 0
        val hasBmiHeight = (flags and 0x08) != 0

        val rawWeight = u16le(data, offset)
        offset += 2

        val weightKg = if (isLb) {
            rawWeight * 0.01f * LB_TO_KG
        } else {
            rawWeight * 0.005f
        }

        if (weightKg <= 0f || !weightKg.isFinite()) return null

        val measurement = ScaleMeasurement()
        measurement[MeasurementType.WEIGHT] = Kg(weightKg)

        if (hasTimestamp) {
            if (offset + 7 > data.size) return null

            val year = u16le(data, offset)
            val month = u8(data, offset + 2)
            val day = u8(data, offset + 3)
            val hour = u8(data, offset + 4)
            val minute = u8(data, offset + 5)
            val second = u8(data, offset + 6)

            val calendar = java.util.Calendar.getInstance().apply {
                clear()
                isLenient = false
                set(year, month - 1, day, hour, minute, second)
            }
            measurement.dateTime = runCatching { calendar.time }.getOrNull() ?: return null
        }

        if (hasUserId || hasBmiHeight) {
            logW(
                "REDMOND optional Weight Measurement fields are not decoded yet: " +
                    "flags=0x${flags.toString(16)}"
            )
        }

        return ParsedWeight(measurement, weightKg, hasTimestamp)
    }

    internal fun stampWithPhoneTime(measurement: ScaleMeasurement): ScaleMeasurement {
        measurement.dateTime = Date()
        return measurement
    }

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private var finalPublishedForSession = false
    private var lastPreviewWeightKg = Float.NaN

    private val SVC_WEIGHT_SCALE = uuid16(0x181D)
    private val CHR_WEIGHT_MEASUREMENT = uuid16(0x2A9D)

    private companion object {
        private val MODEL_NAMES = mapOf(
            "RS-73S" to "RS-73S",
            "RS-744S" to "RS-744S",
            "RS-745S" to "RS-745S",
            "RS-762S" to "RS-762S",
            "RS-773S" to "RS-773S"
        )

        private const val LB_TO_KG = 0.45359237f
        private const val LIVE_PREVIEW_STEP_KG = 0.05f
    }
}