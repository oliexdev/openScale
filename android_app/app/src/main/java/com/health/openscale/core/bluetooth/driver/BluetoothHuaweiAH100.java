/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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

package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

// +++
import android.os.Handler;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public class BluetoothHuaweiAH100 extends BluetoothCommunication {
    private static final UUID SERVICE_AH100_CUSTOM_SERVICE = BluetoothGattUuid.fromShortCode(0xfaa0);

    private static final UUID SERVICE_AH100_CUSTOM_SEND = BluetoothGattUuid.fromShortCode(0xfaa1);
    private static final UUID SERVICE_AH100_CUSTOM_RECEIVE = BluetoothGattUuid.fromShortCode(0xfaa2);

    // +++
    private static byte[] user_id = {0, 0, 0, 0, 0, 0, 0};

    private enum STEPS  {
        INIT,
        INIT_W,
        AUTHORISE,
        SCALE_UNIT,
        SCALE_TIME,
        USER_INFO,
        SCALE_VERSION,
        WAIT_MEASUREMENT,
        READ_HIST,
        READ_HIST_NEXT,
        EXIT,
        BIND,
        EXIT2
    }

    private static final byte AH100_NOTIFICATION_WAKEUP = 0x00;
    private static final byte AH100_NOTIFICATION_GO_SLEEP = 0x01;
    private static final byte AH100_NOTIFICATION_UNITS_SET = 0x02;
    private static final byte AH100_NOTIFICATION_REMINDER_SET = 0x03;
    private static final byte AH100_NOTIFICATION_SCALE_CLOCK = 0x08;
    private static final byte AH100_NOTIFICATION_SCALE_VERSION = 0x0C;
    private static final byte AH100_NOTIFICATION_MEASUREMENT = 0x0E;
    private static final byte AH100_NOTIFICATION_MEASUREMENT2 = (byte) 0x8E;
    private static final byte AH100_NOTIFICATION_MEASUREMENT_WEIGHT = 0x0F;
    private static final byte AH100_NOTIFICATION_HISTORY_RECORD = 0x10;
    private static final byte AH100_NOTIFICATION_HISTORY_RECORD2 = (byte) 0x90;
    private static final byte AH100_NOTIFICATION_UPGRADE_RESPONSE = 0x11;
    private static final byte AH100_NOTIFICATION_UPGRADE_RESULT = 0x12;
    private static final byte AH100_NOTIFICATION_WEIGHT_OVERLOAD = 0x13;
    private static final byte AH100_NOTIFICATION_LOW_POWER = 0x14;
    private static final byte AH100_NOTIFICATION_MEASUREMENT_ERROR = 0x15;
    private static final byte AH100_NOTIFICATION_SET_CLOCK_ACK = 0x16;
    private static final byte AH100_NOTIFICATION_OTA_UPGRADE_READY = 0x17;
    private static final byte AH100_NOTIFICATION_SCALE_MAC_RECEIVED = 0x18;
    private static final byte AH100_NOTIFICATION_HISTORY_UPLOAD_DONE = 0x19;
    private static final byte AH100_NOTIFICATION_USER_CHANGED = 0x20;
    private static final byte AH100_NOTIFICATION_AUTHENTICATION_RESULT = 0x26;
    private static final byte AH100_NOTIFICATION_BINDING_SUCCESSFUL = 0x27;
    private static final byte AH100_NOTIFICATION_FIRMWARE_UPDATE_RECEIVED = 0x28;

    private static final byte AH100_CMD_SET_UNIT = 2;
    private static final byte AH100_CMD_DELETE_ALARM_CLOCK = 3;
    private static final byte AH100_CMD_SET_ALARM_CLOCK = 4;
    private static final byte AH100_CMD_DELETE_ALL_ALARM_CLOCK = 5;
    private static final byte AH100_CMD_GET_ALARM_CLOCK_BY_NO = 6;
    private static final byte AH100_CMD_SET_SCALE_CLOCK = 8;
    private static final byte AH100_CMD_SELECT_USER = 10;
    private static final byte AH100_CMD_USER_INFO = 9;
    private static final byte AH100_CMD_GET_RECORD = 11;
    private static final byte AH100_CMD_GET_VERSION = 12;
    private static final byte AH100_CMD_GET_SCALE_CLOCK = 14;
    private static final byte AH100_CMD_GET_USER_LIST_MARK = 15;
    private static final byte AH100_CMD_UPDATE_SIGN = 16;
    private static final byte AH100_CMD_DELETE_ALL_USER = 17;
    private static final byte AH100_CMD_SET_BLE_BROADCAST_TIME = 18;
    private static final byte AH100_CMD_FAT_RESULT_ACK = 19;
    private static final byte AH100_CMD_GET_LAST_RECORD = 20;
    private static final byte AH100_CMD_DISCONNECT_BT = 22;
    private static final byte AH100_CMD_HEART_BEAT = 32;
    private static final byte AH100_CMD_AUTH = 36;
    private static final byte AH100_CMD_BIND_USER = 37;
    private static final byte AH100_CMD_OTA_PACKAGE  = (byte) 0xDD;

    private Context context;
    private byte[] authCode;
    private byte[] initialKey ;
    private byte[] initialValue ;
    private byte[] magicKey ;

    private int triesToAuth = 0;
    private int triesToBind = 0;
    private int lastMeasuredWeight = -1;
    private boolean authorised = false;
    private boolean scaleWakedUp = false;
    private boolean scaleBinded = false;
    private byte receivedPacketType = 0x00;
    private byte[] receivedPacket1;

    private Handler beatHandler;


    public BluetoothHuaweiAH100(Context context) {
        super(context);
        this.context = context;
        this.beatHandler = new Handler();
        authCode = getUserID();
        initialKey = hexToByteArray("3D A2 78 4A FB 87 B1 2A 98 0F DE 34 56 73 21 56");
        initialValue = hexToByteArray("4E F7 64 32 2F DA 76 32 12 3D EB 87 90 FE A2 19");

    }

    @Override
    public String driverName() {
        return "Huawei AH100 Body Fat Scale";
    }

    public static String driverId() {
        return "huawei_ah100";
    }

///////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////

    @Override
    protected boolean onNextStep(int stepNr) {
        STEPS step;
        try {
            step = STEPS.values()[stepNr];
        } catch (Exception e) {
            // stepNr is bigger then we have in STEPS
            return false;
        }
        switch (step) {
            case INIT:
                // wait scale wake up
                Timber.d("AH100::onNextStep step 0 = set notification");
                final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                // Setup notification
                setNotificationOn(SERVICE_AH100_CUSTOM_SERVICE, SERVICE_AH100_CUSTOM_RECEIVE);
                triesToAuth = 0;
                authorised = false;
                stopMachineState();
                break;
            case INIT_W:
                stopMachineState();
                break;
            case AUTHORISE:
                if ( scaleWakedUp == false ) {
                    jumpNextToStepNr( STEPS.INIT.ordinal() );
                    break;
                }
                // authorize in scale
                Timber.d("AH100::onNextStep  = authorize on scale");
                triesToAuth++;
                AHcmdAutorise();
                stopMachineState();
                break;
            case  SCALE_UNIT:
                Timber.d("AH100::onNextStep  = set scale unit");
                AHcmdSetUnit();
                stopMachineState();
                break;
            case  SCALE_TIME:
                Timber.d("AH100::onNextStep  = set scale time");
                AHcmdDate();
                stopMachineState();
                break;
            case USER_INFO:
                Timber.d("AH100::onNextStep  = send user info to scale");
                if ( !authorised ) {
                    jumpNextToStepNr( STEPS.AUTHORISE.ordinal() );
                    break;
                }
                // set user data
                AHcmdUserInfo();
                stopMachineState();
                break;
            case SCALE_VERSION:
                Timber.d("AH100::onNextStep  = request scale version");
                if ( !authorised ) {
                    jumpNextToStepNr( STEPS.AUTHORISE.ordinal() );
                    break;
                }
                AHcmdGetVersion();
                stopMachineState();
                break;
            case WAIT_MEASUREMENT:
                AHcmdGetUserList();
                Timber.d("AH100::onNextStep  = Do nothing, wait while scale tries disconnect");
                sendMessage(R.string.info_step_on_scale, 0);
                stopMachineState();
                break;
            case READ_HIST:
                Timber.d("AH100::onNextStep  = read history record from scale");
                if ( !authorised ) {
                    jumpNextToStepNr( STEPS.AUTHORISE.ordinal() );
                    break;
                }
                AHcmdReadHistory();
                stopMachineState();
                break;
            case READ_HIST_NEXT:
                Timber.d("AH100::onNextStep  = read NEXT history record from scale");
                if ( !authorised ) {
                    jumpNextToStepNr( STEPS.AUTHORISE.ordinal() );
                    break;
                }
                AHcmdReadHistoryNext();
                stopMachineState();
                break;
            case EXIT:
                Timber.d("AH100::onNextStep  = Exit");
                authorised = false;
                scaleWakedUp = false;
                stopHeartBeat();
                disconnect();
                return false;
            case BIND:
                Timber.d("AH100::onNextStep  = BIND scale to OpenScale");
                // Start measurement
                sendMessage(R.string.info_step_on_scale, 0);
                triesToBind++;
                AHcmdBind();
                AHcmdBind();
                stopMachineState();
                break;
            case EXIT2:
                authorised = false;
                scaleWakedUp = false;
                stopHeartBeat();
                disconnect();
                Timber.d("AH100::onNextStep  = BIND Exit");
            default:
                return false;
        }

        return true;
    }

///////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;
        byte cmdlength = 0;
        Timber.d("AH100::onBluetoothNotify uuid: %s", characteristic.toString());

        if (data != null && data.length > 2) {
            Timber.d("===> New NOTIFY hex data: %s", byteInHex(data));

            cmdlength = data[1];

            // responce from scale received
                switch (data[2]) {
                    case AH100_NOTIFICATION_WAKEUP:
                        scaleWakedUp = true;
                        if (getStepNr() - 1 == STEPS.INIT_W.ordinal() ) {
                            Timber.d("AH100::onNotify = Scale is waked up in Init-stage");
                            startHeartBeat();
                            resumeMachineState();
                            break;
                        }
//                        if (getStepNr() - 1 == STEPS.BIND.ordinal() ) {
//                            Timber.d("AH100::onNotify = Scale is waked up in Init-stage");
//                            jumpBackOneStep();
//                            resumeMachineState();
//                            break;
//                        }
                        Timber.d("AH100::onNotify = Scale is waked up");
//                        authorised = false;
//                        jumpNextToStepNr(STEPS.AUTHORISE.ordinal());
//                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_GO_SLEEP:
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_UNITS_SET:
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_REMINDER_SET:
                        break;
                    case AH100_NOTIFICATION_SCALE_CLOCK:
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_SCALE_VERSION:
                        byte[] VERpayload = getPayload(data);
                        Timber.d("Get Scale Version: input data: %s", byteInHex(VERpayload));
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_MEASUREMENT:
                        if (data[0] == (byte) 0xBD) {
                            Timber.d("Scale plain response received");
                        }
                        if (data[0] == (byte) 0xBC) {
                            Timber.d("Scale encoded response received");
                            receivedPacket1 = Arrays.copyOfRange(data, 0, data.length );
                            receivedPacketType = AH100_NOTIFICATION_MEASUREMENT;
                        }
                        break;
                    case AH100_NOTIFICATION_MEASUREMENT2:
                        if (data[0] == (byte) 0xBC) {   /// normal packet
                            Timber.d("Scale encoded response received");
                            if (receivedPacketType == AH100_NOTIFICATION_MEASUREMENT) {
                                AHrcvEncodedMeasurement(receivedPacket1, data, AH100_NOTIFICATION_MEASUREMENT);
                                receivedPacketType = 0x00;
                                if (scaleBinded == true) {
                                    AHcmdMeasurementAck();
                                } else {
                                    if (lastMeasuredWeight > 0) {
                                        AHcmdUserInfo(lastMeasuredWeight);
                                    }
                                }
                                jumpNextToStepNr( STEPS.READ_HIST.ordinal() );
                                resumeMachineState();
                            }
                            break;
                        }
                        if (data[0] == (byte) 0xBD) {
                            Timber.d("Scale plain response received");
                        }
                        jumpNextToStepNr( STEPS.INIT.ordinal() );
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_MEASUREMENT_WEIGHT:
                        break;
                    case AH100_NOTIFICATION_HISTORY_RECORD:
                        if (data[0] == (byte) 0xBD) {
                            Timber.d("Scale plain response received");
                        }
                        if (data[0] == (byte) 0xBC) {
                            Timber.d("Scale encoded response received");
                            receivedPacket1 = Arrays.copyOfRange(data, 0, data.length );
                            receivedPacketType = AH100_NOTIFICATION_HISTORY_RECORD;
                        }
                        break;
                    case AH100_NOTIFICATION_HISTORY_RECORD2:
                        if (data[0] == (byte) 0xBC) {   /// normal packet
                            Timber.d("Scale encoded response received");
                            if (receivedPacketType == AH100_NOTIFICATION_HISTORY_RECORD) {
                                AHrcvEncodedMeasurement(receivedPacket1, data, AH100_NOTIFICATION_HISTORY_RECORD);
                                receivedPacketType = 0x00;
                                // todo: jumpback only in ReadHistoryNext
                                jumpNextToStepNr(STEPS.READ_HIST_NEXT.ordinal(),
                                        STEPS.READ_HIST_NEXT.ordinal());
                                resumeMachineState();
                            }
                            break;
                        }
                        if (data[0] == (byte) 0xBD) {
                            Timber.d("Scale plain response received");
                        }
                        jumpNextToStepNr( STEPS.INIT.ordinal() );
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_UPGRADE_RESPONSE:
                        break;
                    case AH100_NOTIFICATION_UPGRADE_RESULT:
                        break;
                    case AH100_NOTIFICATION_WEIGHT_OVERLOAD:
                        break;
                    case AH100_NOTIFICATION_LOW_POWER:
                        break;
                    case AH100_NOTIFICATION_MEASUREMENT_ERROR:
                        break;
                    case AH100_NOTIFICATION_SET_CLOCK_ACK:
                        break;
                    case AH100_NOTIFICATION_OTA_UPGRADE_READY:
                        break;
                    case AH100_NOTIFICATION_SCALE_MAC_RECEIVED:
                        break;
                    case AH100_NOTIFICATION_HISTORY_UPLOAD_DONE:
                        resumeMachineState();
                        break;
                    case AH100_NOTIFICATION_USER_CHANGED:
                        resumeMachineState(STEPS.USER_INFO.ordinal()); // waiting wake up in state 4
                        break;
                    case AH100_NOTIFICATION_AUTHENTICATION_RESULT:
                        byte[] ARpayload = getPayload(data);
                        if ( 1 == ARpayload[0] ){
                            authorised = true;
                            magicKey = hexConcatenate(obfuscate(authCode) , Arrays.copyOfRange(initialKey, 7, initialKey.length ) );
                            resumeMachineState(STEPS.AUTHORISE.ordinal()); // waiting wake up in state 4
                        } else {
                            if (triesToAuth < 3){ // try again
                                jumpNextToStepNr(STEPS.AUTHORISE.ordinal());
                            } else {    // bind scale to own code
                                jumpNextToStepNr(STEPS.BIND.ordinal());
                            }
                            resumeMachineState();
                        }
                        // acknowledge that you received the last history data
                        break;
                    case AH100_NOTIFICATION_BINDING_SUCCESSFUL:
                        // jump to authorise again
                        jumpNextToStepNr(STEPS.SCALE_TIME.ordinal());
                        scaleBinded = true;
                        // TODO: count binding tries
                        break;
                    case AH100_NOTIFICATION_FIRMWARE_UPDATE_RECEIVED:
                        break;
                    default:
                        break;

                } // switch command

        }
    }





    private void AHcmdHeartBeat() {
        AHsendCommand(AH100_CMD_HEART_BEAT, new byte[0] );
    }


    private void AHcmdAutorise() {
        AHsendCommand(AH100_CMD_AUTH, authCode);
    }

    private void AHcmdBind() {
        AHsendCommand(AH100_CMD_BIND_USER, authCode);
    }


    private void AHcmdDate() {
        /*
            payload[0]: lowerByte(year)
            payload[1]: upperByte(year)
            payload[2]: month (1..12)
            payload[3]: dayOfMonth
            payload[4]: hourOfDay (0-23)
            payload[5]: minute
            payload[6]: second
            payload[7]: day of week (Monday=1, Sunday=7)
         */
        Calendar currentDateTime = Calendar.getInstance();
        int year = currentDateTime.get(Calendar.YEAR);
        byte month = (byte)(currentDateTime.get(Calendar.MONTH)+1);
        byte day = (byte)currentDateTime.get(Calendar.DAY_OF_MONTH);
        byte hour = (byte)currentDateTime.get(Calendar.HOUR_OF_DAY);
        byte min = (byte)currentDateTime.get(Calendar.MINUTE);
        byte sec = (byte)currentDateTime.get(Calendar.SECOND);
        byte dow = (byte)currentDateTime.get(Calendar.DAY_OF_WEEK);
        byte[] date = new byte[]{
                0x00, 0x00, // year, fill later
                month,
                day,
                hour,
                min,
                sec,
                dow
        };
        Converters.toInt16Le(date, 0, year);
        Timber.d("AH100::AHcmdDate: data to send: %s", byteInHex(date) );
        AHsendCommand(AH100_CMD_SET_SCALE_CLOCK, date);
    }

    private void AHcmdUserInfo() {
        ///String user example =  "27 af 00 2a 03 ff ff";

        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        int weight = (int) currentUser.getInitialWeight() * 10;
        AHcmdUserInfo(weight);
    }

    private void AHcmdUserInfo(int weight) {
        ///String user example =  "27 af 00 2a 03 ff ff";
        /*
            payload[7] = sex == 1 ? age | 0x80 : age
            payload[8] = height of the user
            payload[9] = 0
            payload[10] = lowerByte(weight)
            payload[11] = upperByte(weight)
            payload[12] = lowerByte(impedance)
            payload[13] = upperByte(impedance)
         */
        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        byte height = (byte) currentUser.getBodyHeight();
        byte sex = currentUser.getGender().isMale() ? 0 : (byte) 0x80;
        byte age = (byte) ( sex  |  ((byte) currentUser.getAge()) );
        byte[] user = new byte[]{
                age,
                height,
                0,
                0x00, 0x00, // weight, fill later
                (byte) 0xFF, (byte) 0xFF,    // resistance, wkwtfdim
                (byte) 0x1C, (byte) 0xE2,
        };
        Converters.toInt16Le(user, 3, weight);
        byte[] userinfo = hexConcatenate( authCode, user );
        AHsendCommand(AH100_CMD_USER_INFO, userinfo, 14);
    }

    private void AHcmdReadHistory() {
        byte[] pl;
        byte[] xp = {xorChecksum(authCode, 0, authCode.length)};
        pl = hexConcatenate(  authCode, xp );
        AHsendCommand(AH100_CMD_GET_RECORD, pl, 0x07 - 1);
    }

    private void AHcmdReadHistoryNext() {
        byte[] pl = {0x01};
        AHsendCommand(AH100_CMD_GET_RECORD, pl);
    }

    private void AHcmdSetUnit() {
        // TODO: set correct units
        byte[] pl = new byte[]{0x01}; // 1 = kg; 2 = pounds. set kg only
        AHsendCommand(AH100_CMD_SET_UNIT, pl);
    }

    private void AHcmdGetUserList() {
        //byte[] pl = new byte[]{};
//        byte[] pl = authCode;
//        AHsendCommand(AH100_CMD_SELECT_USER, pl);
    }

    private void AHcmdGetVersion() {
        byte[] pl = new byte[]{};
        AHsendCommand(AH100_CMD_GET_VERSION, pl);
    }

    private void AHcmdMeasurementAck() {
        byte[] pl = new byte[]{0x00};
        AHsendCommand(AH100_CMD_FAT_RESULT_ACK, pl);
    }

    private void AHrcvEncodedMeasurement(byte[] encdata, byte[] encdata2, byte type) {
        byte[] payload = getPayload(encdata);
        byte[] data;
        try{
            data = decryptAES(payload, magicKey, initialValue);
            Timber.d("Decrypted measurement:  hex data: %s", byteInHex(data));
            if (  (type == AH100_NOTIFICATION_MEASUREMENT) ||
                  (type == AH100_NOTIFICATION_HISTORY_RECORD) )   {
                AHaddFatMeasurement(data);
            }
        } catch (Exception e) {
            Timber.d("Decrypting FAIL!!!");
        }
    }

    private void AHaddFatMeasurement(byte[] data) {
        if (data.length < 14) {
            Timber.d(":: AHaddFatMeasurement : data is too short. Expected at least 14 bytes of data." );
            return ;
        }
        byte userid     = data[0]; ///// Arrays.copyOfRange(data, 0, 0 );
        lastMeasuredWeight = Converters.fromUnsignedInt16Le(data, 1);
        float weight     = lastMeasuredWeight / 10.0f;
        float fat        = Converters.fromUnsignedInt16Le(data, 3) / 10.0f;
        int year       = Converters.fromUnsignedInt16Le(data, 5) ;
        int resistance = Converters.fromUnsignedInt16Le(data, 13) ;
        byte month       = (byte) (data[7] - 1);    // 1..12 to zero-based month
        byte dayInMonth  = data[8];
        byte hour        = data[9];
        byte minute      = data[10];
        byte second      = data[11];
        byte weekNumber  = data[12];
        Timber.d("---- measured   userid     %d",userid     );
        Timber.d("---- measured  weight      %f",weight     );
        Timber.d("---- measured  fat         %f",fat        );
        Timber.d("---- measured  resistance  %d",resistance );
        Timber.d("---- measured  year        %d",year       );
        Timber.d("---- measured  month       %d",month      );
        Timber.d("---- measured  dayInMonth  %d",dayInMonth );
        Timber.d("---- measured  hour        %d",hour       );
        Timber.d("---- measured  minute      %d",minute     );
        Timber.d("---- measured  second      %d",second     );
        Timber.d("---- measured  week day    %d",weekNumber );
        ///////////////////////////
        Calendar calendar = Calendar.getInstance();
        calendar.set( year, month, dayInMonth, hour, minute, second);
        Date date = calendar.getTime();

        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        ScaleMeasurement receivedMeasurement = new ScaleMeasurement();
        receivedMeasurement.setUserId(currentUser.getId());
        receivedMeasurement.setDateTime( date );
        receivedMeasurement.setWeight(weight);
        receivedMeasurement.setFat(fat);
//        receivedMeasurement.setWater(water);
//        receivedMeasurement.setMuscle(muscle);
//        receivedMeasurement.setBone(bone);
// todo: calculate water, muscle, bones
        addScaleMeasurement(receivedMeasurement);
    }


    private void startHeartBeat() {
        Timber.d("*** Heart beat started");
        beatHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("*** heart beat.");
                AHcmdHeartBeat();
            }
        }, 2000); // 2 s
    }

    private void resetHeartBeat() {
        Timber.d("*** 0 heart beat reset");
        beatHandler.removeCallbacksAndMessages(null);
        startHeartBeat();
    }

    private void stopHeartBeat() {
        Timber.d("*** ! heart beat stopped");
        beatHandler.removeCallbacksAndMessages(null);
    }


    private void AHsendCommand(byte cmd, byte[] payload ) {
        AHsendCommand(cmd, payload, payload.length );
    }

    private void AHsendCommand(byte cmd, byte[] payload, int len ) {
        resetHeartBeat();
        if (  (cmd == AH100_CMD_USER_INFO) )   {
            AHsendEncryptedCommand(cmd, payload, len);
            return;
        }
        byte[] packet ;
        byte[] header;
        header = new byte[]{(byte) (0xDB),
                (byte) (len + 1),
                cmd};
        packet = hexConcatenate( header, obfuscate(payload) );

        try {
            writeBytes(SERVICE_AH100_CUSTOM_SERVICE,
                    SERVICE_AH100_CUSTOM_SEND,
                    packet);
        } catch (Exception e) {
            Timber.d("AHsendCommand: CANNOT WRITE COMMAND");
            stopHeartBeat();
        }
    }


    private void AHsendEncryptedCommand(byte cmd, byte[] payload , int len ) {
        byte[] packet ;
        byte[] header;
        byte[] encrypted;
        Timber.d("AHsendEncryptedCommand: input data: %s", byteInHex(payload));

        encrypted = encryptAES(payload, magicKey, initialValue); //encryptAES
        header = new byte[]{(byte) (0xDC),
                (byte) (len + 0 ),
                cmd};
        packet = hexConcatenate( header, obfuscate(encrypted) );
        try {
            writeBytes(SERVICE_AH100_CUSTOM_SERVICE,
                    SERVICE_AH100_CUSTOM_SEND,
                    packet);
        } catch (Exception e) {
            Timber.d("AHsendEncryptedCommand: CANNOT WRITE COMMAND");
            stopHeartBeat();
        }
    }


    public byte[] getUserID() {
        ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
        byte id = (byte) currentUser.getId();
        byte[] auth = new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x00, id};
        auth[5] = xorChecksum(auth, 0, auth.length); // set xor of authorise code to 0x00
        return auth;
/////        return getfakeUserID();
    }

    public byte[] getfakeUserID() {
        String fid = "0f 00 43 06 7b 4e 7f"; // "c7b25de6bed0b7";
        byte[] auth =  hexToByteArray(fid) ;
        return auth;
    }


    public byte[] encryptAES(byte[] data, byte[] key, byte[] ivs) {
        Timber.d("Encoding   : input hex data: %s", byteInHex(data));
        Timber.d("Encoding   : encoding key  : %s", byteInHex(key));
        Timber.d("Encoding   : initial value : %s", byteInHex(ivs));
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
            byte[] finalIvs = new byte[16];
            int len = ivs.length > 16 ? 16 : ivs.length;
            System.arraycopy(ivs, 0, finalIvs, 0, len);
            IvParameterSpec ivps = new IvParameterSpec(finalIvs);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivps);
            return cipher.doFinal(data);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public byte[] decryptAES(byte[] data, byte[] key, byte[] ivs) {
        Timber.d("Decoding   : input hex data: %s", byteInHex(data));
        Timber.d("Decoding   : encoding key  : %s", byteInHex(key));
        Timber.d("Decoding   : initial value : %s", byteInHex(ivs));
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
            byte[] finalIvs = new byte[16];
            int len = ivs.length > 16 ? 16 : ivs.length;
            System.arraycopy(ivs, 0, finalIvs, 0, len);
            IvParameterSpec ivps = new IvParameterSpec(finalIvs);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivps);
            byte[] ret = cipher.doFinal(data);
            Timber.d("### decryptAES   :  hex data: %s", byteInHex(ret));
            return ret;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public byte[] hexToByteArray(String hexStr) {
        String hex = hexStr.replaceAll (" ","").replaceAll (":","");
        hex = hex.length()%2 != 0?"0"+hex:hex;

        byte[] b = new byte[hex.length() / 2];

        for (int i = 0; i < b.length; i++) {
            int index = i * 2;
            int v = Integer.parseInt(hex.substring(index, index + 2), 16);
            b[i] = (byte) v;
        }
        return b;
    }

    public byte[] hexConcatenate(byte[] A, byte[] B) {
        byte[] C = new byte[A.length + B.length];
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream( );
        try {
            outputStream.write( A );
            outputStream.write( B );
            C = outputStream.toByteArray( );
        } catch (IOException e) {
            e.printStackTrace();
        }
        return C;
    }

    public byte[] getPayload(byte[] data) {
        byte[] obfpayload = Arrays.copyOfRange(data, 3, data.length );
        byte[] payload = obfuscate(obfpayload);
        Timber.d("Deobfuscated payload: %s", byteInHex(payload));
        return payload;
    }


    private byte[] obfuscate(byte[] rawdata) {
        final byte[] data = Arrays.copyOfRange(rawdata, 0, rawdata.length );
        final byte[] MAC;
        MAC = getScaleMacAddress();
        Timber.d("Obfuscation: input hex data: %s", byteInHex(data));
        //Timber.d("Obfuscation:  MAC  hex data: %s", byteInHex(MAC));

        byte m = 0 ;
        for(int l=0; l< data.length; l++,m++){
            if (MAC.length <= m) { m = 0; }
            data[l] ^= MAC[m];
        }
        //Timber.d("Obfuscation:  out  hex data: %s", byteInHex(data));
        return data;
    }

}
