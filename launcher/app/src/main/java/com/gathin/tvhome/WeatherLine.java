package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current temperature for the status line, from Open-Meteo (free, no key). City and coordinates come from
 * res/values/config.xml. Hidden until the first successful fetch; keeps the last reading while offline.
 */
final class WeatherLine {

    private static final long REFRESH_MS = 30 * 60_000L;

    private final TextView text;
    private final View dot;
    private final String city, url;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long fetchedAt;

    WeatherLine(Context ctx, TextView text, View dot) {
        this.text = text;
        this.dot = dot;
        city = ctx.getString(R.string.weather_city).trim();
        url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,weather_code&timezone=auto",
                ctx.getString(R.string.weather_latitude).trim(), ctx.getString(R.string.weather_longitude).trim());
    }

    /** Called on resume and every minute; only hits the network every 30 minutes. */
    void refresh() {
        if (city.isEmpty() || System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        fetchedAt = System.currentTimeMillis();
        io.execute(() -> {
            String line = fetch();
            if (line == null) {
                fetchedAt = 0;   // try again on the next tick
                return;
            }
            main.post(() -> {
                text.setText(line);
                text.setVisibility(View.VISIBLE);
                dot.setVisibility(View.VISIBLE);
            });
        });
    }

    private String fetch() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                JSONObject cur = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                return t + "°  " + describe(cur.optInt("weather_code", -1)) + ", " + city;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather codes, condensed. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "Rain";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }
}
