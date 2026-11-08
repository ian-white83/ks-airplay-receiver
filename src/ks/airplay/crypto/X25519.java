// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

/** X25519 key agreement (RFC 7748), pure Java. Android only ships it from API 33. */
public final class X25519 {
    private static final BigInteger P = Ed25519.P;
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE = new byte[32];

    static { BASE[0] = 9; }

    private X25519() {}

    /** A fresh 32-byte private key. */
    public static byte[] generatePrivateKey(SecureRandom random) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return key;
    }

    public static byte[] publicKey(byte[] privateKey) {
        return scalarMult(privateKey, BASE);
    }

    /** The shared secret of our private key and their public key. */
    public static byte[] sharedSecret(byte[] privateKey, byte[] theirPublicKey) {
        return scalarMult(privateKey, theirPublicKey);
    }

    static byte[] scalarMult(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kk = Ed25519.fromLittleEndian(k);
        byte[] u = uBytes.clone();
        u[31] &= 127;
        BigInteger x1 = Ed25519.fromLittleEndian(u).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int bit = kk.testBit(t) ? 1 : 0;
            swap ^= bit;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            BigInteger diff = da.subtract(cb).mod(P);
            z3 = x1.multiply(diff.multiply(diff)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
        }
        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }
        BigInteger result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return Ed25519.toLittleEndian(result, 32);
    }
}
