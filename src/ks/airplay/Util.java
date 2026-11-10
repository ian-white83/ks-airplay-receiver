// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers that work on Android API 24 and on a desktop JVM for tests. */
public final class Util {
    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Util() {}

    /** Lenient base64 decoding: ignores whitespace and missing padding, as AirPlay senders vary. */
    public static byte[] base64(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') break;
            int v = c == '-' ? 62 : c == '_' ? 63 : B64.indexOf(c);
            if (v < 0) continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    /** Standard base64 with padding and no line breaks. */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int n = (data[i] & 0xff) << 16 | (i + 1 < data.length ? (data[i + 1] & 0xff) << 8 : 0) | (i + 2 < data.length ? data[i + 2] & 0xff : 0);
            sb.append(B64.charAt(n >> 18 & 63)).append(B64.charAt(n >> 12 & 63));
            sb.append(i + 1 < data.length ? B64.charAt(n >> 6 & 63) : '=').append(i + 2 < data.length ? B64.charAt(n & 63) : '=');
        }
        return sb.toString();
    }

    /** Decode standard base64, padding optional, whitespace ignored. */
    public static byte[] unbase64(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length() * 3 / 4);
        int bits = 0, value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') break;
            if (Character.isWhitespace(c)) continue;
            int v = B64.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not base64: " + c);
            value = value << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write(value >> bits & 0xff);
                value &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    public static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /**
     * Track metadata from a DMAP (application/x-dmap-tagged) body: title (minm), artist (asar), album (asal) and
     * genre (asgn). Unknown tags are skipped.
     */
    public static Map<String, String> parseDmap(byte[] data) {
        Map<String, String> out = new LinkedHashMap<>();
        parseDmap(data, 0, data == null ? 0 : data.length, out, 0);
        return out;
    }

    private static void parseDmap(byte[] data, int start, int end, Map<String, String> out, int depth) {
        int pos = start;
        while (pos + 8 <= end) {
            String tag = new String(data, pos, 4, Charset.forName("US-ASCII"));
            int len = ((data[pos + 4] & 0xff) << 24) | ((data[pos + 5] & 0xff) << 16) | ((data[pos + 6] & 0xff) << 8) | (data[pos + 7] & 0xff);
            pos += 8;
            if (len < 0 || pos + len > end) return;
            if (tag.equals("mlit") || tag.equals("mlcl") || tag.equals("cmst")) {
                if (depth < 4) parseDmap(data, pos, pos + len, out, depth + 1);
            } else if (tag.equals("minm")) {
                out.put("title", new String(data, pos, len, UTF8));
            } else if (tag.equals("asar")) {
                out.put("artist", new String(data, pos, len, UTF8));
            } else if (tag.equals("asal")) {
                out.put("album", new String(data, pos, len, UTF8));
            } else if (tag.equals("asgn")) {
                out.put("genre", new String(data, pos, len, UTF8));
            }
            pos += len;
        }
    }

    /** DNS TXT record wire format of key=value pairs, as AirPlay's /info echoes it. */
    public static byte[] txtRecord(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] item = (e.getKey() + "=" + e.getValue()).getBytes(UTF8);
            int len = Math.min(item.length, 255);
            out.write(len);
            out.write(item, 0, len);
        }
        return out.toByteArray();
    }

    /** 64-bit NTP timestamp for a wall clock time in nanoseconds since 1970. */
    public static long ntpTime(long nanosSince1970) {
        long seconds = nanosSince1970 / 1_000_000_000L + 2208988800L;
        long fraction = ((nanosSince1970 % 1_000_000_000L) << 32) / 1_000_000_000L;
        return (seconds << 32) | (fraction & 0xffffffffL);
    }

    public static void putLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) { b[off + i] = (byte) v; v >>>= 8; }
    }

    public static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    public static int getU16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    public static long getU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    // ---------------------------------------------------------------- threads

    /** Where a guarded thread reports what went wrong. */
    public interface Logger { void log(String message); }

    /**
     * Wraps a task so nothing it throws, Errors included, leaves the thread. Plugins run inside Kiosk Satellite's
     * process: an uncaught throwable on any thread would take the whole app down.
     */
    public static Runnable guard(final String name, final Runnable task, final Logger log) {
        return new Runnable() {
            @Override public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    try {
                        if (log != null) log.log(name + " stopped: " + t);
                    } catch (Throwable ignored) {
                        // nothing more to do
                    }
                }
            }
        };
    }

    /** Starts a guarded daemon thread. */
    public static Thread thread(String name, Runnable task, Logger log) {
        Thread t = new Thread(guard(name, task, log), name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
