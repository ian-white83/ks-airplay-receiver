// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The AirPlay receiver: accepts RTSP connections on the configured port and keeps one sender playing at a time.
 * A new sender that starts a stream takes over from the previous one, the way an Apple TV does.
 */
public final class AirPlayServer {
    /** What the plugin hears about. Calls come from server threads. */
    public interface Listener {
        /** A sender set up a session. */
        void onSessionStarted(String clientName);
        /** The audio stream began: ct 2 ALAC, 4 AAC-LC, 8 AAC-ELD. */
        void onStreamStarted(String clientName, int ct);
        /** Audio started or stopped reaching the speaker. */
        void onAudioActive(boolean active);
        /** The session ended: the sender disconnected, stopped or was replaced. */
        void onSessionEnded();
        void onVolume(double db);
        void onMetadata(Map<String, String> metadata);
        /** Cover art bytes, or null when the sender clears it. */
        void onArtwork(byte[] image, String contentType);
        /** RTP timestamps of the track start, now and end, 44100 per second. */
        void onProgress(long start, long current, long end);
    }

    private final AirPlayConfig config;
    private final Platform platform;
    private final Listener listener;
    private final List<RtspConnection> connections = new ArrayList<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private RtspConnection active;
    private volatile double volumeDb = -12.0;
    private volatile float duck = 1f;

    public AirPlayServer(AirPlayConfig config, Platform platform, Listener listener) {
        this.config = config;
        this.platform = platform;
        this.listener = listener;
    }

    public AirPlayConfig config() { return config; }
    Listener listener() { return listener; }

    /** Bind the port and start accepting senders. Returns the bound port. */
    public synchronized int start() throws IOException {
        if (running) return serverSocket.getLocalPort();
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.port), 8);
        serverSocket = socket;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "airplay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return socket.getLocalPort();
    }

    public int port() {
        ServerSocket s = serverSocket;
        return s == null ? 0 : s.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                RtspConnection connection = new RtspConnection(this, socket, config, platform);
                synchronized (this) {
                    if (!running) {
                        socket.close();
                        return;
                    }
                    if (connections.size() >= 8) {
                        // Senders open short-lived connections for /info. Drop the oldest idle one.
                        RtspConnection oldest = null;
                        for (RtspConnection c : connections) if (c != active) { oldest = c; break; }
                        if (oldest != null) oldest.close();
                    }
                    connections.add(connection);
                }
                Thread t = new Thread(connection, "airplay-rtsp");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) platform.log("AirPlay accept failed: " + e.getMessage());
            }
        }
    }

    /** Stop listening and disconnect every sender. Waits briefly for threads to end. */
    public void stop() {
        List<RtspConnection> all;
        synchronized (this) {
            if (!running) return;
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            all = new ArrayList<>(connections);
        }
        for (RtspConnection c : all) c.close();
        try {
            acceptThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Disconnect the sender that is playing, if any. */
    public void disconnectActive() {
        RtspConnection c;
        synchronized (this) { c = active; }
        if (c != null) c.close();
    }

    public synchronized boolean hasActiveSession() { return active != null; }

    public synchronized String activeClient() { return active == null ? null : active.clientName(); }

    public double volumeDb() { return volumeDb; }

    void setVolumeDb(double db) {
        volumeDb = db;
        listener.onVolume(db);
    }

    /** Attenuate playback while the voice assistant talks: 1 is normal. */
    public void setDuck(float gain) {
        duck = gain;
        RtspConnection c;
        synchronized (this) { c = active; }
        AudioSession a = c == null ? null : c.audio();
        if (a != null) a.setDuck(gain);
    }

    // ---------------------------------------------------------------- connection callbacks

    void sessionStarted(RtspConnection connection) {
        RtspConnection previous;
        synchronized (this) {
            previous = active;
            active = connection;
        }
        if (previous != null && previous != connection) {
            platform.log("New AirPlay sender takes over from " + previous.clientName());
            previous.close();
        }
        listener.onSessionStarted(connection.clientName());
    }

    void streamStarted(RtspConnection connection, int ct) {
        AudioSession a = connection.audio();
        if (a != null) a.setDuck(duck);
        listener.onStreamStarted(connection.clientName(), ct);
    }

    void audioStopped(RtspConnection connection) {
        // AudioSession reports onAudioActive(false) itself when it closes.
    }

    void sessionEnded(RtspConnection connection) {
        boolean was;
        synchronized (this) {
            was = active == connection;
            if (was) active = null;
        }
        if (was) listener.onSessionEnded();
    }

    void connectionEnded(RtspConnection connection) {
        synchronized (this) { connections.remove(connection); }
        sessionEnded(connection);
    }

    AudioSession.Events audioEvents(final RtspConnection connection) {
        return new AudioSession.Events() {
            @Override public void onAudioActive(boolean value) {
                boolean current;
                synchronized (AirPlayServer.this) { current = active == connection; }
                if (current || !value) listener.onAudioActive(value);
            }
        };
    }
}
