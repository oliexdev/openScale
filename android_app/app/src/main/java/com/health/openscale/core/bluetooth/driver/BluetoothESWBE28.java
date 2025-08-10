package com.health.openscale.core.bluetooth.driver;

import static com.health.openscale.core.utils.Converters.toCentimeter;

import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.time.LocalDateTime;

import timber.log.Timber;

public class BluetoothESWBE28 extends BluetoothCommunication {

    private static final UUID SERV_BODY_COMP    = BluetoothGattUuid.fromShortCode(0x181b);
    private static final UUID SERV_USER_DATA    = BluetoothGattUuid.fromShortCode(0x181c);
    private static final UUID SERV_WEIGHT_SCALE = BluetoothGattUuid.fromShortCode(0x181d);
    private static final UUID SERV_CUR_TIME     = BluetoothGattUuid.fromShortCode(0x1805);

    // Custom characteristic nr. 0 (Service: body comp)
    // Written data was always the same on all my tests
    private static final UUID CHAR_CUSTOM0_NOTIFY   = BluetoothGattUuid.fromShortCode(0xffe1);
    private static final UUID CHAR_CUSTOM0          = BluetoothGattUuid.fromShortCode(0xffe2);
    private static final byte[] CHAR_CUSTOM0_MAGIC0 = new byte[]{(byte) 0x10, (byte) 0x01, (byte) 0x00, (byte) 0x11};
    private static final byte[] CHAR_CUSTOM0_MAGIC1 = new byte[]{(byte) 0x03, (byte) 0x00, (byte) 0x01, (byte) 0x04};

    // Custom characteristic nr. 1 (Service: user data)
    // Written data was always the same on all my tests
    private static final UUID CHAR_CUSTOM1_NOTIFY   = BluetoothGattUuid.fromShortCode(0x2a9f);
    private static final UUID CHAR_CUSTOM1          = BluetoothGattUuid.fromShortCode(0x2a9f);
    private static final byte[] CHAR_CUSTOM1_MAGIC  = new byte[]{(byte) 0x02, (byte) 0xaa, (byte) 0x0f, (byte) 0x27};

    // Service: body comp
    private static final UUID CHAR_BODY_COMP_FEAT = BluetoothGattUuid.fromShortCode(0x2a9b);
    private static final UUID CHAR_BODY_COMP_MEAS = BluetoothGattUuid.fromShortCode(0x2a9c);

    // Service: user data
    private static final UUID CHAR_GENDER = BluetoothGattUuid.fromShortCode(0x2a8c); // 0x00 male, 0x01 female
    private static final UUID CHAR_HEIGHT = BluetoothGattUuid.fromShortCode(0x2a8e); // in cm. 177cm = {0xb1 0x00}
    private static final UUID CHAR_BIRTH  = BluetoothGattUuid.fromShortCode(0x2a85); // 2 bytes year, 1 byte month, 1 byte day of year (1-366)
    private static final UUID CHAR_AGE    = BluetoothGattUuid.fromShortCode(0x2a80); // 1 byte
    private static final UUID CHAR_ATHLETE= BluetoothGattUuid.fromShortCode(0x2aff); // {0x0d 0x00} = Athlete; {0x03 0x00} = Not athlete

    // Service: weight scale
    private static final UUID CHAR_WEIGHT = BluetoothGattUuid.fromShortCode(0x2a9d); // {0x0d 0x00} = Athlete; {0x03 0x00} = Not athlete

    // Curr time
    private static final UUID CHAR_CUR_TIME = BluetoothGattUuid.fromShortCode(0x2a2b);
    private static final UUID CHAR_ICCEDK   = BluetoothGattUuid.fromShortCode(0xfff1);

    /*
    Despite notified data is discarded, notify must be set on
     - 0x2a2b (CHAR_CUR_TIME)
     - 0x2a9f (CHAR_CUSTOM1_NOTIFY)
     - 0xfff1 (CHAR_ICCEDK)
     - 0xffe1 (CHAR_CUSTOM0_NOTIFY)
     */

    private ScaleUser user;

    public BluetoothESWBE28(Context context, String deviceName) {
        super(context, deviceName);
    }

    @Override
    public String driverName() {
        // Not sure of the driver name. Tested with ES-WBE28
        return "RENPHO ES-WBE28";
    }

    public static String driverId() {
        return "eswbe28";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        Timber.i("onNextStep(%d)", stepNr);

        switch (stepNr) {
            case 0:
                user = OpenScale.getInstance().getSelectedScaleUser();
                setNotificationOn(SERV_CUR_TIME, CHAR_CUR_TIME);
                break;
            case 1:
                setIndicationOn(SERV_USER_DATA, CHAR_CUSTOM1_NOTIFY);
                break;
            case 2:
                setNotificationOn(SERV_CUR_TIME, CHAR_ICCEDK);
                break;
            case 3:
                setNotificationOn(SERV_BODY_COMP, CHAR_CUSTOM0_NOTIFY);
                break;
            case 4:
                LocalDateTime now = LocalDateTime.now();

                byte[] currtime = new byte[]{
                        (byte) (now.getYear() & 0xff),    // Year LSB
                        (byte) (now.getYear() >> 8),      // Year MSB
                        (byte) (now.getMonthValue()),
                        (byte) (now.getDayOfMonth()),
                        (byte) (now.getHour()),
                        (byte) (now.getMinute()),
                        (byte) (now.getSecond()),
                        (byte) (now.getDayOfWeek().getValue()), // 1 = Monday, 7 = Sunday
                        (byte) 0,                               // Fraction of seconds, unused
                        (byte) 0                                // Reason of update: not specified

                };

                writeBytes(SERV_CUR_TIME, CHAR_CUR_TIME, currtime);
                break;
            case 5:
                stopMachineState();
                writeBytes(SERV_BODY_COMP, CHAR_CUSTOM0, CHAR_CUSTOM0_MAGIC0);
                break;
            case 6:
                stopMachineState();
                writeBytes(SERV_BODY_COMP, CHAR_CUSTOM0, CHAR_CUSTOM0_MAGIC1);
                break;
            case 7:
                stopMachineState();
                writeBytes(SERV_USER_DATA, CHAR_CUSTOM1, CHAR_CUSTOM1_MAGIC);
                break;
            case 8:
                byte[] gender = new byte[]{(byte) (user.getGender().isMale() ? 0x00 : 0x01)};
                writeBytes(SERV_USER_DATA, CHAR_GENDER, gender);
                break;
            case 9:
                int height = (int) toCentimeter(user.getBodyHeight(), user.getMeasureUnit());
                byte[] height_data = new byte[]{
                        (byte) (height & 0xff) ,    // Height, cm, LSB
                        (byte) (height >> 8)        // Height, cm, MSB
                };
                writeBytes(SERV_USER_DATA, CHAR_HEIGHT, height_data);
                break;
            case 10:
                Date dob_d = user.getBirthday();

                // Needed to calculate DAY_OF_YEAR.
                // Moreover, Date::getXXX() is deprecated and replaced by Calendar::get
                Calendar dob = Calendar.getInstance();
                dob.setTime(dob_d);

                byte[] dob_data = new byte[]{
                        (byte) (dob.get(Calendar.YEAR) & 0xff),  // Year LSB
                        (byte) (dob.get(Calendar.YEAR) >> 8),    // Year MSB

                        // Calendar.JANUARY is zero, but scale needs Jan = 1, Dec = 12
                        (byte) (dob.get(Calendar.MONTH) - Calendar.JANUARY + 1),

                        // GATT spec says DAY_OF_MONTH (1-31) but Renpho app sends some strange values
                        (byte) dob.get(Calendar.DAY_OF_MONTH)
                };
                writeBytes(SERV_USER_DATA, CHAR_BIRTH, dob_data);
                break;
            case 11:
                byte[] age = new byte[]{(byte) user.getAge()};
                writeBytes(SERV_USER_DATA, CHAR_AGE, age);
                break;
            case 12:
                byte[] athl = new byte[]{(byte) 0x03, (byte)0x00}; // Non athlete

                switch (user.getActivityLevel()) {
                    case HEAVY:
                    case EXTREME:
                        athl[0] = (byte) 0x0d;
                        break;
                }

                writeBytes(SERV_USER_DATA, CHAR_ATHLETE, athl);
                break;
            case 13:
                readBytes(SERV_BODY_COMP, CHAR_BODY_COMP_FEAT);
                break;
            case 14:
                setNotificationOn(SERV_WEIGHT_SCALE, CHAR_WEIGHT);
                break;
            case 15:
                setIndicationOn(SERV_BODY_COMP, CHAR_BODY_COMP_MEAS);
                break;
            case 16:
                stopMachineState();
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        Timber.d("Received notification on UUID = %s", characteristic.toString());
        for(int i = 0; i < value.length; i++) {
            Timber.d("Byte %d = 0x%02x", i, value[i]);
        }

        switch (getStepNr()) {
            case 6:
            case 7:
            case 8:
                resumeMachineState();
                break;
            case 17:
                if (characteristic.equals(CHAR_WEIGHT)) {
                    if (value[0] == 0x2e) {

                        float weight_kg = (Byte.toUnsignedInt(value[2])*256 + Byte.toUnsignedInt(value[1])) / 20.0f;

                        Timber.d("Weight = 0x%02x, 0x%02x = %f",value[1], value[2], weight_kg);
                        saveMeasurement(weight_kg);
                        resumeMachineState();

                    }
                }
                if (characteristic.equals(CHAR_BODY_COMP_MEAS)) {
                    // TODO
                    /*
                    Not yet decoded (it does not follow GATT Body Comp standard fields).
                    What I've found is:
                    byte      0 : Unknown (always zero?)
                    byte   1- 3 : Unknown
                    byte      4 : Unknown (always zero?)
                    byte      5 : "metabolic_age" in years
                    byte      6 : Unknown (always zero?)
                    byte      7 : "protein" in units of 0.1%
                    byte   8- 9 : "subcutaneous_fat" in units of 0.1%
                    byte     10 : "visceral_fat_grade" in unknown/absolute units
                    byte     11 : Unknown (always zero?)
                    byte     12 : int part of "lean_body_mass" in kg. Dunno where decimal digit is encoded.
                    bytes 13-16 : Unknown (some flags/counters?). These fields change even between identical measurements. byte 16 = (byte 14) + 2.
                    bytes 17-18 : "body_water" in units of 0.1%
                     */
                }
                break;
        }
    }

    /**
     * Save a measurement from the scale to openScale.
     *
     * @param weightKg   The weight, in kilograms
     */
    private void saveMeasurement(float weightKg) {

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight(weightKg);

        addScaleMeasurement(btScaleMeasurement);
    }
}
