package com.health.openscale.core.bluetooth;

import android.content.Context;
import android.icu.number.Scale;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.OneByoneNewLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.math.BigInteger;
import java.util.Arrays;
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

    public BluetoothOneByoneNew(Context context) {
        super(context);
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] data){
        if(data == null){
            Timber.e("Received an empty message");
            return;
        }

        Timber.i("Received %s", new BigInteger(1, data).toString(16));

        if(data.length < 20){
            Timber.e("Received a message too short");
            return;
        }

        if(!(data[0] == HEADER_BYTES[0] && data[1] == HEADER_BYTES[1])){
            Timber.e("Unrecognized message received from scale.");
        }


        switch(data[2]){
            case (byte)0x00:
                // real time measurement
            case (byte)0x80:
                // final measurement
                currentMeasurement = new ScaleMeasurement();
                float weight = Converters.fromUnsignedInt24Be(data, 3) & 0x03ffff;
                weight = weight/1000;
                currentMeasurement.setWeight(weight);
                Timber.d("Weight: %s", weight);
                Timber.d("Weight after save: %s", currentMeasurement.getWeight());
                break;
            case (byte)0x01:
                int impedance = Converters.fromUnsignedInt24Be(data, 3);
                Timber.d("impedance: %s", impedance);

                if(currentMeasurement == null){
                    Timber.e("Received impedance value without weight");
                    break;
                }

                ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
                OneByoneNewLib onebyoneLib = new OneByoneNewLib(getUserGender(user), user.getAge(), user.getBodyHeight(), user.getActivityLevel().toInt());
                currentMeasurement.setDateTime(Calendar.getInstance().getTime());
                currentMeasurement.setFat(onebyoneLib.getBodyFatPercentage(currentMeasurement.getWeight(), impedance));
                currentMeasurement.setWater(onebyoneLib.getWaterPercentage(currentMeasurement.getWeight(), impedance));
                currentMeasurement.setBone(onebyoneLib.getBoneMass(currentMeasurement.getWeight(), impedance));
                currentMeasurement.setVisceralFat(onebyoneLib.getVisceralFat(currentMeasurement.getWeight()));
                currentMeasurement.setMuscle(onebyoneLib.getMuscleMass(currentMeasurement.getWeight(), impedance));
                currentMeasurement.setLbm(onebyoneLib.getLBM(currentMeasurement.getWeight(), impedance));
                addScaleMeasurement(currentMeasurement);
                resumeMachineState();
                sendUsersHistory(OpenScale.getInstance().getSelectedScaleUserId());
                break;
            default:
                Timber.e("Unrecognized message receveid");
        }
    }

    @Override
    public String driverName() {
        return "OneByoneNew";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch(stepNr){
            case 0:
                sendWeightRequest();
                break;
            case 1:
                // Setup notification on new weight
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION);
                stopMachineState();
                break;
            default:
                return false;
        }

        return true;
    }

    private void sendWeightRequest() {
        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        byte[] msg_pre = new byte[20];
        setupMeasurementMessage(msg_pre, 0);
        writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_AFTER_MEASUREMENT, msg_pre, true);

        sendUsersHistory(currentUser.getId());
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
        byte[] msg = new byte[20];
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
                msg = new byte[20];
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
                msg[19] = d4Checksum(msg, 0, 20);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_AFTER_MEASUREMENT, msg, true);
            }

        }
    }

    private void setMeasurementEntry(byte[] msg, int offset, int entryNum, int height, float weight, int sex, int age, int impedance, boolean impedanceLe){
        int roundedWeight = Math.round(weight*100);
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
        msg[offset] = (byte) (timestamp >> 24);
        msg[offset+1] = (byte) (timestamp >> 16);
        msg[offset+2] = (byte) (timestamp >> 8);
        msg[offset+3] = (byte) timestamp;
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
    public int getImpedanceFromLBM(ScaleUser user, ScaleMeasurement measurement){
        float finalLbm = measurement.getLbm();
        float postImpedanceLbm = finalLbm + user.getAge() * 0.0542F;
        float preImpedanceLbm = user.getBodyHeight()/100 * user.getBodyHeight()/100 * 9.058F + 12.226F + measurement.getWeight() * 0.32F;
        return Math.round((preImpedanceLbm - postImpedanceLbm) / 0.0068F);
    }

}
