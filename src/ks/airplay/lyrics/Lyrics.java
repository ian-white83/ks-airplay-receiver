// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A track's lyrics: time-stamped lines from an LRC document, or plain lines with no timing. */
public final class Lyrics {
    public static final class Line {
        /** Seconds into the track, or -1 for plain lyrics. */
        public final double time;
        public final String text;
        Line(double time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]");

    public final List<Line> lines;
    public final boolean synced, instrumental;

    private Lyrics(List<Line> lines, boolean synced, boolean instrumental) {
        this.lines = Collections.unmodifiableList(lines);
        this.synced = synced;
        this.instrumental = instrumental;
    }

    public static Lyrics instrumental() {
        return new Lyrics(new ArrayList<Line>(), false, true);
    }

    /** Parse LRC: each line starts with one or more [mm:ss.xx] stamps. Tags like [ar:...] are skipped. */
    public static Lyrics parseLrc(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\r?\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Double> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2).replace(':', '.')));
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (double t : times) lines.add(new Line(t, text));
        }
        Collections.sort(lines, new java.util.Comparator<Line>() {
            @Override public int compare(Line a, Line b) { return Double.compare(a.time, b.time); }
        });
        return lines.isEmpty() ? null : new Lyrics(lines, true, false);
    }

    public static Lyrics plain(String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\r?\n")) lines.add(new Line(-1, raw.trim()));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text.isEmpty()) lines.remove(lines.size() - 1);
        return lines.isEmpty() ? null : new Lyrics(lines, false, false);
    }

    /** Index of the line being sung at this many seconds into the track, or -1 before the first. */
    public int lineAt(double seconds) {
        if (!synced) return -1;
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= seconds) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
