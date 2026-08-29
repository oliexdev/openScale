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
import com.health.openscale.core.bluetooth.libs.OmronBodyCompositionLib
import org.junit.Test

/**
 * Model-recognition tests for [OmronWlcHandler].
 *
 * Omron devices advertise as `BLEsmart_<group><model><mac>`; the two hex fields are the device
 * group and model ids from the vendor app's own device table. Once bonded, Android reports the
 * plain GAP name instead, so both forms have to resolve to the same record layout.
 */
class OmronWlcHandlerTest {

    @Test
    fun `recognises the HBF-702T variants from their advertised ids`() {
        // Japan, KRD-703T and Asia-Pacific trims — all 48-byte records.
        for (name in listOf(
            "BLEsmart_0001000C0080E1A2B3C4",
            "BLEsmart_000100110080E1A2B3C4",
            "BLEsmart_0001040C0080E1A2B3C4"
        )) {
            val model = OmronWlcHandler.modelFor(name)
            assertThat(model).isNotNull()
            assertThat(model!!.second).isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_702T)
        }
    }

    @Test
    fun `recognises the 32 byte HBF siblings and their body age variants`() {
        val withBodyAge = listOf(
            "BLEsmart_000100090080E1A2B3C4", // HBF-227T
            "BLEsmart_0001000B0080E1A2B3C4", // HBF-228T
            "BLEsmart_0001000D0080E1A2B3C4", // HBF-230T
            "BLEsmart_000104080080E1A2B3C4"  // HBF-222T Asia-Pacific
        )
        for (name in withBodyAge) {
            assertThat(OmronWlcHandler.modelFor(name)?.second)
                .isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_32)
        }

        val withoutBodyAge = listOf(
            "BLEsmart_000101100080E1A2B3C4", // BCM-500
            "BLEsmart_000102080080E1A2B3C4"  // VIVA
        )
        for (name in withoutBodyAge) {
            assertThat(OmronWlcHandler.modelFor(name)?.second)
                .isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE)
        }
    }

    @Test
    fun `matching is case insensitive`() {
        assertThat(OmronWlcHandler.modelFor("blesmart_0001040c0080e1a2b3c4")?.first)
            .isEqualTo("Omron HBF-702T")
        assertThat(OmronWlcHandler.modelFor("hbf-702t")?.first).isEqualTo("Omron HBF-702T")
    }

    @Test
    fun `falls back to the GAP name reported once the scale is bonded`() {
        assertThat(OmronWlcHandler.modelFor("HBF-702T")?.second)
            .isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_702T)
        assertThat(OmronWlcHandler.modelFor("KRD-703T")?.second)
            .isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_702T)
        assertThat(OmronWlcHandler.modelFor("HBF-228T")?.second)
            .isEqualTo(OmronBodyCompositionLib.PROFILE_HBF_32)
    }

    @Test
    fun `does not claim other Omron devices`() {
        // Blood pressure monitors advertise in group 0 and speak a different record format.
        assertThat(OmronWlcHandler.modelFor("BLEsmart_000002210080E1A2B3C4")).isNull()
        assertThat(OmronWlcHandler.modelFor("HEM-7322T")).isNull()
    }

    @Test
    fun `does not claim unrelated or malformed names`() {
        assertThat(OmronWlcHandler.modelFor("")).isNull()
        assertThat(OmronWlcHandler.modelFor("MI SCALE")).isNull()
        assertThat(OmronWlcHandler.modelFor("BLEsmart_")).isNull()
        assertThat(OmronWlcHandler.modelFor("BLEsmart_00010")).isNull()
        assertThat(OmronWlcHandler.modelFor("BLEsmart_zzzzzzzz0080E1A2")).isNull()
    }
}
