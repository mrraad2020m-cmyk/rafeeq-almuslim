package com.raad.monasabatna;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Ongoing notification that counts down to the next prayer (live system chronometer). */
public final class PrayerCountdown {
    public static final int NOTIF_ID = 902001;
    public static final int TICK_ID = 902000;
    private static final String CHANNEL = "prayer_countdown";

    public static void update(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences("prayers", Context.MODE_PRIVATE);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!sp.getBoolean("enabled", true)) {
                nm.cancel(NOTIF_ID);
                cancelTick(context);
                return;
            }
            JSONArray arr = new JSONArray(sp.getString("schedule", "[]"));
            long now = System.currentTimeMillis();
            int next = -1;
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getJSONObject(i).getLong("t") > now) { next = i; break; }
            }
            if (next < 0) {
                nm.cancel(NOTIF_ID);
                cancelTick(context);
                return;
            }
            JSONObject n = arr.getJSONObject(next);
            long target = n.getLong("t");
            String label = n.getString("n");

            SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", new Locale("ar", "SA"));
            String targetText = fmt.format(new Date(target));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "عداد الصلاة القادمة", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("عدّاد تنازلي دائم للصلاة القادمة");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(context, MainActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
            b.setSmallIcon(R.drawable.ic_prayer)
                .setColor(0xFFD9B45E)
                .setContentTitle("🕌 " + label + " • " + targetText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent);
            if (Build.VERSION.SDK_INT >= 24) {
                // Compact notification: prayer name + live countdown beside it.
                RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.notif_prayer);
                rv.setTextViewText(R.id.np_title, "🕌 " + label + " • " + targetText);
                long base = SystemClock.elapsedRealtime() + (target - System.currentTimeMillis());
                rv.setChronometer(R.id.np_timer, base, null, true);
                rv.setChronometerCountDown(R.id.np_timer, true);
                b.setStyle(new Notification.DecoratedCustomViewStyle());
                b.setCustomContentView(rv);
                b.setContentText("المتبقي للأذان");
            } else {
                b.setContentText("موعد الأذان " + targetText);
            }
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
            nm.notify(NOTIF_ID, b.build());

            armTick(context, target + 1500);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent tickIntent(Context context) {
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("prayerTick", true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, TICK_ID, i, flags);
    }

    private static void armTick(Context context, long at) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = tickIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancelTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = tickIntent(context);
        am.cancel(pi);
        pi.cancel();
    }
}
