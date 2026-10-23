package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The apps used most recently, from the TV's own usage record. Needs usage access, granted once over adb:
 * appops set com.gathin.tvhome GET_USAGE_STATS allow. Without it the row is simply left out.
 */
final class Recents {

    static final class Recent {
        final Apps.App app;
        final long lastUsed;

        Recent(Apps.App app, long lastUsed) {
            this.app = app;
            this.lastUsed = lastUsed;
        }

        String when(Context ctx) {
            return app.label + "  ·  " + DateUtils.getRelativeTimeSpanString(lastUsed, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        }
    }

    private Recents() {
    }

    static List<Recent> load(Context ctx, List<Apps.App> apps, int max) {
        List<Recent> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 21 * DateUtils.DAY_IN_MILLIS, now);
        } catch (SecurityException e) {
            return out;
        }
        if (stats == null) return out;
        Map<String, Long> last = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() < 20_000) continue;   // ignore apps only flashed past
            Long prev = last.get(s.getPackageName());
            if (prev == null || s.getLastTimeUsed() > prev) last.put(s.getPackageName(), s.getLastTimeUsed());
        }
        java.util.List<String> skip = java.util.Arrays.asList("com.android.vending", "com.android.tv.settings",
                "com.google.android.katniss", "com.google.android.tvlauncher");
        for (Apps.App a : apps) {
            if (skip.contains(a.pkg)) continue;
            Long t = last.get(a.pkg);
            if (t != null) out.add(new Recent(a, t));
        }
        out.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
