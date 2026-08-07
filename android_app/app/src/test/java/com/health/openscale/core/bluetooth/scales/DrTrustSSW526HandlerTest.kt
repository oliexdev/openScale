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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID
import org.junit.Test

class DrTrustSSW526HandlerTest {

    private fun b(value: Int): Byte = value.toByte()

    private fun device(name: String) =
        ScannedDeviceInfo(name, "00:11:22:33:44:55", 0, emptyList(), null)

    @Test
    fun `decodes the first captured ramp frame`() {
        assertThat(DrTrustSSW526Handler.decodeWeightKg(b(0x68), b(0x13), b(0x88)))
            .isWithin(1e-4f).of(5.0f)
    }

    @Test
    fun `decodes the captured stable 74 kilogram frame`() {
        assertThat(DrTrustSSW526Handler.decodeWeightKg(b(0x69), b(0x22), b(0x82)))
            .isWithin(1e-4f).of(74.370f)
    }

    @Test
    fun `filters the raw idle base`() {
        assertThat(DrTrustSSW526Handler.decodeWeightKg(b(0x68), b(0x00), b(0x00)))
            .isWithin(1e-6f).of(0.0f)
    }

    @Test
    fun `recognises captured ac27 frame markers`() {
        val frame = ByteArray(20).apply {
            this[0] = b(0xAC)
            this[1] = b(0x27)
            this[3] = b(0x69)
            this[4] = b(0x22)
            this[5] = b(0x82)
            this[16] = b(0x03)
            this[17] = b(0xD5)
        }

        assertThat(DrTrustSSW526Handler.isMeasurementFrame(frame)).isTrue()
        frame[17] = b(0x00)
        assertThat(DrTrustSSW526Handler.isMeasurementFrame(frame)).isFalse()
    }

    @Test
    fun `recognises ssw526 without allowing ssw532 to claim it`() {
        assertThat(DrTrustSSW526Handler().supportFor(device("SSW526"))).isNotNull()
        assertThat(DrTrustSSW532Handler().supportFor(device("SSW526"))).isNull()
    }

    @Test
    fun `rejects ssw526 name when advertised service is incompatible`() {
        val info = ScannedDeviceInfo(
            "SSW526",
            "00:11:22:33:44:55",
            0,
            listOf(UUID.randomUUID()),
            null
        )
        assertThat(DrTrustSSW526Handler().supportFor(info)).isNull()
    }
}
