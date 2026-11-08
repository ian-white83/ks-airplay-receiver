// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ks.airplay.fairplay.FairPlay;

/**
 * One sender's RTSP control connection. AirPlay 2 senders (iOS, macOS) use GET /info, fp-setup and two binary
 * plist SETUPs. AirPlay 1 senders use ANNOUNCE with SDP and a Transport header SETUP. Both end in a realtime
 * audio stream handled by {@link AudioSession}.
 */
final class RtspConnection implements Runnable {
    private final AirPlayServer server;
    private final Socket socket;
    private final AirPlayConfig config;
    private final Platform platform;
    private final FairPlay fairPlay = new FairPlay();
    private final Pairing pairing;

    private byte[] streamKey; // decrypted AES key, salted with the pairing secret when there is one
    private byte[] streamIv;
    private boolean encrypted = true;
    private volatile AudioSession audio;
    private String clientName = "AirPlay sender";
    private int announcedCt = -1, announcedSpf = 352;
    private int remoteTimingPort;
    private volatile boolean closed;

    RtspConnection(AirPlayServer server, Socket socket, AirPlayConfig config, Platform platform) {
        this.server = server;
        this.socket = socket;
        this.config = config;
        this.platform = platform;
        this.pairing = new Pairing(config.seed, config.publicKey);
    }

    String clientName() { return clientName; }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (!closed) {
                RtspMessage request = RtspMessage.read(in);
                if (request == null) break;
                RtspMessage.Response response = new RtspMessage.Response();
                try {
                    handle(request, response);
                } catch (Exception e) {
                    platform.log(request.method + " " + request.uri + " failed: " + e);
                    response.status(500, "Internal Server Error");
                }
                response.write(out, request.protocol, request.header("CSeq"));
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
        stopAudio();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void stopAudio() {
        AudioSession a = audio;
        audio = null;
        if (a != null) {
            a.close();
            server.audioStopped(this);
        }
    }

    AudioSession audio() { return audio; }

    private void handle(RtspMessage req, RtspMessage.Response res) throws Exception {
        String method = req.method.toUpperCase(Locale.ROOT);
        String path = req.uri;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);

        if (method.equals("GET") && path.equals("/info")) { info(req, res); return; }
        if (method.equals("POST") && path.equals("/pair-setup")) {
            res.body("application/octet-stream", pairing.setup(req.body));
            return;
        }
        if (method.equals("POST") && path.equals("/pair-verify")) {
            res.body("application/octet-stream", pairing.verify(req.body));
            return;
        }
        if (method.equals("POST") && path.equals("/fp-setup")) {
            byte[] reply = fairPlay.setup(req.body);
            if (reply == null) {
                res.status(501, "Not Implemented");
                platform.log("Unsupported FairPlay request");
            } else {
                res.body("application/octet-stream", reply);
            }
            return;
        }
        if (method.equals("POST") && (path.equals("/feedback") || path.equals("/audioMode") || path.equals("/command"))) return;
        if (method.equals("OPTIONS")) {
            res.header("Public", "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
            return;
        }
        if (method.equals("ANNOUNCE")) { announce(req, res); return; }
        if (method.equals("SETUP")) {
            if (req.contentType().contains("x-apple-binary-plist")) setupPlist(req, res);
            else setupTransport(req, res);
            return;
        }
        if (method.equals("RECORD")) {
            res.header("Audio-Latency", String.valueOf(config.bufferMs * AudioSession.SAMPLE_RATE / 1000));
            res.header("Audio-Jack-Status", "connected; type=analog");
            return;
        }
        if (method.equals("FLUSH")) {
            AudioSession a = audio;
            if (a != null) a.flush(rtpInfoSeq(req.header("RTP-Info")));
            return;
        }
        if (method.equals("TEARDOWN")) { teardown(req, res); return; }
        if (method.equals("GET_PARAMETER")) { getParameter(req, res); return; }
        if (method.equals("SET_PARAMETER")) { setParameter(req); return; }
        if (method.equals("SETPEERS") || method.equals("SETPEERSX") || method.equals("SETRATEANCHORTIME")) {
            res.status(501, "Not Implemented"); // buffered audio and PTP are not supported
            return;
        }
        res.status(404, "Not Found");
    }

    // ---------------------------------------------------------------- AirPlay 2

    @SuppressWarnings("unchecked")
    private void info(RtspMessage req, RtspMessage.Response res) {
        String qualifier = null;
        if (req.contentType().contains("x-apple-binary-plist") && req.body.length > 0) {
            try {
                Map<String, Object> root = (Map<String, Object>) BPlist.parse(req.body);
                Object q = root.get("qualifier");
                if (q instanceof List && !((List<Object>) q).isEmpty()) qualifier = String.valueOf(((List<Object>) q).get(0));
            } catch (Exception e) {
                platform.log("Unreadable /info request: " + e.getMessage());
            }
        }
        boolean full = qualifier == null && req.header("CSeq") != null;
        res.body("application/x-apple-binary-plist", BPlist.write(config.info(qualifier, full, server.volumeDb())));
    }

    @SuppressWarnings("unchecked")
    private void setupPlist(RtspMessage req, RtspMessage.Response res) throws Exception {
        Map<String, Object> root = (Map<String, Object>) BPlist.parse(req.body);
        Map<String, Object> reply = new LinkedHashMap<>();
        byte[] ekey = BPlist.getData(root, "ekey");
        byte[] eiv = BPlist.getData(root, "eiv");
        if (ekey != null && eiv != null) {
            if (ekey.length != 72 || eiv.length != 16) {
                res.status(400, "Bad Request");
                return;
            }
            String name = BPlist.getString(root, "name");
            if (name != null && !name.isEmpty()) clientName = name;
            byte[] key = fairPlay.decryptKey(ekey);
            byte[] secret = pairing.secret();
            if (secret != null) key = java.util.Arrays.copyOf(AirPlayConfig.sha512(key, secret), 16);
            streamKey = key;
            streamIv = eiv.clone();
            encrypted = true;
            if (!"NTP".equals(BPlist.getString(root, "timingProtocol")) && root.containsKey("timingProtocol")) {
                platform.log("Sender asked for timing protocol " + BPlist.getString(root, "timingProtocol") + "; only NTP is supported");
            }
            stopAudio();
            remoteTimingPort = (int) BPlist.getLong(root, "timingPort", 0);
            audio = newAudioSession();
            server.sessionStarted(this);
            reply.put("timingPort", (long) audio.timingPort());
            reply.put("eventPort", 0L);
        }
        Object streams = root.get("streams");
        if (streams instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) streams) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> stream = (Map<String, Object>) o;
                long type = BPlist.getLong(stream, "type", 0);
                if (type == 96) {
                    if (audio == null && streamKey != null) {
                        // Resuming after a pause: the sender tore down only the stream and keeps its keys.
                        audio = newAudioSession();
                    }
                    AudioSession a = audio;
                    if (a == null) {
                        res.status(400, "Bad Request");
                        return;
                    }
                    int ct = (int) BPlist.getLong(stream, "ct", 2);
                    int spf = (int) BPlist.getLong(stream, "spf", 352);
                    int controlPort = (int) BPlist.getLong(stream, "controlPort", 0);
                    a.startAudio(ct, spf, controlPort);
                    a.setVolume(server.volumeDb());
                    server.streamStarted(this, ct);
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("type", 96L);
                    s.put("dataPort", (long) a.dataPort());
                    s.put("controlPort", (long) a.controlPort());
                    out.add(s);
                } else {
                    // 110 is screen mirroring, 103 buffered audio: neither is supported, so refuse the session.
                    platform.log("Refused stream type " + type + " (only realtime audio is supported)");
                    res.status(501, "Not Implemented");
                    return;
                }
            }
            reply.put("streams", out);
        }
        res.body("application/x-apple-binary-plist", BPlist.write(reply));
    }

    private AudioSession newAudioSession() throws IOException {
        AudioSession a = new AudioSession(platform, server.audioEvents(this), socket.getInetAddress(), streamKey, streamIv, config.bufferMs);
        a.setRemoteTimingPort(remoteTimingPort);
        return a;
    }

    @SuppressWarnings("unchecked")
    private void teardown(RtspMessage req, RtspMessage.Response res) {
        boolean audioOnly = false;
        if (req.body.length > 0 && req.contentType().contains("x-apple-binary-plist")) {
            try {
                Map<String, Object> root = (Map<String, Object>) BPlist.parse(req.body);
                Object streams = root.get("streams");
                if (streams instanceof List && !((List<Object>) streams).isEmpty()) audioOnly = true;
            } catch (Exception ignored) {
            }
        }
        stopAudio();
        if (!audioOnly) {
            res.close();
            server.sessionEnded(this);
        }
    }

    // ---------------------------------------------------------------- AirPlay 1

    private void announce(RtspMessage req, RtspMessage.Response res) {
        String sdp = new String(req.body, Util.UTF8);
        byte[] fpKey = null, rsaKey = null, iv = null;
        int ct = -1;
        for (String raw : sdp.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("a=rtpmap:")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("applelossless")) ct = 2;
                else if (lower.contains("mpeg4-generic")) ct = 4;
                else if (lower.contains("l16")) ct = 0;
            } else if (line.startsWith("a=fmtp:")) {
                String[] parts = line.substring(7).trim().split("\\s+");
                if (parts.length > 1 && ct == 2) {
                    try {
                        announcedSpf = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (line.startsWith("a=fpaeskey:")) {
                fpKey = Util.base64(line.substring(11));
            } else if (line.startsWith("a=rsaaeskey:")) {
                rsaKey = Util.base64(line.substring(12));
            } else if (line.startsWith("a=aesiv:")) {
                iv = Util.base64(line.substring(8));
            } else if (line.startsWith("s=") && line.length() > 2) {
                clientName = line.substring(2);
            }
        }
        if (ct != 2 && ct != 4) {
            platform.log("Unsupported AirPlay 1 audio format");
            res.status(415, "Unsupported Media Type");
            return;
        }
        if (rsaKey != null) {
            platform.log("RSA-encrypted AirPlay 1 streams are not supported");
            res.status(415, "Unsupported Media Type");
            return;
        }
        announcedCt = ct;
        if (fpKey != null && iv != null && iv.length == 16) {
            // fpaeskey holds a 72-byte FairPlay key, sometimes behind a short header.
            byte[] ekey = fpKey.length > 72 ? java.util.Arrays.copyOfRange(fpKey, fpKey.length - 72, fpKey.length) : fpKey;
            streamKey = fairPlay.decryptKey(ekey);
            streamIv = iv;
            encrypted = true;
        } else {
            streamKey = null;
            streamIv = null;
            encrypted = false;
        }
    }

    private void setupTransport(RtspMessage req, RtspMessage.Response res) throws Exception {
        String transport = req.header("Transport");
        if (transport == null || announcedCt < 0) {
            res.status(455, "Method Not Valid In This State");
            return;
        }
        int controlPort = 0, timingPort = 0;
        for (String part : transport.split(";")) {
            String p = part.trim();
            if (p.startsWith("control_port=")) controlPort = parseInt(p.substring(13));
            else if (p.startsWith("timing_port=")) timingPort = parseInt(p.substring(12));
        }
        stopAudio();
        audio = new AudioSession(platform, server.audioEvents(this), socket.getInetAddress(), encrypted ? streamKey : null, streamIv, config.bufferMs);
        audio.setRemoteTimingPort(timingPort);
        server.sessionStarted(this);
        audio.startAudio(announcedCt, announcedSpf, controlPort);
        audio.setVolume(server.volumeDb());
        server.streamStarted(this, announcedCt);
        res.header("Transport", "RTP/AVP/UDP;unicast;mode=record;server_port=" + audio.dataPort()
                + ";control_port=" + audio.controlPort() + ";timing_port=" + audio.timingPort());
        res.header("Session", "1");
        res.header("Audio-Jack-Status", "connected; type=analog");
    }

    // ---------------------------------------------------------------- parameters

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
                        AudioSession a = audio;
                        if (a != null) a.setVolume(db);
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

    private static int rtpInfoSeq(String rtpInfo) {
        if (rtpInfo == null) return -1;
        for (String part : rtpInfo.split(";")) {
            String p = part.trim();
            if (p.startsWith("seq=")) return parseInt(p.substring(4)) & 0xffff;
        }
        return -1;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
