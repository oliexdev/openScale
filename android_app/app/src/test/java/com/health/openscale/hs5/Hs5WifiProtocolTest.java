/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import org.junit.Test;
import java.time.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class Hs5WifiProtocolTest {
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private static byte[] record() { return hex("1ad29e2da33800f202090247200908c0"); }
    private static byte[] incoming() {
        byte[] bytes = Hs5WifiProtocol.command(254, 0x38, record(), false);
        bytes[0] = (byte)0xa0;
        return bytes;
    }
    @Test public void discoveryMatchesVendorVector() {
        assertArrayEquals(hex("b0040000ffd0cf"), Hs5WifiProtocol.discovery());
    }
    @Test public void packedRecordUsesKilogramsAndLocalDate() {
        Hs5WifiProtocol.Reading r = Hs5WifiProtocol.reading(record(), ZoneId.of("UTC"));
        assertEquals(Instant.parse("2026-10-14T18:30:45Z").toEpochMilli(), r.timestamp);
        assertEquals(82.4, r.weight, .001); assertEquals(24.2, r.fat, .001);
        assertEquals(52.1, r.water, .001); assertEquals(58.3, r.muscleKg, .001);
        assertEquals(3.2, r.boneKg, .001); assertEquals(9, r.visceralFat); assertEquals(2240, r.dailyCalories);
    }
    @Test public void rejectsBadLengthChecksumAndDeviceType() {
        byte[] bytes = incoming(); assertNotNull(Hs5WifiProtocol.decode(bytes));
        assertNull(Hs5WifiProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        bytes[6] ^= 1; assertNull(Hs5WifiProtocol.decode(bytes));
        bytes = incoming(); bytes[4] = (byte)0xa8; bytes[bytes.length - 1]--; assertNull(Hs5WifiProtocol.decode(bytes));
    }
    @Test public void separatesPayloadAndMac() {
        Hs5WifiProtocol.Frame frame = Hs5WifiProtocol.decode(incoming());
        assertEquals(254, frame.sequence); assertEquals(0x38, frame.command);
        assertArrayEquals(record(), frame.data); assertEquals("30323A30303A", frame.address());
    }
    @Test public void rejectsInvalidDatesAndMissingRecordBytes() {
        byte[] bad = record(); bad[4] &= 15;
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.reading(bad, ZoneId.of("UTC")));
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.reading(new byte[15], ZoneId.of("UTC")));
    }
    @Test public void invalidCompositionNeverBecomesARealMeasurement() {
        byte[] bytes = record(); Arrays.fill(bytes, 6, 16, (byte)255);
        Hs5WifiProtocol.Reading r = Hs5WifiProtocol.reading(bytes, ZoneId.of("UTC"));
        assertEquals(0, r.fat, 0); assertEquals(0, r.water, 0); assertEquals(0, r.muscleKg, 0);
        assertEquals(0, r.boneKg, 0);
        assertEquals(0, r.visceralFat); assertEquals(0, r.dailyCalories);
    }
    @Test public void userIdsAreBigEndianAndEmptySlotsStayDistinct() {
        assertArrayEquals(hex("0312345678"), Hs5WifiProtocol.user(3, 0x12345678));
        byte[] data = new byte[80]; Arrays.fill(data, (byte)255); data[0]=0;data[1]=0;data[2]=0;data[3]=42;
        int[] ids = Hs5WifiProtocol.users(data); assertEquals(42, ids[0]); assertEquals(-1, ids[1]);
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.users(new byte[79]));
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.profile(20, 42, 30, 176, 0));
    }
    @Test public void cipherMatchesVendorSdkGoldenVector() {
        assertArrayEquals(hex("e6c8d5ff070fb6e498a534f7ac03e399"), Hs5WifiProtocol.encrypt(new byte[16],new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.challengeResponse(new byte[47]));
    }
    @Test public void historySupportsTheObservedTrailingZeroPadding() {
        byte[] packet = new byte[4 + 4 * 16 + 1];
        Arrays.fill(packet, 0, 4, (byte)4);
        for (int i = 0; i < 4; i++) System.arraycopy(record(), 0, packet, 4 + i * 16, 16);
        byte[][] records = Hs5WifiProtocol.historyRecords(packet);
        assertEquals(4, records.length);
        for (byte[] bytes : records) assertArrayEquals(record(), bytes);
        assertEquals(4, Hs5WifiProtocol.historyRecords(Arrays.copyOf(packet, packet.length - 1)).length);
    }
    @Test public void historyRejectsTruncatedRecordsAndUnexpectedTrailers() {
        byte[] packet = new byte[21]; packet[2] = 1;
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.historyRecords(Arrays.copyOf(packet, 19)));
        packet[20] = 1;
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.historyRecords(packet));
        assertThrows(IllegalArgumentException.class, () -> Hs5WifiProtocol.historyRecords(new byte[3]));
    }
}
