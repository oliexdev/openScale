/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.health.openscale.R;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only Wi-Fi provisioning; credentials stay in memory and are never logged. */
public class Hs5SetupActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private volatile BluetoothSocket socket;
    private Future<?> task;
    private volatile boolean canceled;
    private Spinner deviceChoice, security;
    private EditText ssid, password;
    private TextView log;
    private Button check, send, refresh, cancel;
    private final int[] securityCodes = {3, 4, 2, 1, 0};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.hs5_title);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        setContentView(scroll);
        TextView intro = new TextView(this);
        intro.setText(R.string.hs5_intro);
        body.addView(intro);
        Button measurements = new Button(this);
        measurements.setText("Collect HS5 measurements over Wi-Fi");
        measurements.setOnClickListener(v -> startActivity(new Intent(this, Hs5MeasurementActivity.class)));
        body.addView(measurements);
        deviceChoice = new Spinner(this);
        body.addView(deviceChoice);
        refresh = button(body, R.string.hs5_refresh, v -> refreshDevices());
        button(body, R.string.hs5_pair, v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        ssid = field(body, R.string.hs5_ssid, false);
        password = field(body, R.string.hs5_password, true);
        security = new Spinner(this);
        security.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.hs5_security_modes)));
        body.addView(security);
        check = button(body, R.string.hs5_check, v -> start(false));
        send = button(body, R.string.hs5_send, v -> start(true));
        cancel = button(body, R.string.hs5_cancel, v -> cancelSession());
        cancel.setEnabled(false);
        log = new TextView(this);
        log.setTextIsSelectable(true);
        body.addView(log);
        refreshDevices();
    }

    private Button button(LinearLayout body, int label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        body.addView(b);
        return b;
    }

    private EditText field(LinearLayout body, int hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(field);
        return field;
    }

    private boolean permitted() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
            return true;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
        return false;
    }

    private void refreshDevices() {
        if (!permitted()) return;
        devices.clear();
        List<String> labels = new ArrayList<>();
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status(getString(R.string.hs5_enable_bluetooth));
        } else {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("IHEALTH HS5") && !upper.startsWith("IHEALTH HS5S")) {
                    devices.add(d);
                    labels.add(name + " · " + d.getAddress());
                }
            }
        }
        if (labels.isEmpty()) labels.add(getString(R.string.hs5_no_device));
        deviceChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        status(getString(R.string.hs5_found, devices.size()));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length == 2 && results[0] == PackageManager.PERMISSION_GRANTED
                && results[1] == PackageManager.PERMISSION_GRANTED) refreshDevices();
        else status(getString(R.string.hs5_permissions));
    }

    private void start(boolean configure) {
        if (!permitted()) return;
        if (task != null && !task.isDone()) return;
        if (devices.isEmpty()) { status(getString(R.string.hs5_no_device)); return; }
        final byte[] payload;
        try {
            payload = configure ? Hs5Protocol.credentials(ssid.getText().toString(),
                    password.getText().toString(), securityCodes[security.getSelectedItemPosition()]) : null;
        } catch (IllegalArgumentException error) { status(error.getMessage()); return; }
        BluetoothDevice device = devices.get(deviceChoice.getSelectedItemPosition());
        canceled = false;
        busy(true);
        status(getString(R.string.hs5_connecting, device.getName()));
        task = worker.submit(() -> {
            BluetoothSocket connection = null;
            try {
                getSystemService(BluetoothManager.class).getAdapter().cancelDiscovery();
                try {
                    connection = connectOnce(device, false);
                } catch (IOException firstAttempt) {
                    if (canceled) throw firstAttempt;
                    status(getString(R.string.hs5_retry_legacy));
                    connection = connectOnce(device, true);
                }
                Hs5Session session = new Hs5Session(connection.getInputStream(), connection.getOutputStream(), this::status);
                if (configure) status(session.provision(payload));
                else session.probe();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException | SecurityException error) {
                if (!canceled) status(getString(R.string.hs5_connection_error, error.getMessage()));
            } finally {
                if (payload != null) Arrays.fill(payload, (byte) 0);
                if (connection != null) try { connection.close(); } catch (IOException ignored) {}
                socket = null;
                main.post(() -> {
                    if (configure) password.setText("");
                    busy(false);
                });
            }
        });
    }

    private BluetoothSocket connectOnce(BluetoothDevice device, boolean legacy)
            throws IOException, InterruptedException {
        // The vendor SDK tries secure SPP, then this public compatibility API
        // for an already bonded device. No fixed channel numbers are guessed.
        BluetoothSocket connection = legacy
                ? device.createInsecureRfcommSocketToServiceRecord(SPP)
                : device.createRfcommSocketToServiceRecord(SPP);
        socket = connection;
        AtomicBoolean connected = new AtomicBoolean();
        Runnable deadline = () -> {
            if (!connected.get()) {
                status(getString(R.string.hs5_connect_timeout));
                try { connection.close(); } catch (IOException ignored) {}
            }
        };
        try {
            if (canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            main.postDelayed(deadline, 15000);
            connection.connect();
            connected.set(true);
            return connection;
        } finally {
            main.removeCallbacks(deadline);
            if (!connected.get()) try { connection.close(); } catch (IOException ignored) {}
        }
    }

    private void busy(boolean running) {
        check.setEnabled(!running); send.setEnabled(!running); refresh.setEnabled(!running);
        deviceChoice.setEnabled(!running); security.setEnabled(!running);
        ssid.setEnabled(!running); password.setEnabled(!running); cancel.setEnabled(running);
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void status(String message) {
        Log.i("HS5Setup", message);
        main.post(() -> {
            if (log.length() > 12000) log.setText("");
            log.append(message + "\n");
        });
    }

    private void closeSocket() {
        BluetoothSocket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }

    private void cancelSession() {
        canceled = true;
        closeSocket();
        if (task != null) task.cancel(true);
        status(getString(R.string.hs5_canceled));
    }

    @Override public void onDestroy() {
        canceled = true;
        closeSocket();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
