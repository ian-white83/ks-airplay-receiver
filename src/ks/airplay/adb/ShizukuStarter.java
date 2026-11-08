// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.adb;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Locale;

/**
 * Starts Shizuku as root without a computer: connect to this device's own adbd on 127.0.0.1, switch it to root
 * (LineageOS "Rooted debugging") and run Shizuku's starter, as "adb root" and "adb shell" would from a computer.
 */
public final class ShizukuStarter {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;
    static final String PACKAGE = "moe.shizuku.privileged.api";

    /** Finds Shizuku's starter: libshizuku.so in recent versions, start.sh in older ones. */
    static final String AUTO_START =
            "p=$(pm path " + PACKAGE + " 2>/dev/null | head -n 1); p=${p#package:}\n"
            + "[ -n \"$p\" ] || { echo 'Shizuku is not installed'; exit 1; }\n"
            + "for f in \"${p%/*}\"/lib/*/libshizuku.so; do [ -f \"$f\" ] && exec \"$f\"; done\n"
            + "s=/storage/emulated/0/Android/data/" + PACKAGE + "/start.sh\n"
            + "[ -f \"$s\" ] && exec sh \"$s\"\n"
            + "echo \"Could not find Shizuku's starter. Copy the command from Shizuku > Start via connected computer into the plugin's Start command setting.\"; exit 1";

    public interface Progress {
        /** Report a step. Returns false to stop. */
        boolean report(String message);
    }

    public static final class Stopped extends IOException {
        Stopped() { super("Stopped"); }
    }

    private final AdbKey key;
    private final String host;
    private final int port;

    public ShizukuStarter(AdbKey key) {
        this(key, HOST, PORT);
    }

    ShizukuStarter(AdbKey key, String host, int port) {
        this.key = key;
        this.host = host;
        this.port = port;
    }

    /** Keeps adbd on port 5555 across reboots, then runs the starter. Shell syntax, run as root. */
    static String script(String command) {
        String c = command == null ? "" : command.trim();
        if (c.toLowerCase(Locale.ROOT).startsWith("adb shell ")) c = c.substring("adb shell ".length()).trim();
        return "[ \"$(getprop persist.adb.tcp.port)\" = " + PORT + " ] || setprop persist.adb.tcp.port " + PORT + "\n"
                + (c.isEmpty() ? AUTO_START : c);
    }

    /**
     * Connect as root and, unless skipStart, run the starter. Returns what the starter printed.
     *
     * @param connectMs how long to keep trying while adbd is not listening yet, as early in boot
     * @param approvalMs how long to wait for someone to approve this plugin's key on the screen
     */
    public String run(String command, boolean skipStart, int connectMs, int approvalMs, final Progress progress) throws IOException {
        step(progress, "Connecting to ADB on port " + port);
        AdbClient c = connectRetry(connectMs, approvalMs, progress);
        try {
            String uid = c.shell("id -u", 10000).trim();
            if (!"0".equals(uid)) {
                step(progress, "Switching ADB to root");
                String reply = c.root().trim();
                c.close();
                String r = reply.toLowerCase(Locale.ROOT);
                if (r.contains("cannot") || r.contains("disabled") || r.contains("not allowed") || r.contains("denied")) {
                    throw new IOException("ADB cannot switch to root (\"" + reply + "\"). Turn on Rooted debugging in Developer options.");
                }
                sleep(1500, progress);
                c = connectRetry(20000, approvalMs, progress);
                uid = c.shell("id -u", 10000).trim();
                if (!"0".equals(uid)) throw new IOException("ADB is still not root (uid " + uid + "). Turn on Rooted debugging in Developer options.");
            }
            if (skipStart) {
                c.shell("[ \"$(getprop persist.adb.tcp.port)\" = " + PORT + " ] || setprop persist.adb.tcp.port " + PORT, 10000);
                return "";
            }
            step(progress, "Starting Shizuku");
            return c.shell(script(command), 30000).trim();
        } finally {
            c.close();
        }
    }

    private AdbClient connectRetry(int connectMs, int approvalMs, final Progress progress) throws IOException {
        long deadline = System.currentTimeMillis() + connectMs;
        AdbClient.Approval approval = new AdbClient.Approval() {
            @Override public void waiting() {
                progress.report("Tap Always allow, then Allow, in the \"Allow USB debugging?\" prompt on the kiosk screen");
            }
        };
        while (true) {
            try {
                return AdbClient.connect(host, port, key, approvalMs, approval);
            } catch (AdbClient.NotApproved e) {
                throw e;
            } catch (IOException e) {
                if (System.currentTimeMillis() >= deadline) {
                    if (e instanceof ConnectException) {
                        throw new IOException("Nothing answers on " + host + ":" + port + ". ADB over network is off: run adb tcpip " + port + " once from a computer, or turn on ADB over network in Developer options.");
                    }
                    throw e;
                }
                sleep(2000, progress);
            }
        }
    }

    private static void step(Progress p, String message) throws Stopped {
        if (!p.report(message)) throw new Stopped();
    }

    private static void sleep(long ms, Progress p) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Stopped();
        }
        step(p, null);
    }
}
