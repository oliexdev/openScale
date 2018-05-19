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
    public enum MeasureUnit {
        CM, INCH;

        public String toString() {
            switch (this) {
                case CM:
                    return "cm";
                case INCH:
                    return "in";
            }

            return "";
        }

        public static MeasureUnit fromInt(int unit) {
            switch (unit) {
                case 0:
                    return CM;
                case 1:
                    return INCH;
            }
            return CM;
        }

        public int toInt() {
            switch (this) {
                case CM:
                    return 0;
                case INCH:
                    return 1;
            }

            return 0;
        }
    }

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

    public enum ActivityLevel {
        SEDENTARY, MILD, MODERATE, HEAVY, EXTREME;

        public static ActivityLevel fromInt(int unit) {
            switch (unit) {
                case 0:
                    return SEDENTARY;
                case 1:
                    return MILD;
                case 2:
                    return MODERATE;
                case 3:
                    return HEAVY;
                case 4:
                    return EXTREME;
            }

            return SEDENTARY;
        }

        public int toInt() {
            switch (this) {
                case SEDENTARY:
                    return 0;
                case MILD:
                    return 1;
                case MODERATE:
                    return 2;
                case HEAVY:
                    return 3;
                case EXTREME:
                    return 4;
            }

            return 0;
        }
    }

    private static final float KG_LB = 2.20462f;
    private static final float KG_ST = 0.157473f;
    private static final float CM_IN = 0.393701f;

    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public static Long dateToTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }

    @TypeConverter
    public static MeasureUnit fromMeasureUnitInt(int unit) {
        return MeasureUnit.fromInt(unit);
    }

    @TypeConverter
    public static int toMeasureUnitInt(MeasureUnit unit) {
        return unit.toInt();
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

    @TypeConverter
    public static ActivityLevel fromActivityLevelInt(int level) {
        return ActivityLevel.fromInt(level);
    }

    @TypeConverter
    public static int toActivityLevelInt(ActivityLevel level) {
        return level.toInt();
    }

    public static float toCentimeter(float value, MeasureUnit unit) {
        switch (unit) {
            case INCH:
                return value / CM_IN;
        }
        return value;
    }

    public static float fromCentimeter(float cm, MeasureUnit unit) {
        switch (unit) {
            case INCH:
                return cm * CM_IN;
        }
        return cm;
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

    public static int fromUnsignedInt16Le(byte[] data, int offset) {
        int value = (data[offset + 1] & 0xFF) << 8;
        value += data[offset] & 0xFF;
        return value;
    }

    public static int fromUnsignedInt16Be(byte[] data, int offset) {
        int value = (data[offset] & 0xFF) << 8;
        value += data[offset + 1] & 0xFF;
        return value;
    }

    public static byte[] toUnsignedInt16Be(int value) {
        byte[] data = new byte[2];
        data[0] = (byte) ((value >> 8) & 0xFF);
        data[1] = (byte) (value & 0xFF);
        return data;
    }

    public static int fromUnsignedInt24Le(byte[] data, int offset) {
        int value = (data[offset + 2] & 0xFF) << 16;
        value += (data[offset + 1] & 0xFF) << 8;
        value += data[offset] & 0xFF;
        return value;
    }

    public static long fromUnsignedInt32Le(byte[] data, int offset) {
        long value = (long) (data[offset + 3] & 0xFF) << 24;
        value += (data[offset + 2] & 0xFF) << 16;
        value += (data[offset + 1] & 0xFF) << 8;
        value += data[offset] & 0xFF;
        return value;
    }

    public static long fromUnsignedInt32Be(byte[] data, int offset) {
        long value = (long) (data[offset] & 0xFF) << 24;
        value += (data[offset + 1] & 0xFF) << 16;
        value += (data[offset + 2] & 0xFF) << 8;
        value += data[offset + 3] & 0xFF;
        return value;
    }

    public static byte[] toUnsignedInt32Le(long value) {
        byte[] data = new byte[4];
        data[3] = (byte) ((value >> 24) & 0xFF);
        data[2] = (byte) ((value >> 16) & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        data[0] = (byte) (value & 0xFF);
        return data;
    }

    public static byte[] toUnsignedInt32Be(long value) {
        byte[] data = new byte[4];
        data[0] = (byte) ((value >> 24) & 0xFF);
        data[1] = (byte) ((value >> 16) & 0xFF);
        data[2] = (byte) ((value >> 8) & 0xFF);
        data[3] = (byte) (value & 0xFF);
        return data;
    }
}
