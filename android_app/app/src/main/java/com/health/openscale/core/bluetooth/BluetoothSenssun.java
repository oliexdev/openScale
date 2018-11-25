/* Copyright (C) 2018  Marco Gittler <marco@gitma.de>
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

package com.health.openscale.core.bluetooth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothSenssun extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1); // read, notify
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff2); // write only

    private int gotData;
    private int FatMus=0;
    private ScaleMeasurement measurement;

    public BluetoothSenssun(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Senssun";
    }

    private void sendUserData(){
      final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();

      byte gender = selectedUser.getGender().isMale() ? (byte)0x01 : (byte)0xf1; // 00 - male; 01 - female
      byte height = (byte)(((int)selectedUser.getBodyHeight()) & 0xff); // cm
      byte age = (byte)(selectedUser.getAge() & 0xff);

      int userId = selectedUser.getId();

      Timber.d("Request Saved User Measurements ");
      byte cmdByte[] = {(byte)0xa5,(byte)0x10,gender,age,height,(byte)0,(byte)0x0,(byte)0x0d2,(byte)0x00};

      byte verify = 0;
        for(int i=1;i<cmdByte.length-2;i++){
          verify=(byte) (verify+cmdByte[i]);
        }
      cmdByte[cmdByte.length-2]=verify;
      writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, cmdByte);
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        Timber.d("Cmd Clean %d",stateNr);
        switch (stateNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC,
                        BluetoothGattUuid.DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION);
                sendUserData();
                gotData=0;
                break;
            case 1:
            //wait for answer
              break;
            default:
              // Finish init if everything is done
                return false;
        }
        return true;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
      switch (stateNr) {
          case 0:
          case 1:
          case 2:
          case 3:
          case 4:
          case 5:
              sendUserData();

              break;

          default:
              return false;
      }
      return true;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
      return false;
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        // The first notification only includes weight and all other fields are
        // either 0x00 (user info) or 0xff (fat, water, etc.)

        if (data != null  ) {
            parseBytes(data);
            if (measurement!=null && measurement.getWeight()!=0.0 && gotData==0){
                    Timber.d("meas: %s",measurement.toString());
              addScaleData(measurement);
              gotData=1;
              nextMachineStateStep();
            }
            if (measurement!=null && measurement.getWeight()!=0.0 && FatMus==0x03 &&  gotData!=2){
                    Timber.d("meas: %s",measurement.toString());
              addScaleData(measurement);
              gotData=2;

            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
      if (measurement==null){
        measurement= new ScaleMeasurement();
      }
      int type=(int)weightBytes[6]&0xff;
      Timber.d("type %02X",type);
      switch (type) {
        case 0xaa:
          float weight = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // kg
          measurement.setWeight(weight);
          break;
        case 0xb0:
          float fat = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // %
          float water = Converters.fromUnsignedInt16Be(weightBytes, 4) / 10.0f; // %
          measurement.setFat(fat);
          measurement.setWater(water);
          FatMus|=0x2;
          break;
        case 0xc0:
          float bone = Converters.fromUnsignedInt16Le(weightBytes, 4) / 10.0f; // kg
          float muscle = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // %
          measurement.setMuscle(muscle);
          measurement.setBone(bone);
          FatMus|=0x1;
          break;
        case 0xd0:
          float calorie = Converters.fromUnsignedInt16Be(weightBytes, 2);
          break;
        case 0xe0:
          break;
        case 0xe1:
          break;
        case 0xe2:
          //date
          break;
        default:

      }

        //measurement.setDateTime(lastWeighted);
        measurement.setDateTime(new Date());


    }
}
