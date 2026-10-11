/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PicoocWhiteBodyCompositionTest {

    @Test
    fun `matches the supplied official PICOOC result`() {
        val result = PicoocWhiteBodyComposition.calculate(
            PicoocWhiteBodyComposition.Input(
                male = true,
                heightCm = 176f,
                age = 39,
                weightKg = 82.7f,
                correctedImpedanceOhm = 500,
                anchorWeightKg = 81,
                anchorBeta = 34,
            )
        )!!

        assertThat(result.bodyFatPercent).isWithin(0.05f).of(26.5f)
        assertThat(result.totalMusclePercent).isWithin(0.05f).of(69.6f)
        assertThat(result.waterPercent).isWithin(0.05f).of(51.2f)
        assertThat(result.skeletalMusclePercent).isWithin(0.05f).of(40.4f)
        assertThat(result.proteinPercent).isWithin(0.05f).of(18.4f)
        assertThat(result.boneMassKg).isWithin(0.05f).of(3.2f)
        assertThat(result.basalMetabolicRateKcal).isEqualTo(1683)
        assertThat(result.bmi).isWithin(0.05f).of(26.7f)
        assertThat(result.visceralFatLevel).isEqualTo(10)
        assertThat(result.metabolicAge).isEqualTo(42)
        assertThat(result.anchorBeta).isEqualTo(34)
        assertThat(result.measurementAnchor).isEqualTo(340)
    }

    @Test
    fun `rounds new impedance and reuses a sufficiently recent stable correction`() {
        val first = PicoocWhiteBodyComposition.correctedImpedance(
            rawOhm = 496,
            weightKg = 82.7f,
            timestampMs = 1_000_000L,
            previousRawOhm = null,
            previousWeightKg = null,
            previousTimestampMs = null,
            previousCorrectedOhm = null,
        )
        val reused = PicoocWhiteBodyComposition.correctedImpedance(
            rawOhm = 492,
            weightKg = 82.5f,
            timestampMs = 1_300_000L,
            previousRawOhm = 496,
            previousWeightKg = 82.7f,
            previousTimestampMs = 1_000_000L,
            previousCorrectedOhm = first,
        )

        assertThat(first).isEqualTo(500)
        assertThat(reused).isEqualTo(500)
    }

    @Test
    fun `keeps and resets the vendor weight anchor at its exact bucket boundaries`() {
        assertThat(PicoocWhiteBodyComposition.anchorWeight(82.7f, 81)).isEqualTo(81)
        assertThat(PicoocWhiteBodyComposition.anchorWeight(83.0f, 81)).isEqualTo(83)
        assertThat(PicoocWhiteBodyComposition.anchorWeight(79.9f, 81)).isEqualTo(79)
    }

    @Test
    fun `cold start derives profile-neutral anchors from the current measurement`() {
        val night = PicoocWhiteBodyComposition.calculate(
            PicoocWhiteBodyComposition.Input(
                male = true,
                heightCm = 176f,
                age = 39,
                weightKg = 82.7f,
                correctedImpedanceOhm = 500,
                anchorWeightKg = PicoocWhiteBodyComposition.anchorWeight(82.7f, null),
                anchorBeta = 0,
                hour = 0,
            )
        )!!
        val morning = PicoocWhiteBodyComposition.calculate(
            PicoocWhiteBodyComposition.Input(
                male = true,
                heightCm = 176f,
                age = 39,
                weightKg = 82.7f,
                correctedImpedanceOhm = 500,
                anchorWeightKg = PicoocWhiteBodyComposition.anchorWeight(82.7f, null),
                anchorBeta = 0,
                hour = 8,
            )
        )!!

        assertThat(night.anchorBeta).isEqualTo(23)
        assertThat(night.bodyFatPercent).isWithin(0.05f).of(29.3f)
        assertThat(morning.anchorBeta).isEqualTo(24)
        assertThat(morning.bodyFatPercent).isWithin(0.05f).of(29.0f)
    }
}
