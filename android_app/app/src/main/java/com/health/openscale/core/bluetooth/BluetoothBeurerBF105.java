/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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

/*
* Based on source-code by weliem/blessed-android
*/

package com.health.openscale.core.bluetooth;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Random;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothBeurerBF105 extends BluetoothStandardWeightProfile {
    private static final UUID SERVICE_BF105_CUSTOM = BluetoothGattUuid.fromShortCode(0xffff);
    private static final UUID SERVICE_BF105_IMG = BluetoothGattUuid.fromShortCode(0xffc0);

    private static final UUID CHARACTERISTIC_SCALE_SETTINGS = BluetoothGattUuid.fromShortCode(0x0000);
    private static final UUID CHARACTERISTIC_USER_LIST = BluetoothGattUuid.fromShortCode(0x0001);
    private static final UUID CHARACTERISTIC_INITIALS = BluetoothGattUuid.fromShortCode(0x0002);
    private static final UUID CHARACTERISTIC_TARGET_WEIGHT = BluetoothGattUuid.fromShortCode(0x0003);
    private static final UUID CHARACTERISTIC_ACTIVITY_LEVEL = BluetoothGattUuid.fromShortCode(0x0004);
    private static final UUID CHARACTERISTIC_REFER_WEIGHT_BF = BluetoothGattUuid.fromShortCode(0x000b);
    private static final UUID CHARACTERISTIC_BT_MODULE = BluetoothGattUuid.fromShortCode(0x0005);
    private static final UUID CHARACTERISTIC_TAKE_MEASUREMENT = BluetoothGattUuid.fromShortCode(0x0006);
    private static final UUID CHARACTERISTIC_TAKE_GUEST_MEASUREMENT = BluetoothGattUuid.fromShortCode(0x0007);
    private static final UUID CHARACTERISTIC_BEURER_I = BluetoothGattUuid.fromShortCode(0x0008);
    private static final UUID CHARACTERISTIC_UPPER_LOWER_BODY = CHARACTERISTIC_BEURER_I;
    private static final UUID CHARACTERISTIC_BEURER_II = BluetoothGattUuid.fromShortCode(0x0009);
    private static final UUID CHARACTERISTIC_BEURER_III = BluetoothGattUuid.fromShortCode(0x000a);
    private static final UUID CHARACTERISTIC_IMG_IDENTIFY = BluetoothGattUuid.fromShortCode(0xffc1);
    private static final UUID CHARACTERISTIC_IMG_BLOCK = BluetoothGattUuid.fromShortCode(0xffc2);

    private ScaleMeasurement scaleMeasurement;
    private ScaleUser scaleUserList[];

    public BluetoothBeurerBF105(Context context) {
        super(context);
        this.scaleMeasurement = new ScaleMeasurement();
        this.scaleUserList = new ScaleUser[10];
    }

    @Override
    public String driverName() {
        return "Beurer BF105";
    }

    @Override
    protected boolean onNextStep(int stepNr) {

        switch (stepNr) {
            case 0:
                // Read manufacturer and model number from the Device Information Service
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION,
                        BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING);
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION,
                        BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING);
                break;
            case 1:
                writeCurrentTime();
                // Turn on notification for Weight Service
                setNotificationOn(BluetoothGattUuid.SERVICE_WEIGHT_SCALE,
                        BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT);
                break;
            case 2:
                // Turn on notification for Body Composition Service
                setNotificationOn(BluetoothGattUuid.SERVICE_BODY_COMPOSITION,
                        BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT);
                break;
            case 3:
                // Turn on notification for User Data Service Cahnge Increment
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA,
                        BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT);
                break;
            case 4:
                // Turn on notification for User Control Point
                setIndicationOn(BluetoothGattUuid.SERVICE_USER_DATA,
                        BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT);
                // read Battery Service
                readBytes(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                break;
            case 5:
                setNotificationOn(SERVICE_BF105_CUSTOM,
                        CHARACTERISTIC_USER_LIST);
                break;
            case 6:
                requestUserList();
                stopMachineState();
                break;
            case 7:
                int userId = this.selectedUser.getId();
                int consentCode = getUserScaleConsent(userId);
                if (consentCode != -1) {
                    registerNewUser = false;
                }
                if (registerNewUser) {
                    Random randomFactory = new Random();
                    consentCode = randomFactory.nextInt(10000);
                    storeUserScaleConsentCode(userId, consentCode);
                    registerUser(consentCode);
                    stopMachineState();
                } else {
                    Timber.d("Set existing user!");
                    setUser(userId);
                }
                break;
            case 8:
                if (registerNewUser) {
                    Timber.d("Set newly registered user!");
                    setUser(this.selectedUser.getId());
                    stopMachineState();
                    break;
                }
            case 9:
                if (registerNewUser) {
                    writeUserDataToScale();
                    break;
                }
            case 10:
                if (registerNewUser) {
                    requestMeasurement();
                    stopMachineState();
                    break;
                }
            default:
                return false;
        }

        return true;
    }

    protected void writeUserDataToScale() {
        super.writeUserDataToScale();
        writeActivityLevel();
        writeInitials();
        writeTargetWeight();
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);

        if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME)) {
            Date currentTime = parser.getDateTime();
            Timber.d(String.format("Received device time: %s", currentTime));
        } else if (characteristic.equals(CHARACTERISTIC_USER_LIST)) {
            Timber.d(String.format("Got user data: <%s>", byteInHex(value)));
            if (parser.getIntValue(FORMAT_UINT8) == 1) {
                for (int i = 0; i < 10; i++) {
                    if (i == 0) {
                        Timber.d("scale user list:");
                    }
                    if (scaleUserList[i] != null) {
                        Timber.d("\n" + (i + 1) + ". " + scaleUserList[i]);
                    }
                }
                resumeMachineState();
                return;
            }
            int index = parser.getIntValue(FORMAT_UINT8);
            String initials = parser.getStringValue();
            int end = 3 > initials.length() ? initials.length() : 3;
            initials = initials.substring(0, end);
            parser.setOffset(5);
            int year = parser.getIntValue(FORMAT_UINT16);
            int month = parser.getIntValue(FORMAT_UINT8);
            int day = parser.getIntValue(FORMAT_UINT8);
            int height = parser.getIntValue(FORMAT_UINT8);
            int gender = parser.getIntValue(FORMAT_UINT8);
            int activityLevel = parser.getIntValue(FORMAT_UINT8);
            GregorianCalendar calendar = new GregorianCalendar(year, month - 1, day);

            ScaleUser scaleUser = new ScaleUser();
            scaleUser.setUserName(initials);
            scaleUser.setBirthday(calendar.getTime());
            scaleUser.setBodyHeight(height);
            scaleUser.setGender(Converters.Gender.fromInt(gender));
            scaleUser.setActivityLevel(Converters.ActivityLevel.fromInt(activityLevel - 1));
            scaleUserList[index - 1] = scaleUser;

        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT)) {
            handleWeightMeasurement(value);
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT)) {
            handleBodyCompositionMeasurement(value);
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL)) {
            int batteryLevel = parser.getIntValue(FORMAT_UINT8);
            Timber.d(String.format("Received battery level %d%%", batteryLevel));
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING)) {
            String manufacturer = parser.getStringValue(0);
            Timber.d(String.format("Received manufacturer: %s", manufacturer));
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING)) {
            String modelNumber = parser.getStringValue(0);
            Timber.d(String.format("Received modelnumber: %s", modelNumber));
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT)) {
            if (value[0] == UDS_CP_RESPONSE) {
                switch (value[1]) {
                    case UDS_CP_REGISTER_NEW_USER:
                        if (value[2] == UDS_CP_RESP_VALUE_SUCCESS) {
                            int userIndex = value[3];
                            int userId = this.selectedUser.getId();
                            Timber.d(String.format("Created user with ID %d and Index %d", userId, userIndex));
                            storeUserScaleIndex(userId, userIndex);
                            resumeMachineState();
                        } else {
                            Timber.e("ERROR: could not register new user");
                        }
                        break;
                    case UDS_CP_CONSENT:
                        if (registerNewUser) {
                            resumeMachineState();
                            break;
                        }
                        if (value[2] == UDS_CP_RESP_VALUE_SUCCESS) {
                            Timber.d("Success user consent");
                        } else if (value[2] == UDS_CP_RESP_USER_NOT_AUTHORIZED && !registerNewUser) {
                            Timber.e("Not authorized");
                        }
                        break;
                    default:
                        Timber.e("Unhandled response");
                        break;
                }
            }
        } else {
            Timber.d(String.format("Got data: <%s>", byteInHex(value)));
        }
    }

    @Override
    protected void handleWeightMeasurement(byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);
        final int flags = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
        boolean isKg = (flags & 0x01) == 0;

        // Determine the right weight multiplier
        float weightMultiplier = isKg ? 0.005f : 0.01f;

        // Get weight
        float weightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * weightMultiplier;
        scaleMeasurement.setWeight(weightValue);

        Date timestamp = parser.getDateTime();
        scaleMeasurement.setDateTime(timestamp);

        int userIndex = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
        Timber.d(String.format("scale User index: %d", userIndex));
        int userId = getUserIdFromScaleIndex(userIndex);
        Timber.d(String.format("user ID: %d", userId));
        setUser(userId);
        scaleMeasurement.setUserId(userId);

        float BMI = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        float heightInMeters = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.001f;

        if (registerNewUser) {
            Timber.d(String.format("Setting initial weight for user %s to: %s and registerNewUser to false", userId,
                    weightValue));
            if (selectedUser.getId() == userId) {
                this.selectedUser.setInitialWeight(weightValue);
            } else {
                OpenScale.getInstance().getScaleUser(userId).setInitialWeight(weightValue);
            }
            registerNewUser = false;
            resumeMachineState();
        }

        Timber.d(String.format("Got weight: %s", weightValue));
    }

    @Override
    protected void handleBodyCompositionMeasurement(byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);
        final int flags = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
        boolean isKg = (flags & 0x0001) == 0;
        float massMultiplier = (float) (isKg ? 0.005 : 0.01);

        float bodyFatPercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        scaleMeasurement.setFat(bodyFatPercentage);

        int bmrInJoules = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
        int bmrInKcal = Math.round(((bmrInJoules / 4.1868f) * 10.0f) / 10.0f);
        Timber.d(String.format("bmrInJoules: %d", bmrInJoules));
        Timber.d(String.format("bmrInKcal: %d", bmrInKcal));

        // Read musclePercentage
        float musclePercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        scaleMeasurement.setMuscle(musclePercentage);
        Timber.d(String.format("muscle percentage: %f", musclePercentage));

        float boneMass = Math.round(parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.0199 + 100) * 0.01f;
        Timber.d(String.format("boneMass: %f", boneMass));
        scaleMeasurement.setBone(boneMass);

        // Read bodyWaterMass
        float bodyWaterMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
        scaleMeasurement.setWater(bodyWaterMass);
        Timber.d(String.format("body water mass: %f", bodyWaterMass));

        // Read impedance
        float impedance = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        Timber.d(String.format("impedance: %f", impedance));

        Timber.d(String.format("Got body composition: %s", byteInHex(value)));
        Timber.d(String.format("Adding Scale Measurement now."));
        addScaleMeasurement(scaleMeasurement);
    }

    private synchronized void requestUserList() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(1, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_USER_LIST,
                parser.getValue());
    }

    @Override
    protected void writeActivityLevel() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int activityLevel = this.selectedUser.getActivityLevel().toInt() + 1;
        Timber.d(String.format("activityLevel: %d", activityLevel));
        parser.setIntValue(activityLevel, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_ACTIVITY_LEVEL,
                parser.getValue());
    }

    private void writeTargetWeight() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int targetWeight = (int) this.selectedUser.getGoalWeight();
        parser.setIntValue(targetWeight, FORMAT_UINT16);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_TARGET_WEIGHT,
                parser.getValue());
    }

    private void writeInitials() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        String initials = getInitials(this.selectedUser.getUserName());
        Timber.d("Initials: " + initials);
        parser.setString(initials);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_INITIALS,
                parser.getValue());
    }

    private String getInitials(String fullName) {
        if (fullName == null || fullName.isEmpty() || fullName.chars().allMatch(Character::isWhitespace)) {
            return getDefaultInitials();
        }
        return buildInitialsStringFrom(fullName).toUpperCase();
    }

    private String getDefaultInitials() {
        int userId = this.selectedUser.getId();
        int userIndex = getUserScaleIndex(userId);
        return "P" + userIndex + " ";
    }

    private String buildInitialsStringFrom(String fullName) {
        String[] name = fullName.split(" ");
        String initials = "";
        for (int i = 0; i < 3; i++) {
            if (i < name.length && name[i] != "") {
                initials += name[i].charAt(0);
            } else {
                initials += " ";
            }
        }
        return initials;
    }

    @Override
    protected synchronized void requestMeasurement() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_TAKE_MEASUREMENT,
                parser.getValue());
    }
}
