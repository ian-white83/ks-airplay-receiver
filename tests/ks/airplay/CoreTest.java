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
import ks.airplay.fairplay.FairPlay;

/** Unit tests for the protocol building blocks. Run by tools/test.py on a desktop JVM. */
public final class CoreTest {
    /** keyMessage (164 bytes), ekey (72 bytes), AES key from UxPlay's C playfair. */
    static final String[][] FAIRPLAY_VECTORS = {
        {"a610b14203ee22ce44337efa00af12fa7fa2debb61c6aff52cac8bac26340fd45fe3e10e55038b7c7d3b37734e54fa55f32ee02ecf7c8df804514b6bfae4a96fa0e7ff13f37217c79445cb1a1c1bf9d39ecedacf77d992bbda972d3a3d291403ecc45f743403588a6eb8455cd9ed69e7579cb8a781d677af812afe61af171a1426075d320dac7d8229227d5b07edf3818df9aa17ae55e5d20e68e2f066a549aa42a7b62b", "620d8c599a794f6645a013996d2e214275e7d6b1852cc97a823c47bc4064121c685268a5534a3d262de629de5e6af8d42336587393d5fdb5953cb33b483668d49fe5173421734ab7", "c5649f29006afee4d8be7ba0fbc16c85"},
        {"7b9503d4036aa2916970cfb201a0838ae5c49b595cea4abfdd87ce27b0d69eed191ad2b689b273d9889ffe35a121898398737a0080ba473f29887995fe7adc3591af8dca5542c26de54280ba2589a9397f368ac23cfb2749869554f5050f8f0efb2a82d608bea0616ad7bd70155077c8fc14d992a86ba37f426b485172b0464f58a746572ff77c54b4b056d75b5a6eabbed18b414e7333e2e0f2a8314d9a1594214dbbe8", "87dfe1865eabd3fdae3b76e0c51c2dfdf0e7dd0c76d58b64e6cf93c6fe456e92ade0f0793b626007f389496ae88c53553aa9e152f83117a68818a5b40b6018682d4902abd324cdd3", "72619b70c278d14a45a43dcd733a4506"},
        {"095266e703a6a36807d489f40227eb86720158b1fc3d05cd67e17943db476f5a9c2095316a5ef7df7401a629b7d6e4a016be23aeb2888b922d68b7bdc0b979dc97fe8404ba13d2dae699145ae4c8154298706e8928e1501d3902f1ee4420f2bbe3f418e423f10b6beaacd363b4bec10a69c2b85648f9304184187c6de35398c90a2424a3cbaf8a2c9a770f874e8e8325a3468b6d82ee705c646190a1c7b211389a9f6bde", "8dbf9a614b3441f2f2927a4ebe218afebda0fb0189b964e0c45438abbf9fd40c5c145a4a35311e8bf75613e2da7a9f6518b77235903289f4a4bf953266dbeafca973dc334b64bae1", "5b7b289f222d443f0661b2a2882b367e"},
    };

    public static void main(String[] args) throws Exception {
        TestSupport.loadTables();
        ed25519();
        x25519();
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
        for (String[] v : FAIRPLAY_VECTORS) {
            FairPlay fp = new FairPlay();
            byte[] reply = fp.setup(Util.unhex(v[0]));
            TestSupport.check(reply != null && reply.length == 32, "fp-setup step 2 reply");
            TestSupport.check(Util.hex(fp.decryptKey(Util.unhex(v[1]))).equals(v[2]), "FairPlay key matches playfair");
        }
        byte[] step1 = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x04, 0x02, 0x00, 0x02, (byte) 0xbb};
        byte[] reply = new FairPlay().setup(step1);
        TestSupport.check(reply != null && reply.length == 142 && reply[13] == 2, "fp-setup step 1 reply for mode 2");
        byte[] wrongVersion = step1.clone();
        wrongVersion[4] = 2;
        TestSupport.check(new FairPlay().setup(wrongVersion) == null, "fp-setup rejects other versions");
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

    @SuppressWarnings("unchecked")
    static void config() {
        AirPlayConfig c = new AirPlayConfig("Kitchen", "02:11:22:33:44:55", 7000, AirPlayConfig.DEFAULT_FEATURES, false, 500);
        TestSupport.check(c.raopServiceName().equals("021122334455@Kitchen"), "RAOP service name");
        TestSupport.check(c.featuresString().equals("0x527FDE00"), "default features");
        TestSupport.check((c.features & (1 << 7)) == 0 && (c.features & (1 << 9)) != 0, "audio without mirroring");
        TestSupport.check(new AirPlayConfig("K", "02:11:22:33:44:55", 7000, AirPlayConfig.DEFAULT_FEATURES, true, 500).featuresString().equals("0x5A7FDE00"), "legacy pairing bit");
        TestSupport.check(Arrays.equals(c.publicKey, new AirPlayConfig("Other", "021122334455", 7001, 0, false, 100).publicKey), "identity follows the device ID");
        TestSupport.check(AirPlayConfig.parseFeatures("0x5A7FFEE6,0x1", 0) == 0x15A7FFEE6L, "features with high word");
        TestSupport.check(AirPlayConfig.parseFeatures("junk", 7) == 7, "bad features fall back");
        TestSupport.check(!AirPlayConfig.validDeviceId("") && !AirPlayConfig.validDeviceId("hello") && AirPlayConfig.validDeviceId(AirPlayConfig.randomDeviceId(new SecureRandom())), "device ID validation");
        Map<String, Object> info = c.info(null, true, -12);
        TestSupport.check("Kitchen".equals(info.get("name")) && info.containsKey("audioFormats") && !info.containsKey("displays"), "/info contents");
        TestSupport.check(c.info("txtAirPlay", false, 0).get("txtAirPlay") instanceof byte[], "/info qualifier");
        TestSupport.check("Kiosk Satellite".equals(new AirPlayConfig(" ", "02:11:22:33:44:55", 7000, 0, false, 500).name), "default name");
    }

    static void gain() {
        TestSupport.check(AudioSession.gainForVolume(-144) == 0f && AudioSession.gainForVolume(0) == 1f, "volume ends");
        TestSupport.check(Math.abs(AudioSession.gainForVolume(-15) - 0.25f) < 1e-6, "volume curve");
        TestSupport.check(AudioSession.seqDiff(2, 65534) == 4 && AudioSession.seqDiff(65534, 2) == -4, "sequence wraparound");
    }
}
