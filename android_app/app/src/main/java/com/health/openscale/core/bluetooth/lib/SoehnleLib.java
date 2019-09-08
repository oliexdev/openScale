/* Copyright (C) 2019 olie.xdev <olie.xdev@googlemail.com>
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

public class SoehnleLib {
    private boolean isMale; // male = 1; female = 0
    private int age;
    private float height;
    private int activityLevel;

    public SoehnleLib(boolean isMale, int age, float height, int activityLevel) {
        this.isMale = isMale;
        this.age = age;
        this.height = height;
        this.activityLevel = activityLevel;
    }

    public float getFat(final float weight, final float imp50) { // in %
        float activityCorrFac = 0.0f;

        switch (activityLevel) {
            case 4: {
                if (isMale) {
                    activityCorrFac = 2.5f;
                }
                else {
                    activityCorrFac = 2.3f;
                }
                break;
            }
            case 5: {
                if (isMale) {
                    activityCorrFac = 4.3f;
                }
                else {
                    activityCorrFac = 4.1f;
                }
                break;
            }
        }

        float sexCorrFac;
        float activitySexDiv;

        if (isMale) {
            sexCorrFac = 0.250f;
            activitySexDiv = 65.5f;
        }
        else {
            sexCorrFac = 0.214f;
            activitySexDiv = 55.1f;
        }

        return 1.847f * weight * 10000.0f / (height * height) + sexCorrFac * age + 0.062f * imp50 - (activitySexDiv - activityCorrFac);
    }

    public float computeBodyMassIndex(final float weight) {
        return 10000.0f * weight / (height * height);
    }

    public float getWater(final float weight, final float imp50) { // in %
        float activityCorrFac = 0.0f;

        switch (activityLevel) {
            case 1:
            case 2:
            case 3: {
                if (isMale) {
                    activityCorrFac = 2.83f;
                }
                else {
                    activityCorrFac = 0.0f;
                }
                break;
            }
            case 4: {
                if (isMale) {
                    activityCorrFac = 3.93f;
                }
                else {
                    activityCorrFac = 0.4f;
                }
                break;
            }
            case 5: {
                if (isMale) {
                    activityCorrFac = 5.33f;
                }
                else {
                    activityCorrFac = 1.4f;
                }
                break;
            }
        }
        return (0.3674f * height * height / imp50 + 0.17530f * weight - 0.11f * age + (6.53f + activityCorrFac)) / weight * 100.0f;
    }

    public float getMuscle(final float weight, final float imp50, final float imp5) { // in %
        float activityCorrFac = 0.0f;

        switch (activityLevel) {
            case 1:
            case 2:
            case 3: {
                if (isMale) {
                    activityCorrFac = 3.6224f;
                }
                else {
                    activityCorrFac = 0.0f;
                }
                break;
            }
            case 4: {
                if (isMale) {
                    activityCorrFac = 4.3904f;
                }
                else {
                    activityCorrFac = 0.0f;
                }
                break;
            }
            case 5: {
                if (isMale) {
                    activityCorrFac = 5.4144f;
                }
                else {
                    activityCorrFac = 1.664f;
                }
                break;
            }
        }
        return ((0.47027f / imp50 - 0.24196f / imp5) * height * height + 0.13796f * weight - 0.1152f * age + (5.12f + activityCorrFac)) / weight * 100.0f;
    }
}
