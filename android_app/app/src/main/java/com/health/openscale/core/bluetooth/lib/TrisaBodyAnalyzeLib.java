/*  Copyright (C) 2018  Maks Verver <maks@verver.ch>
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

import android.support.annotation.Nullable;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Date;

/**
 * Class with static helper methods. This is a separate class for testing purposes.
 *
 * @see com.health.openscale.core.bluetooth.BluetoothTrisaBodyAnalyze
 */
public class TrisaBodyAnalyzeLib {

    // Timestamp of 2010-01-01 00:00:00 UTC (or local time?)
    private static final long TIMESTAMP_OFFSET_SECONDS = 1262304000L;

    /** Converts 4 bytes to a floating point number, starting from  {@code offset}.
     *
     * <p>The first three little-endian bytes form the 24-bit mantissa. The last byte contains the
     * signed exponent, applied in base 10.
     *
     * @throws IndexOutOfBoundsException if {@code offset < 0} or {@code offset + 4> data.length}
     */
    public static double getBase10Float(byte[] data, int offset) {
        int mantissa = Converters.fromUnsignedInt24Le(data, offset);
        int exponent = data[offset + 3];  // note: byte is signed.
        return mantissa * Math.pow(10, exponent);
    }

    public static int convertJavaTimestampToDevice(long javaTimestampMillis) {
        return (int)((javaTimestampMillis + 500)/1000 - TIMESTAMP_OFFSET_SECONDS);
    }

    public static long convertDeviceTimestampToJava(int deviceTimestampSeconds) {
        return 1000 * (TIMESTAMP_OFFSET_SECONDS + (long)deviceTimestampSeconds);
    }

    @Nullable
    public static ScaleMeasurement parseScaleMeasurementData(byte[] data, @Nullable ScaleUser user) {
        // data contains:
        //
        //   1 byte: info about presence of other fields:
        //           bit 0: timestamp
        //           bit 1: resistance1
        //           bit 2: resistance2
        //           (other bits aren't used here)
        //   4 bytes: weight
        //   4 bytes: timestamp (if info bit 0 is set)
        //   4 bytes: resistance1 (if info bit 1 is set)
        //   4 bytes: resistance2 (if info bit 2 is set)
        //   (following fields aren't used here)

        // Check that we have at least weight & timestamp, which is the minimum information that
        // ScaleMeasurement needs.
        if (data.length < 9) {
            return null;  // data is too short
        }
        byte infoByte = data[0];
        boolean hasTimestamp = (infoByte & 1) == 1;
        boolean hasResistance1 = (infoByte & 2) == 2;
        boolean hasResistance2 = (infoByte & 4) == 4;
        if (!hasTimestamp) {
            return null;
        }
        double weightKg = getBase10Float(data, 1);
        int deviceTimestamp = Converters.fromSignedInt32Le(data, 5);

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setDateTime(new Date(convertDeviceTimestampToJava(deviceTimestamp)));
        measurement.setWeight((float) weightKg);

        // Only resistance 2 is used; resistance 1 is 0, even if it is present.
        int resistance2Offset = 9 + (hasResistance1 ? 4 : 0);
        if (hasResistance2 && resistance2Offset + 4 <= data.length && isValidUser(user)) {
            // Calculate body composition statistics from measured weight & resistance, combined
            // with age, height and sex from the user profile. The accuracy of the resulting figures
            // is questionable, but it's better than nothing. Even if the absolute numbers aren't
            // very meaningful, it might still be useful to track changes over time.
            double resistance2 = getBase10Float(data, resistance2Offset);
            int ageYears = user.getAge();
            double heightCm = Converters.toCentimeter(user.getBodyHeight(), user.getMeasureUnit());
            boolean isMale = user.getGender().isMale();
            double impedance = resistance2 < 410 ? 3.0 : 0.3 * (resistance2 - 400);
            double bmi = weightKg * 1e4 / (heightCm * heightCm);
            double fat = isMale
                    ? bmi * (1.479 + 4.4e-4 * impedance) + 0.1 * ageYears - 21.764
                    : bmi * (1.506 + 3.908e-4 * impedance) + 0.1 * ageYears - 12.834;
            double water = isMale
                    ? 87.51 + (-1.162 * bmi - 0.00813 * impedance + 0.07594 * ageYears)
                    : 77.721 + (-1.148 * bmi - 0.00573 * impedance + 0.06448 * ageYears);
            double muscle = isMale
                    ? 74.627 + (-0.811 * bmi - 0.00565 * impedance - 0.367 * ageYears)
                    : 57.0 + (-0.694 * bmi - 0.00344 * impedance - 0.255 * ageYears);
            double bone = isMale
                    ? 7.829 + (-0.0855 * bmi - 5.92e-4 * impedance - 0.0389 * ageYears)
                    : 7.98 + (-0.0973 * bmi - 4.84e-4 * impedance - 0.036 * ageYears);
            measurement.setFat((float) fat);
            measurement.setWater((float) water);
            measurement.setMuscle((float) muscle);
            measurement.setBone((float) bone);
        }
        return measurement;
    }

    private static boolean isValidUser(@Nullable ScaleUser user) {
        return user != null && user.getAge() > 0 && user.getBodyHeight() > 0;
    }

    private TrisaBodyAnalyzeLib() {}
}
