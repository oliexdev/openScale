/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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

/**
 * based on https://github.com/prototux/MIBCS-reverse-engineering by prototux
 */

package com.health.openscale.core.bluetooth.lib;

public class MiScaleLib {
    private int sex; // male = 1; female = 0
    private int age;
    private float height;

    public MiScaleLib(int sex, int age, float height) {
        this.sex = sex;
        this.age = age;
        this.height = height;
    }

    private float getLBMCoefficient(float weight, float impedance) {
        float lbm =  (height * 9.058f / 100.0f) * (height / 100.0f);
        lbm += weight * 0.32f + 12.226f;
        lbm -= impedance * 0.0068f;
        lbm -= age * 0.0542f;

        return lbm;
    }

    public float getBMI(float weight) {
        return weight / (((height * height) / 100.0f) / 100.0f);
    }

    public float getMuscle(float weight, float impedance) {
        float muscleMass = weight - ((getBodyFat(weight, impedance) * 0.01f) * weight) - getBoneMass(weight, impedance);

        if (sex == 0 && muscleMass >= 84.0f) {
            muscleMass = 120.0f;
        }
        else if (sex == 1 && muscleMass >= 93.5f) {
            muscleMass = 120.0f;
        }

        return muscleMass;
    }

    public float getWater(float weight, float impedance) {
        float coeff;
        float water = (100.0f - getBodyFat(weight, impedance)) * 0.7f;

        if (water < 50) {
            coeff = 1.02f;
        } else {
            coeff = 0.98f;
        }

        return coeff * water;
    }

    public float getBoneMass(float weight, float impedance) {
        float boneMass;
        float base;

        if (sex == 0) {
            base = 0.245691014f;
        }
        else {
            base = 0.18016894f;
        }

        boneMass = (base - (getLBMCoefficient(weight, impedance) * 0.05158f)) * -1.0f;

        if (boneMass > 2.2f) {
            boneMass += 0.1f;
        }
        else {
            boneMass -= 0.1f;
        }

        if (sex == 0 && boneMass > 5.1f) {
            boneMass = 8.0f;
        }
        else if (sex == 1 && boneMass > 5.2f) {
            boneMass = 8.0f;
        }

        return boneMass;
    }

    public float getVisceralFat(float weight) {
        float visceralFat = 0.0f;
        if (sex == 0) {
            if (weight > (13.0f - (height * 0.5f)) * -1.0f) {
                float subsubcalc = ((height * 1.45f) + (height * 0.1158f) * height) - 120.0f;
                float subcalc = weight * 500.0f / subsubcalc;
                visceralFat = (subcalc - 6.0f) + (age * 0.07f);
            }
            else {
                float subcalc = 0.691f + (height * -0.0024f) + (height * -0.0024f);
                visceralFat = (((height * 0.027f) - (subcalc * weight)) * -1.0f) + (age * 0.07f) - age;
            }
        }
        else {
            if (height < weight * 1.6f) {
                float subcalc = ((height * 0.4f) - (height * (height * 0.0826f))) * -1.0f;
                visceralFat = ((weight * 305.0f) / (subcalc + 48.0f)) - 2.9f + (age * 0.15f);
            }
            else {
                float subcalc = 0.765f + height * -0.0015f;
                visceralFat = (((height * 0.143f) - (weight * subcalc)) * -1.0f) + (age * 0.15f) - 5.0f;
            }
        }

        return visceralFat;
    }

    public float getBodyFat(float weight, float impedance) {
        float bodyFat = 0.0f;
        float lbmSub = 0.8f;

        if (sex == 0 && age <= 49) {
            lbmSub = 9.25f;
        } else if (sex == 0 && age > 49) {
            lbmSub = 7.25f;
        }

        float lbmCoeff = getLBMCoefficient(weight, impedance);
        float coeff = 1.0f;

        if (sex == 1 && weight < 61.0f) {
            coeff = 0.98f;
        }
        else if (sex == 0 && weight > 60.0f) {
            coeff = 0.96f;

            if (height > 160.0f) {
                coeff *= 1.03f;
            }
        } else if (sex == 0 && weight < 50.0f) {
            coeff = 1.02f;

            if (height > 160.0f) {
                coeff *= 1.03f;
            }
        }

        bodyFat = (1.0f - (((lbmCoeff - lbmSub) * coeff) / weight)) * 100.0f;

        if (bodyFat > 63.0f) {
            bodyFat = 75.0f;
        }

        return bodyFat;
    }
}

