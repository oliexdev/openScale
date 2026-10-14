/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;

public class Hs5ProtocolTest {
    private static byte[] hex(String text) {
        String[] values = text.split(" ");
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (byte) Integer.parseInt(values[i], 16);
        return data;
    }

    @Test public void encodesFixedWidthWpa2RecordWithoutFraming() {
        byte[] record = Hs5Protocol.credentials("test", "password", 3);
        assertEquals(66, record.length);
        assertArrayEquals(hex("00 03 74 65 73 74"), Arrays.copyOf(record, 6));
        assertArrayEquals(new byte[28], Arrays.copyOfRange(record, 6, 34));
        assertArrayEquals("password".getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(record, 34, 42));
        assertArrayEquals(new byte[24], Arrays.copyOfRange(record, 42, 66));
    }

    @Test public void preservesSpacesAndFullWidthFields() {
        assertEquals(' ', Hs5Protocol.credentials(" test ", "password", 3)[2]);
        String full = new String(new char[32]).replace('\0', 'a');
        byte[] record = Hs5Protocol.credentials(full, full, 3);
        assertEquals('a', record[33]);
        assertEquals('a', record[65]);
    }

    @Test public void rejectsTruncationAndInvalidCredentials() {
        for (String[] pair : new String[][] {
                {"", "password"}, {"a\0b", "password"}, {"test", "pass\0word"},
                {"ééééééééééééééééé", "password"},
                {"test", "123456789012345678901234567890123" + "é"}, {"test", "short"}}) {
            assertThrows(IllegalArgumentException.class, () -> Hs5Protocol.credentials(pair[0], pair[1], 3));
        }
        assertEquals(0, Hs5Protocol.credentials("test", "", 0)[1]);
        assertThrows(IllegalArgumentException.class, () -> Hs5Protocol.credentials("test", "password", 5));
    }

    @Test public void matchesSdkCommandVectorsAndChecksumWrap() {
        assertArrayEquals(hex("b0 04 00 01 a9 f1 9b"), Hs5Protocol.command(1, 0xf1));
        assertArrayEquals(hex("b0 04 00 03 a9 f0 9c"), Hs5Protocol.command(3, 0xf0));
        assertArrayEquals(hex("b0 04 00 01 a9 e9 93"), Hs5Protocol.command(1, 0xe9));
        assertArrayEquals(hex("b0 04 00 ff a9 e9 91"), Hs5Protocol.command(255, 0xe9));
    }

    @Test public void decodesEveryFragmentBoundaryAndCoalescedReplies() {
        byte[] status = hex("a0 05 00 04 a9 e0 02 8f");
        for (int split = 0; split <= status.length; split++) {
            Hs5Protocol.Decoder decoder = new Hs5Protocol.Decoder();
            List<Hs5Protocol.Frame> frames = new ArrayList<>();
            frames.addAll(decoder.feed(Arrays.copyOfRange(status, 0, split), split));
            frames.addAll(decoder.feed(Arrays.copyOfRange(status, split, status.length), status.length - split));
            assertEquals(1, frames.size());
            assertEquals(0xe0, frames.get(0).command);
            assertEquals(2, frames.get(0).data[0]);
        }
        byte[] multiple = hex("00 ff a0 00 a0 05 00 04 a9 e0 02 00 a0 05 00 04 a9 e0 02 8f a0 05 00 06 a9 e0 03 92");
        List<Hs5Protocol.Frame> frames = new Hs5Protocol.Decoder().feed(multiple, multiple.length);
        assertEquals(2, frames.size());
        assertEquals(3, frames.get(1).data[0]);
    }

    @Test public void handshakeAndProvisioningRequireExplicitSuccessReply() throws Exception {
        ArrayDeque<Integer> rx = new ArrayDeque<>();
        List<byte[]> tx = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        InputStream in = new InputStream() {
            @Override public int available() { return rx.size(); }
            @Override public int read() { return rx.isEmpty() ? -1 : rx.remove(); }
        };
        OutputStream out = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Expected packet write"); }
            @Override public void write(byte[] value) {
                tx.add(value.clone());
                byte[] reply = value.length == 66 ? hex("a0 05 00 06 a9 e0 02 91")
                        : value[5] == (byte) 0xf1 ? hex("a0 04 00 02 a9 f0 9b") : new byte[0];
                for (byte b : reply) rx.add(b & 0xff);
            }
        };
        byte[] credentials = Hs5Protocol.credentials("test", "password", 3);
        String result = new Hs5Session(in, out, messages::add).provision(credentials);
        assertTrue(result.contains("reports Wi-Fi connected"));
        assertEquals(3, tx.size());
        assertArrayEquals(hex("b0 04 00 03 a9 f0 9c"), tx.get(1));
        assertEquals(66, tx.get(2).length);
        assertArrayEquals(new byte[66], credentials);
        assertFalse(String.join("\n", messages).contains("password"));
    }
}
