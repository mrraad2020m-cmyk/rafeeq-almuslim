package com.raad.monasabatna;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class UpdateChecker {
    private static final String JSON_URL =
        "https://mrraad2020m-cmyk.github.io/rafeeqalmuslimupdate/rafeeq-almuslim-update.json";

    public static void check(final Activity act) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection)
                    new URL(JSON_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                String s = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
                in.close();
                JSONObject j = new JSONObject(s);
                int latest = j.getInt("versionCode");
                int current = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionCode;
                if (latest <= current) return;
                final String notes = j.optString("notes", "");
                final String apk = j.getString("apkUrl");
                final boolean force = j.optBoolean("force", false);
                act.runOnUiThread(() -> {
                    if (act.isFinishing()) return;
                    AlertDialog.Builder b = new AlertDialog.Builder(act)
                        .setTitle("تحديث جديد متوفر")
                        .setMessage(notes.isEmpty() ? "يوجد إصدار جديد من التطبيق." : notes)
                        .setCancelable(!force)
                        .setPositiveButton("تحديث الآن", (d, w) ->
                            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk))));
                    if (!force) b.setNegativeButton("لاحقاً", null);
                    b.show();
                });
            } catch (Exception ignored) { }
        }).start();
    }
}
