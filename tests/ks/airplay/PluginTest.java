// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import me.jxl.kiosk.plugins.AudioOutput;
import me.jxl.kiosk.plugins.PlayerListener;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.PluginPlayer;

/** The plugin's lifecycle against a fake Kiosk Satellite host: start, the pick, settings, commands and stop. */
public final class PluginTest {
    static final int PORT = 47000 + (int) (Math.random() * 1000);

    static final class FakeHost implements PluginHost {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Object> saved = new HashMap<>();
        volatile String lastStatus = "";

        @Override public void showWindow(String title, String message, String buttonLabel) { calls.add("window"); }
        @Override public void hideWindow() {}
        @Override public void log(String message) { calls.add("log:" + message); }
        @Override public void status(String message, boolean error) { lastStatus = message; calls.add("status:" + message); }
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            calls.add("command:" + command);
            if (command.equals("getDeviceInfo")) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", "Echo Show 8");
                callback.onResult(true, info, null);
            } else {
                callback.onResult(true, null, null);
            }
        }
        @Override public void subscribe(String event) { calls.add("subscribe:" + event); }
        @Override public void updateSettings(Map<String, Object> values) { saved.putAll(values); calls.add("updateSettings"); }
        final List<String> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public boolean setupResult(String run, String state, String message) { results.add(run + ":" + state + ":" + message); return true; }
        @Override public InputStream openAsset(String name) throws java.io.IOException {
            return new FileInputStream(TestSupport.root().resolve("assets").resolve(name).toFile());
        }
        @Override public void publishStatusTile(String key, String title, String level, String text) { calls.add("tile:" + level + ":" + text); }
        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) {
            TestSupport.check(metadata.keySet().stream().allMatch(k -> k.equals("unit") || k.equals("deviceClass") || k.equals("stateClass") || k.equals("accuracyDecimals")), "sensor metadata fields");
            calls.add("sensor:" + key);
        }
        @Override public void publishTextSensor(String key, String name, String state) { calls.add("text:" + key + "=" + state); }
        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { calls.add("binary:" + key + "=" + state); }
        @Override public void publishSwitch(String key, String name, boolean state) { calls.add("switch:" + key + "=" + state); }
        // Kiosk Satellite's rule for overlay and screensaver keys.
        static void validKey(String key) {
            if (!key.matches("[a-z][a-z0-9_]{0,39}")) throw new IllegalArgumentException("Invalid key " + key);
        }
        @Override public void fireTrigger(String id) { calls.add("trigger:" + id); }
        volatile PlayerListener player;
        @Override public void publishPlayer(String key, String title, PlayerListener listener) {
            validKey(key);
            TestSupport.check(title.length() <= 80, "player title length");
            player = listener;
            calls.add("player:" + key);
        }
        @Override public void removePlayer(String key) { calls.add("removePlayer:" + key); }
        @Override public boolean isHeadless() { return false; }
        volatile Map<String, Object> shizuku;
        @Override public Map<String, Object> shizukuState() {
            if (shizuku == null) throw new UnsupportedOperationException("Shizuku is unavailable");
            return shizuku;
        }
        final List<String> scripts = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) {
            calls.add("shizuku-exec");
            scripts.add(command[command.length - 1]);
            callback.onResult(false, null, "not in tests");
        }
    }

    /** Kiosk Satellite's side of the pick: what the plugin shows and the output it plays to. */
    static final class FakePlayer implements PluginPlayer {
        final List<Map<String, Object>> states = new CopyOnWriteArrayList<>();
        final OutputTest.FakeOutput output = new OutputTest.FakeOutput();
        volatile boolean active = true;
        @Override public void update(Map<String, Object> state) { states.add(new HashMap<>(state)); }
        @Override public void queueChanged() {}
        @Override public boolean isActive() { return active; }
        @Override public AudioOutput openAudio(int sampleRate, int channels, int encoding) {
            TestSupport.check(active, "audio opened only while picked");
            TestSupport.check(encoding == ENCODING_PCM_16 && channels == 2 && sampleRate == 44100, "16-bit stereo 44.1 kHz");
            return output;
        }
        Map<String, Object> last() { return states.isEmpty() ? null : states.get(states.size() - 1); }
    }

    static void waitForState(FakePlayer p, String state, String title) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> m = p.last();
            if (m != null && state.equals(m.get("state")) && (title == null || title.equals(m.get("title")))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Player state never became " + state + " " + title + ": " + p.last());
    }

    public static void main(String[] args) throws Exception {
        FakeHost host = new FakeHost();
        FlowTest.FakePlatform platform = new FlowTest.FakePlatform();
        platform.dir = java.nio.file.Files.createTempDirectory("airplay-data").toFile();
        AirPlayPlugin plugin = new AirPlayPlugin(platform);
        Map<String, Object> settings = new LinkedHashMap<>();
        // Upgrading from 0.6.2 to 0.7.0, which kept the ADB key in a secret setting.
        ks.airplay.adb.AdbKey oldKey = ks.airplay.adb.AdbKey.generate(AirPlayPlugin.KEY_NAME);
        settings.put("adbKey", oldKey.toSecret());
        settings.put("name", "");
        settings.put("port", String.valueOf(PORT));
        settings.put("deviceId", "");
        long t0 = System.nanoTime();
        plugin.start(host, settings);
        TestSupport.check(System.nanoTime() - t0 < 3_000_000_000L, "start returns within the callback deadline");
        String id = (String) host.saved.get("deviceId");
        TestSupport.check(AirPlayConfig.validDeviceId(id), "device ID generated and saved: " + id);
        TestSupport.check(host.calls.contains("player:" + AirPlayPlugin.PLAYER_KEY) && host.player != null, "publishes its player");
        // The old key moves to private storage, unchanged so Android needs no new approval, and the setting clears.
        waitFor(() -> "".equals(host.saved.get("adbKey")), "old ADB key setting cleared");
        java.io.File keyFile = new java.io.File(platform.dir, AirPlayPlugin.KEY_FILE);
        TestSupport.check(ks.airplay.adb.AdbKey.fromSecret(new String(java.nio.file.Files.readAllBytes(keyFile.toPath()), "UTF-8"), "x").publicKey().split(" ")[0]
                .equals(oldKey.publicKey().split(" ")[0]), "same key in private storage");
        settings.put("adbKey", "");
        settings.put("deviceId", id);
        plugin.configure(settings);
        Thread.sleep(300);
        waitClosed(); // nothing serves before the pick
        TestSupport.check(host.lastStatus.equals(AirPlayPlugin.NOT_PICKED), "says to pick it: " + host.lastStatus);

        // Picked under Media Player > Player: the receiver starts.
        FakePlayer picked = new FakePlayer();
        host.player.onActivate(picked);
        Map<String, Object> info = waitForInfo("Echo Show 8");
        TestSupport.check(id.equals(info.get("deviceID")), "advertises the saved device ID");
        waitForState(picked, "idle", null);
        TestSupport.check(((List<?>) picked.last().get("actions")).isEmpty(), "no buttons without a remote");

        settings.put("name", "Kitchen");
        plugin.configure(settings);
        waitForInfo("Kitchen");

        // The nudge buttons move the audio offset 0.01 s at a time, saved and applied at once.
        for (String b : new String[] {"offsetLater", "offsetLater", "offsetLater", "offsetEarlier"}) {
            Map<String, Object> press = new HashMap<>();
            press.put("id", b);
            press.put("run", "run-" + b + host.results.size());
            plugin.onEvent("settings.button", press);
        }
        waitFor(() -> host.results.size() == 4, "four nudges answered");
        TestSupport.check(Double.valueOf(0.02).equals(host.saved.get("audioOffset")), "offset nudged to 0.02: " + host.saved.get("audioOffset"));
        TestSupport.check(host.results.get(3).endsWith("success:Audio offset +0.02 s"), "says the new offset: " + host.results.get(3));
        settings.put("audioOffset", 0.02);
        plugin.configure(settings);
        plugin.execute("restart", new HashMap<String, Object>());
        waitForInfo("Kitchen");

        // A sender plays: Kiosk Satellite hears what plays.
        plugin.onSessionStarted("Ian's iPhone");
        Map<String, String> track = new HashMap<>();
        track.put("title", "Song");
        track.put("artist", "Band");
        plugin.onMetadata(track);
        plugin.onAudioActive(true);
        waitForState(picked, "playing", "Song");
        TestSupport.check("Band".equals(picked.last().get("artist")), "artist sent");
        waitForCall(host, "trigger:playbackStarted");
        plugin.onArtwork(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2}, "image/jpeg");
        plugin.onProgress(1000, 1000 + 44100 * 10, 1000 + 44100 * 200);
        long until = System.currentTimeMillis() + 5000;
        while ((picked.last() == null || !picked.last().containsKey("durationMs")) && System.currentTimeMillis() < until) Thread.sleep(20);
        TestSupport.check(Long.valueOf(200_000L).equals(picked.last().get("durationMs")) && picked.last().get("artwork") instanceof byte[], "duration and cover art sent");
        plugin.onRemote(new DacpRemote("ABCDEF", "123"), java.net.InetAddress.getLoopbackAddress());
        until = System.currentTimeMillis() + 5000;
        while (((List<?>) picked.last().get("actions")).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        TestSupport.check(((List<?>) picked.last().get("actions")).contains("next"), "buttons once the sender offers a remote");
        plugin.onAudioActive(false);
        waitForState(picked, "paused", "Song");
        plugin.onSessionEnded();
        waitForState(picked, "idle", null);
        TestSupport.check(host.calls.contains("text:shizuku=Not running"), "Shizuku reading published");

        // Another player picked: the receiver stops; picked again: it comes back.
        host.player.onDeactivate();
        waitClosed();
        until = System.currentTimeMillis() + 5000;
        while (!host.lastStatus.equals(AirPlayPlugin.NOT_PICKED) && System.currentTimeMillis() < until) Thread.sleep(20);
        TestSupport.check(host.lastStatus.equals(AirPlayPlugin.NOT_PICKED), "says it is not the player");
        picked = new FakePlayer();
        host.player.onActivate(picked);
        waitForInfo("Kitchen");

        // Shizuku comes up as root: the button turns into Started, and back to Start when it goes away.
        Map<String, Object> root = new HashMap<>();
        root.put("available", true);
        root.put("granted", true);
        root.put("uid", 0);
        settings.put("shizukuAtBoot", true);
        plugin.configure(settings);
        host.shizuku = root;
        plugin.onEvent("shizuku.state", root);
        waitForCall(host, "text:shizuku=Running as root");
        // Turning boot start on changes nothing on the device: no trusted keys, no ADB port.
        Thread.sleep(300);
        TestSupport.check(host.scripts.stream().noneMatch(s -> s.contains("adb_keys") || s.contains("setprop")), "boot start on writes nothing as root");
        waitFor(() -> Boolean.TRUE.equals(host.saved.get("shizukuRunning")), "Started button shown");
        settings.put("shizukuRunning", false); // someone flips the flag by hand
        host.saved.put("shizukuRunning", false);
        plugin.configure(settings);
        waitFor(() -> Boolean.TRUE.equals(host.saved.get("shizukuRunning")), "flag put back");
        host.shizuku = null;
        host.saved.put("shizukuRunning", true);
        plugin.onEvent("shizuku.state", new HashMap<String, Object>());
        waitFor(() -> Boolean.FALSE.equals(host.saved.get("shizukuRunning")), "Start button back");

        // Turning boot start off with Shizuku as root: the plugin's key leaves Android's trusted keys and is deleted.
        host.shizuku = root;
        settings.put("shizukuAtBoot", false);
        plugin.configure(settings);
        waitFor(() -> host.scripts.stream().anyMatch(s -> s.contains("adb_keys") && s.contains("grep -v ' " + AirPlayPlugin.KEY_NAME + "$'")), "key removed from trusted keys");
        waitFor(() -> !keyFile.exists(), "key file deleted");
        TestSupport.check(host.scripts.stream().noneMatch(s -> s.contains("setprop")), "never sets ADB properties");
        host.shizuku = null;

        t0 = System.nanoTime();
        plugin.stop();
        TestSupport.check(System.nanoTime() - t0 < 3_000_000_000L, "stop returns within the callback deadline");
        waitClosed();

        // Fleet Management copies plugin settings, the old random device ID included. With a device ID from
        // Android the plugin ignores the copied one, so every kiosk advertises its own.
        String a1 = AirPlayConfig.deviceIdFrom("android-id-A"), b1 = AirPlayConfig.deviceIdFrom("android-id-B");
        TestSupport.check(a1.equals(AirPlayConfig.deviceIdFrom("android-id-A")) && !a1.equals(b1), "stable and different per device");
        TestSupport.check(AirPlayConfig.validDeviceId(a1) && (Integer.parseInt(a1.substring(0, 2), 16) & 0x03) == 0x02, "locally administered unicast");
        FlowTest.FakePlatform seeded = new FlowTest.FakePlatform();
        seeded.seed = "android-id-A";
        FakeHost host2 = new FakeHost();
        AirPlayPlugin plugin2 = new AirPlayPlugin(seeded);
        Map<String, Object> copied = new LinkedHashMap<>(settings);
        copied.put("name", "Study");
        copied.put("deviceId", id); // the leader's, copied by the fleet
        plugin2.start(host2, copied);
        host2.player.onActivate(new FakePlayer());
        Map<String, Object> info2 = waitForInfo("Study");
        TestSupport.check(a1.equals(info2.get("deviceID")), "advertises its own device ID, not the copied one: " + info2.get("deviceID"));
        TestSupport.check(!host2.saved.containsKey("deviceId"), "saves no device ID setting");
        copied.put("deviceIdOverride", "02:AA:BB:CC:DD:EE");
        plugin2.configure(copied);
        until = System.currentTimeMillis() + 8000;
        while (!"02:AA:BB:CC:DD:EE".equals(waitForInfo("Study").get("deviceID")) && System.currentTimeMillis() < until) Thread.sleep(100);
        TestSupport.check("02:AA:BB:CC:DD:EE".equals(waitForInfo("Study").get("deviceID")), "override wins");
        plugin2.stop();
        waitClosed();
        System.out.println("PluginTest passed");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> waitForInfo(String name) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        Object last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                FlowTest.Client client = new FlowTest.Client(PORT);
                Map<String, Object> info = (Map<String, Object>) BPlist.parse(client.ok("GET", "/info", null, null).body);
                client.socket.close();
                last = info.get("name");
                if (name.equals(last)) return info;
            } catch (java.io.IOException | AssertionError e) {
                // restarting: refused, or accepted just before the old server closed
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Receiver never answered as " + name + " (last " + last + ")");
    }

    static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        TestSupport.check(condition.getAsBoolean(), what);
    }

    static void waitForCall(FakeHost host, String call) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!host.calls.contains(call) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        TestSupport.check(host.calls.contains(call), "host saw " + call);
    }

    static void waitClosed() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", PORT)) {
                Thread.sleep(100);
            } catch (java.io.IOException e) {
                return; // refused, or reset while the listener closed
            }
        }
        throw new AssertionError("Receiver still listening");
    }
}
