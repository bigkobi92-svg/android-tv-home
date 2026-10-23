package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Xml;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Morning screen: big clock, the weather now and for the next hours, and the top headlines. It keeps the
 * screen on for morning_minutes_on (config.xml), then lets the TV fall back to its normal screensaver and
 * sleep. Any key goes to the home screen.
 */
public class MorningActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_morning);
        ((TextView) findViewById(R.id.city)).setText(getString(R.string.weather_city));
        int minutes = getResources().getInteger(R.integer.morning_minutes_on);
        main.postDelayed(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),
                minutes * 60_000L);
        new Thread(this::load, "morning").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event);
        }
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        return true;
    }

    private void load() {
        String lat = getString(R.string.weather_latitude).trim(), lon = getString(R.string.weather_longitude).trim();
        byte[] w = ImageLoader.Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&forecast_hours=12&timezone=auto");
        List<String[]> news = new ArrayList<>();
        for (String spec : getResources().getStringArray(R.array.news_feeds)) {
            String[] p = spec.split("\\|", 2);
            if (p.length < 2) continue;
            byte[] rss = ImageLoader.Net.get(p[1]);
            if (rss == null) continue;
            for (String t : titles(rss, 4)) {
                boolean dup = false;
                for (String[] n : news) dup |= n[1].equalsIgnoreCase(t);
                if (!dup && news.size() < 5) news.add(new String[]{p[0], t});
            }
        }
        main.post(() -> {
            if (isDestroyed()) return;
            if (w != null) showWeather(new String(w, java.nio.charset.StandardCharsets.UTF_8));
            showNews(news);
        });
    }

    private void showWeather(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            ((TextView) findViewById(R.id.temp)).setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            ((TextView) findViewById(R.id.condition)).setText(WeatherLine.describe(cur.optInt("weather_code", -1)));
            JSONObject h = o.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time"), temps = h.getJSONArray("temperature_2m"), codes = h.getJSONArray("weather_code");
            LinearLayout row = findViewById(R.id.hours);
            row.removeAllViews();
            for (int i = 1; i < times.length() && row.getChildCount() < 6; i += 2) {
                TextView t = new TextView(this);
                String hour = times.getString(i).substring(11, 13);
                int hh = Integer.parseInt(hour);
                String label = (hh % 12 == 0 ? 12 : hh % 12) + (hh < 12 ? " am" : " pm");
                t.setText(String.format(Locale.US, "%s\n%d°\n%s", label, Math.round(temps.getDouble(i)),
                        WeatherLine.describe(codes.getInt(i))));
                t.setTextColor(0xE6FFFFFF);
                t.setTextSize(12);
                t.setLineSpacing(0, 1.25f);
                t.setTypeface(getResources().getFont(R.font.outfit));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        (int) (78 * getResources().getDisplayMetrics().density), LinearLayout.LayoutParams.WRAP_CONTENT);
                row.addView(t, lp);
            }
        } catch (Exception ignored) {
        }
    }

    private void showNews(List<String[]> news) {
        LinearLayout box = findViewById(R.id.news);
        box.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (String[] n : news) {
            View item = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, box, false);
            TextView src = item.findViewById(android.R.id.text1), title = item.findViewById(android.R.id.text2);
            src.setText(n[0]);
            src.setTextSize(11);
            src.setTextColor(0xFFE8A33D);
            src.setLetterSpacing(0.12f);
            src.setAllCaps(true);
            title.setText(n[1]);
            title.setTextSize(15);
            title.setTextColor(0xF2FFFFFF);
            title.setMaxLines(2);
            title.setTypeface(getResources().getFont(R.font.outfit));
            src.setTypeface(getResources().getFont(R.font.outfit));
            item.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            item.setMinimumHeight(0);
            box.addView(item);
        }
    }

    /** The first few item titles from an RSS feed. */
    private static List<String> titles(byte[] rss, int max) {
        List<String> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(rss), null);
            boolean inItem = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && out.size() < max; e = p.next()) {
                if (e == XmlPullParser.START_TAG && "item".equals(p.getName())) inItem = true;
                else if (e == XmlPullParser.START_TAG && inItem && "title".equals(p.getName())) {
                    String t = Html.fromHtml(p.nextText(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                    if (!t.isEmpty()) out.add(t);
                    inItem = false;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
