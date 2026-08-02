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
import java.util.Calendar
import java.util.UUID
import org.junit.Test

class LenovoHS10HandlerTest {

    private fun device(name: String) =
        ScannedDeviceInfo(name, "00:11:22:33:44:55", 0, emptyList(), null)

    @Test
    fun `decodes timestamp weight and impedance from history record`() {
        val record = LenovoHS10Handler.decodeRecord(
            byteArrayOf(
                0x67, 0x1F, 0x0F, 0x1E, 0x3B,
                0xF2.toByte(), 0xE0.toByte(), 0x7A, 0x12, 0x00
            )
        ) ?: error("expected a valid HS10 record")

        assertThat(record.weightKg).isWithin(1e-4f).of(73.6f)
        assertThat(record.impedanceOhm).isEqualTo(4730)

        val calendar = Calendar.getInstance().apply { time = record.dateTime }
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2023)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JULY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(31)
        assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isEqualTo(15)
        assertThat(calendar.get(Calendar.MINUTE)).isEqualTo(30)
        assertThat(calendar.get(Calendar.SECOND)).isEqualTo(59)
    }

    @Test
    fun `recognises hs10 by name without advertised services`() {
        assertThat(LenovoHS10Handler().supportFor(device("HS10"))?.displayName)
            .isEqualTo("Lenovo HS10")
        assertThat(LenovoHS10Handler().supportFor(device("Lenovo HS10"))?.displayName)
            .isEqualTo("Lenovo HS10")
    }

    @Test
    fun `rejects hs10 name when advertised service is incompatible`() {
        val info = ScannedDeviceInfo(
            "HS10",
            "00:11:22:33:44:55",
            0,
            listOf(UUID.randomUUID()),
            null
        )
        assertThat(LenovoHS10Handler().supportFor(info)).isNull()
    }

    @Test
    fun `rejects end marker and malformed records`() {
        assertThat(LenovoHS10Handler.isHistoryEnd(byteArrayOf(0xF2.toByte(), 0x00))).isTrue()
        assertThat(LenovoHS10Handler.decodeRecord(byteArrayOf(0xF2.toByte(), 0x00))).isNull()
        assertThat(LenovoHS10Handler.decodeRecord(ByteArray(10))).isNull()
    }
}
