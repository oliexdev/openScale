package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import androidx.annotation.NonNull;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.OneByoneNewLib;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothOneByoneNew extends BluetoothCommunication{
    private final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xffb0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION = BluetoothGattUuid.fromShortCode(0xffb2);
    private final UUID CMD_AFTER_MEASUREMENT = BluetoothGattUuid.fromShortCode(0xffb1);

    private final int MSG_LENGTH = 20;
    private final byte[] HEADER_BYTES = { (byte)0xAB, (byte)0x2A };

    private ScaleMeasurement currentMeasurement;

    public BluetoothOneByoneNew(Context context, String deviceName) {
        super(context, deviceName);
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] data){
        if(data == null){
            Timber.e("Received an empty message");
            return;
        }

        Timber.i("Received %s", new BigInteger(1, data).toString(16));

        if(data.length < MSG_LENGTH){
            Timber.e("Received a message too short");
            return;
        }

        if(!(data[0] == HEADER_BYTES[0] && data[1] == HEADER_BYTES[1])){
            Timber.e("Unrecognized message received from scale.");
        }

        float weight;
        int impedance;


        switch(data[2]){
            case (byte)0x00:
                // real time measurement OR historic measurement
                // real time has the exact same format of 0x80, but we can ignore it
                // we want to capture the historic measures

                // filter out real time measurments
                if (data[7] != (byte)0x80){
                    Timber.i("Received real-time measurement. Skipping.");
                    break;
                }

                Date time = getTimestamp32(data, 3);
                weight = Converters.fromUnsignedInt24Be(data, 8) & 0x03ffff;
                weight /= 1000;
                impedance = Converters.fromUnsignedInt16Be(data, 15);

                ScaleMeasurement historicMeasurement = new ScaleMeasurement();
                int assignableUserId = OpenScale.getInstance().getAssignableUser(weight);
                if(assignableUserId == -1){
                    Timber.i("Discarding historic measurement: no user found with intelligent user recognition");
                    break;
                }
                populateMeasurement(assignableUserId, historicMeasurement, impedance, weight);
                historicMeasurement.setDateTime(time);
                addScaleMeasurement(historicMeasurement);
                Timber.i("Added historic measurement. Weight: %s, impedance: %s, timestamp: %s", weight, impedance, time.toString());
                break;

            case (byte)0x80:
                // final measurement
                currentMeasurement = new ScaleMeasurement();
                weight = Converters.fromUnsignedInt24Be(data, 3) & 0x03ffff;
                weight = weight / 1000;
                currentMeasurement.setWeight(weight);
                Timber.d("Weight: %s", weight);
                break;
            case (byte)0x01:
                impedance = Converters.fromUnsignedInt16Be(data, 4);
                Timber.d("impedance: %s", impedance);

                if(currentMeasurement == null){
                    Timber.e("Received impedance value without weight");
                    break;
                }

                float measurementWeight = currentMeasurement.getWeight();
                ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
                populateMeasurement(user.getId(), currentMeasurement, impedance, measurementWeight);
                addScaleMeasurement(currentMeasurement);
                resumeMachineState();
                break;
            default:
                Timber.e("Unrecognized message receveid");
        }
    }

    private void populateMeasurement(int userId, ScaleMeasurement measurement, int impedance, float weight) {
        if(userId == -1){
            Timber.e("Discarding measurement population since invalid user");
            return;
        }
        ScaleUser user = OpenScale.getInstance().getScaleUser(userId);
        float cmHeight = Converters.fromCentimeter(user.getBodyHeight(), user.getMeasureUnit());
        OneByoneNewLib onebyoneLib = new OneByoneNewLib(getUserGender(user), user.getAge(), cmHeight, user.getActivityLevel().toInt());
        measurement.setUserId(userId);
        measurement.setWeight(weight);
        measurement.setDateTime(Calendar.getInstance().getTime());
        measurement.setFat(onebyoneLib.getBodyFatPercentage(weight, impedance));
        measurement.setWater(onebyoneLib.getWaterPercentage(weight, impedance));
        measurement.setBone(onebyoneLib.getBoneMass(weight, impedance));
        measurement.setVisceralFat(onebyoneLib.getVisceralFat(weight));
        measurement.setMuscle(onebyoneLib.getSkeletonMusclePercentage(weight, impedance));
        measurement.setLbm(onebyoneLib.getLBM(weight, impedance));
    }

    @Override
    public String driverName() {
        return "OneByoneNew";
    }

    public static String driverId() {
        return "one_by_one_new";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch(stepNr){
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION);
                break;
            case 1:
                // Setup notification on new weight
                sendWeightRequest();

                // Update the user history on the scale
                // Priority given to the current user
                ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
                sendUsersHistory(currentUser.getId());

                // We wait for the response
                stopMachineState();
                break;
            case 2:
                // After the measurement took place, we store the data and send back to the scale
                sendUsersHistory(OpenScale.getInstance().getSelectedScaleUserId());
                break;
            default:
                return false;
        }

        return true;
    }

    private void sendWeightRequest() {
        byte[] msgSetup = new byte[MSG_LENGTH];
        setupMeasurementMessage(msgSetup, 0);
        writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_AFTER_MEASUREMENT, msgSetup, true);
    }

    private void sendUsersHistory(int priorityUser){
        List<ScaleUser> scaleUsers = OpenScale.getInstance().getScaleUserList();
        Collections.sort(scaleUsers, (ScaleUser u1, ScaleUser u2) -> {
                    if(u1.getId() == priorityUser) return -9999;
                    if(u2.getId() == priorityUser) return 9999;
                    Date u1LastMeasureDate = OpenScale.getInstance().getLastScaleMeasurement(u1.getId()).getDateTime();
                    Date u2LastMeasureDate = OpenScale.getInstance().getLastScaleMeasurement(u2.getId()).getDateTime();
                    return u1LastMeasureDate.compareTo(u2LastMeasureDate);
                }
        );
        byte[] msg = new byte[MSG_LENGTH];
        int msgCounter = 0;
        for(int i = 0; i < scaleUsers.size(); i++){
            ScaleUser user = scaleUsers.get(i);
            ScaleMeasurement lastMeasure = OpenScale.getInstance().getLastScaleMeasurement(user.getId());
            float weight = 0;
            int impedance = 0;
            if(lastMeasure != null){
                weight = lastMeasure.getWeight();
                impedance = getImpedanceFromLBM(user, lastMeasure);
            }

            int entryPosition = i % 2;

            if (entryPosition == 0){
                msg = new byte[MSG_LENGTH];
                msgCounter ++;
                msg[0] = HEADER_BYTES[0];
                msg[1] = HEADER_BYTES[1];
                msg[2] = (byte) scaleUsers.size();
                msg[3] = (byte) msgCounter;
            }

            setMeasurementEntry(msg, 4 + entryPosition * 7, i + 1,
                    Math.round(user.getBodyHeight()),
                    weight,
                    getUserGender(user),
                    user.getAge(),
                    impedance,
                    true);

            if (entryPosition == 1 || i + 1 == scaleUsers.size()){
                msg[18] = (byte)0xD4;
                msg[19] = d4Checksum(msg, 0, MSG_LENGTH);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_AFTER_MEASUREMENT, msg, true);
            }

        }
    }

    private void setMeasurementEntry(byte[] msg, int offset, int entryNum, int height, float weight, int sex, int age, int impedance, boolean impedanceLe){
        // The scale wants a value rounded to the first decimal place
        // Otherwise we receive always a UP/DOWN arrow since we would communicate
        // AB.CX instead of AB.D0 where D0 is the approximation of CX and it is what the scale uses
        // to compute the UP/DOWN arrows
        int roundedWeight = Math.round( weight * 10) * 10;
        msg[offset] = (byte)(entryNum & 0xFF);
        msg[offset+1] = (byte)(height & 0xFF);
        Converters.toInt16Be(msg, offset+2, roundedWeight);
        msg[offset+4] = (byte)(((sex & 0xFF) << 7) + (age & 0x7F));

        if(impedanceLe) {
            msg[offset + 5] = (byte) (impedance >> 8);
            msg[offset + 6] = (byte) impedance;
        } else {
            msg[offset + 5] = (byte) impedance;
            msg[offset + 6] = (byte) (impedance >> 8);
        }
    }

    private void setTimestamp32(byte[] msg, int offset){
        long timestamp = System.currentTimeMillis()/1000L;
        Converters.toInt32Be(msg, offset, timestamp);
    }

    private Date getTimestamp32(byte[] msg, int offset){
        long timestamp = Converters.fromUnsignedInt32Be(msg, offset);
        return new Date(timestamp * 1000);
    }

    private boolean setupMeasurementMessage(byte[] msg, int offset){
        if(offset + MSG_LENGTH > msg.length){
            return false;
        }

        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        Converters.WeightUnit weightUnit = currentUser.getScaleUnit();

        msg[offset] = HEADER_BYTES[0];
        msg[offset+1] = HEADER_BYTES[1];
        setTimestamp32(msg, offset+2);
        // This byte has been left empty in all the observations, unknown meaning
        msg[offset+6] = 0;
        msg[offset+7] = (byte) weightUnit.toInt();
        int userId = currentUser.getId();


        // We send the last measurement or if not present an empty one
        ScaleMeasurement lastMeasure = OpenScale.getInstance().getLastScaleMeasurement(userId);
        float weight = 0;
        int impedance = 0;
        if(lastMeasure != null){
            weight = lastMeasure.getWeight();
            impedance = getImpedanceFromLBM(currentUser, lastMeasure);
        }

        setMeasurementEntry(msg, offset+8,
                userId,
                Math.round(currentUser.getBodyHeight()),
                weight,
                getUserGender(currentUser),
                currentUser.getAge(),
                impedance,
                false
                );

        // Blank bytes after the empty measurement
        msg[offset + 18] = (byte) 0xD7;
        msg[offset+19] = d7Checksum(msg, offset+2, 17);
        return true;
    }

    private int getUserGender(ScaleUser user){
        // Custom function since the toInt() gives the opposite values
        return user.getGender().isMale() ? 1 : 0;
    }

    private byte d4Checksum(byte[] msg, int offset, int length){
        byte sum = sumChecksum(msg, offset + 2, length - 2);

        // Remove impedance MSB first entry
        sum -= msg[offset+9];

        // Remove second entry weight
        sum -= msg[offset+13];
        sum -= msg[offset+14];

        // Remove impedance MSB second entry
        sum -= msg[offset+16];
        return sum;
    }

    private byte d7Checksum(byte[] msg, int offset, int length){
        byte sum = sumChecksum(msg, offset+2, length-2);

        // Remove impedance MSB
        sum -= msg[offset+14];
        return sum;
    }

    // Since we need to send the impedance to the scale the next time,
    // we obtain it from the previous measurement using the LBM
    public int getImpedanceFromLBM(ScaleUser user, ScaleMeasurement measurement) {
        float finalLbm = measurement.getLbm();
        float postImpedanceLbm = finalLbm + user.getAge() * 0.0542F;
        float preImpedanceLbm = user.getBodyHeight() / 100 * user.getBodyHeight() / 100 * 9.058F + 12.226F + measurement.getWeight() * 0.32F;
        return Math.round((preImpedanceLbm - postImpedanceLbm) / 0.0068F);
    }

}
