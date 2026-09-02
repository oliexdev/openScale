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
package com.health.openscale.core.data

import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The identity rules everything else leans on: the registry invariant that keeps the
 * migration, the CSV headers and the sync wire format correct at once, and the slug rules
 * that keep the 15→16 migration from tripping over its own unique index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementTypeIdentityTest {

    // --- the registry invariant -------------------------------------------------

    /** The `key` column values shipped by every schema up to 15, in seed order — minus
     *  ECW, ICW and BCM, which MIGRATION_15_16 retires into the ble.* namespace. */
    private val historicalEnumNames = listOf(
        "WEIGHT", "BMI", "BODY_FAT", "WATER", "MUSCLE", "LBM", "BONE", "WAIST", "WHR",
        "WHTR", "HIPS", "VISCERAL_FAT", "CHEST", "THIGH", "BICEPS", "NECK", "CALIPER_1",
        "CALIPER_2", "CALIPER_3", "CALIPER", "BMR", "TDEE", "HEART_RATE", "IMPEDANCE",
        "IMPEDANCE_LOW", "PROTEIN", "CALORIES", "COMMENT", "DATE", "TIME", "USER"
    )

    @Test
    fun `every predefined identity is the lowercased historical enum name`() {
        // MIGRATION_15_16 rewrites `'builtin.' || lower(key)`; this is what makes that
        // SQL land exactly on the registry.
        assertThat(MeasurementType.allKeys.map { it.identity })
            .containsExactlyElementsIn(historicalEnumNames.map { "builtin." + it.lowercase() })
            .inOrder()
    }

    @Test
    fun `identity column keys are byte-identical to the pre-16 enum names`() {
        // CSV headers derive from the identity, so files written by older versions keep matching.
        val rows = MeasurementType.seedRows()
        assertThat(rows.map { MeasurementType.identityColumnKey(it.identity) })
            .containsExactlyElementsIn(historicalEnumNames).inOrder()
    }

    @Test
    fun `seed rows carry their key's defaults`() {
        val weight = MeasurementType.seedRows().first()
        assertThat(weight.identity).isEqualTo("builtin.weight")
        assertThat(weight.unit).isEqualTo(UnitType.KG)
        assertThat(weight.isPinned).isTrue()
        assertThat(weight.name).isNull() // display name comes from resources, not the row

        val impedance = MeasurementType.seedRows().first { it.identity == "builtin.impedance" }
        assertThat(impedance.isInternal).isTrue()
        assertThat(impedance.isEnabled).isFalse()
    }

    // --- origin and lookup ------------------------------------------------------

    @Test
    fun `origin is read off the namespace prefix`() {
        val builtIn = MeasurementType(identity = MeasurementType.WEIGHT.identity)
        val device = MeasurementType(identity = "ble.segmental.fat.torso", name = "x")
        val user = MeasurementType(identity = "user.schritte", name = "Schritte")

        assertThat(builtIn.isBuiltIn()).isTrue()
        assertThat(builtIn.key).isEqualTo(MeasurementType.WEIGHT)
        assertThat(device.isDeviceOwned()).isTrue()
        assertThat(device.key).isNull()
        assertThat(user.isUserOwned()).isTrue()
        assertThat(user.key).isNull()
    }

    @Test
    fun `device factories mint the ble namespace, nobody types it`() {
        val key = MeasurementType.deviceFloat("segmental.fat.left_arm", R.string.measurement_type_weight)
        assertThat(key.identity).isEqualTo("ble.segmental.fat.left_arm")
        // Equality is on identity alone: two declarations of one key can never be two columns.
        assertThat(key).isEqualTo(
            MeasurementType.deviceFloat("segmental.fat.left_arm", R.string.measurement_type_bmi, color = 1)
        )
    }

    // --- user identities --------------------------------------------------------

    @Test
    fun `user identities are slugs, numbered instead of colliding`() {
        // This is the case that would abort MIGRATION_15_16 on the unique index:
        // pre-16 allows two custom types to share a name.
        val first = MeasurementType.userIdentityFor("Schritte", emptySet())
        val second = MeasurementType.userIdentityFor("Schritte", setOf("SCHRITTE"))
        assertThat(listOf(first, second)).containsExactly("user.schritte", "user.schritte_2").inOrder()

        assertThat(MeasurementType.userIdentityFor("  Blood   pressure (sys) ", emptySet()))
            .isEqualTo("user.blood_pressure_sys")
        assertThat(MeasurementType.userIdentityFor("Körperfett", emptySet()))
            .isEqualTo("user.körperfett") // unicode aware, no ASCII folding
        assertThat(MeasurementType.userIdentityFor("---", emptySet())).isEqualTo("user.unnamed")
    }

    @Test
    fun `a user type can never shadow a predefined column`() {
        assertThat(MeasurementType.userIdentityFor("Weight", emptySet())).isEqualTo("user.weight_2")
        assertThat(MeasurementType.userIdentityFor("body fat", emptySet())).isEqualTo("user.body_fat_2")
    }

    // --- display name -----------------------------------------------------------

    @Test
    fun `predefined names are localized, custom names are stored`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val weight = MeasurementType(identity = MeasurementType.WEIGHT.identity, name = "ignored")
        val user = MeasurementType(identity = "user.schritte", name = "Schritte")

        assertThat(weight.getDisplayName(context)).isEqualTo(context.getString(R.string.measurement_type_weight))
        assertThat(user.getDisplayName(context)).isEqualTo("Schritte")
    }

    // --- the editable CSV column -----------------------------------------------

    @Test
    fun `a typed-in column is normalised and handed back as an identity`() {
        assertThat(MeasurementType.userIdentityFrom("STEPS", emptySet())).isEqualTo("user.steps")
        assertThat(MeasurementType.userIdentityFrom("Blood pressure (sys)", emptySet()))
            .isEqualTo("user.blood_pressure_sys")
    }

    @Test
    fun `a typed-in column that is not free comes back as null`() {
        val taken = setOf("SCHRITTE", "SEGMENTAL_FAT_LEFT_ARM")

        assertThat(MeasurementType.userIdentityFrom("STEPS", taken)).isEqualTo("user.steps")
        // Nothing is numbered away, unlike userIdentityFor: the user asked for that exact
        // column, so the editor has to say no.
        assertThat(MeasurementType.userIdentityFrom("Schritte", taken)).isNull()
        assertThat(MeasurementType.userIdentityFrom("segmental_fat_left_arm", taken)).isNull()
        assertThat(MeasurementType.userIdentityFrom("Weight", emptySet())).isNull()
        assertThat(MeasurementType.userIdentityFrom("", emptySet())).isNull()
        assertThat(MeasurementType.userIdentityFrom("---", emptySet())).isNull()
    }

    @Test
    fun `unit families - a percentage stands alone, weights convert among themselves`() {
        assertThat(UnitType.PERCENT.convertibleUnits()).containsExactly(UnitType.PERCENT)
        assertThat(UnitType.KG.convertibleUnits())
            .containsExactly(UnitType.KG, UnitType.LB, UnitType.ST)
        assertThat(UnitType.CM.convertibleUnits()).containsExactly(UnitType.CM, UnitType.INCH)
        // Closed under conversion: staying inside the family always means a real conversion.
        UnitType.entries.forEach { unit ->
            unit.convertibleUnits().forEach { target ->
                assertThat(target.convertibleUnits()).contains(unit)
            }
        }
    }
}
