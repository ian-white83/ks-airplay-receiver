// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.adb;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** ADB client and Shizuku starter against a fake adbd that checks signatures the way the real one does. */
public final class AdbTest {
    static void check(boolean c, String what) {
        if (!c) throw new AssertionError("Failed: " + what);
    }

    /** Parse Android's RSAPublicKey struct back into a Java key, checking n0inv and R^2 mod n on the way. */
    static PublicKey parse(String line) throws Exception {
        String b64 = line.substring(0, line.indexOf(' '));
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN);
        check(b.remaining() == 524, "key struct is 524 bytes");
        int words = b.getInt();
        check(words == 64, "64 words");
        long n0inv = b.getInt() & 0xffffffffL;
        BigInteger n = words(b, words), rr = words(b, words);
        int e = b.getInt();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        check(n.multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(r32).signum() == 0, "n0inv = -1/n mod 2^32");
        check(rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n)), "rr = R^2 mod n");
        check(e == 65537, "exponent");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, BigInteger.valueOf(e)));
    }

    static BigInteger words(ByteBuffer b, int words) {
        byte[] be = new byte[words * 4];
        for (int i = be.length - 1; i >= 0; i--) be[i] = b.get();
        return new BigInteger(1, be);
    }

    /** Behaves like adbd on a LineageOS userdebug build with Rooted debugging on. */
    static final class FakeAdbd implements Runnable {
        final ServerSocket server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        final Set<String> trusted = ConcurrentHashMap.newKeySet();
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile boolean root, approve = true, rootAllowed = true;
        volatile int prompts;

        FakeAdbd() throws IOException {
            Thread t = new Thread(this, "fake-adbd");
            t.setDaemon(true);
            t.start();
        }

        @Override public void run() {
            while (true) {
                try (Socket s = server.accept()) {
                    serve(s);
                } catch (Throwable e) {
                    if (server.isClosed()) return;
                    if (!(e instanceof IOException)) e.printStackTrace();
                }
            }
        }

        void serve(Socket s) throws Exception {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            int[] m = new int[3];
            byte[] d = read(in, m);
            check(m[0] == AdbClient.CNXN, "client opens with CNXN");
            byte[] token = new byte[20];
            new SecureRandom().nextBytes(token);
            send(out, AdbClient.AUTH, 1, 0, token);
            boolean ok = false;
            while (!ok) {
                d = read(in, m);
                check(m[0] == AdbClient.AUTH, "AUTH reply");
                if (m[1] == 2) {
                    for (String k : trusted) {
                        Signature v = Signature.getInstance("NONEwithRSA");
                        v.initVerify(parse(k));
                        byte[] digestInfo = new byte[35];
                        System.arraycopy(new byte[] {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14}, 0, digestInfo, 0, 15);
                        System.arraycopy(token, 0, digestInfo, 15, 20);
                        v.update(digestInfo);
                        try {
                            if (v.verify(d)) ok = true;
                        } catch (Exception ignored) {
                        }
                    }
                    if (!ok) send(out, AdbClient.AUTH, 1, 0, token);
                } else if (m[1] == 3) {
                    String key = new String(d, "UTF-8");
                    check(key.endsWith("\0"), "public key ends with NUL");
                    prompts++;
                    parse(key.substring(0, key.length() - 1));
                    if (!approve) return; // adbd closes on deny
                    trusted.add(key.substring(0, key.length() - 1));
                    ok = true;
                }
            }
            send(out, AdbClient.CNXN, AdbClient.VERSION, 4096, "device::\0".getBytes("UTF-8"));
            int remote = 100;
            while (true) {
                try {
                    d = read(in, m);
                } catch (EOFException e) {
                    return;
                }
                if (m[0] != AdbClient.OPEN) continue;
                String service = new String(d, 0, d.length - 1, "UTF-8");
                int local = m[1], id = remote++;
                send(out, AdbClient.OKAY, id, local, new byte[0]);
                String reply;
                if (service.equals("root:")) {
                    if (!rootAllowed) reply = "ADB Root access is disabled by system setting - enable in settings -> development options\n";
                    else if (root) reply = "adbd is already running as root\n";
                    else {
                        send(out, AdbClient.WRTE, id, local, "restarting adbd as root\n".getBytes("UTF-8"));
                        root = true;
                        return; // adbd restarts
                    }
                } else if (service.equals("unroot:")) {
                    send(out, AdbClient.WRTE, id, local, "restarting adbd as non root\n".getBytes("UTF-8"));
                    root = false;
                    return; // adbd restarts
                } else if (service.equals("shell:id -u")) {
                    reply = root ? "0\n" : "2000\n";
                } else {
                    commands.add(service);
                    reply = service.contains("libshizuku") ? "info: shizuku_starter exit with 0\n" : "";
                }
                if (!reply.isEmpty()) {
                    send(out, AdbClient.WRTE, id, local, reply.getBytes("UTF-8"));
                    read(in, m);
                    check(m[0] == AdbClient.OKAY, "client acknowledges output");
                }
                send(out, AdbClient.CLSE, id, local, new byte[0]);
            }
        }

        static byte[] read(DataInputStream in, int[] m) throws IOException {
            byte[] h = new byte[24];
            in.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            m[0] = b.getInt();
            m[1] = b.getInt();
            m[2] = b.getInt();
            int len = b.getInt(), sum = b.getInt(), magic = b.getInt();
            check(magic == ~m[0], "magic");
            byte[] d = new byte[len];
            in.readFully(d);
            int s = 0;
            for (byte x : d) s += x & 0xff;
            check(s == sum, "checksum");
            return d;
        }

        static void send(OutputStream out, int cmd, int a0, int a1, byte[] d) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(0).putInt(~cmd).put(d);
            out.write(b.array());
            out.flush();
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("adbkey").toFile();
        File file = new File(dir, "adbkey");
        AdbKey key = AdbKey.loadOrCreate(file, "ks-airplay@kiosk");
        check(file.isFile(), "key saved");
        check(AdbKey.loadOrCreate(file, "ks-airplay@kiosk").publicKey().equals(key.publicKey()), "same key after reload");
        check(key.publicKey().endsWith(" ks-airplay@kiosk"), "key name");

        // The signature is exactly what Java's SHA1withRSA would produce for data hashing to the token.
        byte[] data = "hello".getBytes("UTF-8");
        byte[] token = java.security.MessageDigest.getInstance("SHA-1").digest(data);
        Signature v = Signature.getInstance("SHA1withRSA");
        v.initVerify(parse(key.publicKey()));
        v.update(data);
        check(v.verify(key.sign(token)), "signature verifies as SHA1withRSA");

        // The key survives as a secret setting: same public key and signatures after a round trip.
        String secret = key.toSecret();
        check(secret.startsWith("v1:") && secret.length() <= 512, "secret fits a 512-character setting: " + secret.length());
        AdbKey again = AdbKey.fromSecret(secret, "ks-airplay@kiosk");
        check(again.publicKey().equals(key.publicKey()), "same public key from the secret");
        check(java.util.Arrays.equals(again.sign(token), key.sign(token)), "same signature from the secret");
        for (int i = 0; i < 5; i++) {
            AdbKey k = AdbKey.generate("x");
            check(AdbKey.fromSecret(k.toSecret(), "x").publicKey().equals(k.publicKey()), "round trip " + i);
        }
        byte[] raw = new byte[257];
        new SecureRandom().nextBytes(raw);
        check(java.util.Arrays.equals(ks.airplay.Util.unbase64(ks.airplay.Util.base64(raw)), raw), "base64 round trip");

        FakeAdbd adbd = new FakeAdbd();
        int port = adbd.server.getLocalPort();
        List<String> steps = new ArrayList<>();
        ShizukuStarter.Progress progress = m -> {
            if (m != null) steps.add(m);
            return true;
        };

        // First start: the prompt, then root, then the starter.
        String out = new ShizukuStarter(key, "127.0.0.1", port).run(false, 5000, 5000, progress);
        check(adbd.prompts == 1, "asked once to approve the key");
        check(steps.stream().anyMatch(s -> s.contains("Always allow")), "told the user to approve");
        check(adbd.root, "switched adbd to root");
        check(out.contains("exit with 0") || adbd.commands.size() == 1, "ran the starter");
        String script = adbd.commands.get(0);
        check(!script.contains("setprop") && !script.contains("adb_keys"), "changes nothing on the device but Shizuku");
        check(script.contains("libshizuku.so"), "finds the starter in Shizuku's package");
        check(!script.contains("start.sh") && !script.contains("/storage") && !script.contains("sdcard"), "never runs a script from shared storage");

        // Next boot: adbd starts as shell again, the key is known, no prompt.
        adbd.root = false;
        adbd.commands.clear();
        new ShizukuStarter(key, "127.0.0.1", port).run(false, 5000, 5000, progress);
        check(adbd.prompts == 1, "no second prompt");

        // Once Shizuku is up, ADB goes back to non-root.
        check(adbd.root, "root after the start");
        new ShizukuStarter(key, "127.0.0.1", port).unroot(5000, 5000, progress);
        check(!adbd.root, "unroot: switches adbd back");
        check(new ShizukuStarter(key, "127.0.0.1", port).unroot(5000, 5000, progress).contains("already"), "unroot when not root changes nothing");
        new ShizukuStarter(key, "127.0.0.1", port).run(true, 5000, 5000, progress);

        // Already root, check only.
        adbd.commands.clear();
        new ShizukuStarter(key, "127.0.0.1", port).run(true, 5000, 5000, progress);
        check(adbd.commands.stream().noneMatch(c -> c.contains("shizuku") || c.contains("setprop")), "check only changes nothing");

        // Rooted debugging off.
        adbd.root = false;
        adbd.rootAllowed = false;
        try {
            new ShizukuStarter(key, "127.0.0.1", port).run(false, 5000, 5000, progress);
            check(false, "should fail without rooted debugging");
        } catch (IOException e) {
            check(e.getMessage().contains("Rooted debugging"), "says to turn on Rooted debugging: " + e.getMessage());
        }

        // A new key that nobody approves.
        adbd.rootAllowed = true;
        adbd.approve = false;
        try {
            new ShizukuStarter(AdbKey.generate("other"), "127.0.0.1", port).run(false, 5000, 2000, progress);
            check(false, "should fail when the key is refused");
        } catch (AdbClient.NotApproved expected) {
        }

        // Nothing listening.
        ServerSocket closed = new ServerSocket(0);
        int dead = closed.getLocalPort();
        closed.close();
        try {
            new ShizukuStarter(key, "127.0.0.1", dead).run(false, 100, 1000, progress);
            check(false, "should fail with nothing listening");
        } catch (IOException e) {
            check(e.getMessage().contains("port " + dead), "explains the port: " + e.getMessage());
        }
        adbd.server.close();
        System.out.println("AdbTest passed");
    }
}
