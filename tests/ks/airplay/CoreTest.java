// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyAgreement;

import ks.airplay.crypto.Ed25519;
import ks.airplay.crypto.X25519;
import ks.airplay.crypto.ChaCha20Poly1305;
import ks.airplay.crypto.Hkdf;
import ks.airplay.crypto.Srp;
import ks.airplay.fairplay.FairPlay;
import ks.airplay.hap.Tlv8;

/** Unit tests for the protocol building blocks. Run by tools/test.py on a desktop JVM. */
public final class CoreTest {
    public static void main(String[] args) throws Exception {
        ed25519();
        x25519();
        chacha();
        hkdfAndSrp();
        tlv();
        ptp();
        fairPlay();
        bplist();
        alac();
        dmapAndTxt();
        config();
        gain();
        System.out.println("CoreTest passed");
    }

    static void ed25519() throws Exception {
        byte[] seed = Util.unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        TestSupport.check(Util.hex(Ed25519.publicKey(seed)).equals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), "RFC 8032 public key");
        TestSupport.check(Util.hex(Ed25519.sign(seed, new byte[0])).equals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"), "RFC 8032 signature");
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 4; i++) {
            byte[] s = new byte[32];
            random.nextBytes(s);
            byte[] message = new byte[17 * i];
            random.nextBytes(message);
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, s));
            Signature jdk = Signature.getInstance("Ed25519");
            jdk.initSign(key);
            jdk.update(message);
            byte[] expected = jdk.sign();
            TestSupport.check(Arrays.equals(expected, Ed25519.sign(s, message)), "Ed25519 matches the JDK");
            TestSupport.check(Ed25519.verify(Ed25519.publicKey(s), message, expected), "Ed25519 verifies");
            expected[3] ^= 1;
            TestSupport.check(!Ed25519.verify(Ed25519.publicKey(s), message, expected), "Ed25519 rejects a bad signature");
        }
    }

    static void x25519() throws Exception {
        byte[] alice = Util.unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bob = Util.unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        TestSupport.check(Util.hex(X25519.publicKey(alice)).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), "RFC 7748 Alice");
        TestSupport.check(Util.hex(X25519.sharedSecret(alice, X25519.publicKey(bob))).equals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), "RFC 7748 shared secret");
        SecureRandom random = new SecureRandom();
        byte[] a = X25519.generatePrivateKey(random), b = X25519.generatePrivateKey(random);
        KeyFactory factory = KeyFactory.getInstance("X25519");
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, a)));
        byte[] u = X25519.publicKey(b), reversed = new byte[32];
        for (int i = 0; i < 32; i++) reversed[i] = u[31 - i];
        agreement.doPhase(factory.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, reversed))), true);
        TestSupport.check(Arrays.equals(agreement.generateSecret(), X25519.sharedSecret(a, u)), "X25519 matches the JDK");
    }

    static void fairPlay() {
        byte[] step1 = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x04, 0x02, 0x00, 0x02, (byte) 0xbb};
        byte[] reply = new FairPlay().setup(step1);
        TestSupport.check(reply != null && reply.length == 142 && reply[13] == 2, "fp-setup step 1 reply for mode 2");
        byte[] wrongVersion = step1.clone();
        wrongVersion[4] = 2;
        TestSupport.check(new FairPlay().setup(wrongVersion) == null, "fp-setup rejects other versions");
    }

    static void chacha() throws Exception {
        byte[] key = Util.unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = Util.unhex("070000004041424344454647");
        byte[] aad = Util.unhex("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".getBytes(Util.UTF8);
        byte[] sealed = ChaCha20Poly1305.seal(key, nonce, pt, 0, pt.length, aad);
        TestSupport.check(Util.hex(Arrays.copyOfRange(sealed, pt.length, sealed.length)).equals("1ae10b594f09e26a7e902ecbd0600691"), "RFC 8439 tag");
        TestSupport.check(Util.hex(Arrays.copyOf(sealed, 16)).equals("d31a8d34648e60db7b86afbc53ef7ec2"), "RFC 8439 ciphertext");
        SecureRandom random = new SecureRandom();
        for (int len : new int[] {0, 1, 63, 64, 65, 1024, 3000}) {
            byte[] k = new byte[32], n = new byte[12], p = new byte[len], a = new byte[len % 13];
            random.nextBytes(k); random.nextBytes(n); random.nextBytes(p); random.nextBytes(a);
            javax.crypto.Cipher jdk = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
            jdk.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(k, "ChaCha20"), new javax.crypto.spec.IvParameterSpec(n));
            if (a.length > 0) jdk.updateAAD(a);
            byte[] expected = jdk.doFinal(p);
            byte[] mine = ChaCha20Poly1305.seal(k, n, p, 0, len, a.length > 0 ? a : null);
            TestSupport.check(Arrays.equals(expected, mine), "ChaCha20-Poly1305 matches the JDK at " + len + " bytes");
            TestSupport.check(Arrays.equals(p, ChaCha20Poly1305.open(k, n, mine, 0, mine.length, a.length > 0 ? a : null)), "ChaCha20-Poly1305 opens");
            if (len > 0) {
                mine[0] ^= 1;
                boolean rejected = false;
                try {
                    ChaCha20Poly1305.open(k, n, mine, 0, mine.length, a.length > 0 ? a : null);
                } catch (java.security.GeneralSecurityException e) {
                    rejected = true;
                }
                TestSupport.check(rejected, "ChaCha20-Poly1305 rejects tampering");
            }
        }
        TestSupport.check(Util.hex(ChaCha20Poly1305.labelNonce("PS-Msg05")).equals("0000000050532d4d73673035"), "label nonce");
        TestSupport.check(Util.hex(ChaCha20Poly1305.counterNonce(1)).equals("000000000100000000000000"), "counter nonce");
    }

    static void hkdfAndSrp() {
        // From Python's cryptography HKDF(SHA512, 32, salt=b"Control-Salt", info=b"Control-Read-Encryption-Key").
        TestSupport.check(Util.hex(Hkdf.sha512(Util.unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), "Control-Salt", "Control-Read-Encryption-Key", 32))
                .equals("5d8f723f18a0a1179df2029373e42f2cc603a4390cab73a6a979e4a666327c15"), "HKDF-SHA512");
        SecureRandom random = new SecureRandom();
        Srp.Server server = new Srp.Server("3939", random);
        Srp.Client client = new Srp.Client(random);
        byte[] m1 = client.proof("3939", server.salt, server.publicKey());
        byte[] m2 = server.verify(client.publicKey(), m1);
        TestSupport.check(m2 != null && client.checkServer(m2) && Arrays.equals(client.sessionKey(), server.sessionKey()), "SRP agreement");
        Srp.Client wrong = new Srp.Client(random);
        Srp.Server other = new Srp.Server("3939", random);
        TestSupport.check(other.verify(wrong.publicKey(), wrong.proof("1234", other.salt, other.publicKey())) == null, "SRP rejects a wrong PIN");
    }

    static void tlv() {
        byte[] big = new byte[600];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        byte[] encoded = new Tlv8.Writer().add(Tlv8.STATE, 3).add(Tlv8.PUBLIC_KEY, big).add(Tlv8.PROOF, new byte[64]).bytes();
        java.util.Map<Integer, byte[]> back = Tlv8.parse(encoded);
        TestSupport.check(Tlv8.intValue(back, Tlv8.STATE, 0) == 3 && Arrays.equals(back.get(Tlv8.PUBLIC_KEY), big) && back.get(Tlv8.PROOF).length == 64, "TLV8 with fragments");
    }

    /** nqptp's filter: an offset estimate follows the smallest network delay and shrugs off slow packets. */
    static void ptp() throws Exception {
        PtpClockAccess.check();
        TestSupport.check(BufferedSession.seqDiff(5, 0x7ffffe) == 7 && BufferedSession.seqDiff(0x7ffffe, 5) == -7, "23-bit sequence wraparound");
    }

    @SuppressWarnings("unchecked")
    static void bplist() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ekey", new byte[72]);
        root.put("name", "Ian’s iPhone");
        root.put("timingPort", 54321L);
        root.put("big", 1L << 40);
        root.put("ratio", 0.25);
        root.put("on", Boolean.TRUE);
        List<Object> streams = new ArrayList<>();
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("type", 96L);
        stream.put("ct", 2L);
        streams.add(stream);
        root.put("streams", streams);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) longText.append('x');
        root.put("long", longText.toString());
        Map<String, Object> back = (Map<String, Object>) BPlist.parse(BPlist.write(root));
        TestSupport.check("Ian’s iPhone".equals(back.get("name")), "bplist UTF-16 string");
        TestSupport.check(Long.valueOf(54321).equals(back.get("timingPort")) && Long.valueOf(1L << 40).equals(back.get("big")), "bplist integers");
        TestSupport.check(Double.valueOf(0.25).equals(back.get("ratio")) && Boolean.TRUE.equals(back.get("on")), "bplist real and bool");
        TestSupport.check(((byte[]) back.get("ekey")).length == 72 && longText.toString().equals(back.get("long")), "bplist data and long string");
        TestSupport.check(BPlist.getLong((Map<String, Object>) ((List<Object>) back.get("streams")).get(0), "type", 0) == 96, "bplist nested");
        boolean rejected = false;
        try {
            BPlist.parse(Arrays.copyOf(BPlist.write(root), 30));
        } catch (java.io.IOException e) {
            rejected = true;
        }
        TestSupport.check(rejected, "bplist rejects truncated data");
    }

    static void alac() throws Exception {
        TestSupport.AlacFixture fixture = TestSupport.alacFixture();
        AlacDecoder decoder = new AlacDecoder(fixture.frameLength, 16, fixture.pb, fixture.mb, fixture.kb, 2, 255);
        short[] out = new short[fixture.frameLength * 2];
        short[] pcm = TestSupport.pcmFixture();
        int pos = 0;
        for (byte[] packet : fixture.packets) {
            int n = decoder.decode(packet, 0, packet.length, out);
            for (int i = 0; i < n * 2 && pos < pcm.length; i++) TestSupport.check(out[i] == pcm[pos++], "ALAC sample " + pos);
        }
        TestSupport.check(pos == pcm.length, "ALAC decoded every sample");
    }

    static void dmapAndTxt() {
        byte[] title = "Song".getBytes(Util.UTF8), artist = "Band".getBytes(Util.UTF8);
        byte[] dmap = new byte[8 + 8 + title.length + 8 + artist.length];
        int p = 0;
        p = tag(dmap, p, "mlit", dmap.length - 8);
        p = tag(dmap, p, "minm", title.length);
        System.arraycopy(title, 0, dmap, p, title.length);
        p += title.length;
        p = tag(dmap, p, "asar", artist.length);
        System.arraycopy(artist, 0, dmap, p, artist.length);
        Map<String, String> md = Util.parseDmap(dmap);
        TestSupport.check("Song".equals(md.get("title")) && "Band".equals(md.get("artist")), "DMAP metadata");
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("a", "1");
        txt.put("bb", "22");
        TestSupport.check(Arrays.equals(Util.txtRecord(txt), new byte[] {3, 'a', '=', '1', 5, 'b', 'b', '=', '2', '2'}), "TXT wire format");
        TestSupport.check(Arrays.equals(Util.base64("AQID"), new byte[] {1, 2, 3}) && Arrays.equals(Util.base64("AQI"), new byte[] {1, 2}), "base64");
    }

    private static int tag(byte[] b, int p, String tag, int len) {
        for (int i = 0; i < 4; i++) b[p + i] = (byte) tag.charAt(i);
        b[p + 4] = (byte) (len >>> 24);
        b[p + 5] = (byte) (len >>> 16);
        b[p + 6] = (byte) (len >>> 8);
        b[p + 7] = (byte) len;
        return p + 8;
    }

    static void config() {
        AirPlayConfig c = new AirPlayConfig("Kitchen", "02:11:22:33:44:55", 7100, AirPlayConfig.DEFAULT_FEATURES);
        TestSupport.check(c.raopServiceName().equals("021122334455@Kitchen"), "RAOP service name");
        TestSupport.check(c.featuresString().equals("0x401FCA00,0x1C340"), "features as shairport-sync advertises them");
        TestSupport.check(c.featuresEx().equals("AMofQEDDAQA"), "fex");
        TestSupport.check((c.features & (1L << 40)) != 0 && (c.features & (1L << 41)) != 0 && (c.features & (1L << 46)) != 0, "buffered audio, PTP and HomeKit pairing bits");
        TestSupport.check(Arrays.equals(c.publicKey, new AirPlayConfig("Other", "021122334455", 7001, 0).publicKey), "identity follows the device ID");
        TestSupport.check(AirPlayConfig.parseFeatures("0x5A7FFEE6,0x1", 0) == 0x15A7FFEE6L, "features with high word");
        TestSupport.check(AirPlayConfig.parseFeatures("junk", 7) == 7, "bad features fall back");
        TestSupport.check(!AirPlayConfig.validDeviceId("") && AirPlayConfig.validDeviceId(AirPlayConfig.randomDeviceId(new SecureRandom())), "device ID validation");
        java.util.Map<String, Object> info = c.info(null, "1.2.3.4:5", -12, null, false);
        TestSupport.check("Kitchen".equals(info.get("name")) && info.get("supportedFormats") instanceof java.util.Map, "/info contents");
        TestSupport.check(c.info("txtAirPlay", null, 0, null, false).get("txtAirPlay") instanceof byte[], "/info qualifier");
        TestSupport.check(c.airplayTxt("G1", true).get("gid").equals("G1") && c.airplayTxt(null, false).get("gid").equals(c.pi), "group ID in TXT");
    }

    static void gain() {
        TestSupport.check(AudioCurve.gain(-144) == 0f && AudioCurve.gain(0) == 1f, "volume ends");
        TestSupport.check(Math.abs(AudioCurve.gain(-15) - 0.25f) < 1e-6 && AudioCurve.percent(-15) == 50, "volume curve");
    }
}
