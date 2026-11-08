// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Ed25519 signatures (RFC 8032), pure Java. Android only ships Ed25519 from API 33, so the plugin brings its own.
 * Speed is not a concern: AirPlay signs and verifies once per connection.
 */
public final class Ed25519 {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger TWO = BigInteger.valueOf(2);
    static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.multiply(TWO).mod(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final Point B;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, 0);
        B = new Point(bx, by, BigInteger.ONE, bx.multiply(by).mod(P));
    }

    private Ed25519() {}

    /** Extended homogeneous coordinates. */
    static final class Point {
        final BigInteger x, y, z, t;
        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }

        Point add(Point q) {
            BigInteger a = y.subtract(x).multiply(q.y.subtract(q.x)).mod(P);
            BigInteger b = y.add(x).multiply(q.y.add(q.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(q.t).mod(P);
            BigInteger d = z.multiply(TWO).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point multiply(BigInteger s) {
            Point result = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
            Point addend = this;
            for (int i = 0; i < s.bitLength(); i++) {
                if (s.testBit(i)) result = result.add(addend);
                addend = addend.add(addend);
            }
            return result;
        }

        byte[] encode() {
            BigInteger zi = z.modInverse(P);
            BigInteger ax = x.multiply(zi).mod(P), ay = y.multiply(zi).mod(P);
            byte[] out = toLittleEndian(ay, 32);
            if (ax.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        if (y.compareTo(P) >= 0) return null;
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) return sign == 0 ? BigInteger.ZERO : null;
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) return null;
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return x;
    }

    static Point decode(byte[] s) {
        if (s.length != 32) return null;
        byte[] copy = s.clone();
        int sign = (copy[31] & 0xff) >> 7;
        copy[31] &= 0x7f;
        BigInteger y = fromLittleEndian(copy);
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger secretScalar(byte[] h) {
        byte[] a = Arrays.copyOf(h, 32);
        a[0] &= (byte) 248;
        a[31] &= 127;
        a[31] |= 64;
        return fromLittleEndian(a);
    }

    /** The 32-byte public key for a 32-byte seed. */
    public static byte[] publicKey(byte[] seed) {
        return B.multiply(secretScalar(sha512(seed))).encode();
    }

    /** A 64-byte signature of message with the 32-byte seed. */
    public static byte[] sign(byte[] seed, byte[] message) {
        byte[] h = sha512(seed);
        BigInteger a = secretScalar(h);
        byte[] publicKey = B.multiply(a).encode();
        byte[] prefix = Arrays.copyOfRange(h, 32, 64);
        BigInteger r = fromLittleEndian(sha512(prefix, message)).mod(L);
        byte[] encodedR = B.multiply(r).encode();
        BigInteger k = fromLittleEndian(sha512(encodedR, publicKey, message)).mod(L);
        BigInteger s = r.add(k.multiply(a)).mod(L);
        byte[] signature = new byte[64];
        System.arraycopy(encodedR, 0, signature, 0, 32);
        System.arraycopy(toLittleEndian(s, 32), 0, signature, 32, 32);
        return signature;
    }

    /** True when signature is a valid signature of message by publicKey. */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || signature == null || publicKey.length != 32 || signature.length != 64) return false;
        Point a = decode(publicKey);
        Point r = decode(Arrays.copyOf(signature, 32));
        if (a == null || r == null) return false;
        BigInteger s = fromLittleEndian(Arrays.copyOfRange(signature, 32, 64));
        if (s.compareTo(L) >= 0) return false;
        BigInteger k = fromLittleEndian(sha512(Arrays.copyOf(signature, 32), publicKey, message)).mod(L);
        return Arrays.equals(B.multiply(s).encode(), r.add(a.multiply(k)).encode());
    }

    static BigInteger fromLittleEndian(byte[] bytes) {
        byte[] reversed = new byte[bytes.length + 1];
        for (int i = 0; i < bytes.length; i++) reversed[bytes.length - i] = bytes[i];
        return new BigInteger(reversed);
    }

    static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < length && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }
}
