package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;

/**
 * Wakes the TV at the morning time from config.xml every day and opens the morning screen. Rescheduled after
 * each alarm, after a restart (BOOT_COMPLETED) and whenever Home starts, so it survives power cuts.
 */
public class Morning extends BroadcastReceiver {

    private static final String TAG = "TvHomeMorning";
    static final String ACTION = "com.gathin.tvhome.MORNING";

    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, ctx.getResources().getInteger(R.integer.morning_hour));
        c.set(Calendar.MINUTE, ctx.getResources().getInteger(R.integer.morning_minute));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 6, new Intent(ctx, Morning.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT);
        // RTC_WAKEUP so it fires while the TV is in standby.
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        Log.i(TAG, "next morning screen at " + c.getTime());
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(ctx);
            RemoteService.start(ctx);
            return;
        }
        if (ACTION.equals(a)) {
            schedule(ctx);
            show(ctx);
        }
    }

    /** Turns the screen on and opens the morning screen. */
    @SuppressWarnings("deprecation")
    static void show(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "tvhome:morning");
            wl.acquire(15_000);
        }
        ctx.startActivity(new Intent(ctx, MorningActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
