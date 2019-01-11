package com.health.openscale;

import com.health.openscale.core.bluetooth.BluetoothTrisaBodyAnalyze;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.MainActivity;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import static junit.framework.Assert.assertEquals;

/** Unit tests for {@link com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib}.*/
@RunWith(AndroidJUnit4.class)
public class TrisaBodyAnalyzeLibTest {

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class, false, false);


    public BluetoothTrisaBodyAnalyze trisaBodyAnalyze;

    @Before
    public void initTest() {
        try {
            mActivityTestRule.runOnUiThread(new Runnable() {
                public void run() {
                    trisaBodyAnalyze =new BluetoothTrisaBodyAnalyze(InstrumentationRegistry.getInstrumentation().getTargetContext());
                }
            });
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Test
    public void getBase10FloatTests() {
        double eps = 1e-9;  // margin of error for inexact floating point comparisons

        assertEquals(0.0f, trisaBodyAnalyze.getBase10Float(new byte[]{0, 0, 0, 0}, 0));
        assertEquals(0.0f, trisaBodyAnalyze.getBase10Float(new byte[]{0, 0, 0, -1}, 0));
        assertEquals(76.1f, trisaBodyAnalyze.getBase10Float(new byte[]{-70, 29, 0, -2}, 0), eps);
        assertEquals(1234.5678f, trisaBodyAnalyze.getBase10Float(new byte[]{78, 97, -68, -4}, 0), eps);
        assertEquals(12345678e20f, trisaBodyAnalyze.getBase10Float(new byte[]{78, 97, -68, 20}, 0));
        assertEquals(12345678e-20f, trisaBodyAnalyze.getBase10Float(new byte[]{78, 97, -68, -20}, 0), eps);

        byte[] data = new byte[]{1,2,3,4,5};
        assertEquals(0x030201*1e4f, trisaBodyAnalyze.getBase10Float(data, 0));
        assertEquals(0x040302*1e5f, trisaBodyAnalyze.getBase10Float(data, 1));

        assertThrows(IndexOutOfBoundsException.class, getBase10FloatRunnable(data, -1));
        assertThrows(IndexOutOfBoundsException.class, getBase10FloatRunnable(data, 5));
        assertThrows(IndexOutOfBoundsException.class, getBase10FloatRunnable(new byte[]{1,2,3}, 0));
    }

    @Test
    public void convertJavaTimestampToDeviceTests() {
        assertEquals(275852082, trisaBodyAnalyze.convertJavaTimestampToDevice(1538156082000L));

        // Rounds down.
        assertEquals(275852082, trisaBodyAnalyze.convertJavaTimestampToDevice(1538156082499L));

        // Rounds up.
        assertEquals(275852083, trisaBodyAnalyze.convertJavaTimestampToDevice(1538156082500L));
    }

    @Test
    public void convertDeviceTimestampToJavaTests() {
        assertEquals(1538156082000L, trisaBodyAnalyze.convertDeviceTimestampToJava(275852082));
    }

    @Test
    public void parseScaleMeasurementData_validUserData() {
        long expected_timestamp_seconds = 1539205852L;  // Wed Oct 10 21:10:52 UTC 2018
        byte[] bytes = hexToBytes("9f:b0:1d:00:fe:dc:2f:81:10:00:00:00:ff:0a:15:00:ff:00:09:00");

        ScaleUser user = new ScaleUser();
        user.setGender(Converters.Gender.MALE);
        user.setBirthday(ageToBirthday(36));
        user.setBodyHeight(186);
        user.setMeasureUnit(Converters.MeasureUnit.CM);

        ScaleMeasurement measurement = trisaBodyAnalyze.parseScaleMeasurementData(bytes, user);

        float eps = 1e-3f;
        assertEquals(76.0f, measurement.getWeight(), eps);
        assertEquals(new Date(expected_timestamp_seconds * 1000), measurement.getDateTime());
        assertEquals(14.728368f, measurement.getFat(), eps);
        assertEquals(64.37914f, measurement.getWater(), eps);
        assertEquals(43.36414f, measurement.getMuscle(), eps);
        assertEquals(4.525733f, measurement.getBone());
    }

    @Test
    public void parseScaleMeasurementData_missingUserData() {
        long expected_timestamp_seconds = 1538156082L;  // Fri Sep 28 17:34:42 UTC 2018
        byte[] bytes = hexToBytes("9f:ba:1d:00:fe:32:2b:71:10:00:00:00:ff:8d:14:00:ff:00:09:00");

        ScaleMeasurement measurement = trisaBodyAnalyze.parseScaleMeasurementData(bytes, null);

        assertEquals(76.1f, measurement.getWeight(), 1e-3f);
        assertEquals(new Date(expected_timestamp_seconds * 1000), measurement.getDateTime());
        assertEquals(0f, measurement.getFat());
    }

    @Test
    public void parseScaleMeasurementData_invalidUserData() {
        long expected_timestamp_seconds = 1538156082L;  // Fri Sep 28 17:34:42 UTC 2018
        byte[] bytes = hexToBytes("9f:ba:1d:00:fe:32:2b:71:10:00:00:00:ff:8d:14:00:ff:00:09:00");

        ScaleMeasurement measurement = trisaBodyAnalyze.parseScaleMeasurementData(bytes, new ScaleUser());

        assertEquals(76.1f, measurement.getWeight(), 1e-3f);
        assertEquals(new Date(expected_timestamp_seconds * 1000), measurement.getDateTime());
        assertEquals(0f, measurement.getFat());
    }

    /**
     * Creates a {@link Runnable} that will call getBase10Float(). In Java 8, this can be done more
     * easily with a lambda expression at the call site, but we are using Java 7.
     */
    private Runnable getBase10FloatRunnable(final byte[] data, final int offset) {
        return new Runnable() {
            @Override
            public void run() {
                trisaBodyAnalyze.getBase10Float(data, offset);
            }
        };
    }

    /**
     * Runs the given {@link Runnable} and verifies that it throws an exception of class {@code
     * exceptionClass}. If it does, the exception will be caught and returned. If it does not (i.e.
     * the runnable throws no exception, or throws an exception of a different class), then {@link
     * Assert#fail} is called to abort the test.
     */
    private static <T extends Throwable> T assertThrows(Class<T> exceptionClass, Runnable run) {
        try {
            run.run();
            Assert.fail("Expected an exception to be thrown.");
        } catch (Throwable t) {
            if (exceptionClass.isInstance(t)) {
                return exceptionClass.cast(t);
            }
            Assert.fail("Wrong kind of exception was thrown; expected " + exceptionClass + ", received " + t.getClass());
        }
        return null;  // unreachable, because Assert.fail() throws an exception
    }

    /** Parses a colon-separated hex-encoded string like "aa:bb:cc:dd" into an array of bytes. */
    private static byte[] hexToBytes(String s) {
        String[] parts = s.split(":");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < bytes.length; ++i) {
            if (parts[i].length() != 2) {
                throw new IllegalArgumentException();
            }
            bytes[i] = (byte)Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    private static Date ageToBirthday(int years) {
        int currentYear = GregorianCalendar.getInstance().get(Calendar.YEAR);
        return new GregorianCalendar(currentYear - years, Calendar.JANUARY, 1).getTime();
    }
}
