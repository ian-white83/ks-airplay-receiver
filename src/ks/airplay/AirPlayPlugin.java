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
import ks.airplay.android.NowPlayingCard;
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
    private boolean playing;
    private String source = "";
    private String title = "", artist = "", album = "";
    private byte[] artwork;
    private String codec = "";
    private ScheduledFuture<?> hideCard;
    private String lastCardTitle;
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
        boolean wakeScreen = false;
        String nowPlaying = "On track change";
        String position = "Bottom";
        int port = 7100;
        String deviceId = "";
        String features = "";

        static Settings from(Map<String, Object> map) {
            Settings s = new Settings();
            if (map == null) return s;
            s.name = string(map, "name", s.name).trim();
            s.duckVoice = bool(map, "duckVoice", s.duckVoice);
            s.duckLevel = (int) Math.round(number(map, "duckLevel", s.duckLevel));
            s.wakeScreen = bool(map, "wakeScreen", s.wakeScreen);
            s.nowPlaying = string(map, "nowPlaying", s.nowPlaying);
            s.position = string(map, "position", s.position);
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
            post(new Runnable() { @Override public void run() { applyDuck(); refreshCard(true); } });
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

    private void applyDuck() {
        AirPlayServer s = server;
        if (s != null) s.setDuck(settings.duckVoice ? duck : 1f);
    }

    private void clearPlayback() {
        boolean was = playing;
        playing = false;
        source = "";
        title = artist = album = "";
        artwork = null;
        codec = "";
        lastCardTitle = null;
        publishPlayback();
        hideCardNow();
        if (was) fire("playbackStopped");
    }

    // ---------------------------------------------------------------- AirPlayServer.Listener (server threads)

    @Override public void onSessionStarted(final String clientName) {
        post(new Runnable() { @Override public void run() {
            source = clientName == null ? "" : clientName;
            safe(new Runnable() { @Override public void run() { host.publishTextSensor("source", "AirPlay source", source); } });
            status("Connected to " + source, false);
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
            if (playing == active) return;
            playing = active;
            publishPlayback();
            if (active) {
                fire("playbackStarted");
                if (settings.wakeScreen) {
                    try {
                        host.executeCommand("stopScreensaver", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
                            @Override public void onResult(boolean ok, Object data, String error) { if (!ok) log("Wake screen: " + error); }
                        });
                    } catch (RuntimeException e) {
                        log("Wake screen: " + e.getMessage());
                    }
                }
            } else {
                fire("playbackStopped");
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
            final double percent = db <= -30 ? 0 : db >= 0 ? 100 : (db + 30) / 30 * 100;
            safe(new Runnable() { @Override public void run() { host.publishSensor("volume", "AirPlay volume", volumeMeta(), (double) Math.round(percent)); } });
        } });
    }

    @Override public void onMetadata(final Map<String, String> metadata) {
        post(new Runnable() { @Override public void run() {
            title = value(metadata.get("title"));
            artist = value(metadata.get("artist"));
            album = value(metadata.get("album"));
            publishPlayback();
            refreshCard(false);
        } });
    }

    @Override public void onArtwork(final byte[] image, String contentType) {
        post(new Runnable() { @Override public void run() {
            artwork = image;
            refreshCard(true);
        } });
    }

    @Override public void onProgress(long start, long current, long end) {}

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
        safe(new Runnable() { @Override public void run() {
            host.publishBinarySensor("playing", "AirPlay playing", "running", playing);
            host.publishTextSensor("track", "AirPlay track", title);
            host.publishTextSensor("artist", "AirPlay artist", artist);
            host.publishTextSensor("album", "AirPlay album", album);
            host.publishTextSensor("source", "AirPlay source", source);
        } });
        if (playing) {
            String what = title.isEmpty() ? "Playing from " + source : "Playing " + title + (artist.isEmpty() ? "" : " by " + artist);
            tile("on", what);
        }
    }

    /** Show, update or hide the Now Playing card according to the setting. */
    private void refreshCard(boolean contentChanged) {
        if (host == null || isHeadless()) return;
        String mode = settings.nowPlaying.toLowerCase(Locale.ROOT);
        if (mode.startsWith("off") || !playing) {
            hideCardNow();
            return;
        }
        boolean trackChanged = !title.equals(lastCardTitle);
        if (mode.startsWith("while")) {
            if (trackChanged || contentChanged) showCard();
        } else if (trackChanged) {
            showCard();
            if (hideCard != null) hideCard.cancel(false);
            hideCard = worker.schedule(new Runnable() { @Override public void run() { hideCardNow(); } }, 8, TimeUnit.SECONDS);
        } else if (contentChanged && hideCard != null && !hideCard.isDone()) {
            showCard();
        }
    }

    private void showCard() {
        lastCardTitle = title;
        final String heading = title.isEmpty() ? (source.isEmpty() ? "AirPlay" : source) : title;
        StringBuilder sub = new StringBuilder(artist);
        if (!album.isEmpty()) sub.append(sub.length() > 0 ? " — " : "").append(album);
        final NowPlayingCard card = new NowPlayingCard(heading, sub.toString(), title.isEmpty() ? "" : source, artwork);
        final String anchor = settings.position.toLowerCase(Locale.ROOT).startsWith("top") ? OverlaySpec.TOP : OverlaySpec.BOTTOM;
        safe(new Runnable() { @Override public void run() {
            host.showOverlay(OVERLAY, OverlaySpec.at(anchor).inset(24).enter(OverlaySpec.SLIDE).exit(OverlaySpec.SLIDE).touchable(false), card);
        } });
    }

    private void hideCardNow() {
        if (hideCard != null) {
            hideCard.cancel(false);
            hideCard = null;
        }
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
