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
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ks.airplay.crypto.Ed25519;
import ks.airplay.crypto.X25519;
import ks.airplay.fairplay.FairPlay;

/**
 * Plays the part of an iPhone against a real server over localhost: /info, pairing, FairPlay, both SETUPs,
 * encrypted RTP audio with a lost packet that must come back through a resend request, volume, metadata and
 * TEARDOWN. Then the same audio over the AirPlay 1 ANNOUNCE path without encryption.
 */
public final class FlowTest {
    static final InetAddress LOCAL = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws Exception {
        TestSupport.loadTables();
        airPlay2(false);
        airPlay2(true);
        airPlay1();
        System.out.println("FlowTest passed");
    }

    /** Collects PCM in real time, like AudioTrack, so the jitter buffer behaves as on a device. */
    static final class FakePlatform implements Platform {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final List<String> logs = new CopyOnWriteArrayList<>();
        volatile float gain = -1;
        volatile int samples;

        @Override public AudioSink openSink(int sampleRate, int channels) {
            return new AudioSink() {
                @Override public boolean write(short[] s, int off, int len) {
                    synchronized (pcm) {
                        for (int i = 0; i < len; i++) {
                            pcm.write(s[off + i] & 0xff);
                            pcm.write((s[off + i] >> 8) & 0xff);
                        }
                        samples += len;
                    }
                    try {
                        Thread.sleep(len / 2 * 1000L / sampleRate);
                    } catch (InterruptedException e) {
                        return false;
                    }
                    return true;
                }
                @Override public void setGain(float g) { gain = g; }
                @Override public void flush() {}
                @Override public void close() {}
            };
        }

        @Override public AudioDecoder openDecoder(int ct, int spf) {
            TestSupport.check(ct == 2, "ALAC requested");
            final AlacDecoder alac = AlacDecoder.airPlay(spf);
            return new AudioDecoder() {
                @Override public int decode(byte[] d, int o, int l, short[] out) { return alac.decode(d, o, l, out); }
                @Override public void close() {}
            };
        }

        @Override public void log(String message) { logs.add(message); }

        short[] pcm() {
            byte[] b;
            synchronized (pcm) { b = pcm.toByteArray(); }
            short[] out = new short[b.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            return out;
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
        @Override public void onArtwork(byte[] image, String type) { seen.add("art:" + (image == null ? 0 : image.length)); }
        @Override public void onProgress(long s, long c, long e) {}
    }

    /** A minimal RTSP client. */
    static final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        int cseq = 1;

        Client(int port) throws Exception {
            socket = new Socket(LOCAL, port);
            socket.setSoTimeout(10000);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        RtspMessage send(String method, String uri, String type, byte[] body, String... headers) throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(cseq++).append("\r\n");
            sb.append("User-Agent: AirPlay/800.0\r\n");
            for (int i = 0; i + 1 < headers.length; i += 2) sb.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
            sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
            out.write(sb.toString().getBytes(Util.UTF8));
            if (body != null) out.write(body);
            out.flush();
            // A response line parses as method=RTSP/1.0, uri=status.
            RtspMessage response = RtspMessage.read(in);
            TestSupport.check(response != null, method + " " + uri + " got a response");
            return response;
        }

        RtspMessage ok(String method, String uri, String type, byte[] body, String... headers) throws Exception {
            RtspMessage r = send(method, uri, type, body, headers);
            TestSupport.check("200".equals(r.uri), method + " " + uri + " returned " + r.uri + " " + r.protocol);
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    static void airPlay2(boolean pair) throws Exception {
        FakePlatform platform = new FakePlatform();
        Events events = new Events();
        AirPlayConfig config = new AirPlayConfig("Test Kiosk", "02:AA:BB:CC:DD:EE", 0, AirPlayConfig.DEFAULT_FEATURES, pair, 200);
        AirPlayServer server = new AirPlayServer(config, platform, events);
        int port = server.start();
        SecureRandom random = new SecureRandom();
        DatagramSocket timing = new DatagramSocket(0, LOCAL);
        DatagramSocket control = new DatagramSocket(0, LOCAL);
        try {
            Client client = new Client(port);
            Map<String, Object> info = (Map<String, Object>) BPlist.parse(client.ok("GET", "/info", null, null).body);
            TestSupport.check("Test Kiosk".equals(info.get("name")), "/info name");
            TestSupport.check(Arrays.equals((byte[]) info.get("pk"), config.publicKey), "/info public key");

            byte[] secret = null;
            if (pair) secret = pairVerify(client, config);

            byte[] fp1 = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x04, 0x02, 0x00, 0x01, (byte) 0xbb};
            TestSupport.check(client.ok("POST", "/fp-setup", "application/octet-stream", fp1).body.length == 142, "fp-setup 1");
            byte[] fp2 = new byte[164];
            random.nextBytes(fp2);
            fp2[4] = 3;
            fp2[12] = 1;
            TestSupport.check(client.ok("POST", "/fp-setup", "application/octet-stream", fp2).body.length == 32, "fp-setup 2");

            byte[] ekey = new byte[72], eiv = new byte[16];
            random.nextBytes(ekey);
            random.nextBytes(eiv);
            FairPlay sender = new FairPlay();
            sender.setup(fp2);
            byte[] key = sender.decryptKey(ekey);
            if (secret != null) {
                MessageDigest sha = MessageDigest.getInstance("SHA-512");
                sha.update(key);
                sha.update(secret);
                key = Arrays.copyOf(sha.digest(), 16);
            }

            Map<String, Object> setup1 = new LinkedHashMap<>();
            setup1.put("ekey", ekey);
            setup1.put("eiv", eiv);
            setup1.put("timingProtocol", "NTP");
            setup1.put("timingPort", (long) timing.getLocalPort());
            setup1.put("name", "Test iPhone");
            Map<String, Object> r1 = (Map<String, Object>) BPlist.parse(client.ok("SETUP", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(setup1)).body);
            TestSupport.check(BPlist.getLong(r1, "timingPort", 0) > 0, "SETUP returned a timing port");

            TestSupport.AlacFixture fixture = TestSupport.alacFixture();
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("type", 96L);
            stream.put("ct", 2L);
            stream.put("spf", (long) fixture.frameLength);
            stream.put("controlPort", (long) control.getLocalPort());
            Map<String, Object> setup2 = new LinkedHashMap<>();
            setup2.put("streams", new ArrayList<Object>(Collections.singletonList(stream)));
            Map<String, Object> r2 = (Map<String, Object>) BPlist.parse(client.ok("SETUP", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(setup2)).body);
            Map<String, Object> s0 = (Map<String, Object>) ((List<Object>) r2.get("streams")).get(0);
            int dataPort = (int) BPlist.getLong(s0, "dataPort", 0), serverControl = (int) BPlist.getLong(s0, "controlPort", 0);
            TestSupport.check(dataPort > 0 && serverControl > 0, "audio ports");

            client.ok("RECORD", "rtsp://x/1", null, null);
            client.ok("SET_PARAMETER", "rtsp://x/1", "text/parameters", "volume: -15.000000\r\n".getBytes(Util.UTF8));
            client.ok("SET_PARAMETER", "rtsp://x/1", "application/x-dmap-tagged", dmapTitle("Test Song"));
            client.ok("POST", "/feedback", null, null);

            streamAudio(fixture, key, eiv, dataPort, serverControl, control, 5);

            short[] expected = TestSupport.pcmFixture();
            waitFor(platform, expected.length);
            short[] got = platform.pcm();
            int first = firstSound(got);
            TestSupport.check(first >= 0, "audio arrived");
            TestSupport.check(got.length - first >= expected.length, "all audio played (" + (got.length - first) + " of " + expected.length + ")");
            for (int i = 0; i < expected.length; i++) TestSupport.check(got[first + i] == expected[i], "decrypted PCM sample " + i + (pair ? " (paired)" : ""));
            TestSupport.check(Math.abs(platform.gain - 0.25f) < 1e-6, "volume applied to the sink");

            // The server asks for the time on the sender's timing port.
            timing.setSoTimeout(4000);
            DatagramPacket t = new DatagramPacket(new byte[64], 64);
            timing.receive(t);
            TestSupport.check((t.getData()[1] & 0xff) == 0xd2, "timing request");

            // Pause: the sender tears down only the audio stream, then resumes with a new stream SETUP.
            Map<String, Object> pause = new LinkedHashMap<>();
            Map<String, Object> pausedStream = new LinkedHashMap<>();
            pausedStream.put("type", 96L);
            pause.put("streams", new ArrayList<Object>(Collections.singletonList(pausedStream)));
            RtspMessage paused = client.ok("TEARDOWN", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(pause));
            TestSupport.check(paused.header("Connection") == null, "pause keeps the connection");
            int before = platform.samples;
            Map<String, Object> r3 = (Map<String, Object>) BPlist.parse(client.ok("SETUP", "rtsp://x/1", "application/x-apple-binary-plist", BPlist.write(setup2)).body);
            Map<String, Object> s1 = (Map<String, Object>) ((List<Object>) r3.get("streams")).get(0);
            streamAudio(fixture, key, eiv, (int) BPlist.getLong(s1, "dataPort", 0), (int) BPlist.getLong(s1, "controlPort", 0), control, 2);
            long resumeDeadline = System.currentTimeMillis() + 10000;
            while (platform.samples - before < expected.length && System.currentTimeMillis() < resumeDeadline) Thread.sleep(50);
            TestSupport.check(platform.samples - before >= expected.length, "audio resumes after a pause");

            RtspMessage teardown = client.send("TEARDOWN", "rtsp://x/1", null, null);
            TestSupport.check("close".equalsIgnoreCase(teardown.header("Connection")), "TEARDOWN closes");
            Thread.sleep(300);
            TestSupport.check(events.seen.contains("session:Test iPhone"), "session event " + events.seen);
            TestSupport.check(events.seen.contains("stream:2") && events.seen.contains("active:true") && events.seen.contains("ended"), "stream events " + events.seen);
            TestSupport.check(events.seen.contains("title:Test Song") && events.seen.contains("volume:-15.0"), "metadata events " + events.seen);
            TestSupport.check(!server.hasActiveSession(), "session cleared");
        } finally {
            timing.close();
            control.close();
            server.stop();
        }
    }

    /** Legacy pair-setup and pair-verify from the sender's side. Returns the shared secret. */
    static byte[] pairVerify(Client client, AirPlayConfig config) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] edSeed = new byte[32];
        random.nextBytes(edSeed);
        byte[] edPublic = Ed25519.publicKey(edSeed);
        byte[] serverEd = client.ok("POST", "/pair-setup", "application/octet-stream", edPublic).body;
        TestSupport.check(Arrays.equals(serverEd, config.publicKey), "pair-setup key");
        byte[] curvePrivate = X25519.generatePrivateKey(random);
        byte[] curvePublic = X25519.publicKey(curvePrivate);
        byte[] step1 = new byte[68];
        step1[0] = 1;
        System.arraycopy(curvePublic, 0, step1, 4, 32);
        System.arraycopy(edPublic, 0, step1, 36, 32);
        byte[] reply = client.ok("POST", "/pair-verify", "application/octet-stream", step1).body;
        TestSupport.check(reply.length == 96, "pair-verify reply");
        byte[] serverCurve = Arrays.copyOf(reply, 32);
        byte[] secret = X25519.sharedSecret(curvePrivate, serverCurve);
        Cipher ctr = ctr(secret);
        byte[] serverSignature = ctr.update(Arrays.copyOfRange(reply, 32, 96));
        TestSupport.check(Ed25519.verify(serverEd, Pairing.concat(serverCurve, curvePublic), serverSignature), "server signature");
        byte[] ours = ctr.update(Ed25519.sign(edSeed, Pairing.concat(curvePublic, serverCurve)));
        byte[] step2 = new byte[68];
        System.arraycopy(ours, 0, step2, 4, 64);
        client.ok("POST", "/pair-verify", "application/octet-stream", step2);
        return secret;
    }

    static Cipher ctr(byte[] secret) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update("Pair-Verify-AES-Key".getBytes(Util.UTF8));
        sha.update(secret);
        byte[] key = Arrays.copyOf(sha.digest(), 16);
        sha.update("Pair-Verify-AES-IV".getBytes(Util.UTF8));
        sha.update(secret);
        byte[] iv = Arrays.copyOf(sha.digest(), 16);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    /** Send the fixture as RTP, holding back one packet until the server asks for it. */
    static void streamAudio(TestSupport.AlacFixture fixture, byte[] key, byte[] iv, int dataPort, int serverControl, DatagramSocket control, int dropIndex) throws Exception {
        DatagramSocket data = new DatagramSocket(0, LOCAL);
        try {
            List<byte[]> packets = new ArrayList<>();
            int seq = 65530; // crosses the 16-bit wrap
            long ts = 1234567;
            for (byte[] frame : fixture.packets) {
                byte[] p = new byte[12 + frame.length];
                p[0] = (byte) 0x80;
                p[1] = 0x60;
                p[2] = (byte) (seq >> 8);
                p[3] = (byte) seq;
                p[4] = (byte) (ts >> 24);
                p[5] = (byte) (ts >> 16);
                p[6] = (byte) (ts >> 8);
                p[7] = (byte) ts;
                byte[] payload = frame.clone();
                if (key != null) {
                    int blocks = payload.length / 16 * 16;
                    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    System.arraycopy(c.doFinal(payload, 0, blocks), 0, payload, 0, blocks);
                }
                System.arraycopy(payload, 0, p, 12, payload.length);
                packets.add(p);
                seq = (seq + 1) & 0xffff;
                ts += fixture.frameLength;
            }
            for (int i = 0; i < packets.size(); i++) {
                if (i == dropIndex) continue;
                byte[] p = packets.get(i);
                data.send(new DatagramPacket(p, p.length, LOCAL, dataPort));
                Thread.sleep(20);
            }
            // Answer the resend request for the missing packet.
            control.setSoTimeout(3000);
            DatagramPacket request = new DatagramPacket(new byte[64], 64);
            try {
                control.receive(request);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("No resend request for the lost packet");
            }
            byte[] r = request.getData();
            TestSupport.check((r[1] & 0xff) == 0xd5, "resend request type");
            int missing = Util.getU16(r, 4), count = Util.getU16(r, 6);
            byte[] dropped = packets.get(dropIndex);
            TestSupport.check(missing == Util.getU16(dropped, 2) && count == 1, "resend asks for the lost packet");
            byte[] resend = new byte[4 + dropped.length];
            resend[0] = (byte) 0x80;
            resend[1] = (byte) 0xd6;
            System.arraycopy(dropped, 0, resend, 4, dropped.length);
            control.send(new DatagramPacket(resend, resend.length, LOCAL, serverControl));
        } finally {
            data.close();
        }
    }

    static void airPlay1() throws Exception {
        FakePlatform platform = new FakePlatform();
        Events events = new Events();
        AirPlayConfig config = new AirPlayConfig("Test Kiosk", "02:AA:BB:CC:DD:EF", 0, AirPlayConfig.DEFAULT_FEATURES, false, 200);
        AirPlayServer server = new AirPlayServer(config, platform, events);
        int port = server.start();
        DatagramSocket control = new DatagramSocket(0, LOCAL);
        try {
            TestSupport.AlacFixture fixture = TestSupport.alacFixture();
            Client client = new Client(port);
            client.ok("OPTIONS", "*", null, null);
            String sdp = "v=0\r\no=iTunes 1 0 IN IP4 127.0.0.1\r\ns=Old Mac\r\nm=audio 0 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 AppleLossless\r\na=fmtp:96 " + fixture.frameLength + " 0 16 40 10 14 2 255 0 0 44100\r\n";
            client.ok("ANNOUNCE", "rtsp://x/1", "application/sdp", sdp.getBytes(Util.UTF8));
            RtspMessage setup = client.ok("SETUP", "rtsp://x/1", null, null,
                    "Transport", "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=" + control.getLocalPort() + ";timing_port=0");
            String transport = setup.header("Transport");
            int dataPort = 0, serverControl = 0;
            for (String part : transport.split(";")) {
                if (part.startsWith("server_port=")) dataPort = Integer.parseInt(part.substring(12));
                if (part.startsWith("control_port=")) serverControl = Integer.parseInt(part.substring(13));
            }
            TestSupport.check(dataPort > 0 && serverControl > 0, "AirPlay 1 ports");
            client.ok("RECORD", "rtsp://x/1", null, null, "RTP-Info", "seq=65530;rtptime=1234567");
            streamAudio(fixture, null, null, dataPort, serverControl, control, 3);
            short[] expected = TestSupport.pcmFixture();
            waitFor(platform, expected.length);
            short[] got = platform.pcm();
            int first = firstSound(got);
            TestSupport.check(first >= 0 && got.length - first >= expected.length, "AirPlay 1 audio played");
            for (int i = 0; i < expected.length; i++) TestSupport.check(got[first + i] == expected[i], "AirPlay 1 PCM sample " + i);
            TestSupport.check(events.seen.contains("session:Old Mac"), "AirPlay 1 sender name " + events.seen);
            client.send("TEARDOWN", "rtsp://x/1", null, null);
        } finally {
            control.close();
            server.stop();
        }
    }

    static byte[] dmapTitle(String title) {
        byte[] t = title.getBytes(Util.UTF8);
        byte[] b = new byte[16 + t.length];
        int len = 8 + t.length;
        byte[] header = {'m', 'l', 'i', 't', (byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len,
                'm', 'i', 'n', 'm', (byte) (t.length >>> 24), (byte) (t.length >>> 16), (byte) (t.length >>> 8), (byte) t.length};
        System.arraycopy(header, 0, b, 0, 16);
        System.arraycopy(t, 0, b, 16, t.length);
        return b;
    }

    static void waitFor(FakePlatform platform, int samples) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            short[] got = platform.pcm();
            int first = firstSound(got);
            if (first >= 0 && got.length - first >= samples) return;
            Thread.sleep(50);
        }
    }

    /** The fixture starts with a sine wave, so the first non-zero sample marks where it begins. */
    static int firstSound(short[] pcm) {
        for (int i = 0; i < pcm.length; i++) if (pcm[i] != 0) return i - (i % 2 == 0 ? 0 : 1);
        return -1;
    }
}
