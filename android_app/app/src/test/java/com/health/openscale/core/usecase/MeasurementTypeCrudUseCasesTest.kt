/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.usecase

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the unit-conversion logic in [MeasurementTypeCrudUseCases.updateTypeAndConvertValues]
 * against in-memory Room (Robolectric). Covers generic length conversion, percent<->absolute
 * composition conversion (using the per-measurement WEIGHT), and the skip/no-op paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementTypeCrudUseCasesTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var useCase: MeasurementTypeCrudUseCases
    private var userId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(ApplicationProvider.getApplicationContext())
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        useCase = MeasurementTypeCrudUseCases(repo, ApplicationProvider.getApplicationContext())
        userId = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun type(key: MeasurementType.Key<*>): MeasurementType =
        repo.getAllMeasurementTypes().first().first { it.key == key }

    private suspend fun newMeasurement(timestamp: Long): Int =
        repo.insertMeasurement(Measurement(userId = userId, timestamp = timestamp)).toInt()

    private suspend fun valueOf(typeId: Int): Float? =
        repo.getValuesForType(typeId).first().firstOrNull()?.floatValue

    @Test
    fun sameUnit_updatesDefinitionWithoutConverting() = runBlocking {
        val waist = type(MeasurementType.WAIST)
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = waist.id, floatValue = 90f))

        val report = useCase.updateTypeAndConvertValues(waist, waist.copy(name = "Bauch")).getOrThrow()

        assertThat(report.attempted).isFalse()
        assertThat(valueOf(waist.id)).isWithin(1e-3f).of(90f)
        assertThat(type(MeasurementType.WAIST).name).isEqualTo("Bauch")
    }

    @Test
    fun genericConversion_centimeterToInch() = runBlocking {
        val waist = type(MeasurementType.WAIST) // unit CM by default
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = waist.id, floatValue = 100f))

        val report = useCase.updateTypeAndConvertValues(waist, waist.copy(unit = UnitType.INCH)).getOrThrow()

        assertThat(report.attempted).isTrue()
        assertThat(report.updatedCount).isEqualTo(1)
        assertThat(valueOf(waist.id)).isWithin(1e-2f).of(39.3701f)
    }

    @Test
    fun compositionConversion_percentToKg_usesPerMeasurementWeight() = runBlocking {
        val weight = type(MeasurementType.WEIGHT)   // KG
        val bodyFat = type(MeasurementType.BODY_FAT) // PERCENT
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = weight.id, floatValue = 80f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = bodyFat.id, floatValue = 20f))

        val report = useCase.updateTypeAndConvertValues(bodyFat, bodyFat.copy(unit = UnitType.KG)).getOrThrow()

        assertThat(report.updatedCount).isEqualTo(1)
        assertThat(valueOf(bodyFat.id)).isWithin(1e-2f).of(16f) // 20% of 80kg
    }

    @Test
    fun compositionConversion_percentToKg_skipsRowsWithoutWeight() = runBlocking {
        val bodyFat = type(MeasurementType.BODY_FAT)
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = bodyFat.id, floatValue = 20f))

        val report = useCase.updateTypeAndConvertValues(bodyFat, bodyFat.copy(unit = UnitType.KG)).getOrThrow()

        assertThat(report.attempted).isTrue()
        assertThat(report.updatedCount).isEqualTo(0)
        assertThat(valueOf(bodyFat.id)).isWithin(1e-3f).of(20f) // unchanged
    }

    // --- device-contributed types ----------------------------------------------

    private val leftArmFat = MeasurementType.deviceFloat(
        "segmental.fat.left_arm", com.health.openscale.R.string.measurement_type_segmental_fat_left_arm
    )

    @Test
    fun resolveOrCreate_createsOnceAndReusesAfterwards() = runBlocking {
        val first = useCase.resolveOrCreate(leftArmFat)
        val second = useCase.resolveOrCreate(leftArmFat)

        assertThat(first).isNotNull()
        assertThat(second!!.id).isEqualTo(first!!.id)
        assertThat(first.identity).isEqualTo("ble.segmental.fat.left_arm")
        assertThat(first.isEnabled).isTrue()
        assertThat(first.isPinned).isFalse()
        assertThat(allTypes().count { it.identity == leftArmFat.identity }).isEqualTo(1)
    }

    @Test
    fun resolveOrCreate_stillFindsTheTypeAfterTheUserRenamedIt() = runBlocking {
        val created = useCase.resolveOrCreate(leftArmFat)!!

        // The editor rebuilds the entity from scratch, dropping identity and isInternal.
        useCase.update(created.copy(identity = "", name = "Fett Arm links")).getOrThrow()

        val resolved = useCase.resolveOrCreate(leftArmFat)
        assertThat(resolved!!.id).isEqualTo(created.id)
        assertThat(resolved.name).isEqualTo("Fett Arm links")
    }

    @Test
    fun resolveOrCreate_recreatesATypeTheUserDeleted() = runBlocking {
        val created = useCase.resolveOrCreate(leftArmFat)!!
        useCase.delete(created).getOrThrow()

        val recreated = useCase.resolveOrCreate(leftArmFat)
        assertThat(recreated).isNotNull()
        assertThat(recreated!!.id).isNotEqualTo(created.id)
    }

    // --- identities on add/update ----------------------------------------------

    @Test
    fun add_assignsAUserIdentityWithoutTheEditorKnowing() = runBlocking {
        val id = useCase.add(MeasurementType(name = "Schritte")).getOrThrow().toInt()
        assertThat(typeById(id).identity).isEqualTo("user.schritte")
    }

    @Test
    fun add_numbersASecondTypeWithTheSameName() = runBlocking {
        useCase.add(MeasurementType(name = "Schritte")).getOrThrow()
        val second = useCase.add(MeasurementType(name = "Schritte")).getOrThrow().toInt()
        assertThat(typeById(second).identity).isEqualTo("user.schritte_2")
    }

    @Test
    fun add_rejectsASecondRowForAPredefinedType() = runBlocking {
        // The point of the unique identity index: since MIGRATION_12_13 removed the unique
        // key index, nothing had stopped a second WEIGHT row from existing.
        val duplicate = useCase.add(MeasurementType(identity = MeasurementType.WEIGHT.identity))
        assertThat(duplicate.isFailure).isTrue()
        assertThat(allTypes().count { it.identity == MeasurementType.WEIGHT.identity }).isEqualTo(1)
    }

    @Test
    fun update_restoresTheIdentityAndInternalFlagTheEditorDropped() = runBlocking {
        val impedance = type(MeasurementType.IMPEDANCE)
        assertThat(impedance.isInternal).isTrue()

        useCase.update(impedance.copy(identity = "", isInternal = false, color = 123)).getOrThrow()

        val after = typeById(impedance.id)
        assertThat(after.identity).isEqualTo(MeasurementType.IMPEDANCE.identity)
        assertThat(after.isInternal).isTrue()
        assertThat(after.color).isEqualTo(123)
    }

    private suspend fun allTypes(): List<MeasurementType> = repo.getAllMeasurementTypes().first()

    private suspend fun typeById(id: Int): MeasurementType = allTypes().first { it.id == id }

    @Test
    fun update_takesTheCsvColumnTheUserTypedIn() = runBlocking {
        val id = useCase.add(MeasurementType(name = "Schritte")).getOrThrow().toInt()

        // What the editor sends when the CSV column field was edited.
        useCase.update(typeById(id).copy(identity = "user.steps")).getOrThrow()

        assertThat(typeById(id).identity).isEqualTo("user.steps")
        assertThat(MeasurementType.identityColumnKey(typeById(id).identity)).isEqualTo("STEPS")
    }

    @Test
    fun update_keepsAUserIdentityWhenOnlyTheNameChanges() = runBlocking {
        val id = useCase.add(MeasurementType(name = "Schritte")).getOrThrow().toInt()

        // Frozen: a rename must not invalidate earlier exports.
        useCase.update(typeById(id).copy(name = "Tagesschritte", identity = "")).getOrThrow()

        assertThat(typeById(id).identity).isEqualTo("user.schritte")
        assertThat(typeById(id).name).isEqualTo("Tagesschritte")
    }

    @Test
    fun update_ignoresAClaimedIdentityOnTypesThatDoNotOwnOne() = runBlocking {
        val fromScale = useCase.resolveOrCreate(leftArmFat)!!
        val builtIn = type(MeasurementType.BMI)

        useCase.update(fromScale.copy(identity = "user.hijacked")).getOrThrow()
        useCase.update(builtIn.copy(identity = "user.hijacked")).getOrThrow()

        assertThat(typeById(fromScale.id).identity).isEqualTo("ble.segmental.fat.left_arm")
        assertThat(typeById(builtIn.id).identity).isEqualTo(MeasurementType.BMI.identity)
    }

    // --- namespace enforcement at the creation seams ----------------------------

    @Test
    fun resolveOrCreate_materializesOnlyBleKeys() = runBlocking {
        // The Key constructor cannot be sealed against the module, so a handler could mint
        // a key in a foreign namespace. The creation seam is the enforcement: anything that
        // is not ble.* resolves but never creates.
        val minted = MeasurementType.Key<Float>(
            "builtin.smuggled", com.health.openscale.R.string.measurement_type_weight,
            InputFieldType.FLOAT, UnitType.PERCENT, listOf(UnitType.PERCENT),
            listOf(InputFieldType.FLOAT), UnitType.PERCENT, 0,
            MeasurementTypeIcon.IC_DEFAULT, false, true, false, false, false
        )

        assertThat(useCase.resolveOrCreate(minted)).isNull()
        assertThat(allTypes().none { it.identity == "builtin.smuggled" }).isTrue()
    }

    @Test
    fun resolveOrCreate_resolvesButNeverRecreatesAPredefinedRow() = runBlocking {
        // A predefined key resolves to its seeded row…
        val resolved = useCase.resolveOrCreate(MeasurementType.WEIGHT)
        assertThat(resolved!!.identity).isEqualTo(MeasurementType.WEIGHT.identity)

        // …and if that row were ever missing, it is not half-healed here: seeding and the
        // migrations own predefined rows.
        useCase.delete(resolved.copy()).getOrThrow()
        assertThat(useCase.resolveOrCreate(MeasurementType.WEIGHT)).isNull()
        assertThat(allTypes().none { it.identity == MeasurementType.WEIGHT.identity }).isTrue()
    }

    @Test
    fun add_ignoresASmuggledForeignNamespace() = runBlocking {
        val bleId = useCase.add(
            MeasurementType(identity = "ble.smuggled", name = "Schritte")
        ).getOrThrow().toInt()
        val ghostId = useCase.add(
            MeasurementType(identity = "builtin.ghost", name = "Geist")
        ).getOrThrow().toInt()

        // Both land in the user namespace, derived from the display name.
        assertThat(typeById(bleId).identity).isEqualTo("user.schritte")
        assertThat(typeById(ghostId).identity).isEqualTo("user.geist")
    }
}
