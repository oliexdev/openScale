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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID
import kotlin.math.roundToInt

/** Lefu Body Pod 2 segmental, dual-frequency protocol. */
class BodyPod2Handler : ScaleDeviceHandler() {
    private val service = uuid16(0xFFF0)
    private val profileCharacteristic = uuid16(0xFFF1)
    private val fallbackCharacteristic = uuid16(0xFFF2)
    private val dataCharacteristic = uuid16(0xFFF4)

    private var weightKg = 0f
    private var lowFrequency: DoubleArray? = null
    private var highFrequency: DoubleArray? = null
    private var published = false
    private var lastPacket: ByteArray? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.equals("Body Pod 2", ignoreCase = true)) return null
        return DeviceSupport(
            displayName = "Body Pod 2",
            capabilities = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.USER_SYNC,
            ),
            implemented = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.USER_SYNC,
            ),
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        weightKg = 0f
        lowFrequency = null
        highFrequency = null
        published = false
        lastPacket = null

        setNotifyOn(service, dataCharacteristic)
        setNotifyOn(service, fallbackCharacteristic)
        writeTo(service, profileCharacteristic, buildUserProfile(user), withResponse = true)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != dataCharacteristic && characteristic != fallbackCharacteristic) return
        if (data.isEmpty() || !validChecksum(data)) return

        if (lastPacket?.contentEquals(data) == true) return
        lastPacket = data.copyOf()

        when (data[0].toInt() and 0xFF) {
            0xCF -> parseWeight(data)
            0xBE -> parseImpedance(data)
            0xDF -> logD("extended Body Pod 2 packet ${data.toHexPreview(32)}")
        }
    }

    private fun parseWeight(data: ByteArray) {
        if (data.size < 11) return
        val raw = uint16le(data, 3)
        if (raw == 0) return
        weightKg = raw / 100f
        if ((data[2].toInt() and 0x80) != 0) publishIfComplete(currentAppUser())
    }

    private fun parseImpedance(data: ByteArray) {
        if (data.size < 13) return
        val index = data[1].toInt() and 0xFF
        if (index !in 0..1) return
        val values = DoubleArray(5) { uint16le(data, 2 + it * 2) / 10.0 }
        if (index == 0) lowFrequency = values else highFrequency = values
        publishIfComplete(currentAppUser())
    }

    private fun publishIfComplete(user: ScaleUser) {
        if (published || weightKg <= 0f || lowFrequency == null || highFrequency == null) return
        val low = lowFrequency ?: return
        // Segment order: right arm, left arm, trunk, right leg, left leg.
        val rightPath = low[0] + low[2] + low[3]
        val leftPath = low[1] + low[2] + low[4]
        val wholeBodyImpedance = (rightPath + leftPath) / 2.0
        if (wholeBodyImpedance !in 100.0..1500.0) return

        val measurement = ScaleMeasurement().apply {
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            this[MeasurementType.IMPEDANCE] = Ohm(wholeBodyImpedance.toFloat())
        }
        published = true
        publish(measurement)
        requestDisconnect()
    }

    companion object {
        internal fun buildUserProfile(user: ScaleUser): ByteArray {
            val activity = when (user.activityLevel) {
                ActivityLevel.SEDENTARY, ActivityLevel.MILD -> 0
                ActivityLevel.MODERATE -> 1
                ActivityLevel.HEAVY, ActivityLevel.EXTREME -> 2
            }
            val packet = byteArrayOf(
                0xFE.toByte(), 0x01,
                (if (user.gender.isMale()) 1 else 0).toByte(), activity.toByte(),
                user.bodyHeight.roundToInt().coerceIn(0, 255).toByte(),
                user.age.coerceIn(0, 255).toByte(), 0x01, 0,
            )
            packet[7] = xor(packet, 1, 7)
            return packet
        }

        internal fun validChecksum(packet: ByteArray): Boolean =
            packet.isNotEmpty() && xor(packet, 0, packet.lastIndex) == packet.last()

        internal fun uint16le(packet: ByteArray, offset: Int): Int =
            (packet[offset].toInt() and 0xFF) or ((packet[offset + 1].toInt() and 0xFF) shl 8)

        private fun xor(packet: ByteArray, start: Int, endExclusive: Int): Byte {
            var result = 0
            for (i in start until endExclusive) result = result xor (packet[i].toInt() and 0xFF)
            return result.toByte()
        }
    }
}
