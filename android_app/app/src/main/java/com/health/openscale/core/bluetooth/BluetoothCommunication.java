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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.jakewharton.rx.ReplayingShare;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.exceptions.BleException;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.io.IOException;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.UUID;

import androidx.core.content.ContextCompat;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;

import static android.content.Context.LOCATION_SERVICE;

public abstract class BluetoothCommunication {
    public enum BT_STATUS {
        RETRIEVE_SCALE_DATA,
        INIT_PROCESS,
        CONNECTION_RETRYING,
        CONNECTION_ESTABLISHED,
        CONNECTION_DISCONNECT,
        CONNECTION_LOST,
        NO_DEVICE_FOUND,
        UNEXPECTED_ERROR,
        SCALE_MESSAGE
    }

    private enum BT_ACTIONS {
        WRITE,
        READ,
        NOTIFICATION,
        INDICATION,
        DISCOVER,
        DISCONNECT,
        STOP,
        RESUME,
        JUMP,
        FINISH;

        public String toString() {
            switch (this) {
                case WRITE:
                    return "WRITE";
                case READ:
                    return "READ";
                case NOTIFICATION:
                    return "NOTIFICATION";
                case INDICATION:
                    return "INDICATION";
                case DISCOVER:
                    return "DISCOVER";
                case DISCONNECT:
                    return "DISCONNECT";
                case STOP:
                    return "STOP";
                case RESUME:
                    return "RESUME";
                case JUMP:
                    return "JUMP";
                case FINISH:
                    return "FINISH";
            }

            return "";
        }
    }

    private class BluetoothObject {
        public BT_ACTIONS action;
        public UUID characteristic;
        public byte[] bytes;
        public int nr;
    }

    private LinkedList<BluetoothObject> btQueue;
    private int stepNr;
    private boolean stopped;

    protected Context context;

    private final int BT_RETRY_TIMES_ON_ERROR = 3;

    private RxBleClient bleClient;
    private RxBleDevice bleDevice;
    private Observable<RxBleConnection> connectionObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable scanSubscription;
    private PublishSubject<Boolean> disconnectTriggerSubject = PublishSubject.create();

    private Handler callbackBtHandler;
    private Handler disconnectHandler;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        this.bleClient = OpenScale.getInstance().getBleClient();
        this.scanSubscription = null;
        this.disconnectHandler = new Handler();
        this.btQueue = new LinkedList<BluetoothObject>();
        this.stepNr = 0;
        this.stopped = false;

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
     * Register a callback Bluetooth handler that notify any BT_STATUS changes for GUI/CORE.
     *
     * @param cbBtHandler a handler that is registered
     */
    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param status the status code that should be set
     */
    protected void setBluetoothStatus(BT_STATUS status) {
        setBluetoothStatus(status, "");
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     * @param infoText the information text that is displayed to the status code.
     */
    protected void setBluetoothStatus(BT_STATUS statusCode, String infoText) {
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
    protected void addScaleMeasurement(ScaleMeasurement scaleMeasurement) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS.RETRIEVE_SCALE_DATA.ordinal(), scaleMeasurement).sendToTarget();
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
                    BT_STATUS.SCALE_MESSAGE.ordinal(), msg, 0, value).sendToTarget();
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
     * @param stepNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean onNextStep(int stepNr);

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
     * Method is triggered if a Bluetooth services from a device is discovered.
     *
     * @param rxBleDeviceServices
     */
    protected void onBluetoothDiscovery(RxBleDeviceServices rxBleDeviceServices) { }

    protected void writeBytes(UUID characteristic, byte[] command) {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.WRITE;
        btObject.characteristic = characteristic;
        btObject.bytes = command;

        btQueue.add(btObject);
    }

    protected void setNotificationOn(UUID characteristic) {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.NOTIFICATION;
        btObject.characteristic = characteristic;

        btQueue.add(btObject);
    }

    protected void setIndicationOn(UUID characteristic) {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.INDICATION;
        btObject.characteristic = characteristic;

        btQueue.add(btObject);
    }

    protected void readBytes(UUID characteristic) {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.READ;
        btObject.characteristic = characteristic;

        btQueue.add(btObject);
    }

    protected void discoverBluetoothServices() {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.DISCOVER;

        btQueue.add(btObject);
    }

    protected void disconnect() {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.DISCONNECT;
        btObject.nr = stepNr;

        btQueue.add(btObject);
    }

    protected void stopMachineState() {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.STOP;
        btObject.nr = stepNr;

        btQueue.add(btObject);
    }

    protected void resumeMachineState() {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.RESUME;
        btObject.nr = stepNr;

        btQueue.addFirst(btObject);
        processBtQueue();
    }

    protected void finishMachineState() {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.FINISH;

        btQueue.add(btObject);
    }

    protected void jumpToStepNr(int nr) {
        BluetoothObject btObject = new BluetoothObject();
        btObject.action = BT_ACTIONS.JUMP;
        btObject.nr = nr;

        btQueue.add(btObject);
    }

    /**
     * Write a byte array to a Bluetooth device.
     *  @param characteristic the Bluetooth UUID characteristic
     * @param bytes the bytes that should be write
     */
    private void doWriteBytes(UUID characteristic, byte[] bytes) {
        final Disposable disposable = connectionObservable
                .flatMapSingle(rxBleConnection -> rxBleConnection.writeCharacteristic(characteristic, bytes))
                .observeOn(AndroidSchedulers.mainThread())
                .retry(BT_RETRY_TIMES_ON_ERROR)
                .subscribe(
                        value -> {
                            Timber.d("Write characteristic %s: %s",
                                    BluetoothGattUuid.prettyPrint(characteristic),
                                    byteInHex(value));
                            processBtQueue();
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposable);
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothRead() will be triggered if read command was successful. nextMachineStep() needs to manually called!
     *@param characteristic the Bluetooth UUID characteristic
     */
    private void doReadBytes(UUID characteristic) {
        final Disposable disposable = connectionObservable
                .firstOrError()
                .flatMap(rxBleConnection -> rxBleConnection.readCharacteristic(characteristic))
                .observeOn(AndroidSchedulers.mainThread())
                .retry(BT_RETRY_TIMES_ON_ERROR)
                .subscribe(bytes -> {
                    Timber.d("Read characteristic %s", BluetoothGattUuid.prettyPrint(characteristic));
                    onBluetoothRead(characteristic, bytes);
                    processBtQueue();
                },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposable);
    }

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    private void doSetIndicationOn(UUID characteristic) {
        final Disposable disposable = connectionObservable
                .flatMap(rxBleConnection -> rxBleConnection.setupIndication(characteristic))
                .doOnNext(notificationObservable -> {
                            Timber.d("Successful set indication on for %s", BluetoothGattUuid.prettyPrint(characteristic));
                            processBtQueue();
                        }
                )
                .flatMap(indicationObservable -> indicationObservable)
                .observeOn(AndroidSchedulers.mainThread())
                .retry(BT_RETRY_TIMES_ON_ERROR)
                .subscribe(
                        bytes -> {
                            Timber.d("onCharacteristicChanged %s: %s",
                                    BluetoothGattUuid.prettyPrint(characteristic),
                                    byteInHex(bytes));
                            onBluetoothNotify(characteristic, bytes);
                            resetDisconnectTimer();
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposable);
    }

    /**
     * Set notification flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    private void doSetNotificationOn(UUID characteristic) {
        final Disposable disposable = connectionObservable
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(characteristic))
                .doOnNext(notificationObservable -> {
                            Timber.d("Successful set notification on for %s", BluetoothGattUuid.prettyPrint(characteristic));
                            processBtQueue();
                        }
                )
                .flatMap(notificationObservable -> notificationObservable)
                .observeOn(AndroidSchedulers.mainThread())
                .retry(BT_RETRY_TIMES_ON_ERROR)
                .subscribe(
                        bytes -> {
                            Timber.d("onCharacteristicChanged %s: %s",
                                    BluetoothGattUuid.prettyPrint(characteristic),
                                    byteInHex(bytes));
                            onBluetoothNotify(characteristic, bytes);
                            resetDisconnectTimer();
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposable);
    }

    private void doBluetoothDiscoverServices() {
        final Disposable connectionDisposable = connectionObservable
                .flatMapSingle(RxBleConnection::discoverServices)
                .observeOn(AndroidSchedulers.mainThread())
                .retry(BT_RETRY_TIMES_ON_ERROR)
                .subscribe(
                        deviceServices -> {
                            Timber.d("Successful Bluetooth services discovered");
                            onBluetoothDiscovery(deviceServices);
                            processBtQueue();
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(connectionDisposable);
    }

    /**
     * Disconnect from a Bluetooth device
     */
    public void doDisconnect() {
        Timber.d("Bluetooth disconnect");
        setBluetoothStatus(BT_STATUS.CONNECTION_DISCONNECT);
        if (scanSubscription != null) {
            scanSubscription.dispose();
        }
        callbackBtHandler = null;
        disconnectHandler.removeCallbacksAndMessages(null);
        disconnectTriggerSubject.onNext(true);
        compositeDisposable.clear();
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
        bleDevice = bleClient.getBleDevice(macAddress);

        // Running an LE scan during connect improves connectivity on some phones
        // (e.g. Sony Xperia Z5 compact, Android 7.1.1). For some scales (e.g. Medisana BS444)
        // it seems to be a requirement that the scale is discovered before connecting to it.
        // Otherwise the connection almost never succeeds.
        LocationManager locationManager = (LocationManager)context.getSystemService(LOCATION_SERVICE);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        ) {
            Timber.d("Do LE scan before connecting to device");
            disconnectWithDelay();
            scanSubscription = bleClient.scanBleDevices(
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            //.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .build()
            )
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(bleScanResult -> {
                        if (bleScanResult.getBleDevice().getMacAddress().equals(macAddress)) {
                            connectToDevice(macAddress);
                    }}, throwable -> setBluetoothStatus(BT_STATUS.NO_DEVICE_FOUND));
        }
        else {
            Timber.d("No coarse location permission, connecting without LE scan");
            connectToDevice(macAddress);
        }
    }

    private void connectToDevice(String macAddress) {

        // stop LE scan before connecting to device
        if (scanSubscription != null) {
            Timber.d("Stop Le san");
            scanSubscription.dispose();
            scanSubscription = null;

        }

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("Try to connect to BLE device " + macAddress);

                connectionObservable = bleDevice
                        .establishConnection(false)
                        .takeUntil(disconnectTriggerSubject)
                        .doOnError(throwable -> setBluetoothStatus(BT_STATUS.CONNECTION_RETRYING))
                        .observeOn(AndroidSchedulers.mainThread())
                        .compose(ReplayingShare.instance());

                if (isConnected()) {
                    doDisconnect();
                } else {
                    btQueue.clear();
                    stepNr = 0;

                    setBtMonitoringOn();
                    nextMachineStep();
                    resetDisconnectTimer();
                }
            }
        }, 500);
    }

    private void setBtMonitoringOn() {
        final Disposable disposableConnectionState = bleDevice.observeConnectionStateChanges()
                .subscribe(
                        connectionState -> {
                            switch (connectionState) {
                                case CONNECTED:
                                    setBluetoothStatus(BT_STATUS.CONNECTION_ESTABLISHED);
                                    break;
                                case CONNECTING:
                                    // empty
                                    break;
                                case DISCONNECTING:
                                    // empty
                                    break;
                                case DISCONNECTED:
                                    // setBluetoothStatus(BT_STATUS.CONNECTION_LOST);
                                    break;
                            }
                        },
                        throwable -> onError(throwable)
                );

        compositeDisposable.add(disposableConnectionState);
    }

    private String getQueueListAsString() {
        String str = new String();
        str += "[";
        for (BluetoothObject btObject : btQueue) {
            str += btObject.action;

            if (btObject != btQueue.getLast()) {
                str += ", ";
            }
        }
        str += "]";

        return str;
    }

    private void onError(Throwable throwable) {
        setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, throwable.getMessage());
    }

    private boolean isConnected() {
        return bleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED;
    }

    private void resetDisconnectTimer() {
        disconnectHandler.removeCallbacksAndMessages(null);
        disconnectWithDelay();
    }

    private void disconnectWithDelay() {
        disconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("Timeout Bluetooth disconnect");
                doDisconnect();
            }
        }, 60000); // 60s timeout
    }

    private synchronized void nextMachineStep() {
        if (btQueue.isEmpty() && !stopped) {
            if (onNextStep(stepNr)) {
                if (!btQueue.isEmpty()) {
                    Timber.d("Step Nr " + stepNr);
                    Timber.d("Bt queue list " + getQueueListAsString());
                    processBtQueue();
                } else {
                    Timber.d("Empty bt queue list for step nr " + stepNr);
                }
                stepNr++;
            } else {
                finishMachineState();
            }
        } else {
            processBtQueue();
        }
    }

    private synchronized void processBtQueue() {
        if (!btQueue.isEmpty()) {
            if (stopped && btQueue.getFirst().action != BT_ACTIONS.RESUME) {
                return;
            }

            Timber.d("Process bt object " + btQueue.getFirst().action + " from list " + getQueueListAsString());

            BluetoothObject lastbtObject = btQueue.pop();

            switch(lastbtObject.action) {
                case NOTIFICATION:
                    Timber.d("Call bt object notify for UUID " + BluetoothGattUuid.prettyPrint(lastbtObject.characteristic));
                    doSetNotificationOn(lastbtObject.characteristic);
                    break;
                case INDICATION:
                    Timber.d("Call bt object indication for UUID " + BluetoothGattUuid.prettyPrint(lastbtObject.characteristic));
                    doSetIndicationOn(lastbtObject.characteristic);
                    break;
                case READ:
                    Timber.d("Call bt object read on UUID " + BluetoothGattUuid.prettyPrint(lastbtObject.characteristic));
                    doReadBytes(lastbtObject.characteristic);
                    break;
                case WRITE:
                    Timber.d("Call bt object write " + byteInHex(lastbtObject.bytes) + " on UUID " + BluetoothGattUuid.prettyPrint(lastbtObject.characteristic));
                    doWriteBytes(lastbtObject.characteristic, lastbtObject.bytes);
                    break;
                case DISCOVER:
                    Timber.d("Call bt object discover bluetooth services");
                    doBluetoothDiscoverServices();
                    break;
                case DISCONNECT:
                    Timber.d("Call bt object disconnect on step nr " + lastbtObject.nr);
                    doDisconnect();
                    break;
                case STOP:
                    Timber.d("Call bt object stop on step nr " + lastbtObject.nr);
                    stopped = true;
                    break;
                case RESUME:
                    if (stopped) {
                        Timber.d("Call bt object resume on step nr " + (lastbtObject.nr-1));
                        stopped = false;
                        processBtQueue();
                    } else {
                        Timber.w("warning resume called without stopping the machine state");
                        processBtQueue();
                    }
                    break;
                case JUMP:
                    Timber.d("Call bt object jump to step nr " + lastbtObject.nr);
                    stepNr = lastbtObject.nr;
                    processBtQueue();
                    break;
                case FINISH:
                    Timber.d("Call bt object finish");
                    disconnectWithDelay();

                    if (!btQueue.isEmpty()) {
                        processBtQueue();
                    }
                    break;
                default:
                    Timber.e("No valid Bluetooth action called");
                    break;
            }
        } else {
            nextMachineStep();
        }
    }
}
