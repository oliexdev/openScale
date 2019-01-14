/*  Copyright (C) 2018  Maks Verver <maks@verver.ch>
 *                2019 olie.xdev <olie.xdev@googlemail.com>
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package com.health.openscale.core.bluetooth.lib;

/**
 * Class with static helper methods. This is a separate class for testing purposes.
 *
 * @see com.health.openscale.core.bluetooth.BluetoothTrisaBodyAnalyze
 */
public class TrisaBodyAnalyzeLib {

    private boolean isMale;
    private int ageYears;
    private float heightCm;

    public TrisaBodyAnalyzeLib(int sex, int age, float height) {
        isMale = sex == 1 ? true : false; // male = 1; female = 0
        ageYears = age;
        heightCm = height;
    }

    public float getBMI(float weightKg) {
        return weightKg * 1e4f / (heightCm * heightCm);
    }

    public float getWater(float weightKg, float impedance) {
        float bmi = getBMI(weightKg);

        float water = isMale
                ? 87.51f + (-1.162f * bmi - 0.00813f * impedance + 0.07594f * ageYears)
                : 77.721f + (-1.148f * bmi - 0.00573f * impedance + 0.06448f * ageYears);

        return water;
    }

    public float getFat(float weightKg, float impedance) {
        float bmi = getBMI(weightKg);

        float fat = isMale
                ? bmi * (1.479f + 4.4e-4f * impedance) + 0.1f * ageYears - 21.764f
                : bmi * (1.506f + 3.908e-4f * impedance) + 0.1f * ageYears - 12.834f;

        return fat;
    }

    public float getMuscle(float weightKg, float impedance) {
        float bmi = getBMI(weightKg);

        float muscle = isMale
                ? 74.627f + (-0.811f * bmi - 0.00565f * impedance - 0.367f * ageYears)
                : 57.0f + (-0.694f * bmi - 0.00344f * impedance - 0.255f * ageYears);

        return muscle;
    }

    public float getBone(float weightKg, float impedance) {
        float bmi = getBMI(weightKg);

        float bone = isMale
                ? 7.829f + (-0.0855f * bmi - 5.92e-4f * impedance - 0.0389f * ageYears)
                : 7.98f + (-0.0973f * bmi - 4.84e-4f * impedance - 0.036f * ageYears);

        return bone;
    }
}
