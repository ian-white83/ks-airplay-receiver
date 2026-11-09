// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ks.airplay.fairplay.FairPlay;
import ks.airplay.hap.HapChannel;
import ks.airplay.hap.HapPairing;

/**
 * One sender's AirPlay 2 control connection: /info, HomeKit transient pairing, then (encrypted) the PTP session
 * SETUP, the buffered audio stream SETUP, SETRATEANCHORTIME, FLUSHBUFFERED, volume and metadata.
 */
final class RtspConnection implements Runnable {
    private final AirPlayServer server;
    private final Socket socket;
    private final AirPlayConfig config;
    private final Platform platform;
    private final HapPairing pairing;
    private final FairPlay fairPlay = new FairPlay();

    private InputStream in;
    private OutputStream out;
    private InputStream rawIn;
    private OutputStream rawOut;
    private byte[] pendingSecret;

    private volatile BufferedSession session;
    private ServerSocket eventServer;
    private Thread eventThread;
    private String clientName = "AirPlay sender";
    private String groupId;
    private boolean groupContainsLeader;
    private volatile boolean closed;

    RtspConnection(AirPlayServer server, Socket socket, AirPlayConfig config, Platform platform) {
        this.server = server;
        this.socket = socket;
        this.config = config;
        this.platform = platform;
        this.pairing = new HapPairing(config.deviceIdString(), config.seed(), config.publicKey);
    }

    String clientName() { return clientName; }

    private String dacpId, activeRemote;

    /** Senders name their DACP remote on every request; pass it on when it first appears or changes. */
    private void remoteHeaders(RtspMessage req) {
        if (!loggedHeaders && "SETUP".equals(req.method)) {
            // Diagnostics: which headers the sender uses (names only), to see whether it offers a remote.
            loggedHeaders = true;
            platform.log("SETUP from " + socket.getInetAddress().getHostAddress() + " with headers " + req.headerNames());
        }
        String id = req.header("DACP-ID"), remote = req.header("Active-Remote");
        if (id == null || remote == null || id.isEmpty() || remote.isEmpty()) return;
        if (id.equals(dacpId) && remote.equals(activeRemote)) return;
        dacpId = id;
        activeRemote = remote;
        // From the audio connection or the sender's separate remote-control connection alike.
        server.listener().onRemote(new DacpRemote(id, remote), socket.getInetAddress());
    }

    private boolean loggedHeaders;

    /** Called when this connection's buffered session starts, so the plugin learns its remote. */
    void announceRemote() {
        if (dacpId != null) server.listener().onRemote(new DacpRemote(dacpId, activeRemote), socket.getInetAddress());
    }
    BufferedSession session() { return session; }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            rawIn = new BufferedInputStream(socket.getInputStream());
            rawOut = new BufferedOutputStream(socket.getOutputStream());
            in = rawIn;
            out = rawOut;
            while (!closed) {
                RtspMessage request = RtspMessage.read(in);
                if (request == null) break;
                RtspMessage.Response response = new RtspMessage.Response();
                remoteHeaders(request);
                try {
                    handle(request, response);
                } catch (Exception e) {
                    platform.log(request.method + " " + request.uri + " failed: " + e);
                    response.status(500, "Internal Server Error");
                }
                response.write(out, request.protocol, request.header("CSeq"));
                if (pendingSecret != null) {
                    // Pairing finished with that response: everything after it is encrypted.
                    byte[][] keys = HapPairing.controlKeys(pendingSecret);
                    pendingSecret = null;
                    in = new BufferedInputStream(new HapChannel.Reader(rawIn, keys[0]));
                    out = new HapChannel.Writer(rawOut, keys[1]);
                }
                if (response.close) break;
            }
        } catch (SocketException e) {
            // closed by us or the sender
        } catch (IOException e) {
            if (!closed) platform.log("AirPlay connection error: " + e.getMessage());
        } finally {
            close();
            server.connectionEnded(this);
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        stopSession();
        closeEvents();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void stopSession() {
        BufferedSession s = session;
        session = null;
        if (s != null) s.close();
    }

    private void handle(RtspMessage req, RtspMessage.Response res) throws Exception {
        String method = req.method.toUpperCase(Locale.ROOT);
        String path = req.uri;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);

        if (method.equals("GET") && path.equals("/info")) { info(req, res); return; }
        if (method.equals("POST")) {
            if (path.equals("/pair-pin-start")) return;
            if (path.equals("/pair-setup")) {
                res.body("application/octet-stream", pairing.setup(req.body));
                byte[] secret = pairing.sharedSecret();
                if (secret != null && in == rawIn) pendingSecret = secret;
                return;
            }
            if (path.equals("/pair-verify")) {
                res.body("application/octet-stream", pairing.verify(req.body));
                return;
            }
            if (path.equals("/fp-setup")) {
                byte[] reply = fairPlay.setup(req.body);
                if (reply == null) res.status(501, "Not Implemented");
                else res.body("application/octet-stream", reply);
                return;
            }
            if (path.equals("/feedback") || path.equals("/audioMode") || path.equals("/command")
                    || path.equals("/pair-add") || path.equals("/pair-remove") || path.equals("/configure")) {
                return;
            }
            res.status(404, "Not Found");
            return;
        }
        if (method.equals("OPTIONS")) {
            res.header("Public", "SETUP, RECORD, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, FLUSHBUFFERED, SETRATEANCHORTIME, SETPEERS, SETPEERSX, POST, GET");
            return;
        }
        if (method.equals("SETUP")) { setup(req, res); return; }
        if (method.equals("RECORD")) return;
        if (method.equals("SETRATEANCHORTIME")) { anchor(req); return; }
        if (method.equals("FLUSHBUFFERED")) { flushBuffered(req); return; }
        if (method.equals("SETPEERS") || method.equals("SETPEERSX")) { peers(req); return; }
        if (method.equals("TEARDOWN")) { teardown(req, res); return; }
        if (method.equals("GET_PARAMETER")) { getParameter(req, res); return; }
        if (method.equals("SET_PARAMETER")) { setParameter(req); return; }
        res.status(501, "Not Implemented"); // AirPlay 1 (ANNOUNCE, FLUSH, ...) is left to Kiosk Satellite's own receiver
    }

    // ---------------------------------------------------------------- requests

    @SuppressWarnings("unchecked")
    private Map<String, Object> plist(RtspMessage req) {
        if (req.body.length == 0) return Collections.emptyMap();
        try {
            Object o = BPlist.parse(req.body);
            return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
        } catch (IOException e) {
            platform.log("Unreadable plist in " + req.method + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private void info(RtspMessage req, RtspMessage.Response res) {
        String qualifier = null;
        Object q = plist(req).get("qualifier");
        if (q instanceof List && !((List<Object>) q).isEmpty()) qualifier = String.valueOf(((List<Object>) q).get(0));
        String sender = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        res.body("application/x-apple-binary-plist", BPlist.write(config.info(qualifier, sender, server.volumeDb(), groupId, groupContainsLeader)));
    }

    @SuppressWarnings("unchecked")
    private void setup(RtspMessage req, RtspMessage.Response res) throws Exception {
        Map<String, Object> root = plist(req);
        Map<String, Object> reply = new LinkedHashMap<>();
        String name = BPlist.getString(root, "name");
        if (name != null && !name.isEmpty()) clientName = name;
        Object streams = root.get("streams");
        if (!(streams instanceof List)) {
            String timing = BPlist.getString(root, "timingProtocol");
            if ("PTP".equals(timing)) {
                String gid = BPlist.getString(root, "groupUUID");
                if (gid != null) groupId = gid;
                groupContainsLeader = BPlist.getBool(root, "groupContainsGroupLeader", false);
                reply.put("eventPort", (long) openEvents());
                reply.put("timingPort", 0L);
                if (root.containsKey("timingPeerInfo")) {
                    Map<String, Object> peer = new LinkedHashMap<>();
                    peer.put("Addresses", localAddresses());
                    peer.put("ID", socket.getLocalAddress().getHostAddress());
                    reply.put("timingPeerInfo", peer);
                }
                server.sessionStarted(this);
            } else if (BPlist.getBool(root, "isRemoteControlOnly", false)) {
                reply.put("eventPort", (long) openEvents());
            } else {
                platform.log("Refused a session with timing protocol " + timing + ": only PTP (AirPlay 2 buffered audio) is supported");
                res.status(501, "Not Implemented");
                return;
            }
            res.body("application/x-apple-binary-plist", BPlist.write(reply));
            return;
        }
        List<Object> out = new ArrayList<>();
        for (Object o : (List<Object>) streams) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> stream = (Map<String, Object>) o;
            long type = BPlist.getLong(stream, "type", 0);
            if (type == 103) {
                byte[] shk = BPlist.getData(stream, "shk");
                int ct = (int) BPlist.getLong(stream, "ct", 4);
                int spf = (int) BPlist.getLong(stream, "spf", ct == 2 ? 352 : 1024);
                if (shk == null || shk.length != 32) {
                    platform.log("Buffered stream without a 32-byte session key");
                    res.status(400, "Bad Request");
                    return;
                }
                if (ct != 2 && ct != 4) {
                    platform.log("Unsupported buffered audio compression type " + ct);
                    res.status(501, "Not Implemented");
                    return;
                }
                stopSession();
                BufferedSession s = new BufferedSession(platform, server.audioEvents(this), server.clock(), shk, ct, spf);
                s.setVolume(server.volumeDb());
                s.setOffset(server.offsetNanos());
                session = s;
                server.streamStarted(this, ct);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("type", 103L);
                r.put("dataPort", (long) s.dataPort());
                r.put("audioBufferSize", 8L * 1024 * 1024);
                r.put("controlPort", 0L);
                out.add(r);
            } else if (type == 130) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("streamID", 1L);
                r.put("type", 130L);
                out.add(r);
            } else {
                // 96 realtime audio and 110 mirroring are Kiosk Satellite's own receiver's job.
                platform.log("Refused stream type " + type + ": this receiver plays AirPlay 2 buffered audio only");
                res.status(501, "Not Implemented");
                return;
            }
        }
        reply.put("streams", out);
        res.body("application/x-apple-binary-plist", BPlist.write(reply));
    }

    private void anchor(RtspMessage req) {
        Map<String, Object> p = plist(req);
        BufferedSession s = session;
        if (s == null) return;
        if (p.containsKey("networkTimeSecs")) {
            long secs = BPlist.getLong(p, "networkTimeSecs", 0);
            long frac = BPlist.getLong(p, "networkTimeFrac", 0);
            long nanos = secs * BufferedSession.NANOS + (((frac >>> 32) * BufferedSession.NANOS) >>> 32);
            s.setAnchor(BPlist.getLong(p, "networkTimeTimelineID", 0), BPlist.getLong(p, "rtpTime", 0), nanos);
        }
        if (p.containsKey("rate")) s.setRate((int) BPlist.getLong(p, "rate", 0));
    }

    /**
     * The group's members. SETPEERS lists address strings, SETPEERSX dictionaries with an Addresses list. The
     * clock says hello to each, as nqptp does, since some (HomePods) only send timing to peers that ask.
     */
    private void peers(RtspMessage req) {
        java.util.List<String> addresses = new java.util.ArrayList<>();
        try {
            if (req.body.length > 0) collectAddresses(BPlist.parse(req.body), addresses);
        } catch (Exception e) {
            platform.log("Could not read " + req.method + ": " + e.getMessage());
            return;
        }
        platform.log(req.method + ": " + addresses);
        BufferedSession.Clock c = server.clock();
        if (c != null) c.peers(addresses);
    }

    private static void collectAddresses(Object o, java.util.List<String> out) {
        if (o instanceof String) {
            if (!out.contains(o)) out.add((String) o);
        } else if (o instanceof List) {
            for (Object x : (List<?>) o) collectAddresses(x, out);
        } else if (o instanceof Map) {
            collectAddresses(((Map<?, ?>) o).get("Addresses"), out);
        }
    }

    private void flushBuffered(RtspMessage req) {
        Map<String, Object> p = plist(req);
        BufferedSession s = session;
        if (s == null) return;
        int from = p.containsKey("flushFromSeq") ? (int) BPlist.getLong(p, "flushFromSeq", 0) : -1;
        s.flush(from, (int) BPlist.getLong(p, "flushUntilSeq", 0));
    }

    private void teardown(RtspMessage req, RtspMessage.Response res) {
        Object streams = plist(req).get("streams");
        stopSession();
        if (!(streams instanceof List) || ((List<?>) streams).isEmpty()) {
            closeEvents();
            res.close();
            server.sessionEnded(this);
        }
    }

    private void getParameter(RtspMessage req, RtspMessage.Response res) {
        String body = new String(req.body, Util.UTF8);
        if (body.contains("volume")) {
            res.body("text/parameters", String.format(Locale.ROOT, "volume: %.6f\r\n", server.volumeDb()).getBytes(Util.UTF8));
        }
    }

    private void setParameter(RtspMessage req) {
        String type = req.contentType();
        if (type.startsWith("text/parameters")) {
            for (String line : new String(req.body, Util.UTF8).split("\r?\n")) {
                line = line.trim();
                if (line.startsWith("volume:")) {
                    try {
                        double db = Double.parseDouble(line.substring(7).trim());
                        server.setVolumeDb(db);
                        BufferedSession s = session;
                        if (s != null) s.setVolume(db);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.startsWith("progress:")) {
                    String[] parts = line.substring(9).trim().split("/");
                    if (parts.length == 3) {
                        try {
                            server.listener().onProgress(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } else if (type.startsWith("image/")) {
            server.listener().onArtwork(req.body.length == 0 ? null : req.body, type);
        } else if (type.startsWith("application/x-dmap-tagged")) {
            server.listener().onMetadata(Util.parseDmap(req.body));
        }
    }

    // ---------------------------------------------------------------- event channel and addresses

    /** The sender opens an (encrypted) event connection. Nothing needs to be sent on it, so it is held open. */
    private synchronized int openEvents() throws IOException {
        if (eventServer != null) return eventServer.getLocalPort();
        final ServerSocket s = new ServerSocket(0);
        eventServer = s;
        eventThread = new Thread(new Runnable() {
            @Override public void run() {
                List<Socket> held = new ArrayList<>();
                try {
                    while (!closed) {
                        final Socket c = s.accept();
                        held.add(c);
                        Thread drain = new Thread(new Runnable() {
                            @Override public void run() {
                                byte[] b = new byte[1024];
                                try {
                                    InputStream i = c.getInputStream();
                                    while (i.read(b) >= 0) { /* discard */ }
                                } catch (IOException ignored) {
                                }
                            }
                        }, "airplay-events");
                        drain.setDaemon(true);
                        drain.start();
                    }
                } catch (IOException ignored) {
                } finally {
                    for (Socket c : held) {
                        try {
                            c.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, "airplay-event-accept");
        eventThread.setDaemon(true);
        eventThread.start();
        return s.getLocalPort();
    }

    private synchronized void closeEvents() {
        if (eventServer != null) {
            try {
                eventServer.close();
            } catch (IOException ignored) {
            }
            eventServer = null;
        }
    }

    private List<Object> localAddresses() {
        List<Object> list = new ArrayList<>();
        list.add(socket.getLocalAddress().getHostAddress());
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    String text = a.getHostAddress();
                    if (a instanceof Inet6Address) {
                        int pct = text.indexOf('%');
                        if (pct >= 0) text = text.substring(0, pct);
                    }
                    if (!list.contains(text)) list.add(text);
                }
            }
        } catch (SocketException ignored) {
        }
        return list;
    }
}
