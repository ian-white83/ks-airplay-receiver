// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One realtime AirPlay audio stream (RTP payload type 96): receives RTP packets over UDP, decrypts them, holds
 * them in a jitter buffer, asks the sender to resend lost ones and plays them out at a fixed delay.
 */
public final class AudioSession {
    /** Callbacks for the plugin, made from session threads. */
    public interface Events {
        /** Audio started or stopped flowing to the speaker. */
        void onAudioActive(boolean active);
    }

    static final int SAMPLE_RATE = 44100;
    private static final int SLOTS = 1024;
    private static final int MAX_RESEND = 64;
    private static final long IDLE_NANOS = 2_000_000_000L;

    private final Platform platform;
    private final Events events;
    private final InetAddress remote;
    private final byte[] key; // null for an unencrypted stream
    private final byte[] iv;
    private final int bufferMs;

    private DatagramSocket dataSocket, controlSocket, timingSocket;
    private Thread dataThread, controlThread, timingThread, playerThread;
    private volatile boolean running = true;
    private volatile int remoteControlPort, remoteTimingPort;
    private int ct = 2, spf = 352;
    private boolean audioStarted;

    // Jitter buffer, guarded by lock.
    private final Object lock = new Object();
    private final int[] slotSeq = new int[SLOTS];
    private final byte[][] slotData = new byte[SLOTS][];
    private final boolean[] slotSilent = new boolean[SLOTS];
    private int buffered;
    private boolean haveHighest;
    private int highest;
    private boolean playing;
    private int nextSeq;
    private int flushBefore = -1;
    private int controlSeq;

    private volatile float volumeGain = 1f, duckGain = 1f;
    private volatile Platform.AudioSink sink;
    private volatile boolean active;
    private volatile long lastAudioNanos;
    private Cipher cipher;

    // Counters for status and tests.
    public volatile long packetsReceived, packetsLost, resendRequests, framesPlayed;

    public AudioSession(Platform platform, Events events, InetAddress remote, byte[] key, byte[] iv, int bufferMs) throws IOException {
        this.platform = platform;
        this.events = events;
        this.remote = remote;
        this.key = key;
        this.iv = iv;
        this.bufferMs = Math.max(50, Math.min(bufferMs, 5000));
        for (int i = 0; i < SLOTS; i++) slotSeq[i] = -1;
        timingSocket = new DatagramSocket(0);
        timingSocket.setSoTimeout(1000);
        timingThread = thread("timing", new Runnable() { @Override public void run() { timingLoop(); } });
    }

    private Thread thread(String name, Runnable body) {
        Thread t = new Thread(body, "airplay-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public int timingPort() { return timingSocket.getLocalPort(); }
    public int dataPort() { return dataSocket == null ? 0 : dataSocket.getLocalPort(); }
    public int controlPort() { return controlSocket == null ? 0 : controlSocket.getLocalPort(); }
    public boolean audioStarted() { return audioStarted; }
    public int compressionType() { return ct; }

    /** The sender's NTP timing port. AirPlay 2 names it in the first SETUP, AirPlay 1 in the Transport header. */
    public void setRemoteTimingPort(int port) { remoteTimingPort = port; }

    /** Start the type 96 stream: ct 2 ALAC, 4 AAC-LC or 8 AAC-ELD, with spf samples per packet. */
    public synchronized void startAudio(int ct, int spf, int remoteControlPort) throws IOException {
        if (audioStarted) return;
        this.ct = ct;
        this.spf = spf > 0 ? spf : (ct == 8 ? 480 : ct == 4 ? 1024 : 352);
        this.remoteControlPort = remoteControlPort;
        dataSocket = new DatagramSocket(0);
        controlSocket = new DatagramSocket(0);
        try {
            dataSocket.setReceiveBufferSize(256 * 1024);
        } catch (SocketException ignored) {
        }
        audioStarted = true;
        dataThread = thread("data", new Runnable() { @Override public void run() { receiveLoop(dataSocket, false); } });
        controlThread = thread("control", new Runnable() { @Override public void run() { receiveLoop(controlSocket, true); } });
        playerThread = thread("player", new Runnable() { @Override public void run() { playerLoop(); } });
    }

    /** AirPlay volume in dB: -30 (quietest) to 0, or -144 for mute. */
    public void setVolume(double db) {
        volumeGain = gainForVolume(db);
        applyGain();
    }

    /** Perceptual curve: the -30..0 dB slider maps to 0..1 and is squared, so the low end is usable. */
    public static float gainForVolume(double db) {
        if (db <= -30.0 || Double.isNaN(db)) return 0f;
        if (db >= 0.0) return 1f;
        double fraction = (db + 30.0) / 30.0;
        return (float) (fraction * fraction);
    }

    /** Extra attenuation while the voice assistant talks, 0 to 1. */
    public void setDuck(float gain) {
        duckGain = Math.max(0f, Math.min(1f, gain));
        applyGain();
    }

    private void applyGain() {
        Platform.AudioSink s = sink;
        if (s != null) s.setGain(volumeGain * duckGain);
    }

    /** FLUSH: drop buffered audio. Packets before nextSeq, when given, are ignored. */
    public void flush(int nextSeqHint) {
        synchronized (lock) {
            clearLocked();
            playing = false;
            haveHighest = false;
            flushBefore = nextSeqHint;
            lock.notifyAll();
        }
        Platform.AudioSink s = sink;
        if (s != null) s.flush();
        setActive(false);
    }

    private void clearLocked() {
        for (int i = 0; i < SLOTS; i++) {
            slotSeq[i] = -1;
            slotData[i] = null;
        }
        buffered = 0;
    }

    public boolean closed() { return !running; }

    public void close() {
        if (!running) return;
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        if (dataSocket != null) dataSocket.close();
        if (controlSocket != null) controlSocket.close();
        timingSocket.close();
        Platform.AudioSink s = sink;
        if (s != null) s.flush();
        join(playerThread);
        join(dataThread);
        join(controlThread);
        join(timingThread);
        setActive(false);
    }

    private static void join(Thread t) {
        if (t == null || t == Thread.currentThread()) return;
        t.interrupt();
        try {
            t.join(1000);
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
        return (short) (a - b);
    }

    // ---------------------------------------------------------------- receiving

    private void receiveLoop(DatagramSocket socket, boolean control) {
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
                int len = packet.getLength();
                if (len < 4) continue;
                if (control) {
                    int type = buffer[1] & 0x7f;
                    if (type == 0x56 && len >= 16) {
                        // Retransmitted audio packet after a 4-byte header.
                        byte[] inner = new byte[len - 4];
                        System.arraycopy(buffer, 4, inner, 0, inner.length);
                        receive(inner, inner.length, true);
                    }
                    // 0x54 sync packets carry the sender's clock. Playout here follows arrival, so they are not needed.
                } else {
                    receive(buffer, len, false);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) platform.log("Audio socket error: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                platform.log("Dropped a bad audio packet: " + e);
            }
        }
    }

    /** Handle one RTP packet: 12-byte header, then the (encrypted) payload. Visible for tests. */
    void receive(byte[] data, int len, boolean resent) {
        if (len < 12) return;
        int seq = Util.getU16(data, 2);
        int payloadLen = len - 12;
        boolean silent = payloadLen == 0 || (payloadLen == 4 && data[12] == 0x00 && data[13] == 0x68 && data[14] == 0x34 && data[15] == 0x00);
        // ALAC streams open with 44-byte packets carrying format information instead of audio.
        if (ct == 2 && len == 44) silent = true;
        byte[] payload = null;
        if (!silent) {
            payload = new byte[payloadLen];
            System.arraycopy(data, 12, payload, 0, payloadLen);
            if (key != null) {
                try {
                    decrypt(payload);
                } catch (Exception e) {
                    platform.log("Audio decryption failed: " + e);
                    return;
                }
            }
        }
        packetsReceived++;
        int resendFrom = -1, resendCount = 0;
        synchronized (lock) {
            if (flushBefore >= 0) {
                if (seqDiff(seq, flushBefore) < 0) return;
                flushBefore = -1;
            }
            if (!haveHighest) {
                haveHighest = true;
                highest = seq;
            } else {
                int d = seqDiff(seq, highest);
                if (d > 0) {
                    if (d > 1 && !resent) {
                        resendFrom = (highest + 1) & 0xffff;
                        resendCount = Math.min(d - 1, MAX_RESEND);
                    }
                    highest = seq;
                } else if (d < -SLOTS / 2) {
                    return;
                }
            }
            if (playing && seqDiff(seq, nextSeq) < 0) return; // too late
            int slot = seq & (SLOTS - 1);
            if (slotSeq[slot] == seq) return; // duplicate (AAC-ELD repeats packets)
            if (slotSeq[slot] < 0) buffered++;
            slotSeq[slot] = seq;
            slotData[slot] = payload;
            slotSilent[slot] = silent;
            lock.notifyAll();
        }
        if (resendCount > 0) requestResend(resendFrom, resendCount);
    }

    private void decrypt(byte[] payload) throws Exception {
        int blocks = payload.length / 16 * 16;
        if (blocks == 0) return;
        if (cipher == null) cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(payload, 0, blocks);
        System.arraycopy(plain, 0, payload, 0, blocks);
    }

    private void requestResend(int from, int count) {
        int port = remoteControlPort;
        DatagramSocket socket = controlSocket;
        if (port <= 0 || socket == null) return;
        byte[] p = new byte[8];
        int ours = controlSeq++;
        p[0] = (byte) 0x80;
        p[1] = (byte) (0x55 | 0x80);
        p[2] = (byte) (ours >> 8);
        p[3] = (byte) ours;
        p[4] = (byte) (from >> 8);
        p[5] = (byte) from;
        p[6] = (byte) (count >> 8);
        p[7] = (byte) count;
        try {
            socket.send(new DatagramPacket(p, p.length, remote, port));
            resendRequests++;
        } catch (IOException e) {
            if (running) platform.log("Resend request failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- timing (NTP-style)

    private void timingLoop() {
        byte[] buffer = new byte[128];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        long nextRequest = 0;
        while (running) {
            long now = System.nanoTime();
            int port = remoteTimingPort;
            if (port > 0 && now >= nextRequest) {
                nextRequest = now + 3_000_000_000L;
                byte[] req = new byte[32];
                req[0] = (byte) 0x80;
                req[1] = (byte) 0xd2;
                req[3] = 0x07;
                Util.putLong(req, 24, Util.ntpTime(System.currentTimeMillis() * 1_000_000L));
                try {
                    timingSocket.send(new DatagramPacket(req, req.length, remote, port));
                } catch (IOException e) {
                    if (running) platform.log("Timing request failed: " + e.getMessage());
                }
            }
            try {
                packet.setLength(buffer.length);
                timingSocket.receive(packet);
                if (packet.getLength() >= 32 && (buffer[1] & 0x7f) == 0x52) {
                    // The sender asked us for the time: answer like an NTP server.
                    long received = Util.ntpTime(System.currentTimeMillis() * 1_000_000L);
                    byte[] reply = new byte[32];
                    reply[0] = (byte) 0x80;
                    reply[1] = (byte) 0xd3;
                    reply[3] = 0x07;
                    System.arraycopy(buffer, 24, reply, 8, 8);
                    Util.putLong(reply, 16, received);
                    Util.putLong(reply, 24, Util.ntpTime(System.currentTimeMillis() * 1_000_000L));
                    timingSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) platform.log("Timing socket error: " + e.getMessage());
                return;
            }
        }
    }

    // ---------------------------------------------------------------- playout

    /** One packet's worth of playout. */
    private static final class Item {
        byte[] payload;
        boolean silent, lost;
    }

    private void playerLoop() {
        Platform.AudioDecoder decoder = null;
        try {
            decoder = platform.openDecoder(ct, spf);
            sink = platform.openSink(SAMPLE_RATE, 2);
            applyGain();
            short[] pcm = new short[Math.max(spf, 2048) * 2 * 4];
            int target = Math.max(2, bufferMs * SAMPLE_RATE / 1000 / spf);
            Item item = new Item();
            while (running) {
                boolean have;
                synchronized (lock) {
                    if (!playing) {
                        if (haveHighest && buffered >= target) {
                            nextSeq = lowestBufferedLocked();
                            playing = true;
                        } else {
                            lock.wait(50);
                        }
                    }
                    have = playing && takeLocked(item, target);
                }
                if (!have) {
                    if (active && System.nanoTime() - lastAudioNanos > IDLE_NANOS) setActive(false);
                    continue;
                }
                int frames = 0;
                if (item.silent || item.payload == null) {
                    frames = spf;
                    java.util.Arrays.fill(pcm, 0, frames * 2, (short) 0);
                } else {
                    try {
                        frames = decoder.decode(item.payload, 0, item.payload.length, pcm);
                    } catch (Exception e) {
                        platform.log("Audio decode failed: " + e);
                        frames = spf;
                        java.util.Arrays.fill(pcm, 0, frames * 2, (short) 0);
                    }
                    lastAudioNanos = System.nanoTime();
                    setActive(true);
                }
                if (frames > 0) {
                    if (!sink.write(pcm, 0, frames * 2)) break;
                    framesPlayed += frames;
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
            if (decoder != null) decoder.close();
            setActive(false);
        }
    }

    /** Take the packet due next. False on underrun, which stops playout until the buffer refills. */
    private boolean takeLocked(Item item, int target) {
        int slot = nextSeq & (SLOTS - 1);
        if (slotSeq[slot] == nextSeq) {
            item.payload = slotData[slot];
            item.silent = slotSilent[slot];
            item.lost = false;
            slotSeq[slot] = -1;
            slotData[slot] = null;
            buffered--;
        } else if (haveHighest && seqDiff(highest, nextSeq) > 0) {
            // Lost even after resend requests: play silence in its place.
            item.payload = null;
            item.silent = true;
            item.lost = true;
            packetsLost++;
        } else {
            playing = false;
            return false;
        }
        nextSeq = (nextSeq + 1) & 0xffff;
        // Clock drift: a sender that runs fast fills the buffer. Drop one packet to catch up.
        if (buffered > target * 2 + 8) {
            int s = nextSeq & (SLOTS - 1);
            if (slotSeq[s] == nextSeq) {
                slotSeq[s] = -1;
                slotData[s] = null;
                buffered--;
            }
            nextSeq = (nextSeq + 1) & 0xffff;
        }
        return true;
    }

    private int lowestBufferedLocked() {
        int lowest = highest;
        for (int i = 1; i < SLOTS / 2; i++) {
            int candidate = (highest - i) & 0xffff;
            if (slotSeq[candidate & (SLOTS - 1)] == candidate) lowest = candidate;
        }
        return lowest;
    }
}
