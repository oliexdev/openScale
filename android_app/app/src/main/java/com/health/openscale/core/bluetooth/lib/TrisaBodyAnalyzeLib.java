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

import java.time.Clock;
import java.util.Date;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Class with static helper methods. This is a separate class for testing purposes.
 *
 * @see com.health.openscale.core.bluetooth.BluetoothTrisaBodyAnalyze
 */
public class TrisaBodyAnalyzeLib {

    // Timestamp of 2010-01-01 00:00:00 UTC (or local time?)
    private static final long TIMESTAMP_OFFSET_SECONDS = 1262304000L;

    /**
     * Converts 4 little-endian bytes to a 32-bit integer, starting from {@code offset}.
     *
     * @throws IndexOutOfBoundsException if {@code offset < 0} or {@code offset + 4> data.length}
     */
    public static int getInt32(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }

    /** Converts 4 bytes to a floating point number, starting from  {@code offset}.
     *
     * <p>The first three little-endian bytes form the 24-bit mantissa. The last byte contains the
     * signed exponent, applied in base 10.
     *
     * @throws IndexOutOfBoundsException if {@code offset < 0} or {@code offset + 4> data.length}
     */
    public static double getBase10Float(byte[] data, int offset) {
        int mantissa = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16);
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
    public static ScaleMeasurement parseScaleMeasurementData(byte[] data) {
        // Byte 0 contains info.
        // Byte 1-4 contains weight.
        // Byte 5-8 contains timestamp, if bit 0 in info byte is set.
        // Check that we have at least weight & timestamp, which is the minimum information that
        // ScaleMeasurement needs.
        if (data.length < 9 || (data[0] & 1) == 0) {
            return null;
        }

        double weight = getBase10Float(data, 1);
        int deviceTimestamp = getInt32(data, 5);

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setDateTime(new Date(convertDeviceTimestampToJava(deviceTimestamp)));
        measurement.setWeight((float)weight);
        // TODO: calculate body composition (if possible) and set those fields too
        return measurement;
    }

    private TrisaBodyAnalyzeLib() {}
}
