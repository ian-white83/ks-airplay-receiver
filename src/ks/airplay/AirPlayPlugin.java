// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import ks.airplay.android.AndroidPlatform;
import ks.airplay.android.Bonjour;
import ks.airplay.android.NowPlayingViews;
import ks.airplay.ptp.PtpClock;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * AirPlay Receiver for Kiosk Satellite: makes the kiosk an AirPlay speaker for iPhone, iPad and Mac. All work
 * happens on one control thread so the KS callbacks return at once.
 */
public final class AirPlayPlugin implements KioskPlugin, AirPlayServer.Listener {
    static final String OVERLAY = "now-playing";
    static final String TILE = "airplay";

    private PluginHost host;
    private ScheduledExecutorService worker;
    private Settings settings;
    private Platform platform;

    // Owned by the worker thread.
    private AirPlayServer server;
    private AirPlayConfig config;
    private Bonjour bonjour;
    private boolean receiverOn = true;
    private String resolvedName;
    private volatile NowPlaying np = NowPlaying.EMPTY;
    private final NowPlayingViews views = new NowPlayingViews(new NowPlayingViews.Source() {
        @Override public NowPlaying nowPlaying() { return np; }
    });
    private boolean sessionLive, cardShown, modeSet, fullScreenAvailable, startedRightAway;
    private String codec = "";
    private ScheduledFuture<?> hideCard, idleLater;
    static final String SCREENSAVER_KEY = "now_playing";
    static final String SCREENSAVER_MODE = "plugin:airplay2-multiroom:" + SCREENSAVER_KEY;
    private static final long IDLE_SECONDS = 15;
    private float duck = 1f;
    private PtpClock ptp;
    private boolean redirected, ptpStarting;
    private String syncState = "";
    private ScheduledFuture<?> syncRefresh;

    static final int REDIRECT_OFFSET = 10000;
    /** Adds iptables redirects for PTP (319, 320) to ports an app may open. Run as root through Shizuku. */
    static final String REDIRECT_ADD = redirectScript("-C", "-I");
    static final String REDIRECT_REMOVE = redirectScript(null, "-D");

    static String redirectScript(String check, String action) {
        StringBuilder sb = new StringBuilder("r=0; ");
        for (String tool : new String[] {"/system/bin/iptables", "/system/bin/ip6tables"}) {
            for (int port : new int[] {319, 320}) {
                String rule = " -w -t nat %s PREROUTING -p udp --dport " + port + " -j REDIRECT --to-ports " + (port + REDIRECT_OFFSET);
                if (check != null) {
                    sb.append(tool).append(String.format(rule, check)).append(" 2>/dev/null || ")
                      .append(tool).append(String.format(rule, action)).append(" || { echo '").append(tool).append(' ').append(port)
                      .append(" failed'; ").append(tool.endsWith("/iptables") ? "r=1; " : "").append("}; ");
                } else {
                    sb.append("while ").append(tool).append(String.format(rule, action)).append(" 2>/dev/null; do :; done; ");
                }
            }
        }
        return sb.append("exit $r").toString();
    }

    /** Validated settings with defaults. */
    static final class Settings {
        String name = "";
        boolean duckVoice = true;
        int duckLevel = 20;
        double audioOffset = 0;
        boolean wakeScreen = false;
        String nowPlaying = "On track change";
        String position = "Bottom";
        String fullScreen = "When the screensaver starts";
        int port = 7100;
        String deviceId = "";
        String features = "";

        static Settings from(Map<String, Object> map) {
            Settings s = new Settings();
            if (map == null) return s;
            s.name = string(map, "name", s.name).trim();
            s.duckVoice = bool(map, "duckVoice", s.duckVoice);
            s.duckLevel = (int) Math.round(number(map, "duckLevel", s.duckLevel));
            s.audioOffset = Math.max(-3.0, Math.min(3.0, number(map, "audioOffset", 0)));
            s.wakeScreen = bool(map, "wakeScreen", s.wakeScreen);
            s.nowPlaying = string(map, "nowPlaying", s.nowPlaying);
            s.position = string(map, "position", s.position);
            s.fullScreen = string(map, "fullScreen", s.fullScreen);
            try {
                int p = Integer.parseInt(string(map, "port", "7100").trim());
                s.port = p >= 1024 && p <= 65535 ? p : 7100;
            } catch (NumberFormatException e) {
                s.port = 7100;
            }
            s.deviceId = string(map, "deviceId", "").trim();
            s.features = string(map, "features", "").trim();
            return s;
        }

        /** True when a change needs the receiver to restart and re-advertise. */
        boolean needsRestart(Settings o) {
            return o == null || !name.equals(o.name) || port != o.port || !deviceId.equalsIgnoreCase(o.deviceId)
                    || !features.equals(o.features);
        }

        private static String string(Map<String, Object> m, String k, String d) {
            Object v = m.get(k);
            return v instanceof String ? (String) v : d;
        }

        private static double number(Map<String, Object> m, String k, double d) {
            Object v = m.get(k);
            return v instanceof Number ? ((Number) v).doubleValue() : d;
        }

        private static boolean bool(Map<String, Object> m, String k, boolean d) {
            Object v = m.get(k);
            return v instanceof Boolean ? (Boolean) v : d;
        }
    }

    public AirPlayPlugin() {}

    /** For tests: run against a fake platform instead of Android. */
    AirPlayPlugin(Platform platform) {
        this.platform = platform;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start(PluginHost host, Map<String, Object> raw) throws Exception {
        this.host = host;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "airplay-control");
                t.setDaemon(true);
                return t;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        worker = executor;
        if (platform == null) {
            platform = new AndroidPlatform(new AndroidPlatform.Logger() {
                @Override public void log(String message) { AirPlayPlugin.this.log(message); }
            });
        }
        settings = Settings.from(raw);
        publishEntities();
        publishFullScreen();
        try {
            host.subscribe("voice.state");
        } catch (RuntimeException e) {
            log("Voice ducking unavailable: " + e.getMessage());
        }
        ensureDeviceId(raw);
        resolveNameAndStart();
    }

    @Override
    public void configure(Map<String, Object> raw) {
        final Settings next = Settings.from(raw);
        final Settings previous = settings;
        settings = next;
        if (worker == null) return;
        if (!AirPlayConfig.validDeviceId(next.deviceId)) {
            ensureDeviceId(raw);
            return;
        }
        if (next.needsRestart(previous)) {
            if (!next.name.equals(previous == null ? null : previous.name)) resolvedName = null;
            resolveNameAndStart();
        } else {
            post(new Runnable() { @Override public void run() {
                applyDuck();
                applyOffset();
                if (fullScreenOff()) clearMode();
                else if (np.playing) ensureMode();
                refreshCard(true);
            } });
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("disconnect".equals(command)) {
            post(new Runnable() { @Override public void run() { if (server != null) server.disconnectActive(); } });
        } else if ("restart".equals(command)) {
            post(new Runnable() { @Override public void run() { restartReceiver(); } });
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("ks.voice.state".equals(event)) {
            final Object state = payload == null ? null : payload.get("state");
            post(new Runnable() {
                @Override public void run() {
                    boolean talking = state != null && !"idle".equals(state);
                    duck = talking ? settings.duckLevel / 100f : 1f;
                    applyDuck();
                }
            });
        } else if ("shizuku.state".equals(event)) {
            post(new Runnable() { @Override public void run() { if (receiverOn && server != null && ptp == null) startPtp(); } });
        } else if ("switch.receiver".equals(event)) {
            final boolean on = payload != null && Boolean.TRUE.equals(payload.get("on"));
            post(new Runnable() {
                @Override public void run() {
                    receiverOn = on;
                    if (on) {
                        startReceiver();
                    } else {
                        stopReceiver("Receiver turned off");
                        stopPtp();
                    }
                    safe(new Runnable() { @Override public void run() { host.publishSwitch("receiver", "AirPlay receiver", receiverOn); } });
                }
            });
        }
    }

    @Override
    public void stop() throws Exception {
        ScheduledExecutorService w = worker;
        if (w == null) return;
        w.submit(new Runnable() {
            @Override public void run() {
                stopReceiver(null);
                stopPtp();
                hideCardNow();
            }
        });
        w.shutdown();
        if (!w.awaitTermination(2500, TimeUnit.MILLISECONDS)) w.shutdownNow();
        worker = null;
    }

    // ---------------------------------------------------------------- receiver control (worker thread)

    private void ensureDeviceId(Map<String, Object> raw) {
        if (AirPlayConfig.validDeviceId(settings.deviceId)) return;
        String id = AirPlayConfig.randomDeviceId(new SecureRandom());
        settings.deviceId = id;
        try {
            host.updateSettings(Collections.<String, Object>singletonMap("deviceId", id));
        } catch (UnsupportedOperationException e) {
            Map<String, Object> all = new HashMap<>(raw == null ? Collections.<String, Object>emptyMap() : raw);
            all.put("deviceId", id);
            try {
                host.saveSettings(all);
            } catch (RuntimeException e2) {
                log("Could not save the device ID: " + e2.getMessage());
            }
        } catch (RuntimeException e) {
            log("Could not save the device ID: " + e.getMessage());
        }
    }

    /** Use the configured name, or ask KS for this kiosk's device name, then (re)start the receiver. */
    private void resolveNameAndStart() {
        if (!settings.name.isEmpty()) {
            resolvedName = settings.name;
            post(new Runnable() { @Override public void run() { restartReceiver(); } });
            return;
        }
        try {
            host.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    String name = null;
                    if (ok && data instanceof Map) {
                        Object n = ((Map<?, ?>) data).get("name");
                        if (n instanceof String && !((String) n).trim().isEmpty()) name = ((String) n).trim();
                    }
                    resolvedName = name;
                    post(new Runnable() { @Override public void run() { restartReceiver(); } });
                }
            });
        } catch (RuntimeException e) {
            resolvedName = null;
            post(new Runnable() { @Override public void run() { restartReceiver(); } });
        }
    }

    private void restartReceiver() {
        stopReceiver(null);
        if (receiverOn) startReceiver();
    }

    private void startReceiver() {
        if (server != null) return;
        Settings s = settings;
        if (!AirPlayConfig.validDeviceId(s.deviceId)) return;
        try {
            long features = AirPlayConfig.parseFeatures(s.features, AirPlayConfig.DEFAULT_FEATURES);
            config = new AirPlayConfig(resolvedName != null ? resolvedName : s.name, s.deviceId, s.port, features);
            AirPlayServer next = new AirPlayServer(config, platform, this);
            next.start();
            server = next;
            applyDuck();
            applyOffset();
            if (ptp != null) next.setClock(clockFor(ptp));
        } catch (Exception e) {
            server = null;
            String message = e instanceof java.net.BindException ? "Port " + s.port + " is in use. Pick another port under Advanced." : "Could not start: " + e.getMessage();
            status(message, true);
            tile("warn", message);
            log(message);
            return;
        }
        if (advertise()) {
            status("Ready as \"" + config.name + "\"", false);
            tile("", "Ready as " + config.name);
        }
        if (ptp == null) startPtp();
    }

    // ---------------------------------------------------------------- PTP and root (worker thread)

    private static BufferedSession.Clock clockFor(final PtpClock clock) {
        return new BufferedSession.Clock() {
            @Override public Long offset(long timelineId) { return clock.offset(timelineId); }
        };
    }

    /**
     * Multi-room needs the sender's PTP clock on UDP 319 and 320. Use them directly if this device lets apps bind
     * them, otherwise redirect them with iptables through Shizuku running as root.
     */
    private void startPtp() {
        if (ptp != null || ptpStarting) return;
        PtpClock direct = new PtpClock(ptpLog(), 319, 320);
        try {
            direct.start();
            usePtp(direct, "on ports 319 and 320");
            return;
        } catch (java.io.IOException ignored) {
            // normal without root
        }
        Map<String, Object> state;
        try {
            state = host.shizukuState();
        } catch (RuntimeException e) {
            sync("Multi-room off: Shizuku is unavailable (" + e.getMessage() + ")");
            return;
        }
        Object uid = state == null ? null : state.get("uid");
        if (!Boolean.TRUE.equals(state == null ? null : state.get("granted")) || !(uid instanceof Number) || ((Number) uid).intValue() != 0) {
            sync("Multi-room off: start Shizuku as root and give Kiosk Satellite access (status "
                    + (state == null ? "unknown" : state.get("status")) + ", uid " + uid + ")");
            return;
        }
        ptpStarting = true;
        try {
            host.executeShizuku(new String[] {"/system/bin/sh", "-c", REDIRECT_ADD}, 15000, new PluginHost.CommandCallback() {
                @Override public void onResult(final boolean ok, final Object data, final String error) {
                    post(new Runnable() { @Override public void run() { redirectDone(ok, data, error); } });
                }
            });
        } catch (RuntimeException e) {
            ptpStarting = false;
            sync("Multi-room off: could not run the port redirect (" + e.getMessage() + ")");
        }
    }

    private void redirectDone(boolean ok, Object data, String error) {
        ptpStarting = false;
        Map<?, ?> result = data instanceof Map ? (Map<?, ?>) data : null;
        Object exit = result == null ? null : result.get("exitCode");
        if (!ok || !(exit instanceof Number) || ((Number) exit).intValue() != 0) {
            String detail = ok ? String.valueOf(result == null ? null : result.get("stdout")).trim() + " " + String.valueOf(result == null ? null : result.get("stderr")).trim() : error;
            sync("Multi-room off: the iptables redirect failed: " + detail);
            return;
        }
        String out = result.get("stdout") == null ? "" : String.valueOf(result.get("stdout")).trim();
        if (!out.isEmpty()) log("Port redirect: " + out); // IPv6 can fail where IPv4 works
        redirected = true;
        PtpClock clock = new PtpClock(ptpLog(), 319 + REDIRECT_OFFSET, 320 + REDIRECT_OFFSET);
        try {
            clock.start();
            usePtp(clock, "through redirected ports " + (319 + REDIRECT_OFFSET) + " and " + (320 + REDIRECT_OFFSET));
        } catch (java.io.IOException e) {
            sync("Multi-room off: could not open the redirected PTP ports: " + e.getMessage());
        }
    }

    private void usePtp(PtpClock clock, String how) {
        ptp = clock;
        AirPlayServer s = server;
        if (s != null) s.setClock(clockFor(clock));
        log("PTP listening " + how);
        sync("Multi-room ready (PTP " + how + ")");
        if (syncRefresh != null) syncRefresh.cancel(false);
        syncRefresh = worker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                PtpClock c = ptp;
                if (c != null) sync("Multi-room ready, " + c.describe());
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void stopPtp() {
        if (syncRefresh != null) {
            syncRefresh.cancel(false);
            syncRefresh = null;
        }
        PtpClock c = ptp;
        ptp = null;
        AirPlayServer s = server;
        if (s != null) s.setClock(null);
        if (c != null) c.stop();
        if (redirected) {
            redirected = false;
            try {
                host.executeShizuku(new String[] {"/system/bin/sh", "-c", REDIRECT_REMOVE}, 15000, new PluginHost.CommandCallback() {
                    @Override public void onResult(boolean ok, Object data, String error) {}
                });
            } catch (RuntimeException ignored) {
                // stopping; the rules only point at ports nothing else uses
            }
        }
    }

    private PtpClock.Log ptpLog() {
        return new PtpClock.Log() { @Override public void log(String message) { AirPlayPlugin.this.log(message); } };
    }

    private void sync(final String text) {
        if (text.equals(syncState)) return;
        syncState = text;
        safe(new Runnable() { @Override public void run() { host.publishTextSensor("sync", "AirPlay multi-room", text); } });
        if (text.startsWith("Multi-room off")) log(text);
    }

    /** Register the Bonjour services. False when this device cannot advertise at all. */
    private boolean advertise() {
        try {
            if (bonjour == null) bonjour = Bonjour.create();
            Bonjour.Callback callback = new Bonjour.Callback() {
                @Override public void onRegistered(String type, String name) { log("Advertised " + type + " as " + name); }
                @Override public void onFailed(final String type, final int error) {
                    post(new Runnable() {
                        @Override public void run() {
                            String message = "Bonjour advertising failed for " + type + " (error " + error + ")";
                            status(message, true);
                            tile("warn", message);
                        }
                    });
                }
            };
            bonjour.register("_airplay._tcp", config.name, config.port, config.airplayTxt(null, false), callback);
            bonjour.register("_raop._tcp", config.raopServiceName(), config.port, config.raopTxt(), callback);
            return true;
        } catch (Throwable e) {
            String message = "Bonjour is unavailable, so senders cannot find this kiosk: " + e.getMessage();
            status(message, true);
            tile("warn", "Not discoverable");
            log(message);
            return false;
        }
    }

    private void stopReceiver(String why) {
        if (bonjour != null) bonjour.unregisterAll();
        AirPlayServer s = server;
        server = null;
        if (s != null) s.stop();
        clearPlayback();
        if (why != null) {
            status(why, false);
            tile("off", why);
        }
    }

    private void applyOffset() {
        AirPlayServer s = server;
        if (s != null) s.setOffset(Math.round(settings.audioOffset * 1_000_000_000L));
    }

    private void applyDuck() {
        AirPlayServer s = server;
        if (s != null) s.setDuck(settings.duckVoice ? duck : 1f);
    }

    private void clearPlayback() {
        boolean was = np.playing;
        np = NowPlaying.EMPTY;
        sessionLive = false;
        startedRightAway = false;
        codec = "";
        cancelIdle();
        clearMode();
        publishPlayback();
        hideCardNow();
        views.refresh();
        if (was) fire("playbackStopped");
    }

    // ---------------------------------------------------------------- AirPlayServer.Listener (server threads)

    @Override public void onSessionStarted(final String clientName) {
        post(new Runnable() { @Override public void run() {
            np = np.withSource(clientName);
            safe(new Runnable() { @Override public void run() { host.publishTextSensor("source", "AirPlay source", np.source); } });
            status("Connected to " + np.source, false);
        } });
    }

    @Override public void onStreamStarted(final String clientName, final int ct) {
        post(new Runnable() { @Override public void run() {
            codec = ct == 2 ? "ALAC" : ct == 4 ? "AAC" : "codec " + ct;
            status("Streaming from " + clientName + " (" + codec + ")", false);
        } });
    }

    @Override public void onAudioActive(final boolean active) {
        post(new Runnable() { @Override public void run() {
            if (np.playing == active) return;
            np = np.withPlaying(active, System.nanoTime());
            publishPlayback();
            if (active) {
                sessionLive = true;
                cancelIdle();
                fire("playbackStarted");
                if (settings.wakeScreen) command("stopScreensaver", null);
                ensureMode();
                if (!startedRightAway && settings.fullScreen.toLowerCase(Locale.ROOT).startsWith("right") && fullScreenAvailable) {
                    startedRightAway = true;
                    command("startScreensaver", null);
                }
            } else {
                fire("playbackStopped");
                // Pauses and gaps between tracks are short: keep the views until playback has really stopped.
                cancelIdle();
                idleLater = worker.schedule(new Runnable() { @Override public void run() {
                    sessionLive = false;
                    startedRightAway = false;
                    clearMode();
                    hideCardNow();
                } }, IDLE_SECONDS, TimeUnit.SECONDS);
            }
            refreshCard(false);
        } });
    }

    @Override public void onSessionEnded() {
        post(new Runnable() { @Override public void run() {
            clearPlayback();
            if (config != null && server != null) {
                status("Ready as \"" + config.name + "\"", false);
                tile("", "Ready as " + config.name);
            }
        } });
    }

    @Override public void onVolume(final double db) {
        post(new Runnable() { @Override public void run() {
            final double percent = AudioCurve.percent(db);
            safe(new Runnable() { @Override public void run() { host.publishSensor("volume", "AirPlay volume", volumeMeta(), (double) Math.round(percent)); } });
        } });
    }

    @Override public void onMetadata(final Map<String, String> metadata) {
        post(new Runnable() { @Override public void run() {
            String title = value(metadata.get("title"));
            boolean changed = !title.equals(np.title);
            np = np.withTrack(title, metadata.get("artist"), metadata.get("album"));
            publishPlayback();
            refreshCard(changed);
        } });
    }

    @Override public void onArtwork(final byte[] image, String contentType) {
        post(new Runnable() { @Override public void run() {
            np = np.withArtwork(image);
            views.refresh();
        } });
    }

    @Override public void onProgress(final long start, final long current, final long end) {
        post(new Runnable() { @Override public void run() {
            np = np.withProgress(start, current, end, System.nanoTime());
            views.refresh();
        } });
    }

    // ---------------------------------------------------------------- full-screen view (worker thread)

    /** Register the full-screen Now Playing view as a plugin screensaver, once per session. */
    private void publishFullScreen() {
        if (isHeadless()) return;
        try {
            host.publishScreensaverView(SCREENSAVER_KEY, "AirPlay Now Playing", views.fullScreen(new Runnable() {
                @Override public void run() {
                    post(new Runnable() { @Override public void run() { refreshCard(false); } });
                }
            }));
            fullScreenAvailable = true;
        } catch (UnsupportedOperationException | LinkageError e) {
            fullScreenAvailable = false;
            log("Full-screen Now Playing needs a newer Kiosk Satellite: " + e.getMessage());
        } catch (RuntimeException e) {
            fullScreenAvailable = false;
            log("Full-screen Now Playing unavailable: " + e.getMessage());
        }
    }

    private boolean fullScreenOff() {
        return settings.fullScreen.toLowerCase(Locale.ROOT).startsWith("off");
    }

    /** Make the full-screen view the screensaver while AirPlay plays. KS still decides when it starts. */
    private void ensureMode() {
        if (modeSet || !fullScreenAvailable || fullScreenOff()) return;
        modeSet = true;
        command("setScreensaverMode", Collections.<String, Object>singletonMap("mode", SCREENSAVER_MODE));
    }

    private void clearMode() {
        if (!modeSet) return;
        modeSet = false;
        command("clearScreensaverMode", null);
    }

    /** Tapping the floating player opens the full-screen view now. */
    private void openFullScreen() {
        if (!fullScreenAvailable) return;
        if (!modeSet) {
            modeSet = true;
            command("setScreensaverMode", Collections.<String, Object>singletonMap("mode", SCREENSAVER_MODE));
        }
        command("startScreensaver", null);
    }

    private void cancelIdle() {
        if (idleLater != null) {
            idleLater.cancel(false);
            idleLater = null;
        }
    }

    private void command(final String name, Map<String, Object> args) {
        final Map<String, Object> arguments = args == null ? Collections.<String, Object>emptyMap() : args;
        safe(new Runnable() { @Override public void run() {
            host.executeCommand(name, arguments, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) { if (!ok) log(name + ": " + error); }
            });
        } });
    }

    // ---------------------------------------------------------------- Kiosk Satellite output

    private void publishEntities() {
        safe(new Runnable() { @Override public void run() {
            host.publishBinarySensor("playing", "AirPlay playing", "running", false);
            host.publishTextSensor("track", "AirPlay track", "");
            host.publishTextSensor("artist", "AirPlay artist", "");
            host.publishTextSensor("album", "AirPlay album", "");
            host.publishTextSensor("source", "AirPlay source", "");
            host.publishSensor("volume", "AirPlay volume", volumeMeta(), null);
            host.publishSwitch("receiver", "AirPlay receiver", true);
            host.publishTextSensor("sync", "AirPlay multi-room", "Starting");
        } });
    }

    private static Map<String, Object> volumeMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unit", "%");
        meta.put("stateClass", "measurement");
        meta.put("accuracyDecimals", 0);
        return meta;
    }

    private void publishPlayback() {
        final NowPlaying n = np;
        safe(new Runnable() { @Override public void run() {
            host.publishBinarySensor("playing", "AirPlay playing", "running", n.playing);
            host.publishTextSensor("track", "AirPlay track", n.title);
            host.publishTextSensor("artist", "AirPlay artist", n.artist);
            host.publishTextSensor("album", "AirPlay album", n.album);
            host.publishTextSensor("source", "AirPlay source", n.source);
        } });
        if (n.playing) {
            String what = n.title.isEmpty() ? "Playing from " + n.source : "Playing " + n.title + (n.artist.isEmpty() ? "" : " by " + n.artist);
            tile("on", what);
        }
    }

    /**
     * Show, keep or hide the floating player: always while a session plays (or briefly pauses) in "While playing",
     * for a few seconds after each track change in "On track change", and never over the full-screen view.
     */
    private void refreshCard(boolean trackChanged) {
        views.refresh();
        if (host == null || isHeadless()) return;
        String mode = settings.nowPlaying.toLowerCase(Locale.ROOT);
        boolean live = sessionLive && np.hasContent();
        if (mode.startsWith("off") || !live || views.fullScreenCount() > 0) {
            hideCardNow();
            return;
        }
        if (mode.startsWith("while")) {
            if (!cardShown) showCard();
        } else if (trackChanged || !cardShown && np.playing && hideCard == null) {
            showCard();
            if (hideCard != null) hideCard.cancel(false);
            hideCard = worker.schedule(new Runnable() { @Override public void run() { hideCard = null; hideCardNow(); } }, 8, TimeUnit.SECONDS);
        }
    }

    private void showCard() {
        cardShown = true;
        final String anchor = settings.position.toLowerCase(Locale.ROOT).startsWith("top") ? OverlaySpec.TOP : OverlaySpec.BOTTOM;
        final me.jxl.kiosk.plugins.OverlayFactory floating = views.floating(new NowPlayingViews.Tap() {
            @Override public void onTap() {
                post(new Runnable() { @Override public void run() { openFullScreen(); } });
            }
        });
        safe(new Runnable() { @Override public void run() {
            host.showOverlay(OVERLAY, OverlaySpec.at(anchor).inset(24).enter(OverlaySpec.SLIDE).exit(OverlaySpec.SLIDE).touchable(true), floating);
        } });
    }

    private void hideCardNow() {
        if (hideCard != null) {
            hideCard.cancel(false);
            hideCard = null;
        }
        if (!cardShown) return;
        cardShown = false;
        if (host == null || isHeadless()) return;
        safe(new Runnable() { @Override public void run() { host.hideOverlay(OVERLAY); } });
    }

    private boolean isHeadless() {
        try {
            return host.isHeadless();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void fire(final String trigger) {
        safe(new Runnable() { @Override public void run() {
            try {
                host.fireTrigger(trigger);
            } catch (UnsupportedOperationException ignored) {
            }
        } });
    }

    private void status(final String message, final boolean error) {
        safe(new Runnable() { @Override public void run() { host.status(message, error); } });
    }

    private void tile(final String level, final String text) {
        safe(new Runnable() { @Override public void run() { host.publishStatusTile(TILE, "AirPlay", level, text); } });
    }

    void log(final String message) {
        safe(new Runnable() { @Override public void run() { host.log(message); } });
    }

    private void post(Runnable task) {
        ScheduledExecutorService w = worker;
        if (w == null) return;
        try {
            w.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopping
        }
    }

    /** Host calls throw after the session is revoked or on older KS releases. Never let that kill the plugin. */
    private void safe(Runnable call) {
        if (host == null) return;
        try {
            call.run();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            // revoked, or not available on this KS version
        } catch (RuntimeException e) {
            try {
                host.log("Host call failed: " + e);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String value(String s) {
        return s == null ? "" : s;
    }
}
