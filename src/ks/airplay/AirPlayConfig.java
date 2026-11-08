// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ks.airplay.crypto.Ed25519;

/** Everything the receiver advertises: name, identity, feature bits and the TXT records built from them. */
public final class AirPlayConfig {
    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";

    /**
     * Features advertised by default: UxPlay's set (0x5A7FFEE6) without screen mirroring, video, photos or
     * slideshows (bits 1, 2, 5, 6, 7 and 13), and with bit 27 "supports legacy pairing" off, so senders skip
     * pair-setup and start faster. Bit 9 (audio), 11 (audio redundancy), 14 and 22 (FairPlay), 15-17 (artwork,
     * progress and text metadata), 18-21 (audio formats) and 30 (RAOP) stay on.
     */
    public static final long DEFAULT_FEATURES = 0x527FDE00L;
    public static final long LEGACY_PAIRING_BIT = 1L << 27;

    public final String name;
    public final byte[] deviceId; // 6 bytes, shown like a MAC address
    public final int port;
    public final long features;
    public final int bufferMs;
    final byte[] seed;
    public final byte[] publicKey;
    public final String pi;

    public AirPlayConfig(String name, String deviceId, int port, long features, boolean legacyPairing, int bufferMs) {
        this.name = name == null || name.trim().isEmpty() ? "Kiosk Satellite" : name.trim();
        this.deviceId = parseDeviceId(deviceId);
        this.port = port;
        long f = features;
        f = legacyPairing ? f | LEGACY_PAIRING_BIT : f & ~LEGACY_PAIRING_BIT;
        this.features = f;
        this.bufferMs = bufferMs;
        byte[] digest = sha512(("ks-airplay identity " + deviceIdString()).getBytes(Util.UTF8));
        this.seed = Arrays.copyOf(digest, 32);
        this.publicKey = Ed25519.publicKey(seed);
        this.pi = UUID.nameUUIDFromBytes(("ks-airplay pi " + deviceIdString()).getBytes(Util.UTF8)).toString();
    }

    /** A random locally administered unicast address, such as 02:1A:2B:3C:4D:5E. */
    public static String randomDeviceId(java.util.Random random) {
        byte[] b = new byte[6];
        random.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xfc) | 0x02);
        return format(b, ":");
    }

    public static boolean validDeviceId(String id) {
        return id != null && id.trim().toUpperCase(java.util.Locale.ROOT).matches("([0-9A-F]{2}[:-]?){5}[0-9A-F]{2}");
    }

    static byte[] parseDeviceId(String id) {
        if (!validDeviceId(id)) throw new IllegalArgumentException("Device ID must look like 02:1A:2B:3C:4D:5E");
        return Util.unhex(id.trim().replace(":", "").replace("-", ""));
    }

    private static String format(byte[] b, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(String.format("%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    public String deviceIdString() { return format(deviceId, ":"); }

    /** The _raop._tcp instance name: the device ID without separators, then @ and the speaker name. */
    public String raopServiceName() { return format(deviceId, "") + "@" + name; }

    /** Parse "0x527FDE00" or "0x5A7FFEE6,0x0" (low word, high word) as AirPlay writes features. */
    public static long parseFeatures(String text, long fallback) {
        if (text == null || text.trim().isEmpty()) return fallback;
        try {
            String[] parts = text.trim().split(",");
            long low = Long.decode(parts[0].trim()) & 0xffffffffL;
            long high = parts.length > 1 ? Long.decode(parts[1].trim()) & 0xffffffffL : 0;
            return (high << 32) | low;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String featuresString() {
        long high = features >>> 32, low = features & 0xffffffffL;
        return high == 0 ? String.format("0x%X", low) : String.format("0x%X,0x%X", low, high);
    }

    public Map<String, String> raopTxt() {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("txtvers", "1");
        txt.put("ch", "2");
        txt.put("cn", "0,1,2,3");
        txt.put("da", "true");
        txt.put("et", "0,3,5");
        txt.put("vv", "2");
        txt.put("ft", featuresString());
        txt.put("am", MODEL);
        txt.put("md", "0,1,2");
        txt.put("rhd", "5.6.0.0");
        txt.put("pw", "false");
        txt.put("sr", "44100");
        txt.put("ss", "16");
        txt.put("sv", "false");
        txt.put("tp", "UDP");
        txt.put("sf", "0x4");
        txt.put("vs", SOURCE_VERSION);
        txt.put("vn", "65537");
        txt.put("pk", Util.hex(publicKey));
        return txt;
    }

    public Map<String, String> airplayTxt() {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("deviceid", deviceIdString());
        txt.put("features", featuresString());
        txt.put("flags", "0x4");
        txt.put("model", MODEL);
        txt.put("pk", Util.hex(publicKey));
        txt.put("pi", pi);
        txt.put("srcvers", SOURCE_VERSION);
        txt.put("vv", "2");
        txt.put("pw", "false");
        return txt;
    }

    /** The GET /info plist. A request that names a qualifier only gets that TXT record back. */
    public Map<String, Object> info(String qualifier, boolean full, double initialVolume) {
        Map<String, Object> info = new LinkedHashMap<>();
        if ("txtAirPlay".equals(qualifier)) {
            info.put("txtAirPlay", Util.txtRecord(airplayTxt()));
            return info;
        }
        if ("txtRAOP".equals(qualifier)) {
            info.put("txtRAOP", Util.txtRecord(raopTxt()));
            return info;
        }
        info.put("deviceID", deviceIdString());
        info.put("macAddress", deviceIdString());
        info.put("pk", publicKey.clone());
        info.put("features", features);
        info.put("name", name);
        info.put("pi", pi);
        info.put("vv", 2L);
        info.put("statusFlags", 68L);
        info.put("keepAliveLowPower", Boolean.TRUE);
        info.put("sourceVersion", SOURCE_VERSION);
        info.put("keepAliveSendStatsAsBody", Boolean.TRUE);
        info.put("model", MODEL);
        if (!full) return info;
        info.put("initialVolume", initialVolume);
        List<Object> latencies = new ArrayList<>();
        List<Object> formats = new ArrayList<>();
        for (long type : new long[] {100, 101}) {
            Map<String, Object> latency = new LinkedHashMap<>();
            latency.put("type", type);
            latency.put("audioType", "default");
            latency.put("inputLatencyMicros", 0L);
            latency.put("outputLatencyMicros", Boolean.FALSE);
            latencies.add(latency);
            Map<String, Object> format = new LinkedHashMap<>();
            format.put("type", type);
            format.put("audioInputFormats", 0x3fffffcL);
            format.put("audioOutputFormats", 0x3fffffcL);
            formats.add(format);
        }
        info.put("audioLatencies", latencies);
        info.put("audioFormats", formats);
        return info;
    }

    static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] part : parts) digest.update(part);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean sameAs(AirPlayConfig other) {
        return other != null && name.equals(other.name) && Arrays.equals(deviceId, other.deviceId) && port == other.port
                && features == other.features && bufferMs == other.bufferMs;
    }
}
