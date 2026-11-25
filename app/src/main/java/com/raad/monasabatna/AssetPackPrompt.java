package com.raad.monasabatna;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

/** Shows a one-time consent prompt only while the private asset pack is missing. */
public final class AssetPackPrompt {
    private final WeakReference<Activity> activityRef;
    private final RemoteAssetPackManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean promptScheduled;
    private boolean promptShown;
    private boolean downloading;
    private ProgressDialog progressDialog;
    private final Runnable promptRunnable;

    public AssetPackPrompt(Activity activity, RemoteAssetPackManager manager) {
        this.activityRef = new WeakReference<>(activity);
        this.manager = manager;
        this.promptRunnable = () -> {
            promptScheduled = false;
            Activity current = this.activityRef.get();
            if (current == null || current.isFinishing() || current.isDestroyed()
                    || !current.hasWindowFocus() || this.manager.isReady()) return;
            promptShown = true;
            showConsent(current);
        };
    }

    /** Waits briefly so startup permission dialogs are not obscured. */
    public void onResume() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || manager.isReady()
                || promptShown || promptScheduled || downloading) return;
        promptScheduled = true;
        handler.postDelayed(promptRunnable, 4500L);
    }

    public void onDestroy() {
        handler.removeCallbacks(promptRunnable);
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        progressDialog = null;
        activityRef.clear();
    }

    private void showConsent(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("تنزيل صفحات القرآن الكريم والأذان")
                .setMessage("يلزم تنزيل صور صفحات المصحف وأصوات الأذان مرة واحدة (نحو 37 ميغابايت). ستُحفظ داخل مساحة التطبيق الخاصة، ولن تظهر صور القرآن في الاستديو، ولن يعاد تنزيلها عند تحديث التطبيق ما دامت محفوظة. اضغط «موافق» لبدء التنزيل.")
                .setCancelable(false)
                .setPositiveButton("موافق", (dialog, which) -> beginDownload())
                .setNegativeButton("لاحقًا", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void beginDownload() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || downloading) return;
        downloading = true;
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("تنزيل موارد التطبيق");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setMessage("جارٍ الاتصال بـ GitHub…");
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        manager.downloadLatest(new RemoteAssetPackManager.Listener() {
            @Override public void onProgress(int percent, String message) {
                Activity current = activityRef.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.setProgress(percent);
                        progressDialog.setMessage(message);
                    }
                });
            }

            @Override public void onFinished(boolean success, String message) {
                Activity current = activityRef.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.runOnUiThread(() -> {
                    downloading = false;
                    if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                    progressDialog = null;
                    if (success && manager.isReady()) showDone(current);
                    else showFailure(current, message);
                });
            }
        });
    }

    private void showDone(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("تم")
                .setMessage("اكتمل تنزيل صفحات القرآن الكريم وأصوات الأذان والتحقق منها. اضغط «تم» لإعادة تشغيل التطبيق.")
                .setCancelable(false)
                .setPositiveButton("تم", (dialog, which) -> restartApp(activity))
                .show();
    }

    private void showFailure(Activity activity, String message) {
        new AlertDialog.Builder(activity)
                .setTitle("لم يكتمل التنزيل")
                .setMessage(message == null ? "تحقق من اتصال الإنترنت ومساحة التخزين ثم أعد المحاولة." : message)
                .setCancelable(false)
                .setPositiveButton("إعادة المحاولة", (dialog, which) -> beginDownload())
                .setNegativeButton("لاحقًا", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void restartApp(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null) {
            activity.recreate();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(launch);
        activity.finish();
    }
}
