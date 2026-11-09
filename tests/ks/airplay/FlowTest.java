// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ks.airplay.crypto.ChaCha20Poly1305;
import ks.airplay.crypto.Srp;
import ks.airplay.hap.HapChannel;
import ks.airplay.hap.HapPairing;
import ks.airplay.hap.Tlv8;
import ks.airplay.ptp.PtpClock;

/**
 * Plays an AirPlay 2 iPhone against a real server over localhost, and its PTP master clock too: transient
 * pairing, the encrypted control channel, /info, the PTP session, a buffered ALAC stream encrypted per packet,
 * SETRATEANCHORTIME, pause, FLUSHBUFFERED and TEARDOWN. The fake speaker records when each frame would be
 * heard, so the test checks the audio both for content and for landing at the anchored time.
 */
public final class FlowTest {
    static final InetAddress LOCAL = InetAddress.getLoopbackAddress();
    static final long MASTER_OFFSET = 1_700_000_000_000_000_000L; // the "iPhone" clock runs far from ours

    public static void main(String[] args) throws Exception {
        session(false);
        session(true);
        unsynchronized(false);
        unsynchronized(true);
        System.out.println("FlowTest passed");
    }

    /** A speaker that plays written audio in real time, about 30 ms after it is written, like AudioTrack. */
    static final class FakePlatform implements Platform {
        static final long LATENCY = 30_000_000L;
        final List<String> logs = new CopyOnWriteArrayList<>();
        volatile float gain = -1;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final List<long[]> segments = new ArrayList<>(); // {first sample index, play time of that sample}
        long writtenFrames;
        boolean laggyDecoder; // behave like AAC through MediaCodec: one packet late, sometimes two at once
        long startTime = -1; // play time of frame 0 of the current segment
        long segmentFrames;

        @Override public AudioSink openSink(final int rate, int channels) {
            return new AudioSink() {
                @Override public boolean write(short[] s, int off, int len) {
                    int frames = len / 2;
                    synchronized (FakePlatform.this) {
                        long now = System.nanoTime();
                        if (startTime < 0 || startTime + segmentFrames * 1_000_000_000L / rate < now) {
                            // empty or underrun: the next frame plays after the output latency
                            startTime = now + LATENCY;
                            segmentFrames = 0;
                            segments.add(new long[] {writtenFrames, startTime});
                        }
                        for (int i = 0; i < len; i++) {
                            pcm.write(s[off + i] & 0xff);
                            pcm.write((s[off + i] >> 8) & 0xff);
                        }
                        writtenFrames += frames;
                        segmentFrames += frames;
                    }
                    // Block while more than 100 ms is queued, as a real track does.
                    while (true) {
                        long ahead;
                        synchronized (FakePlatform.this) {
                            ahead = startTime + segmentFrames * 1_000_000_000L / rate - System.nanoTime();
                        }
                        if (ahead < 100_000_000L) break;
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                    return true;
                }
                @Override public synchronized long nextPlayTime() {
                    synchronized (FakePlatform.this) {
                        long end = startTime + segmentFrames * 1_000_000_000L / rate;
                        long now = System.nanoTime();
                        return startTime < 0 || end < now ? now + LATENCY : end;
                    }
                }
                @Override public void setGain(float g) { gain = g; }
                @Override public void flush() {
                    synchronized (FakePlatform.this) { startTime = -1; }
                }
                @Override public void close() {}
            };
        }

        @Override public AudioDecoder openDecoder(int ct, int spf) {
            TestSupport.check(ct == 2, "ALAC requested");
            final AlacDecoder alac = AlacDecoder.airPlay(spf);
            if (!laggyDecoder) {
                return new AudioDecoder() {
                    @Override public int decode(byte[] d, int o, int l, short[] out) { return alac.decode(d, o, l, out); }
                    @Override public void close() {}
                };
            }
            return new AudioDecoder() {
                final java.util.ArrayDeque<short[]> held = new java.util.ArrayDeque<>();
                int calls;
                @Override public int decode(byte[] d, int o, int l, short[] out) {
                    short[] frame = new short[alac.frameLength() * 2];
                    int n = alac.decode(d, o, l, frame);
                    held.add(java.util.Arrays.copyOf(frame, n * 2));
                    calls++;
                    if (calls == 1 || calls % 4 == 2) return 0; // nothing yet: output arrives later
                    int written = 0;
                    while (held.size() > 1) { // keep one packet of latency, release the rest
                        short[] h = held.poll();
                        System.arraycopy(h, 0, out, written, h.length);
                        written += h.length;
                    }
                    return written / 2;
                }
                @Override public void close() {}
            };
        }

        @Override public void log(String message) { logs.add(message); }
        boolean logged(String part) { for (String l : logs) if (l.contains(part)) return true; return false; }

        synchronized short[] samples() {
            byte[] b = pcm.toByteArray();
            short[] out = new short[b.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            return out;
        }

        /** When the given written frame reached the speaker. */
        synchronized long playTime(long frame) {
            long[] seg = null;
            for (long[] s : segments) if (s[0] <= frame) seg = s;
            return seg[1] + (frame - seg[0]) * 1_000_000_000L / 44100;
        }
    }

    static final class Events implements AirPlayServer.Listener {
        final List<String> seen = new CopyOnWriteArrayList<>();
        @Override public void onSessionStarted(String c) { seen.add("session:" + c); }
        @Override public void onStreamStarted(String c, int ct) { seen.add("stream:" + ct); }
        @Override public void onAudioActive(boolean a) { seen.add("active:" + a); }
        @Override public void onSessionEnded() { seen.add("ended"); }
        @Override public void onVolume(double db) { seen.add("volume:" + db); }
        @Override public void onMetadata(Map<String, String> m) { seen.add("title:" + m.get("title")); }
        @Override public void onArtwork(byte[] image, String type) { seen.add("art"); }
        @Override public void onProgress(long s, long c, long e) {}
    }

    /** An RTSP client that can switch to the HAP encrypted channel. */
    static final class Client {
        final Socket socket;
        InputStream in;
        OutputStream out;
        final InputStream rawIn;
        final OutputStream rawOut;
        int cseq = 1;

        Client(int port) throws Exception {
            socket = new Socket(LOCAL, port);
            socket.setSoTimeout(10000);
            rawIn = new BufferedInputStream(socket.getInputStream());
            rawOut = socket.getOutputStream();
            in = rawIn;
            out = rawOut;
        }

        void encrypt(byte[] secret) {
            byte[][] server = HapPairing.controlKeys(secret); // {server decrypt, server encrypt}
            in = new BufferedInputStream(new HapChannel.Reader(rawIn, server[1]));
            out = new HapChannel.Writer(rawOut, server[0]);
        }

        RtspMessage send(String method, String uri, String type, byte[] body) throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(cseq++).append("\r\n");
            sb.append("User-Agent: AirPlay/700.0\r\n");
            if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
            sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
            out.write(sb.toString().getBytes(Util.UTF8));
            if (body != null) out.write(body);
            out.flush();
            RtspMessage response = RtspMessage.read(in);
            TestSupport.check(response != null, method + " " + uri + " got a response");
            return response;
        }

        RtspMessage ok(String method, String uri, String type, byte[] body) throws Exception {
            RtspMessage r = send(method, uri, type, body);
            TestSupport.check("200".equals(r.uri), method + " " + uri + " returned " + r.uri);
            return r;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> plist(String method, String uri, Map<String, Object> body) throws Exception {
            RtspMessage r = ok(method, uri, "application/x-apple-binary-plist", body == null ? null : BPlist.write(body));
            return r.body.length == 0 ? new LinkedHashMap<String, Object>() : (Map<String, Object>) BPlist.parse(r.body);
        }

        /** HomeKit transient pair-setup as iOS does it. Returns the shared secret. */
        byte[] pairTransient() throws Exception {
            ok("POST", "/pair-pin-start", null, null);
            byte[] m1 = new Tlv8.Writer().add(Tlv8.METHOD, 0).add(Tlv8.STATE, 1).add(Tlv8.FLAGS, Tlv8.FLAG_TRANSIENT).bytes();
            Map<Integer, byte[]> m2 = Tlv8.parse(ok("POST", "/pair-setup", "application/octet-stream", m1).body);
            TestSupport.check(Tlv8.intValue(m2, Tlv8.STATE, 0) == 2 && m2.get(Tlv8.PUBLIC_KEY).length > 300, "pair-setup M2");
            Srp.Client srp = new Srp.Client(new SecureRandom());
            byte[] proof = srp.proof(HapPairing.TRANSIENT_PIN, m2.get(Tlv8.SALT), m2.get(Tlv8.PUBLIC_KEY));
            byte[] m3 = new Tlv8.Writer().add(Tlv8.STATE, 3).add(Tlv8.PUBLIC_KEY, srp.publicKey()).add(Tlv8.PROOF, proof).bytes();
            Map<Integer, byte[]> m4 = Tlv8.parse(ok("POST", "/pair-setup", "application/octet-stream", m3).body);
            TestSupport.check(Tlv8.intValue(m4, Tlv8.STATE, 0) == 4 && !m4.containsKey(Tlv8.ERROR), "pair-setup M4");
            TestSupport.check(srp.checkServer(m4.get(Tlv8.PROOF)), "server proof");
            return srp.sessionKey();
        }
    }

    /** The iPhone's PTP master: Sync then Follow_Up carrying when Sync left, on the master clock. */
    static final class Master {
        final DatagramSocket socket;
        final int eventPort, generalPort;
        final long clockId = 0x1122334455667788L;
        int seq;
        volatile boolean running = true;
        final Thread thread;

        Master(int eventPort, int generalPort) throws Exception {
            socket = new DatagramSocket(0, LOCAL);
            this.eventPort = eventPort;
            this.generalPort = generalPort;
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    while (running) {
                        try {
                            tick();
                            Thread.sleep(125);
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        byte[] header(int type, int s) {
            byte[] m = new byte[44];
            m[0] = (byte) (0x10 | type);
            m[1] = 2;
            m[3] = 44;
            for (int k = 0; k < 8; k++) m[20 + k] = (byte) (clockId >>> (56 - 8 * k));
            m[30] = (byte) (s >> 8);
            m[31] = (byte) s;
            return m;
        }

        void tick() throws Exception {
            int s = seq++ & 0xffff;
            long sent = System.nanoTime() + MASTER_OFFSET;
            byte[] sync = header(0x0, s);
            socket.send(new DatagramPacket(sync, sync.length, LOCAL, eventPort));
            byte[] fu = header(0x8, s);
            long secs = sent / 1_000_000_000L, nanos = sent % 1_000_000_000L;
            for (int k = 0; k < 6; k++) fu[34 + k] = (byte) (secs >>> (40 - 8 * k));
            for (int k = 0; k < 4; k++) fu[40 + k] = (byte) (nanos >>> (24 - 8 * k));
            socket.send(new DatagramPacket(fu, fu.length, LOCAL, generalPort));
        }

        void close() {
            running = false;
            socket.close();
        }
    }

    @SuppressWarnings("unchecked")
    static void session(boolean laggy) throws Exception {
        System.out.println(laggy ? " with a decoder that lags like AAC, and a +0.25 s audio offset:" : " with ALAC:");
        FakePlatform platform = new FakePlatform();
        platform.laggyDecoder = laggy;
        Events events = new Events();
        PtpClock ptp = new PtpClock(new PtpClock.Log() { @Override public void log(String m) { platform.log(m); } }, 0, 0);
        ptp.start();
        Master master = new Master(ptp.eventPort(), ptp.generalPort());
        AirPlayConfig config = new AirPlayConfig("Test Kiosk", "02:AA:BB:CC:DD:EE", 0, AirPlayConfig.DEFAULT_FEATURES);
        AirPlayServer server = new AirPlayServer(config, platform, events);
        final List<String> peers = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.setClock(new BufferedSession.Clock() {
            @Override public Long offset(long id) { return ptp.offset(id); }
            @Override public void peers(List<String> addresses) { peers.addAll(addresses); }
        });
        int port = server.start();
        long shift = laggy ? 250_000_000L : 0; // the second run also checks the audio offset setting
        server.setOffset(shift);
        try {
            Client client = new Client(port);
            Map<String, Object> info = client.plist("GET", "/info", null);
            TestSupport.check("Test Kiosk".equals(info.get("name")) && Long.valueOf(AirPlayConfig.DEFAULT_FEATURES).equals(info.get("features")), "/info before pairing");

            client.encrypt(client.pairTransient());
            info = client.plist("GET", "/info", null);
            TestSupport.check(config.deviceIdString().equals(info.get("deviceID")), "/info over the encrypted channel");

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("timingProtocol", "PTP");
            session.put("name", "Test iPhone");
            session.put("groupUUID", "5D1E8C3A-0000-0000-0000-000000000001");
            session.put("timingPeerInfo", new LinkedHashMap<String, Object>());
            Map<String, Object> r1 = client.plist("SETUP", "rtsp://x/1", session);
            TestSupport.check(BPlist.getLong(r1, "eventPort", 0) > 0 && r1.get("timingPeerInfo") instanceof Map, "PTP session SETUP");
            Socket eventChannel = new Socket(LOCAL, (int) BPlist.getLong(r1, "eventPort", 0));

            TestSupport.AlacFixture fixture = TestSupport.alacFixture();
            byte[] shk = new byte[32];
            new SecureRandom().nextBytes(shk);
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("type", 103L);
            stream.put("ct", 2L);
            stream.put("spf", (long) fixture.frameLength);
            stream.put("shk", shk);
            stream.put("audioFormat", 0x40000L);
            Map<String, Object> setup = new LinkedHashMap<>();
            setup.put("streams", new ArrayList<Object>(Collections.singletonList(stream)));
            Map<String, Object> r2 = client.plist("SETUP", "rtsp://x/1", setup);
            Map<String, Object> s0 = (Map<String, Object>) ((List<Object>) r2.get("streams")).get(0);
            int dataPort = (int) BPlist.getLong(s0, "dataPort", 0);
            TestSupport.check(dataPort > 0 && BPlist.getLong(s0, "type", 0) == 103, "buffered stream SETUP");
            client.ok("RECORD", "rtsp://x/1", null, null);
            client.ok("SET_PARAMETER", "rtsp://x/1", "text/parameters", "volume: -15.000000\r\n".getBytes(Util.UTF8));
            client.ok("SETPEERS", "rtsp://x/1", "/peer-list-changed", BPlist.write(new ArrayList<Object>(Collections.singletonList("127.0.0.1"))));
            Map<String, Object> homePod = new LinkedHashMap<>();
            homePod.put("Addresses", new ArrayList<Object>(java.util.Arrays.asList("192.168.1.40", "fe80::1%en0")));
            homePod.put("ID", "8A1B2C3D-0000-0000-0000-000000000002");
            client.ok("SETPEERSX", "rtsp://x/1", "/peer-list-changed-x", BPlist.write(new ArrayList<Object>(Collections.singletonList(homePod))));
            TestSupport.check(peers.contains("127.0.0.1") && peers.contains("192.168.1.40"), "group members passed to the clock: " + peers);

            // Let the clock settle, then push audio well ahead and anchor its first frame 600 ms from now.
            long deadline = System.currentTimeMillis() + 5000;
            while (ptp.offset(master.clockId) == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
            Long offset = ptp.offset(master.clockId);
            TestSupport.check(offset != null && Math.abs(offset - MASTER_OFFSET) < 10_000_000L, "PTP offset found: " + offset);

            long rtp0 = 0xFFFFF000L; // crosses the 32-bit wrap
            Socket data = new Socket(LOCAL, dataPort);
            sendPackets(data.getOutputStream(), fixture, shk, 100, rtp0);
            long anchorMaster = System.nanoTime() + MASTER_OFFSET + 600_000_000L;
            client.ok("SETRATEANCHORTIME", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(anchor(master.clockId, rtp0, anchorMaster, 1)));

            short[] expected = TestSupport.pcmFixture();
            waitForSamples(platform, expected.length, 10000);
            short[] got = platform.samples();
            int first = firstSound(got);
            TestSupport.check(first >= 0, "audio arrived");
            long heard = platform.playTime(first / 2);
            long due = anchorMaster - MASTER_OFFSET + shift;
            System.out.println("  first frame timing error: " + (heard - due) / 1000 + " us");
            TestSupport.check(Math.abs(heard - due) < 10_000_000L, "first frame heard on time: off by " + (heard - due) / 1000 + " us");
            int mismatched = 0;
            for (int i = 0; i < expected.length && first + i < got.length; i++) if (got[first + i] != expected[i]) mismatched++;
            // Drift correction may drop or repeat a frame or two; the audio must otherwise be intact.
            System.out.println("  samples differing after drift correction: " + mismatched + " of " + expected.length);
            TestSupport.check(mismatched < expected.length / 50, "decrypted and decoded audio matches (" + mismatched + " samples differ)");
            TestSupport.check(Math.abs(platform.gain - 0.25f) < 1e-6, "volume applied");
            // What's heard now, for lyrics: the anchor frame plus the time since it was due.
            long heardRtp = server.playingRtp();
            long expectRtp = rtp0 + (System.nanoTime() - due) * 44100 / 1_000_000_000L;
            TestSupport.check(Math.abs((int) (heardRtp - expectRtp)) < 441, "playing position within 10 ms: off by " + (int) (heardRtp - expectRtp) + " frames");

            // Pause, flush everything, and resume with new audio anchored later.
            client.ok("SETRATEANCHORTIME", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(anchor(master.clockId, rtp0, anchorMaster, 0)));
            Map<String, Object> flush = new LinkedHashMap<>();
            flush.put("flushUntilSeq", 200L);
            flush.put("flushUntilTS", rtp0 + 100_000L);
            client.plist("FLUSHBUFFERED", "rtsp://x/1", flush);
            long resumeRtp = rtp0 + 500_000L;
            int before = platform.samples().length;
            sendPackets(data.getOutputStream(), fixture, shk, 300, resumeRtp);
            long resumeMaster = System.nanoTime() + MASTER_OFFSET + 500_000_000L;
            client.ok("SETRATEANCHORTIME", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(anchor(master.clockId, resumeRtp, resumeMaster, 1)));
            waitForSamples(platform, before + expected.length, 10000);
            short[] after = platform.samples();
            int resumed = -1;
            for (int i = before; i < after.length; i++) if (after[i] != 0) { resumed = i - (i % 2); break; }
            TestSupport.check(resumed >= 0, "audio resumed");
            long resumeHeard = platform.playTime(resumed / 2);
            System.out.println("  resume timing error: " + (resumeHeard - (resumeMaster - MASTER_OFFSET + shift)) / 1000 + " us, PTP offset error: " + (ptp.offset(master.clockId) - MASTER_OFFSET) / 1000 + " us");
            TestSupport.check(Math.abs(resumeHeard - (resumeMaster - MASTER_OFFSET + shift)) < 10_000_000L,
                    "resumed on time: off by " + (resumeHeard - (resumeMaster - MASTER_OFFSET + shift)) / 1000 + " us");

            RtspMessage teardown = client.send("TEARDOWN", "rtsp://x/1", null, null);
            TestSupport.check("close".equalsIgnoreCase(teardown.header("Connection")), "TEARDOWN closes");
            Thread.sleep(300);
            TestSupport.check(events.seen.contains("session:Test iPhone") && events.seen.contains("stream:2"), "session events " + events.seen);
            TestSupport.check(events.seen.contains("active:true") && events.seen.contains("ended") && events.seen.contains("volume:-15.0"), "events " + events.seen);
            data.close();
            eventChannel.close();
        } finally {
            master.close();
            server.stop();
            ptp.stop();
        }
    }

    /** Without a PTP clock the receiver still plays, as a standalone speaker shortly after the anchor. */
    @SuppressWarnings("unchecked")
    static void unsynchronized(boolean otherClock) throws Exception {
        FakePlatform platform = new FakePlatform();
        AirPlayConfig config = new AirPlayConfig("Solo", "02:AA:BB:CC:DD:EF", 0, AirPlayConfig.DEFAULT_FEATURES);
        AirPlayServer server = new AirPlayServer(config, platform, new Events());
        // otherClock: the only clock heard is not the group's (a HomePod leads, the iPhone is heard). Its time
        // must not be used for the anchor, or every frame looks hours late and is dropped.
        PtpClock ptp = null;
        Master master = null;
        if (otherClock) {
            final FakePlatform pl = platform;
            final PtpClock clock = new PtpClock(new PtpClock.Log() { @Override public void log(String m) { pl.log(m); } }, 0, 0);
            clock.start();
            ptp = clock;
            master = new Master(clock.eventPort(), clock.generalPort());
            server.setClock(new BufferedSession.Clock() { @Override public Long offset(long id) { return clock.offset(id); } });
            long deadline = System.currentTimeMillis() + 5000;
            while (clock.offset(master.clockId) == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
            TestSupport.check(clock.offset(42) != null, "the lone clock answers for any timeline");
        }
        int port = server.start();
        try {
            Client client = new Client(port);
            client.encrypt(client.pairTransient());
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("timingProtocol", "PTP");
            client.plist("SETUP", "rtsp://x/1", session);
            TestSupport.AlacFixture fixture = TestSupport.alacFixture();
            byte[] shk = new byte[32];
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("type", 103L);
            stream.put("ct", 2L);
            stream.put("spf", (long) fixture.frameLength);
            stream.put("shk", shk);
            Map<String, Object> setup = new LinkedHashMap<>();
            setup.put("streams", new ArrayList<Object>(Collections.singletonList(stream)));
            Map<String, Object> s0 = (Map<String, Object>) ((List<Object>) client.plist("SETUP", "rtsp://x/1", setup).get("streams")).get(0);
            Socket data = new Socket(LOCAL, (int) BPlist.getLong(s0, "dataPort", 0));
            sendPackets(data.getOutputStream(), fixture, shk, 1, 1000);
            client.ok("SETRATEANCHORTIME", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(anchor(42, 1000, 123_456_789_000L, 1)));
            short[] expected = TestSupport.pcmFixture();
            waitForSamples(platform, expected.length, 10000);
            if (firstSound(platform.samples()) < 0) System.out.println(platform.logs);
            TestSupport.check(firstSound(platform.samples()) >= 0, otherClock ? "plays when the clock heard is not the group's" : "plays without PTP");
            if (otherClock) TestSupport.check(platform.logged("does not match timeline 2a"), "says the clock does not match");
            data.close();
        } finally {
            server.stop();
            if (master != null) master.close();
            if (ptp != null) ptp.stop();
        }
    }

    static Map<String, Object> anchor(long clock, long rtp, long masterNanos, int rate) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("networkTimeSecs", masterNanos / 1_000_000_000L);
        long frac = ((masterNanos % 1_000_000_000L) << 32) / 1_000_000_000L; // top 32 bits of the 64-bit fraction
        a.put("networkTimeFrac", frac << 32);
        a.put("networkTimeTimelineID", clock);
        a.put("rtpTime", rtp & 0xffffffffL);
        a.put("rate", (long) rate);
        return a;
    }

    /** Buffered packets: 2-byte length, RTP header, ChaCha20-Poly1305 payload and tag, 8-byte nonce. */
    static void sendPackets(OutputStream out, TestSupport.AlacFixture fixture, byte[] shk, int seq0, long rtp0) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        long rtp = rtp0;
        int seq = seq0;
        for (byte[] frame : fixture.packets) {
            byte[] header = new byte[12];
            int s = (seq & 0x7fffff) | 0x80000000;
            header[0] = (byte) (s >>> 24); header[1] = (byte) (s >>> 16); header[2] = (byte) (s >>> 8); header[3] = (byte) s;
            long t = rtp & 0xffffffffL;
            header[4] = (byte) (t >>> 24); header[5] = (byte) (t >>> 16); header[6] = (byte) (t >>> 8); header[7] = (byte) t;
            header[8] = 0x16; // SSRC
            byte[] nonce8 = new byte[8];
            new SecureRandom().nextBytes(nonce8);
            byte[] nonce = new byte[12];
            System.arraycopy(nonce8, 0, nonce, 4, 8);
            byte[] aad = java.util.Arrays.copyOfRange(header, 4, 12);
            byte[] sealed = ChaCha20Poly1305.seal(shk, nonce, frame, 0, frame.length, aad);
            int len = 2 + 12 + sealed.length + 8;
            all.write(len >> 8);
            all.write(len);
            all.write(header);
            all.write(sealed);
            all.write(nonce8);
            seq++;
            rtp += fixture.frameLength;
        }
        // One corrupted packet must be dropped without upsetting the stream.
        byte[] junk = new byte[60];
        junk[0] = 0;
        junk[1] = 60;
        all.write(junk);
        out.write(all.toByteArray());
        out.flush();
    }

    static void waitForSamples(FakePlatform platform, int samples, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            short[] got = platform.samples();
            int first = firstSound(got);
            if (first >= 0 && got.length - first >= samples) return;
            Thread.sleep(50);
        }
    }

    /** The fixture starts with a sine wave, so the first non-zero sample marks where it begins. */
    static int firstSound(short[] pcm) {
        for (int i = 0; i < pcm.length; i++) if (pcm[i] != 0) return i - (i % 2);
        return -1;
    }
}
