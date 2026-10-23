package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Tile art for YouTube: the official logo from the app's own banner, recoloured for a brand-red tile.
 * The banner is the red play button and dark wordmark on light grey; here the background turns red, the
 * button and wordmark white, and the play triangle red, keeping the logo's real shapes and anti-aliasing.
 */
final class YouTubeTile extends Drawable {

    private static final int RED = 0xFFFF0000;

    private final Bitmap art;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect src;

    /** Returns null when the banner can't be read, so the caller falls back to the app's own banner. */
    static YouTubeTile from(Drawable banner) {
        if (banner == null || banner.getIntrinsicWidth() <= 0 || banner.getIntrinsicHeight() <= 0) return null;
        int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable d = banner.getConstantState() != null ? banner.getConstantState().newDrawable().mutate() : banner;
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int bgLum = luminance(px[0]);   // the banner's flat background, sampled from a corner
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), bl = Color.blue(p);
            // How much this pixel is "logo": red-ness picks out the play button, darkness the wordmark.
            float red = Math.max(0, r - Math.max(g, bl)) / 255f;
            float dark = (bgLum - luminance(p)) / (float) Math.max(1, bgLum - 30);
            float t = Math.max(0f, Math.min(1f, Math.max(red, dark)));
            int c = Math.round(255 * t);   // mix red (t = 0) towards white (t = 1)
            px[i] = Color.rgb(255, c, c);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return new YouTubeTile(b);
    }

    private static int luminance(int p) {
        return (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000;
    }

    private YouTubeTile(Bitmap art) {
        this.art = art;
        src = new Rect(0, 0, art.getWidth(), art.getHeight());
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        paint.setColor(RED);
        c.drawRect(b, paint);
        // Fit the logo art inside the tile, centred, keeping its aspect ratio.
        float scale = Math.min(b.width() / (float) art.getWidth(), b.height() / (float) art.getHeight());
        int w = Math.round(art.getWidth() * scale), h = Math.round(art.getHeight() * scale);
        int x = b.left + (b.width() - w) / 2, y = b.top + (b.height() - h) / 2;
        c.drawBitmap(art, src, new Rect(x, y, x + w, y + h), paint);
    }

    /** Lets each tile get its own copy (a shared drawable keeps only the last bounds it was given). */
    @Override
    public ConstantState getConstantState() {
        return new ConstantState() {
            @Override
            public Drawable newDrawable() {
                return new YouTubeTile(art);
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        };
    }

    @Override
    public int getIntrinsicWidth() {
        return art.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return art.getHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
