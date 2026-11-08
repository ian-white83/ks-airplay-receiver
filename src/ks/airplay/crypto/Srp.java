// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.crypto;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SRP-6a with the RFC 5054 3072-bit group, SHA-512 and HomeKit's padding rules (k and u hash padded values; M1
 * and M2 hash minimal ones). AirPlay transient pairing uses user "Pair-Setup" and PIN 3939.
 */
public final class Srp {
    public static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
            + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
            + "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
            + "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
            + "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C"
            + "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    public static final BigInteger G = BigInteger.valueOf(5);
    public static final int N_BYTES = 384;
    public static final String USER = "Pair-Setup";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Srp() {}

    static byte[] hash(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Big-endian bytes without leading zeros (or sign byte). */
    public static byte[] bytes(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    static byte[] pad(BigInteger n) {
        byte[] b = bytes(n);
        byte[] out = new byte[N_BYTES];
        System.arraycopy(b, 0, out, N_BYTES - b.length, b.length);
        return out;
    }

    static BigInteger num(byte[] b) { return new BigInteger(1, b); }

    static BigInteger k() { return num(hash(pad(N), pad(G))); }

    static BigInteger x(byte[] salt, String pin) {
        return num(hash(salt, hash((USER + ":" + pin).getBytes(UTF8))));
    }

    static byte[] m1(byte[] salt, BigInteger a, BigInteger b, byte[] key) {
        byte[] hn = hash(bytes(N)), hg = hash(bytes(G));
        byte[] xor = new byte[hn.length];
        for (int i = 0; i < xor.length; i++) xor[i] = (byte) (hn[i] ^ hg[i]);
        return hash(xor, hash(USER.getBytes(UTF8)), salt, bytes(a), bytes(b), key);
    }

    /** The accessory side: create with the PIN, send salt and B, then check the controller's proof. */
    public static final class Server {
        public final byte[] salt = new byte[16];
        private final BigInteger v, b, bPub;
        private byte[] key, m2;

        public Server(String pin, SecureRandom random) {
            random.nextBytes(salt);
            v = G.modPow(x(salt, pin), N);
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            b = num(secret);
            bPub = k().multiply(v).add(G.modPow(b, N)).mod(N);
        }

        public byte[] publicKey() { return bytes(bPub); }

        /** Check A and M1. Returns M2 on success, null when the proof is wrong. */
        public byte[] verify(byte[] aBytes, byte[] m1) {
            BigInteger a = num(aBytes);
            if (a.mod(N).signum() == 0) return null;
            BigInteger u = num(hash(pad(a), pad(bPub)));
            if (u.signum() == 0) return null;
            BigInteger s = a.multiply(v.modPow(u, N)).modPow(b, N);
            byte[] k = hash(bytes(s));
            byte[] expected = Srp.m1(salt, a, bPub, k);
            if (!MessageDigest.isEqual(expected, m1)) return null;
            key = k;
            m2 = hash(bytes(a), m1, k);
            return m2.clone();
        }

        /** The 64-byte session key K once verified. */
        public byte[] sessionKey() { return key == null ? null : key.clone(); }
    }

    /** The controller side, used by tests to act as an iPhone. */
    public static final class Client {
        private final BigInteger a, aPub;
        private byte[] key, m1;

        public Client(SecureRandom random) {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            a = num(secret);
            aPub = G.modPow(a, N);
        }

        public byte[] publicKey() { return bytes(aPub); }

        /** Process salt and B; returns M1. */
        public byte[] proof(String pin, byte[] salt, byte[] bBytes) {
            BigInteger bPub = num(bBytes);
            BigInteger u = num(hash(pad(aPub), pad(bPub)));
            BigInteger xx = x(salt, pin);
            BigInteger base = bPub.subtract(k().multiply(G.modPow(xx, N))).mod(N);
            BigInteger s = base.modPow(a.add(u.multiply(xx)), N);
            key = hash(bytes(s));
            m1 = Srp.m1(salt, aPub, bPub, key);
            return m1.clone();
        }

        public boolean checkServer(byte[] m2) {
            return MessageDigest.isEqual(hash(bytes(aPub), m1, key), m2);
        }

        public byte[] sessionKey() { return key.clone(); }
    }
}
