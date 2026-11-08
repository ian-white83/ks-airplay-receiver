// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One RTSP (or HTTP-style) request read from the AirPlay control connection, and the response written back. */
public final class RtspMessage {
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024; // cover art can be large

    public final String method;
    public final String uri;
    public final String protocol;
    private final Map<String, String> headers = new LinkedHashMap<>();
    public byte[] body = new byte[0];

    RtspMessage(String method, String uri, String protocol) {
        this.method = method;
        this.uri = uri;
        this.protocol = protocol;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String contentType() {
        String type = header("Content-Type");
        return type == null ? "" : type.trim();
    }

    /** Read one request, or return null at a clean end of stream. */
    public static RtspMessage read(InputStream in) throws IOException {
        String requestLine;
        do {
            requestLine = readLine(in);
            if (requestLine == null) return null;
        } while (requestLine.isEmpty());
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) throw new IOException("Bad request line: " + requestLine);
        RtspMessage message = new RtspMessage(parts[0], parts[1], parts[2]);
        int headerBytes = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) throw new EOFException("Connection closed in headers");
            if (line.isEmpty()) break;
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) throw new IOException("Headers too large");
            int colon = line.indexOf(':');
            if (colon > 0) message.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        String length = message.header("Content-Length");
        if (length != null) {
            int n;
            try {
                n = Integer.parseInt(length.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Bad Content-Length");
            }
            if (n < 0 || n > MAX_BODY_BYTES) throw new IOException("Body too large");
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(body, off, n - off);
                if (r < 0) throw new EOFException("Connection closed in body");
                off += r;
            }
            message.body = body;
        }
        return message;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > MAX_HEADER_BYTES) throw new IOException("Line too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), Util.UTF8);
    }

    /** A response to send back with the request's CSeq. */
    public static final class Response {
        int status = 200;
        String reason = "OK";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
        boolean close;

        public Response status(int status, String reason) { this.status = status; this.reason = reason; return this; }
        public Response header(String name, String value) { headers.put(name, value); return this; }
        public Response body(String contentType, byte[] body) {
            headers.put("Content-Type", contentType);
            this.body = body == null ? new byte[0] : body;
            return this;
        }
        public Response close() { close = true; headers.put("Connection", "close"); return this; }

        void write(OutputStream out, String protocol, String cseq) throws IOException {
            StringBuilder sb = new StringBuilder();
            boolean known = protocol != null && (protocol.startsWith("RTSP/") || protocol.startsWith("HTTP/"));
            sb.append(known ? protocol : "RTSP/1.0").append(' ').append(status).append(' ').append(reason).append("\r\n");
            if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("Server: AirTunes/").append(AirPlayConfig.SOURCE_VERSION).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            out.write(sb.toString().getBytes(Util.UTF8));
            out.write(body);
            out.flush();
        }
    }
}
