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

import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementType
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
 * Drives real weigh-ins of the Beurer/Sanitas FFE1 family (BF700/800, BF710, SBF70/75, Crane)
 * through [BeurerSanitasHandler]. Each row carries the scale's own frames from the issue named
 * above it: the user list (0x34) and the measurement frames (0x42 stored, 0x59 live).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeurerSanitasHandlerTest {

    private val chr = uuid16(0xFFE1)

    /** A null expectation means the value must not be published. */
    private data class Weighing(
        val deviceName: String,
        val live: Boolean,
        val userList: List<String>,
        val owner: Int,
        val frames: List<String>,
        val takenAt: Long,
        val weight: Float,
        val bodyFat: Float? = null,
        val water: Float? = null,
        val muscle: Float? = null,
        val impedance: Float? = null,
    )

    private val weighings = listOf(
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale)
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214015a20f7e4060d01cc00aa02", "f74214024701ab00ef07080d0300f7"),
            takenAt = 1512110052L, weight = 77.45f, bodyFat = 17.00f, water = 58.30f, muscle = 42.70f, impedance = 460.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.0 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214035a21a94c061801cc00ae02", "f74214044401aa00ef070f0d1000f9"),
            takenAt = 1512155468L, weight = 78.00f, bodyFat = 17.40f, water = 58.00f, muscle = 42.60f, impedance = 460.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.1 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214055a23f9ca061a0000000000", "f7421406000000000007100d1200f9"),
            takenAt = 1512307146L, weight = 78.10f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 79.2 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214075a242731063001c500b402", "f74214083f01a700f107200d2e00fd"),
            takenAt = 1512318769L, weight = 79.20f, bodyFat = 18.00f, water = 57.50f, muscle = 42.30f, impedance = 453.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 79.2 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214095a24294e062f01cc00b502", "f742140a3f01a700f107200d2e00fd"),
            takenAt = 1512319310L, weight = 79.15f, bodyFat = 18.10f, water = 57.50f, muscle = 42.30f, impedance = 460.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 79.4 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f742140b5a24e1a9063401cc00b502", "f742140c3e01a700f207220d3300fd"),
            takenAt = 1512366505L, weight = 79.40f, bodyFat = 18.10f, water = 57.40f, muscle = 42.30f, impedance = 460.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.4 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f742140d5a29852e061f01c500af02", "f742140e4301a900f007150d1a00fa"),
            takenAt = 1512670510L, weight = 78.35f, bodyFat = 17.50f, water = 57.90f, muscle = 42.50f, impedance = 453.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.5 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f742140f5a298afc062201c500b102", "f74214104201a900f007160d1c00fb"),
            takenAt = 1512671996L, weight = 78.50f, bodyFat = 17.70f, water = 57.80f, muscle = 42.50f, impedance = 453.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.6 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214115a298c42062401c500b102", "f74214124201a900f007170d1f00fb"),
            takenAt = 1512672322L, weight = 78.60f, bodyFat = 17.70f, water = 57.80f, muscle = 42.50f, impedance = 453.00f),
        // Real weigh-in from the maintainer's own capture (BF700, stored on the scale), Beurer app: 78.6 kg
        Weighing("BEURER BF700", false, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 0,
            listOf("f74214135a298fd2062301cc00b102", "f74214144201a900f007170d1f00fb"),
            takenAt = 1512673234L, weight = 78.55f, bodyFat = 17.70f, water = 57.80f, muscle = 42.50f, impedance = 460.00f),
        // Real weigh-in from #300 (BF700, stored on the scale)
        Weighing("BEURER BF700", false, listOf("f734010100000000000000655045544d"), 0,
            listOf("f74204015b3bc1cc077401c0017801", "f742040291015100f907a908cf0142"),
            takenAt = 1530642892L, weight = 95.40f, bodyFat = 37.60f, water = 40.10f, muscle = 33.70f, impedance = 448.00f),
        // Real weigh-in from #300 (BF700, stored on the scale)
        Weighing("BEURER BF700", false, listOf("f734010100000000000000655045544d"), 0,
            listOf("f74204035b3bf409077501b8017901", "f742040490015100f907aa08d00143"),
            takenAt = 1530655753L, weight = 95.45f, bodyFat = 37.70f, water = 40.00f, muscle = 33.70f, impedance = 440.00f),
        // Real weigh-in from #300 (BF700, stored on the scale)
        Weighing("BEURER BF700", false, listOf("f734010100000000000000655045544d"), 0,
            listOf("f74204015b41b8b9075e01c0017f01", "f74204028e014f00f6078f08b20146"),
            takenAt = 1531033785L, weight = 94.30f, bodyFat = 38.30f, water = 39.80f, muscle = 33.50f, impedance = 448.00f),
        // Real weigh-in from #300 (BF700, stored on the scale)
        Weighing("BEURER BF700", false, listOf("f734010100000000000000655045544d"), 0,
            listOf("f74204035b421406074a01b8017901", "f742040493015200f5078208a20143"),
            takenAt = 1531057158L, weight = 93.30f, bodyFat = 37.70f, water = 40.30f, muscle = 33.80f, impedance = 440.00f),
        // Real weigh-in from #343 (BF700, live)
        Weighing("BEURER BF700", true, listOf("f7340301000000000000006546415759", "f7340302000000000000006652414b55", "f734030300000000000000675453545c"), 2,
            listOf("f7590301010000000000000067", "f75903025bd42bc005e10000000000", "f75903030000000000074a086200e8"),
            takenAt = 1540631488L, weight = 75.25f),
        // Real weigh-in from #1500 (BF800, stored on the scale)
        Weighing("BEURER BF800", false, listOf("f734010100000000000000654d000040"), 0,
            listOf("f74202016a91452a067001b400f401", "f7420202ea017c007304dd074c00f1"),
            takenAt = 1787905322L, weight = 82.40f, bodyFat = 24.40f, water = 49.00f, muscle = 38.00f, impedance = 436.00f),
        // Real weigh-in from #1500 (BF800, live)
        Weighing("BEURER BF800", true, listOf("f734010100000000000000654d000040"), 0,
            listOf("f7590301010000000000000065", "f7590302ea9290c2066f01ba00f501", "f7590303ea017c007304dd074c00f1"),
            takenAt = 1787990210L, weight = 82.35f, bodyFat = 24.50f, water = 49.00f, muscle = 38.00f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216015c776cef091b0000000000", "e74216020000000000088a09d2016c"),
            takenAt = 1551330543L, weight = 116.55f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216035c78be58091901ba01ce01", "e742160468013b010d088809d0016c"),
            takenAt = 1551416920L, weight = 116.45f, bodyFat = 46.20f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216055c7a5013091f01ba01d001", "e742160668013b010d088c09d5016d"),
            takenAt = 1551519763L, weight = 116.75f, bodyFat = 46.40f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216075c7cb274092501c301d101", "e742160868013b010e089109d9016d"),
            takenAt = 1551676020L, weight = 117.05f, bodyFat = 46.50f, water = 36.00f, muscle = 31.50f, impedance = 451.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216095c7e03e9091101ba01cb01", "e742160a68013b010c088309ca016a"),
            takenAt = 1551762409L, weight = 116.05f, bodyFat = 45.90f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e742160b5c7f54d7091901c301cf01", "e742160c68013b010d088809d0016c"),
            takenAt = 1551848663L, weight = 116.45f, bodyFat = 46.30f, water = 36.00f, muscle = 31.50f, impedance = 451.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e742160d5c80a663091901c301cf01", "e742160e68013b010d088809d0016c"),
            takenAt = 1551935075L, weight = 116.45f, bodyFat = 46.30f, water = 36.00f, muscle = 31.50f, impedance = 451.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e742160f5c81f7b4090a01ba01c901", "e742161068013b010c087d09c30169"),
            takenAt = 1552021428L, weight = 115.70f, bodyFat = 45.70f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216115c8522b0090701ba01c901", "e742161268013b010b087c09c20169"),
            takenAt = 1552229040L, weight = 115.55f, bodyFat = 45.70f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216135c85ebd8090001ba01c701", "e742161468013b010b087609bb0168"),
            takenAt = 1552280536L, weight = 115.20f, bodyFat = 45.50f, water = 36.00f, muscle = 31.50f, impedance = 442.00f),
        // Real weigh-in from #416 (BF710, stored on the scale)
        Weighing("BEURER BF710", false, listOf("e734010100000000000000654e4f523e"), 0,
            listOf("e74216155c88969908f501c301c401", "e742161668013b010a087009b40166"),
            takenAt = 1552455321L, weight = 114.65f, bodyFat = 45.20f, water = 36.00f, muscle = 31.50f, impedance = 451.00f),
        // Real weigh-in from #319 (SBF70, live), Sanitas app: 69.2 kg, fat 29.4 %, water 45.2 %, muscle 33.2 %
        Weighing("SANITAS SBF70", true, listOf("e73401010000000000000065414c455d"), 0,
            listOf("e7590301010000000000000065", "e75903025bb5f97c056701c5012601", "e7590303c4014c00e405e306c500ef"),
            takenAt = 1538652540L, weight = 69.15f, bodyFat = 29.40f, water = 45.20f, muscle = 33.20f, impedance = 453.00f),
        // Real weigh-in from #319 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734020100000000001291124c494e5b", "e73402020000000000000013414e4452"), 0,
            listOf("e74208015bf8f5ff063801b900bc02", "e74208021b019800f107280a0500ee"),
            takenAt = 1543042559L, weight = 79.60f, bodyFat = 18.80f, water = 53.90f, muscle = 40.80f, impedance = 441.00f),
        // Real weigh-in from #319 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734020100000000001291124c494e5b", "e73402020000000000000013414e4452"), 0,
            listOf("e74208035bf8f700063701b900bc02", "e74208041b019800f107280a0500ee"),
            takenAt = 1543042816L, weight = 79.55f, bodyFat = 18.80f, water = 53.90f, muscle = 40.80f, impedance = 441.00f),
        // Real weigh-in from #319 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734020100000000001291124c494e5b", "e73402020000000000000013414e4452"), 0,
            listOf("e74208055bfa3a8e063c01b900bc02", "e74208061a019800f1072b0a0900ee"),
            takenAt = 1543125646L, weight = 79.80f, bodyFat = 18.80f, water = 53.80f, muscle = 40.80f, impedance = 441.00f),
        // Real weigh-in from #319 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734020100000000001291124c494e5b", "e73402020000000000000013414e4452"), 0,
            listOf("e74208075bfa798c062b01c200ba02", "e74208081d019900f0072009fa00ec"),
            takenAt = 1543141772L, weight = 78.95f, bodyFat = 18.60f, water = 54.10f, muscle = 40.90f, impedance = 450.00f),
        // Real weigh-in from #335 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e73401010000000000000065414c455d"), 0,
            listOf("e7590301010000000000000065", "e75903025bbc495f056a01c5012801", "e7590303c4014c00e405e406c600f0"),
            takenAt = 1539066207L, weight = 69.30f, bodyFat = 29.60f, water = 45.20f, muscle = 33.20f, impedance = 453.00f),
        // Real weigh-in from #335 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e73401010000000000000065414c455d"), 0,
            listOf("e7590301010000000000000065", "e75903025bbc4d6c056e01c5012801", "e7590303c3014c00e505e606c800f0"),
            takenAt = 1539067244L, weight = 69.50f, bodyFat = 29.60f, water = 45.10f, muscle = 33.20f, impedance = 453.00f),
        // Real weigh-in from #335 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e73401010000000000000065414c455d"), 0,
            listOf("e7590301010000000000000065", "e75903025bbef2f3056801c500a402", "e75903033401a700f406f6080200d6"),
            takenAt = 1539240691L, weight = 69.20f, bodyFat = 16.40f, water = 56.40f, muscle = 42.30f, impedance = 453.00f),
        // Real weigh-in from #335 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e73401010000000000000065444f5862"), 0,
            listOf("e74202015bbef40305ad0000000000", "e742020200000000000726083900e0"),
            takenAt = 1539240963L, weight = 72.65f),
        // Real weigh-in from #335 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e73402010000000000000065444f5862", "e73402020000000000000066424f4362"), 1,
            listOf("e7590301010000000000000066", "e75903025bbef4e505ae01c500b602", "e759030324019f00fa0726083900e0"),
            takenAt = 1539241189L, weight = 72.70f, bodyFat = 18.20f, water = 54.80f, muscle = 41.50f, impedance = 453.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c015c174e13071001c400d401", "e7423c02fe0189010807d90afd00fa"),
            takenAt = 1545031187L, weight = 90.40f, bodyFat = 21.20f, water = 51.00f, muscle = 39.30f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c035c18d448070b01cc00d501", "e7423c04ff0189010807d60af900fa"),
            takenAt = 1545131080L, weight = 90.15f, bodyFat = 21.30f, water = 51.10f, muscle = 39.30f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c055c1a16ee06fc01cc00d102", "e7423c0603018b010707cb0ae900f8"),
            takenAt = 1545213678L, weight = 89.40f, bodyFat = 20.90f, water = 51.50f, muscle = 39.50f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c075c1b7858071601cc00d701", "e7423c08fd0188010907dd0b0200fb"),
            takenAt = 1545304152L, weight = 90.70f, bodyFat = 21.50f, water = 50.90f, muscle = 39.20f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c095c1c8e94073001c500dd01", "e7423c0af70185010b07ef0b1b00ff"),
            takenAt = 1545375380L, weight = 92.00f, bodyFat = 22.10f, water = 50.30f, muscle = 38.90f, impedance = 453.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c0b5c1e1422070c01cc00d501", "e7423c0cff0189010807d60af900fa"),
            takenAt = 1545475106L, weight = 90.20f, bodyFat = 21.30f, water = 51.10f, muscle = 39.30f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c0d5c1f6cc5070701cc00d302", "e7423c0e00018a010807d30af500f9"),
            takenAt = 1545563333L, weight = 89.95f, bodyFat = 21.10f, water = 51.20f, muscle = 39.40f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c0f5c20faa6071601cc00d701", "e7423c10fd0188010907dd0b0200fb"),
            takenAt = 1545665190L, weight = 90.70f, bodyFat = 21.50f, water = 50.90f, muscle = 39.20f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c115c222f30070601cc00d302", "e7423c1200018a010707d20af300f9"),
            takenAt = 1545744176L, weight = 89.90f, bodyFat = 21.10f, water = 51.20f, muscle = 39.40f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c135c23584006f501cc00cf02", "e7423c1404018c010607c70ae300f7"),
            takenAt = 1545820224L, weight = 89.05f, bodyFat = 20.70f, water = 51.60f, muscle = 39.60f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c155c24ac5c070001c500d002", "e7423c1602018b010707ce0aed00f8"),
            takenAt = 1545907292L, weight = 89.60f, bodyFat = 20.80f, water = 51.40f, muscle = 39.50f, impedance = 453.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c175c276092070d01c400d401", "e7423c18fe0189010807d70afb00fa"),
            takenAt = 1546084498L, weight = 90.25f, bodyFat = 21.20f, water = 51.00f, muscle = 39.30f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c195c28c17506fa01c400ce02", "e7423c1a03018b010607ca0ae700f7"),
            takenAt = 1546174837L, weight = 89.30f, bodyFat = 20.60f, water = 51.50f, muscle = 39.50f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c1b5c29fbd306e701cc00cc02", "e7423c1c07018e010507bd0ad600f5"),
            takenAt = 1546255315L, weight = 88.35f, bodyFat = 20.40f, water = 51.90f, muscle = 39.80f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c1d5c2dc3d8070c01c400d401", "e7423c1eff0189010807d60af900fa"),
            takenAt = 1546503128L, weight = 90.20f, bodyFat = 21.20f, water = 51.10f, muscle = 39.30f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c1f5c2f0c01071901cc00d901", "e7423c20fc0188010907e00b0600fc"),
            takenAt = 1546587137L, weight = 90.85f, bodyFat = 21.70f, water = 50.80f, muscle = 39.20f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c215c30597c070b01cc00d501", "e7423c22ff0189010807d60af900fa"),
            takenAt = 1546672508L, weight = 90.15f, bodyFat = 21.30f, water = 51.10f, muscle = 39.30f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c235c31aeab070a01c400d401", "e7423c24ff0189010807d50af700fa"),
            takenAt = 1546759851L, weight = 90.10f, bodyFat = 21.20f, water = 51.10f, muscle = 39.30f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c255c331df9070401cc00d302", "e7423c2601018a010707d10af100f9"),
            takenAt = 1546853881L, weight = 89.80f, bodyFat = 21.10f, water = 51.30f, muscle = 39.40f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c275c3466d9070d01c400d401", "e7423c28fe0189010807d70afb00fa"),
            takenAt = 1546938073L, weight = 90.25f, bodyFat = 21.20f, water = 51.00f, muscle = 39.30f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c295c35aca5070e01cc00d501", "e7423c2afe0189010807d70afb00fa"),
            takenAt = 1547021477L, weight = 90.30f, bodyFat = 21.30f, water = 51.00f, muscle = 39.30f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c2b5c37156c06fc01c400d002", "e7423c2c03018b010707cb0ae900f8"),
            takenAt = 1547113836L, weight = 89.40f, bodyFat = 20.80f, water = 51.50f, muscle = 39.50f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c2d5c3867d106fa01c400ce02", "e7423c2e03018b010607ca0ae700f7"),
            takenAt = 1547200465L, weight = 89.30f, bodyFat = 20.60f, water = 51.50f, muscle = 39.50f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c2f5c39cbf9071001cb00d501", "e7423c30fe0189010807d90afd00fa"),
            takenAt = 1547291641L, weight = 90.40f, bodyFat = 21.30f, water = 51.00f, muscle = 39.30f, impedance = 459.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c315c3b2f81070701c400d202", "e7423c3200018a010807d30af500f9"),
            takenAt = 1547382657L, weight = 89.95f, bodyFat = 21.00f, water = 51.20f, muscle = 39.40f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c335c3c330006f301cc00cf02", "e7423c3404018c010607c60ae200f7"),
            takenAt = 1547449088L, weight = 88.95f, bodyFat = 20.70f, water = 51.60f, muscle = 39.60f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c355c3ed7f206fe01cc00d102", "e7423c3602018b010707cc0aeb00f8"),
            takenAt = 1547622386L, weight = 89.50f, bodyFat = 20.90f, water = 51.40f, muscle = 39.50f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c375c40575c070901cc00d501", "e7423c38ff0189010807d50af700fa"),
            takenAt = 1547720540L, weight = 90.05f, bodyFat = 21.30f, water = 51.10f, muscle = 39.30f, impedance = 460.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c395c41a964073001c400dd01", "e7423c3af70185010b07ef0b1b00ff"),
            takenAt = 1547807076L, weight = 92.00f, bodyFat = 22.10f, water = 50.30f, muscle = 38.90f, impedance = 452.00f),
        // Real weigh-in from #398 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e734010100000000000000654a4f5451"), 0,
            listOf("e7423c3b5c483a19071001c300d401", "e7423c3cfe0189010807d90afd00fa"),
            takenAt = 1548237337L, weight = 90.40f, bodyFat = 21.20f, water = 51.00f, muscle = 39.30f, impedance = 451.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 1,
            listOf("e759030101000000000015ab11", "e75903025d51674406e601e8020f01", "e759030368011800fc069308e00170"),
            takenAt = 1565615940L, weight = 88.30f, bodyFat = 52.70f, water = 36.00f, muscle = 28.00f, impedance = 488.00f),
        // Real weigh-in from #488 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e74202015d51678e06f301ec021301", "e742020268011800fd069a08e90172"),
            takenAt = 1565616014L, weight = 88.95f, bodyFat = 53.10f, water = 36.00f, muscle = 28.00f, impedance = 492.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 1,
            listOf("e759030101000000000015ab11", "e75903025d5167b706ea01df020e01", "e759030368011800fc069508e30170"),
            takenAt = 1565616055L, weight = 88.50f, bodyFat = 52.60f, water = 36.00f, muscle = 28.00f, impedance = 479.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 1,
            listOf("e759030101000000000015ab11", "e75903025d5167df06e601fc021101", "e759030368011800fc069308e00170"),
            takenAt = 1565616095L, weight = 88.30f, bodyFat = 52.90f, water = 36.00f, muscle = 28.00f, impedance = 508.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 1,
            listOf("e759030101000000000015ab11", "e75903025d51681506e101f7020f01", "e759030368011800fb069108de016f"),
            takenAt = 1565616149L, weight = 88.05f, bodyFat = 52.70f, water = 36.00f, muscle = 28.00f, impedance = 503.00f),
        // Real weigh-in from #488 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e74204015d5168aa00db0000000000", "e7420402000000000003b30767002e"),
            takenAt = 1565616298L, weight = 10.95f),
        // Real weigh-in from #488 (SBF70, stored on the scale)
        Weighing("SANITAS SBF70", false, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e74204035d5168ee00fc0000000000", "e7420404000000000003c207850034"),
            takenAt = 1565616366L, weight = 12.60f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 1,
            listOf("e759030101000000000015ab11", "e75903025d51697d06e201f6020f01", "e759030368011800fb069108de016f"),
            takenAt = 1565616509L, weight = 88.10f, bodyFat = 52.70f, water = 36.00f, muscle = 28.00f, impedance = 502.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e7590301010000000000159679", "e75903025d5169b606ed01f201e501", "e75903039d013600fb06920d250171"),
            takenAt = 1565616566L, weight = 88.65f, bodyFat = 48.50f, water = 41.30f, muscle = 31.00f, impedance = 498.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e7590301010000000000159679", "e75903025d516a4a06f301fe020401", "e75903036d011900fc0695098b0172"),
            takenAt = 1565616714L, weight = 88.95f, bodyFat = 51.60f, water = 36.50f, muscle = 28.10f, impedance = 510.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e7590301010000000000159679", "e75903025d516dc506db01d001f501", "e759030375011d00fa068a097b016d"),
            takenAt = 1565617605L, weight = 87.75f, bodyFat = 50.10f, water = 37.30f, muscle = 28.50f, impedance = 464.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab3d3838395e"), 2,
            listOf("e759030101000000000015ab3d", "e75903025d516dea06c701e300a902", "e75903032f01a1011207fd0bfc00ee"),
            takenAt = 1565617642L, weight = 86.75f, bodyFat = 16.90f, water = 55.90f, muscle = 41.70f, impedance = 483.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab3d3838395e"), 2,
            listOf("e759030101000000000015ab3d", "e75903025d5172b306ac01e900a202", "e75903033401a3011007ea0bdf00ea"),
            takenAt = 1565618867L, weight = 85.40f, bodyFat = 16.20f, water = 56.40f, muscle = 41.90f, impedance = 489.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab3d3838395e"), 2,
            listOf("e759030101000000000015ab3d", "e75903025d51735806ae01de00a102", "e75903033501a4011007ec0be200ea"),
            takenAt = 1565619032L, weight = 85.50f, bodyFat = 16.10f, water = 56.50f, muscle = 42.00f, impedance = 478.00f),
        // Real weigh-in from #488 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e7340301000000000015967944570062", "e7340302000000000015ab1146524163", "e7340303000000000015ab1f5454005e"), 0,
            listOf("e7590301010000000000159679", "e75903025d51768a06dd01e401f901", "e759030372011c00fa068b097c016e"),
            takenAt = 1565619850L, weight = 87.85f, bodyFat = 50.50f, water = 37.00f, muscle = 28.40f, impedance = 484.00f),
        // Real weigh-in from #1259 (SBF70, live)
        Weighing("SANITAS SBF70", true, listOf("e73401010000000000000065414c455d"), 0,
            listOf("e7590301010000000000000065", "e759030269593329073a01bf00f401", "e7590303f90184011d08440c66011d"),
            takenAt = 1767453481L, weight = 92.50f, bodyFat = 24.40f, water = 50.50f, muscle = 38.80f, impedance = 447.00f),
        // Real weigh-in from #341 (SBF75, live)
        Weighing("SBF75", true, listOf("e734040100000000000000655356004b", "e73404020000000000000066534f4d4b", "e734040300000000000000674a4f4862", "e73404040000000000000068464c416b"), 1,
            listOf("e7590301010000000000000066", "e75903025bc4eeaf069e0000000000", "e7590303000000000007080816011e"),
            takenAt = 1539632815L, weight = 84.70f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212015c5c1af2071b0000000000", "e74212020000000000087009b5013b"),
            takenAt = 1549540082L, weight = 90.95f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212035c628196071a0000000000", "e74212040000000000086f09b3013b"),
            takenAt = 1549959574L, weight = 90.90f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212055c7510b907100000000000", "e74212060000000000086809ab0139"),
            takenAt = 1551175865L, weight = 90.40f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212075c780cd9072d01c5015501", "e7421208b40164012b087d09c3013e"),
            takenAt = 1551371481L, weight = 91.85f, bodyFat = 34.10f, water = 43.60f, muscle = 35.60f, impedance = 453.00f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212095c7ab998073001be015401", "e742120ab50164012b087e09c4013e"),
            takenAt = 1551546776L, weight = 92.00f, bodyFat = 34.00f, water = 43.70f, muscle = 35.60f, impedance = 446.00f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e742120b5c7ababb072f01c6015501", "e742120cb40164012b087e09c4013e"),
            takenAt = 1551547067L, weight = 91.95f, bodyFat = 34.10f, water = 43.60f, muscle = 35.60f, impedance = 454.00f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e742120d5c7ac23d072f01c5015501", "e742120eb40164012b087e09c4013e"),
            takenAt = 1551548989L, weight = 91.95f, bodyFat = 34.10f, water = 43.60f, muscle = 35.60f, impedance = 453.00f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e742120f5c7e3577071d01c6015001", "e7421210b801660129087209b6013b"),
            takenAt = 1551775095L, weight = 91.05f, bodyFat = 33.60f, water = 44.00f, muscle = 35.80f, impedance = 454.00f),
        // Real weigh-in from #435 (SBF75, stored on the scale)
        Weighing("SBF75", false, listOf("e734020100000000000000655400004b", "e7340202000000000000006654455375"), 0,
            listOf("e74212115c80e39a073501c6015701", "e7421212b20163012b088209c9013f"),
            takenAt = 1551950746L, weight = 92.25f, bodyFat = 34.30f, water = 43.40f, muscle = 35.50f, impedance = 454.00f),
        // Real weigh-in from #315 (Crane, live)
        Weighing("AICDSCALE1", true, listOf("e73401018e6de9c5f2561d526371644d"), 0,
            listOf("e7590301018e6de9c5f2561d52", "e75903025b904518065e0000000000", "e759030300000000000725083800f1"),
            takenAt = 1536181528L, weight = 81.50f),
        // Real weigh-in from #315 (Crane, live)
        Weighing("AICDSCALE1", true, listOf("e73401018e6de9c5f2561d526371644d"), 0,
            listOf("e7590301018e6de9c5f2561d52", "e75903025b904536065901ba00e101", "e7590303fc018a00ee0723083500f0"),
            takenAt = 1536181558L, weight = 81.25f, bodyFat = 22.50f, water = 50.80f, muscle = 39.40f, impedance = 442.00f),

    )

    @Test
    fun `captured weigh-ins publish one measurement with the scale's values`() {
        for (w in weighings) {
            val source = "${w.deviceName} ${w.frames.first()}"
            val published = replay(w)

            assertWithMessage(source).that(published).hasSize(1)
            val m = published.single()
            assertWithMessage("$source user").that(m.userId).isEqualTo(1)
            assertWithMessage("$source taken at").that(m.dateTime!!.time / 1000).isEqualTo(w.takenAt)
            listOf(
                MeasurementType.WEIGHT to w.weight,
                MeasurementType.BODY_FAT to w.bodyFat,
                MeasurementType.WATER to w.water,
                MeasurementType.MUSCLE to w.muscle,
                MeasurementType.IMPEDANCE to w.impedance,
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

    /**
     * Connects as the user at [Weighing.owner] of the scale's user list and answers the handler's steps with the
     * scale's acknowledgements as captured (init, status, user list, saved count, user details,
     * measure), then delivers the weigh-in frames.
     */
    private fun replay(w: Weighing): List<ScaleMeasurement> {
        val s = w.userList.first().substring(0, 1)
        val info = hex(w.userList[w.owner])
        val name = String(info.copyOfRange(12, 15).filter { it != 0.toByte() }.toByteArray())
        val birthday = Calendar.getInstance().apply { clear(); set(1900 + (info[15].toInt() and 0xFF), 0, 1) }.time
        val user = ScaleUser(id = 1, userName = name, birthday = birthday, bodyHeight = 175f)

        val callbacks = CapturingCallbacks()
        val handler = BeurerSanitasHandler()
        assertWithMessage(w.deviceName).that(handler.supportFor(device(w.deviceName))).isNotNull()
        handler.attach(NoopTransport(), callbacks, InMemorySettings(), FixedDataProvider(user), CoroutineScope(Dispatchers.Unconfined))
        handler.handleConnected(user)

        val conversation = buildList {
            add(if (s == "f") "f60027" else "e60020")
            add(if (s == "f") "f7f04f006414dc0100000006" else "e7f04f006414140100000005")
            add("${s}7f033000108")
            addAll(w.userList)
            if (w.live) {
                add("${s}7f0410000")
                add(if (s == "f") "f7f036005045544d0207aa81" else "e7f03600444f5862090bb481")
                add("${s}7f04000")
            } else {
                add("${s}7f0410200")
            }
            addAll(w.frames)
        }
        conversation.forEach { handler.handleNotification(chr, hex(it)) }
        handler.handleDisconnected()
        return callbacks.published
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

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
