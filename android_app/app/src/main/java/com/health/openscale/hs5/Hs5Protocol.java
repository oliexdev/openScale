/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent implementation of the HS5 Bluetooth setup wire format. */
public final class Hs5Protocol {
    private Hs5Protocol() {}

    public static byte[] credentials(String ssid, String password, int security) {
        byte[] s = ssid.getBytes(StandardCharsets.UTF_8);
        byte[] p = password.getBytes(StandardCharsets.UTF_8);
        if (s.length == 0 || s.length > 32 || p.length > 32)
            throw new IllegalArgumentException("SSID must be 1–32 bytes and password at most 32 bytes.");
        if (ssid.indexOf('\0') >= 0 || password.indexOf('\0') >= 0)
            throw new IllegalArgumentException("SSID and password cannot contain NUL characters.");
        if (security < 0 || security > 4)
            throw new IllegalArgumentException("Unsupported Wi-Fi security mode.");
        if (security >= 2 && p.length < 8)
            throw new IllegalArgumentException("WPA/WPA2 passwords must contain at least 8 bytes.");
        if (security == 0 && p.length != 0)
            throw new IllegalArgumentException("Leave the password empty for an open network.");
        byte[] result = new byte[66];
        result[1] = (byte) security;
        System.arraycopy(s, 0, result, 2, s.length);
        System.arraycopy(p, 0, result, 34, p.length);
        return result;
    }

    public static byte[] command(int sequence, int command) {
        byte[] result = {(byte) 0xb0, 4, 0, (byte) sequence, (byte) 0xa9, (byte) command, 0};
        result[6] = checksum(result);
        return result;
    }

    private static byte checksum(byte[] frame) {
        int total = 0;
        for (int i = 2; i < frame.length - 1; i++) total += frame[i] & 0xff;
        return (byte) total;
    }

    public static final class Frame {
        public final int sequence, device, command;
        public final boolean acknowledgement;
        public final byte[] data;
        private Frame(byte[] bytes) {
            sequence = bytes[3] & 0xff;
            acknowledgement = bytes.length == 6;
            device = acknowledgement ? -1 : bytes[4] & 0xff;
            command = acknowledgement ? -1 : bytes[5] & 0xff;
            data = acknowledgement ? new byte[0] : Arrays.copyOfRange(bytes, 6, bytes.length - 1);
        }
    }

    /** RFCOMM may split or concatenate packets. Bound and resynchronize the stream. */
    public static final class Decoder {
        private final byte[] pending = new byte[258];
        private int size;
        public List<Frame> feed(byte[] bytes, int count) {
            if (count < 0 || count > bytes.length) throw new IllegalArgumentException("Invalid count");
            List<Frame> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (size == pending.length) discard(1);
                pending[size++] = bytes[i];
                while (size > 0) {
                    if ((pending[0] & 0xff) != 0xa0) { discard(1); continue; }
                    if (size < 2) break;
                    int length = (pending[1] & 0xff) + 3;
                    if (length < 6) { discard(1); continue; }
                    if (size < length) break;
                    byte[] frame = Arrays.copyOf(pending, length);
                    if (checksum(frame) != frame[length - 1]) { discard(1); continue; }
                    frames.add(new Frame(frame));
                    discard(length);
                }
            }
            return frames;
        }
        private void discard(int count) {
            System.arraycopy(pending, count, pending, 0, size - count);
            size -= count;
        }
    }
}
