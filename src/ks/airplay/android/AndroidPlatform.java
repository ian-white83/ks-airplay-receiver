// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
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

    @Override
    public AudioSink openSink(int sampleRate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        // About 200 ms of device buffer: the jitter buffer upstream absorbs network delay.
        int size = Math.max(min * 2, sampleRate * channels * 2 / 5);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(mask)
                .build();
        final AudioTrack track = new AudioTrack(attributes, format, size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack failed to initialize");
        }
        track.play();
        return new AudioSink() {
            private volatile boolean closed;

            @Override public boolean write(short[] samples, int offset, int length) {
                int done = 0;
                while (done < length) {
                    if (closed) return false;
                    int n = track.write(samples, offset + done, length - done);
                    if (n < 0) {
                        logger.log("AudioTrack write failed: " + n);
                        return false;
                    }
                    if (n == 0) {
                        // Paused by a flush: resume.
                        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                        else return true;
                    }
                    done += n;
                }
                return true;
            }

            @Override public void setGain(float gain) {
                if (!closed) track.setVolume(gain);
            }

            @Override public void flush() {
                if (closed) return;
                try {
                    track.pause();
                    track.flush();
                    track.play();
                } catch (IllegalStateException ignored) {
                }
            }

            @Override public void close() {
                if (closed) return;
                closed = true;
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
        };
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
        if (ct == 4 || ct == 8) return new AacDecoder(ct == 8);
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
