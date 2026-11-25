package com.raad.monasabatna;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;

/**
 * Plays a short "صلِّ على محمد" sound every N minutes (default 15).
 * The audio file is downloaded once into the app-private offline media directory.
 */
public class SalatSoundReceiver extends BroadcastReceiver {
    public static final String PREFS = "salat_sound";
    public static final String RAW_NAME = "salat_nabi";
    public static final int DEFAULT_MINUTES = 15;
    private static final int REQ_ID = 901500;

    /** Save settings and (re)arm or cancel the repeating alarm. */
    public static void configure(Context context, boolean enabled, int minutes) {
        int m = clampMinutes(minutes);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).putInt("minutes", m).apply();
        if (enabled) arm(context, System.currentTimeMillis() + m * 60000L);
        else cancel(context);
    }

    /** Called after reboot / app update, when alarms were wiped. */
    public static void rearm(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains("enabled")) {
            sp.edit().putBoolean("enabled", true).putInt("minutes", DEFAULT_MINUTES).apply();
        }
        if (!sp.getBoolean("enabled", true)) return;
        int m = clampMinutes(sp.getInt("minutes", DEFAULT_MINUTES));
        arm(context, System.currentTimeMillis() + m * 60000L);
    }

    public static String getConfig(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean("enabled", true) + "|" + clampMinutes(sp.getInt("minutes", DEFAULT_MINUTES));
    }

    private static int clampMinutes(int m) { return Math.max(1, Math.min(1440, m)); }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, SalatSoundReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQ_ID, i, flags);
    }

    private static void arm(Context context, long atMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(context);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, atMillis, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            else am.set(AlarmManager.RTC_WAKEUP, atMillis, pi);
        }
    }

    private static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(context);
        am.cancel(pi);
        pi.cancel();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("enabled", true)) return;
        int m = clampMinutes(sp.getInt("minutes", DEFAULT_MINUTES));
        // Re-arm first so the chain continues even if playback fails.
        arm(context, System.currentTimeMillis() + m * 60000L);
        play(context.getApplicationContext(), goAsync());
    }

    /** Plays the clip once. Returns false if the mp3 is missing. Used by the alarm and the "test" button. */
    public static boolean play(final Context context, final PendingResult result) {
        final File audioFile = new RemoteAssetPackManager(context).getAudioFile(RAW_NAME + ".mp3");
        final AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioFile == null || audio == null || audio.getMode() == AudioManager.MODE_IN_CALL
            || audio.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
            if (result != null) result.finish();
            return audioFile != null;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rafeeq:salat");
        wl.acquire(60000L);
        final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        final Object[] focusHolder = new Object[1];
        final boolean[] done = {false};
        final MediaPlayer mp = new MediaPlayer();
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try { mp.release(); } catch (Throwable ignored) {}
                try {
                    if (Build.VERSION.SDK_INT >= 26 && focusHolder[0] != null)
                        audio.abandonAudioFocusRequest((AudioFocusRequest) focusHolder[0]);
                    else if (Build.VERSION.SDK_INT < 26) audio.abandonAudioFocus(null);
                } catch (Throwable ignored) {}
                try { if (wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
                if (result != null) { try { result.finish(); } catch (Throwable ignored) {} }
            }
        };
        try {
            mp.setDataSource(audioFile.getAbsolutePath());
            mp.setAudioAttributes(attrs);
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build();
                focusHolder[0] = req;
                audio.requestAudioFocus(req);
            } else {
                audio.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) { finish.run(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) { finish.run(); return true; }
            });
            mp.prepare();
            mp.start();
            // The broadcast must be released within ~10s; playback itself keeps going under the wake lock.
            if (result != null) handler.postDelayed(new Runnable() {
                @Override public void run() { try { result.finish(); } catch (Throwable ignored) {} }
            }, 9000);
            handler.postDelayed(finish, 55000);
        } catch (Throwable t) {
            finish.run();
        }
        return true;
    }
}
