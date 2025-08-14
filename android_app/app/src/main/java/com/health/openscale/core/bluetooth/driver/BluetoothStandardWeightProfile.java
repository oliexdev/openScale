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
 package com.health.openscale.core.bluetooth.driver;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT32;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Pair;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Random;
import java.util.UUID;
import java.util.Vector;

import timber.log.Timber;

public abstract class BluetoothStandardWeightProfile extends BluetoothCommunication {

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
    ScaleMeasurement previousMeasurement;
    protected boolean haveBatteryService;
    protected Vector<ScaleUser> scaleUserList;

    public BluetoothStandardWeightProfile(Context context) {
        super(context);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        this.registerNewUser = false;
        previousMeasurement = null;
        haveBatteryService = false;
        scaleUserList = new Vector<ScaleUser>();
    }

    @Override
    public String driverName() {
        return "Bluetooth Standard Weight Profile";
    }

    public static String driverId() {
        return "standard_weight_profile";
    }

    protected abstract int getVendorSpecificMaxUserCount();

    private enum SM_STEPS {
        START,
        READ_DEVICE_MANUFACTURER,
        READ_DEVICE_MODEL,
        WRITE_CURRENT_TIME,
        SET_NOTIFY_WEIGHT_MEASUREMENT,
        SET_NOTIFY_BODY_COMPOSITION_MEASUREMENT,
        SET_NOTIFY_CHANGE_INCREMENT,
        SET_INDICATION_USER_CONTROL_POINT,
        SET_NOTIFY_BATTERY_LEVEL,
        READ_BATTERY_LEVEL,
        SET_NOTIFY_VENDOR_SPECIFIC_USER_LIST,
        REQUEST_VENDOR_SPECIFIC_USER_LIST,
        REGISTER_NEW_SCALE_USER,
        SELECT_SCALE_USER,
        SET_SCALE_USER_DATA,
        REQUEST_MEASUREMENT,
        MAX_STEP
    }

    @Override
    protected boolean onNextStep(int stepNr) {

        if (stepNr > SM_STEPS.MAX_STEP.ordinal()) {
            Timber.d( "WARNING: stepNr == " + stepNr + " outside range, must be from 0 to " + SM_STEPS.MAX_STEP.ordinal());
            stepNr = SM_STEPS.MAX_STEP.ordinal();
        }
        SM_STEPS step = SM_STEPS.values()[stepNr];
        Timber.d("stepNr: " + stepNr + " " + step);

        switch (step) {
            case START:
                break;
            case READ_DEVICE_MANUFACTURER:
                // Read manufacturer from the Device Information Service
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION, BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING);
                break;
            case READ_DEVICE_MODEL:
                // Read model number from the Device Information Service
                readBytes(BluetoothGattUuid.SERVICE_DEVICE_INFORMATION, BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING);
                break;
            case WRITE_CURRENT_TIME:
                writeCurrentTime();
                break;
            case SET_NOTIFY_WEIGHT_MEASUREMENT:
                // Turn on notification for Weight Service
                setNotificationOn(BluetoothGattUuid.SERVICE_WEIGHT_SCALE, BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT);
                break;
            case SET_NOTIFY_BODY_COMPOSITION_MEASUREMENT:
                // Turn on notification for Body Composition Service
                setNotificationOn(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT);
                break;
            case SET_NOTIFY_CHANGE_INCREMENT:
                // Turn on notification for User Data Service Change Increment
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT);
                break;
            case SET_INDICATION_USER_CONTROL_POINT:
                // Turn on notification for User Control Point
                setIndicationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT);
                break;
            case SET_NOTIFY_BATTERY_LEVEL:
                // Turn on notifications for Battery Service
                if (setNotificationOn(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL)) {
                    haveBatteryService = true;
                }
                else {
                    haveBatteryService = false;
                }
                break;
            case READ_BATTERY_LEVEL:
                // read Battery Service
                if (haveBatteryService) {
                    readBytes(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                }
                break;
            case SET_NOTIFY_VENDOR_SPECIFIC_USER_LIST:
                setNotifyVendorSpecificUserList();
                break;
            case REQUEST_VENDOR_SPECIFIC_USER_LIST:
                scaleUserList.clear();
                requestVendorSpecificUserList();
                stopMachineState();
                break;
            case REGISTER_NEW_SCALE_USER:
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
            case SELECT_SCALE_USER:
                Timber.d("Select user on scale!");
                setUser(this.selectedUser.getId());
                stopMachineState();
                break;
            case SET_SCALE_USER_DATA:
                if (registerNewUser) {
                    writeUserDataToScale();
                    // stopping machine state to have all user data written, before the reference measurment starts, otherwise the scale might not store the user
                    stopMachineState();
                    // reading CHARACTERISTIC_CHANGE_INCREMENT to resume machine state
                    readBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT);
                }
                break;
            case REQUEST_MEASUREMENT:
                if (registerNewUser) {
                    requestMeasurement();
                    stopMachineState();
                    sendMessage(R.string.info_step_on_scale_for_reference, 0);
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
        writeActivityLevel();
        writeInitials();
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
            if (batteryLevel <= 10) {
                sendMessage(R.string.info_scale_low_battery, batteryLevel);
            }
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MANUFACTURER_NAME_STRING)) {
            String manufacturer = parser.getStringValue(0);
            Timber.d(String.format("Received manufacturer: %s", manufacturer));
        }
        else if(characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_MODEL_NUMBER_STRING)) {
            String modelNumber = parser.getStringValue(0);
            Timber.d(String.format("Received modelnumber: %s", modelNumber));
        }
        else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT)) {
            handleUserControlPointNotify(value);
        }
        else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT)) {
            int increment = parser.getIntValue(FORMAT_UINT32);
            Timber.d(String.format("Notification from CHARACTERISTIC_CHANGE_INCREMENT, value: %s", increment));
            resumeMachineState();
        }
        else {
            Timber.d(String.format("Notification from unhandled characteristic: %s, value: [%s]",
                    characteristic.toString(), byteInHex(value)));
        }
    }

    protected void handleUserControlPointNotify(byte[] value) {
        if(value[0]==UDS_CP_RESPONSE) {
            switch (value[1]) {
                case UDS_CP_LIST_ALL_USERS:
                    Timber.d("UDS_CP_LIST_ALL_USERS value [" + byteInHex(value) + "]");
                    break;
                case UDS_CP_REGISTER_NEW_USER:
                    if (value[2] == UDS_CP_RESP_VALUE_SUCCESS) {
                        int userIndex = value[3];
                        int userId = this.selectedUser.getId();
                        Timber.d(String.format("UDS_CP_REGISTER_NEW_USER: Created scale user index: "
                                + "%d (app user id: %d)", userIndex, userId));
                        storeUserScaleIndex(userId, userIndex);
                        resumeMachineState();
                    } else {
                        Timber.e("UDS_CP_REGISTER_NEW_USER: ERROR: could not register new scale user, code: " + value[2]);
                    }
                    break;
                case UDS_CP_CONSENT:
                    if (registerNewUser) {
                        Timber.d("UDS_CP_CONSENT: registerNewUser==true, value[2] == " + value[2]);
                        resumeMachineState();
                        break;
                    }
                    if (value[2] == UDS_CP_RESP_VALUE_SUCCESS) {
                        Timber.d("UDS_CP_CONSENT: Success user consent");
                        resumeMachineState();
                    } else if (value[2] == UDS_CP_RESP_USER_NOT_AUTHORIZED) {
                        Timber.e("UDS_CP_CONSENT: Not authorized");
                        enterScaleUserConsentUi(this.selectedUser.getId(), getUserScaleIndex(this.selectedUser.getId()));
                    }
                    else {
                        Timber.e("UDS_CP_CONSENT: unhandled, code: " + value[2]);
                    }
                    break;
                default:
                    Timber.e("CHARACTERISTIC_USER_CONTROL_POINT: Unhandled response code "
                            + value[1] + " value [" + byteInHex(value) + "]");
                    break;
            }
        }
        else {
            Timber.d("CHARACTERISTIC_USER_CONTROL_POINT: non-response code " + value[0]
                    + " value [" + byteInHex(value) + "]");
        }
    }

    protected ScaleMeasurement weightMeasurementToScaleMeasurement(byte[] value) {
        String prefix = "weightMeasurementToScaleMeasurement() ";
        Timber.d(String.format(prefix + "value: [%s]", byteInHex(value)));
        BluetoothBytesParser parser = new BluetoothBytesParser(value);

        final int flags = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
        boolean isKg = (flags & 0x01) == 0;
        final boolean timestampPresent = (flags & 0x02) > 0;
        final boolean userIDPresent = (flags & 0x04) > 0;
        final boolean bmiAndHeightPresent = (flags & 0x08) > 0;
        Timber.d(String.format(prefix + "flags: 0x%02x ", flags)
                + "[" + (isKg ? "SI" : "Imperial")
                + (timestampPresent ? ", timestamp" : "")
                + (userIDPresent ? ", userID" : "")
                + (bmiAndHeightPresent ? ", bmiAndHeight" : "")
                + "], " + String.format("reserved flags: 0x%02x ", flags & 0xf0));

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        // Determine the right weight multiplier
        float weightMultiplier = isKg ? 0.005f : 0.01f;

        // Get weight
        float weightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * weightMultiplier;
        Timber.d(prefix+ "weight: " + weightValue);
        scaleMeasurement.setWeight(weightValue);

        if(timestampPresent) {
            Date timestamp = parser.getDateTime();
            Timber.d(prefix + "timestamp: " + timestamp.toString());
            scaleMeasurement.setDateTime(timestamp);
        }

        if(userIDPresent) {
            int scaleUserIndex = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
            int userID = getUserIdFromScaleIndex(scaleUserIndex);
            Timber.d(String.format(prefix + "scale user index: %d (app user id: %d)", scaleUserIndex, userID));
            if (userID != -1) {
                scaleMeasurement.setUserId(userID);
            }

            if (registerNewUser) {
                Timber.d(String.format(prefix + "Setting initial weight for user %s to: %s and registerNewUser to false", userID,
                        weightValue));
                if (selectedUser.getId() == userID) {
                    this.selectedUser.setInitialWeight(weightValue);
                    OpenScale.getInstance().updateScaleUser(selectedUser);
                }
                registerNewUser = false;
                resumeMachineState();
            }
        }

        if(bmiAndHeightPresent) {
            float BMI = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
            Timber.d(prefix + "BMI: " + BMI);
            float heightInMeters = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.001f;
            Timber.d(prefix + "heightInMeters: " + heightInMeters);
        }

        Timber.d(String.format("Got weight: %s", weightValue));
        return scaleMeasurement;
    }

    protected void handleWeightMeasurement(byte[] value) {
        mergeWithPreviousScaleMeasurement(weightMeasurementToScaleMeasurement(value));
    }

    protected ScaleMeasurement bodyCompositionMeasurementToScaleMeasurement(byte[] value) {
        String prefix = "bodyCompositionMeasurementToScaleMeasurement() ";
        Timber.d(String.format(prefix + "value: [%s]", byteInHex(value)));
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
        Timber.d(String.format(prefix + "flags: 0x%02x ", flags)
                + "[" + (isKg ? "SI" : "Imperial")
                + (timestampPresent ? ", timestamp" : "")
                + (userIDPresent ? ", userID" : "")
                + (bmrPresent ? ", bmr" : "")
                + (musclePercentagePresent ? ", musclePercentage" : "")
                + (muscleMassPresent ? ", muscleMass" : "")
                + (fatFreeMassPresent ? ", fatFreeMass" : "")
                + (softLeanMassPresent ? ", softLeanMass" : "")
                + (bodyWaterMassPresent ? ", bodyWaterMass" : "")
                + (impedancePresent ? ", impedance" : "")
                + (weightPresent ? ", weight" : "")
                + (heightPresent ? ", height" : "")
                + (multiPacketMeasurement ? ", multiPacketMeasurement" : "")
                + "], " + String.format("reserved flags: 0x%04x ", flags & 0xe000));

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        float bodyFatPercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
        Timber.d(prefix + "bodyFatPercentage: " + bodyFatPercentage);
        scaleMeasurement.setFat(bodyFatPercentage);

        // Read timestamp if present
        if (timestampPresent) {
            Date timestamp = parser.getDateTime();
            Timber.d(prefix + "timestamp: " + timestamp.toString());
            scaleMeasurement.setDateTime(timestamp);
        }

        // Read userID if present
        if (userIDPresent) {
            int scaleUserIndex = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8);
            int userID = getUserIdFromScaleIndex(scaleUserIndex);
            Timber.d(String.format(prefix + "scale user index: %d (app user id: %d)", scaleUserIndex, userID));
            if (userID != -1) {
                scaleMeasurement.setUserId(userID);
            }
        }

        // Read bmr if present
        if (bmrPresent) {
            int bmrInJoules = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
            int bmrInKcal = Math.round(((bmrInJoules / 4.1868f) * 10.0f) / 10.0f);
            Timber.d(prefix + "bmrInJoules: " + bmrInJoules + " bmrInKcal: " + bmrInKcal);
        }

        // Read musclePercentage if present
        if (musclePercentagePresent) {
            float musclePercentage = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
            Timber.d(prefix + "musclePercentage: " + musclePercentage);
            scaleMeasurement.setMuscle(musclePercentage);
        }

        // Read muscleMass if present
        if (muscleMassPresent) {
            float muscleMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            Timber.d(prefix + "muscleMass: " + muscleMass);
        }

        // Read fatFreeMassPresent if present
        if (fatFreeMassPresent) {
            float fatFreeMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            Timber.d(prefix + "fatFreeMass: " + fatFreeMass);
        }

        // Read softleanMass if present
        float softLeanMass = 0.0f;
        if (softLeanMassPresent) {
            softLeanMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            Timber.d(prefix + "softLeanMass: " + softLeanMass);
        }

        // Read bodyWaterMass if present
        if (bodyWaterMassPresent) {
            float bodyWaterMass = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            Timber.d(prefix + "bodyWaterMass: " + bodyWaterMass);
            scaleMeasurement.setWater(bodyWaterMass);
        }

        // Read impedance if present
        if (impedancePresent) {
            float impedance = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * 0.1f;
            Timber.d(prefix + "impedance: " + impedance);
        }

        // Read weight if present
        float weightValue = 0.0f;
        if (weightPresent) {
            weightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16) * massMultiplier;
            Timber.d(prefix + "weightValue: " + weightValue);
            scaleMeasurement.setWeight(weightValue);
        }
        else {
            if (previousMeasurement != null) {
                weightValue = previousMeasurement.getWeight();
                if (weightValue > 0) {
                    weightPresent = true;
                }
            }
        }

        // calculate lean body mass and bone mass
        if (weightPresent && softLeanMassPresent) {
            float fatMass = weightValue * bodyFatPercentage / 100.0f;
            float leanBodyMass = weightValue - fatMass;
            float boneMass = leanBodyMass - softLeanMass;
            scaleMeasurement.setLbm(leanBodyMass);
            scaleMeasurement.setBone(boneMass);
        }

        // Read height if present
        if (heightPresent) {
            float heightValue = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT16);
            Timber.d(prefix + "heightValue: " + heightValue);
        }

        if (multiPacketMeasurement) {
            Timber.e(prefix + "multiPacketMeasurement not supported!");
        }

        Timber.d(String.format("Got body composition: %s", byteInHex(value)));
        return scaleMeasurement;
    }

    protected void handleBodyCompositionMeasurement(byte[] value) {
        mergeWithPreviousScaleMeasurement(bodyCompositionMeasurementToScaleMeasurement(value));
    }

    /**
     * Bluetooth scales usually implement both "Weight Scale Feature" and "Body Composition Feature".
     * It seems that scale first transmits weight measurement (with user index and timestamp) and
     * later transmits body composition measurement (without user index and timestamp).
     * If previous measurement contains user index and new measurements does not then merge them and
     * store as one.
     * disconnect() function must store previousMeasurement to openScale db (if present).
     *
     * @param newMeasurement the scale data that should be merged with previous measurement or
     *                       stored as previous measurement.
     */
    protected void mergeWithPreviousScaleMeasurement(ScaleMeasurement newMeasurement) {
        if (previousMeasurement == null) {
            if (newMeasurement.getUserId() == -1) {
                addScaleMeasurement(newMeasurement);
            }
            else {
                previousMeasurement = newMeasurement;
            }
        }
        else {
            if ((newMeasurement.getUserId() == -1) && (previousMeasurement.getUserId() != -1)) {
                previousMeasurement.merge(newMeasurement);
                addScaleMeasurement(previousMeasurement);
                previousMeasurement = null;
            }
            else {
                addScaleMeasurement(previousMeasurement);
                if (newMeasurement.getUserId() == -1) {
                    addScaleMeasurement(newMeasurement);
                    previousMeasurement = null;
                }
                else {
                    previousMeasurement = newMeasurement;
                }
            }
        }
    }

    @Override
    public void disconnect() {
        if (previousMeasurement != null) {
            addScaleMeasurement(previousMeasurement);
            previousMeasurement = null;
        }
        super.disconnect();
    }

    protected abstract void setNotifyVendorSpecificUserList();

    protected abstract void requestVendorSpecificUserList();

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
        Calendar userBirthday = dateToCalender(this.selectedUser.getBirthday());
        Timber.d(String.format("user Birthday: %tD", userBirthday));
        parser.setDateTime(userBirthday);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_DATE_OF_BIRTH,
                Arrays.copyOfRange(parser.getValue(), 0, 4));
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

    protected void writeActivityLevel() {
        Timber.d("Write user activity level not implemented!");
    }

    protected void writeInitials() {
        Timber.d("Write user initials not implemented!");
    }

    protected void setChangeIncrement() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int i = 1;
        Timber.d(String.format("Setting Change increment to %s", i));
        parser.setIntValue(i, FORMAT_UINT32);
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT,
                parser.getValue());
    }

    protected void requestMeasurement() {
        Timber.d("Take measurement command not implemented!");
    }

    protected synchronized void storeUserScaleConsentCode(int userId, int consentCode) {
        prefs.edit().putInt("userConsentCode" + userId, consentCode).apply();
    }

    protected synchronized int getUserScaleConsent(int userId) {
        return prefs.getInt("userConsentCode" + userId, -1);
    }

    protected synchronized void storeUserScaleIndex(int userId, int userIndex) {
        int currentUserIndex = getUserScaleIndex(userId);
        if (currentUserIndex != -1) {
            prefs.edit().putInt("userIdFromUserScaleIndex" + currentUserIndex, -1);
        }
        prefs.edit().putInt("userScaleIndex" + userId, userIndex).apply();
        if (userIndex != -1) {
            prefs.edit().putInt("userIdFromUserScaleIndex" + userIndex, userId).apply();
        }
    }

    protected synchronized int getUserIdFromScaleIndex(int userScaleIndex) {
        return prefs.getInt("userIdFromUserScaleIndex" + userScaleIndex, -1);
    }

    protected synchronized int getUserScaleIndex(int userId) {
        return prefs.getInt("userScaleIndex" + userId, -1);
    }

    protected void reconnectOrSetSmState(SM_STEPS requestedState, SM_STEPS minState, Handler uiHandler) {
        if (needReConnect()) {
            jumpNextToStepNr(SM_STEPS.START.ordinal());
            stopMachineState();
            reConnectPreviousPeripheral(uiHandler);
            return;
        }
        if (getStepNr() > minState.ordinal()) {
            jumpNextToStepNr(requestedState.ordinal());
        }
        resumeMachineState();
    }

    @Override
    public void selectScaleUserIndexForAppUserId(int appUserId, int scaleUserIndex, Handler uiHandler) {
        Timber.d("Select scale user index from UI: user id: " + appUserId + ", scale user index: " + scaleUserIndex);
        if (scaleUserIndex == -1) {
            reconnectOrSetSmState(SM_STEPS.REGISTER_NEW_SCALE_USER, SM_STEPS.REGISTER_NEW_SCALE_USER, uiHandler);
        }
        else {
            storeUserScaleIndex(appUserId, scaleUserIndex);
            if (getUserScaleConsent(appUserId) == -1) {
                enterScaleUserConsentUi(appUserId, scaleUserIndex);
            }
            else {
                reconnectOrSetSmState(SM_STEPS.SELECT_SCALE_USER, SM_STEPS.REQUEST_VENDOR_SPECIFIC_USER_LIST, uiHandler);
            }
        }
    }

    @Override
    public void setScaleUserConsent(int appUserId, int scaleUserConsent, Handler uiHandler) {
        Timber.d("set scale user consent from UI: user id: " + appUserId + ", scale user consent: " + scaleUserConsent);
        storeUserScaleConsentCode(appUserId, scaleUserConsent);
        if (scaleUserConsent == -1) {
            reconnectOrSetSmState(SM_STEPS.REQUEST_VENDOR_SPECIFIC_USER_LIST, SM_STEPS.REQUEST_VENDOR_SPECIFIC_USER_LIST, uiHandler);
        }
        else {
            reconnectOrSetSmState(SM_STEPS.SELECT_SCALE_USER, SM_STEPS.REQUEST_VENDOR_SPECIFIC_USER_LIST, uiHandler);
        }
    }

    protected void handleVendorSpecificUserList(byte[] value) {
            Timber.d(String.format("Got user data: <%s>", byteInHex(value)));
            BluetoothBytesParser parser = new BluetoothBytesParser(value);
            int userListStatus = parser.getIntValue(FORMAT_UINT8);
            if (userListStatus == 2) {
                Timber.d("scale have no users!");
                storeUserScaleConsentCode(selectedUser.getId(), -1);
                storeUserScaleIndex(selectedUser.getId(), -1);
                jumpNextToStepNr(SM_STEPS.REGISTER_NEW_SCALE_USER.ordinal());
                resumeMachineState();
                return;
            }
            else if (userListStatus == 1) {
                for (int i = 0; i < scaleUserList.size(); i++) {
                    if (i == 0) {
                        Timber.d("scale user list:");
                    }
                    Timber.d("\n" + (i + 1) + ". " + scaleUserList.get(i));
                }
                if ((scaleUserList.size() == 0)) {
                    storeUserScaleConsentCode(selectedUser.getId(), -1);
                    storeUserScaleIndex(selectedUser.getId(), -1);
                    jumpNextToStepNr(SM_STEPS.REGISTER_NEW_SCALE_USER.ordinal());
                    resumeMachineState();
                    return;
                }
                if (getUserScaleIndex(selectedUser.getId()) == -1 || getUserScaleConsent(selectedUser.getId()) == -1)  {
                    chooseExistingScaleUser(scaleUserList);
                    return;
                }
                resumeMachineState();
                return;
            }
            int index = parser.getIntValue(FORMAT_UINT8);
            String initials = parser.getStringValue();
            int end = 3 > initials.length() ? initials.length() : 3;
            initials = initials.substring(0, end);
            if (initials.length() == 3) {
                if (initials.charAt(0) == 0xff && initials.charAt(1) == 0xff && initials.charAt(2) == 0xff) {
                    initials = "";
                }
            }
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
            scaleUser.setId(index);
            scaleUserList.add(scaleUser);
            if (scaleUserList.size() == getVendorSpecificMaxUserCount()) {
                if (getUserScaleIndex(selectedUser.getId()) == -1 || getUserScaleConsent(selectedUser.getId()) == -1)  {
                    chooseExistingScaleUser(scaleUserList);
                    return;
                }
                resumeMachineState();
            }
    }

    protected void chooseExistingScaleUser(Vector<ScaleUser> userList) {
        final DateFormat dateFormat = DateFormat.getDateInstance();
        int choicesCount = userList.size();
        if (userList.size() < getVendorSpecificMaxUserCount()) {
            choicesCount = userList.size() + 1;
        }
        CharSequence[] choiceStrings = new String[choicesCount];
        int indexArray[] = new int[choicesCount];
        int selectedItem = -1;
        for (int i = 0; i < userList.size(); ++i) {
            ScaleUser u = userList.get(i);
            String name = u.getUserName();
            choiceStrings[i] = (name.length() > 0 ? name : String.format("P%02d", u.getId()))
                    + " " + context.getString(u.getGender().isMale() ? R.string.label_male : R.string.label_female).toLowerCase()
                    + " " + context.getString(R.string.label_height).toLowerCase() + ":" + u.getBodyHeight()
                    + " " + context.getString(R.string.label_birthday).toLowerCase() + ":" + dateFormat.format(u.getBirthday())
                    + " " + context.getString(R.string.label_activity_level).toLowerCase() + ":" + (u.getActivityLevel().toInt() + 1);
            indexArray[i] = u.getId();
        }
        if (userList.size() < getVendorSpecificMaxUserCount()) {
            choiceStrings[userList.size()] = context.getString(R.string.info_create_new_user_on_scale);
            indexArray[userList.size()] = -1;
        }
        Pair<CharSequence[], int[]> choices = new Pair(choiceStrings, indexArray);
        chooseScaleUserUi(choices);
    }

    protected String getInitials(String fullName) {
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
        String[] name = fullName.trim().split(" +");
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
}
