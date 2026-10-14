/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** One bounded, cancellable HS5 session. It never logs credential bytes. */
public final class Hs5Session {
    public interface Listener { void status(String message); }
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final Hs5Protocol.Decoder decoder = new Hs5Protocol.Decoder();
    private int sequence = 1;
    private int lastReplySequence = -1;
    private int lastReplyCommand = -1;
    private boolean identity;
    private int wifiState = -1;
    private final byte[] buffer = new byte[512];

    public Hs5Session(InputStream in, OutputStream out, Listener listener) {
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    public boolean probe() throws IOException, InterruptedException {
        listener.status("Bluetooth connected. Asking the scale for its identity…");
        sendCommand(0xf1);
        long end = now() + 6000;
        while (now() < end && !identity) { receive(); Thread.sleep(25); }
        if (!identity) listener.status("No identity reply. Older HS5 firmware may only reply during Wi-Fi setup.");
        return identity;
    }

    public String provision(byte[] credentials) throws IOException, InterruptedException {
        if (credentials.length != 66) throw new IllegalArgumentException("Expected 66 credential bytes");
        // Recent SDKs query IDPS first; the original SDK sends credentials directly.
        // Give the optional IDPS exchange a short window before the legacy path.
        sendCommand(0xf1);
        long end = now() + 2500;
        while (now() < end && !identity) { receive(); Thread.sleep(25); }
        if (!identity) listener.status("Using the original HS5 setup sequence.");
        out.write(credentials);
        out.flush();
        Arrays.fill(credentials, (byte) 0);
        listener.status("Wi-Fi settings sent. Waiting for the scale’s result…");
        wifiState = -1;
        end = now() + 60000;
        long pollAt = now() + 500;
        while (now() < end) {
            receive();
            if (wifiState == 2) return "The scale reports Wi-Fi connected. Check that SET disappears.";
            if (wifiState == 3) return "The scale reports Wi-Fi setup failed. Check the 2.4 GHz network, password and security mode.";
            if (wifiState >= 0 && wifiState != 1)
                return "The scale returned an unknown Wi-Fi status (" + wifiState + "). Success is unconfirmed.";
            if (now() >= pollAt) {
                sendCommand(0xe9);
                pollAt = now() + 1000;
            }
            Thread.sleep(25);
        }
        return "Timed out waiting for Wi-Fi confirmation. Last status: "
                + (wifiState == 1 ? "connecting" : "no Wi-Fi status received") + ".";
    }

    private void sendCommand(int command) throws IOException {
        out.write(Hs5Protocol.command(sequence, command));
        out.flush();
        sequence = (sequence + 2) & 0xff;
    }

    private void receive() throws IOException {
        int available = in.available();
        if (available <= 0) return;
        int count = in.read(buffer, 0, Math.min(buffer.length, available));
        if (count < 0) throw new IOException("Bluetooth stream closed; Wi-Fi success is unconfirmed.");
        for (Hs5Protocol.Frame frame : decoder.feed(buffer, count)) {
            if (frame.acknowledgement || frame.device != 0xa9) continue;
            sequence = (frame.sequence + 1) & 0xff;
            if (frame.command == 0xf0) {
                sendCommand(0xf0);
                if (!identity) listener.status("HS5 identity reply received; Bluetooth protocol confirmed.");
                identity = true;
            } else if (frame.sequence != lastReplySequence || frame.command != lastReplyCommand) {
                if (frame.command == 0xe0 && frame.data.length >= 1) {
                    int state = frame.data[0] & 0xff;
                    if (state != wifiState) listener.status("Scale Wi-Fi status: " + state
                            + (state == 1 ? " (connecting)" : state == 2 ? " (connected)" : state == 3 ? " (failed)" : " (unknown)"));
                    wifiState = state;
                } else {
                    listener.status(String.format("Received HS5 command 0x%02X (%d data bytes).", frame.command, frame.data.length));
                }
            }
            lastReplySequence = frame.sequence;
            lastReplyCommand = frame.command;
        }
    }
}
