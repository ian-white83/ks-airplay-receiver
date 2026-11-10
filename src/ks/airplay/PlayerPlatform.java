// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import me.jxl.kiosk.plugins.AudioOutput;
import me.jxl.kiosk.plugins.PluginPlayer;

/**
 * The device platform, with audio going to Kiosk Satellite's own output while this plugin's player is the pick.
 * One output is kept per format and shared by the sessions that follow each other.
 */
public final class PlayerPlatform implements Platform {
    private final Platform base;
    private final PluginPlayer player;
    private AudioOutput output;
    private int rate, channels;

    public PlayerPlatform(Platform base, PluginPlayer player) {
        this.base = base;
        this.player = player;
    }

    @Override public synchronized AudioSink openSink(int sampleRate, int channels) {
        if (output == null || sampleRate != rate || channels != this.channels) {
            output = player.openAudio(sampleRate, channels, PluginPlayer.ENCODING_PCM_16);
            rate = sampleRate;
            this.channels = channels;
        }
        output.flush();
        return new OutputSink(output, sampleRate, channels);
    }

    @Override public AudioDecoder openDecoder(int ct, int samplesPerFrame) throws Exception { return base.openDecoder(ct, samplesPerFrame); }
    @Override public void log(String message) { base.log(message); }
    @Override public java.io.File dataDir() { return base.dataDir(); }
    @Override public String deviceSeed() { return base.deviceSeed(); }
    @Override public java.net.InetSocketAddress findService(String type, String name, int timeoutMs) { return base.findService(type, name, timeoutMs); }
}
