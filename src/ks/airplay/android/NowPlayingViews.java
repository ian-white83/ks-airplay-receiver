// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import ks.airplay.NowPlaying;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The plugin's Now Playing views: a floating player and a full-screen player over the dashboard, and the same
 * full-screen view drawn as a plugin screensaver. Both read one shared {@link NowPlaying} and refresh themselves, so the plugin only swaps the
 * snapshot when something changes.
 */
public final class NowPlayingViews {
    /** What the views need from the plugin. */
    public interface Source {
        NowPlaying nowPlaying();
    }

    /** Something a view can do when tapped. */
    public interface Tap {
        void onTap();
    }

    private final Source source;
    private final Set<Updatable> live = new CopyOnWriteArraySet<>();

    public NowPlayingViews(Source source) {
        this.source = source;
    }

    /** Ask every visible view to redraw from the current snapshot. Safe from any thread. */
    public void refresh() {
        for (final Updatable u : live) u.view().post(new Runnable() { @Override public void run() { u.update(true); } });
    }

    public OverlayFactory floating(final Tap tap) {
        return new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                Floating f = new Floating(context, theme, tap);
                live.add(f);
                return f.root;
            }
            @Override public void onDestroy(View view) { remove(view); }
        };
    }

    /** The full-screen player over the dashboard, with a button that calls minimise. */
    public OverlayFactory fullScreenPlayer(final Tap minimise) {
        return new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                FullScreen f = new FullScreen(context, theme, minimise);
                live.add(f);
                return f.root;
            }
            @Override public void onDestroy(View view) { remove(view); }
        };
    }

    /** The full-screen view for the screensaver: no buttons, since KS owns touches there. */
    public OverlayFactory fullScreen(final Runnable shownChanged) {
        return new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                FullScreen f = new FullScreen(context, theme, null);
                live.add(f);
                if (shownChanged != null) shownChanged.run();
                return f.root;
            }
            @Override public void onDestroy(View view) {
                remove(view);
                if (shownChanged != null) shownChanged.run();
            }
        };
    }

    private void remove(View view) {
        for (Updatable u : live) {
            if (u.view() == view) {
                u.stop();
                live.remove(u);
            }
        }
    }

    /** A view that redraws from the snapshot and ticks its progress bar once a second while visible. */
    abstract class Updatable implements Runnable {
        private boolean stopped;
        private byte[] shownArt;
        abstract View view();
        abstract void draw(NowPlaying np, boolean artChanged);
        abstract void tick(NowPlaying np);

        void update(boolean full) {
            if (stopped) return;
            NowPlaying np = source.nowPlaying();
            boolean artChanged = full && !Arrays.equals(shownArt, np.artwork);
            if (artChanged) shownArt = np.artwork;
            if (full) draw(np, artChanged);
            tick(np);
        }

        void start() {
            update(true);
            view().postDelayed(this, 1000);
        }

        @Override public void run() {
            if (stopped) return;
            update(false);
            view().postDelayed(this, 1000);
        }

        void stop() {
            stopped = true;
            view().removeCallbacks(this);
        }
    }

    // ---------------------------------------------------------------- floating player

    final class Floating extends Updatable {
        final LinearLayout root;
        private final KsTheme theme;
        private final ImageView cover;
        private final TextView title, subtitle, chip;
        private final ProgressBar progress;

        Floating(Context context, KsTheme theme, final Tap tap) {
            this.theme = theme;
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            int pad = theme.px(12);
            root.setPadding(pad, pad, theme.px(18), pad);
            root.setBackground(theme.card());
            root.setElevation(theme.px(6));
            if (tap != null) {
                root.setClickable(true);
                root.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tap.onTap(); } });
            }
            int art = theme.px(60);
            cover = roundedImage(context, theme.px(theme.radiusRow()));
            root.addView(cover, new LinearLayout.LayoutParams(art, art));

            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(theme.px(300), ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.setMarginStart(theme.px(14));
            root.addView(text, tp);
            title = line(context, theme, 16f, 600, "onSurface");
            subtitle = line(context, theme, 13f, 400, "onSurfaceVariant");
            chip = line(context, theme, 11f, 500, "primary");
            text.addView(title);
            text.addView(subtitle);
            progress = bar(context, theme.color("primary"), theme.color("onSurfaceVariant"));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(4));
            pp.topMargin = theme.px(6);
            text.addView(progress, pp);
            text.addView(chip);
            if (tap != null) {
                View button = iconButton(context, theme, "mdi:fullscreen", theme.color("onSurfaceVariant"), "Open the full-screen player", tap);
                if (button != null) {
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(theme.px(44), theme.px(44));
                    bp.setMarginStart(theme.px(8));
                    root.addView(button, bp);
                }
            }
            start();
        }

        @Override View view() { return root; }

        @Override void draw(NowPlaying np, boolean artChanged) {
            title.setText(np.title.isEmpty() ? (np.source.isEmpty() ? "AirPlay" : np.source) : np.title);
            setOrHide(subtitle, np.subtitle());
            chip.setText((np.playing ? "AirPlay" : "Paused") + (np.source.isEmpty() || np.title.isEmpty() ? "" : " · " + np.source));
            if (artChanged) setArt(cover, np.artwork, theme.px(60), theme, "onSurfaceVariant");
            progress.setVisibility(np.hasProgress() ? View.VISIBLE : View.GONE);
            cover.setContentDescription(artDescription(np));
            root.setContentDescription(spoken(np) + ". Opens the full-screen player.");
        }

        @Override void tick(NowPlaying np) {
            if (np.hasProgress()) progress.setProgress((int) Math.round(1000 * np.elapsedSeconds(System.nanoTime()) / np.durationSeconds()));
        }
    }

    // ---------------------------------------------------------------- full-screen view

    final class FullScreen extends Updatable {
        final FrameLayout root;
        private final KsTheme theme;
        private final ImageView cover;
        private final TextView title, subtitle, chip, elapsed, remaining;
        private final ProgressBar progress;
        private final LinearLayout progressRow;
        private final int artSize;

        FullScreen(Context context, KsTheme theme, Tap minimise) {
            this.theme = theme;
            root = new FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            boolean landscape = dm.widthPixels >= dm.heightPixels;
            artSize = (int) (Math.min(dm.widthPixels, dm.heightPixels) * (landscape ? 0.62f : 0.55f));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            int pad = theme.px(32);
            content.setPadding(pad, pad, pad, pad);
            root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            cover = roundedImage(context, theme.px(theme.radiusCard()));
            cover.setElevation(theme.px(12));
            content.addView(cover, new LinearLayout.LayoutParams(artSize, artSize));

            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(landscape ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    landscape ? Math.max(theme.px(240), (int) (dm.widthPixels * 0.42f)) : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (landscape) tp.setMarginStart(theme.px(40));
            else tp.topMargin = theme.px(28);
            content.addView(text, tp);

            chip = white(line(context, theme, 14f, 500, "onSurface"), 0.75f);
            title = white(line(context, theme, 30f, 700, "onSurface"), 1f);
            title.setSingleLine(false);
            title.setMaxLines(2);
            subtitle = white(line(context, theme, 20f, 400, "onSurface"), 0.8f);
            subtitle.setSingleLine(false);
            subtitle.setMaxLines(2);
            text.addView(chip);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = theme.px(6);
            text.addView(title, titleParams);
            text.addView(subtitle);

            progress = bar(context, Color.WHITE, Color.argb(80, 255, 255, 255));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.px(6));
            pp.topMargin = theme.px(24);
            text.addView(progress, pp);
            progressRow = new LinearLayout(context);
            elapsed = white(line(context, theme, 13f, 500, "onSurface"), 0.75f);
            remaining = white(line(context, theme, 13f, 500, "onSurface"), 0.75f);
            remaining.setGravity(Gravity.END);
            progressRow.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            progressRow.addView(remaining, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            text.addView(progressRow);
            if (minimise != null) {
                root.setClickable(true); // keep taps from reaching the dashboard underneath
                View button = iconButton(context, theme, "mdi:fullscreen-exit", Color.WHITE, "Back to the floating player", minimise);
                if (button == null) {
                    TextView close = white(line(context, theme, 15f, 600, "onSurface"), 0.9f);
                    close.setText("Minimise");
                    int p = theme.px(12);
                    close.setPadding(p, p, p, p);
                    close.setOnClickListener(click(minimise));
                    button = close;
                }
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                if (button instanceof ImageView) bp.width = bp.height = theme.px(56);
                bp.topMargin = bp.rightMargin = theme.px(16);
                root.addView(button, bp);
            }
            start();
        }

        @Override View view() { return root; }

        @Override void draw(NowPlaying np, boolean artChanged) {
            title.setText(np.title.isEmpty() ? (np.source.isEmpty() ? "AirPlay" : np.source) : np.title);
            setOrHide(subtitle, np.subtitle());
            chip.setText((np.playing ? "Playing on AirPlay" : "Paused") + (np.source.isEmpty() || np.title.isEmpty() ? "" : " from " + np.source));
            if (artChanged) {
                Bitmap bitmap = setArt(cover, np.artwork, artSize, theme, "onSurfaceVariant");
                int base = bitmap == null ? Color.rgb(28, 28, 32) : averageColor(bitmap);
                // A dark gradient from the artwork's colour keeps white text readable.
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[] {shade(base, 0.55f), shade(base, 0.22f), Color.BLACK});
                root.setBackground(bg);
            }
            boolean hasProgress = np.hasProgress();
            progress.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
            progressRow.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
            cover.setContentDescription(artDescription(np));
        }

        @Override void tick(NowPlaying np) {
            if (!np.hasProgress()) return;
            double at = np.elapsedSeconds(System.nanoTime()), total = np.durationSeconds();
            progress.setProgress((int) Math.round(1000 * at / total));
            elapsed.setText(NowPlaying.clock(at));
            remaining.setText("-" + NowPlaying.clock(total - at));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** What TalkBack reads for the player: state, track and artist. */
    static String spoken(NowPlaying np) {
        StringBuilder sb = new StringBuilder(np.playing ? "Playing on AirPlay" : "AirPlay paused");
        if (!np.title.isEmpty()) sb.append(": ").append(np.title);
        if (!np.artist.isEmpty()) sb.append(" by ").append(np.artist);
        if (np.title.isEmpty() && !np.source.isEmpty()) sb.append(" from ").append(np.source);
        return sb.toString();
    }

    static String artDescription(NowPlaying np) {
        if (np.artwork == null) return "No cover art";
        return np.album.isEmpty() ? "Cover art" : "Cover art for " + np.album;
    }

    /** A round, borderless icon button, or null when this Kiosk Satellite has no icons. */
    private static View iconButton(Context context, KsTheme theme, String icon, int color, String description, Tap tap) {
        android.graphics.drawable.Drawable d;
        try {
            d = theme.icon(icon, "onSurface");
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        ImageView button = new ImageView(context);
        button.setImageDrawable(d);
        button.setScaleType(ImageView.ScaleType.CENTER);
        int p = theme.px(8);
        button.setPadding(p, p, p, p);
        GradientDrawable ripple = new GradientDrawable();
        ripple.setShape(GradientDrawable.OVAL);
        ripple.setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)));
        button.setBackground(ripple);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setOnClickListener(click(tap));
        return button;
    }

    private static View.OnClickListener click(final Tap tap) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    tap.onTap();
                } catch (RuntimeException ignored) {
                    // the session ended under the button: nothing to do
                }
            }
        };
    }

    private static ImageView roundedImage(Context context, final int radius) {
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        return image;
    }

    private static Bitmap setArt(ImageView view, byte[] data, int size, KsTheme theme, String role) {
        Bitmap bitmap = decode(data, size);
        if (bitmap != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(bitmap);
            view.setBackground(null);
        } else {
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageDrawable(theme.icon("mdi:music-note", role));
            view.setBackground(new ColorDrawable(Color.argb(40, 128, 128, 128)));
        }
        return bitmap;
    }

    private static TextView line(Context context, KsTheme theme, float sp, int weight, String role) {
        TextView view = new TextView(context);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        theme.styleText(view, sp, weight, role);
        return view;
    }

    private static TextView white(TextView view, float alpha) {
        view.setTextColor(Color.argb(Math.round(alpha * 255), 255, 255, 255));
        return view;
    }

    private static void setOrHide(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static ProgressBar bar(Context context, int fill, int track) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(false);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(fill));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
        return bar;
    }

    static Bitmap decode(byte[] data, int size) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    static int averageColor(Bitmap bitmap) {
        try {
            return Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0);
        } catch (RuntimeException e) {
            return Color.rgb(28, 28, 32);
        }
    }

    static int shade(int color, float factor) {
        return Color.rgb(Math.round(Color.red(color) * factor), Math.round(Color.green(color) * factor), Math.round(Color.blue(color) * factor));
    }
}
