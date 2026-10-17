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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.UnitValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.UUID
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.device

/**
 * Drives real weigh-ins of the scales built on the standard weight/body-composition profile
 * through their handlers. Packets are unedited captures from the issues named per row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandardWeightProfileHandlerTest {

    private val chrWeight = uuid16(0x2A9D)
    private val chrBodyComposition = uuid16(0x2A9C)
    private val chrSegmentalFat = uuid16(0x0009)
    private val chrSegmentalMuscle = uuid16(0x000A)

    /**
     * One weigh-in: the weight packet (2A9D) and the body-composition packet (2A9C) that
     * followed it. A null expectation means the value must not be published.
     */
    private data class Capture(
        val deviceName: String,
        val weightPacket: String,
        val compositionPacket: String,
        val takenAt: List<Int>,
        val weight: Float,
        val bodyFat: Float? = null,
        val muscle: Float? = null,
        val water: Float? = null,
        val lbm: Float? = null,
        val bone: Float? = null,
        val impedance: Float? = null,
    )

    private val captures = listOf(
        // Real weigh-in from #635, Beurer app: 79.4 kg, fat 19.7 %, water 52.3 %, muscle 38.0 %, bone 3.3 kg
        Capture("BF105", "0E 10 3E E4 07 06 11 12 33 2C 02 F5 00 08 07", "98 03 C5 00 A0 1B 7C 01 3C 2F 74 20 52 12", listOf(2020, 6, 17, 18, 51, 44),
            weight = 79.440f, bodyFat = 19.70f, muscle = 38.00f, water = 52.29f, lbm = 63.79f, bone = 3.33f, impedance = 469.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 64 42 E4 07 06 11 12 35 3A 02 06 01 08 07", "98 03 DD 00 86 1C 6F 01 14 31 86 21 E4 11", listOf(2020, 6, 17, 18, 53, 58),
            weight = 84.980f, bodyFat = 22.10f, muscle = 36.70f, water = 50.49f, lbm = 66.20f, bone = 3.38f, impedance = 458.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 26 40 E4 07 06 11 12 37 0D 02 FD 00 08 07", "98 03 D0 00 11 1C 75 01 24 30 08 21 EE 11", listOf(2020, 6, 17, 18, 55, 13),
            weight = 82.110f, bodyFat = 20.80f, muscle = 37.30f, water = 51.49f, lbm = 65.03f, bone = 3.41f, impedance = 459.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E CA 45 D0 07 01 05 0D 09 2D 01 13 01 08 07", "98 03 F6 00 45 20 9A 01 DE 31 D2 25 D6 10", listOf(2000, 1, 5, 13, 9, 45),
            weight = 89.330f, bodyFat = 24.60f, muscle = 41.00f, water = 54.19f, lbm = 67.35f, bone = 3.52f, impedance = 431.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 6C 3F E5 07 04 07 0F 23 09 01 FA 00 08 07", "98 03 A5 00 36 1D 99 01 3C 32 D0 22 30 11", listOf(2021, 4, 7, 15, 35, 9),
            weight = 81.180f, bodyFat = 16.50f, muscle = 40.90f, water = 54.89f, lbm = 67.79f, bone = 3.49f, impedance = 440.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 40 44 E5 07 04 07 0F 28 1E 01 0D 01 08 07", "98 03 C0 00 39 1E 88 01 48 34 E6 23 08 11", listOf(2021, 4, 7, 15, 40, 30),
            weight = 87.360f, bodyFat = 19.20f, muscle = 39.20f, water = 52.60f, lbm = 70.59f, bone = 3.67f, impedance = 436.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 56 40 E5 07 04 07 0F 2C 29 01 FE 00 08 07", "98 03 A9 00 68 1D 97 01 AE 32 30 23 1C 11", listOf(2021, 4, 7, 15, 44, 41),
            weight = 82.350f, bodyFat = 16.90f, muscle = 40.70f, water = 54.69f, lbm = 68.43f, bone = 3.56f, impedance = 438.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 3A 42 E5 07 04 07 0F 2F 09 01 05 01 08 07", "98 03 B7 00 CC 1D 8C 01 50 33 2A 23 58 11", listOf(2021, 4, 7, 15, 47, 9),
            weight = 84.770f, bodyFat = 18.30f, muscle = 39.60f, water = 53.10f, lbm = 69.26f, bone = 3.58f, impedance = 444.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 58 42 E5 07 04 0A 17 1C 33 04 EB 00 6C 07", "98 03 85 00 56 1F B9 01 8A 36 12 27 8A 11", listOf(2021, 4, 10, 23, 28, 51),
            weight = 84.920f, bodyFat = 13.30f, muscle = 44.10f, water = 58.89f, lbm = 73.63f, bone = 3.82f, impedance = 449.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E 10 40 E5 07 04 0A 17 1F 23 04 E3 00 6C 07", "98 03 7B 00 DC 1E C3 01 4A 35 A0 26 44 11", listOf(2021, 4, 10, 23, 31, 35),
            weight = 82.000f, bodyFat = 12.30f, muscle = 45.10f, water = 60.29f, lbm = 71.91f, bone = 3.70f, impedance = 442.00f),
        // Real weigh-in from #635
        Capture("BF105", "0E DE 41 E5 07 04 0A 17 24 20 04 E9 00 6C 07", "98 03 85 00 3D 1F B7 01 22 36 98 26 C6 11", listOf(2021, 4, 10, 23, 36, 32),
            weight = 84.310f, bodyFat = 13.30f, muscle = 43.90f, water = 58.59f, lbm = 73.10f, bone = 3.81f, impedance = 455.00f),
        // Real weigh-in from #789, no foot contact (all-zero composition)
        Capture("BF720", "0E 2E 4C E5 07 0B 04 14 39 2F 02 26 01 1C 07", "98 03 00 00 00 00 00 00 00 00 00 00 00 00", listOf(2021, 11, 4, 20, 57, 47),
            weight = 97.510f),
        // Real weigh-in from #789
        Capture("BF720", "0E 36 4C E5 07 0B 04 14 3A 30 02 26 01 1C 07", "98 03 15 01 B2 1F 74 01 50 34 0C 24 BC 11", listOf(2021, 11, 4, 20, 58, 48),
            weight = 97.550f, bodyFat = 27.70f, muscle = 37.20f, water = 47.30f, lbm = 70.53f, bone = 3.57f, impedance = 454.00f),
        // Real weigh-in from #789, Beurer app: 94.6 kg, fat 26.8 %, water 48.3 %, muscle 37.7 %, bone 3.5 kg
        Capture("BF720", "0E DE 49 E5 07 0B 07 0E 04 17 02 1D 01 1C 07", "98 03 0C 01 1F 1F 79 01 5E 33 AC 23 EC 13", listOf(2021, 11, 7, 14, 4, 23),
            weight = 94.550f, bodyFat = 26.80f, muscle = 37.70f, water = 48.29f, lbm = 69.21f, bone = 3.46f, impedance = 510.00f),
        // Real weigh-in from #789
        Capture("BF720", "0E CA 4B E5 07 0B 07 0E 0B 34 02 24 01 1C 07", "98 03 18 01 84 1F 73 01 C0 33 C4 23 6A 13", listOf(2021, 11, 7, 14, 11, 52),
            weight = 97.010f, bodyFat = 28.00f, muscle = 37.10f, water = 47.19f, lbm = 69.85f, bone = 3.61f, impedance = 497.00f),
        // Real weigh-in from #789, Beurer app: 97.0 kg, fat 28.0 %, water 47.3 %, muscle 37.1 %, bone 3.6 kg
        Capture("BF720", "0E C0 4B E5 07 0B 07 0E 0C 2E 02 24 01 1C 07", "98 03 18 01 84 1F 73 01 C0 33 D4 23 60 13", listOf(2021, 11, 7, 14, 12, 46),
            weight = 96.960f, bodyFat = 28.00f, muscle = 37.10f, water = 47.30f, lbm = 69.81f, bone = 3.57f, impedance = 496.00f),
        // Real weigh-in from #789, Beurer app: 95.8 kg, fat 27.5 %, water 47.7 %, muscle 37.4 %, bone 3.6 kg
        Capture("BF720", "0E CE 4A E5 07 0B 07 0E 11 39 02 21 01 1C 07", "98 03 13 01 52 1F 76 01 74 33 AE 23 EC 13", listOf(2021, 11, 7, 14, 17, 57),
            weight = 95.750f, bodyFat = 27.50f, muscle = 37.40f, water = 47.70f, lbm = 69.42f, bone = 3.56f, impedance = 510.00f),
        // Real weigh-in from #1282, scale display: water ~59 %, muscle ~44 %
        Capture("BF720", "0E F0 39 EA 07 01 0A 11 00 20 01 EF 00 E0 06", "98 03 A3 00 D7 1A AE 01 06 2E 10 22 12 11", listOf(2026, 1, 10, 17, 0, 32),
            weight = 74.160f, bodyFat = 16.30f, muscle = 43.00f, water = 58.79f, lbm = 62.07f, bone = 3.16f, impedance = 437.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 6C 38 E3 07 0A 04 09 03 3B 01 D3 00 3A 07", "98 03 86 00 7D 1C BE 01 5A 2E CE 22 2C 1A", listOf(2019, 10, 4, 9, 3, 59),
            weight = 72.220f, bodyFat = 13.40f, muscle = 44.60f, water = 61.69f, lbm = 62.54f, bone = 3.21f, impedance = 670.00f),
        // Real weigh-in from #502, Beurer app: 72.5 kg, fat 13.2 %, water 61.7 %, muscle 44.6 %, bone 3.2 kg
        Capture("BF950", "0E 9C 38 E3 07 0A 05 09 15 28 01 D3 00 3A 07", "98 03 84 00 8A 1C BE 01 AA 2E EC 22 82 19", listOf(2019, 10, 5, 9, 21, 40),
            weight = 72.460f, bodyFat = 13.20f, muscle = 44.60f, water = 61.69f, lbm = 62.90f, bone = 3.17f, impedance = 653.00f),
        // Real weigh-in from #502, Beurer app: 74.7 kg, fat 13.0 %, water 60.8 %, muscle 44.1 %, bone 3.3 kg
        Capture("BF950", "0E 62 3A E3 07 0A 05 0B 3A 0F 01 DA 00 3A 07", "98 03 82 00 0C 1D B9 01 32 30 7E 23 BA 18", listOf(2019, 10, 5, 11, 58, 15),
            weight = 74.730f, bodyFat = 13.00f, muscle = 44.10f, water = 60.79f, lbm = 65.02f, bone = 3.33f, impedance = 633.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 9C 38 E3 07 0A 03 09 01 2E 01 D3 00 3A 07", "98 03 82 00 8A 1C BE 01 C8 2E FC 22 CE 18", listOf(2019, 10, 3, 9, 1, 46),
            weight = 72.460f, bodyFat = 13.00f, muscle = 44.60f, water = 61.80f, lbm = 63.04f, bone = 3.16f, impedance = 635.00f),
        // Real weigh-in from #502, Beurer app: 77.0 kg, fat 14.9 %, water 59.7 %, muscle 43.5 %, bone 3.3 kg
        Capture("BF950", "0E 2A 3C E3 07 0A 05 0C 0F 0A 01 E1 00 3A 07", "98 03 95 00 8E 1D B3 01 9E 30 EA 23 1E 19", listOf(2019, 10, 5, 12, 15, 10),
            weight = 77.010f, bodyFat = 14.90f, muscle = 43.50f, water = 59.69f, lbm = 65.54f, bone = 3.31f, impedance = 643.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 28 45 E5 07 08 07 0C 2E 16 05 32 01 A4 06", "98 03 7A 01 C9 1B 41 01 2A 26 7E 1E CC 15", listOf(2021, 8, 7, 12, 46, 22),
            weight = 88.520f, bodyFat = 37.80f, muscle = 32.10f, water = 44.09f, lbm = 55.06f, bone = 6.21f, impedance = 558.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 38 45 E5 07 08 09 14 39 10 08 1A 01 EA 06", "98 03 26 01 65 21 70 01 5E 2E D6 1F 14 14", listOf(2021, 8, 9, 20, 57, 16),
            weight = 88.600f, bodyFat = 29.40f, muscle = 36.80f, water = 45.99f, lbm = 62.55f, bone = 3.20f, impedance = 514.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 1E 45 E5 07 08 09 15 0A 35 04 1A 01 EA 06", "98 03 27 01 5D 21 70 01 3E 2E B8 1F 96 14", listOf(2021, 8, 9, 21, 10, 53),
            weight = 88.470f, bodyFat = 29.50f, muscle = 36.80f, water = 45.89f, lbm = 62.37f, bone = 3.18f, impedance = 527.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E 22 45 E5 07 08 09 15 0B 36 05 1A 01 EA 06", "98 03 27 01 5D 21 70 01 3E 2E CC 1F 50 14", listOf(2021, 8, 9, 21, 11, 54),
            weight = 88.490f, bodyFat = 29.50f, muscle = 36.80f, water = 45.99f, lbm = 62.39f, bone = 3.20f, impedance = 520.00f),
        // Real weigh-in from #502
        Capture("BF950", "0E A6 44 E5 07 08 19 14 0B 10 08 18 01 EA 06", "98 03 2A 01 1E 21 71 01 CA 2D A4 1F 2C 15", listOf(2021, 8, 25, 20, 11, 16),
            weight = 87.870f, bodyFat = 29.80f, muscle = 36.90f, water = 46.09f, lbm = 61.68f, bone = 3.07f, impedance = 542.00f),
        // Real weigh-in from #219
        Capture("BF600", "0E FA 3B E2 07 05 04 17 07 22 01 0A 01 A4 06", "98 03 E1 00 FB 1B 98 01 10 2C EC 1F 3F 0D", listOf(2018, 5, 4, 23, 7, 34),
            weight = 76.770f, bodyFat = 22.50f, muscle = 40.80f, water = 53.22f, lbm = 59.50f, bone = 3.10f, impedance = 339.10f),
        // Real weigh-in from #219
        Capture("BF600", "0E FE 3B E2 07 05 04 17 0A 21 01 0A 01 A4 06", "98 03 E1 00 FB 1B 98 01 10 2C EC 1F 5A 0D", listOf(2018, 5, 4, 23, 10, 33),
            weight = 76.790f, bodyFat = 22.50f, muscle = 40.80f, water = 53.21f, lbm = 59.51f, bone = 3.11f, impedance = 341.80f),
        // Real weigh-in from #219
        Capture("BF600", "0E 78 3B E2 07 05 03 17 1A 30 01 07 01 A4 06", "98 03 57 01 51 19 45 01 7C 24 D0 1A 8A 0F", listOf(2018, 5, 3, 23, 26, 48),
            weight = 76.120f, bodyFat = 34.30f, muscle = 32.50f, water = 45.09f, lbm = 50.01f, bone = 3.31f, impedance = 397.80f),
        // Real weigh-in from #219
        Capture("BF600", "0E 8E 3A E2 07 04 1C 10 0D 20 01 04 01 A4 06", "98 03 EE 00 DA 1B 91 01 44 2A A4 1E 1B 11", listOf(2018, 4, 28, 16, 13, 32),
            weight = 74.950f, bodyFat = 23.80f, muscle = 40.10f, water = 52.33f, lbm = 57.11f, bone = 3.01f, impedance = 437.90f),
        // Real weigh-in from #219
        Capture("BF600", "0E FA 2E E5 07 07 08 0D 22 01 01 D0 00 A4 06", "98 03 97 00 DD 17 B2 01 BC 25 60 1B 9F 0C", listOf(2021, 7, 8, 13, 34, 1),
            weight = 60.130f, bodyFat = 15.10f, muscle = 43.40f, water = 58.27f, lbm = 51.05f, bone = 2.75f, impedance = 323.10f),
        // Real weigh-in from #219
        Capture("BF600", "0E 30 45 E5 07 07 17 0D 1C 34 01 28 01 C2 06", "98 03 18 01 CC 1D 7A 01 44 2F 42 22 E4 0B", listOf(2021, 7, 23, 13, 28, 52),
            weight = 88.560f, bodyFat = 28.00f, muscle = 37.80f, water = 49.51f, lbm = 63.76f, bone = 3.26f, impedance = 304.40f),
        // Real weigh-in from #1164
        Capture("BF600", "0E A6 48 E9 07 08 1D 0A 03 16 01 3A 01 B8 06", "98 03 39 01 18 1E 68 01 58 2F 4A 22 EF 0C", listOf(2025, 8, 29, 10, 3, 22),
            weight = 92.990f, bodyFat = 31.30f, muscle = 36.00f, water = 47.20f, lbm = 63.88f, bone = 3.28f, impedance = 331.10f),
        // Real weigh-in from #1164
        Capture("BF600", "0E A2 48 E9 07 08 1D 0A 04 15 01 3A 01 B8 06", "98 03 39 01 18 1E 68 01 58 2F 4A 22 D4 0C", listOf(2025, 8, 29, 10, 4, 21),
            weight = 92.970f, bodyFat = 31.30f, muscle = 36.00f, water = 47.21f, lbm = 63.87f, bone = 3.27f, impedance = 328.40f),
        // Real weigh-in from #531
        Capture("BF850", "0E EE 32 E8 07 02 14 0E 10 1E 01 E2 00 A4 06", "98 03 2F 01 10 16 4F 01 34 21 66 18 E2 14", listOf(2024, 2, 20, 14, 16, 30),
            weight = 65.190f, bodyFat = 30.30f, muscle = 33.50f, water = 47.91f, lbm = 45.44f, bone = 2.94f, impedance = 534.60f),
        // Real weigh-in from #1034
        Capture("BF500", "0E A8 3E E9 07 08 18 0B 10 33 01 EA 00 3A 07", "98 03 AD 00 53 1D 9D 01 24 31 6C 25 F8 20", listOf(2025, 8, 24, 11, 16, 51),
            weight = 80.200f, bodyFat = 17.30f, muscle = 41.30f, water = 59.73f, lbm = 66.33f, bone = 3.43f, impedance = 844.00f),
        // Real weigh-in from #550
        Capture("SBF76", "0E 3A 3F E4 07 02 0F 10 1B 3B 01 05 01 E0 06", "98 03 CD 00 60 1C 9C 01 AC 2F F6 22 5E 10", listOf(2020, 2, 15, 16, 27, 59),
            weight = 80.930f, bodyFat = 20.50f, muscle = 41.20f, water = 55.29f, lbm = 64.34f, bone = 3.32f, impedance = 419.00f),
        // Real weigh-in from #550
        Capture("SBF76", "0E 52 3F E4 07 02 0F 10 1D 12 01 05 01 E0 06", "98 03 CE 00 68 1C 9B 01 BA 2F F2 22 90 10", listOf(2020, 2, 15, 16, 29, 18),
            weight = 81.050f, bodyFat = 20.60f, muscle = 41.10f, water = 55.19f, lbm = 64.35f, bone = 3.26f, impedance = 424.00f),
        // Real weigh-in from #550
        Capture("SBF76", "0E 4A 3F E4 07 02 0F 11 0C 12 01 05 01 E0 06", "98 03 CE 00 64 1C 9B 01 AA 2F EE 22 86 10", listOf(2020, 2, 15, 17, 12, 18),
            weight = 81.010f, bodyFat = 20.60f, muscle = 41.10f, water = 55.19f, lbm = 64.32f, bone = 3.31f, impedance = 423.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E DC 3E E5 07 01 07 14 26 16 02 2E 01 5E 06", "98 03 23 01 09 1B 78 01 52 2A 3C 1E 58 11", listOf(2021, 1, 7, 20, 38, 22),
            weight = 80.460f, bodyFat = 29.10f, muscle = 37.60f, water = 48.10f, lbm = 57.05f, bone = 2.88f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 0A 40 E5 07 01 07 14 27 0B 02 34 01 5E 06", "98 03 2D 01 48 1B 74 01 84 2A 5A 1E 58 11", listOf(2021, 1, 7, 20, 39, 11),
            weight = 81.970f, bodyFat = 30.10f, muscle = 37.20f, water = 47.40f, lbm = 57.30f, bone = 2.88f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 62 40 E5 07 01 07 14 29 09 02 36 01 5E 06", "98 03 30 01 58 1B 73 01 8A 2A 62 1E 58 11", listOf(2021, 1, 7, 20, 41, 9),
            weight = 82.410f, bodyFat = 30.40f, muscle = 37.10f, water = 47.19f, lbm = 57.36f, bone = 2.91f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 04 3F E5 07 01 07 15 02 2B 02 2F 01 5E 06", "98 03 24 01 11 1B 77 01 60 2A 3E 1E 58 11", listOf(2021, 1, 7, 21, 2, 43),
            weight = 80.660f, bodyFat = 29.20f, muscle = 37.50f, water = 47.99f, lbm = 57.11f, bone = 2.87f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E D6 3E E5 07 01 07 15 08 1A 02 2E 01 5E 06", "98 03 22 01 09 1B 78 01 54 2A 38 1E 58 11", listOf(2021, 1, 7, 21, 8, 26),
            weight = 80.430f, bodyFat = 29.00f, muscle = 37.60f, water = 48.09f, lbm = 57.11f, bone = 2.93f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 64 3F E5 07 01 07 16 07 31 02 31 01 5E 06", "98 03 27 01 26 1B 76 01 68 2A 4C 1E 58 11", listOf(2021, 1, 7, 22, 7, 49),
            weight = 81.140f, bodyFat = 29.50f, muscle = 37.40f, water = 47.79f, lbm = 57.20f, bone = 2.92f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E A6 3E E5 07 01 07 16 16 09 02 2D 01 5E 06", "98 03 21 01 FC 1A 78 01 48 2A 32 1E 62 11", listOf(2021, 1, 7, 22, 22, 9),
            weight = 80.190f, bodyFat = 28.90f, muscle = 37.60f, water = 48.20f, lbm = 57.02f, bone = 2.90f, impedance = 445.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E A6 3E E5 07 01 07 16 17 24 02 2D 01 5E 06", "98 03 21 01 FC 1A 78 01 48 2A 32 1E 58 11", listOf(2021, 1, 7, 22, 23, 36),
            weight = 80.190f, bodyFat = 28.90f, muscle = 37.60f, water = 48.20f, lbm = 57.02f, bone = 2.90f, impedance = 444.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 78 3E E5 07 01 08 00 04 01 02 2C 01 5E 06", "98 03 1F 01 F4 1A 79 01 4C 2A 2C 1E 62 11", listOf(2021, 1, 8, 0, 4, 1),
            weight = 79.960f, bodyFat = 28.70f, muscle = 37.70f, water = 48.30f, lbm = 57.01f, bone = 2.87f, impedance = 445.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 94 3E E5 07 01 08 00 04 3B 02 2D 01 5E 06", "98 03 20 01 F8 1A 79 01 4A 2A 38 1E 62 11", listOf(2021, 1, 8, 0, 4, 59),
            weight = 80.100f, bodyFat = 28.80f, muscle = 37.70f, water = 48.29f, lbm = 57.03f, bone = 2.90f, impedance = 445.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 6C 4F E5 07 08 0A 0E 0B 2C 02 19 01 6C 07", "98 03 20 01 7E 22 69 01 AE 35 6C 23 D6 10", listOf(2021, 8, 10, 14, 11, 44),
            weight = 101.660f, bodyFat = 28.80f, muscle = 36.10f, water = 44.60f, lbm = 72.38f, bone = 3.67f, impedance = 431.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 7C 4F E5 07 08 0A 0E 0E 06 02 19 01 6C 07", "98 03 20 01 7E 22 69 01 AE 35 72 23 D6 10", listOf(2021, 8, 10, 14, 14, 6),
            weight = 101.740f, bodyFat = 28.80f, muscle = 36.10f, water = 44.59f, lbm = 72.44f, bone = 3.73f, impedance = 431.00f),
        // Real weigh-in from #681
        Capture("SBF77", "0E 6C 4F E5 07 08 0A 0E 0F 08 02 19 01 6C 07", "98 03 20 01 7E 22 69 01 AE 35 6C 23 D6 10", listOf(2021, 8, 10, 14, 15, 8),
            weight = 101.660f, bodyFat = 28.80f, muscle = 36.10f, water = 44.60f, lbm = 72.38f, bone = 3.67f, impedance = 431.00f),
        // Real weigh-in from #1172
        Capture("SBF73", "0E 4A 40 E9 07 09 07 14 0C 14 01 F8 00 1C 07", "98 03 C4 00 85 1D 9A 01 08 31 0A 23 60 13", listOf(2025, 9, 7, 20, 12, 20),
            weight = 82.290f, bodyFat = 19.60f, muscle = 41.00f, water = 54.50f, lbm = 66.16f, bone = 3.40f, impedance = 496.00f),
        // Real weigh-in from #1172
        Capture("SBF73", "0E 28 40 E9 07 09 07 14 08 0C 01 F8 00 1C 07", "98 03 C2 00 81 1D 9A 01 0E 31 08 23 2E 13", listOf(2025, 9, 7, 20, 8, 12),
            weight = 82.120f, bodyFat = 19.40f, muscle = 41.00f, water = 54.60f, lbm = 66.19f, bone = 3.40f, impedance = 491.00f),
        // Real weigh-in from #1172
        Capture("SBF73", "0E 1E 40 E9 07 09 07 14 07 16 01 F8 00 1C 07", "98 03 C3 00 7D 1D 9A 01 F6 30 02 23 6A 13", listOf(2025, 9, 7, 20, 7, 22),
            weight = 82.070f, bodyFat = 19.50f, muscle = 41.00f, water = 54.60f, lbm = 66.07f, bone = 3.40f, impedance = 497.00f),
        // Real weigh-in from #1172
        Capture("SBF73", "0E 26 40 E9 07 09 07 14 05 12 01 F8 00 1C 07", "98 03 C2 00 81 1D 9A 01 0C 31 06 23 2E 13", listOf(2025, 9, 7, 20, 5, 18),
            weight = 82.110f, bodyFat = 19.40f, muscle = 41.00f, water = 54.60f, lbm = 66.18f, bone = 3.40f, impedance = 491.00f),
        // Real weigh-in from #1172
        Capture("SBF73", "0E 20 40 E9 07 09 07 14 03 20 01 F8 00 1C 07", "98 03 C3 00 7D 1D 9A 01 F6 30 04 23 74 13", listOf(2025, 9, 7, 20, 3, 32),
            weight = 82.080f, bodyFat = 19.50f, muscle = 41.00f, water = 54.61f, lbm = 66.07f, bone = 3.40f, impedance = 498.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 E8 53 EA 07 06 10 12 13 2C 01 4F 01 B3 00", "98 03 4A 01 BD 21 40 01 AB 02 08 02 74 13", listOf(2026, 6, 16, 18, 19, 44),
            weight = 107.400f, bodyFat = 33.00f, muscle = 32.00f, water = 48.42f, impedance = 498.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 E8 53 EA 07 06 10 12 14 0F 01 4F 01 B3 00", "98 03 4A 01 BD 21 40 01 AB 02 08 02 60 13", listOf(2026, 6, 16, 18, 20, 15),
            weight = 107.400f, bodyFat = 33.00f, muscle = 32.00f, water = 48.42f, impedance = 496.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 E8 53 EA 07 06 10 12 14 30 01 4F 01 B3 00", "98 03 4A 01 BD 21 40 01 AB 02 08 02 BA 13", listOf(2026, 6, 16, 18, 20, 48),
            weight = 107.400f, bodyFat = 33.00f, muscle = 32.00f, water = 48.42f, impedance = 505.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 E8 4E EA 07 06 10 12 15 24 01 3B 01 B3 00", "98 03 2B 01 B1 20 50 01 9F 02 FF 01 A6 13", listOf(2026, 6, 16, 18, 21, 36),
            weight = 101.000f, bodyFat = 29.90f, muscle = 33.60f, water = 50.59f, impedance = 503.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 1C 52 EA 07 06 10 12 15 2F 01 48 01 B3 00", "98 03 3F 01 5D 21 46 01 A7 02 05 02 92 13", listOf(2026, 6, 16, 18, 21, 47),
            weight = 105.100f, bodyFat = 31.90f, muscle = 32.60f, water = 49.19f, impedance = 501.00f),
        // Real weigh-in from #1399, no foot contact (all-zero composition)
        Capture("BF450", "02 CC 10 EA 07 06 10 12 17 14 01 43 00 B3 00", "98 03 00 00 B1 13 00 00 00 00 00 00 00 00", listOf(2026, 6, 16, 18, 23, 20),
            weight = 21.500f),
        // Real weigh-in from #1399, no foot contact (all-zero composition)
        Capture("BF450", "02 C8 46 EA 07 06 10 12 1F 21 02 FA 00 BE 00", "98 03 00 00 24 1E 00 00 00 00 00 00 00 00", listOf(2026, 6, 16, 18, 31, 33),
            weight = 90.600f),
        // Real weigh-in from #1399
        Capture("BF450", "02 D4 53 EA 07 06 10 12 1F 36 02 29 01 BE 00", "98 03 69 01 DF 20 1B 01 7D 02 D7 01 CE 13", listOf(2026, 6, 16, 18, 31, 54),
            weight = 107.300f, bodyFat = 36.10f, muscle = 28.30f, water = 43.90f, impedance = 507.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 54 15 EA 07 06 10 12 20 23 02 4C 00 BE 00", "98 03 48 00 CA 13 C1 01 F2 00 AE 00 88 13", listOf(2026, 6, 16, 18, 32, 35),
            weight = 27.300f, bodyFat = 7.20f, muscle = 44.90f, water = 63.74f, impedance = 500.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 8C 46 EA 07 06 10 12 23 08 02 FA 00 BE 00", "98 03 20 01 18 1E 3E 01 56 02 B9 01 C4 13", listOf(2026, 6, 16, 18, 35, 8),
            weight = 90.300f, bodyFat = 28.80f, muscle = 31.80f, water = 48.84f, impedance = 506.00f),
        // Real weigh-in from #1399
        Capture("BF450", "02 B4 0A EA 07 06 10 12 23 25 02 26 00 BE 00", "98 03 48 00 91 11 DE 01 7F 00 55 00 C4 13", listOf(2026, 6, 16, 18, 35, 37),
            weight = 13.700f, bodyFat = 7.20f, muscle = 47.80f, water = 62.04f, impedance = 506.00f),
    )

    private fun handlerFor(deviceName: String): ScaleDeviceHandler = when (deviceName) {
        "SBF73" -> SanitasSbf72Handler()
        "BF450" -> BeurerBF450Handler()
        else -> StandardBeurerSanitasHandler()
    }

    @Test
    fun `captured weigh-ins publish one measurement with the scale's values`() {
        for (c in captures) {
            val callbacks = CapturingCallbacks()
            val handler = attachedHandler(handlerFor(c.deviceName), c.deviceName, callbacks)

            handler.handleNotification(chrWeight, hex(c.weightPacket))
            handler.handleNotification(chrBodyComposition, hex(c.compositionPacket))
            handler.handleDisconnected()

            val source = "${c.deviceName} ${c.weightPacket}"
            assertWithMessage(source).that(callbacks.published).hasSize(1)
            val m = callbacks.published.single()
            assertWithMessage("$source taken at").that(m.takenAt()).isEqualTo(c.takenAt)
            listOf(
                MeasurementType.WEIGHT to c.weight,
                MeasurementType.BODY_FAT to c.bodyFat,
                MeasurementType.MUSCLE to c.muscle,
                MeasurementType.WATER to c.water,
                MeasurementType.LBM to c.lbm,
                MeasurementType.BONE to c.bone,
                MeasurementType.IMPEDANCE to c.impedance,
            ).forEach { (key, expected) ->
                val actual = (m.values[key] as? UnitValue)?.value
                if (expected == null) {
                    assertWithMessage("$source ${key.identity}").that(actual).isNull()
                } else {
                    assertWithMessage("$source ${key.identity}").that(actual).isWithin(0.01f).of(expected)
                }
            }
        }
    }

    @Test
    fun `consecutive weigh-ins in one connection stay separate measurements`() {
        // Real weigh-ins from #1164 (BF600), taken without disconnecting in between.
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "BF600", callbacks)

        handler.handleNotification(chrWeight, hex("0E A6 48 E9 07 08 1D 0A 03 16 01 3A 01 B8 06"))
        handler.handleNotification(chrBodyComposition, hex("98 03 39 01 18 1E 68 01 58 2F 4A 22 EF 0C"))
        handler.handleNotification(chrWeight, hex("0E A2 48 E9 07 08 1D 0A 04 15 01 3A 01 B8 06"))
        handler.handleNotification(chrBodyComposition, hex("98 03 39 01 18 1E 68 01 58 2F 4A 22 D4 0C"))
        handler.handleDisconnected()

        assertThat(callbacks.published).hasSize(2)
        assertThat(callbacks.published.map { it.takenAt() }).containsExactly(
            listOf(2025, 8, 29, 10, 3, 22),
            listOf(2025, 8, 29, 10, 4, 21),
        ).inOrder()
        assertThat(callbacks.published.all { MeasurementType.BODY_FAT in it }).isTrue()
    }

    // --- BF1000: segmental values on Beurer-private characteristics -----------------
    // Packet payloads are the vectors already asserted in BeurerBf1000LibTest.

    @Test
    fun `attaches BF1000 segmental values to the published measurement`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "Beurer BF1000", callbacks)

        handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        handler.handleNotification(chrSegmentalMuscle, segmentalMusclePacket())
        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()

        val published = callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(72.5f)

        val segmentals = published.values.keys
            .filter { it.identity.startsWith("ble.segmental.") }
            .associate { it.identity to (published.values[it] as Percent).value }

        val expected = mapOf(
            "ble.segmental.fat.left_arm" to 12.4f,
            "ble.segmental.fat.right_arm" to 12.9f,
            "ble.segmental.fat.torso" to 21.3f,
            "ble.segmental.fat.left_leg" to 18.7f,
            "ble.segmental.fat.right_leg" to 18.9f,
            "ble.segmental.muscle.left_arm" to 33.1f,
            "ble.segmental.muscle.right_arm" to 33.6f,
            "ble.segmental.muscle.torso" to 45.2f,
            "ble.segmental.muscle.left_leg" to 41.8f,
            "ble.segmental.muscle.right_leg" to 42.0f,
        )
        assertThat(segmentals.keys).containsExactlyElementsIn(expected.keys)
        expected.forEach { (identity, percent) ->
            assertThat(segmentals.getValue(identity)).isWithin(1e-3f).of(percent)
        }
    }

    @Test
    fun `BF1000 visceral fat rides the predefined key, not a device-specific one`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "Beurer BF1000", callbacks)

        handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()

        val published = callbacks.published.single()
        assertThat(published[MeasurementType.VISCERAL_FAT]!!).isWithin(1e-3f).of(0.8f)
        assertThat(published.values.keys.map { it.identity })
            .doesNotContain("ble.segmental.visceral_fat")
    }

    @Test
    fun `BF1000 buffer is cleared, so the next weigh-in does not inherit stale values`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "Beurer BF1000", callbacks)

        handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()
        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()

        assertThat(callbacks.published).hasSize(2)
        assertThat(callbacks.published[0].values.keys.any { it.identity.startsWith("ble.") }).isTrue()
        assertThat(callbacks.published[1].values.keys.any { it.identity.startsWith("ble.") }).isFalse()
    }

    @Test
    fun `other Beurer models are untouched by the BF1000 path`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "Beurer BF105", callbacks)

        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()

        val published = callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(72.5f)
        assertThat(published.values.keys.none { it.identity.startsWith("ble.") }).isTrue()
    }

    @Test
    fun `each BF1000 segment seeds with a distinguishable icon and colour`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "Beurer BF1000", callbacks)
        handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        handler.handleNotification(chrSegmentalMuscle, segmentalMusclePacket())
        handler.handleNotification(chrWeight, bareWeightPacket())
        handler.handleDisconnected()

        val keys = callbacks.published.single().values.keys
            .filter { it.identity.startsWith("ble.segmental.") }

        val fat = keys.filter { it.identity.startsWith("ble.segmental.fat.") }
        val muscle = keys.filter { it.identity.startsWith("ble.segmental.muscle.") }
        // Colour separates the groups and the segments; the whole-body anchors stay reserved.
        assertThat(fat.map { it.defaultColor }).containsNoDuplicates()
        assertThat(muscle.map { it.defaultColor }).containsNoDuplicates()
        assertThat(fat.map { it.defaultColor }.intersect(muscle.map { it.defaultColor }.toSet())).isEmpty()
        assertThat(keys.map { it.defaultColor })
            .containsNoneOf(MeasurementType.BODY_FAT.defaultColor, MeasurementType.MUSCLE.defaultColor)
        // The icon carries the body part; unit is what both packets report.
        assertThat(keys.none { it.defaultIcon == MeasurementTypeIcon.IC_DEFAULT }).isTrue()
        assertThat(keys.all { it.wireUnit == UnitType.PERCENT }).isTrue()
        assertThat(keys.none { it.defaultPinned }).isTrue()
    }

    // --- A&D UC-352BLE -----------------------------------------------------------

    @Test
    fun `recognises the A&D UC-352BLE advertisement without depending on its serial suffix`() {
        val support = AndUC352BLEHandler().supportFor(device("A&D_UC-352BLE_01166D", uuid16(0x181D)))

        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("A&D UC-352BLE")
        assertThat(support.capabilities).containsExactly(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
        )
        assertThat(support.implemented).isEqualTo(support.capabilities)
        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
    }

    @Test
    fun `A&D UC-352BLE matching is case insensitive and rejects other devices`() {
        val handler = AndUC352BLEHandler()

        assertThat(handler.supportFor(device("a&d_uc-352ble_abcdef", uuid16(0x181D)))).isNotNull()
        assertThat(handler.supportFor(device("A&D_UC-355BLE_01166D", uuid16(0x181D)))).isNull()
        assertThat(handler.supportFor(device("UC-352BLE", uuid16(0x181D)))).isNull()
        assertThat(handler.supportFor(device("A&D_UC-352BLE_01166D"))).isNotNull()
    }

    @Test
    fun `A&D UC-352BLE weight-only indications publish one measurement each`() {
        val captured = listOf(
            "025433ea070816082a24" to Pair(65.7f, listOf(2026, 8, 22, 8, 42, 36)),
            "024033ea070816082b0b" to Pair(65.6f, listOf(2026, 8, 22, 8, 43, 11)),
            "027c33ea070816082b28" to Pair(65.9f, listOf(2026, 8, 22, 8, 43, 40)),
        )

        for ((payload, expected) in captured) {
            val callbacks = CapturingCallbacks()
            val handler = attachedHandler(AndUC352BLEHandler(), "A&D_UC-352BLE_01166D", callbacks)

            handler.handleNotification(chrWeight, hex(payload))
            handler.handleDisconnected()

            val m = callbacks.published.single()
            assertThat(m[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(expected.first)
            assertThat(m.takenAt()).isEqualTo(expected.second)
        }
    }

    // --- Packet decoding edge cases -------------------------------------------------

    private val bf500Weight = "0E EC 36 EA 07 0A 11 0F 0A 1C 01 EB 00 C2 06"

    @Test
    fun `imperial masses are converted from pounds to kilograms`() {
        val callbacks = CapturingCallbacks()
        val handler = attachedHandler(StandardBeurerSanitasHandler(), "BF500", callbacks)

        // 150.00 lb; then 20.0 % fat with 50.00 lb water mass.
        handler.handleNotification(chrWeight, hex("01 98 3A"))
        handler.handleNotification(chrBodyComposition, hex("01 01 C8 00 88 13"))
        handler.handleDisconnected()

        val m = callbacks.published.single()
        assertThat(m[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(68.039f)
        assertThat(m[MeasurementType.WATER]?.value).isWithin(0.01f).of(33.33f)
    }

    @Test
    fun `unknown or impossible timestamps leave the reading without a date`() {
        val stamps = listOf(
            "00 00 00 00 0F 0A 1C", // year, month and day "not known"
            "EA 07 0D 11 0F 0A 1C", // month 13 would otherwise roll into the next year
        )
        for (stamp in stamps) {
            val callbacks = CapturingCallbacks()
            val handler = attachedHandler(StandardBeurerSanitasHandler(), "BF500", callbacks)

            handler.handleNotification(chrWeight, hex("06 EC 36 $stamp 01"))
            handler.handleDisconnected()

            val m = callbacks.published.single()
            assertWithMessage(stamp).that(m.dateTime).isNull()
            assertWithMessage(stamp).that(m[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(70.3f)
        }
    }

    /** Standard Weight Scale 2A9D: flags 0x00 (kg, nothing optional) + 72.5 kg / 0.005. */
    private fun bareWeightPacket(): ByteArray {
        val raw = (72.5f / 0.005f).toInt()
        return byteArrayOf(0x00, (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
    }

    /** 0x7E marker, visceral in tenths, then five fat percentages in tenths, LE. */
    private fun segmentalFatPacket(): ByteArray =
        byteArrayOf(0x7E, 8) + tenths(12.4f, 12.9f, 21.3f, 18.7f, 18.9f)

    /** 0x3E marker, then five muscle percentages in tenths, LE. */
    private fun segmentalMusclePacket(): ByteArray =
        byteArrayOf(0x3E) + tenths(33.1f, 33.6f, 45.2f, 41.8f, 42.0f)

    private fun tenths(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val raw = Math.round(v * 10f)
            out[i * 2] = (raw and 0xFF).toByte()
            out[i * 2 + 1] = ((raw shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun ScaleMeasurement.takenAt(): List<Int> {
        val cal = Calendar.getInstance().apply { time = requireNotNull(dateTime) }
        return listOf(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
    }

    private fun attachedHandler(
        handler: ScaleDeviceHandler,
        deviceName: String,
        callbacks: CapturingCallbacks,
    ): ScaleDeviceHandler {
        assertWithMessage(deviceName).that(handler.supportFor(device(deviceName, uuid16(0x181D)))).isNotNull()
        handler.attach(
            NoopTransport(), callbacks, InMemorySettings(), FixedDataProvider(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        return handler
    }

    private class NoopTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val ints = mutableMapOf<String, Int>()
        private val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { ints.remove(key); strings.remove(key) }
    }

    private class FixedDataProvider : ScaleDeviceHandler.DataProvider {
        private val user = ScaleUser(
            id = 1,
            userName = "Test User",
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -30) }.time,
            bodyHeight = 180f,
            gender = GenderType.MALE,
        )
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
