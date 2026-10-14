/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/** HS5 LAN protocol, independent of the vendor SDK. All numeric payloads are big endian. */
public final class Hs5WifiProtocol {
    private Hs5WifiProtocol() {}
    public static byte[] discovery() {
        return new byte[]{(byte)0xb0, 4, 0, 0, (byte)0xff, (byte)0xd0, (byte)0xcf};
    }

    public static byte[] command(int sequence, int command, byte[] payload, boolean ack) {
        if (payload.length > 245) throw new IllegalArgumentException("HS5 payload too long");
        byte[] bytes = new byte[payload.length + 13];
        bytes[0] = (byte)0xb0; bytes[1] = (byte)(bytes.length - 3);
        bytes[2] = ack ? (byte)0xa0 : 0; bytes[3] = (byte)sequence;
        bytes[4] = (byte)0xa9; bytes[5] = (byte)command;
        System.arraycopy(payload, 0, bytes, 6, payload.length);
        // The SDK uses the first six ASCII bytes of Android's Wi-Fi MAC string.
        byte[] client = {'0', '2', ':', '0', '0', ':'};
        System.arraycopy(client, 0, bytes, bytes.length - 7, 6);
        bytes[bytes.length - 1] = checksum(bytes);
        return bytes;
    }

    private static byte checksum(byte[] bytes) {
        int total = 0;
        for (int i = 2; i < bytes.length - 1; i++) total += bytes[i] & 255;
        return (byte)total;
    }

    public static final class Frame {
        public final int state, sequence, command;
        public final byte[] data, mac;
        Frame(byte[] bytes) {
            state = bytes[2] & 255; sequence = bytes[3] & 255; command = bytes[5] & 255;
            data = Arrays.copyOfRange(bytes, 6, bytes.length - 7);
            mac = Arrays.copyOfRange(bytes, bytes.length - 7, bytes.length - 1);
        }
        public String address() {
            StringBuilder value = new StringBuilder();
            for (byte b : mac) value.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
            return value.toString();
        }
    }

    public static Frame decode(byte[] bytes) {
        if (bytes.length < 13 || bytes.length > 258 || (bytes[0] & 255) != 0xa0
                || (bytes[1] & 255) != bytes.length - 3 || (bytes[4] & 255) != 0xa9
                || bytes[bytes.length - 1] != checksum(bytes)) return null;
        return new Frame(bytes);
    }

    public static byte[] clock(LocalDateTime time) {
        int year = time.getYear() - 2000;
        if (year < 0 || year > 255) throw new IllegalArgumentException("Unsupported scale year");
        return new byte[]{(byte)year, (byte)time.getMonthValue(), (byte)time.getDayOfMonth(),
                (byte)time.getHour(), (byte)time.getMinute(), (byte)time.getSecond()};
    }

    public static byte[] user(int slot, int id) {
        if (slot < 0 || slot >= 20 || id == -1) throw new IllegalArgumentException("Invalid scale profile");
        return ByteBuffer.allocate(5).put((byte)slot).putInt(id).array();
    }

    public static byte[] profile(int slot, int id, int age, int height, int sex) {
        if (age < 7 || age > 99 || height < 81 || height > 219 || sex < 0 || sex > 1)
            throw new IllegalArgumentException("HS5 profile needs age 7–99 and height 81–219 cm.");
        byte[] data = Arrays.copyOf(user(slot, id), 9);
        data[5] = (byte)age; data[6] = (byte)height; data[7] = 1; data[8] = (byte)sex;
        return data;
    }

    public static int[] users(byte[] data) {
        if (data.length != 80) throw new IllegalArgumentException("Incomplete HS5 user list");
        int[] ids = new int[20]; ByteBuffer b = ByteBuffer.wrap(data);
        for (int i = 0; i < 20; i++) ids[i] = b.getInt();
        return ids;
    }

    /** Some HS5 firmware appends one zero padding byte after the history records. */
    public static byte[][] historyRecords(byte[] data) {
        if (data.length < 4) throw new IllegalArgumentException("Incomplete history packet");
        int count = data[2] & 255, expected = 4 + count * 16;
        if (data.length != expected && !(data.length == expected + 1 && data[expected] == 0))
            throw new IllegalArgumentException("Invalid history record count: " + count + " records in " + data.length + " bytes");
        byte[][] records = new byte[count][];
        for (int i = 0; i < count; i++) records[i] = Arrays.copyOfRange(data, 4 + i * 16, 20 + i * 16);
        return records;
    }

    /** Device record: packed local date/time and seven body measurements (16 bytes). */
    public static final class Reading {
        public final long timestamp;
        public final float weight, fat, water, muscleKg, boneKg;
        public final int visceralFat, dailyCalories;
        public final byte[] raw;
        Reading(byte[] bytes, ZoneId zone) {
            raw = bytes.clone();
            int day = ((bytes[3] & 128) >>> 3) | ((bytes[2] & 128) >>> 4) | ((bytes[1] & 224) >>> 5);
            LocalDateTime time = LocalDateTime.of(2000 + (bytes[0] & 255), (bytes[4] & 240) >>> 4,
                    day, bytes[1] & 31, bytes[2] & 127, bytes[3] & 127);
            timestamp = time.atZone(zone).toInstant().toEpochMilli();
            weight = (((bytes[4] & 15) << 8) | (bytes[5] & 255)) / 10f;
            if (weight <= 0 || weight > 300) throw new IllegalArgumentException("Invalid HS5 weight");
            fat = optional(u16(bytes, 6) / 10f, 100);
            water = optional(u16(bytes, 8) / 10f, 100);
            muscleKg = optional(u16(bytes, 10) / 10f, weight);
            boneKg = (bytes[12] & 255) == 255 ? 0 : optional((bytes[12] & 255) / 10f, weight);
            visceralFat = (bytes[13] & 255) == 255 ? 0 : bytes[13] & 255;
            dailyCalories = u16(bytes, 14) == 65535 ? 0 : u16(bytes, 14);
        }
        private static float optional(float value, float max) { return value > 0 && value <= max ? value : 0; }
    }

    public static Reading reading(byte[] data, ZoneId zone) {
        if (data.length != 16) throw new IllegalArgumentException("Expected a 16-byte HS5 reading");
        try { return new Reading(data, zone); }
        catch (DateTimeException error) { throw new IllegalArgumentException("Invalid HS5 record date", error); }
    }
    public static int u16(byte[] bytes, int offset) { return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255); }

    /** Firmware challenge/response. Constants identify the HS5 protocol, not an account. */
    public static byte[] challengeResponse(byte[] challenge) {
        if (challenge.length != 48) throw new IllegalArgumentException("Invalid HS5 challenge");
        byte[] model = {(byte)0x52,0x11,(byte)0x9a,(byte)0xf4,(byte)0xc1,(byte)0xce,(byte)0xfa,(byte)0x89,
                (byte)0x85,(byte)0x8c,(byte)0xe8,0x46,0x26,0x0b,(byte)0xa8,(byte)0xc1};
        byte[] mask = "Ch/HQ4LzItYT42s=".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < 16; i++) { model[i] = swapNibbles(model[i]); mask[i] = swapNibbles(mask[i]); }
        byte[] key = encrypt(model, mask);
        byte[] sessionKey = encrypt(reverseWords(Arrays.copyOfRange(challenge, 0, 16)), key);
        return reverseWords(encrypt(reverseWords(Arrays.copyOfRange(challenge, 32, 48)), sessionKey));
    }
    private static byte swapNibbles(byte b) { return (byte)(((b & 15) << 4) | ((b & 240) >>> 4)); }
    private static byte[] reverseWords(byte[] input) {
        byte[] result = new byte[input.length];
        for (int i = 0; i < input.length; i++) result[i] = input[(i & ~3) + 3 - (i & 3)];
        return result;
    }
    static byte[] encrypt(byte[] input, byte[] key) {
        int[] v = new int[4], k = new int[4];
        ByteBuffer b = ByteBuffer.wrap(input), kb = ByteBuffer.wrap(key);
        for (int i = 0; i < 4; i++) { v[i] = b.getInt(); k[i] = kb.getInt(); }
        int z = v[3], sum = 0;
        for (int round = 0; round < 19; round++) {
            sum += 0x9e3779b9; int e = (sum >>> 2) & 3;
            for (int p = 0; p < 4; p++) {
                int y = v[(p + 1) & 3];
                z = v[p] += ((z >>> 5 ^ y << 2) + (y >>> 3 ^ z << 4)) ^ ((sum ^ y) + (k[(p & 3) ^ e] ^ z));
            }
        }
        ByteBuffer result = ByteBuffer.allocate(16);
        for (int value : v) result.putInt(value);
        return result.array();
    }
}
