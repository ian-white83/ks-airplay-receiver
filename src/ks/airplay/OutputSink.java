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
    /** A timestamp that says more than this is queued is stale or wrong. */
    static final long MAX_QUEUED_NANOS = 2_000_000_000L;

    private final AudioOutput out;
    private final int rate, channels;
    private final long[] stamp = new long[2];
    private ByteBuffer buffer = ByteBuffer.allocate(0);
    private volatile float gain = 1f;
    private volatile boolean closed;
    /** When the output's count last started from zero. Timestamps from before it belong to the old count. */
    private volatile long flushedAt;
    private long rejected;
    /** The last timestamp that passed the checks since the last flush, {frame, nanos}, or frame -1 for none. */
    private final long[] good = {-1, 0};

    public OutputSink(AudioOutput out, int rate, int channels) {
        this.out = out;
        this.rate = rate;
        this.channels = channels;
        flushedAt = System.nanoTime();
    }

    @Override public boolean write(short[] samples, int offset, int length) {
        if (closed) return false;
        int frames = length / channels;
        if (frames == 0) return true;
        synchronized (this) {
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
                // A newer session took the output over: stop at once rather than mix into its audio.
                if (closed) return false;
                int n = out.write(buffer, left, 100);
                if (n < 0) {
                    closed = true;
                    return false; // KS closed it: the player is no longer the pick
                }
                left -= n;
            }
            return true;
        }
    }

    /** When the next frame written will be heard, on the System.nanoTime() clock. */
    @Override public synchronized long nextPlayTime() {
        long written = out.framesWritten();
        long now = System.nanoTime();
        if (out.getTimestamp(stamp)) {
            long queued = (written - stamp[0]) * 1_000_000_000L / rate;
            // After a flush the output can still report the old count for a moment: a position beyond what has been
            // written since, or a time from before the flush, would put the next frame seconds off.
            if (stamp[1] >= flushedAt && stamp[0] >= 0 && queued >= 0 && queued <= MAX_QUEUED_NANOS && now - stamp[1] < 1_000_000_000L) {
                good[0] = stamp[0];
                good[1] = stamp[1];
                return stamp[1] + queued;
            }
            rejected++;
        }
        if (good[0] >= 0) {
            // Playing, but no fresh timestamp this time: carry on from the last good one rather than guess.
            long fromGood = good[1] + (written - good[0]) * 1_000_000_000L / rate;
            if (fromGood >= now) return fromGood;
        }
        // Nothing heard since the flush: everything written is still queued.
        return now + written * 1_000_000_000L / rate + FIRST_LATENCY_NANOS;
    }

    /** Timestamps ignored as stale since this sink opened. For the log. */
    public synchronized long rejectedTimestamps() { return rejected; }

    /** The sender's volume, 0 to 1. */
    @Override public void setGain(float g) {
        gain = Math.max(0f, Math.min(1f, g));
    }

    @Override public void flush() {
        if (closed) return;
        synchronized (this) {
            out.flush();
            flushedAt = System.nanoTime();
            good[0] = -1;
        }
    }

    /**
     * Ends this session's use of the output. The output itself stays open for the next session: KS asks a player
     * to keep one output while the format is the same, and closes it itself when the pick moves on. Marking it
     * closed first stops a write in progress within 100 ms.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        synchronized (this) {
            out.flush();
        }
    }
}
