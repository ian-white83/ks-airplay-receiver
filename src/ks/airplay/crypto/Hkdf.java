// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.crypto;

import java.nio.charset.Charset;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF with HMAC-SHA512 (RFC 5869), as HomeKit pairing derives its keys. */
public final class Hkdf {
    private Hkdf() {}

    public static byte[] sha512(byte[] ikm, String salt, String info, int length) {
        Charset ascii = Charset.forName("US-ASCII");
        return sha512(ikm, salt.getBytes(ascii), info.getBytes(ascii), length);
    }

    public static byte[] sha512(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[64] : salt, "HmacSHA512"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA512"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
