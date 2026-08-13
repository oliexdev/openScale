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
package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.data.ActivityLevel
import kotlin.math.sqrt


class YunmaiLib(// male = 1; female = 0
    private val sex: Int, private val height: Float, activityLevel: ActivityLevel
) {
    private val fitnessBodyType: Boolean

    init {
        this.fitnessBodyType = toYunmaiActivityLevel(activityLevel) == 1
    }

    fun getWater(bodyFat: Float): Float {
        return ((100.0f - bodyFat) * 0.726f * 100.0f + 0.5f) / 100.0f
    }

    fun getFat(age: Int, weight: Float, resistance: Int): Float {
        // for < 0x1e version devices
        var fat: Float

        var r = (resistance - 100.0f) / 100.0f
        val h = height / 100.0f

        if (r >= 1) {
            r = sqrt(r.toDouble()).toFloat()
        }

        fat = (weight * 1.5f / h / h) + (age * 0.08f)
        if (this.sex == 1) {
            fat -= 10.8f
        }

        fat = (fat - 7.4f) + r

        if (fat < 5.0f || fat > 75.0f) {
            fat = 0.0f
        }

        return fat
    }

    fun getMuscle(bodyFat: Float): Float {
        var muscle: Float
        muscle = (100.0f - bodyFat) * 0.67f

        if (this.fitnessBodyType) {
            muscle = (100.0f - bodyFat) * 0.7f
        }

        muscle = ((muscle * 100.0f) + 0.5f) / 100.0f

        return muscle
    }

    fun getSkeletalMuscle(bodyFat: Float): Float {
        var muscle: Float

        muscle = (100.0f - bodyFat) * 0.53f
        if (this.fitnessBodyType) {
            muscle = (100.0f - bodyFat) * 0.6f
        }

        muscle = ((muscle * 100.0f) + 0.5f) / 100.0f

        return muscle
    }


    fun getBoneMass(muscle: Float, weight: Float): Float {
        var boneMass: Float

        val h = height - 170.0f

        if (sex == 1) {
            boneMass = ((weight * (muscle / 100.0f) * 4.0f) / 7.0f * 0.22f * 0.6f) + (h / 100.0f)
        } else {
            boneMass = ((weight * (muscle / 100.0f) * 4.0f) / 7.0f * 0.34f * 0.45f) + (h / 100.0f)
        }

        boneMass = ((boneMass * 10.0f) + 0.5f) / 10.0f

        return boneMass
    }

    fun getLeanBodyMass(weight: Float, bodyFat: Float): Float {
        return weight * (100.0f - bodyFat) / 100.0f
    }

    fun getVisceralFat(bodyFat: Float, age: Int): Float {
        var f = bodyFat
        val a = if (age < 18 || age > 120) 18 else age

        val vf: Float
        if (!fitnessBodyType) {
            if (sex == 1) {
                if (a < 40) {
                    f -= 21.0f
                } else if (a < 60) {
                    f -= 22.0f
                } else {
                    f -= 24.0f
                }
            } else {
                if (a < 40) {
                    f -= 34.0f
                } else if (a < 60) {
                    f -= 35.0f
                } else {
                    f -= 36.0f
                }
            }

            var d = if (sex == 1) 1.4f else 1.8f
            if (f > 0.0f) {
                d = 1.1f
            }

            vf = (f / d) + 9.5f
            if (vf < 1.0f) {
                return 1.0f
            }
            if (vf > 30.0f) {
                return 30.0f
            }
            return vf
        } else {
            if (bodyFat > 15.0f) {
                vf = (bodyFat - 15.0f) / 1.1f + 12.0f
            } else {
                vf = -1 * (15.0f - bodyFat) / 1.4f + 12.0f
            }
            if (vf < 1.0f) {
                return 1.0f
            }
            if (vf > 9.0f) {
                return 9.0f
            }
            return vf
        }
    }

    companion object {
        @JvmStatic
        fun toYunmaiActivityLevel(activityLevel: ActivityLevel): Int {
            when (activityLevel) {
                ActivityLevel.HEAVY, ActivityLevel.EXTREME -> return 1
                else -> return 0
            }
        }
    }
}
