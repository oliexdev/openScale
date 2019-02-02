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

package com.health.openscale.core.bluetooth;

import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.jakewharton.rx.ReplayingShare;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.exceptions.BleException;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;

public abstract class BluetoothCommunication {
    public enum BT_STATUS_CODE {
        BT_RETRIEVE_SCALE_DATA,
        BT_INIT_PROCESS,
        BT_CONNECTION_RETRYING,
        BT_CONNECTION_ESTABLISHED,
        BT_CONNECTION_DISCONNECT,
        BT_CONNECTION_LOST,
        BT_NO_DEVICE_FOUND,
        BT_UNEXPECTED_ERROR,
        BT_SCALE_MESSAGE
    }

    public enum BT_MACHINE_STATE {
        BT_INIT_STATE,
        BT_CMD_STATE,
        BT_CLEANUP_STATE,
        BT_STOPPED_STATE
    }

    private final int BT_RETRY_TIMES_ON_ERROR = 3;
    private final int BT_DELAY = 50; // MS

    protected Context context;

    private RxBleClient bleClient;
    private RxBleDevice bleDevice;
    private Observable<RxBleConnection> connectionObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PublishSubject<Boolean> disconnectTriggerSubject = PublishSubject.create();

    private Handler callbackBtHandler;
    private RxBleDeviceServices rxBleDeviceServices;

    private int cmdStepNr;
    private int initStepNr;
    private int cleanupStepNr;
    private BT_MACHINE_STATE btMachineState;
    private BT_MACHINE_STATE btStopppedMachineState;

    private Handler disconnectHandler;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        this.bleClient = OpenScale.getInstance().getBleClient();
        this.rxBleDeviceServices = null;
        this.disconnectHandler = new Handler();

        RxJavaPlugins.setErrorHandler(e -> {
            if (e instanceof UndeliverableException && e.getCause() instanceof BleException) {
                return; // ignore BleExceptions as they were surely delivered at least once
            }
            if (e instanceof UndeliverableException) {
                onError(e);
            }
            if ((e instanceof IOException) || (e instanceof SocketException)) {
                // fine, irrelevant network problem or API that throws on cancellation
                return;
            }
            if (e instanceof InterruptedException) {
                // fine, some blocking code was interrupted by a dispose call
                return;
            }
            if ((e instanceof NullPointerException) || (e instanceof IllegalArgumentException)) {
                // that's likely a bug in the application
                onError(e);
                return;
            }
            if (e instanceof IllegalStateException) {
                // that's a bug in RxJava or in a custom operator
                onError(e);
                return;
            }
            onError(e);
        });
    }

    /**
     * Register a callback Bluetooth handler that notify any BT_STATUS_CODE changes for GUI/CORE.
     *
     * @param cbBtHandler a handler that is registered
     */
    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     */
    protected void setBtStatus(BT_STATUS_CODE statusCode) {
        setBtStatus(statusCode, "");
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     * @param infoText the information text that is displayed to the status code.
     */
    protected void setBtStatus(BT_STATUS_CODE statusCode, String infoText) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    statusCode.ordinal(), infoText).sendToTarget();
        }
    }

    /**
     * Add a new scale data to openScale
     *
     * @param scaleMeasurement the scale data that should be added to openScale
     */
    protected void addScaleData(ScaleMeasurement scaleMeasurement) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS_CODE.BT_RETRIEVE_SCALE_DATA.ordinal(), scaleMeasurement).sendToTarget();
        }
    }

    /**
     * Send message to openScale user
     *
     * @param msg the string id to be send
     * @param value the value to be used
     */
    protected void sendMessage(int msg, Object value) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS_CODE.BT_SCALE_MESSAGE.ordinal(), msg, 0, value).sendToTarget();
        }
    }

    /**
     * Return the Bluetooth driver name
     *
     * @return a string in a human readable name
     */
    abstract public String driverName();

    /**
     * State machine for the initialization process of the Bluetooth device.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextInitCmd(int stateNr);

    /**
     * State machine for the normal/command process of the Bluetooth device.
     *
     * This state machine is automatically triggered if initialization process is finished.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextBluetoothCmd(int stateNr);

    /**
     * Step the current machine state one step back. Needs to be called before a command
     */
    protected void repeatMachineStateStep() {
        switch (btMachineState) {
            case BT_INIT_STATE:
                initStepNr = initStepNr - 1;
                break;
            case BT_CMD_STATE:
                cmdStepNr = cmdStepNr - 1;
                break;
            case BT_CLEANUP_STATE:
                cleanupStepNr = cleanupStepNr - 1;
                break;
        }
    }

    /**
     * Stopped the current machine state
     */
    protected void stopMachineState() {
        Timber.d("Machine state stopped");
        btStopppedMachineState = btMachineState;
        btMachineState = BT_MACHINE_STATE.BT_STOPPED_STATE;
    }

    /**
     * Resumed the current machine state
     */
    protected void resumeMachineState() {
        Timber.d("Machine state resumed");
        btMachineState = btStopppedMachineState;

        nextMachineStateStep();
    }

    /**
     * State machine for the clean up process for the Bluetooth device.
     *
     * This state machine is *not* automatically triggered. You have to setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE) to trigger this process if necessary.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextCleanUpCmd(int stateNr);

    /**
     * Method is triggered if a Bluetooth data is read from a device.
     *
     * @param characteristic
     * @param value
     */
    protected void onBluetoothRead(UUID characteristic, byte[] value) {}

    /**
     * Method is triggered if a Bluetooth data from a device is notified or indicated.
     *
     * @param characteristic
     * @param value the Bluetooth characteristic
     */
    protected void onBluetoothNotify(UUID characteristic, byte[] value) {}

    /**
     * Set the Bluetooth machine state to a specific state.
     *
     * @note after setting a new state the next step is automatically triggered.
     *
     * @param btMachineState the machine state that should be set.
     */
    protected void setBtMachineState(BT_MACHINE_STATE btMachineState) {
        this.btMachineState = btMachineState;

        nextMachineStateStep();
    }

    /**
     * Write a byte array to a Bluetooth device.
     *  @param characteristic the Bluetooth UUID characteristic
     * @param bytes the bytes that should be write
     */
    protected void writeBytes(UUID characteristic, byte[] bytes) {
        if (isConnected()) {
            final Disposable disposable = connectionObservable
                    .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                    .flatMapSingle(rxBleConnection -> rxBleConnection.writeCharacteristic(characteristic, bytes))
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(BT_RETRY_TIMES_ON_ERROR)
                    .subscribe(
                            value -> {
                                Timber.d("Write characteristic %s: %s",
                                        BluetoothGattUuid.prettyPrint(characteristic),
                                        byteInHex(value));
                                nextMachineStateStep();
                            },
                            throwable -> onError(throwable)
                    );

            compositeDisposable.add(disposable);
        }
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothRead() will be triggered if read command was successful. nextMachineStep() needs to manually called!
     *@param characteristic the Bluetooth UUID characteristic
     */
    protected void readBytes(UUID characteristic) {
        if (isConnected()) {
            final Disposable disposable = connectionObservable
                    .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                    .firstOrError()
                    .flatMap(rxBleConnection -> rxBleConnection.readCharacteristic(characteristic))
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(BT_RETRY_TIMES_ON_ERROR)
                    .subscribe(bytes -> {
                        Timber.d("Read characteristic %s", BluetoothGattUuid.prettyPrint(characteristic));
                        onBluetoothRead(characteristic, bytes);
                    },
                            throwable -> onError(throwable)
                    );

            compositeDisposable.add(disposable);
        }
    }

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setIndicationOn(UUID characteristic) {
        if (isConnected()) {
            final Disposable disposable = connectionObservable
                    .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                    .flatMap(rxBleConnection -> rxBleConnection.setupIndication(characteristic))
                    .doOnNext(notificationObservable -> {
                                Timber.d("Successful set indication on for %s", BluetoothGattUuid.prettyPrint(characteristic));
                                nextMachineStateStep();
                            }
                    )
                    .flatMap(indicationObservable -> indicationObservable)
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(BT_RETRY_TIMES_ON_ERROR)
                    .subscribe(
                            bytes -> {
                                onBluetoothNotify(characteristic, bytes);
                                Timber.d("onCharacteristicChanged %s: %s",
                                        BluetoothGattUuid.prettyPrint(characteristic),
                                        byteInHex(bytes));
                                resetDisconnectTimer();
                            },
                            throwable -> onError(throwable)
                    );

            compositeDisposable.add(disposable);
        }
    }

    /**
     * Set notification flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setNotificationOn(UUID characteristic) {
        if (isConnected()) {
            final Disposable disposable = connectionObservable
                    .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                    .flatMap(rxBleConnection -> rxBleConnection.setupNotification(characteristic))
                    .doOnNext(notificationObservable -> {
                                Timber.d("Successful set notification on for %s", BluetoothGattUuid.prettyPrint(characteristic));
                                nextMachineStateStep();
                            }
                    )
                    .flatMap(notificationObservable -> notificationObservable)
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(BT_RETRY_TIMES_ON_ERROR)
                    .subscribe(
                            bytes -> {
                                onBluetoothNotify(characteristic, bytes);
                                Timber.d("onCharacteristicChanged %s: %s",
                                        BluetoothGattUuid.prettyPrint(characteristic),
                                        byteInHex(bytes));
                                resetDisconnectTimer();
                            },
                            throwable -> onError(throwable)
                    );

            compositeDisposable.add(disposable);
        }
    }

    /**
     * Convert a byte array to hex for debugging purpose
     *
     * @param data data we want to make human-readable (hex)
     * @return a human-readable string representing the content of 'data'
     */
    protected String byteInHex(byte[] data) {
        if (data == null) {
            Timber.e("Data is null");
            return "";
        }

        if (data.length == 0) {
            return "";
        }

        final StringBuilder stringBuilder = new StringBuilder(3 * data.length);
        for (byte byteChar : data) {
            stringBuilder.append(String.format("%02X ", byteChar));
        }

        return stringBuilder.substring(0, stringBuilder.length() - 1);
    }

    protected float clamp(double value, double min, double max) {
        if (value < min) {
            return (float)min;
        }
        if (value > max) {
            return (float)max;
        }
        return (float)value;
    }

    protected byte xorChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = offset; i < offset + length; ++i) {
            checksum ^= data[i];
        }
        return checksum;
    }

    protected List<BluetoothGattService> getBluetoothGattServices() {
        if (rxBleDeviceServices == null) {
            return new ArrayList<>();
        }

        return rxBleDeviceServices.getBluetoothGattServices();
    }

    /**
     * Test in a byte if a bit is set (1) or not (0)
     *
     * @param value byte which is tested
     * @param bit bit position which is tested
     * @return true if bit is set (1) otherwise false (0)
     */
    protected boolean isBitSet(byte value, int bit) {
        return (value & (1 << bit)) != 0;
    }

    /**
     * Connect to a Bluetooth device.
     *
     * On successfully connection Bluetooth machine state is automatically triggered.
     * If the device is not found the process is automatically stopped.
     *
     * @param macAddress the Bluetooth address to connect to
     */
    public void connect(String macAddress) {
        Timber.d("Try to connect to BLE device " + macAddress);

        bleDevice = bleClient.getBleDevice(macAddress);

        connectionObservable = bleDevice
                .establishConnection(false)
                .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                .takeUntil(disconnectTriggerSubject)
                .doOnError(throwable -> setBtStatus(BT_STATUS_CODE.BT_CONNECTION_RETRYING))
                .observeOn(AndroidSchedulers.mainThread())
                .compose(ReplayingShare.instance());

       if (isConnected()) {
           disconnect();
       } else {
            final Disposable connectionDisposable = connectionObservable
                    .delay(BT_DELAY, TimeUnit.MILLISECONDS)
                    .flatMapSingle(RxBleConnection::discoverServices)
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(BT_RETRY_TIMES_ON_ERROR)
                    .subscribe(
                            deviceServices -> {
                                rxBleDeviceServices = deviceServices;
                                //setBtMonitoringOn();

                                initStepNr = -1;
                                cmdStepNr = -1;
                                cleanupStepNr = -1;

                                setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);
                                setBtMachineState(BT_MACHINE_STATE.BT_INIT_STATE);
                            },
                            throwable -> {
                                setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
                                disconnect();
                            }
                    );

            compositeDisposable.add(connectionDisposable);
        }
    }

    private void setBtMonitoringOn() {
        final Disposable disposableConnectionState = bleDevice.observeConnectionStateChanges()
                .subscribe(
                        connectionState -> {
                            switch (connectionState) {
                                case CONNECTED:
                                    setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);
                                    break;
                                case CONNECTING:
                                    // empty
                                    break;
                                case DISCONNECTING:
                                    // empty
                                    break;
                                case DISCONNECTED:
                                    setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
                                    break;
                            }
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposableConnectionState);
    }

    private void onError(Throwable throwable) {
        setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, throwable.getMessage());
    }

    private boolean isConnected() {
        return bleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED;
    }

    public void resetDisconnectTimer() {
        disconnectHandler.removeCallbacksAndMessages(null);
        disconnectWithDelay();
    }

    public void disconnectWithDelay() {
        disconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("Timeout disconnect");
                disconnect();
            }
        }, 60000); // 60s timeout
    }

    /**
     * Disconnect from a Bluetooth device
     */
    public void disconnect() {
        setBtStatus(BT_STATUS_CODE.BT_CONNECTION_DISCONNECT);
        callbackBtHandler = null;
        disconnectHandler.removeCallbacksAndMessages(null);
        disconnectTriggerSubject.onNext(true);
        compositeDisposable.clear();
    }

    /**
     * Invoke next step for internal Bluetooth state machine.
     */
    protected void nextMachineStateStep() {
        switch (btMachineState) {
            case BT_INIT_STATE:
                initStepNr++;
                Timber.d("INIT STATE: %d", initStepNr);
                if (!nextInitCmd(initStepNr)) {
                    setBtMachineState(BT_MACHINE_STATE.BT_CMD_STATE);
                }
                break;
            case BT_CMD_STATE:
                cmdStepNr++;
                Timber.d("CMD STATE: %d", cmdStepNr);
                if (!nextBluetoothCmd(cmdStepNr)) {
                    disconnectWithDelay();
                }
                break;
            case BT_CLEANUP_STATE:
                cleanupStepNr++;
                Timber.d("CLEANUP STATE: %d", cleanupStepNr);
                if (!nextCleanUpCmd(cleanupStepNr)) {
                    disconnect();
                }
                break;
        }
    }
}
