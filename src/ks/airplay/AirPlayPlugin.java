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

import ks.airplay.adb.AdbKey;
import ks.airplay.lyrics.Lrclib;
import ks.airplay.lyrics.Lyrics;
import ks.airplay.adb.ShizukuStarter;
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
    static final String OVERLAY = "now_playing_card", FULL_OVERLAY = "now_playing_full";
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
        @Override public Lyrics lyrics() { return settings.lyrics ? lyrics : null; }
        @Override public double position() { return lyricsPosition(); }
        @Override public boolean controls() { return remote != null; }
        @Override public void control(String command) { remoteControl(command); }
    });

    // Lyrics for the track playing, and the sender's remote control.
    private volatile Lyrics lyrics;
    private String lyricsKey = "";
    private ScheduledFuture<?> lyricsLater;
    private volatile DacpRemote remote;
    private volatile java.net.InetSocketAddress remoteAddress;
    private final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "airplay-net");
            t.setDaemon(true);
            return t;
        }
    });
    static final String USER_AGENT = "ks-airplay-receiver (https://github.com/ian-white83/ks-airplay-receiver)";
    private boolean sessionLive, cardShown, modeSet, fullScreenAvailable, startedRightAway;
    // Full-screen player overlay: shown, opened by a tap, closed by the user, card closed by back, timed window open.
    private boolean fullShown, fullRequested, fullClosed, cardClosed, windowOpen;
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
    private int refreshes;

    // Starting Shizuku through this device's own adbd.
    static final String START_BUTTON = "startShizuku", CHECK_BUTTON = "checkShizuku";
    private final java.util.concurrent.atomic.AtomicReference<String> starterRun = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Thread starter;
    private boolean keyInstalled;
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
        boolean duckVoice = true;
        int duckLevel = 20;
        double audioOffset = 0;
        boolean lyrics = true;
        double lyricsOffset = 0;
        boolean wakeScreen = false;
        String nowPlaying = "On track change";
        String position = "Bottom";
        String playerStyle = "Floating";
        String fullScreen = "When the screensaver starts";
        int port = 7100;
        String deviceId = "";
        String features = "";
        boolean shizukuAtBoot = false;
        String shizukuCommand = "";
        boolean shizukuRunning = false;

        static Settings from(Map<String, Object> map) {
            Settings s = new Settings();
            if (map == null) return s;
            s.name = string(map, "name", s.name).trim();
            s.duckVoice = bool(map, "duckVoice", s.duckVoice);
            s.duckLevel = (int) Math.round(number(map, "duckLevel", s.duckLevel));
            s.audioOffset = Math.max(-3.0, Math.min(3.0, number(map, "audioOffset", 0)));
            s.lyrics = bool(map, "lyrics", true);
            s.lyricsOffset = Math.max(-3.0, Math.min(3.0, number(map, "lyricsOffset", 0)));
            s.wakeScreen = bool(map, "wakeScreen", s.wakeScreen);
            s.nowPlaying = string(map, "nowPlaying", s.nowPlaying);
            s.position = string(map, "position", s.position);
            s.playerStyle = string(map, "playerStyle", s.playerStyle);
            s.fullScreen = string(map, "fullScreen", s.fullScreen);
            try {
                int p = Integer.parseInt(string(map, "port", "7100").trim());
                s.port = p >= 1024 && p <= 65535 ? p : 7100;
            } catch (NumberFormatException e) {
                s.port = 7100;
            }
            s.deviceId = string(map, "deviceId", "").trim();
            s.features = string(map, "features", "").trim();
            s.shizukuAtBoot = bool(map, "shizukuAtBoot", s.shizukuAtBoot);
            s.shizukuCommand = string(map, "shizukuCommand", "").trim();
            s.shizukuRunning = bool(map, "shizukuRunning", false);
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
        if (settings.shizukuAtBoot) {
            // Give Kiosk Satellite a moment to connect to a Shizuku that is already running.
            worker.schedule(new Runnable() { @Override public void run() { bootStart(); } }, 8, TimeUnit.SECONDS);
        }
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
        if (previous != null && next.shizukuRunning != previous.shizukuRunning) {
            post(new Runnable() { @Override public void run() { refreshShizuku(null); } });
        }
        if (next.shizukuAtBoot && (previous == null || !previous.shizukuAtBoot)) {
            post(new Runnable() { @Override public void run() { keyInstalled = false; prepareBootStart(); } });
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
                // Style or position may have changed: build the player again.
                hideCardNow();
                hideFullNow();
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
        } else if ("playPause".equals(command)) {
            remoteControl(DacpRemote.PLAY_PAUSE);
        } else if ("nextTrack".equals(command)) {
            remoteControl(DacpRemote.NEXT);
        } else if ("previousTrack".equals(command)) {
            remoteControl(DacpRemote.PREVIOUS);
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
            post(new Runnable() { @Override public void run() {
                if (receiverOn && server != null && ptp == null) startPtp();
                if (starter == null) refreshShizuku(null);
                prepareBootStart();
            } });
        } else if ("settings.button".equals(event)) {
            Object id = payload == null ? null : payload.get("id");
            if ((START_BUTTON.equals(id) || CHECK_BUTTON.equals(id)) && payload.get("run") instanceof String) {
                final String run = (String) payload.get("run");
                post(new Runnable() { @Override public void run() { startShizuku(run); } });
            }
        } else if ("overlay.closed".equals(event)) {
            // Back closed one of the players. KS already removed the view.
            final Object key = payload == null ? null : payload.get("key");
            post(new Runnable() { @Override public void run() {
                if (FULL_OVERLAY.equals(key)) {
                    fullShown = false;
                    closeFullScreen();
                } else if (OVERLAY.equals(key)) {
                    cardShown = false;
                    cardClosed = true;
                }
            } });
        } else if ("settings.cancel".equals(event)) {
            Object run = payload == null ? null : payload.get("run");
            if (run != null && run.equals(starterRun.get())) {
                starterRun.set(null);
                Thread t = starter;
                if (t != null) t.interrupt();
            }
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
        starterRun.set(null);
        Thread t = starter;
        if (t != null) t.interrupt();
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

    private AdbKey adbKey() throws Exception {
        if (adbKey == null) {
            java.io.File dir = platform.dataDir();
            adbKey = dir != null ? AdbKey.loadOrCreate(new java.io.File(dir, "adbkey"), "ks-airplay@kiosk") : AdbKey.generate("ks-airplay@kiosk");
        }
        return adbKey;
    }

    /**
     * While Shizuku already runs as root, trust this plugin's ADB key and keep adbd on port 5555 across reboots,
     * so the next boot needs no prompt. If adbd ignores the file, the prompt at the first start covers it.
     */
    private void prepareBootStart() {
        if (keyInstalled || !settings.shizukuAtBoot || !rootReady(shizukuStateOrNull())) return;
        final String line;
        try {
            line = adbKey().publicKey();
        } catch (Exception e) {
            log("Could not create the ADB key: " + e);
            return;
        }
        keyInstalled = true;
        String script = "k='" + line + "'; f=/data/misc/adb/adb_keys\n"
                + "if ! grep -qF \"$k\" \"$f\" 2>/dev/null; then echo \"$k\" >> \"$f\" && chown system:shell \"$f\"; chmod 640 \"$f\"; restorecon \"$f\" 2>/dev/null; echo 'trusted the ADB key'; fi\n"
                + "[ \"$(getprop persist.adb.tcp.port)\" = " + ShizukuStarter.PORT + " ] || { setprop persist.adb.tcp.port " + ShizukuStarter.PORT + "; echo 'kept ADB on port " + ShizukuStarter.PORT + "'; }\n"
                + "exit 0";
        try {
            host.executeShizuku(new String[] {"/system/bin/sh", "-c", script}, 10000, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    Object out = data instanceof Map ? ((Map<?, ?>) data).get("stdout") : null;
                    if (!ok) log("Boot start setup failed: " + error);
                    else if (out != null && !String.valueOf(out).trim().isEmpty()) log("Boot start: " + String.valueOf(out).trim().replace("\n", ", "));
                }
            });
        } catch (RuntimeException e) {
            keyInstalled = false;
            log("Boot start setup failed: " + e.getMessage());
        }
    }

    /** At boot: start Shizuku as root unless it already runs that way. */
    private void bootStart() {
        if (!settings.shizukuAtBoot) return;
        if (rootReady(shizukuStateOrNull())) {
            prepareBootStart();
            return;
        }
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
        final String command = settings.shizukuCommand;
        starterRun.set(run);
        if (run == null) sync("Multi-room off: starting Shizuku as root");
        if (!alreadyRunning) shizukuStatus("Starting…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String error = null, output = "";
                try {
                    output = new ShizukuStarter(key).run(command, alreadyRunning, run == null ? 120000 : 10000, 60000, new ShizukuStarter.Progress() {
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
                } catch (ShizukuStarter.Stopped e) {
                    error = "";
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
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
        }, "airplay-shizuku");
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
        hideFullNow();
        fullRequested = fullClosed = cardClosed = windowOpen = false;
        remote = null;
        remoteAddress = null;
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
                    hideFullNow();
                    fullRequested = fullClosed = cardClosed = windowOpen = false;
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
            scheduleLyrics();
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
            if (lyrics == null) scheduleLyrics(); // the duration helps LRCLIB pick the right version
        } });
    }

    @Override public void onRemote(final DacpRemote r, final java.net.InetAddress sender) {
        post(new Runnable() { @Override public void run() {
            boolean first = remote == null;
            remote = r;
            remoteAddress = null;
            if (first) log("The sender offers remote control (DACP " + r.dacpId + ")");
            views.refresh();
        } });
    }

    // ---------------------------------------------------------------- lyrics and remote control

    /** Look lyrics up a moment after the track settles, once per track. */
    private void scheduleLyrics() {
        if (!settings.lyrics || worker == null) return;
        final NowPlaying n = np;
        final String key = n.title + "\n" + n.artist + "\n" + n.album;
        if (n.title.isEmpty() || n.artist.isEmpty()) return;
        if (!key.equals(lyricsKey)) {
            lyrics = null;
            views.refresh();
        } else if (lyrics != null) {
            return;
        }
        lyricsKey = key;
        if (lyricsLater != null) lyricsLater.cancel(false);
        lyricsLater = worker.schedule(new Runnable() { @Override public void run() {
            lyricsLater = null;
            final NowPlaying now = np;
            if (!key.equals(lyricsKey)) return;
            final double duration = now.durationSeconds();
            net.execute(new Runnable() { @Override public void run() {
                Lyrics found = null;
                try {
                    found = new Lrclib(USER_AGENT).find(now.title, now.artist, now.album, duration);
                } catch (Exception e) {
                    log("Lyrics lookup failed: " + e.getMessage());
                }
                final Lyrics result = found;
                post(new Runnable() { @Override public void run() {
                    if (!key.equals(lyricsKey)) return;
                    lyrics = result;
                    log(result == null ? "No lyrics on LRCLIB for " + now.title
                            : (result.synced ? "Synced" : result.instrumental ? "Instrumental" : "Plain") + " lyrics found for " + now.title);
                    views.refresh();
                } });
            } });
        } }, 1500, TimeUnit.MILLISECONDS);
    }

    /** Seconds into the track that the speaker plays right now, with the lyrics timing nudge. */
    private double lyricsPosition() {
        NowPlaying n = np;
        double at;
        AirPlayServer s = server;
        long rtp = s == null ? Long.MIN_VALUE : s.playingRtp();
        if (rtp != Long.MIN_VALUE && n.hasProgress()) {
            at = Math.max(0, Math.min(((int) (rtp - n.startRtp())) / 44100.0, n.durationSeconds()));
        } else {
            at = n.elapsedSeconds(System.nanoTime());
        }
        return at + settings.lyricsOffset;
    }

    /** Play/pause or skip on the sender, over DACP. Runs on the network thread. */
    private void remoteControl(final String command) {
        final DacpRemote r = remote;
        if (r == null) return;
        net.execute(new Runnable() { @Override public void run() {
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

    /** The floating player's full-screen button: open the full-screen player over the dashboard. */
    private void openFullScreen() {
        fullRequested = true;
        fullClosed = false;
        refreshCard(false);
    }

    /** The full-screen player's minimise button, or back: return to the floating player. */
    private void closeFullScreen() {
        fullRequested = false;
        fullClosed = true;
        cardClosed = false;
        hideFullNow();
        refreshCard(false);
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
        post(new Runnable() { @Override public void run() { refreshShizuku(null); } });
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

    private boolean fullStyle() {
        return settings.playerStyle.toLowerCase(Locale.ROOT).startsWith("full");
    }

    /**
     * Show, keep or hide the player. "While playing" keeps it up while a session plays (or briefly pauses), "On
     * track change" for a few seconds after each new track. The style picks the floating card or the full-screen
     * player; the card's button opens full screen and the full-screen player's button goes back to the card.
     * Neither needs the screensaver: both are overlays over the dashboard.
     */
    private void refreshCard(boolean trackChanged) {
        views.refresh();
        if (host == null || isHeadless()) return;
        String mode = settings.nowPlaying.toLowerCase(Locale.ROOT);
        boolean live = sessionLive && np.hasContent();
        if (mode.startsWith("off") || !live) {
            hideCardNow();
            hideFullNow();
            return;
        }
        boolean timed = mode.startsWith("on");
        if (trackChanged || !timed) {
            cardClosed = false;
            if (trackChanged && timed) fullClosed = false;
        }
        if (timed && (trackChanged || !windowOpen && np.playing && hideCard == null && !cardShown && !fullShown)) {
            windowOpen = true;
            if (hideCard != null) hideCard.cancel(false);
            hideCard = worker.schedule(new Runnable() { @Override public void run() {
                hideCard = null;
                windowOpen = false;
                refreshCard(false);
            } }, 8, TimeUnit.SECONDS);
        }
        boolean window = !timed || windowOpen;
        boolean full = fullRequested || fullStyle() && !fullClosed && window;
        boolean card = !full && !cardClosed && window;
        if (full) {
            if (!fullShown) showFull();
        } else {
            hideFullNow();
        }
        if (card) {
            if (!cardShown) showCard();
        } else {
            hideCardNow();
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
        if (!cardShown) return;
        cardShown = false;
        if (host == null || isHeadless()) return;
        safe(new Runnable() { @Override public void run() { host.hideOverlay(OVERLAY); } });
    }

    private void showFull() {
        fullShown = true;
        final me.jxl.kiosk.plugins.OverlayFactory full = views.fullScreenPlayer(new NowPlayingViews.Tap() {
            @Override public void onTap() {
                post(new Runnable() { @Override public void run() { closeFullScreen(); } });
            }
        });
        safe(new Runnable() { @Override public void run() {
            host.showOverlay(FULL_OVERLAY, OverlaySpec.fullScreen().enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).touchable(true), full);
        } });
    }

    private void hideFullNow() {
        if (!fullShown) return;
        fullShown = false;
        if (host == null || isHeadless()) return;
        safe(new Runnable() { @Override public void run() { host.hideOverlay(FULL_OVERLAY); } });
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
