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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.welie.blessed.BluetoothBytesParser;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.util.Random;

import timber.log.Timber;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

public class BluetoothStandardWeightProfile extends BluetoothCommunication {
    protected int CURRENT_USER_CONSENT = 3289;

    // UDS control point codes
    protected static final byte UDS_CP_REGISTER_NEW_USER              = 0x01;
    protected static final byte UDS_CP_CONSENT                        = 0x02;
    protected static final byte UDS_CP_DELETE_USER_DATA               = 0x03;
    protected static final byte UDS_CP_LIST_ALL_USERS                 = 0x04;
    protected static final byte UDS_CP_DELETE_USERS                   = 0x05;
    protected static final byte UDS_CP_RESPONSE                       = 0x20;

    // UDS response codes
    protected static final byte UDS_CP_RESP_VALUE_SUCCESS             = 0x01;
    protected static final byte UDS_CP_RESP_OP_CODE_NOT_SUPPORTED     = 0x02;
    protected static final byte UDS_CP_RESP_INVALID_PARAMETER         = 0x03;
    protected static final byte UDS_CP_RESP_OPERATION_FAILED          = 0x04;
    protected static final byte UDS_CP_RESP_USER_NOT_AUTHORIZED       = 0x05;

    SharedPreferences prefs;
    protected boolean registerNewUser;
    ScaleUser selectedUser;

    public BluetoothStandardWeightProfile(Context context) {
        super(context);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        this.registerNewUser = false;
    }

    @Override
    public String driverName() {
        return "Bluetooth Standard Weight Profile";
    }

    @Override
    protected boolean onNextStep(int stepNr) {

        switch (stepNr) {
            case 0:
                // Read manufacturer and model number from the Device Information Service
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION, BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING);
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION, BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING);
                break;
            case 1:
                writeCurrentTime();
                // Turn on notification for Weight Service
                setNotificationOn(BluetoothGattUuid.SERVICE_WEIGHT_SCALE, BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT);
                break;
            case 2:
                // Turn on notification for Body Composition Service
                setNotificationOn(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT);
                break;
            case 3:
                // Turn on notification for User Data Service Change Increment
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT);
                break;
            case 4:
                // Turn on notification for User Control Point
                setIndicationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT);
                // read Battery Service
                readBytes(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                break;
            case 5:
                // Turn on notifications for Battery Service
                setNotificationOn(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                break;
            case 6:
                int userId = this.selectedUser.getId();
                int consentCode = getUserScaleConsent(userId);
                int userIndex = getUserScaleIndex(userId);
                if (consentCode == -1 || userIndex == -1) {
                    registerNewUser = true;
                }
                if (registerNewUser) {
                    Random randomFactory = new Random();
                    consentCode = randomFactory.nextInt(10000);
                    storeUserScaleConsentCode(userId, consentCode);
                    registerUser(consentCode);
                    stopMachineState();
                }
                break;
            case 7:
                Timber.d("Select user on scale!");
                setUser(this.selectedUser.getId());
                stopMachineState();
                break;
            case 8:
                if (registerNewUser) {
                    writeUserDataToScale();
                }
                break;
            default:
                return false;
        }

        return true;
    }

    protected void writeUserDataToScale() {
        writeBirthday();
        writeGender();
        writeHeight();
        setChangeIncrement();
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);

        if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME)) {
            Date currentTime = parser.getDateTime();
            Timber.d(String.format("Received device time: %s", currentTime));
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT)) {
            handleWeightMeasurement(value);
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT)) {
            handleBodyCompositionMeasurement(value);
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL)) {
            int batteryLevel = parser.getIntValue(FORMAT_UINT8);
            Timber.d(String.format("Received battery level %d%%", batteryLevel));
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING)) {
            String manufacturer = parser.getStringValue(0);
            Timber.d(String.format("Received manufacturer: %s", manufacturer));
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING)) {
            String modelNumber = parser.getStringValue(0);
            Timber.d(String.format("Received modelnumber: %s", modelNumber));
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT)) {
            if(value[0]==UDS_CP_RESPONSE) {
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
                        } else if (value[2] == UDS_CP_RESP_USER_NOT_AUTHORIZED) {
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

    protected void handleWeightMeasurement(byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);
        final int flags = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
        boolean isKg = (flags & 0x01) == 0;
        final boolean timestampPresent = (flags & 0x02) > 0;
        final boolean userIDPresent = (flags & 0x04) > 0;
        final boolean bmiAndHeightPresent = (flags & 0x08) > 0;

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        // Determine the right weight multiplier
        float weightMultiplier = isKg ? 0.005f : 0.01f;

        // Get weight
        float weightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * weightMultiplier;
        scaleMeasurement.setWeight(weightValue);

        if(timestampPresent) {
            Date timestamp = parser.getDateTime();
            scaleMeasurement.setDateTime(timestamp);
        }

        if(userIDPresent) {
            int scaleUserIndex = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
            int userID = getUserIdFromScaleIndex(scaleUserIndex);
            Timber.d(String.format("User id: %d, scale user index: %d", userID, scaleUserIndex));

            if (registerNewUser) {
                Timber.d(String.format("Setting initial weight for user %s to: %s and registerNewUser to false", userID,
                        weightValue));
                if (selectedUser.getId() == userID) {
                    this.selectedUser.setInitialWeight(weightValue);
                    OpenScale.getInstance().updateScaleUser(selectedUser);
                }
                registerNewUser = false;
            }
        }

        if(bmiAndHeightPresent) {
            float BMI = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
            float heightInMeters = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.001f;
        }

        Timber.d(String.format("Got weight: %s", weightValue));
        addScaleMeasurement(scaleMeasurement);
    }

    protected void handleBodyCompositionMeasurement(byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);
        final int flags = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
        boolean isKg = (flags & 0x0001) == 0;
        float massMultiplier = (float) (isKg ? 0.005 : 0.01);
        boolean timestampPresent = (flags & 0x0002) > 0;
        boolean userIDPresent = (flags & 0x0004) > 0;
        boolean bmrPresent = (flags & 0x0008) > 0;
        boolean musclePercentagePresent = (flags & 0x0010) > 0;
        boolean muscleMassPresent = (flags & 0x0020) > 0;
        boolean fatFreeMassPresent = (flags & 0x0040) > 0;
        boolean softLeanMassPresent = (flags & 0x0080) > 0;
        boolean bodyWaterMassPresent = (flags & 0x0100) > 0;
        boolean impedancePresent = (flags & 0x0200) > 0;
        boolean weightPresent = (flags & 0x0400) > 0;
        boolean heightPresent = (flags & 0x0800) > 0;
        boolean multiPacketMeasurement = (flags & 0x1000) > 0;

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        float bodyFatPercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        scaleMeasurement.setFat(bodyFatPercentage);

        // Read timestamp if present
        if (timestampPresent) {
            Date timestamp = parser.getDateTime();
            scaleMeasurement.setDateTime(timestamp);
        }

        // Read userID if present
        if (userIDPresent) {
            int scaleUserIndex = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
            int userID = getUserIdFromScaleIndex(scaleUserIndex);
            Timber.d(String.format("User id: %d, scale user index: %d", userID, scaleUserIndex));
        }

        // Read bmr if present
        if (bmrPresent) {
            int bmrInJoules = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
            int bmrInKcal = Math.round(((bmrInJoules / 4.1868f) * 10.0f) / 10.0f);
        }

        // Read musclePercentage if present
        if (musclePercentagePresent) {
            float musclePercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
            scaleMeasurement.setMuscle(musclePercentage);
        }

        // Read muscleMass if present
        if (muscleMassPresent) {
            float muscleMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
        }

        // Read fatFreeMassPresent if present
        if (fatFreeMassPresent) {
            float fatFreeMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
        }

        // Read softleanMass if present
        if (softLeanMassPresent) {
            float softLeanMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
        }

        // Read bodyWaterMass if present
        if (bodyWaterMassPresent) {
            float bodyWaterMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            scaleMeasurement.setWater(bodyWaterMass);
        }

        // Read impedance if present
        if (impedancePresent) {
            float impedance = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        }

        // Read weight if present
        if (weightPresent) {
            float weightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            scaleMeasurement.setWeight(weightValue);
        }

        // Read height if present
        if (heightPresent) {
            float heightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
        }

        Timber.d(String.format("Got body composition: %s", byteInHex(value)));
        addScaleMeasurement(scaleMeasurement);
    }

    protected void registerUser(int consentCode) {
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0,0,0});
        parser.setIntValue(UDS_CP_REGISTER_NEW_USER, FORMAT_UINT8,0);
        parser.setIntValue(consentCode, FORMAT_UINT16,1);
        Timber.d(String.format("registerUser consentCode: %d", consentCode));
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, parser.getValue());
    }

    protected void setUser(int userIndex, int consentCode) {
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0,0,0,0});
        parser.setIntValue(UDS_CP_CONSENT,FORMAT_UINT8,0);
        parser.setIntValue(userIndex, FORMAT_UINT8,1);
        parser.setIntValue(consentCode, FORMAT_UINT16,2);
        Timber.d(String.format("setUser userIndex: %d, consentCode: %d", userIndex, consentCode));
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, parser.getValue());
    }

    protected synchronized void setUser(int userId) {
        int userIndex = getUserScaleIndex(userId);
        int consentCode = getUserScaleConsent(userId);
        Timber.d(String.format("setting: userId %d, userIndex: %d, consent Code: %d ", userId, userIndex, consentCode));
        setUser(userIndex, consentCode);
    }

    protected void deleteUser(int userIndex, int consentCode) {
        setUser(userIndex, consentCode);
        deleteUser();
    }

    protected void deleteUser() {
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[] { 0 });
        parser.setIntValue(UDS_CP_DELETE_USER_DATA, FORMAT_UINT8, 0);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT,
                parser.getValue());
    }

    protected void writeCurrentTime() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setCurrentTime(Calendar.getInstance());
        writeBytes(BluetoothGattUuid.SERVICE_CURRENT_TIME, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME,
                parser.getValue());
    }

    protected void writeBirthday() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setDateTime(dateToCalender(this.selectedUser.getBirthday()));
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_DATE_OF_BIRTH,
                parser.getValue());
    }

    protected Calendar dateToCalender(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar;
    }

    protected void writeGender() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int gender = this.selectedUser.getGender().toInt();
        Timber.d(String.format("gender: %d", gender));
        parser.setIntValue(gender, FORMAT_UINT8);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_GENDER,
                parser.getValue());
    }

    protected void writeHeight() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int height = (int) this.selectedUser.getBodyHeight();
        Timber.d(String.format("height: %d", height));
        parser.setIntValue(height, FORMAT_UINT16);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_HEIGHT,
                parser.getValue());
    }

    protected void setChangeIncrement() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(1, FORMAT_UINT8);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT,
                parser.getValue());
    }

    protected synchronized void storeUserScaleConsentCode(int userId, int consentCode) {
        prefs.edit().putInt("userConsentCode" + userId, consentCode).apply();
    }

    protected synchronized int getUserScaleConsent(int userId) {
        return prefs.getInt("userConsentCode" + userId, -1);
    }

    protected synchronized void storeUserScaleIndex(int userId, int userIndex) {
        prefs.edit().putInt("userScaleIndex" + userId, userIndex).apply();
        prefs.edit().putInt("userIdFromUserScaleIndex" + userIndex, userId).apply();
    }

    protected synchronized int getUserIdFromScaleIndex(int userScaleIndex) {
        return prefs.getInt("userIdFromUserScaleIndex" + userScaleIndex, -1);
    }

    protected synchronized int getUserScaleIndex(int userId) {
        return prefs.getInt("userScaleIndex" + userId, -1);
    }
}
