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

        // A HomePod that follows the iPhone's clock as grandmaster but sends its own local time, hours away.
        // The iPhone's own clock must win for the iPhone's timeline, whatever order the clocks are stored in.
        PtpClock group = new PtpClock(new PtpClock.Log() { @Override public void log(String m) {} }, 0, 0);
        long phone = 0xCC817D8E2D040008L, homePod = 0xD4A33D8165D70008L;
        long phoneTruth = 50_000_000_000_000L, homePodTruth = phoneTruth - 30_608_000_000_000L;
        long t0 = System.nanoTime() - 2_000_000_000L;
        for (int i = 0; i < 16; i++) {
            long sent = t0 + i * 125_000_000L;
            group.handle(message(0x0, homePod, i, 0), 44, from, sent + 300_000);
            group.handle(message(0x8, homePod, i, sent + homePodTruth), 44, from, sent + 350_000);
            group.handle(message(0x0, phone, i, 0), 44, from, sent + 300_000);
            group.handle(message(0x8, phone, i, sent + phoneTruth), 44, from, sent + 350_000);
        }
        byte[] follows = message(0xB, homePod, 1, 0);
        for (int k = 0; k < 8; k++) follows[53 + k] = (byte) (phone >>> (56 - 8 * k));
        group.handle(follows, 64, from, System.nanoTime());
        Long phoneOffset = group.offset(phone);
        check(phoneOffset != null && Math.abs(phoneOffset - phoneTruth) < 1_000_000, "the timeline's own clock wins over a follower: " + phoneOffset);
        check(Math.abs(group.offset(homePod) - homePodTruth) < 1_000_000, "the follower keeps its own offset");

        // The iPhone steps its clock 8.5 hours back to follow the HomePod, and forward again when it leaves.
        // One odd reading is ignored; two that agree are a step and taken at once.
        long t1 = System.nanoTime() - 1_000_000_000L;
        long stepped = phoneTruth - 30_608_000_000_000L;
        group.handle(message(0x0, phone, 100, 0), 44, from, t1 + 300_000);
        group.handle(message(0x8, phone, 100, t1 + stepped), 44, from, t1 + 350_000);
        check(Math.abs(group.offset(phone) - phoneTruth) < 1_000_000, "a single jumped reading is held back");
        for (int i = 1; i < 4; i++) {
            long sent = t1 + i * 125_000_000L;
            group.handle(message(0x0, phone, 100 + i, 0), 44, from, sent + 300_000);
            group.handle(message(0x8, phone, 100 + i, sent + stepped), 44, from, sent + 350_000);
        }
        check(Math.abs(group.offset(phone) - stepped) < 1_000_000, "the step is followed: " + (group.offset(phone) - stepped));
        long t2 = System.nanoTime() - 200_000_000L;
        for (int i = 0; i < 2; i++) {
            long sent = t2 + i * 50_000_000L;
            group.handle(message(0x0, phone, 200 + i, 0), 44, from, sent + 300_000);
            group.handle(message(0x8, phone, 200 + i, sent + phoneTruth), 44, from, sent + 350_000);
        }
        check(Math.abs(group.offset(phone) - phoneTruth) < 1_000_000, "and the step back");
        // A late packet (100 ms delay) is not a step.
        long t3 = System.nanoTime();
        group.handle(message(0x0, phone, 300, 0), 44, from, t3 + 100_000_000L);
        group.handle(message(0x8, phone, 300, t3 + phoneTruth), 44, from, t3 + 100_050_000L);
        check(Math.abs(group.offset(phone) - phoneTruth) < 1_000_000, "a late packet barely moves the offset");

        // The HomePod also sends Sync under the iPhone's clock ID, with its own time 489739 s away. Its readings
        // stay apart from the iPhone's, and the anchor picks the sender whose time fits it.
        InetAddress homePodIp = InetAddress.getByName("127.0.0.2");
        long impostor = phoneTruth + 489_739_618_000_000L;
        long t4 = System.nanoTime() - 500_000_000L;
        for (int i = 0; i < 4; i++) {
            long sent = t4 + i * 125_000_000L;
            group.handle(message(0x0, phone, 400 + i, 0), 44, homePodIp, sent + 300_000);
            group.handle(message(0x8, phone, 400 + i, sent + impostor), 44, homePodIp, sent + 350_000);
            group.handle(message(0x0, phone, 500 + i, 0), 44, from, sent + 300_000);
            group.handle(message(0x8, phone, 500 + i, sent + phoneTruth), 44, from, sent + 350_000);
        }
        long anchorLocal = System.nanoTime(), anchorNetwork = anchorLocal + phoneTruth + 100_000_000L;
        Long picked = group.offset(phone, anchorNetwork, anchorLocal);
        check(picked != null && Math.abs(picked - phoneTruth) < 1_000_000, "the anchor picks the iPhone's readings: " + picked);
        check(Math.abs(group.offset(phone, anchorLocal + impostor, anchorLocal) - impostor) < 1_000_000, "and the other sender's when its time fits");
    }
}
