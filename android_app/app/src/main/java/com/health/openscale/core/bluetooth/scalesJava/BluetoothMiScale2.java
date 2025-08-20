/* Copyright (C) 2025  olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: GPLv3 or later.
 */

package com.health.openscale.core.bluetooth.scalesJava;

import static com.health.openscale.core.bluetooth.scalesJava.BluetoothCommunication.BT_STATUS.UNEXPECTED_ERROR;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.bluetooth.libs.MiScaleLib;
import com.health.openscale.core.data.GenderType;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.LogManager;

import java.io.ByteArrayOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

public class BluetoothMiScale2 extends BluetoothCommunication {
    private static final String TAG = "BluetoothMiScale2";

    // Mi custom history characteristic
    private static final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC =
            UUID.fromString("00002a2f-0000-3512-2118-0009af100700");

    // Mi custom service + config for unit command
    private static final UUID WEIGHT_CUSTOM_SERVICE =
            UUID.fromString("00001530-0000-3512-2118-0009af100700");
    private static final UUID WEIGHT_CUSTOM_CONFIG =
            UUID.fromString("00001542-0000-3512-2118-0009af100700");

    // Enable history (helps after battery reset)
    private static final byte[] ENABLE_HISTORY_MAGIC = new byte[]{0x01, (byte) 0x96, (byte) 0x8a, (byte) 0xbd, 0x62};

    // History reassembly buffer (notifications can fragment)
    private final ByteArrayOutputStream histBuf = new ByteArrayOutputStream();

    private int pendingHistoryCount = -1;
    private int importedHistory = 0;

    private boolean historyMode = false;

    // warn only once per session if we see "unexpected" flags in history status byte
    private boolean historyUnexpectedFlagWarned = false;

    public BluetoothMiScale2(Context context) { super(context); }

    @Override
    public String driverName() { return "Xiaomi Mi Scale v2"; }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (value == null || value.length == 0) return;

        LogManager.d(TAG, "Notify " + characteristic + " len=" + value.length + " data=" + byteInHex(value));

        // STOP (end of history transfer)
        if (value.length == 1 && value[0] == 0x03) {
            historyMode = false;

            // Check for truncated leftover BEFORE flush (for debug visibility)
            int leftoverLen = histBuf.size();
            if (leftoverLen % 10 != 0) {
                LogManager.w(TAG, "History STOP with truncated fragment: " + leftoverLen + " byte(s) not multiple of 10 (ignored)", null);
            }

            LogManager.d(TAG, "History STOP received; flush + ACK");
            flushHistoryBuffer(); // parse any full 10-byte records left

            // ACK stop
            writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x03});
            // 0x04 + uniq acknowledge
            int uniq = getUniqueNumber();
            byte[] ack = new byte[]{0x04, (byte) 0xFF, (byte) 0xFF, (byte) ((uniq >> 8) & 0xFF), (byte) (uniq & 0xFF)};
            writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, ack);

            LogManager.i(TAG, "History import done: " + importedHistory + " record(s)");
            if (pendingHistoryCount == 0) {
                LogManager.i(TAG, "Scale reported no new history records.");
            }
            pendingHistoryCount = -1;
            importedHistory = 0;
            histBufReset();
            // keep connection logic to the state machine
            resumeMachineState();
            return;
        }

        // Live frame (13 bytes)
        if (value.length == 13) {
            if (historyMode) {
                if (parseLiveFrame(value)) {
                    importedHistory++;
                    LogManager.d(TAG, "History (13B) parsed");
                }
                return;
            } else {
                parseLiveFrame(value);
                return;
            }
        }

        // Rare: two live frames combined in one notify (2x13)
        if (value.length == 26) {
            LogManager.d(TAG, "26-byte payload -> split into 2x13 live frames");
            boolean s1 = parseLiveFrame(Arrays.copyOfRange(value, 0, 13));
            boolean s2 = parseLiveFrame(Arrays.copyOfRange(value, 13, 26));
            if (historyMode) {
                int inc = (s1 ? 1 : 0) + (s2 ? 1 : 0);
                if (inc > 0) importedHistory += inc;
            }
            return;
        }

        // Response to 0x01 FFFF <uniq>: 0x01 <count> FF FF <uniqHi> <uniqLo> (6..7 bytes)
        if (value.length >= 6 && value[0] == 0x01 && (value[2] == (byte) 0xFF)) {
            pendingHistoryCount = (value[1] & 0xFF);
            LogManager.i(TAG, "History count: " + pendingHistoryCount);
            return;
        }

        // Assume history data chunk (may be fragmented/concatenated)
        appendAndParseHistory(value);
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0: { // set unit on the scale
                final ScaleUser u = getSelectedScaleUser();
                byte[] setUnitCmd = new byte[]{0x06, 0x04, 0x00, (byte) u.getScaleUnit().toInt()};
                writeBytes(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_CONFIG, setUnitCmd);
                LogManager.d(TAG, "Unit set cmd: " + byteInHex(setUnitCmd));
                break;
            }
            case 1: { // set current time
                Calendar c = Calendar.getInstance();
                int year = c.get(Calendar.YEAR);
                byte[] dateTime = new byte[]{
                        (byte) (year), (byte) (year >> 8),
                        (byte) (c.get(Calendar.MONTH) + 1),
                        (byte) c.get(Calendar.DAY_OF_MONTH),
                        (byte) c.get(Calendar.HOUR_OF_DAY),
                        (byte) c.get(Calendar.MINUTE),
                        (byte) c.get(Calendar.SECOND),
                        0x03, 0x00, 0x00
                };
                writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME, dateTime);
                LogManager.d(TAG, "Current time written");
                break;
            }
            case 2: { // enable history notifications + magic
                setNotificationOn(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);
                writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, ENABLE_HISTORY_MAGIC);
                LogManager.d(TAG, "History notify ON + magic sent");
                break;
            }
            case 3: { // request "only last" measurements (per-user marker)
                int uniq = getUniqueNumber();
                byte[] req = new byte[]{0x01, (byte) 0xFF, (byte) 0xFF, (byte) ((uniq >> 8) & 0xFF), (byte) (uniq & 0xFF)};
                writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, req);
                LogManager.d(TAG, "History count request: " + byteInHex(req));
                break;
            }
            case 4: { // trigger history transfer
                writeBytes(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x02});
                LogManager.d(TAG, "History transfer triggered (0x02)");
                historyMode = true;
                stopMachineState(); // wait for notifications
                break;
            }
            default:
                return false;
        }
        return true;
    }

    // --- Parsing ---

    private boolean parseLiveFrame(byte[] data) {
        final byte c0 = data[0], c1 = data[1];
        final boolean isLbs = isBitSet(c0,0), isCatty = isBitSet(c1,6),
                isStabilized = isBitSet(c1,5), isRemoved = isBitSet(c1,7),
                isImpedance = isBitSet(c1,1);

        if (!isStabilized || isRemoved) {
            LogManager.d(TAG, "Live ignored (unstable/removed)");
            return false;
        }

        final int year   = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        final int month  = (data[4] & 0xFF);
        final int day    = (data[5] & 0xFF);
        final int hour   = (data[6] & 0xFF);
        final int minute = (data[7] & 0xFF);

        int weightRaw = ((data[12] & 0xFF) << 8) | (data[11] & 0xFF);
        float weight  = (isLbs || isCatty) ? (weightRaw / 100.0f) : (weightRaw / 200.0f);

        float impedance = 0.0f;
        if (isImpedance) {
            impedance = ((data[10] & 0xFF) << 8) | (data[9] & 0xFF);
            LogManager.d(TAG, "Impedance: " + impedance + " Ohm");
        }

        try {
            Date dt = new SimpleDateFormat("yyyy/MM/dd/HH/mm")
                    .parse(year + "/" + month + "/" + day + "/" + hour + "/" + minute);

            if (!validateDate(dt, 20)) {
                LogManager.w(TAG, "Live date out of range: " + dt, null);
                return false;
            }

            final ScaleUser user = getSelectedScaleUser();
            ScaleMeasurement m = new ScaleMeasurement();
            m.setWeight(Converters.toKilogram(weight, user.getScaleUnit()));
            m.setDateTime(dt);

            if (impedance > 0.0f) {
                int sex = (user.getGender() == GenderType.MALE) ? 1 : 0;
                MiScaleLib lib = new MiScaleLib(sex, user.getAge(), user.getBodyHeight());
                m.setWater(lib.getWater(weight, impedance));
                m.setVisceralFat(lib.getVisceralFat(weight));
                m.setFat(lib.getBodyFat(weight, impedance));
                m.setMuscle(lib.getMuscle(weight, impedance));
                m.setLbm(lib.getLBM(weight, impedance));
                m.setBone(lib.getBoneMass(weight, impedance));
            }

            addScaleMeasurement(m);
            LogManager.i(TAG, "Live saved @ " + dt + " kg=" + m.getWeight());
            return true;
        } catch (ParseException e) {
            setBluetoothStatus(UNEXPECTED_ERROR, "Live date parse error (" + e.getMessage() + ")");
            return false;
        }
    }

    // History record (10 bytes): [status][weightLE(2)][yearLE(2)][mon][day][h][m][s]
    private boolean parseHistoryRecord10(byte[] data) {
        if (data.length != 10) return false;

        final byte status = data[0];
        final boolean isLbs   = (status & 0x01) != 0;
        final boolean isCatty = (status & 0x10) != 0;
        final boolean isStab  = (status & 0x20) != 0;
        final boolean isRem   = (status & 0x80) != 0;

        // detect "unexpected" bits in history status (there is no impedance in 10B format)
        // bit1 and bit2 are unspecified in most reverse-engineering notes; warn once if we see them.
        if (!historyUnexpectedFlagWarned) {
            boolean bit1 = (status & 0x02) != 0; // unknown
            boolean bit2 = (status & 0x04) != 0; // "partial data" in some docs
            if (bit1 || bit2) {
                LogManager.w(TAG, "History status has unexpected flag(s): "
                        + (bit1 ? "bit1 " : "") + (bit2 ? "bit2 " : "")
                        + "— ignoring (history carries no impedance)", null);
                historyUnexpectedFlagWarned = true;
            }
        }

        if (!isStab || isRem) return false;

        int weightRaw = ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
        float weight  = (isLbs || isCatty) ? (weightRaw / 100.0f) : (weightRaw / 200.0f);

        final int year   = ((data[4] & 0xFF) << 8) | (data[3] & 0xFF);
        final int month  = (data[5] & 0xFF);
        final int day    = (data[6] & 0xFF);
        final int hour   = (data[7] & 0xFF);
        final int minute = (data[8] & 0xFF);
        // int second = data[9] & 0xFF;

        try {
            Date dt = new SimpleDateFormat("yyyy/MM/dd/HH/mm")
                    .parse(year + "/" + month + "/" + day + "/" + hour + "/" + minute);

            if (!validateDate(dt, 20)) {
                LogManager.w(TAG, "History date out of range: " + dt, null);
                return false;
            }

            final ScaleUser user = getSelectedScaleUser();
            ScaleMeasurement m = new ScaleMeasurement();
            m.setWeight(Converters.toKilogram(weight, user.getScaleUnit()));
            m.setDateTime(dt);
            // No impedance expected in history; BIA fields left empty.

            addScaleMeasurement(m);
            return true;
        } catch (ParseException e) {
            setBluetoothStatus(UNEXPECTED_ERROR, "History date parse error (" + e.getMessage() + ")");
            return false;
        }
    }

    private void appendAndParseHistory(byte[] chunk) {
        try {
            // Ignore 13-byte frames when in historyMode (they get parsed separately)
            if (historyMode && (chunk.length == 13)) {
                return;
            }
            // Ignore control/short frames
            if (chunk.length < 10) {
                return;
            }

            // Append raw chunk to buffer
            histBuf.write(chunk, 0, chunk.length);
            byte[] buf = histBuf.toByteArray();

            // Process all complete 10-byte records in buffer
            int full = (buf.length / 10) * 10;
            if (full >= 10) {
                int ok = 0;
                for (int off = 0; off < full; off += 10) {
                    byte[] rec = Arrays.copyOfRange(buf, off, off + 10);
                    if (parseHistoryRecord10(rec)) {
                        ok++;
                    }
                }
                if (ok > 0) {
                    importedHistory += ok;
                    LogManager.d(TAG, "History parsed: " + ok + " record(s) from " + full + " bytes");
                }

                // Reset buffer and keep any remainder
                histBufReset();
                if (buf.length > full) {
                    histBuf.write(buf, full, buf.length - full);
                }
            }
        } catch (Exception e) {
            LogManager.e(TAG, "History buffer append failed: " + e.getMessage(), e);
            histBufReset();
        }
    }

    private void flushHistoryBuffer() {
        byte[] leftover = histBuf.toByteArray();
        if (leftover.length >= 10 && leftover.length % 10 == 0) {
            int ok = 0;
            for (int off = 0; off < leftover.length; off += 10) {
                if (parseHistoryRecord10(Arrays.copyOfRange(leftover, off, off + 10))) ok++;
            }
            if (ok > 0) {
                importedHistory += ok;
                LogManager.d(TAG, "History flush parsed: " + ok + " record(s)");
            }
        }
        histBufReset();
    }

    private void histBufReset() {
        try { histBuf.reset(); } catch (Exception ignored) {}
    }

    // --- Utils ---

    private boolean validateDate(Date weightDate, int rangeYears) {
        Calendar max = Calendar.getInstance(); max.add(Calendar.YEAR, rangeYears);
        Calendar min = Calendar.getInstance(); min.add(Calendar.YEAR, -rangeYears);
        return weightDate.before(max.getTime()) && weightDate.after(min.getTime());
    }
}
