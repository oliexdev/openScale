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

package com.health.openscale.core.utils;

import android.arch.persistence.room.TypeConverter;

import java.util.Date;

public class Converters {
    public enum WeightUnit {
        KG, LB, ST;

        public String toString() {
            switch (this) {
                case LB:
                    return "lb";
                case ST:
                    return "st";
            }
            return "kg";
        }

        public static WeightUnit fromInt(int unit) {
            switch (unit) {
                case 1:
                    return LB;
                case 2:
                    return ST;
            }
            return KG;
        }

        public int toInt() {
            switch (this) {
                case LB:
                    return 1;
                case ST:
                    return 2;
            }
            return 0;
        }
    }

    public enum Gender {
        MALE, FEMALE;

        public boolean isMale() {
            return this == MALE;
        }

        public static Gender fromInt(int gender) {
            return gender == 0 ? MALE : FEMALE;
        }

        public int toInt() {
            return this == MALE ? 0 : 1;
        }
    }

    private static float KG_LB = 2.20462f;
    private static float KG_ST = 0.157473f;

    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public static Long dateToTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }

    @TypeConverter
    public static WeightUnit fromWeightUnitInt(int unit) {
        return WeightUnit.fromInt(unit);
    }

    @TypeConverter
    public static int toWeightUnitInt(WeightUnit unit) {
        return unit.toInt();
    }

    @TypeConverter
    public static Gender fromGenderInt(int gender) {
        return Gender.fromInt(gender);
    }

    @TypeConverter
    public static int toGenderInt(Gender gender) {
        return gender.toInt();
    }

    public static float toKilogram(float value, WeightUnit unit) {
        switch (unit) {
            case LB:
                return value / KG_LB;
            case ST:
                return value / KG_ST;
        }
        return value;
    }

    public static float fromKilogram(float kg, WeightUnit unit) {
        switch (unit) {
            case LB:
                return kg * KG_LB;
            case ST:
                return kg * KG_ST;
        }
        return kg;
    }
}
