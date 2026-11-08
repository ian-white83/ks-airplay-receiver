// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ks.airplay.crypto.Ed25519;
import ks.airplay.crypto.X25519;

/**
 * AirPlay "legacy" pairing for one connection: pair-setup swaps Ed25519 keys and pair-verify agrees an X25519
 * secret, with each side signing both ephemeral keys. The secret later salts the stream key. Senders only pair
 * when feature bit 27 is advertised.
 */
public final class Pairing {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] seed;
    private final byte[] publicKey;
    private byte[] theirEd;
    private byte[] theirCurve;
    private byte[] ourCurve;
    private byte[] secret;
    private boolean verified;

    public Pairing(byte[] seed, byte[] publicKey) {
        this.seed = seed;
        this.publicKey = publicKey;
    }

    /** POST /pair-setup: the sender's 32-byte Ed25519 key in, ours out. */
    public byte[] setup(byte[] body) {
        if (body == null || body.length != 32) throw new IllegalArgumentException("pair-setup body must be 32 bytes");
        theirEd = body.clone();
        return publicKey.clone();
    }

    /** POST /pair-verify. Returns the response body, empty for the second step. */
    public byte[] verify(byte[] body) throws Exception {
        if (body == null || body.length < 4) throw new IllegalArgumentException("pair-verify body too short");
        if (body[0] == 1) {
            if (body.length != 68) throw new IllegalArgumentException("pair-verify step 1 must be 68 bytes");
            theirCurve = Arrays.copyOfRange(body, 4, 36);
            theirEd = Arrays.copyOfRange(body, 36, 68);
            byte[] ourPrivate = X25519.generatePrivateKey(RANDOM);
            ourCurve = X25519.publicKey(ourPrivate);
            secret = X25519.sharedSecret(ourPrivate, theirCurve);
            byte[] signed = concat(ourCurve, theirCurve);
            byte[] signature = Ed25519.sign(seed, signed);
            byte[] encrypted = cipher().update(signature);
            return concat(ourCurve, encrypted);
        }
        if (secret == null || body.length != 68) throw new IllegalArgumentException("pair-verify step 2 out of order");
        Cipher cipher = cipher();
        cipher.update(new byte[64]); // our signature used the first 64 bytes of the stream
        byte[] signature = cipher.update(Arrays.copyOfRange(body, 4, 68));
        verified = Ed25519.verify(theirEd, concat(theirCurve, ourCurve), signature);
        if (!verified) throw new SecurityException("pair-verify signature mismatch");
        return new byte[0];
    }

    /** The X25519 secret once pair-verify has finished, otherwise null. */
    public byte[] secret() {
        return verified ? secret.clone() : null;
    }

    private Cipher cipher() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update("Pair-Verify-AES-Key".getBytes(Util.UTF8));
        sha.update(secret);
        byte[] key = Arrays.copyOf(sha.digest(), 16);
        sha.update("Pair-Verify-AES-IV".getBytes(Util.UTF8));
        sha.update(secret);
        byte[] iv = Arrays.copyOf(sha.digest(), 16);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
