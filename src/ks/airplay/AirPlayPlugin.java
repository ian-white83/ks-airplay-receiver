// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import ks.airplay.adb.AdbKey;
import ks.airplay.adb.ShizukuStarter;
import ks.airplay.android.AndroidPlatform;
import ks.airplay.android.Bonjour;
import ks.airplay.ptp.PtpClock;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PlayerListener;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.PluginPlayer;

/**
 * AirPlay 2 Multi-Room for Kiosk Satellite: a Kiosk Satellite player (the player capability) that makes the kiosk
 * an AirPlay 2 speaker. It serves only while picked under Media Player > Player: then the receiver listens,
 * advertises itself and follows the group's PTP clock, its audio goes through Kiosk Satellite's own output and
 * Kiosk Satellite shows what plays. All work happens on one control thread so the KS callbacks return at once.
 */
public final class AirPlayPlugin implements KioskPlugin, AirPlayServer.Listener {
    static final String PLAYER_KEY = "airplay";
    static final String PLAYER_TITLE = "AirPlay 2";
    static final String TILE = "airplay";

    private PluginHost host;
    private ScheduledExecutorService worker;
    private Settings settings;
    private Platform platform;

    // Owned by the worker thread.
    private volatile PluginPlayer player;
    private AirPlayServer server;
    private AirPlayConfig config;
    private Bonjour bonjour;
    private String resolvedName;
    private boolean nameResolved;
    private volatile NowPlaying np = NowPlaying.EMPTY;
    private boolean sessionLive;
    private volatile DacpRemote remote;
    private volatile java.net.InetSocketAddress remoteAddress;
    /** For DACP requests, which wait on the network. Made in start, shut down in stop. */
    private volatile java.util.concurrent.ExecutorService net;
    private PtpClock ptp;
    private boolean redirected, ptpStarting;
    private String syncState = "";
    private ScheduledFuture<?> syncRefresh;
    private int refreshes;

    // Starting Shizuku through this device's own adbd.
    static final String START_BUTTON = "startShizuku", CHECK_BUTTON = "checkShizuku";
    static final String OFFSET_EARLIER = "offsetEarlier", OFFSET_LATER = "offsetLater";
    private final java.util.concurrent.atomic.AtomicReference<String> starterRun = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Thread starter;
    private AdbKey adbKey;

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
                    sb.append(tool).append(String.format(rule, check)).append(" 2>/dev/null || { ")
                      .append(tool).append(String.format(rule, action)).append(" && echo 'added ").append(tool).append(' ').append(port)
                      .append("'; } || { echo '").append(tool).append(' ').append(port)
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
        double audioOffset = 0;
        int port = 7100;
        String deviceId = "";
        String deviceIdOverride = "";
        String features = "";
        boolean shizukuAtBoot = false;
        boolean shizukuRunning = false;
        String adbKey = "";

        static Settings from(Map<String, Object> map) {
            Settings s = new Settings();
            if (map == null) return s;
            s.name = string(map, "name", s.name).trim();
            s.audioOffset = Math.max(-3.0, Math.min(3.0, number(map, "audioOffset", 0)));
            try {
                int p = Integer.parseInt(string(map, "port", "7100").trim());
                s.port = p >= 1024 && p <= 65535 ? p : 7100;
            } catch (NumberFormatException e) {
                s.port = 7100;
            }
            s.deviceId = string(map, "deviceId", "").trim();
            s.deviceIdOverride = string(map, "deviceIdOverride", "").trim();
            s.features = string(map, "features", "").trim();
            s.shizukuAtBoot = bool(map, "shizukuAtBoot", s.shizukuAtBoot);
            s.shizukuRunning = bool(map, "shizukuRunning", false);
            s.adbKey = string(map, "adbKey", "").trim();
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
        net = java.util.concurrent.Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "airplay-net");
                t.setDaemon(true);
                return t;
            }
        });
        if (platform == null) {
            platform = new AndroidPlatform(new AndroidPlatform.Logger() {
                @Override public void log(String message) { AirPlayPlugin.this.log(message); }
            });
        }
        String seed = platform.deviceSeed();
        seedDeviceId = seed == null ? null : AirPlayConfig.deviceIdFrom(seed);
        settings = Settings.from(raw);
        applyDeviceId(settings);
        publishEntities();
        ensureDeviceId(raw);
        resolveName();
        try {
            host.publishPlayer(PLAYER_KEY, PLAYER_TITLE, new Listener());
            status(NOT_PICKED, false);
            tile("off", "Not the selected player");
        } catch (UnsupportedOperationException | LinkageError e) {
            String message = "This Kiosk Satellite has no plugin players. Update Kiosk Satellite to 2026.11.14 or later.";
            status(message, true);
            log(message + " (" + e + ")");
        }
        if (!settings.adbKey.isEmpty()) {
            // An earlier version kept the ADB key in settings: move it to this device's private storage now.
            post(new Runnable() { @Override public void run() {
                try {
                    adbKey();
                } catch (Exception e) {
                    log("Could not move the ADB key: " + e);
                }
            } });
        }
        if (settings.shizukuAtBoot) {
            // Give Kiosk Satellite a moment to connect to a Shizuku that is already running.
            worker.schedule(new Runnable() { @Override public void run() { bootStart(); } }, 8, TimeUnit.SECONDS);
        }
    }

    static final String NOT_PICKED = "Not the selected player. Pick it under Settings > Media Player > Player.";

    @Override
    public void configure(Map<String, Object> raw) {
        final Settings next = Settings.from(raw);
        applyDeviceId(next);
        final Settings previous = settings;
        settings = next;
        if (worker == null) return;
        if (!AirPlayConfig.validDeviceId(next.deviceId)) {
            ensureDeviceId(raw);
            return;
        }
        if (previous != null && next.shizukuRunning != previous.shizukuRunning) {
            post(new Runnable() { @Override public void run() { refreshShizuku(null); } });
        }
        if (previous != null && previous.shizukuAtBoot && !next.shizukuAtBoot) {
            post(new Runnable() { @Override public void run() { revokeBootStart(); } });
        }
        if (next.needsRestart(previous)) {
            if (previous == null || !next.name.equals(previous.name)) nameResolved = false;
            resolveName();
            post(new Runnable() { @Override public void run() { if (player != null) restartReceiver(); } });
        } else {
            post(new Runnable() { @Override public void run() { applyOffset(); } });
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("disconnect".equals(command)) {
            post(new Runnable() { @Override public void run() { if (server != null) server.disconnectActive(); } });
        } else if ("restart".equals(command)) {
            post(new Runnable() { @Override public void run() { if (player != null) restartReceiver(); } });
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("shizuku.state".equals(event)) {
            post(new Runnable() { @Override public void run() {
                if (server != null && ptp == null) startPtp();
                if (starter == null) refreshShizuku(null);
            } });
        } else if ("settings.button".equals(event)) {
            Object id = payload == null ? null : payload.get("id");
            if ((OFFSET_EARLIER.equals(id) || OFFSET_LATER.equals(id)) && payload.get("run") instanceof String) {
                final String run = (String) payload.get("run");
                final double delta = OFFSET_LATER.equals(id) ? 0.01 : -0.01;
                post(new Runnable() { @Override public void run() { nudgeOffset(run, delta); } });
            } else if ((START_BUTTON.equals(id) || CHECK_BUTTON.equals(id)) && payload.get("run") instanceof String) {
                final String run = (String) payload.get("run");
                post(new Runnable() { @Override public void run() { startShizuku(run); } });
            }
        } else if ("settings.cancel".equals(event)) {
            Object run = payload == null ? null : payload.get("run");
            if (run != null && run.equals(starterRun.get())) {
                starterRun.set(null);
                Thread t = starter;
                if (t != null) t.interrupt();
            }
        }
    }

    @Override
    public void stop() throws Exception {
        ScheduledExecutorService w = worker;
        if (w == null) return;
        w.submit(guard("stop", new Runnable() {
            @Override public void run() {
                player = null;
                // The root cleanup goes first, before any thread joins, so it is sent within the stop deadline.
                removeRedirect();
                stopReceiver(null);
                stopPtp();
            }
        }));
        w.shutdown();
        // Kiosk Satellite gives stop() three seconds. Every join below has a short limit, and the sockets they
        // wait on are closed first, so the threads end within a few milliseconds in practice.
        if (!w.awaitTermination(2500, TimeUnit.MILLISECONDS)) w.shutdownNow();
        worker = null;
        java.util.concurrent.ExecutorService n = net;
        net = null;
        if (n != null) n.shutdownNow();
        starterRun.set(null);
        Thread t = starter;
        if (t != null) t.interrupt();
    }

    // ---------------------------------------------------------------- the player (KS threads, then worker)

    /**
     * Kiosk Satellite's side of the player. Nothing serves until the pick arrives: a receiver that advertised while
     * another player is picked would show on iPhones as a speaker that plays nowhere.
     */
    final class Listener implements PlayerListener {
        @Override public void onActivate(final PluginPlayer p) {
            post(new Runnable() { @Override public void run() {
                player = p;
                log("Picked as the player: starting the AirPlay receiver");
                restartReceiver();
            } });
        }

        @Override public void onDeactivate() {
            post(new Runnable() { @Override public void run() {
                player = null;
                stopReceiver(null);
                stopPtp();
                sync("Multi-room off: not the selected player");
                status(NOT_PICKED, false);
                tile("off", "Not the selected player");
                log("No longer the player: the AirPlay receiver stopped");
            } });
        }

        @Override public void onCommand(String command, Map<String, Object> arguments) {
            if ("play".equals(command) || "pause".equals(command)) remoteControl(DacpRemote.PLAY_PAUSE);
            else if ("next".equals(command)) remoteControl(DacpRemote.NEXT);
            else if ("previous".equals(command)) remoteControl(DacpRemote.PREVIOUS);
        }
    }

    /** Tell Kiosk Satellite what plays: Now Playing, the floating player and the ESPHome media player show it. */
    private void publishState() {
        final PluginPlayer p = player;
        if (p == null) return;
        NowPlaying n = np;
        final Map<String, Object> state = new HashMap<>();
        state.put("state", n.playing ? "playing" : sessionLive && n.hasContent() ? "paused" : "idle");
        if (!n.title.isEmpty()) state.put("title", clip(n.title));
        if (!n.artist.isEmpty()) state.put("artist", clip(n.artist));
        if (!n.album.isEmpty()) state.put("album", clip(n.album));
        if (n.title.isEmpty() && !n.source.isEmpty()) state.put("title", clip("AirPlay from " + n.source));
        if (n.artwork != null && n.artwork.length <= 1 << 20) state.put("artwork", n.artwork);
        if (n.hasProgress()) {
            state.put("durationMs", Math.round(n.durationSeconds() * 1000));
            state.put("positionMs", Math.round(position(n) * 1000));
        }
        List<String> actions = new ArrayList<>();
        if (remote != null) Collections.addAll(actions, "play", "pause", "next", "previous");
        state.put("actions", actions);
        safe(new Runnable() { @Override public void run() { p.update(state); } });
    }

    /** Seconds into the track that the speaker plays now: from the playout's own clock when it has one. */
    private double position(NowPlaying n) {
        AirPlayServer s = server;
        long rtp = s == null ? Long.MIN_VALUE : s.playingRtp();
        if (rtp != Long.MIN_VALUE && n.hasProgress()) {
            return Math.max(0, Math.min(((int) (rtp - n.startRtp())) / 44100.0, n.durationSeconds()));
        }
        return n.elapsedSeconds(System.nanoTime());
    }

    private static String clip(String s) {
        return s.length() > 512 ? s.substring(0, 512) : s;
    }

    // ---------------------------------------------------------------- receiver control (worker thread)

    /** Made from this device when Android gives an ID, so Fleet Management can't copy it to other kiosks. */
    private String seedDeviceId;

    /**
     * The device ID to advertise: the override setting when set, otherwise one made from this device. Only without
     * a device ID from Android (tests) does it fall back to the random ID saved in the old deviceId setting.
     */
    private void applyDeviceId(Settings s) {
        if (AirPlayConfig.validDeviceId(s.deviceIdOverride)) s.deviceId = s.deviceIdOverride;
        else if (seedDeviceId != null) s.deviceId = seedDeviceId;
    }

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

    /** Use the configured name, or ask KS for this kiosk's device name. */
    private void resolveName() {
        if (!settings.name.isEmpty()) {
            resolvedName = settings.name;
            nameResolved = true;
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
                    final String resolved = name;
                    post(new Runnable() { @Override public void run() {
                        boolean changed = nameResolved && resolved != null && !resolved.equals(resolvedName);
                        resolvedName = resolved;
                        nameResolved = true;
                        if (player != null && (server == null || changed)) restartReceiver();
                    } });
                }
            });
        } catch (RuntimeException e) {
            resolvedName = null;
            nameResolved = true;
        }
    }

    private void restartReceiver() {
        stopReceiver(null);
        if (player != null) startReceiver();
    }

    private void startReceiver() {
        if (server != null || player == null) return;
        Settings s = settings;
        if (!AirPlayConfig.validDeviceId(s.deviceId)) return;
        if (!nameResolved && s.name.isEmpty()) return; // resolveName starts it once the device name arrives
        try {
            long features = AirPlayConfig.parseFeatures(s.features, AirPlayConfig.DEFAULT_FEATURES);
            String name = resolvedName != null ? resolvedName : s.name.isEmpty() ? "Kiosk" : s.name;
            config = new AirPlayConfig(name, s.deviceId, s.port, features);
            AirPlayServer next = new AirPlayServer(config, new PlayerPlatform(platform, player), this);
            next.start();
            server = next;
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
        publishState();
    }

    // ---------------------------------------------------------------- PTP and root (worker thread)

    private static BufferedSession.Clock clockFor(final PtpClock clock) {
        return new BufferedSession.Clock() {
            @Override public Long offset(long timelineId) { return clock.offset(timelineId); }
            @Override public Long offset(long timelineId, long anchorNetwork, long anchorLocal) { return clock.offset(timelineId, anchorNetwork, anchorLocal); }
            @Override public void peers(java.util.List<String> addresses) { clock.peers(addresses); }
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
                if (redirected && ++refreshes % 2 == 0) repairRedirect();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * Put the port redirect back if something removed it, such as the previous session of this plugin cleaning
     * up after a plugin update just after this session added it. Without it no PTP reaches the plugin.
     */
    private void repairRedirect() {
        try {
            host.executeShizuku(new String[] {"/system/bin/sh", "-c", REDIRECT_ADD}, 15000, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    Object out = ok && data instanceof Map ? ((Map<?, ?>) data).get("stdout") : null;
                    String text = out == null ? "" : String.valueOf(out).trim();
                    if (text.contains("added /system/bin/iptables")) log("The PTP port redirect was missing and has been added again: " + text.replace("\n", ", "));
                }
            });
        } catch (RuntimeException ignored) {
            // busy with another Shizuku command or Shizuku went away: try again next time
        }
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
        removeRedirect();
        if (c != null) c.stop();
    }

    /** Take the PTP port redirect out. Kiosk Satellite may already have closed Shizuku when the plugin stops. */
    private void removeRedirect() {
        if (!redirected) return;
        redirected = false;
        try {
            host.executeShizuku(new String[] {"/system/bin/sh", "-c", REDIRECT_REMOVE}, 15000, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {}
            });
        } catch (RuntimeException ignored) {
            // Shizuku is gone or busy; the rules only point at ports nothing else uses
        }
    }

    private PtpClock.Log ptpLog() {
        return new PtpClock.Log() { @Override public void log(String message) { AirPlayPlugin.this.log(message); } };
    }

    // ---------------------------------------------------------------- starting Shizuku (worker thread)

    private static boolean rootReady(Map<String, Object> state) {
        Object uid = state == null ? null : state.get("uid");
        return state != null && Boolean.TRUE.equals(state.get("granted")) && uid instanceof Number && ((Number) uid).intValue() == 0;
    }

    /** The Shizuku reading under the Start button, in words. */
    static String describe(Map<String, Object> state) {
        if (state == null || !Boolean.TRUE.equals(state.get("available"))) return "Not running";
        if (!Boolean.TRUE.equals(state.get("granted"))) return "Running, but Kiosk Satellite has no access. Tap Shizuku access.";
        Object uid = state.get("uid");
        if (uid instanceof Number && ((Number) uid).intValue() == 0) return "Running as root";
        return "Running as shell, not root (uid " + uid + ")";
    }

    /** Update the Shizuku reading, and the setting that picks the Start or Started button. */
    private void refreshShizuku(String failure) {
        Map<String, Object> state = shizukuStateOrNull();
        boolean root = rootReady(state);
        String now = describe(state);
        shizukuStatus(failure != null && !root ? now + ". Start failed: " + failure : now);
        if (settings.shizukuRunning != root) {
            settings.shizukuRunning = root;
            final boolean value = root;
            safe(new Runnable() { @Override public void run() {
                try {
                    host.updateSettings(Collections.<String, Object>singletonMap("shizukuRunning", value));
                } catch (LinkageError ignored) {
                    // older Kiosk Satellite: only the Start button shows
                }
            } });
        }
    }

    private void shizukuStatus(final String text) {
        safe(new Runnable() { @Override public void run() { host.publishTextSensor("shizuku", "Shizuku", text.length() > 255 ? text.substring(0, 255) : text); } });
    }

    private Map<String, Object> shizukuStateOrNull() {
        try {
            return host.shizukuState();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final String KEY_NAME = "ks-airplay@kiosk";
    static final String KEY_FILE = "adbkey.v1";

    /**
     * The plugin's ADB key, kept in a private file on this device only: never in settings, so configuration
     * exports and Fleet Management never copy it and no two kiosks share it. Android remembers a key someone
     * allowed, so it must stay the same from boot to boot. Versions 0.6.2 to 0.7.0 kept it in a secret setting;
     * it moves to the file once, and the setting is cleared after the file reads back the same key.
     */
    private AdbKey adbKey() throws Exception {
        if (adbKey != null) return adbKey;
        java.io.File dir = platform.dataDir();
        java.io.File file = dir == null ? null : new java.io.File(dir, KEY_FILE);
        if (file != null && file.isFile()) {
            try {
                adbKey = AdbKey.fromSecret(readText(file), KEY_NAME);
                clearOldKeySetting();
                return adbKey;
            } catch (Exception e) {
                log("The saved ADB key is unreadable, making a new one: " + e.getMessage());
            }
        }
        AdbKey k = null;
        if (!settings.adbKey.isEmpty()) {
            try {
                k = AdbKey.fromSecret(settings.adbKey, KEY_NAME);
            } catch (Exception e) {
                log("The ADB key from an earlier version is unreadable: " + e.getMessage());
            }
        }
        boolean migrated = k != null;
        if (k == null) k = AdbKey.generate(KEY_NAME);
        adbKey = k;
        if (file == null) {
            log("No private storage for the ADB key: Android will ask to allow it again after a restart");
            return k;
        }
        try {
            writeText(file, k.toSecret());
            if (!AdbKey.fromSecret(readText(file), KEY_NAME).publicKey().equals(k.publicKey())) throw new java.io.IOException("read back a different key");
            if (migrated) {
                log("Moved the ADB key out of the plugin's settings into private storage on this device");
                clearOldKeySetting();
            } else {
                log("Made a new ADB key for this device. Android asks once to allow it.");
            }
        } catch (Exception e) {
            log("Could not save the ADB key (" + e + "): Android will ask to allow it again after a restart");
        }
        return k;
    }

    private void clearOldKeySetting() {
        if (settings.adbKey.isEmpty()) return;
        settings.adbKey = "";
        safe(new Runnable() { @Override public void run() {
            try {
                host.updateSettings(Collections.<String, Object>singletonMap("adbKey", ""));
            } catch (LinkageError ignored) {
            }
        } });
    }

    private static String readText(java.io.File f) throws java.io.IOException {
        byte[] b = new byte[(int) Math.min(f.length(), 8192)];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, Util.UTF8).trim();
        }
    }

    private static void writeText(java.io.File f, String text) throws java.io.IOException {
        java.io.File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(text.getBytes(Util.UTF8));
            out.getFD().sync();
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot save " + f);
    }

    /**
     * Undo the boot start's access when it is turned off: take this plugin's key out of Android's trusted ADB
     * keys and delete the key. Needs Shizuku running as root; without it the README says how to undo by hand.
     * ADB on port 5555 was set up by the person, not the plugin, so it is left as they set it.
     */
    private void revokeBootStart() {
        adbKey = null;
        java.io.File dir = platform.dataDir();
        if (dir != null) {
            java.io.File f = new java.io.File(dir, KEY_FILE);
            if (f.isFile() && !f.delete()) log("Could not delete the ADB key file " + f);
        }
        clearOldKeySetting();
        if (!rootReady(shizukuStateOrNull())) {
            log("Boot start turned off. Shizuku isn't running as root, so the plugin's key stays in Android's trusted ADB keys: use Revoke USB debugging authorizations in Developer options to remove it.");
            return;
        }
        String script = "f=/data/misc/adb/adb_keys\n"
                + "if grep -q ' " + KEY_NAME + "$' \"$f\" 2>/dev/null; then\n"
                + "  grep -v ' " + KEY_NAME + "$' \"$f\" > \"$f.tmp\"; cat \"$f.tmp\" > \"$f\"; rm -f \"$f.tmp\"; echo 'removed the key'\n"
                + "fi\n"
                + "exit 0";
        try {
            host.executeShizuku(new String[] {"/system/bin/sh", "-c", script}, 10000, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    Object out = data instanceof Map ? ((Map<?, ?>) data).get("stdout") : null;
                    if (!ok) log("Could not remove the plugin's key from Android's trusted ADB keys: " + error + ". Use Revoke USB debugging authorizations in Developer options.");
                    else log("Boot start turned off: " + (out != null && String.valueOf(out).contains("removed") ? "removed the plugin's key from Android's trusted ADB keys" : "the plugin's key was not trusted"));
                }
            });
        } catch (RuntimeException e) {
            log("Could not remove the plugin's key from Android's trusted ADB keys: " + e.getMessage() + ". Use Revoke USB debugging authorizations in Developer options.");
        }
    }

    /** At boot: start Shizuku as root unless it already runs that way. */
    private void bootStart() {
        if (!settings.shizukuAtBoot) return;
        if (rootReady(shizukuStateOrNull())) return;
        launchStarter(null, false);
    }

    /** The settings button: start Shizuku, or just check the ADB path when it already runs as root. */
    private void startShizuku(String run) {
        launchStarter(run, rootReady(shizukuStateOrNull()));
    }

    private void launchStarter(final String run, final boolean alreadyRunning) {
        if (starter != null) {
            if (run != null) setup(run, "error", "Already starting Shizuku. Wait for it to finish.");
            return;
        }
        final AdbKey key;
        try {
            key = adbKey();
        } catch (Exception e) {
            if (run != null) setup(run, "error", "Could not create the ADB key: " + e.getMessage());
            log("Could not create the ADB key: " + e);
            return;
        }
        starterRun.set(run);
        if (run == null) sync("Multi-room off: starting Shizuku as root");
        if (!alreadyRunning) shizukuStatus("Starting…");
        Thread t = new Thread(guard("airplay-shizuku", new Runnable() {
            @Override public void run() {
                String error = null, output = "";
                // At boot nobody may be at the screen to allow the key: keep asking for up to ten minutes.
                int attempts = run == null ? 10 : 1;
                for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    error = null;
                    output = new ShizukuStarter(key).run(alreadyRunning, run == null ? 120000 : 10000, 60000, new ShizukuStarter.Progress() {
                        @Override public boolean report(String message) {
                            if (Thread.currentThread().isInterrupted()) return false;
                            if (message == null) return run == null || run.equals(starterRun.get());
                            if (run == null) {
                                log("Shizuku: " + message);
                                return true;
                            }
                            return run.equals(starterRun.get()) && setup(run, "running", message);
                        }
                    });
                    break;
                } catch (ShizukuStarter.Stopped e) {
                    error = "";
                    break;
                } catch (ks.airplay.adb.AdbClient.NotApproved e) {
                    error = e.getMessage();
                    if (attempt < attempts) {
                        log("Shizuku: " + error + ". Asking again in 30 seconds. Tick Always allow, then Allow.");
                        try {
                            Thread.sleep(30000);
                        } catch (InterruptedException ie) {
                            error = "";
                            break;
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                    break;
                }
                }
                final String err = error, out = output;
                starter = null;
                post(new Runnable() { @Override public void run() { refreshShizuku(err == null || err.isEmpty() ? null : err); } });
                if (err != null && err.isEmpty()) return;
                if (!out.isEmpty()) log("Shizuku starter: " + out);
                String message;
                if (err != null) {
                    message = "Could not start Shizuku: " + err;
                    log(message);
                    if (run == null) post(new Runnable() { @Override public void run() { sync("Multi-room off: " + err); } });
                } else if (alreadyRunning) {
                    message = "Shizuku already runs as root, and the boot start can reach ADB as root.";
                } else {
                    message = "Shizuku started. Multi-room turns on once Kiosk Satellite connects to it.";
                    log(message);
                }
                if (run != null && run.equals(starterRun.get())) setup(run, err == null ? "success" : "error", message);
                starterRun.compareAndSet(run, null);
            }
        }), "airplay-shizuku");
        t.setDaemon(true);
        starter = t;
        t.start();
    }

    private boolean setup(String run, String state, String message) {
        try {
            return host.setupResult(run, state, message.length() > 1000 ? message.substring(0, 1000) : message);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
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

    /** The nudge buttons: move the audio offset by one step, save it and apply it at once. */
    private void nudgeOffset(String run, double delta) {
        double next = Math.round((settings.audioOffset + delta) * 100) / 100.0;
        next = Math.max(-3.0, Math.min(3.0, next));
        settings.audioOffset = next;
        applyOffset();
        final double value = next;
        String shown = String.format(java.util.Locale.ROOT, "%+.2f s", value).replace("+0.00", "0.00").replace("-0.00", "0.00");
        try {
            host.updateSettings(Collections.<String, Object>singletonMap("audioOffset", value));
            setup(run, "success", "Audio offset " + shown + (Math.abs(value) >= 3.0 ? " (the limit)" : ""));
        } catch (RuntimeException | LinkageError e) {
            setup(run, "error", "Applied " + shown + " for now, but could not save it: " + e.getMessage());
        }
    }

    private void applyOffset() {
        AirPlayServer s = server;
        if (s != null) s.setOffset(Math.round(settings.audioOffset * 1_000_000_000L));
    }

    private void clearPlayback() {
        boolean was = np.playing;
        np = NowPlaying.EMPTY;
        sessionLive = false;
        publishState();
        if (was) fire("playbackStopped");
    }

    // ---------------------------------------------------------------- AirPlayServer.Listener (server threads)

    @Override public void onSessionStarted(final String clientName) {
        post(new Runnable() { @Override public void run() {
            np = np.withSource(clientName);
            status("Connected to " + np.source, false);
        } });
    }

    @Override public void onStreamStarted(final String clientName, final int ct) {
        post(new Runnable() { @Override public void run() {
            String codec = ct == 2 ? "ALAC" : ct == 4 ? "AAC" : "codec " + ct;
            status("Streaming from " + clientName + " (" + codec + ")", false);
        } });
    }

    @Override public void onAudioActive(final boolean active) {
        post(new Runnable() { @Override public void run() {
            if (np.playing == active) return;
            np = np.withPlaying(active, System.nanoTime());
            if (active) sessionLive = true;
            publishState();
            fire(active ? "playbackStarted" : "playbackStopped");
            if (active) {
                String what = np.title.isEmpty() ? "Playing from " + np.source : "Playing " + np.title + (np.artist.isEmpty() ? "" : " by " + np.artist);
                tile("on", what);
            } else if (config != null) {
                tile("", "Ready as " + config.name);
            }
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

    /** The sender's volume is applied to the audio itself (see OutputSink); Kiosk Satellite's volume comes on top. */
    @Override public void onVolume(double db) {}

    @Override public void onMetadata(final Map<String, String> metadata) {
        post(new Runnable() { @Override public void run() {
            np = np.withTrack(value(metadata.get("title")), metadata.get("artist"), metadata.get("album"));
            publishState();
        } });
    }

    @Override public void onArtwork(final byte[] image, String contentType) {
        post(new Runnable() { @Override public void run() {
            np = np.withArtwork(image);
            publishState();
        } });
    }

    @Override public void onProgress(final long start, final long current, final long end) {
        post(new Runnable() { @Override public void run() {
            np = np.withProgress(start, current, end, System.nanoTime());
            publishState();
        } });
    }

    @Override public void onRemote(final DacpRemote r, final java.net.InetAddress sender) {
        post(new Runnable() { @Override public void run() {
            boolean first = remote == null || !r.dacpId.equals(remote.dacpId);
            remote = r;
            remoteAddress = null;
            if (first) {
                log("The sender offers remote control (DACP " + r.dacpId + " from " + sender.getHostAddress() + ")");
                publishState();
            }
        } });
    }

    /** Play/pause or skip on the sender, over DACP. Runs on the network thread. */
    private void remoteControl(final String command) {
        final DacpRemote r = remote;
        final java.util.concurrent.ExecutorService n = net;
        if (r == null || n == null) return;
        try {
            n.execute(guard("DACP remote control", new Runnable() { @Override public void run() {
            for (int attempt = 0; attempt < 2; attempt++) {
                java.net.InetSocketAddress address = remoteAddress;
                if (address == null) {
                    address = platform.findService("_dacp._tcp", r.serviceName(), 4000);
                    if (address == null) {
                        log("Could not find the sender's remote control (" + r.serviceName() + ") on the network");
                        return;
                    }
                    remoteAddress = address;
                }
                try {
                    int status = r.send(address, command);
                    if (status != 200 && status != 204) log("The sender refused " + command + " (HTTP " + status + ")");
                    return;
                } catch (java.io.IOException e) {
                    remoteAddress = null; // it may have moved: look it up again
                    if (attempt == 1) log("Remote control failed: " + e.getMessage());
                }
            }
        } }));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopping
        }
    }

    private Runnable guard(String name, Runnable task) {
        return Util.guard(name, task, new Util.Logger() { @Override public void log(String m) { AirPlayPlugin.this.log(m); } });
    }

    // ---------------------------------------------------------------- Kiosk Satellite output

    private void publishEntities() {
        safe(new Runnable() { @Override public void run() {
            host.publishTextSensor("sync", "AirPlay multi-room", "Off: not the selected player");
        } });
        post(new Runnable() { @Override public void run() { refreshShizuku(null); } });
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
            w.execute(guard("airplay-control", task));
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
