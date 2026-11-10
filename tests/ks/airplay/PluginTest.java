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

import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** The plugin's lifecycle against a fake Kiosk Satellite host: start, settings, commands, switch and stop. */
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
        @Override public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { validKey(key); calls.add("overlay:" + key); }
        @Override public void hideOverlay(String key) { validKey(key); calls.add("hide:" + key); }
        @Override public void fireTrigger(String id) { calls.add("trigger:" + id); }
        @Override public void publishScreensaverView(String key, String title, OverlayFactory factory) { validKey(key); calls.add("screensaver:" + key); }
        @Override public boolean isHeadless() { return false; }
        volatile Map<String, Object> shizuku;
        @Override public Map<String, Object> shizukuState() {
            if (shizuku == null) throw new UnsupportedOperationException("Shizuku is unavailable");
            return shizuku;
        }
        @Override public void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) {
            calls.add("shizuku-exec");
            callback.onResult(false, null, "not in tests");
        }
    }

    public static void main(String[] args) throws Exception {
        FakeHost host = new FakeHost();
        AirPlayPlugin plugin = new AirPlayPlugin(new FlowTest.FakePlatform());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("name", "");
        settings.put("port", String.valueOf(PORT));
        settings.put("deviceId", "");
        long t0 = System.nanoTime();
        plugin.start(host, settings);
        TestSupport.check(System.nanoTime() - t0 < 3_000_000_000L, "start returns within the callback deadline");
        String id = (String) host.saved.get("deviceId");
        TestSupport.check(AirPlayConfig.validDeviceId(id), "device ID generated and saved: " + id);
        TestSupport.check(host.calls.contains("subscribe:voice.state"), "subscribes to voice state");
        TestSupport.check(host.calls.contains("switch:receiver=true") && host.calls.contains("binary:playing=false"), "entities published");

        // KS calls configure with the saved device ID.
        settings.put("deviceId", id);
        plugin.configure(settings);
        Map<String, Object> info = waitForInfo("Echo Show 8");
        TestSupport.check(id.equals(info.get("deviceID")), "advertises the saved device ID");

        settings.put("name", "Kitchen");
        plugin.configure(settings);
        waitForInfo("Kitchen");

        plugin.onEvent("ks.voice.state", java.util.Collections.<String, Object>singletonMap("state", "listening"));
        plugin.onEvent("ks.voice.state", java.util.Collections.<String, Object>singletonMap("state", "idle"));
        plugin.execute("restart", new HashMap<String, Object>());
        waitForInfo("Kitchen");

        // A sender plays: the full-screen view becomes the screensaver and the floating player appears.
        TestSupport.check(host.calls.contains("screensaver:" + AirPlayPlugin.SCREENSAVER_KEY), "full-screen view published as a screensaver");
        plugin.onSessionStarted("Ian's iPhone");
        Map<String, String> track = new HashMap<>();
        track.put("title", "Song");
        track.put("artist", "Band");
        plugin.onMetadata(track);
        plugin.onAudioActive(true);
        waitForCall(host, "command:setScreensaverMode");
        waitForCall(host, "overlay:" + AirPlayPlugin.OVERLAY);
        waitForCall(host, "text:track=Song");
        waitForCall(host, "trigger:playbackStarted");

        TestSupport.check(host.calls.contains("text:shizuku=Not running"), "Shizuku reading published");
        // Full-screen style: the full-screen player opens over the dashboard, without the screensaver.
        host.calls.clear();
        settings.put("nowPlaying", "While playing");
        settings.put("playerStyle", "Full screen");
        plugin.configure(settings);
        waitForCall(host, "overlay:" + AirPlayPlugin.FULL_OVERLAY);
        TestSupport.check(!host.calls.contains("command:startScreensaver"), "full screen needs no screensaver");
        // Back (or its minimise button) shrinks it to the floating card.
        host.calls.clear();
        plugin.onEvent("overlay.closed", java.util.Collections.<String, Object>singletonMap("key", AirPlayPlugin.FULL_OVERLAY));
        waitForCall(host, "overlay:" + AirPlayPlugin.OVERLAY);
        TestSupport.check(!host.calls.contains("overlay:" + AirPlayPlugin.FULL_OVERLAY), "stays minimised");
        // Floating style with the card closed by back: it stays closed until the next track.
        host.calls.clear();
        settings.put("playerStyle", "Floating");
        plugin.configure(settings);
        waitForCall(host, "overlay:" + AirPlayPlugin.OVERLAY);
        TestSupport.check(!host.calls.contains("overlay:" + AirPlayPlugin.FULL_OVERLAY), "floating style shows the card");
        plugin.onEvent("overlay.closed", java.util.Collections.<String, Object>singletonMap("key", AirPlayPlugin.OVERLAY));
        host.calls.clear();
        track.put("title", "Next song");
        plugin.onMetadata(track);
        waitForCall(host, "overlay:" + AirPlayPlugin.OVERLAY);

        plugin.onSessionEnded();
        waitForCall(host, "command:clearScreensaverMode");
        waitForCall(host, "hide:" + AirPlayPlugin.OVERLAY);

        plugin.onEvent("switch.receiver", java.util.Collections.<String, Object>singletonMap("on", false));
        waitClosed();
        waitForCall(host, "switch:receiver=false");
        plugin.onEvent("switch.receiver", java.util.Collections.<String, Object>singletonMap("on", true));
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
        // Boot start on and Shizuku root: the plugin makes its ADB key, keeps it in its secret setting and trusts it.
        waitFor(() -> String.valueOf(host.saved.get("adbKey")).startsWith("v1:"), "ADB key saved in settings");
        waitForCall(host, "shizuku-exec");
        waitFor(() -> Boolean.TRUE.equals(host.saved.get("shizukuRunning")), "Started button shown");
        settings.put("shizukuRunning", false); // someone flips the flag by hand
        host.saved.put("shizukuRunning", false);
        plugin.configure(settings);
        waitFor(() -> Boolean.TRUE.equals(host.saved.get("shizukuRunning")), "flag put back");
        host.shizuku = null;
        host.saved.put("shizukuRunning", true);
        plugin.onEvent("shizuku.state", new HashMap<String, Object>());
        waitFor(() -> Boolean.FALSE.equals(host.saved.get("shizukuRunning")), "Start button back");

        t0 = System.nanoTime();
        plugin.stop();
        TestSupport.check(System.nanoTime() - t0 < 3_000_000_000L, "stop returns within the callback deadline");
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
