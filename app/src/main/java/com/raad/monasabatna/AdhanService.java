package com.raad.monasabatna;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;

/**
 * Plays the adhan on the ALARM audio stream from a foreground service, so it does not depend on
 * the notification channel sound (which is affected by channel settings, silent mode and the
 * notification volume). A "stop" button is shown while the adhan plays.
 */
public class AdhanService extends Service {
    public static final String ACTION_STOP = "com.raad.monasabatna.ADHAN_STOP";
    private static final String CHANNEL_PLAYING = "adhan_playing_v2";

    private MediaPlayer player;
    private AudioManager audio;
    private int notifId = 0;
    private String title, message;

    public static void start(Context context, String title, String message, int id) {
        Intent i = new Intent(context, AdhanService.class);
        i.putExtra("title", title);
        i.putExtra("message", message);
        i.putExtra("notificationId", id);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, AdhanService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishPlayback();
            return START_NOT_STICKY;
        }
        title = intent != null ? intent.getStringExtra("title") : null;
        message = intent != null ? intent.getStringExtra("message") : null;
        if (title == null) title = "رفيق المسلم";
        if (message == null) message = "حان وقت الصلاة";
        int id = intent != null ? intent.getIntExtra("notificationId", 0) : 0;
        notifId = id == 0 ? 7001 : id;

        // Must be called within a few seconds of startForegroundService().
        startForeground(notifId, buildPlayingNotification());
        launchPopup();
        startPlayback();
        return START_NOT_STICKY;
    }

    private Intent popupIntent() {
        Intent pop = new Intent(this, AdhanPopupActivity.class);
        pop.putExtra("title", title);
        pop.putExtra("message", message);
        pop.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return pop;
    }

    /** Opens the pop-up window (Haram picture + X button) when the adhan starts. */
    private void launchPopup() {
        try { startActivity(popupIntent()); } catch (Throwable ignored) {
            // Blocked from the background: the full-screen notification below will open it instead.
        }
    }

    private void startPlayback() {
        releasePlayer();
        try {
            String voice = getSharedPreferences("adhan_settings", MODE_PRIVATE).getString("voice", "original");
            String fileName = "qatami".equals(voice) ? "adhan_nasser_alqatami.mp3" : "adhan_makkah.mp3";
            File audioFile = new RemoteAssetPackManager(this).getAudioFile(fileName);
            if (audioFile == null) { finishPlayback(); return; }
            try { audio.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT); } catch (Throwable ignored) {}
            player = new MediaPlayer();
            player.setDataSource(audioFile.getAbsolutePath());
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            // Use full player gain; the user still controls the device's alarm volume.
            player.setVolume(1.0f, 1.0f);
            player.setOnPreparedListener(mp -> {
                try { mp.setVolume(1.0f, 1.0f); } catch (Throwable ignored) {}
                mp.start();
            });
            player.setOnCompletionListener(mp -> finishPlayback());
            player.setOnErrorListener((mp, what, extra) -> { finishPlayback(); return true; });
            player.prepareAsync();
        } catch (Throwable t) {
            finishPlayback();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    /** Stops the sound, turns the playing notification into a normal swipeable prayer notification. */
    private void finishPlayback() {
        releasePlayer();
        try { sendBroadcast(new Intent(AdhanPopupActivity.ACTION_CLOSE).setPackage(getPackageName())); }
        catch (Throwable ignored) {}
        try { audio.abandonAudioFocus(null); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        if (notifId != 0 && title != null) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(notifId, buildFinalNotification());
            } catch (Throwable ignored) {}
        }
        stopSelf();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** Tapping the playing notification opens the app and stops the adhan. */
    private PendingIntent openAndStopIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("stop_adhan", true);
        return PendingIntent.getActivity(this, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private Notification buildPlayingNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_PLAYING, "تشغيل الأذان", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(false);
            ch.setDescription("إشعار يظهر أثناء تشغيل الأذان مع زر الإيقاف");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, AdhanService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_PLAYING)
            : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_prayer)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openAndStopIntent())
            .setDeleteIntent(stopPi);
        PendingIntent popupPi = PendingIntent.getActivity(this, 3, popupIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        b.setFullScreenIntent(popupPi, true);
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "إيقاف الأذان", stopPi).build());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private Notification buildFinalNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                ReminderReceiver.CHANNEL_PRAYER_SILENT, "تنبيه الصلاة بدون أذان", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch); // no-op if it already exists
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, ReminderReceiver.CHANNEL_PRAYER_SILENT)
            : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setSound(null);
        return b.build();
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
