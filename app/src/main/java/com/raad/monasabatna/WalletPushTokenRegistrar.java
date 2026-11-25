package com.raad.monasabatna;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/** Registers the current FCM token against the same stable participant UUID used by the WebView. */
public final class WalletPushTokenRegistrar {
    private static final String TAG = "WalletPushToken";
    private static final String SUPABASE_URL = "https://sonkrnhnzfygdeawfmoy.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_gbv6qgNC32m3g6czdRSFEg_F7rwu8cH";

    private WalletPushTokenRegistrar() {}

    public static void register(Context context) {
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> register(context, token))
            .addOnFailureListener(error -> Log.e(TAG, "FCM token retrieval failed", error));
    }

    public static void register(Context context, String token) {
        if (context == null || token == null || token.trim().isEmpty()) {
            Log.w(TAG, "Skipped registration: context/token missing");
            return;
        }
        final String participant = participantKey(context.getApplicationContext());
        if (participant.isEmpty()) {
            Log.e(TAG, "Skipped registration: participant key unavailable");
            return;
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(SUPABASE_URL + "/rest/v1/rpc/wallet_register_push_token");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("apikey", SUPABASE_KEY);
                connection.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                JSONObject body = new JSONObject();
                body.put("p_participant_key", participant);
                body.put("p_fcm_token", token);
                body.put("p_platform", "android");
                byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    Log.i(TAG, "FCM token registered successfully; HTTP " + status);
                } else {
                    java.io.InputStream error = connection.getErrorStream();
                    String details = "";
                    if (error != null) {
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[1024]; int count;
                        while ((count = error.read(chunk)) != -1) buffer.write(chunk, 0, count);
                        details = buffer.toString("UTF-8"); error.close();
                    }
                    Log.e(TAG, "FCM token registration failed; HTTP " + status + ": " + details);
                }
            } catch (Exception error) {
                Log.e(TAG, "FCM token registration request failed", error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "wallet-token-register").start();
    }

    private static String participantKey(Context context) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) return "";
            return UUID.nameUUIDFromBytes(("rafeeq-almuslim-device:" + androidId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        } catch (Exception error) {
            Log.e(TAG, "Could not create participant key", error);
            return "";
        }
    }
}
