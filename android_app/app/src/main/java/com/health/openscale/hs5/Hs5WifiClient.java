/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** One cancellable UDP session. Call from a worker thread; closing interrupts receive. */
public final class Hs5WifiClient implements Closeable {
    public interface Listener {
        void status(String text);
        /** Must durably save, or throw, before the next history packet is requested. */
        void reading(Hs5WifiProtocol.Reading reading) throws IOException;
    }
    public static final class Device {
        public final InetAddress ip;
        public final String mac;
        public Device(InetAddress ip, String mac) { this.ip = ip; this.mac = mac; }
        @Override public String toString() { return "HS5 · " + mac + " · " + ip.getHostAddress(); }
    }
    private final DatagramSocket socket;
    private final Listener listener;
    private Device device;
    private int sequence = 1;
    private int previousSequence = -1, previousCommand = -1;
    private byte[] previousAck;

    public Hs5WifiClient(DatagramSocket socket, Listener listener) throws SocketException {
        this.socket = socket; this.listener = listener;
        socket.setBroadcast(true); socket.setSoTimeout(300);
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    @Override public void close() { socket.close(); }
    private void send(byte[] data, InetAddress address) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
        socket.send(new DatagramPacket(data, data.length, address, 10000));
    }

    public List<Device> discover(List<InetAddress> broadcasts, int seconds) throws IOException {
        Map<String, Device> devices = new LinkedHashMap<>();
        long end = now() + seconds * 1000L, next = 0;
        while (now() < end) {
            if (now() >= next) {
                for (InetAddress address : broadcasts) send(Hs5WifiProtocol.discovery(), address);
                next = now() + 1000;
            }
            DatagramPacket packet = receive();
            if (packet == null) continue;
            Hs5WifiProtocol.Frame frame = decode(packet);
            if (frame != null && frame.command == 0xf0 && packet.getAddress().isSiteLocalAddress()) {
                Device found = new Device(packet.getAddress(), frame.address());
                if (!devices.containsKey(found.mac)) listener.status("Found " + found);
                devices.put(found.mac, found);
            }
        }
        return new ArrayList<>(devices.values());
    }
    private DatagramPacket receive() throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        try { socket.receive(packet); return packet; }
        catch (SocketTimeoutException timeout) { return null; }
    }
    private static Hs5WifiProtocol.Frame decode(DatagramPacket packet) {
        return Hs5WifiProtocol.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
    }
    private Hs5WifiProtocol.Frame next() throws IOException {
        DatagramPacket packet = receive();
        if (packet == null || !device.ip.equals(packet.getAddress())) return null;
        Hs5WifiProtocol.Frame frame = decode(packet);
        if (frame == null || !frame.address().equals(device.mac) || frame.command == 0xf0) return null;
        if (frame.sequence == previousSequence && frame.command == previousCommand) {
            if (previousAck != null) send(previousAck, device.ip);
            return null;
        }
        previousSequence = frame.sequence; previousCommand = frame.command; previousAck = null;
        sequence = (frame.sequence + 1) & 255;
        if (frame.command == 0x34) {
            acknowledge(0x34, false);
            throw new IOException("HS5 error " + (frame.data.length > 0 ? frame.data[0] & 255 : -1));
        }
        if (frame.command == 0xef) throw new IOException("The scale is busy with another connection. Wait, wake it, and retry.");
        return frame;
    }
    private byte[] sendCommand(int command, byte[] payload, boolean ack) throws IOException {
        byte[] bytes = Hs5WifiProtocol.command(sequence, command, payload, ack);
        sequence = (sequence + (ack ? 1 : 2)) & 255;
        send(bytes, device.ip); return bytes;
    }
    private void acknowledge(int command, boolean ack) throws IOException {
        previousAck = sendCommand(command, new byte[0], ack);
    }
    private Hs5WifiProtocol.Frame request(int command, byte[] payload, int... replies) throws IOException {
        byte[] bytes = sendCommand(command, payload, false);
        long end = now() + 6000, resend = now() + 700;
        while (now() < end) {
            Hs5WifiProtocol.Frame frame = next();
            if (frame != null) {
                for (int reply : replies) if (frame.command == reply) return frame;
            }
            if (now() >= resend) { send(bytes, device.ip); resend = now() + 700; }
        }
        throw new IOException(String.format(Locale.ROOT, "HS5 did not answer command %02X. Wake the scale and retry.", command));
    }
    public int[] connect(Device device) throws IOException {
        this.device = device;
        listener.status("Connecting to " + device.ip.getHostAddress() + "…");
        byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
        Hs5WifiProtocol.Frame challenge = request(0xfa, nonce, 0xfb, 0xfe);
        if (challenge.command == 0xfe) throw new IOException("Scale rejected the connection handshake.");
        Hs5WifiProtocol.Frame result = request(0xfc, Hs5WifiProtocol.challengeResponse(challenge.data), 0xfd, 0xfe);
        if (result.command != 0xfd) throw new IOException("Scale rejected the connection handshake.");
        // The vendor SDK writes cloud URLs here. Local measurements do not need us to change them.
        int[] users = Hs5WifiProtocol.users(request(0x31, Hs5WifiProtocol.clock(LocalDateTime.now()), 0x31).data);
        listener.status("HS5 connected over Wi-Fi.");
        return users;
    }
    public void addUser(int slot, int id, int age, int height, int sex) throws IOException {
        Hs5WifiProtocol.Frame reply = request(0x51, Hs5WifiProtocol.profile(slot, id, age, height, sex), 0x51);
        if (reply.data.length != 1 || reply.data[0] != 1) throw new IOException("Scale did not confirm the new user.");
        listener.status("Created scale user " + (slot + 1) + ".");
    }
    public void measure(int slot, int userId) throws IOException {
        Hs5WifiProtocol.Frame reply = request(0x32, Hs5WifiProtocol.user(slot, userId), 0x32);
        if (reply.state != 0xa0) throw new IOException("Scale did not accept the measurement session.");
        listener.status("Ready. Step on the scale with bare feet and stay still.");
        long end = now() + 120000;
        while (now() < end) {
            Hs5WifiProtocol.Frame frame = next();
            if (frame == null) continue;
            if (frame.command == 0x35 || frame.command == 0x36 || frame.command == 0x37) {
                if (frame.data.length < 2) throw new IOException("Incomplete weight packet");
                if (frame.command != 0x35) acknowledge(frame.command, false);
                listener.status(frame.command == 0x37 ? "Measuring body composition…"
                        : (frame.command == 0x35 ? "Weight: " : "Stable weight: ")
                        + Hs5WifiProtocol.u16(frame.data, 0) / 10f + " kg");
            } else if (frame.command == 0x38) {
                save(frame.data);
                acknowledge(0x38, false);
                // Give the scale a chance to send its final stop and receive our ACK.
                long finish = now() + 2000;
                while (now() < finish) {
                    Hs5WifiProtocol.Frame stop = next();
                    if (stop != null && stop.command == 0x39) { acknowledge(0x39, true); break; }
                }
                return;
            } else if (frame.command == 0x39) {
                acknowledge(0x39, true);
                throw new IOException("The scale stopped without a complete measurement. No reading was saved.");
            }
        }
        throw new IOException("Timed out waiting for a complete measurement.");
    }
    public void history(int slot, int userId) throws IOException {
        byte[] payload = Arrays.copyOf(Hs5WifiProtocol.user(slot, userId), 11);
        System.arraycopy(Hs5WifiProtocol.clock(LocalDateTime.now()), 0, payload, 5, 6);
        Hs5WifiProtocol.Frame reply = request(0x33, payload, 0x33);
        if (reply.data.length < 1 || reply.data[0] != 2) throw new IOException("Scale has no matching user for history.");
        reply = request(0x41, new byte[0], 0x41);
        if (reply.data.length < 2) throw new IOException("Incomplete history count");
        if (reply.data[1] == 0) { listener.status("No stored measurements for this scale user."); return; }
        int count = 0;
        for (int page = 0; page < 1000; page++) {
            reply = request(0x42, new byte[0], 0x42);
            byte[][] records;
            try { records = Hs5WifiProtocol.historyRecords(reply.data); }
            catch (IllegalArgumentException malformed) { throw new IOException(malformed.getMessage(), malformed); }
            for (byte[] record : records) { save(record); count++; }
            if (reply.data[3] == 0) { listener.status("History complete: " + count + " readings received."); return; }
        }
        throw new IOException("History transfer exceeded its limit.");
    }
    private void save(byte[] data) throws IOException {
        try {
            Hs5WifiProtocol.Reading reading = Hs5WifiProtocol.reading(data, ZoneId.systemDefault());
            if (reading.timestamp > System.currentTimeMillis() + 300000)
                throw new IOException("The scale sent a future measurement date; it was not saved.");
            listener.reading(reading);
        } catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
    }
}
