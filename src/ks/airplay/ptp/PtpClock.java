// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.ptp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Follows the PTP clocks AirPlay 2 senders run, the way nqptp does: it listens for Sync, Follow_Up and Announce
 * on the event (319) and general (320) ports, estimates each clock's offset from the local monotonic clock
 * ({@link System#nanoTime()}) and keeps the estimate close to the minimum network delay. It never acts as a
 * master. It sends one "awakening" Announce pair to a new clock, as nqptp does, so a quiet sender starts its Sync
 * messages.
 */
public final class PtpClock {
    public interface Log { void log(String message); }

    static final int SYNC = 0x0, FOLLOW_UP = 0x8, ANNOUNCE = 0xB;
    private static final long DISCONTINUITY_NANOS = 5_000_000_000L;
    private static final long STALE_NANOS = 10_000_000_000L;

    /** What we know about one clock, keyed by its clockIdentity. */
    static final class Clock {
        final long id;
        InetAddress address;
        long grandmaster;
        int priority1 = 248, priority2 = 248;
        boolean wakeSent;
        final long[] syncSeq = new long[8];
        final long[] syncTime = new long[8];
        final long[] syncCorrection = new long[8];
        long offset, previousOffsetTime, mastershipStart, lastUpdate;
        boolean stepPending;
        long stepOffset;
        int samples;

        Clock(long id) {
            this.id = id;
            java.util.Arrays.fill(syncSeq, -1);
        }
    }

    private final Log log;
    private final int eventPort, generalPort;
    private final long selfId;
    /**
     * By clock ID and sender address. Two devices can send under one clock ID: with a first-generation HomePod
     * in the group, the HomePod sends Sync carrying the iPhone's clock ID but its own time, days away. Mixed
     * into one record, its readings would drag the iPhone's clock off.
     */
    private final Map<String, Clock> clocks = new HashMap<>();
    private DatagramSocket event, general;
    private Thread eventThread, generalThread;
    private volatile boolean running;
    public volatile long packets;

    /** Listen on these local ports: 319 and 320, or the high ports they are redirected to. */
    public PtpClock(Log log, int eventPort, int generalPort) {
        this.log = log;
        this.eventPort = eventPort;
        this.generalPort = generalPort;
        byte[] id = new byte[8];
        new SecureRandom().nextBytes(id);
        long v = 0;
        for (byte b : id) v = (v << 8) | (b & 0xff);
        selfId = v;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        event = bind(eventPort);
        try {
            general = bind(generalPort);
        } catch (IOException e) {
            event.close();
            throw e;
        }
        running = true;
        eventThread = loop(event, "ptp-event");
        generalThread = loop(general, "ptp-general");
    }

    private static DatagramSocket bind(int port) throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        s.setSoTimeout(1000);
        return s;
    }

    public int eventPort() { return event == null ? 0 : event.getLocalPort(); }
    public int generalPort() { return general == null ? 0 : general.getLocalPort(); }
    public boolean running() { return running; }

    public void stop() {
        running = false;
        if (event != null) event.close();
        if (general != null) general.close();
        join(eventThread);
        join(generalThread);
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread loop(final DatagramSocket socket, String name) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[512];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (running) {
                    try {
                        p.setLength(buf.length);
                        socket.receive(p);
                        long now = System.nanoTime();
                        handle(buf, p.getLength(), p.getAddress(), now);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        if (running) log.log("PTP socket error: " + e.getMessage());
                        return;
                    } catch (RuntimeException e) {
                        log.log("Bad PTP packet: " + e);
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static long u64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    /** PTP timestamp: 48-bit seconds then 32-bit nanoseconds, as nanoseconds. */
    static long timestamp(byte[] b, int off) {
        long seconds = 0;
        for (int i = 0; i < 6; i++) seconds = (seconds << 8) | (b[off + i] & 0xff);
        long nanos = ((long) (b[off + 6] & 0xff) << 24) | ((b[off + 7] & 0xff) << 16) | ((b[off + 8] & 0xff) << 8) | (b[off + 9] & 0xff);
        return seconds * 1_000_000_000L + nanos;
    }

    /** Handle one PTP message received at local monotonic time now. Visible for tests. */
    void handle(byte[] b, int len, InetAddress from, long now) {
        if (len < 34) return;
        int type = b[0] & 0x0f;
        if ((b[1] & 0x0f) != 2) return; // PTP version 2
        long correction = u64(b, 8) >> 16; // nanoseconds, signed
        long clockId = u64(b, 20);
        int seq = ((b[30] & 0xff) << 8) | (b[31] & 0xff);
        if (clockId == selfId) return;
        packets++;
        Clock wake = null;
        synchronized (this) {
            String key = clockId + "@" + from.getHostAddress();
            Clock c = clocks.get(key);
            if (c == null) {
                c = new Clock(clockId);
                clocks.put(key, c);
                log.log(String.format("PTP clock %016x found at %s", clockId, from.getHostAddress()));
            }
            c.address = from;
            if (type == SYNC) {
                int slot = seq & 7;
                c.syncSeq[slot] = seq;
                c.syncTime[slot] = now;
                c.syncCorrection[slot] = correction;
            } else if (type == FOLLOW_UP && len >= 44) {
                long origin = timestamp(b, 34) + correction;
                long received = now;
                int slot = seq & 7;
                if (c.syncSeq[slot] == seq) {
                    // The precise origin is when Sync left the sender, so compare it with Sync's arrival.
                    received = c.syncTime[slot];
                    origin += c.syncCorrection[slot];
                }
                sample(c, origin - received, received);
            } else if (type == ANNOUNCE && len >= 64) {
                c.priority1 = b[47] & 0xff;
                c.priority2 = b[52] & 0xff;
                c.grandmaster = u64(b, 53);
                if (!c.wakeSent) {
                    c.wakeSent = true;
                    wake = c;
                }
            }
        }
        if (wake != null) sendWake(wake);
    }

    static final long STEP_NANOS = 1_000_000_000L, STEP_AGREE_NANOS = 50_000_000L;

    /**
     * nqptp's filter: accept increases quickly at first, then slowly; let decreases in only very slowly. A jump
     * of more than a second that two readings in a row agree on is a step, not network delay: an iPhone steps
     * its clock by hours to follow a HomePod that becomes the group's grandmaster, and back when it leaves. It
     * is taken at once, or the clock would stay on the old time for good.
     */
    private void sample(Clock c, long offset, long received) {
        long jump = offset - c.offset;
        boolean step = false;
        if (c.previousOffsetTime != 0 && Math.abs(jump) > STEP_NANOS) {
            if (c.stepPending && Math.abs(offset - c.stepOffset) < STEP_AGREE_NANOS) {
                step = true;
            } else {
                // First reading of a possible step: wait for the next one to agree.
                c.stepPending = true;
                c.stepOffset = offset;
                return;
            }
        }
        c.stepPending = false;
        if (step) log.log(String.format("PTP clock %016x stepped by %d ms", c.id, jump / 1_000_000L));
        if (step || c.previousOffsetTime == 0 || received - c.previousOffsetTime > DISCONTINUITY_NANOS) {
            c.offset = offset;
            c.mastershipStart = received;
        } else {
            long jitter = offset - c.offset;
            long mastership = received - c.mastershipStart;
            if (jitter < 0) {
                long clamped = Math.max(jitter, -2_500_000L);
                if (mastership > 1_000_000_000L) c.offset += clamped / 256;
            } else if (mastership < 1_000_000_000L) {
                c.offset += jitter;
            } else {
                c.offset += jitter / 16;
            }
        }
        c.previousOffsetTime = received;
        c.lastUpdate = received;
        c.samples++;
    }

    /** As offset(timelineId, anchorNetwork, anchorLocal), without an anchor to choose between senders by. */
    public Long offset(long timelineId) {
        return offset(timelineId, Long.MIN_VALUE, 0);
    }

    /**
     * The offset (clock time minus local monotonic time, in nanoseconds) of the clock an anchor names by its
     * timeline ID, or null when no fresh sample exists. The clock whose own ID is the timeline comes first; a
     * follower of it (its grandmaster is the timeline) stands in only without one, and the one fresh clock only
     * without either. When several senders use that ID, the one whose time puts the anchor (network time
     * anchorNetwork, received at local time anchorLocal) closest to when it arrived wins; without an anchor,
     * the one with the most samples.
     */
    public synchronized Long offset(long timelineId, long anchorNetwork, long anchorLocal) {
        long now = System.nanoTime();
        Clock exact = null, follower = null, only = null;
        int fresh = 0;
        for (Clock c : clocks.values()) {
            if (c.samples == 0 || now - c.lastUpdate > STALE_NANOS) continue;
            if (c.id == timelineId) exact = better(exact, c, anchorNetwork, anchorLocal);
            else if (c.grandmaster == timelineId) follower = better(follower, c, anchorNetwork, anchorLocal);
            fresh++;
            only = c;
        }
        Clock chosen = exact != null ? exact : follower != null ? follower : fresh == 1 ? only : null;
        if (chosen != null && (chosen != lastChosen || timelineId != lastTimeline)) {
            lastChosen = chosen;
            lastTimeline = timelineId;
            log.log("Timeline " + Long.toHexString(timelineId) + " follows PTP clock " + Long.toHexString(chosen.id)
                    + " from " + (chosen.address == null ? "?" : chosen.address.getHostAddress())
                    + (chosen == exact ? "" : chosen == follower ? " (a follower of it)" : " (the only clock heard)"));
        }
        return chosen == null ? null : chosen.offset;
    }

    private static Clock better(Clock a, Clock b, long anchorNetwork, long anchorLocal) {
        if (a == null) return b;
        if (anchorNetwork == Long.MIN_VALUE) return b.samples > a.samples ? b : a;
        long da = Math.abs(anchorNetwork - a.offset - anchorLocal), db = Math.abs(anchorNetwork - b.offset - anchorLocal);
        return db < da ? b : a;
    }

    private Clock lastChosen;
    private long lastTimeline;

    /**
     * Say hello to the group's other devices, as nqptp does when it learns the timing peers: two Announce
     * messages that make a quiet clock (a HomePod leading the group) start sending Sync. Runs in the background,
     * and once more after two seconds for any peer still silent.
     */
    public void peers(final java.util.List<String> addresses) {
        if (!running) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<InetAddress> targets = new java.util.ArrayList<>();
                for (String a : addresses) {
                    try {
                        if (a.indexOf('%') >= 0) continue; // link-local with an Apple interface name
                        InetAddress ip = InetAddress.getByName(a);
                        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || java.net.NetworkInterface.getByInetAddress(ip) != null) continue;
                        targets.add(ip);
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
                for (int round = 0; round < 2 && running; round++) {
                    for (InetAddress ip : targets) {
                        if (round == 1 && heardFrom(ip)) continue;
                        sendWake(ip, 248, 248);
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "ptp-peers");
        t.setDaemon(true);
        t.start();
    }

    private synchronized boolean heardFrom(InetAddress ip) {
        long now = System.nanoTime();
        for (Clock c : clocks.values()) {
            if (ip.equals(c.address) && c.samples > 0 && now - c.lastUpdate < STALE_NANOS) return true;
        }
        return false;
    }

    /** A one-line summary for status. */
    public synchronized String describe() {
        long now = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        for (Clock c : clocks.values()) {
            if (now - c.lastUpdate > STALE_NANOS) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("%016x (%d samples)", c.id, c.samples));
        }
        return sb.length() == 0 ? "no clock yet" : sb.toString();
    }

    private void sendWake(Clock c) {
        if (c.address != null) sendWake(c.address, c.priority1, c.priority2);
    }

    private void sendWake(InetAddress address, int priority1, int priority2) {
        DatagramSocket s = general;
        if (s == null || address == null) return;
        try {
            for (int i = 0; i < 2; i++) {
                byte[] m = new byte[64];
                m[0] = 0x10 | ANNOUNCE;
                m[1] = 0x02;
                m[3] = 64;
                m[6] = 0x04;
                m[7] = 0x08;
                for (int k = 0; k < 8; k++) m[20 + k] = (byte) (selfId >>> (56 - 8 * k));
                m[28] = (byte) 0x80;
                m[29] = 0x08;
                m[32] = 0x05;
                m[33] = (byte) 0xFE;
                m[45] = 37; // currentUtcOffset
                // First seem better than the clock, then worse, which prompts it to start sending Sync.
                m[47] = (byte) (i == 0 ? Math.max(priority1 - 1, 0) : Math.min(priority1 + 1, 255));
                m[48] = (byte) 0xf8; m[49] = (byte) 0xfe; m[50] = 0x43; m[51] = 0x6a;
                m[52] = (byte) priority2;
                for (int k = 0; k < 8; k++) m[53 + k] = (byte) (selfId >>> (56 - 8 * k));
                m[63] = (byte) 160;
                s.send(new DatagramPacket(m, m.length, address, 320));
                if (i == 0) Thread.sleep(150);
            }
        } catch (IOException e) {
            log.log("PTP wake-up failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
