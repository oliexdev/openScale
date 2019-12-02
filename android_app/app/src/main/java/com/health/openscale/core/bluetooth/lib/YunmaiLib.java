/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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

public class YunmaiLib {
    private int sex; // male = 1; female = 0
    private float height;

    public YunmaiLib(int sex, float height) {
        this.sex = sex;
        this.height = height;
    }

    public float getWater(float bodyFat) {
        return ((100.0f - bodyFat) * 0.726f * 100.0f + 0.5f) / 100.0f;
    }

    public float getFat(int age, float weight, int resistance) {
        // for < 0x1e version devices
        float fat;

        float r = (resistance - 100.0f) / 100.0f;
        float h = height / 100.0f;

        if (this.sex == 1) {
            fat = (weight * 1.5f / h / h) + (age * 0.08f) - 10.8f;
        } else {
            fat = (weight * 1.5f / h / h) + (age * 0.08f);
        }

        fat = (fat - 7.4f) + r;

        return fat;
    }

    public float getMuscle(float bodyFat) {
        float muscle;
        muscle = (100.0f - bodyFat) * 0.67f;

        if (sex == 1) {
            muscle = (100.0f - bodyFat) * 0.7f;
        }

        muscle = ((muscle * 100.0f) + 0.5f) / 100.0f;

        return muscle;
    }

    public float getBoneMass(float muscle, float weight) {
        float boneMass;

        float h = height - 170.0f;

        if (sex == 1) {
            boneMass = ((weight * (muscle  / 100.0f) * 4.0f) / 7.0f * 0.22f * 0.6f) + (h / 100.0f);
        } else {
            boneMass = ((weight * (muscle  / 100.0f) * 4.0f) / 7.0f * 0.34f * 0.45f) + (h / 100.0f);
        }

        boneMass = ((boneMass * 10.0f) + 0.5f) / 10.0f;

        return boneMass;
    }

    public float getLeanBodyMass(float weight, float bodyFat) {
        if (bodyFat < 5.0f || bodyFat > 75.0f) {
            return 0.0f;
        }
        return weight * (100.0f - bodyFat) / 100.0f;
    }

    public float getVisceralFat(float bodyFat, int age) {
        float f = (bodyFat < 5.0f || bodyFat > 75.0f) ? 0.0f : bodyFat;
        int a = (age < 18 || age > 120) ? 18 : age;

        if (sex == 1) {
            if (a < 40) {
                f -= 21.0f;
            } else if (a < 60) {
                f -= 22.0f;
            } else {
                f -= 24.0f;
            }
        } else {
            if (a < 40) {
                f -= 34.0f;
            } else if (a < 60) {
                f -= 35.0f;
            } else {
                f -= 36.0f;
            }
        }

        float d = sex == 1 ? 1.4f : 1.8f;
        if (f > 0.0f) {
            d = 1.1f;
        }

        float vf = (f / d) + 9.5f;
        if (vf < 1.0f) {
            return 1.0f;
        }
        if (vf > 30.0f) {
            return 30.0f;
        }
        return vf;
    }
}
