// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.jxl.kiosk.plugins.AudioOutput;

/**
 * Plays through Kiosk Satellite's own audio output (the player capability), the path its Sendspin player uses: the
 * echo canceller hears it, and KS applies the media volume and the voice assistant's duck. The sender's AirPlay
 * volume is applied here, to the samples, before they are written. AudioOutput.getTimestamp says when a written
 * frame is heard, which is what multi-room playout needs.
 */
public final class OutputSink implements Platform.AudioSink {
    /** Before the first timestamp: a typical output delay on top of what is queued. */
    static final long FIRST_LATENCY_NANOS = 40_000_000L;

    private final AudioOutput out;
    private final int rate, channels;
    private final long[] stamp = new long[2];
    private ByteBuffer buffer = ByteBuffer.allocate(0);
    private volatile float gain = 1f;
    private volatile boolean closed;

    public OutputSink(AudioOutput out, int rate, int channels) {
        this.out = out;
        this.rate = rate;
        this.channels = channels;
    }

    @Override public synchronized boolean write(short[] samples, int offset, int length) {
        if (closed) return false;
        int frames = length / channels;
        if (frames == 0) return true;
        if (buffer.capacity() < frames * channels * 2) buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.clear();
        float g = gain;
        if (g >= 0.9999f) {
            for (int i = 0; i < frames * channels; i++) buffer.putShort(samples[offset + i]);
        } else {
            for (int i = 0; i < frames * channels; i++) buffer.putShort((short) Math.round(samples[offset + i] * g));
        }
        buffer.flip();
        int left = frames;
        while (left > 0) {
            int n = out.write(buffer, left, 500);
            if (n < 0) {
                closed = true;
                return false; // KS closed it: the player is no longer the pick
            }
            left -= n;
        }
        return true;
    }

    /** When the next frame written will be heard, on the System.nanoTime() clock. */
    @Override public synchronized long nextPlayTime() {
        long written = out.framesWritten();
        long now = System.nanoTime();
        if (out.getTimestamp(stamp) && now - stamp[1] < 1_000_000_000L) {
            return stamp[1] + (written - stamp[0]) * 1_000_000_000L / rate;
        }
        return now + written * 1_000_000_000L / rate + FIRST_LATENCY_NANOS;
    }

    /** The sender's volume, 0 to 1. */
    @Override public void setGain(float g) {
        gain = Math.max(0f, Math.min(1f, g));
    }

    @Override public synchronized void flush() {
        if (!closed) out.flush();
    }

    /**
     * Ends this session's use of the output. The output itself stays open for the next session: KS asks a player
     * to keep one output while the format is the same, and closes it itself when the pick moves on.
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        out.flush();
    }
}
