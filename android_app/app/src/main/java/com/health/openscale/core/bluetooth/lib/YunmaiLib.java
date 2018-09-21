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
}
