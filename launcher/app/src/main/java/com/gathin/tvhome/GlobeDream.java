package com.gathin.tvhome;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;

/**
 * Night globe screensaver. Pick it in Settings, or:
 * settings put secure screensaver_components com.gathin.tvhome/.GlobeDream
 * Flights refresh every 15 minutes (OpenSky's free limit); between refreshes planes move along their heading.
 */
public class GlobeDream extends DreamService {

    private static final long FLIGHTS_MS = 15 * 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GlobeView globe;
    private TextView planes;
    private WeatherLine weather;
    private volatile boolean running;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                if (globe.planeCount() == 0) {
                    globe.loadCachedPlanes();   // saved list first, so planes show at once
                    main.post(GlobeDream.this::updateCount);
                }
                java.io.File f = GlobeView.cacheFile(GlobeDream.this);
                boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 15 * 60_000L;
                if (!fresh || globe.planeCount() == 0) globe.refreshPlanes();   // OpenSky's free allowance is small
                main.post(GlobeDream.this::updateCount);
            }, "flights").start();
            weather.refresh();
            if (running) main.postDelayed(this, FLIGHTS_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        float d = getResources().getDisplayMetrics().density;
        Typeface outfit = getResources().getFont(R.font.outfit);

        FrameLayout root = new FrameLayout(this);
        globe = new GlobeView(this);
        root.addView(globe, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(64);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.create(outfit, Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour(getString(R.string.date_format));
        date.setFormat24Hour(getString(R.string.date_format));
        date.setAllCaps(true);
        date.setLetterSpacing(0.16f);
        date.setTextSize(13);
        date.setTextColor(0xCCFFFFFF);
        date.setTypeface(outfit);
        TextView wx = new TextView(this);
        wx.setTextSize(14);
        wx.setTextColor(0xE6FFFFFF);
        wx.setTypeface(outfit);
        TextView dotView = new TextView(this);
        planes = new TextView(this);
        planes.setTextSize(12);
        planes.setTextColor(0xFFFFC75A);
        planes.setTypeface(outfit);
        TextView credit = new TextView(this);
        credit.setText("Live flights: OpenSky Network");
        credit.setTextSize(10);
        credit.setTextColor(0x66FFFFFF);
        credit.setTypeface(outfit);
        info.addView(clock);
        info.addView(date);
        info.addView(wx, lp(10 * d));
        info.addView(planes, lp(4 * d));
        info.addView(credit, lp(12 * d));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        ip.setMargins((int) (48 * d), 0, 0, (int) (40 * d));
        root.addView(info, ip);
        setContentView(root);

        weather = new WeatherLine(this, wx, dotView);
    }

    private static LinearLayout.LayoutParams lp(float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) top;
        return p;
    }

    private void updateCount() {
        int n = globe.planeCount();
        planes.setText(n > 0 ? String.format(Locale.US, "%,d planes in the air over Africa and Europe", n) : "");
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        main.post(refresh);
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        main.removeCallbacks(refresh);
        super.onDreamingStopped();
    }
}
