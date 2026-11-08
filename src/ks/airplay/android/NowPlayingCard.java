// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/** A compact Now Playing card: cover art, title, and artist with album, styled with the Kiosk Satellite theme. */
public final class NowPlayingCard implements OverlayFactory {
    private final String title, subtitle, source;
    private final byte[] artwork;

    public NowPlayingCard(String title, String subtitle, String source, byte[] artwork) {
        this.title = title;
        this.subtitle = subtitle;
        this.source = source;
        this.artwork = artwork;
    }

    @Override
    public View create(Context context, KsTheme theme) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = theme.px(14);
        root.setPadding(pad, pad, theme.px(20), pad);

        final int art = theme.px(64);
        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bitmap = decode(artwork, art);
        if (bitmap != null) {
            cover.setImageBitmap(bitmap);
        } else {
            cover.setImageDrawable(theme.icon("mdi:music-note", "onSurfaceVariant"));
            cover.setScaleType(ImageView.ScaleType.CENTER);
        }
        final int radius = theme.px(theme.radiusRow());
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        cover.setClipToOutline(true);
        root.addView(cover, new LinearLayout.LayoutParams(art, art));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(theme.px(14));
        root.addView(text, textParams);

        text.addView(line(context, theme, title, 17f, 600, "onSurface"));
        if (!TextUtils.isEmpty(subtitle)) text.addView(line(context, theme, subtitle, 14f, 400, "onSurfaceVariant"));
        if (!TextUtils.isEmpty(source)) text.addView(line(context, theme, "AirPlay · " + source, 12f, 500, "primary"));

        root.setBackground(theme.card());
        root.setElevation(theme.px(6));
        return root;
    }

    private static TextView line(Context context, KsTheme theme, String value, float sp, int weight, String role) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMaxWidth(theme.px(360));
        theme.styleText(view, sp, weight, role);
        return view;
    }

    private static Bitmap decode(byte[] data, int size) {
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
}
