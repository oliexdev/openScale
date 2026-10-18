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
import com.health.openscale.core.bluetooth.ScaleCatalog
import com.health.openscale.core.data.MeasurementType
import org.junit.Test
import java.util.Calendar
import java.util.Date

class RedmondSkybalanceHandlerTest {

    private val handler = RedmondSkybalanceHandler()

    @Test
    fun recognizesOnlySupportedSkyBalanceModels() {
        listOf("RS-73S", "RS-744S", "RS-745S", "RS-762S", "RS-773S").forEach { name ->
            val support = handler.supportFor(ScaleCatalog.device(name))
            assertThat(support).isNotNull()
            assertThat(support!!.capabilities).containsExactly(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.HISTORY_READ
            )
            assertThat(support.implemented).containsExactly(DeviceCapability.LIVE_WEIGHT_STREAM)
            assertThat(support.capabilities).doesNotContain(DeviceCapability.BODY_COMPOSITION)
        }

        assertThat(handler.supportFor(ScaleCatalog.device("RS-740S"))).isNull()
        assertThat(handler.supportFor(ScaleCatalog.device("RS-744Y"))).isNull()
        assertThat(handler.supportFor(ScaleCatalog.device("SB1812S"))).isNull()
    }

    @Test
    fun decodesRealRs744s1109KgFrame() {
        val parsed = handler.parseWeightMeasurement(
            ScaleCatalog.hex("02 A4 56 E5 07 07 04 01 06 3B")
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(110.9f)

        val measurement = parsed.measurement
        assertThat(measurement[MeasurementType.WEIGHT]!!.value).isWithin(0.0001f).of(110.9f)

        val calendar = Calendar.getInstance().apply { time = measurement.dateTime!! }
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2021)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JULY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(4)
        assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isEqualTo(1)
        assertThat(calendar.get(Calendar.MINUTE)).isEqualTo(6)
        assertThat(calendar.get(Calendar.SECOND)).isEqualTo(59)
    }

    @Test
    fun decodesRealRs744s1132KgFrame() {
        val parsed = handler.parseWeightMeasurement(
            ScaleCatalog.hex("02 70 58 E5 07 07 04 01 08 14")
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(113.2f)
    }

    @Test
    fun decodesRealRs744s1149KgFrame() {
        val parsed = handler.parseWeightMeasurement(
            ScaleCatalog.hex("02 C4 59 E5 07 07 04 01 0A 1B")
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(114.9f)
    }

    @Test
    fun decodesRealLiveFrameWithoutTimestamp() {
        val parsed = handler.parseWeightMeasurement(
            ScaleCatalog.hex("00 30 57")
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(111.6f)
        assertThat(parsed.hasTimestamp).isFalse()
        assertThat(parsed.measurement.dateTime).isNull()
    }

    @Test
    fun deviceTimestampIsDecodedButPublicationUsesPhoneTime() {
        val parsed = handler.parseWeightMeasurement(
            ScaleCatalog.hex("02 A4 56 E5 07 07 04 01 06 3B")
        )
        assertThat(parsed).isNotNull()

        val before = Date()
        handler.stampWithPhoneTime(parsed!!.measurement)
        val after = Date()

        assertThat(parsed.measurement.dateTime).isAtLeast(before)
        assertThat(parsed.measurement.dateTime).isAtMost(after)
    }
}