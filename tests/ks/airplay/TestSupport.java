// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


final class TestSupport {
    private TestSupport() {}

    static Path root() {
        return Paths.get(System.getProperty("ks.root", "."));
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("Failed: " + what);
    }

    static final class AlacFixture {
        int frameLength, pb, mb, kb;
        List<byte[]> packets = new ArrayList<>();
    }

    /** ffmpeg-encoded ALAC: the 24-byte ALACSpecificConfig, a packet count, then length-prefixed packets. */
    static AlacFixture alacFixture() throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(root().resolve("tests/fixtures/alac-stereo-4096.bin")));
        AlacFixture f = new AlacFixture();
        f.frameLength = b.getInt();
        b.get();
        b.get();
        f.pb = b.get() & 0xff;
        f.mb = b.get() & 0xff;
        f.kb = b.get() & 0xff;
        b.position(24);
        int n = b.getInt();
        for (int i = 0; i < n; i++) {
            byte[] p = new byte[b.getInt()];
            b.get(p);
            f.packets.add(p);
        }
        return f;
    }

    /** The source audio of the ALAC fixture, interleaved 16-bit little endian. */
    static short[] pcmFixture() throws Exception {
        byte[] raw = Files.readAllBytes(root().resolve("tests/fixtures/pcm-stereo.raw"));
        short[] out = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }
}
