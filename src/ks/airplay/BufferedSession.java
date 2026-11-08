// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ks.airplay.crypto.ChaCha20Poly1305;

/**
 * One AirPlay 2 buffered audio stream (type 103): the sender pushes encrypted packets over TCP well ahead of
 * time, and SETRATEANCHORTIME says which RTP frame plays at which moment on its PTP clock. Every speaker in the
 * group plays the same frame at the same moment, which is what makes multi-room work.
 */
public final class BufferedSession {
    /** Callbacks for the plugin, from session threads. */
    public interface Events {
        void onAudioActive(boolean active);
    }

    /** Maps an anchor's PTP timeline to the local clock. */
    public interface Clock {
        /** Clock time minus System.nanoTime(), in nanoseconds, or null if unknown. */
        Long offset(long timelineId);

        /** SETPEERS: the addresses of the other devices in the group, whose clocks may lead it. */
        default void peers(java.util.List<String> addresses) {}
    }

    static final int RATE = 44100;
    static final long NANOS = 1_000_000_000L;
    private static final long MAX_QUEUED_NANOS = 60 * NANOS;
    private static final long RESYNC_NANOS = 20_000_000L;
    private static final long TRIM_NANOS = 1_000_000L;
    private static final long IDLE_NANOS = 2 * NANOS;

    /** Decoded audio for one packet. */
    static final class Chunk {
        final int seq;
        final long rtp; // extended to 64 bits
        final short[] pcm;
        final int frames;
        Chunk(int seq, long rtp, short[] pcm, int frames) { this.seq = seq; this.rtp = rtp; this.pcm = pcm; this.frames = frames; }
    }

    private final Platform platform;
    private final Events events;
    private final Clock clock;
    private final byte[] key;
    private final int ct, spf;
    private final ServerSocket server;
    private Thread readerThread, playerThread;
    private volatile boolean running = true;
    private volatile Socket connection;

    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private long queuedFrames;
    private boolean haveExtension;
    private long lastRtp;
    // Anchor and rate, guarded by lock.
    private boolean anchored, playing;
    private long anchorTimeline, anchorRtp, anchorNetwork;
    private long fallbackOffset, anchorReceived;
    private boolean warnedUnsynced;
    private static final long CLOCK_WAIT_NANOS = 2 * 1_000_000_000L;
    /** An anchor names a moment close to when it arrives; a clock putting it further away is the wrong clock. */
    private static final long PLAUSIBLE_NANOS = 30 * 1_000_000_000L;
    private long rejectedTimeline = -1;
    private boolean fallbackRestarted;
    // Flush filters, guarded by lock.
    private final List<int[]> dropRanges = new ArrayList<>();
    private int dropUntilSeq = -1;
    private boolean flushDecoder;

    private volatile float volumeGain = 1f, duckGain = 1f;
    private volatile long offsetNanos;
    private volatile Platform.AudioSink sink;
    private volatile boolean active;
    private volatile long lastAudioNanos;
    public volatile long packets, badPackets, lateDrops, resyncs, trims, framesPlayed;
    /** Last measured playout error, nanoseconds: positive means late. */
    public volatile long lastError;

    public BufferedSession(Platform platform, Events events, Clock clock, byte[] sessionKey, int ct, int spf) throws IOException {
        if (sessionKey == null || sessionKey.length != 32) throw new IllegalArgumentException("shk must be 32 bytes");
        this.platform = platform;
        this.events = events;
        this.clock = clock;
        this.key = sessionKey.clone();
        this.ct = ct;
        this.spf = spf > 0 ? spf : (ct == 2 ? 352 : 1024);
        server = new ServerSocket(0);
        readerThread = start("airplay-buffered", new Runnable() { @Override public void run() { acceptLoop(); } });
        playerThread = start("airplay-player", new Runnable() { @Override public void run() { playerLoop(); } });
    }

    private static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public int dataPort() { return server.getLocalPort(); }

    /** SETRATEANCHORTIME: rtpTime plays at networkTime (ns) on the timeline's clock. */
    public void setAnchor(long timelineId, long rtpTime, long networkNanos) {
        synchronized (lock) {
            anchorTimeline = timelineId;
            anchorRtp = extend(rtpTime & 0xffffffffL, false);
            anchorNetwork = networkNanos;
            anchored = true;
            // Without a PTP clock, play as a standalone speaker starting a moment after the anchor arrives.
            anchorReceived = System.nanoTime();
            fallbackOffset = networkNanos - (anchorReceived + 250_000_000L);
            fallbackRestarted = false;
            lock.notifyAll();
        }
    }

    /** rate 1 plays, rate 0 pauses. */
    public void setRate(int rate) {
        boolean pause;
        synchronized (lock) {
            pause = playing && rate == 0;
            playing = rate != 0;
            lock.notifyAll();
        }
        if (pause) {
            Platform.AudioSink s = sink;
            if (s != null) s.flush();
            setActive(false);
        }
    }

    /**
     * FLUSHBUFFERED. With a start, drop that range of sequence numbers. Without one, drop everything up to
     * untilSeq and stop until the next anchor.
     */
    public void flush(int fromSeq, int untilSeq) {
        synchronized (lock) {
            if (fromSeq < 0) {
                dropUntilSeq = untilSeq & 0x7fffff;
                playing = false;
                anchored = false;
                flushDecoder = true;
                for (Iterator<Chunk> it = queue.iterator(); it.hasNext();) {
                    Chunk c = it.next();
                    if (seqDiff(c.seq, dropUntilSeq) <= 0) {
                        it.remove();
                        queuedFrames -= c.frames;
                    }
                }
            } else {
                int[] range = {fromSeq & 0x7fffff, untilSeq & 0x7fffff};
                dropRanges.add(range);
                if (dropRanges.size() > 16) dropRanges.remove(0);
                for (Iterator<Chunk> it = queue.iterator(); it.hasNext();) {
                    Chunk c = it.next();
                    if (inRange(c.seq, range)) {
                        it.remove();
                        queuedFrames -= c.frames;
                    }
                }
            }
            lock.notifyAll();
        }
        if (fromSeq < 0) {
            Platform.AudioSink s = sink;
            if (s != null) s.flush();
            setActive(false);
        }
    }

    /** Shift playout: positive plays later, negative earlier (within what the sender has already sent). */
    public void setOffset(long nanos) { offsetNanos = nanos; }

    public void setVolume(double db) {
        volumeGain = AudioCurve.gain(db);
        applyGain();
    }

    public void setDuck(float gain) {
        duckGain = Math.max(0f, Math.min(1f, gain));
        applyGain();
    }

    private void applyGain() {
        Platform.AudioSink s = sink;
        if (s != null) s.setGain(volumeGain * duckGain);
    }

    public void close() {
        if (!running) return;
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Socket c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (lock) { lock.notifyAll(); }
        join(readerThread);
        join(playerThread);
        setActive(false);
    }

    public boolean closed() { return !running; }

    private static void join(Thread t) {
        if (t == null || t == Thread.currentThread()) return;
        t.interrupt();
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setActive(boolean value) {
        if (active == value) return;
        active = value;
        try {
            events.onAudioActive(value);
        } catch (RuntimeException e) {
            platform.log("Audio state callback failed: " + e);
        }
    }

    static int seqDiff(int a, int b) {
        int d = (a - b) & 0x7fffff;
        return d >= 0x400000 ? d - 0x800000 : d;
    }

    static boolean inRange(int seq, int[] range) {
        return seqDiff(seq, range[0]) >= 0 && seqDiff(range[1], seq) >= 0;
    }

    /** Extend a 32-bit RTP timestamp onto a 64-bit timeline near the last one seen. Call with lock held. */
    private long extend(long rtp32, boolean advance) {
        long result;
        if (!haveExtension) {
            result = rtp32;
            if (advance) {
                haveExtension = true;
                lastRtp = result;
            }
            return result;
        }
        int diff = (int) (rtp32 - (lastRtp & 0xffffffffL));
        result = lastRtp + diff;
        if (advance && diff > 0) lastRtp = result;
        return result;
    }

    // ---------------------------------------------------------------- receiving and decoding

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;
            }
            Socket previous = connection;
            connection = s;
            if (previous != null) {
                try {
                    previous.close();
                } catch (IOException ignored) {
                }
            }
            readLoop(s);
        }
    }

    private void readLoop(Socket s) {
        Platform.AudioDecoder decoder = null;
        ArrayDeque<long[]> pending = new ArrayDeque<>(); // {seq, rtp} awaiting decoder output
        short[] pcm = new short[Math.max(spf, 4096) * 2 * 2];
        try {
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 65536));
            decoder = platform.openDecoder(ct, spf);
            while (running) {
                int length = in.readUnsignedShort();
                if (length < 2 + 12 + 16 + 8) {
                    in.skipBytes(Math.max(0, length - 2));
                    continue;
                }
                byte[] packet = new byte[length - 2];
                in.readFully(packet);
                packets++;
                int seq = (int) (Util.getU32(packet, 0) & 0x7fffff);
                long rtp32 = Util.getU32(packet, 4);
                byte[] payload;
                try {
                    payload = decrypt(packet);
                } catch (Exception e) {
                    badPackets++;
                    continue;
                }
                long rtp;
                synchronized (lock) {
                    if (flushDecoder) {
                        flushDecoder = false;
                        pending.clear();
                        decoder.close();
                        decoder = platform.openDecoder(ct, spf);
                    }
                    if (dropped(seq)) continue;
                    rtp = extend(rtp32, true);
                }
                pending.add(new long[] {seq, rtp});
                int frames;
                try {
                    frames = decoder.decode(payload, 0, payload.length, pcm);
                } catch (Exception e) {
                    platform.log("Audio decode failed: " + e);
                    frames = 0;
                }
                if (frames <= 0) {
                    if (pending.size() > 4) pending.removeFirst(); // decoder swallowed a packet
                    continue;
                }
                // A codec with latency (AAC) can return a frame late, or two at once: the output starts at the
                // oldest packet still waiting, and covers as many packets as it has frames for.
                long[] origin = pending.removeFirst();
                for (int covered = Math.round(frames / (float) spf); covered > 1 && !pending.isEmpty(); covered--) pending.removeFirst();
                short[] copy = java.util.Arrays.copyOf(pcm, frames * 2);
                enqueue(new Chunk((int) origin[0], origin[1], copy, frames));
            }
        } catch (EOFException e) {
            // sender closed the stream
        } catch (IOException e) {
            if (running) platform.log("Buffered audio connection ended: " + e.getMessage());
        } catch (Exception e) {
            if (running) platform.log("Buffered audio failed: " + e);
        } finally {
            if (decoder != null) decoder.close();
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Packet layout: 12-byte RTP header, ciphertext and 16-byte tag, then the last 8 bytes of the nonce. */
    private byte[] decrypt(byte[] p) throws Exception {
        byte[] nonce = new byte[12];
        System.arraycopy(p, p.length - 8, nonce, 4, 8);
        byte[] aad = new byte[8];
        System.arraycopy(p, 4, aad, 0, 8);
        return ChaCha20Poly1305.open(key, nonce, p, 12, p.length - 12 - 8, aad);
    }

    private boolean dropped(int seq) {
        if (dropUntilSeq >= 0) {
            if (seqDiff(seq, dropUntilSeq) <= 0) return true;
            dropUntilSeq = -1;
        }
        for (int[] range : dropRanges) if (inRange(seq, range)) return true;
        return false;
    }

    private void enqueue(Chunk c) throws InterruptedException {
        synchronized (lock) {
            while (running && queuedFrames * NANOS / RATE > MAX_QUEUED_NANOS) lock.wait(100);
            if (!running) return;
            if (dropped(c.seq)) return;
            queue.addLast(c);
            queuedFrames += c.frames;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- playout

    private void playerLoop() {
        boolean started = false;
        try {
            sink = platform.openSink(RATE, 2);
            applyGain();
            while (running) {
                Chunk c;
                long due;
                synchronized (lock) {
                    c = playing && anchored ? queue.peekFirst() : null;
                    if (c == null) {
                        lock.wait(20);
                        if (active && System.nanoTime() - lastAudioNanos > IDLE_NANOS) {
                            started = false;
                            setActive(false);
                        }
                        continue;
                    }
                    Long off = clock == null ? null : clock.offset(anchorTimeline);
                    if (off != null && Math.abs(anchorNetwork - off - anchorReceived) > PLAUSIBLE_NANOS) {
                        if (rejectedTimeline != anchorTimeline) {
                            rejectedTimeline = anchorTimeline;
                            platform.log("The PTP clock heard does not match timeline " + Long.toHexString(anchorTimeline)
                                    + " (anchor " + (anchorNetwork - off - anchorReceived) / 1_000_000L + " ms away): waiting for the group's clock");
                        }
                        off = null;
                    }
                    if (off == null) {
                        // Give the sender's clock a moment to appear; it often starts with the group.
                        if (clock != null && System.nanoTime() - anchorReceived < CLOCK_WAIT_NANOS) {
                            lock.wait(20);
                            continue;
                        }
                        if (!fallbackRestarted && clock != null) {
                            // The wait for a clock used up the anchor's lead: start from now instead of dropping it.
                            fallbackRestarted = true;
                            fallbackOffset = anchorNetwork - (System.nanoTime() + 250_000_000L);
                        }
                        if (!warnedUnsynced) {
                            warnedUnsynced = true;
                            platform.log("No PTP clock for timeline " + Long.toHexString(anchorTimeline) + ": playing without multi-room sync");
                        }
                        off = fallbackOffset;
                    } else if (warnedUnsynced) {
                        warnedUnsynced = false;
                        platform.log("PTP clock found: playing in sync");
                    }
                    due = anchorNetwork - off + (c.rtp - anchorRtp) * NANOS / RATE + offsetNanos;
                }
                long next = sink.nextPlayTime();
                long error = next - due; // positive: this chunk would play late
                lastError = error;
                long duration = c.frames * NANOS / RATE;
                if (started && Math.abs(error) > RESYNC_NANOS) {
                    started = false;
                    resyncs++;
                }
                if (!started) {
                    if (error > duration) {
                        take(c);
                        lateDrops++;
                        continue;
                    }
                    if (error < 0) {
                        long lead = -error;
                        if (lead > 250_000_000L) {
                            Thread.sleep(Math.min((lead - 200_000_000L) / 1_000_000L, 50));
                            continue;
                        }
                        writeSilence(framesFor(lead));
                        take(c);
                        play(c, 0, c.frames);
                    } else {
                        int skip = framesFor(error);
                        take(c);
                        play(c, skip, c.frames - skip);
                    }
                    started = true;
                    continue;
                }
                take(c);
                if (error > TRIM_NANOS && c.frames > 1) {
                    trims++;
                    play(c, 1, c.frames - 1); // a little late: drop one frame
                } else if (error < -TRIM_NANOS) {
                    trims++;
                    play(c, 0, c.frames);
                    play(c, c.frames - 1, 1); // a little early: repeat one frame
                } else {
                    play(c, 0, c.frames);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) platform.log("Audio output failed: " + e);
        } finally {
            Platform.AudioSink s = sink;
            sink = null;
            if (s != null) s.close();
            setActive(false);
        }
    }

    private void take(Chunk c) {
        synchronized (lock) {
            if (queue.peekFirst() == c) {
                queue.pollFirst();
                queuedFrames -= c.frames;
                lock.notifyAll();
            }
        }
    }

    private static int framesFor(long nanos) {
        return (int) Math.max(0, Math.round(nanos * (double) RATE / NANOS));
    }

    private void play(Chunk c, int from, int frames) {
        if (frames <= 0) return;
        Platform.AudioSink s = sink;
        if (s == null || !s.write(c.pcm, from * 2, frames * 2)) return;
        framesPlayed += frames;
        lastAudioNanos = System.nanoTime();
        setActive(true);
    }

    private void writeSilence(int frames) {
        short[] zeros = new short[2048 * 2];
        while (frames > 0 && running) {
            int n = Math.min(frames, 2048);
            Platform.AudioSink s = sink;
            if (s == null || !s.write(zeros, 0, n * 2)) return;
            frames -= n;
        }
    }
}
