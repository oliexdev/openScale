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
import com.health.openscale.core.data.WeightUnit
import java.util.Calendar
import java.util.Date

data class ScaleUser (
    var id: Int = 0,
    var userName: String = "",
    var birthday: Date = Date(),
    var bodyHeight: Float = -1f, // always in cm
    var gender: GenderType = GenderType.MALE,
    var initialWeight: Float = 0f, // always in kg
    var goalWeight: Float = 0f, // always in kg
    var scaleUnit: WeightUnit = WeightUnit.KG,
    var activityLevel: ActivityLevel = ActivityLevel.SEDENTARY
){
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
}
