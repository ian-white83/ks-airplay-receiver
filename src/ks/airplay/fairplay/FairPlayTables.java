// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.fairplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Holds the FairPlay tables, which ship as assets/fairplay.bin instead of a huge static initializer. */
public final class FairPlayTables {
    static final int SIZE = 100352;
    private static volatile byte[] data;

    private FairPlayTables() {}

    /** Load the tables once. Later calls are ignored. */
    public static synchronized void load(InputStream in) throws IOException {
        if (data != null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream(SIZE);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        byte[] bytes = out.toByteArray();
        if (bytes.length != SIZE) throw new IOException("fairplay.bin has " + bytes.length + " bytes, expected " + SIZE);
        data = bytes;
    }

    public static boolean loaded() { return data != null; }

    static byte[] data() {
        byte[] bytes = data;
        if (bytes == null) throw new IllegalStateException("FairPlay tables are not loaded");
        return bytes;
    }
}
