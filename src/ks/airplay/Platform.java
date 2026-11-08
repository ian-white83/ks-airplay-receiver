// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

/**
 * What the receiver needs from the device. The plugin supplies Android implementations; tests supply fakes, so
 * the protocol code runs on a desktop JVM.
 */
public interface Platform {
    /** PCM output, 16-bit interleaved. */
    interface AudioSink {
        /** Blocking write of frames * channels samples. Returns false when the sink is closed. */
        boolean write(short[] samples, int offset, int length);
        /** Linear gain from 0 to 1, applied to the output. */
        void setGain(float gain);
        /** Drop anything queued and stop until the next write. */
        void flush();
        void close();
    }

    /** One compressed codec stream. */
    interface AudioDecoder {
        /** Decode one frame into interleaved samples. Returns samples per channel, 0 for no output yet. */
        int decode(byte[] data, int offset, int length, short[] out) throws Exception;
        void close();
    }

    AudioSink openSink(int sampleRate, int channels) throws Exception;

    /** A decoder for AirPlay compression type ct: 2 ALAC, 4 AAC-LC, 8 AAC-ELD. */
    AudioDecoder openDecoder(int ct, int samplesPerFrame) throws Exception;

    void log(String message);
}
