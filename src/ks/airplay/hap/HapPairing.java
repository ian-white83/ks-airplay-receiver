// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.hap;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

import ks.airplay.crypto.ChaCha20Poly1305;
import ks.airplay.crypto.Ed25519;
import ks.airplay.crypto.Hkdf;
import ks.airplay.crypto.Srp;
import ks.airplay.crypto.X25519;

/**
 * The accessory side of HomeKit pairing as AirPlay 2 uses it, following shairport-sync's pair_ap:
 * <ul>
 * <li>Transient pair-setup (M1-M4, PIN 3939): the SRP session key becomes the shared secret and the control
 * channel is encrypted from the next message on. This is what iOS uses to stream and group.</li>
 * <li>Normal pair-setup (M5-M6) and pair-verify are answered so the Home app can proceed, but every controller
 * is accepted and, as in shairport-sync, they do not switch on encryption.</li>
 * </ul>
 */
public final class HapPairing {
    public static final String TRANSIENT_PIN = "3939";
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String accessoryId;
    private final byte[] seed, publicKey;
    private Srp.Server srp;
    private boolean transientSetup;
    private byte[] sharedSecret;
    private byte[] verifySecret, ourCurve, theirCurve;

    public HapPairing(String accessoryId, byte[] seed, byte[] publicKey) {
        this.accessoryId = accessoryId;
        this.seed = seed;
        this.publicKey = publicKey;
    }

    /**
     * The secret that encrypts the control channel, set once transient pair-setup completes. The caller switches
     * on encryption after sending the response that produced it.
     */
    public byte[] sharedSecret() { return sharedSecret == null ? null : sharedSecret.clone(); }

    /** POST /pair-setup. */
    public byte[] setup(byte[] body) throws Exception {
        Map<Integer, byte[]> in = Tlv8.parse(body);
        int state = Tlv8.intValue(in, Tlv8.STATE, -1);
        switch (state) {
            case 1: {
                transientSetup = (Tlv8.intValue(in, Tlv8.FLAGS, 0) & Tlv8.FLAG_TRANSIENT) != 0;
                srp = new Srp.Server(TRANSIENT_PIN, RANDOM);
                return new Tlv8.Writer().add(Tlv8.STATE, 2).add(Tlv8.SALT, srp.salt).add(Tlv8.PUBLIC_KEY, srp.publicKey()).bytes();
            }
            case 3: {
                byte[] a = in.get(Tlv8.PUBLIC_KEY), proof = in.get(Tlv8.PROOF);
                byte[] m2 = srp == null || a == null || proof == null ? null : srp.verify(a, proof);
                if (m2 == null) return error(4);
                if (transientSetup) sharedSecret = srp.sessionKey();
                return new Tlv8.Writer().add(Tlv8.STATE, 4).add(Tlv8.PROOF, m2).bytes();
            }
            case 5: {
                byte[] key = srp == null ? null : srp.sessionKey();
                byte[] enc = in.get(Tlv8.ENCRYPTED_DATA);
                if (key == null || enc == null) return error(6);
                byte[] encKey = Hkdf.sha512(key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", 32);
                try {
                    ChaCha20Poly1305.open(encKey, ChaCha20Poly1305.labelNonce("PS-Msg05"), enc, 0, enc.length, null);
                } catch (Exception e) {
                    return error(6);
                }
                byte[] accessoryX = Hkdf.sha512(key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32);
                byte[] id = accessoryId.getBytes(ASCII);
                byte[] signature = Ed25519.sign(seed, concat(accessoryX, id, publicKey));
                byte[] inner = new Tlv8.Writer().add(Tlv8.IDENTIFIER, id).add(Tlv8.PUBLIC_KEY, publicKey).add(Tlv8.SIGNATURE, signature).bytes();
                byte[] sealed = ChaCha20Poly1305.seal(encKey, ChaCha20Poly1305.labelNonce("PS-Msg06"), inner, 0, inner.length, null);
                return new Tlv8.Writer().add(Tlv8.STATE, 6).add(Tlv8.ENCRYPTED_DATA, sealed).bytes();
            }
            default:
                return error(2);
        }
    }

    /** POST /pair-verify. */
    public byte[] verify(byte[] body) throws Exception {
        Map<Integer, byte[]> in = Tlv8.parse(body);
        int state = Tlv8.intValue(in, Tlv8.STATE, -1);
        if (state == 1) {
            theirCurve = in.get(Tlv8.PUBLIC_KEY);
            if (theirCurve == null || theirCurve.length != 32) return error(2);
            byte[] priv = X25519.generatePrivateKey(RANDOM);
            ourCurve = X25519.publicKey(priv);
            verifySecret = X25519.sharedSecret(priv, theirCurve);
            byte[] id = accessoryId.getBytes(ASCII);
            byte[] signature = Ed25519.sign(seed, concat(ourCurve, id, theirCurve));
            byte[] inner = new Tlv8.Writer().add(Tlv8.IDENTIFIER, id).add(Tlv8.SIGNATURE, signature).bytes();
            byte[] key = Hkdf.sha512(verifySecret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32);
            byte[] sealed = ChaCha20Poly1305.seal(key, ChaCha20Poly1305.labelNonce("PV-Msg02"), inner, 0, inner.length, null);
            return new Tlv8.Writer().add(Tlv8.STATE, 2).add(Tlv8.PUBLIC_KEY, ourCurve).add(Tlv8.ENCRYPTED_DATA, sealed).bytes();
        }
        if (state == 3) {
            byte[] enc = in.get(Tlv8.ENCRYPTED_DATA);
            if (verifySecret == null || enc == null) return error(4);
            byte[] key = Hkdf.sha512(verifySecret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32);
            try {
                ChaCha20Poly1305.open(key, ChaCha20Poly1305.labelNonce("PV-Msg03"), enc, 0, enc.length, null);
            } catch (Exception e) {
                return error(4);
            }
            return new Tlv8.Writer().add(Tlv8.STATE, 4).bytes();
        }
        return error(2);
    }

    private static byte[] error(int state) {
        return new Tlv8.Writer().add(Tlv8.STATE, state).add(Tlv8.ERROR, Tlv8.ERROR_AUTHENTICATION).bytes();
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    /** Keys for the control channel, from the accessory's point of view: {decrypt, encrypt}. */
    public static byte[][] controlKeys(byte[] secret) {
        return new byte[][] {
                Hkdf.sha512(secret, "Control-Salt", "Control-Write-Encryption-Key", 32),
                Hkdf.sha512(secret, "Control-Salt", "Control-Read-Encryption-Key", 32)};
    }

    static boolean same(byte[] a, byte[] b) { return Arrays.equals(a, b); }
}
