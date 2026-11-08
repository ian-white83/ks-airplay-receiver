// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

/**
 * An immutable snapshot of what is playing, shared by the floating player and the full-screen view. Progress
 * comes from the sender's "progress: start/current/end" RTP timestamps and runs on in real time while playing.
 */
public final class NowPlaying {
    static final long RATE = 44100;

    public final String title, artist, album, source;
    public final byte[] artwork;
    public final boolean playing;
    private final long startRtp, currentRtp, endRtp, progressAtNanos;
    private final boolean hasProgress;

    public static final NowPlaying EMPTY = new NowPlaying("", "", "", "", null, false, 0, 0, 0, 0, false);

    private NowPlaying(String title, String artist, String album, String source, byte[] artwork, boolean playing,
            long startRtp, long currentRtp, long endRtp, long progressAtNanos, boolean hasProgress) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.source = source;
        this.artwork = artwork;
        this.playing = playing;
        this.startRtp = startRtp;
        this.currentRtp = currentRtp;
        this.endRtp = endRtp;
        this.progressAtNanos = progressAtNanos;
        this.hasProgress = hasProgress;
    }

    public NowPlaying withTrack(String title, String artist, String album) {
        return new NowPlaying(nz(title), nz(artist), nz(album), source, artwork, playing, startRtp, currentRtp, endRtp, progressAtNanos, hasProgress);
    }

    public NowPlaying withSource(String source) {
        return new NowPlaying(title, artist, album, nz(source), artwork, playing, startRtp, currentRtp, endRtp, progressAtNanos, hasProgress);
    }

    public NowPlaying withArtwork(byte[] artwork) {
        return new NowPlaying(title, artist, album, source, artwork, playing, startRtp, currentRtp, endRtp, progressAtNanos, hasProgress);
    }

    /** Playing or paused. Pausing freezes the progress where it is. */
    public NowPlaying withPlaying(boolean playing, long nowNanos) {
        if (playing == this.playing) return this;
        long current = currentRtp;
        long at = progressAtNanos;
        if (hasProgress) {
            current = startRtp + Math.round(elapsedSeconds(nowNanos) * RATE);
            at = nowNanos;
        }
        return new NowPlaying(title, artist, album, source, artwork, playing, startRtp, current, endRtp, at, hasProgress);
    }

    public NowPlaying withProgress(long start, long current, long end, long nowNanos) {
        boolean valid = (int) (end - start) > 0;
        return new NowPlaying(title, artist, album, source, artwork, playing, start, current, end, nowNanos, valid);
    }

    public boolean hasProgress() { return hasProgress; }

    /** Track length in seconds, or 0 when unknown. */
    public double durationSeconds() {
        return hasProgress ? ((int) (endRtp - startRtp)) / (double) RATE : 0;
    }

    /** Seconds into the track at a System.nanoTime() moment, clamped to the track. */
    public double elapsedSeconds(long nowNanos) {
        if (!hasProgress) return 0;
        double at = ((int) (currentRtp - startRtp)) / (double) RATE;
        if (playing) at += (nowNanos - progressAtNanos) / 1e9;
        return Math.max(0, Math.min(at, durationSeconds()));
    }

    /** "3:07" style time. */
    public static String clock(double seconds) {
        long s = Math.max(0, Math.round(Math.floor(seconds)));
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    /** True when there is anything worth showing. */
    public boolean hasContent() {
        return !title.isEmpty() || !source.isEmpty();
    }

    /** The second line: artist and album. */
    public String subtitle() {
        if (artist.isEmpty()) return album;
        if (album.isEmpty()) return artist;
        return artist + " — " + album;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
