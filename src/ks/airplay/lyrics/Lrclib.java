// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.lyrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * Looks lyrics up on LRCLIB (lrclib.net), the free database Kiosk Satellite's own Now Playing uses. Only the
 * track's title, artist, album and duration are sent.
 */
public final class Lrclib {
    public static final String BASE = "https://lrclib.net";
    private final String base, userAgent;

    public Lrclib(String userAgent) {
        this(BASE, userAgent);
    }

    Lrclib(String base, String userAgent) {
        this.base = base;
        this.userAgent = userAgent;
    }

    /**
     * Lyrics for a track: the exact match by title, artist, album and duration first, then the best search hit
     * within a few seconds of the duration. Null when LRCLIB has none.
     */
    public Lyrics find(String title, String artist, String album, double durationSeconds) throws IOException {
        if (title.isEmpty() || artist.isEmpty()) return null;
        StringBuilder q = new StringBuilder("/api/get?track_name=").append(enc(title)).append("&artist_name=").append(enc(artist));
        if (!album.isEmpty()) q.append("&album_name=").append(enc(album));
        if (durationSeconds > 0) q.append("&duration=").append(Math.round(durationSeconds));
        String body = get(q.toString());
        if (body != null) {
            Lyrics l = from(Json.parse(body));
            if (l != null) return l;
        }
        body = get("/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
        if (body == null) return null;
        Object results = Json.parse(body);
        if (!(results instanceof List)) return null;
        Lyrics plainFallback = null;
        for (Object r : (List<?>) results) {
            if (!(r instanceof Map)) continue;
            Object d = ((Map<?, ?>) r).get("duration");
            if (durationSeconds > 0 && d instanceof Double && Math.abs((Double) d - durationSeconds) > 3) continue;
            Lyrics l = from(r);
            if (l != null && l.synced) return l;
            if (plainFallback == null) plainFallback = l;
        }
        return plainFallback;
    }

    static Lyrics from(Object json) {
        if (!(json instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) json;
        if (Boolean.TRUE.equals(m.get("instrumental"))) return Lyrics.instrumental();
        Object synced = m.get("syncedLyrics");
        if (synced instanceof String && !((String) synced).trim().isEmpty()) {
            Lyrics l = Lyrics.parseLrc((String) synced);
            if (l != null) return l;
        }
        Object plain = m.get("plainLyrics");
        if (plain instanceof String && !((String) plain).trim().isEmpty()) return Lyrics.plain((String) plain);
        return null;
    }

    /** The body of a 200 answer, or null for 404. */
    private String get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("LRCLIB answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 2 << 20) throw new IOException("LRCLIB answer too large");
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
