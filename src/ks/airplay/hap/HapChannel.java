// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.hap;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import ks.airplay.crypto.ChaCha20Poly1305;

/**
 * The HAP encrypted transport: frames of a 2-byte little-endian length, up to 1024 bytes of ChaCha20-Poly1305
 * ciphertext with the length as associated data, and a 16-byte tag. Each direction counts its own nonces.
 */
public final class HapChannel {
    static final int FRAME = 1024;

    private HapChannel() {}

    /** Decrypts frames from the raw stream. */
    public static final class Reader extends InputStream {
        private final InputStream raw;
        private final byte[] key;
        private long counter;
        private byte[] plain = new byte[0];
        private int pos;

        public Reader(InputStream raw, byte[] key) {
            this.raw = raw;
            this.key = key;
        }

        private boolean fill() throws IOException {
            byte[] header = new byte[2];
            if (!readFully(header, true)) return false;
            int len = (header[0] & 0xff) | ((header[1] & 0xff) << 8);
            byte[] sealed = new byte[len + ChaCha20Poly1305.TAG_LENGTH];
            readFully(sealed, false);
            try {
                plain = ChaCha20Poly1305.open(key, ChaCha20Poly1305.counterNonce(counter++), sealed, 0, sealed.length, header);
            } catch (Exception e) {
                throw new IOException("Encrypted frame failed authentication");
            }
            pos = 0;
            return true;
        }

        private boolean readFully(byte[] b, boolean eofOk) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = raw.read(b, off, b.length - off);
                if (n < 0) {
                    if (eofOk && off == 0) return false;
                    throw new EOFException("Connection closed inside an encrypted frame");
                }
                off += n;
            }
            return true;
        }

        @Override public int read() throws IOException {
            while (pos >= plain.length) if (!fill()) return -1;
            return plain[pos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= plain.length) if (!fill()) return -1;
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** Buffers a whole message, then writes it as encrypted frames on flush. */
    public static final class Writer extends OutputStream {
        private final OutputStream raw;
        private final byte[] key;
        private long counter;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        public Writer(OutputStream raw, byte[] key) {
            this.raw = raw;
            this.key = key;
        }

        @Override public void write(int b) { pending.write(b); }
        @Override public void write(byte[] b, int off, int len) { pending.write(b, off, len); }

        @Override public void flush() throws IOException {
            byte[] data = pending.toByteArray();
            pending.reset();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 64);
            for (int pos = 0; pos < data.length; pos += FRAME) {
                int n = Math.min(FRAME, data.length - pos);
                byte[] header = {(byte) n, (byte) (n >> 8)};
                out.write(header);
                out.write(ChaCha20Poly1305.seal(key, ChaCha20Poly1305.counterNonce(counter++), data, pos, n, header));
            }
            raw.write(out.toByteArray());
            raw.flush();
        }
    }
}
