// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** The DACP remote request, and the sink that writes to Kiosk Satellite's audio output. */
public final class OutputTest {
    static void check(boolean c, String what) {
        if (!c) throw new AssertionError("Failed: " + what);
    }

    public static void main(String[] args) throws Exception {
        // DACP: GET /ctrl-int/1/<command> with Active-Remote.
        try (ServerSocket fake = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final String[] got = new String[2];
            Thread t = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    got[0] = in.readLine();
                    String h;
                    while ((h = in.readLine()) != null && !h.isEmpty()) if (h.startsWith("Active-Remote:")) got[1] = h;
                    s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            DacpRemote remote = new DacpRemote("ABCDEF0123456789", "1986535575");
            check(remote.serviceName().equals("iTunes_Ctrl_ABCDEF0123456789"), "service name");
            int status = remote.send(new InetSocketAddress(InetAddress.getLoopbackAddress(), fake.getLocalPort()), DacpRemote.NEXT);
            t.join(2000);
            check(status == 204, "status");
            check("GET /ctrl-int/1/nextitem HTTP/1.1".equals(got[0]), "request line: " + got[0]);
            check("Active-Remote: 1986535575".equals(got[1]), "Active-Remote header");
        }
        sink();
        System.out.println("OutputTest passed");
    }

    /** A fake AudioOutput: records what is written and reports a chosen timestamp. */
    static final class FakeOutput implements me.jxl.kiosk.plugins.AudioOutput {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        long written, stampFrame = -1, stampNanos;
        int flushes;
        boolean closed;
        @Override public int write(java.nio.ByteBuffer pcm, int frames, int timeoutMs) {
            if (closed) return -1;
            int take = Math.min(frames, 100); // partial writes, like a full buffer
            for (int i = 0; i < take * 4; i++) bytes.write(pcm.get());
            written += take;
            return take;
        }
        @Override public boolean getTimestamp(long[] out) {
            if (stampFrame < 0) return false;
            out[0] = stampFrame;
            out[1] = stampNanos;
            return true;
        }
        @Override public long framesWritten() { return written; }
        @Override public void flush() { written = 0; flushes++; }
        @Override public void close() { closed = true; }
    }

    static void sink() {
        FakeOutput out = new FakeOutput();
        OutputSink sink = new OutputSink(out, 44100, 2);
        short[] pcm = new short[2 * 300];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i % 2 == 0 ? 1000 : -2000);
        check(sink.write(pcm, 0, pcm.length), "write");
        check(out.written == 300, "all frames written across partial writes: " + out.written);
        byte[] b = out.bytes.toByteArray();
        check(b[0] == (byte) 0xE8 && b[1] == 0x03 && b[2] == (byte) 0x30 && b[3] == (byte) 0xF8, "16-bit little endian");
        sink.setGain(0.5f);
        out.bytes.reset();
        sink.write(pcm, 0, 4);
        b = out.bytes.toByteArray();
        check((short) ((b[1] << 8) | (b[0] & 0xff)) == 500 && (short) ((b[3] << 8) | (b[2] & 0xff)) == -1000, "the sender's volume scales the samples");
        // Timing: frame 100 was heard 10 ms ago, so the next frame written (302) plays (302 - 100) frames later.
        long now = System.nanoTime();
        out.stampFrame = 100;
        out.stampNanos = now - 10_000_000L;
        long expected = out.stampNanos + (out.written - 100) * 1_000_000_000L / 44100;
        check(Math.abs(sink.nextPlayTime() - expected) < 1000, "next play time from the output's timestamp");
        sink.close();
        check(!out.closed && out.flushes == 1, "closing the sink flushes but keeps KS's output for the next session");
        check(!sink.write(pcm, 0, 4), "a closed sink takes no more audio");
        OutputSink other = new OutputSink(out, 44100, 2);
        out.closed = true;
        check(!other.write(pcm, 0, 4), "write fails once KS closed the output");
    }
}
