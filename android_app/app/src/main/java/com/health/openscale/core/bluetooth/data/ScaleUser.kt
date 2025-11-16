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
package com.health.openscale.core.bluetooth.data

import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasureUnit
import com.health.openscale.core.data.WeightUnit
import java.util.Calendar
import java.util.Date

class ScaleUser {
    @JvmField
    var id: Int = 0

    @JvmField
    var userName: String = ""
    @JvmField
    var birthday: Date

    @JvmField
    var bodyHeight: Float // always in cm

    @JvmField
    var gender: GenderType

    @JvmField
    var initialWeight: Float = 0f // always in kg

    @JvmField
    var goalWeight: Float = 0f // always in kg

    @JvmField
    var scaleUnit: WeightUnit

    @JvmField
    var activityLevel: ActivityLevel

    init {
        birthday = Date()
        bodyHeight = -1f
        gender = GenderType.MALE
        scaleUnit = WeightUnit.KG
        activityLevel = ActivityLevel.SEDENTARY
    }

    fun getAge(todayDate: Date?): Int {
        val calToday = Calendar.getInstance()
        if (todayDate != null) {
            calToday.setTime(todayDate)
        }

        val calBirthday = Calendar.getInstance()
        calBirthday.setTime(birthday)

        return yearsBetween(calBirthday, calToday)
    }

    val age: Int
        get() = getAge(null)

    private fun yearsBetween(start: Calendar, end: Calendar): Int {
        var years = end.get(Calendar.YEAR) - start.get(Calendar.YEAR)

        val startMonth = start.get(Calendar.MONTH)
        val endMonth = end.get(Calendar.MONTH)
        if (endMonth < startMonth
            || (endMonth == startMonth
                    && end.get(Calendar.DAY_OF_MONTH) < start.get(Calendar.DAY_OF_MONTH))
        ) {
            years -= 1
        }
        return years
    }

    override fun toString() : String {
        return "ScaleUser(id=$id, userName=$userName, birthday=$birthday, bodyHeight=$bodyHeight, gender=$gender, initialWeight=$initialWeight, goalWeight=$goalWeight, scaleUnit=$scaleUnit, activityLevel=$activityLevel)"
    }
}
