// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.android;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Advertises the receiver over Bonjour with Android's NsdManager: an _airplay._tcp service for AirPlay 2 senders
 * and a _raop._tcp service for the audio endpoint, both with the TXT records iOS reads.
 */
public final class Bonjour {
    public interface Callback {
        void onRegistered(String type, String name);
        void onFailed(String type, int error);
    }

    private final NsdManager nsd;
    private final List<NsdManager.RegistrationListener> listeners = new ArrayList<>();

    private Bonjour(NsdManager nsd) {
        this.nsd = nsd;
    }

    /**
     * Plugins get no Context from the host, so this asks the framework for the running application. That is a
     * long-standing public-in-practice framework method, not a Kiosk Satellite internal.
     */
    public static Bonjour create() throws Exception {
        Context context = applicationContext();
        if (context == null) throw new IllegalStateException("No application context");
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) throw new IllegalStateException("Network service discovery is unavailable");
        return new Bonjour(nsd);
    }

    static Context applicationContext() throws Exception {
        Exception failure = null;
        String[][] candidates = {{"android.app.ActivityThread", "currentApplication"}, {"android.app.AppGlobals", "getInitialApplication"}};
        for (String[] candidate : candidates) {
            try {
                Object app = Class.forName(candidate[0]).getMethod(candidate[1]).invoke(null);
                if (app instanceof Context) return (Context) app;
            } catch (Exception e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        return null;
    }

    public synchronized void register(final String type, String name, int port, Map<String, String> txt, final Callback callback) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) info.setAttribute(e.getKey(), e.getValue());
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { callback.onFailed(type, errorCode); }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { callback.onRegistered(type, serviceInfo.getServiceName()); }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
        };
        listeners.add(listener);
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    public synchronized void unregisterAll() {
        for (NsdManager.RegistrationListener listener : listeners) {
            try {
                nsd.unregisterService(listener);
            } catch (RuntimeException ignored) {
                // Not registered (yet) or already gone.
            }
        }
        listeners.clear();
    }
}
