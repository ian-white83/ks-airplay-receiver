// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

/** Runs a receiver for tools/interop_pyatv.py: prints its port, then serves until killed. */
public final class InteropServer {
    public static void main(String[] args) throws Exception {
        AirPlayConfig config = new AirPlayConfig("Interop Kiosk", "02:AB:CD:EF:01:23", 0, AirPlayConfig.DEFAULT_FEATURES);
        AirPlayServer server = new AirPlayServer(config, new FlowTest.FakePlatform(), new FlowTest.Events());
        System.out.println("PORT " + server.start());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
