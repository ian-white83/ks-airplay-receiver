// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Apple's ALAC reference decoder: Copyright (c) 2011 Apple Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0. See NOTICE.
package ks.airplay;

/**
 * Apple Lossless decoder for 16-bit audio, following Apple's open-source reference (Apache-2.0) ALAC decoder:
 * adaptive Golomb entropy decoding, the adaptive FIR predictor and stereo unmixing. AirPlay sends one ALAC frame
 * per RTP packet, normally 352 stereo samples.
 */
public final class AlacDecoder {
    private static final int ID_SCE = 0, ID_CPE = 1, ID_CCE = 2, ID_LFE = 3, ID_DSE = 4, ID_PCE = 5, ID_FIL = 6, ID_END = 7;
    private static final int QBSHIFT = 9, QB = 1 << QBSHIFT, MMULSHIFT = 2, MDENSHIFT = QBSHIFT - MMULSHIFT - 1;
    private static final int MOFF = 1 << (MDENSHIFT - 2), BITOFF = 24, MAX_PREFIX_16 = 9, MAX_PREFIX_32 = 9;
    private static final int MAX_DATATYPE_BITS_16 = 16, N_MAX_MEAN_CLAMP = 0xffff, N_MEAN_CLAMP_VAL = 0xffff;

    private final int frameLength, bitDepth, pb, mb, kb, channels, maxRun;
    private final int[] predictor, mixU, mixV;
    private final short[] coefsU = new short[32], coefsV = new short[32];
    private byte[] in = new byte[0];
    private int bitPos, maxPos;

    /** AirPlay's fixed configuration: 352 samples, 16 bits, pb 40, mb 10, kb 14, stereo, max run 255. */
    public static AlacDecoder airPlay(int samplesPerFrame) {
        return new AlacDecoder(samplesPerFrame > 0 ? samplesPerFrame : 352, 16, 40, 10, 14, 2, 255);
    }

    public AlacDecoder(int frameLength, int bitDepth, int pb, int mb, int kb, int channels, int maxRun) {
        if (bitDepth != 16) throw new IllegalArgumentException("Only 16-bit ALAC is supported");
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("Only mono and stereo ALAC are supported");
        if (frameLength < 1 || frameLength > 16384) throw new IllegalArgumentException("Bad ALAC frame length");
        this.frameLength = frameLength;
        this.bitDepth = bitDepth;
        this.pb = pb;
        this.mb = mb;
        this.kb = kb;
        this.channels = channels;
        this.maxRun = maxRun;
        predictor = new int[frameLength];
        mixU = new int[frameLength];
        mixV = new int[frameLength];
    }

    public int frameLength() { return frameLength; }
    public int channels() { return channels; }

    /**
     * Decode one frame into interleaved 16-bit samples. Returns the number of samples per channel written, which
     * is at most frameLength. out must hold frameLength * channels values.
     */
    public int decode(byte[] data, int offset, int length, short[] out) {
        if (in.length < length + 8) in = new byte[length + 8];
        System.arraycopy(data, offset, in, 0, length);
        for (int i = length; i < length + 8; i++) in[i] = 0;
        bitPos = 0;
        maxPos = length * 8;
        int channelIndex = 0;
        int samples = 0;
        while (true) {
            if (bitPos + 3 > maxPos) break;
            int tag = readBits(3);
            switch (tag) {
                case ID_SCE:
                case ID_LFE: {
                    if (channelIndex >= channels) throw new IllegalStateException("Too many ALAC channels");
                    samples = decodeSingle(out, channelIndex);
                    channelIndex += 1;
                    break;
                }
                case ID_CPE: {
                    if (channelIndex + 2 > channels) throw new IllegalStateException("Too many ALAC channels");
                    samples = decodePair(out, channelIndex);
                    channelIndex += 2;
                    break;
                }
                case ID_DSE: {
                    readBits(4); // element instance tag
                    int align = readBits(1);
                    int count = readBits(8);
                    if (count == 255) count += readBits(8);
                    if (align != 0) byteAlign();
                    bitPos += count * 8;
                    break;
                }
                case ID_FIL: {
                    int count = readBits(4);
                    if (count == 15) count += readBits(8) - 1;
                    bitPos += count * 8;
                    break;
                }
                case ID_END:
                    byteAlign();
                    return samples;
                case ID_CCE:
                case ID_PCE:
                default:
                    throw new IllegalStateException("Unsupported ALAC element " + tag);
            }
            if (bitPos > maxPos) throw new IllegalStateException("ALAC frame overrun");
        }
        return samples;
    }

    private int decodeSingle(short[] out, int channelIndex) {
        readBits(4); // element instance tag
        readBits(12); // unused
        int header = readBits(4);
        boolean partial = (header >> 3) != 0;
        int bytesShifted = (header >> 1) & 3;
        boolean escape = (header & 1) != 0;
        if (bytesShifted != 0) throw new IllegalStateException("Shifted ALAC samples are not used at 16 bits");
        int numSamples = frameLength;
        if (partial) numSamples = readPartialLength();
        int chanBits = bitDepth;
        if (!escape) {
            readBits(8); // mixBits
            readBits(8); // mixRes
            int h = readBits(8);
            int mode = h >> 4, denShift = h & 15;
            h = readBits(8);
            int pbFactor = h >> 5, num = h & 31;
            for (int i = 0; i < num; i++) coefsU[i] = (short) readBits(16);
            dynDecomp((pb * pbFactor) / 4, numSamples, chanBits);
            if (mode != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
            unpcBlock(predictor, mixU, numSamples, coefsU, num, chanBits, denShift);
        } else {
            int shift = 32 - chanBits;
            for (int i = 0; i < numSamples; i++) mixU[i] = (readBits(chanBits) << shift) >> shift;
        }
        for (int i = 0, j = channelIndex; i < numSamples; i++, j += channels) out[j] = (short) mixU[i];
        return numSamples;
    }

    private int decodePair(short[] out, int channelIndex) {
        readBits(4); // element instance tag
        readBits(12); // unused
        int header = readBits(4);
        boolean partial = (header >> 3) != 0;
        int bytesShifted = (header >> 1) & 3;
        boolean escape = (header & 1) != 0;
        if (bytesShifted != 0) throw new IllegalStateException("Shifted ALAC samples are not used at 16 bits");
        int numSamples = frameLength;
        if (partial) numSamples = readPartialLength();
        int mixBits = 0, mixRes = 0;
        if (!escape) {
            int chanBits = bitDepth + 1;
            mixBits = readBits(8);
            mixRes = (byte) readBits(8);
            int h = readBits(8);
            int modeU = h >> 4, denShiftU = h & 15;
            h = readBits(8);
            int pbFactorU = h >> 5, numU = h & 31;
            for (int i = 0; i < numU; i++) coefsU[i] = (short) readBits(16);
            h = readBits(8);
            int modeV = h >> 4, denShiftV = h & 15;
            h = readBits(8);
            int pbFactorV = h >> 5, numV = h & 31;
            for (int i = 0; i < numV; i++) coefsV[i] = (short) readBits(16);

            dynDecomp((pb * pbFactorU) / 4, numSamples, chanBits);
            if (modeU != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
            unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU);

            dynDecomp((pb * pbFactorV) / 4, numSamples, chanBits);
            if (modeV != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
            unpcBlock(predictor, mixV, numSamples, coefsV, numV, chanBits, denShiftV);
        } else {
            int chanBits = bitDepth;
            int shift = 32 - chanBits;
            for (int i = 0; i < numSamples; i++) {
                mixU[i] = (readBits(chanBits) << shift) >> shift;
                mixV[i] = (readBits(chanBits) << shift) >> shift;
            }
        }
        // unmix16
        int j = channelIndex;
        if (mixRes != 0) {
            for (int i = 0; i < numSamples; i++, j += channels) {
                int l = mixU[i] + mixV[i] - ((mixRes * mixV[i]) >> mixBits);
                out[j] = (short) l;
                out[j + 1] = (short) (l - mixV[i]);
            }
        } else {
            for (int i = 0; i < numSamples; i++, j += channels) {
                out[j] = (short) mixU[i];
                out[j + 1] = (short) mixV[i];
            }
        }
        return numSamples;
    }

    private int readPartialLength() {
        int n = (readBits(16) << 16) | readBits(16);
        if (n <= 0 || n > frameLength) throw new IllegalStateException("Bad ALAC partial frame length " + n);
        return n;
    }

    // ---------------------------------------------------------------- adaptive Golomb decoding (ag_dec)

    private void dynDecomp(int pbLocal, int numSamples, int maxSize) {
        int wbLocal = (1 << kb) - 1;
        long mbLocal = mb;
        int zmode = 0;
        int c = 0;
        while (c < numSamples) {
            if (bitPos >= maxPos) throw new IllegalStateException("ALAC entropy data overrun");
            int m = (int) (mbLocal >>> QBSHIFT);
            int k = 31 - Integer.numberOfLeadingZeros(m + 3);
            if (k > kb) k = kb;
            m = (1 << k) - 1;
            long n = dynGet32Bit(m, k, maxSize) & 0xffffffffL;
            long ndecode = n + zmode;
            int multiplier = (int) (-(ndecode & 1)) | 1;
            predictor[c++] = (int) (((ndecode + 1) >> 1) * multiplier);
            mbLocal = (pbLocal * (n + zmode) + mbLocal - ((pbLocal * mbLocal) >>> QBSHIFT)) & 0xffffffffL;
            if (n > N_MAX_MEAN_CLAMP) mbLocal = N_MEAN_CLAMP_VAL;
            zmode = 0;
            if (((mbLocal << MMULSHIFT) < QB) && c < numSamples) {
                zmode = 1;
                int mbi = (int) mbLocal;
                int kz = Integer.numberOfLeadingZeros(mbi) - BITOFF + ((mbi + MOFF) >> MDENSHIFT);
                int mz = ((1 << kz) - 1) & wbLocal;
                int run = dynGet(mz, kz);
                if (c + run > numSamples) throw new IllegalStateException("ALAC zero run overrun");
                for (int j = 0; j < run; j++) predictor[c++] = 0;
                if (run >= 65535) zmode = 0;
                mbLocal = 0;
            }
        }
        if (bitPos > maxPos) throw new IllegalStateException("ALAC entropy data overrun");
    }

    private int peek32() {
        int b = bitPos >>> 3;
        int word = ((in[b] & 0xff) << 24) | ((in[b + 1] & 0xff) << 16) | ((in[b + 2] & 0xff) << 8) | (in[b + 3] & 0xff);
        return word << (bitPos & 7);
    }

    private static int top(int word, int bits) {
        return bits == 0 ? 0 : word >>> (32 - bits);
    }

    private int dynGet(int m, int k) {
        int stream = peek32();
        int pre = Integer.numberOfLeadingZeros(~stream);
        int result;
        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            bitPos += pre;
            stream <<= pre;
            result = top(stream, MAX_DATATYPE_BITS_16);
            bitPos += MAX_DATATYPE_BITS_16;
        } else {
            bitPos += pre + 1;
            stream <<= pre + 1;
            int v = top(stream, k);
            bitPos += k;
            result = pre * m + v - 1;
            if (v < 2) {
                result -= (v - 1);
                bitPos -= 1;
            }
        }
        return result;
    }

    private int dynGet32Bit(int m, int k, int maxBits) {
        int stream = peek32();
        int result = Integer.numberOfLeadingZeros(~stream);
        if (result >= MAX_PREFIX_32) {
            bitPos += MAX_PREFIX_32;
            result = readBits(maxBits);
        } else {
            bitPos += result + 1;
            if (k != 1) {
                stream <<= result + 1;
                int v = top(stream, k);
                bitPos += k - 1;
                result = result * m;
                if (v >= 2) {
                    result += v - 1;
                    bitPos += 1;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- adaptive FIR predictor (dp_dec)

    private static int signOf(int i) {
        return ((-i) >>> 31) | (i >> 31);
    }

    private static void unpcBlock(int[] pc1, int[] out, int num, short[] coefs, int numActive, int chanBits, int denShift) {
        int chanShift = 32 - chanBits;
        int denHalf = denShift > 0 ? 1 << (denShift - 1) : 0;
        out[0] = pc1[0];
        if (numActive == 0) {
            if (num > 1 && pc1 != out) System.arraycopy(pc1, 1, out, 1, num - 1);
            return;
        }
        if (numActive == 31) {
            int prev = out[0];
            for (int j = 1; j < num; j++) {
                int del = pc1[j] + prev;
                prev = (del << chanShift) >> chanShift;
                out[j] = prev;
            }
            return;
        }
        for (int j = 1; j <= numActive && j < num; j++) {
            int del = pc1[j] + out[j - 1];
            out[j] = (del << chanShift) >> chanShift;
        }
        int lim = numActive + 1;
        for (int j = lim; j < num; j++) {
            int sum1 = 0;
            int p = j - 1; // pout
            int top = out[j - lim];
            for (int k = 0; k < numActive; k++) sum1 += coefs[k] * (out[p - k] - top);
            int del = pc1[j];
            int del0 = del;
            int sg = signOf(del);
            del += top + ((sum1 + denHalf) >> denShift);
            out[j] = (del << chanShift) >> chanShift;
            if (sg > 0) {
                for (int k = numActive - 1; k >= 0; k--) {
                    int dd = top - out[p - k];
                    int sgn = signOf(dd);
                    coefs[k] -= sgn;
                    del0 -= (numActive - k) * ((sgn * dd) >> denShift);
                    if (del0 <= 0) break;
                }
            } else if (sg < 0) {
                for (int k = numActive - 1; k >= 0; k--) {
                    int dd = top - out[p - k];
                    int sgn = signOf(dd);
                    coefs[k] += sgn;
                    del0 -= (numActive - k) * ((-sgn * dd) >> denShift);
                    if (del0 >= 0) break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- bit reading

    private int readBits(int n) {
        if (n == 0) return 0;
        int b = bitPos >>> 3;
        long word = ((long) (in[b] & 0xff) << 32) | ((long) (in[b + 1] & 0xff) << 24) | ((in[b + 2] & 0xff) << 16)
                | ((in[b + 3] & 0xff) << 8) | (in[b + 4] & 0xff);
        int value = (int) ((word >>> (40 - (bitPos & 7) - n)) & ((1L << n) - 1));
        bitPos += n;
        return value;
    }

    private void byteAlign() {
        bitPos = (bitPos + 7) & ~7;
    }
}
