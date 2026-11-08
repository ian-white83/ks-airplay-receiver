// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

/** AirPlay volume (dB, -30 to 0, or -144 for mute) to a linear output gain. */
public final class AudioCurve {
    private AudioCurve() {}

    /** The -30..0 dB slider maps to 0..1 and is squared, so the low end stays usable. */
    public static float gain(double db) {
        if (db <= -30.0 || Double.isNaN(db)) return 0f;
        if (db >= 0.0) return 1f;
        double fraction = (db + 30.0) / 30.0;
        return (float) (fraction * fraction);
    }

    /** The sender's slider position as a percentage. */
    public static double percent(double db) {
        return db <= -30 ? 0 : db >= 0 ? 100 : (db + 30) / 30 * 100;
    }
}
