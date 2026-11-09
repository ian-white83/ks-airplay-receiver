// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader: objects become maps, arrays lists, numbers doubles. Enough for LRCLIB's answers. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.space();
        if (j.i != j.s.length()) throw new IllegalArgumentException("Trailing data at " + j.i);
        return v;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (peek() == '}') { i++; return m; }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            m.put(key, value());
            space();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("Expected , or } at " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value());
            space();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + (i - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = next();
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("Bad escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("Unexpected " + s.charAt(i) + " at " + i);
        return Double.parseDouble(s.substring(start, i));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw new IllegalArgumentException("Unexpected token at " + i);
        i += w.length();
        return v;
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : 0; }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("Expected " + c + " at " + (i - 1));
    }
}
