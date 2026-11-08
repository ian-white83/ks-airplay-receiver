// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;

import ks.airplay.Util;

/**
 * The RSA key this plugin signs ADB connections with, the same kind adb on a computer keeps in ~/.android/adbkey.
 * Android shows its public key in the "Allow USB debugging?" prompt and remembers it in /data/misc/adb/adb_keys.
 */
public final class AdbKey {
    static final int BITS = 2048;
    /** DER prefix of a SHA-1 DigestInfo: adbd checks the token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final RSAPrivateCrtKey key;
    private final String name;

    AdbKey(RSAPrivateCrtKey key, String name) {
        if (key.getModulus().bitLength() != BITS) throw new IllegalArgumentException("ADB keys are " + BITS + "-bit RSA");
        this.key = key;
        this.name = name;
    }

    public static AdbKey generate(String name) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4), new SecureRandom());
        return new AdbKey((RSAPrivateCrtKey) g.generateKeyPair().getPrivate(), name);
    }

    public static AdbKey fromPkcs8(byte[] der, String name) throws GeneralSecurityException {
        return new AdbKey((RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)), name);
    }

    /** The key saved in file, or a new one written there with owner-only permissions. */
    public static AdbKey loadOrCreate(File file, String name) throws IOException, GeneralSecurityException {
        if (file.isFile()) {
            try {
                return fromPkcs8(readAll(file), name);
            } catch (GeneralSecurityException | RuntimeException e) {
                // unreadable: replace it below; Android asks to approve the new key once
            }
        }
        AdbKey k = generate(name);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(k.key.getEncoded());
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(false, false);
        tmp.setWritable(true, true);
        if (!tmp.renameTo(file)) throw new IOException("Cannot save " + file);
        return k;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) throw new IOException("Short read");
                n += r;
            }
            return b;
        }
    }

    /** PKCS#1 v1.5 signature of the 20-byte token adbd sends, taken as an already hashed SHA-1 digest. */
    public byte[] sign(byte[] token) {
        int k = BITS / 8;
        byte[] em = new byte[k];
        int t = SHA1_PREFIX.length + token.length;
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < k - t - 1; i++) em[i] = (byte) 0xff;
        em[k - t - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, k - t, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, k - token.length, token.length);
        BigInteger m = new BigInteger(1, em);
        // CRT: s = m^d mod n, as two half-size exponentiations.
        BigInteger p = key.getPrimeP(), q = key.getPrimeQ();
        BigInteger s1 = m.modPow(key.getPrimeExponentP(), p);
        BigInteger s2 = m.modPow(key.getPrimeExponentQ(), q);
        BigInteger h = key.getCrtCoefficient().multiply(s1.subtract(s2)).mod(p);
        return unsigned(s2.add(h.multiply(q)), k);
    }

    /**
     * The public key as adbd reads it: Android's RSAPublicKey struct (word count, n0inv, modulus and R^2 mod n as
     * little-endian 32-bit words, exponent), base64, then a space and the key's name.
     */
    public String publicKey() {
        BigInteger n = key.getModulus();
        int words = BITS / 32;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, BigInteger.ONE.shiftLeft(BITS * 2).mod(n), words);
        b.putInt(key.getPublicExponent().intValue());
        return Util.base64(b.array()) + " " + name;
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        byte[] be = unsigned(v, words * 4);
        for (int i = be.length - 1; i >= 0; i--) b.put(be[i]);
    }

    private static byte[] unsigned(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }
}
