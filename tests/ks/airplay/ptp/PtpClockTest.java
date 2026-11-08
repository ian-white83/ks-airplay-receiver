// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.ptp;

import java.net.InetAddress;

/** Feeds PtpClock synthetic Sync and Follow_Up pairs with chosen arrival times. */
public final class PtpClockTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("Failed: " + what);
    }

    static byte[] message(int type, long clock, int seq, long origin) {
        byte[] m = new byte[64];
        m[0] = (byte) (0x10 | type);
        m[1] = 2;
        for (int k = 0; k < 8; k++) m[20 + k] = (byte) (clock >>> (56 - 8 * k));
        m[30] = (byte) (seq >> 8);
        m[31] = (byte) seq;
        long secs = origin / 1_000_000_000L, nanos = origin % 1_000_000_000L;
        for (int k = 0; k < 6; k++) m[34 + k] = (byte) (secs >>> (40 - 8 * k));
        for (int k = 0; k < 4; k++) m[40 + k] = (byte) (nanos >>> (24 - 8 * k));
        return m;
    }

    public static void run() throws Exception {
        PtpClock clock = new PtpClock(new PtpClock.Log() { @Override public void log(String m) {} }, 0, 0);
        InetAddress from = InetAddress.getLoopbackAddress();
        long clockId = 0xABCDEF0011223344L, truth = 5_000_000_000_000L; // master = local + truth
        long base = System.nanoTime() - 3_000_000_000L;
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 24; i++) {
            long sent = base + i * 125_000_000L;
            long delay = 200_000 + (i % 5 == 3 ? 15_000_000 : random.nextInt(300_000)); // some slow packets
            clock.handle(message(0x0, clockId, i, 0), 44, from, sent + delay);
            clock.handle(message(0x8, clockId, i, sent + truth), 44, from, sent + delay + 50_000);
        }
        Long offset = clock.offset(clockId);
        check(offset != null, "offset available");
        long error = truth - offset;
        check(error > 0 && error < 600_000, "offset tracks the minimum delay, error " + error + " ns");
        check(clock.offset(0x1234) != null, "single fresh clock used for an unknown timeline");
        byte[] announce = message(0xB, clockId, 1, 0);
        long gm = 0x5555666677778888L;
        for (int k = 0; k < 8; k++) announce[53 + k] = (byte) (gm >>> (56 - 8 * k));
        clock.handle(announce, 64, from, System.nanoTime());
        check(clock.offset(gm) != null, "grandmaster ID resolves to its clock");
    }
}
