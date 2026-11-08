// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ks.airplay.crypto.Ed25519;

/** Everything the receiver advertises: name, identity, feature bits and the TXT records built from them. */
public final class AirPlayConfig {
    public static final String SOURCE_VERSION = "366.0";
    public static final String FIRMWARE = "ks-airplay-0.2";
    public static final String MODEL = "KSAirPlay1,1";

    /**
     * shairport-sync's AirPlay 2 feature set: audio, buffered audio (bit 40), PTP (41), HomeKit pairing (46, 47,
     * 48), unified media control (38) and classic metadata (15-17: artwork, progress, text).
     */
    public static final long DEFAULT_FEATURES = 0x0001C340401FCA00L;
    /** bufferStream formats: AAC-LC 44.1 kHz stereo and ALAC 44.1 kHz 16-bit stereo. */
    public static final long BUFFER_FORMATS = 0x400000L | 0x40000L;

    public final String name;
    public final byte[] deviceId; // 6 bytes, shown like a MAC address
    public final int port;
    public final long features;
    final byte[] seed;
    public final byte[] publicKey;
    public final String pi, psi;

    public AirPlayConfig(String name, String deviceId, int port, long features) {
        this.name = name == null || name.trim().isEmpty() ? "Kiosk Satellite" : name.trim();
        this.deviceId = parseDeviceId(deviceId);
        this.port = port;
        this.features = features;
        byte[] digest = sha512(("ks-airplay identity " + deviceIdString()).getBytes(Util.UTF8));
        this.seed = Arrays.copyOf(digest, 32);
        this.publicKey = Ed25519.publicKey(seed);
        this.pi = UUID.nameUUIDFromBytes(("ks-airplay pi " + deviceIdString()).getBytes(Util.UTF8)).toString();
        this.psi = UUID.nameUUIDFromBytes(("ks-airplay psi " + deviceIdString()).getBytes(Util.UTF8)).toString().toUpperCase(java.util.Locale.ROOT);
    }

    public byte[] seed() { return seed.clone(); }

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

    /** Parse "0x401FCA00,0x1C340" (low word, high word) as AirPlay writes features. */
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
        return String.format("0x%X,0x%X", features & 0xffffffffL, features >>> 32);
    }

    /** Features as eight little-endian bytes in unpadded base64, the "fex" field. */
    public String featuresEx() {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (features >>> (8 * i));
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < 8 ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < 8 ? b[i + 2] & 0xff : 0);
            int chars = i + 2 < 8 ? 4 : i + 1 < 8 ? 3 : 2;
            for (int k = 0; k < chars; k++) sb.append(alphabet.charAt((n >> (18 - 6 * k)) & 63));
        }
        return sb.toString();
    }

    public Map<String, String> raopTxt() {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("cn", "0,1");
        txt.put("da", "true");
        txt.put("et", "0,1");
        txt.put("ft", featuresString());
        txt.put("fv", FIRMWARE);
        txt.put("sf", "0x4");
        txt.put("md", "0,1,2");
        txt.put("am", MODEL);
        txt.put("pk", Util.hex(publicKey));
        txt.put("tp", "UDP");
        txt.put("vn", "65537");
        txt.put("vs", SOURCE_VERSION);
        return txt;
    }

    /** gid and gcgl follow the group the current sender put this receiver in. */
    public Map<String, String> airplayTxt(String groupId, boolean groupContainsLeader) {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("acl", "0");
        txt.put("deviceid", deviceIdString());
        txt.put("fex", featuresEx());
        txt.put("features", featuresString());
        txt.put("flags", "0x4");
        txt.put("gid", groupId == null ? pi : groupId);
        txt.put("igl", "0");
        txt.put("gcgl", groupContainsLeader ? "1" : "0");
        txt.put("model", MODEL);
        txt.put("protovers", "1.1");
        txt.put("pi", pi);
        txt.put("psi", psi);
        txt.put("pk", Util.hex(publicKey));
        txt.put("srcvers", SOURCE_VERSION);
        txt.put("fv", FIRMWARE);
        return txt;
    }

    /** The GET /info plist. A request that names the txtAirPlay qualifier gets that TXT record back. */
    public Map<String, Object> info(String qualifier, String senderAddress, double initialVolume, String groupId, boolean gcgl) {
        Map<String, Object> info = new LinkedHashMap<>();
        if ("txtAirPlay".equals(qualifier)) {
            info.put("txtAirPlay", Util.txtRecord(airplayTxt(groupId, gcgl)));
            return info;
        }
        Map<String, Object> playback = new LinkedHashMap<>();
        playback.put("supportsInterstitials", Boolean.FALSE);
        playback.put("supportsFPSSecureStop", Boolean.FALSE);
        playback.put("supportsUIForAudioOnlyContent", Boolean.FALSE);
        info.put("playbackCapabilities", playback);
        info.put("canRecordScreenStream", Boolean.FALSE);
        info.put("keepAliveSendStatsAsBody", Boolean.FALSE);
        info.put("protocolVersion", "1.1");
        info.put("screenDemoMode", Boolean.FALSE);
        info.put("receiverHDRCapability", "4k60");
        info.put("psi", psi);
        info.put("featuresEx", featuresEx());
        info.put("features", features);
        info.put("statusFlags", 4L);
        info.put("deviceID", deviceIdString());
        info.put("pi", pi);
        info.put("name", name);
        info.put("model", MODEL);
        info.put("pk", publicKey.clone());
        if (senderAddress != null) info.put("senderAddress", senderAddress);
        info.put("initialVolume", initialVolume);
        info.put("vv", 0L);
        info.put("volumeControlType", 3L);
        info.put("sourceVersion", SOURCE_VERSION);
        Map<String, Object> formats = new LinkedHashMap<>();
        formats.put("audioStream", 262144L);
        formats.put("bufferStream", BUFFER_FORMATS);
        info.put("supportedFormats", formats);
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
                && features == other.features;
    }
}
