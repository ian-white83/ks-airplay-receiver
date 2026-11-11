// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

/** Hostile input from the network must end in an error message, never an Error that takes Kiosk Satellite down. */
public final class SafetyTest {
    public static void main(String[] args) throws Exception {
        plistBomb();
        plistFuzz();
        samplesPerFrame();
        dacpReply();
        guard();
        TestSupport.check(DacpRemote.statusLine(new java.io.ByteArrayInputStream("HTTP/1.1 204 No Content\r\n".getBytes("UTF-8"))).equals("HTTP/1.1 204 No Content"), "reads a status line");
        try {
            DacpRemote.statusLine(new java.io.ByteArrayInputStream(new byte[1_000_000]));
            TestSupport.check(false, "an endless status line should fail");
        } catch (IOException expected) {
        }
        System.out.println("SafetyTest passed");
    }

    /** 40 nested two-entry arrays that all point at the next one: 2^40 objects if followed naively. */
    static void plistBomb() {
        int levels = 40;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        int[] offsets = new int[levels + 1];
        for (int i = 0; i < levels; i++) {
            offsets[i] = out.size();
            out.write(0xA2);
            out.write(i + 1);
            out.write(i + 1);
        }
        offsets[levels] = out.size();
        out.write(0x10);
        out.write(7);
        int table = out.size();
        for (int o : offsets) out.write(o);
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(1); // offset size
        out.write(1); // ref size
        long[] trailer = {levels + 1, 0, table};
        for (long v : trailer) for (int k = 7; k >= 0; k--) out.write((int) (v >>> (8 * k)) & 0xff);
        byte[] bomb = out.toByteArray();
        long t0 = System.nanoTime();
        try {
            BPlist.parse(bomb);
            TestSupport.check(false, "the plist bomb should be refused");
        } catch (IOException e) {
            TestSupport.check(e.getMessage().contains("too many objects") || e.getMessage().contains("expands"), "refused: " + e.getMessage());
        }
        TestSupport.check(System.nanoTime() - t0 < 1_000_000_000L, "refused quickly");
    }

    /** Random corruption of a real plist: only IOExceptions. */
    static void plistFuzz() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("qualifier", java.util.Arrays.<Object>asList("txtAirPlay"));
        m.put("streams", java.util.Arrays.<Object>asList(java.util.Collections.singletonMap("spf", (Object) 352L)));
        m.put("name", "a fairly long string to give the fuzzer something to chew on");
        byte[] good = BPlist.write(m);
        Random r = new Random(7);
        for (int i = 0; i < 200000; i++) {
            byte[] b = good.clone();
            int flips = 1 + r.nextInt(4);
            for (int f = 0; f < flips; f++) b[r.nextInt(b.length)] = (byte) r.nextInt(256);
            try {
                BPlist.parse(b);
            } catch (IOException expected) {
            } catch (RuntimeException e) {
                throw new AssertionError("fuzz case " + i + " threw " + e, e);
            }
        }
    }

    static void samplesPerFrame() throws Exception {
        try {
            new BufferedSession(new FlowTest.FakePlatform(), null, null, new byte[32], 2, Integer.MAX_VALUE);
            TestSupport.check(false, "a huge spf should be refused");
        } catch (IllegalArgumentException expected) {
        }
    }

    /** A remote that answers nonsense: an IOException, not a NumberFormatException on the executor. */
    static void dacpReply() throws Exception {
        for (String reply : new String[] {"HTTP/1.1 abc OK\r\n\r\n", "HTTP/1.1 99999999999 OK\r\n\r\n", "garbage\r\n"}) {
            final ServerSocket server = new ServerSocket(0);
            final String answer = reply;
            Thread t = new Thread(() -> {
                try (Socket c = server.accept()) {
                    c.getInputStream().read(new byte[1024]);
                    OutputStream o = c.getOutputStream();
                    o.write(answer.getBytes("UTF-8"));
                    o.flush();
                } catch (IOException ignored) {
                }
            });
            t.start();
            try {
                new DacpRemote("1", "2").send(new InetSocketAddress("127.0.0.1", server.getLocalPort()), DacpRemote.PLAY_PAUSE);
                TestSupport.check(false, "a bad reply should fail");
            } catch (IOException expected) {
            } finally {
                server.close();
                t.join(2000);
            }
        }
    }

    static void guard() {
        final String[] logged = {null};
        Util.guard("test", () -> { throw new OutOfMemoryError("simulated"); }, m -> logged[0] = m).run();
        TestSupport.check(logged[0] != null && logged[0].contains("simulated"), "Errors are logged, not thrown");
    }
}
