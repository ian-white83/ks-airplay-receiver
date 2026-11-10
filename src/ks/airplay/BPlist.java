// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small binary property list (bplist00) codec for AirPlay messages. Values map to Map (dictionary), List
 * (array), String, byte[] (data), Long (integer), Double (real), Boolean and null.
 */
public final class BPlist {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF16 = Charset.forName("UTF-16BE");

    private BPlist() {}

    // ---------------------------------------------------------------- reading

    public static Object parse(byte[] data) throws IOException {
        if (data == null || data.length < 40 || !new String(data, 0, 8, ASCII).equals("bplist00")) {
            throw new IOException("Not a binary plist");
        }
        int t = data.length - 32;
        int offsetSize = data[t + 6] & 0xff;
        int refSize = data[t + 7] & 0xff;
        long count = readUInt(data, t + 8, 8);
        long top = readUInt(data, t + 16, 8);
        long tableOffset = readUInt(data, t + 24, 8);
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8 || count <= 0 || count > 100000
                || top < 0 || top >= count || tableOffset < 8 || tableOffset + count * offsetSize > t) {
            throw new IOException("Malformed binary plist trailer");
        }
        int[] offsets = new int[(int) count];
        for (int i = 0; i < count; i++) {
            long offset = readUInt(data, (int) tableOffset + i * offsetSize, offsetSize);
            if (offset < 8 || offset >= tableOffset) throw new IOException("Malformed binary plist offset");
            offsets[i] = (int) offset;
        }
        return new Reader(data, offsets, refSize).read((int) top, 0);
    }

    /**
     * Most objects one parse may build. Objects can be shared by reference, so a tiny plist of nested arrays that
     * all point at the same child would otherwise expand to billions of objects.
     */
    static final int MAX_OBJECTS = 10000;

    private static final class Reader {
        final byte[] data;
        final int[] offsets;
        final int refSize;

        /** Objects and bytes left to build; both bound the work and memory one parse can take. */
        int objectsLeft = MAX_OBJECTS;
        long bytesLeft;

        Reader(byte[] data, int[] offsets, int refSize) {
            this.data = data;
            this.offsets = offsets;
            this.refSize = refSize;
            bytesLeft = 2L * data.length;
        }

        Object read(int ref, int depth) throws IOException {
            if (ref < 0 || ref >= offsets.length) throw new IOException("Bad object reference");
            if (depth > 32) throw new IOException("Binary plist nests too deeply");
            if (--objectsLeft < 0) throw new IOException("Binary plist has too many objects");
            int pos = offsets[ref];
            check(pos, 1);
            int marker = data[pos] & 0xff;
            int type = marker >> 4, info = marker & 0x0f;
            switch (type) {
                case 0x0:
                    if (info == 0x8) return Boolean.FALSE;
                    if (info == 0x9) return Boolean.TRUE;
                    return null;
                case 0x1: {
                    int size = 1 << info;
                    check(pos + 1, size);
                    if (size == 16) return readUInt(data, pos + 9, 8); // high word is zero for anything AirPlay sends
                    long value = readUInt(data, pos + 1, size);
                    return value;
                }
                case 0x2: {
                    int size = 1 << info;
                    check(pos + 1, size);
                    long bits = readUInt(data, pos + 1, size);
                    return size == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                }
                case 0x3: {
                    check(pos + 1, 8);
                    return Double.longBitsToDouble(readUInt(data, pos + 1, 8));
                }
                case 0x4: {
                    int[] lp = length(pos, info);
                    check(lp[1], lp[0]);
                    spend(lp[0]);
                    byte[] out = new byte[lp[0]];
                    System.arraycopy(data, lp[1], out, 0, lp[0]);
                    return out;
                }
                case 0x5: {
                    int[] lp = length(pos, info);
                    check(lp[1], lp[0]);
                    spend(lp[0]);
                    return new String(data, lp[1], lp[0], ASCII);
                }
                case 0x6: {
                    int[] lp = length(pos, info);
                    check(lp[1], 2L * lp[0]);
                    spend(2L * lp[0]);
                    return new String(data, lp[1], lp[0] * 2, UTF16);
                }
                case 0x8: {
                    check(pos + 1, info + 1);
                    return readUInt(data, pos + 1, info + 1);
                }
                case 0xA: {
                    int[] lp = length(pos, info);
                    check(lp[1], (long) lp[0] * refSize);
                    spend((long) lp[0] * 8);
                    List<Object> list = new ArrayList<>(lp[0]);
                    for (int i = 0; i < lp[0]; i++) list.add(read(ref(lp[1] + i * refSize), depth + 1));
                    return list;
                }
                case 0xD: {
                    int[] lp = length(pos, info);
                    check(lp[1], (long) lp[0] * refSize * 2);
                    spend((long) lp[0] * 16);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < lp[0]; i++) {
                        Object key = read(ref(lp[1] + i * refSize), depth + 1);
                        Object value = read(ref(lp[1] + (lp[0] + i) * refSize), depth + 1);
                        map.put(String.valueOf(key), value);
                    }
                    return map;
                }
                default:
                    throw new IOException("Unsupported plist object 0x" + Integer.toHexString(marker));
            }
        }

        int ref(int pos) throws IOException {
            check(pos, refSize);
            long r = readUInt(data, pos, refSize);
            if (r < 0 || r >= offsets.length) throw new IOException("Bad object reference");
            return (int) r;
        }

        /** {length, start of content}. */
        int[] length(int pos, int info) throws IOException {
            if (info != 0xf) return new int[] {info, pos + 1};
            check(pos + 1, 1);
            int marker = data[pos + 1] & 0xff;
            if (marker >> 4 != 0x1) throw new IOException("Bad plist length");
            int size = 1 << (marker & 0xf);
            check(pos + 2, size);
            long length = readUInt(data, pos + 2, size);
            if (length > data.length) throw new IOException("Bad plist length");
            return new int[] {(int) length, pos + 2 + size};
        }

        void check(long pos, long length) throws IOException {
            if (pos < 0 || length < 0 || pos + length > data.length - 32) throw new IOException("Truncated binary plist");
        }

        void spend(long bytes) throws IOException {
            bytesLeft -= bytes;
            if (bytesLeft < 0) throw new IOException("Binary plist expands too far");
        }
    }

    private static long readUInt(byte[] data, int pos, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) value = (value << 8) | (data[pos + i] & 0xff);
        return value;
    }

    // ---------------------------------------------------------------- writing

    public static byte[] write(Object root) {
        List<Object> objects = new ArrayList<>();
        flatten(root, objects);
        int count = objects.size();
        int refSize = count < 256 ? 1 : count < 65536 ? 2 : 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        long[] offsets = new long[count];
        // Objects are numbered in flatten order; containers reference later indices.
        for (int i = 0; i < count; i++) {
            offsets[i] = out.size();
            writeObject(out, objects.get(i), objects, i, refSize);
        }
        long tableOffset = out.size();
        int offsetSize = tableOffset < 256 ? 1 : tableOffset < 65536 ? 2 : 4;
        for (long offset : offsets) writeUInt(out, offset, offsetSize);
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(offsetSize);
        out.write(refSize);
        writeUInt(out, count, 8);
        writeUInt(out, 0, 8);
        writeUInt(out, tableOffset, 8);
        return out.toByteArray();
    }

    /** Depth-first numbering. Each container is followed by its children, recorded with their indices. */
    private static final class Node {
        final Object value;
        final int[] refs;
        Node(Object value, int[] refs) { this.value = value; this.refs = refs; }
    }

    @SuppressWarnings("unchecked")
    private static int flatten(Object value, List<Object> objects) {
        int index = objects.size();
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            int[] refs = new int[map.size() * 2];
            objects.add(null);
            int i = 0;
            for (String key : map.keySet()) refs[i++] = flatten(key, objects);
            for (Object v : map.values()) refs[i++] = flatten(v, objects);
            objects.set(index, new Node(map, refs));
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            int[] refs = new int[list.size()];
            objects.add(null);
            for (int i = 0; i < list.size(); i++) refs[i] = flatten(list.get(i), objects);
            objects.set(index, new Node(list, refs));
        } else {
            objects.add(new Node(value, null));
        }
        return index;
    }

    private static void writeObject(ByteArrayOutputStream out, Object entry, List<Object> objects, int index, int refSize) {
        Node node = (Node) entry;
        Object value = node.value;
        if (value instanceof Map) {
            writeMarker(out, 0xD, node.refs.length / 2);
            for (int ref : node.refs) writeUInt(out, ref, refSize);
        } else if (value instanceof List) {
            writeMarker(out, 0xA, node.refs.length);
            for (int ref : node.refs) writeUInt(out, ref, refSize);
        } else if (value == null) {
            out.write(0x00);
        } else if (value instanceof Boolean) {
            out.write((Boolean) value ? 0x09 : 0x08);
        } else if (value instanceof Double || value instanceof Float) {
            out.write(0x23);
            writeUInt(out, Double.doubleToLongBits(((Number) value).doubleValue()), 8);
        } else if (value instanceof Number) {
            long v = ((Number) value).longValue();
            if (v >= 0 && v < 0x100) { out.write(0x10); writeUInt(out, v, 1); }
            else if (v >= 0 && v < 0x10000) { out.write(0x11); writeUInt(out, v, 2); }
            else if (v >= 0 && v < 0x100000000L) { out.write(0x12); writeUInt(out, v, 4); }
            else { out.write(0x13); writeUInt(out, v, 8); }
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            writeMarker(out, 0x4, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else {
            String s = value.toString();
            boolean ascii = true;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7f) { ascii = false; break; }
            if (ascii) {
                writeMarker(out, 0x5, s.length());
                byte[] bytes = s.getBytes(ASCII);
                out.write(bytes, 0, bytes.length);
            } else {
                writeMarker(out, 0x6, s.length());
                byte[] bytes = s.getBytes(UTF16);
                out.write(bytes, 0, bytes.length);
            }
        }
    }

    private static void writeMarker(ByteArrayOutputStream out, int type, int length) {
        if (length < 15) {
            out.write((type << 4) | length);
        } else {
            out.write((type << 4) | 0xf);
            if (length < 0x100) { out.write(0x10); writeUInt(out, length, 1); }
            else if (length < 0x10000) { out.write(0x11); writeUInt(out, length, 2); }
            else { out.write(0x12); writeUInt(out, length, 4); }
        }
    }

    private static void writeUInt(ByteArrayOutputStream out, long value, int size) {
        for (int i = size - 1; i >= 0; i--) out.write((int) (value >>> (8 * i)) & 0xff);
    }

    // ---------------------------------------------------------------- helpers

    public static long getLong(Map<String, Object> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static boolean getBool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0;
        return fallback;
    }

    public static String getString(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : null;
    }

    public static byte[] getData(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof byte[] ? (byte[]) value : null;
    }
}
