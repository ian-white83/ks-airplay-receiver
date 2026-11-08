// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.hap;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** HomeKit TLV8: type, length, value, with values over 255 bytes split into consecutive items of one type. */
public final class Tlv8 {
    public static final int METHOD = 0, IDENTIFIER = 1, SALT = 2, PUBLIC_KEY = 3, PROOF = 4, ENCRYPTED_DATA = 5,
            STATE = 6, ERROR = 7, SIGNATURE = 10, FLAGS = 19;
    public static final int ERROR_AUTHENTICATION = 2;
    public static final int FLAG_TRANSIENT = 0x10;

    private Tlv8() {}

    public static Map<Integer, byte[]> parse(byte[] data) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int pos = 0, last = -1;
        while (data != null && pos + 2 <= data.length) {
            int type = data[pos] & 0xff, len = data[pos + 1] & 0xff;
            pos += 2;
            if (pos + len > data.length) break;
            byte[] value = new byte[len];
            System.arraycopy(data, pos, value, 0, len);
            pos += len;
            byte[] prev = out.get(type);
            if (prev != null && type == last) {
                byte[] joined = new byte[prev.length + len];
                System.arraycopy(prev, 0, joined, 0, prev.length);
                System.arraycopy(value, 0, joined, prev.length, len);
                out.put(type, joined);
            } else {
                out.put(type, value);
            }
            last = type;
        }
        return out;
    }

    /** Builds a TLV8 body from type/value pairs in order. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer add(int type, byte[] value) {
            int pos = 0;
            do {
                int n = Math.min(255, value.length - pos);
                out.write(type);
                out.write(n);
                out.write(value, pos, n);
                pos += n;
            } while (pos < value.length);
            return this;
        }

        public Writer add(int type, int oneByte) {
            return add(type, new byte[] {(byte) oneByte});
        }

        public byte[] bytes() { return out.toByteArray(); }
    }

    public static int intValue(Map<Integer, byte[]> tlv, int type, int fallback) {
        byte[] v = tlv.get(type);
        return v == null || v.length == 0 ? fallback : v[0] & 0xff;
    }
}
