package com.raad.monasabatna;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class WalletFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL = "wallet_updates";
    @Override public void onNewToken(String token) {
        WalletPushTokenRegistrar.register(this, token);
    }
    @Override public void onMessageReceived(RemoteMessage message) {
        String title = value(message, "title", "رفيق المسلم");
        String body = value(message, "body", "لديك تحديث جديد في محفظتك");
        String amount = value(message, "amount_yer", "");
        String balance = value(message, "balance_available_yer", "");
        String note = value(message, "note", "");
        if (!amount.isEmpty() && !body.contains(amount)) body += "\nالمبلغ: " + amount + " ريال يمني";
        if (!note.isEmpty()) body += "\n" + note;
        if (!balance.isEmpty()) body += "\nرصيدك الجديد: " + balance + " ريال يمني";
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "تحديثات المحفظة", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(this, MainActivity.class); open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); open.putExtra("open_wallet", true);
        PendingIntent pi = PendingIntent.getActivity(this, 7301, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_prayer).setContentTitle(title).setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi);
        manager.notify((int)(System.currentTimeMillis() & 0x7fffffff), n.build());
    }
    private String value(RemoteMessage m, String key, String fallback) { String v=m.getData().get(key); return v==null||v.trim().isEmpty()?fallback:v; }
}
