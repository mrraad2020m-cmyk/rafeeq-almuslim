package com.raad.monasabatna;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        rearmPrayers(context);
        ReminderReceiver.scheduleDailyAdhkar(context);
        ReminderReceiver.scheduleFastingFromSaved(context);
        ReminderReceiver.scheduleDailyQuranReminder(context);
        ReminderReceiver.scheduleRamadanNotification(context);
        PrayerCountdown.update(context);
        SalatSoundReceiver.rearm(context);
        SharedPreferences sp = context.getSharedPreferences("dhikr", Context.MODE_PRIVATE);
        long interval = sp.getLong("repeat_interval", 0);
        if (sp.getBoolean("repeat_on", false) && interval > 0) {
            ReminderReceiver.armDhikr(context, System.currentTimeMillis() + interval, interval);
        }
    }

    /** Alarms are wiped on reboot / app update: re-create the upcoming prayer alarms from the saved schedule. */
    private static void rearmPrayers(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences("prayers", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("schedule", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long t = o.getLong("t");
                if (t <= now || !o.has("id")) continue;
                String n = o.getString("n");
                ReminderReceiver.schedule(context, t,
                    "🕌 حان الآن وقت صلاة " + n,
                    "حان وقت صلاة " + n + ". حيّ على الصلاة، حيّ على الفلاح. تقبّل الله طاعتك 🤲",
                    o.getInt("id"), true, o.optBoolean("a", true), false);
            }
        } catch (Throwable ignored) {
        }
    }
}
