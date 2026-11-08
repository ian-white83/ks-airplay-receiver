// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import ks.airplay.AlacDecoder;
import ks.airplay.Platform;

/** Audio output through AudioTrack and AAC decoding through MediaCodec. ALAC is decoded in Java. */
public final class AndroidPlatform implements Platform {
    public interface Logger { void log(String message); }

    private final Logger logger;

    public AndroidPlatform(Logger logger) {
        this.logger = logger;
    }

    @Override public void log(String message) { logger.log(message); }

    /** Kiosk Satellite's no-backup files folder, so the ADB key never leaves the device in a backup. */
    @Override public java.io.File dataDir() {
        try {
            return new java.io.File(Bonjour.applicationContext().getNoBackupFilesDir(), "plugin-airplay2-multiroom");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AudioSink openSink(int sampleRate, int channels) {
        return new Sink(sampleRate, channels, logger);
    }

    /**
     * AudioTrack output that knows when written audio will play: AudioTrack.getTimestamp pairs a frame position
     * with the CLOCK_MONOTONIC time it reached the speaker, which is the clock System.nanoTime() reads.
     */
    static final class Sink implements AudioSink {
        private final int rate, channels;
        private final Logger logger;
        private final AudioTimestamp stamp = new AudioTimestamp();
        private AudioTrack track;
        private long written; // frames since the track was created
        private float gain = 1f;
        private volatile boolean closed;

        Sink(int rate, int channels, Logger logger) {
            this.rate = rate;
            this.channels = channels;
            this.logger = logger;
            track = create();
        }

        private AudioTrack create() {
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            // About 100 ms: small enough that timing corrections take effect quickly.
            int size = Math.max(min * 2, rate * channels * 2 / 10);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build();
            AudioTrack t = new AudioTrack(attributes, format, size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                throw new IllegalStateException("AudioTrack failed to initialize");
            }
            t.setVolume(gain);
            t.play();
            written = 0;
            return t;
        }

        @Override public synchronized boolean write(short[] samples, int offset, int length) {
            int done = 0;
            while (done < length) {
                if (closed) return false;
                int n = track.write(samples, offset + done, length - done);
                if (n < 0) {
                    logger.log("AudioTrack write failed: " + n);
                    return false;
                }
                if (n == 0) return true;
                done += n;
            }
            written += length / channels;
            return true;
        }

        @Override public synchronized long nextPlayTime() {
            long now = System.nanoTime();
            if (track.getTimestamp(stamp) && stamp.nanoTime > 0 && now - stamp.nanoTime < 1_000_000_000L) {
                return stamp.nanoTime + (written - stamp.framePosition) * 1_000_000_000L / rate;
            }
            // Before the first timestamp: count what is still queued, plus a typical output latency.
            long head = track.getPlaybackHeadPosition() & 0xffffffffL;
            return now + Math.max(0, written - head) * 1_000_000_000L / rate + 40_000_000L;
        }

        @Override public synchronized void setGain(float g) {
            gain = g;
            if (!closed) track.setVolume(g);
        }

        /** Start over with an empty track, so frame positions and timestamps begin again. */
        @Override public synchronized void flush() {
            if (closed) return;
            release(track);
            track = create();
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            release(track);
        }

        private static void release(AudioTrack t) {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
        }
    }

    @Override
    public AudioDecoder openDecoder(int ct, int samplesPerFrame) throws Exception {
        if (ct == 2) {
            final AlacDecoder alac = AlacDecoder.airPlay(samplesPerFrame);
            return new AudioDecoder() {
                @Override public int decode(byte[] data, int offset, int length, short[] out) {
                    return alac.decode(data, offset, length, out);
                }
                @Override public void close() {}
            };
        }
        if (ct == 4) return new AacDecoder(false);
        throw new IllegalArgumentException("Unsupported AirPlay audio compression type " + ct);
    }

    /** AAC-LC (AirPlay 1, some AirPlay 2 senders) and AAC-ELD (AirPlay 2 low latency) through MediaCodec. */
    static final class AacDecoder implements AudioDecoder {
        private static final byte[] ELD_CONFIG = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
        private static final byte[] LC_CONFIG = {0x12, 0x10};
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int outputChannels = 2;
        private long pts;

        AacDecoder(boolean eld) throws Exception {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(eld ? ELD_CONFIG : LC_CONFIG));
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, eld ? MediaCodecInfo.CodecProfileLevel.AACObjectELD : MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, 0);
            codec.start();
        }

        @Override
        public int decode(byte[] data, int offset, int length, short[] out) {
            int in = codec.dequeueInputBuffer(20_000);
            if (in >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(in);
                buffer.clear();
                int n = Math.min(length, buffer.remaining());
                buffer.put(data, offset, n);
                codec.queueInputBuffer(in, 0, n, pts, 0);
                pts += 10_000;
            }
            int written = 0; // samples (not frames)
            while (true) {
                int index = codec.dequeueOutputBuffer(info, written == 0 ? 5_000 : 0);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    continue;
                }
                if (index < 0) break;
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    ShortBuffer shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int n = shorts.remaining();
                    if (outputChannels == 1) {
                        int room = (out.length - written) / 2;
                        for (int i = 0; i < Math.min(n, room); i++) {
                            short s = shorts.get();
                            out[written++] = s;
                            out[written++] = s;
                        }
                    } else {
                        int take = Math.min(n, out.length - written);
                        shorts.get(out, written, take);
                        written += take;
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }
            return written / 2;
        }

        @Override
        public void close() {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
        }
    }
}
