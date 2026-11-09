// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.lyrics;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ks.airplay.DacpRemote;

/** Lyrics: JSON and LRC parsing, the LRCLIB lookup against a fake server, and the DACP remote request. */
public final class LyricsTest {
    static void check(boolean c, String what) {
        if (!c) throw new AssertionError("Failed: " + what);
    }

    public static void main(String[] args) throws Exception {
        // JSON
        Object j = Json.parse("{\"a\":[1,2.5,-3e2],\"b\":\"x\\n\\u00e9\\\"\",\"c\":true,\"d\":null,\"e\":{}}");
        Map<?, ?> m = (Map<?, ?>) j;
        check(((List<?>) m.get("a")).get(2).equals(-300.0), "numbers");
        check("x\né\"".equals(m.get("b")), "string escapes");
        check(Boolean.TRUE.equals(m.get("c")) && m.containsKey("d") && m.get("d") == null, "true and null");
        try {
            Json.parse("{\"a\":1,}");
            check(false, "bad JSON rejected");
        } catch (IllegalArgumentException expected) {
        }

        // LRC with tags, several stamps on one line, and an empty line.
        Lyrics l = Lyrics.parseLrc("[ar:Band]\n[00:12.50]First line\n[00:20.00][01:05.25]Chorus\n[00:30.1]\n[1:10]Last");
        check(l.synced && l.lines.size() == 5, "five timed lines: " + l.lines.size());
        check(l.lines.get(0).text.equals("First line") && Math.abs(l.lines.get(0).time - 12.5) < 1e-9, "first line");
        check(l.lines.get(3).text.equals("Chorus") && Math.abs(l.lines.get(3).time - 65.25) < 1e-9, "repeated chorus sorted in");
        check(l.lineAt(5) == -1 && l.lineAt(12.5) == 0 && l.lineAt(25) == 1 && l.lineAt(31) == 2 && l.lineAt(500) == 4, "lineAt");
        check(Lyrics.parseLrc("no stamps here") == null, "not LRC");
        Lyrics plain = Lyrics.plain("One\nTwo\n\n");
        check(!plain.synced && plain.lines.size() == 2 && plain.lineAt(10) == -1, "plain lyrics");

        // LRCLIB: the exact lookup misses, the search finds a synced match near the duration.
        final List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery() + " UA=" + ex.getRequestHeaders().getFirst("User-Agent"));
            String path = ex.getRequestURI().getPath();
            byte[] body;
            int code;
            if (path.equals("/api/get")) {
                code = 404;
                body = "{\"code\":404}".getBytes("UTF-8");
            } else {
                code = 200;
                body = ("[{\"duration\":300,\"syncedLyrics\":\"[00:01.00]Wrong version\"},"
                        + "{\"duration\":201,\"plainLyrics\":\"Plain\",\"syncedLyrics\":\"[00:02.00]Right one\\n[00:04.00]Next\"}]").getBytes("UTF-8");
            }
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        http.start();
        try {
            Lrclib lrclib = new Lrclib("http://127.0.0.1:" + http.getAddress().getPort(), "test-agent");
            Lyrics found = lrclib.find("Song & Dance", "Band", "Album", 200.4);
            check(found != null && found.synced && found.lines.get(0).text.equals("Right one"), "picked the match within 3 s of the duration");
            check(requests.get(0).startsWith("/api/get?track_name=Song%20%26%20Dance&artist_name=Band&album_name=Album&duration=200"), "exact lookup query: " + requests.get(0));
            check(requests.get(0).endsWith("UA=test-agent"), "sends the user agent");
            check(requests.get(1).startsWith("/api/search?"), "falls back to search");
            check(lrclib.find("", "Band", "", 0) == null && requests.size() == 2, "no lookup without a title");
        } finally {
            http.stop(0);
        }
        check(Lrclib.from(Json.parse("{\"instrumental\":true}")).instrumental, "instrumental");

        // DACP: GET /ctrl-int/1/<command> with Active-Remote.
        try (ServerSocket fake = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final String[] got = new String[2];
            Thread t = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    got[0] = in.readLine();
                    String h;
                    while ((h = in.readLine()) != null && !h.isEmpty()) if (h.startsWith("Active-Remote:")) got[1] = h;
                    s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            DacpRemote remote = new DacpRemote("ABCDEF0123456789", "1986535575");
            check(remote.serviceName().equals("iTunes_Ctrl_ABCDEF0123456789"), "service name");
            int status = remote.send(new InetSocketAddress(InetAddress.getLoopbackAddress(), fake.getLocalPort()), DacpRemote.NEXT);
            t.join(2000);
            check(status == 204, "status");
            check("GET /ctrl-int/1/nextitem HTTP/1.1".equals(got[0]), "request line: " + got[0]);
            check("Active-Remote: 1986535575".equals(got[1]), "Active-Remote header");
        }
        System.out.println("LyricsTest passed");
    }
}
