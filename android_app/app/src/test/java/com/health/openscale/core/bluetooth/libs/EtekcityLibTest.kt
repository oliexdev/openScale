package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test

/**
 * Unit tests for [EtekcityLib].
 */
class EtekcityLibTest {
    internal val EPS = 1e-3 // general float tolerance

    val lib = EtekcityLib(gender = GenderType.MALE, age = 30, weightKg = 70.0, heightM = 1.8, impedance = 527.0)

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        assertThat(lib.bmi).isWithin(EPS).of(21.6049)
        assertThat(lib.bodyFatPercentage).isWithin(EPS).of(13.0)
        assertThat(lib.fatFreeWeight).isWithin(EPS).of(60.9)
        assertThat(lib.visceralFat).isWithin(EPS).of(4.7968)
        assertThat(lib.water).isWithin(EPS).of(62.814)
        assertThat(lib.basalMetabolicRate).isWithin(EPS).of(1685.44)
        assertThat(lib.skeletalMusclePercentage).isWithin(EPS).of(56.202)
        assertThat(lib.boneMass).isWithin(EPS).of(3.045)
        assertThat(lib.subcutaneousFat).isWithin(EPS).of(11.4897)
        assertThat(lib.muscleMass).isWithin(EPS).of(57.855)
        assertThat(lib.proteinPercentage).isWithin(EPS).of(19.836)
        assertThat(lib.weightScore).isEqualTo(100)
        assertThat(lib.fatScore).isEqualTo(86)
        assertThat(lib.bmiScore).isEqualTo(98)
        assertThat(lib.healthScore).isEqualTo(94)
        assertThat(lib.metabolicAge).isEqualTo(26)
    }

    @Test
    fun bmi_monotonicity_weightUp_heightSame_increases() {
        assertThat(lib.run { copy(weightKg = weightKg + 5.0) }.bmi).isGreaterThan(lib.bmi)
    }

    @Test
    fun bmi_monotonicity_heightUp_weightSame_decreases() {
        assertThat(lib.run { copy(heightM = heightM + 0.05) }.bmi).isLessThan(lib.bmi)
    }
}
