package com.raad.monasabatna;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.webkit.WebViewAssetLoader;
import android.graphics.Color;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.MediaStore;
import android.view.View;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public class MainActivity extends Activity {
    // User-presence heartbeat for the administration dashboard.
    private final android.os.Handler usageHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String usageSessionKey = "";
    private boolean usageHeartbeatRunning = false;
    private static final String USAGE_SUPABASE_BASE = "https://sonkrnhnzfygdeawfmoy.supabase.co";
    private static final String USAGE_SUPABASE_KEY = "sb_publishable_gbv6qgNC32m3g6czdRSFEg_F7rwu8cH";
    private final Runnable usageHeartbeat = new Runnable() {
        @Override public void run() {
            if (!usageHeartbeatRunning) return;
            sendUsageHeartbeat(false);
            usageHandler.postDelayed(this, 5000);
        }
    };
    private WebView webView;
    private RemotePatchManager remotePatchManager;
    private RemoteAssetPackManager remoteAssetPackManager;
    private AssetPackPrompt assetPackPrompt;
    private WebViewAssetLoader assetLoader;
    private boolean webPageLoaded = false;
    private volatile boolean remotePatchApplied = false;
    private boolean remotePatchReloaded = false;
    private String pendingAdhkarTarget = null;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 501;
    private static final int LOCATION_PERMISSION_REQUEST = 502;
    private static final int MIC_PERMISSION_REQUEST = 503;
    private static final int SPEECH_PERMISSION_REQUEST = 504;
    private android.speech.SpeechRecognizer recognizer;
    private boolean speechWanted = false, speechAwaitingPermission = false, speechResume = false;
    private int speechErrors = 0;
    private long lastRmsSent = 0;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private android.webkit.PermissionRequest pendingMicRequest;
    private static final int DHIKR_REPEAT_ID = 901000;
    private volatile boolean locationRequestInProgress = false;
    private android.location.LocationListener activeLocationListener;
    private final Handler locationHandler = new Handler(Looper.getMainLooper());
    private Runnable locationTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WalletPushTokenRegistrar.register(this);
        handleWalletPushIntent(getIntent());
        // Adhan is ON for first installs. Preserve an explicit OFF choice on later launches.
        android.content.SharedPreferences adhanPrefs = getSharedPreferences("adhan_settings", MODE_PRIVATE);
        if (!adhanPrefs.contains("enabled")) {
            adhanPrefs.edit().putBoolean("enabled", true).apply();
        }
        android.content.SharedPreferences salatPrefs = getSharedPreferences(SalatSoundReceiver.PREFS, MODE_PRIVATE);
        if (!salatPrefs.contains("enabled")) {
            salatPrefs.edit().putBoolean("enabled", true).putInt("minutes", SalatSoundReceiver.DEFAULT_MINUTES).apply();
        }
        getWindow().setStatusBarColor(Color.rgb(9, 26, 48));
        getWindow().setNavigationBarColor(Color.rgb(8, 20, 38));
        remotePatchManager = new RemotePatchManager(this);
        remoteAssetPackManager = new RemoteAssetPackManager(this);
        assetPackPrompt = new AssetPackPrompt(this, remoteAssetPackManager);
        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(9, 26, 48));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setGeolocationEnabled(true);
        webView.addJavascriptInterface(new ReminderBridge(this), "AndroidReminders");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openWhatsAppExternally(request.getUrl());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openWhatsAppExternally(Uri.parse(url));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // لا تستدعِ أي دالة من WebView هنا: هذه الدالة تعمل على خيط الشبكة وليس الخيط الرئيسي،
                // واستدعاء view.getSettings() منها يرمي RuntimeException ويُغلق التطبيق.
                String requestUrl = request.getUrl().toString();
                WebResourceResponse offlineMedia = remoteAssetPackManager.intercept(requestUrl);
                if (offlineMedia != null) return offlineMedia;
                WebResourceResponse patched = remotePatchManager.intercept(requestUrl);
                if (patched != null) return patched;
                WebResourceResponse asset = assetLoader.shouldInterceptRequest(request.getUrl());
                return asset != null ? asset : super.shouldInterceptRequest(view, request);
            }
            @SuppressWarnings("deprecation")
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse offlineMedia = remoteAssetPackManager.intercept(url);
                if (offlineMedia != null) return offlineMedia;
                WebResourceResponse patched = remotePatchManager.intercept(url);
                if (patched != null) return patched;
                WebResourceResponse asset = assetLoader.shouldInterceptRequest(Uri.parse(url));
                return asset != null ? asset : super.shouldInterceptRequest(view, url);
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                updateCacheMode(); // يعمل على الخيط الرئيسي
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // منع إغلاق التطبيق عند انهيار عملية عرض الويب؛ نعيد إنشاء الصفحة بدلاً من ذلك.
                runOnUiThread(() -> { try { recreate(); } catch (Throwable ignored) {} });
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webPageLoaded = true;
                if (remotePatchApplied && !remotePatchReloaded) { remotePatchReloaded = true; reloadFresh(); return; }
                dispatchPendingAdhkarNavigation();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : request.getResources()) if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    String host = request.getOrigin() == null ? "" : String.valueOf(request.getOrigin().getHost());
                    if (!audio || !"appassets.androidplatform.net".equals(host)) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingMicRequest != null) pendingMicRequest.deny();
                        pendingMicRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
                if (pendingMicRequest == request) pendingMicRequest = null;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }
        });
        setContentView(webView);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        checkRemoteUpdate();
        handleStopIntent(getIntent());
        handleAdhkarIntent(getIntent());
        // Ask for notification access first, then location, avoiding overlapping permission dialogs.
        webView.postDelayed(this::requestNextPermission, 700);
    }

    /** يضبط وضع الكاش حسب توفر الإنترنت. يجب استدعاؤها من الخيط الرئيسي فقط. */
    private void updateCacheMode() {
        try {
            if (webView == null) return;
            webView.getSettings().setCacheMode(isNetworkAvailable()
                ? android.webkit.WebSettings.LOAD_DEFAULT
                : android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } catch (Throwable ignored) {}
    }

    private boolean openWhatsAppExternally(Uri uri) {
        if (uri == null) return false;
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        String host = String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT);
        boolean waWeb = "wa.me".equals(host) || "www.wa.me".equals(host)
                || "api.whatsapp.com".equals(host) || "www.whatsapp.com".equals(host);
        boolean waScheme = "whatsapp".equals(scheme);
        if (!waWeb && !waScheme) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception first) {
            String[] pkgs = {"com.whatsapp", "com.whatsapp.w4b"};
            for (String pkg : pkgs) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, uri);
                    i.setPackage(pkg);
                    startActivity(i);
                    return true;
                } catch (Exception ignored) { }
            }
            android.widget.Toast.makeText(this,
                "تعذر فتح واتساب. تأكد أنه مثبت، ثم أرسل رقم الطلب يدوياً.",
                android.widget.Toast.LENGTH_LONG).show();
            return true; // يمنع ظهور صفحة الخطأ داخل التطبيق
        }
    }

    /** Loads the app page again under a new URL after clearing caches, so the just-applied update is what the user sees. */
    private void reloadFresh() {
        if (webView == null) return;
        // Ask the page to open on the home screen after this reload (consumed by restoreLastView).
        webView.evaluateJavascript("try{localStorage.setItem('raadGoHome','1');}catch(e){}", ignored -> {
            if (webView == null) return;
            webView.clearCache(true);
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html?r=" + System.currentTimeMillis());
            webView.clearHistory();
        });
    }

    private long lastUpdateCheck = 0;
    private boolean updateCheckRunning = false;
    private static final long UPDATE_CHECK_INTERVAL_MS = 60_000L;

    /** Checks for published updates; on success reloads the page so changes show without closing the app. */
    private void checkRemoteUpdate() {
        if (updateCheckRunning) return;
        updateCheckRunning = true;
        lastUpdateCheck = System.currentTimeMillis();
        remotePatchManager.applyLatestAsync((updated, message) -> runOnUiThread(() -> {
            updateCheckRunning = false;
            if (updated) {
                remotePatchApplied = true;
                android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_LONG).show();
                if (webPageLoaded && webView != null) {
                    remotePatchReloaded = true;
                    reloadFresh();
                }
                // If the page is still loading, onPageFinished performs the reload.
            } else if (message != null && message.startsWith("فشل التحديث:")) {
                android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_LONG).show();
            }
        }));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStopIntent(intent);
        handleAdhkarIntent(intent);
        handleWalletPushIntent(intent);
    }

    private void handleAdhkarIntent(Intent intent) {
        if (intent == null) return;
        String target = intent.getStringExtra("open_adhkar");
        if ("morning".equals(target) || "evening".equals(target) || "sleep".equals(target) || "quran".equals(target)) {
            pendingAdhkarTarget = target;
            intent.removeExtra("open_adhkar");
            dispatchPendingAdhkarNavigation();
        }
    }

    private void dispatchPendingAdhkarNavigation() {
        if (!webPageLoaded || webView == null || pendingAdhkarTarget == null) return;
        final String target = pendingAdhkarTarget;
        pendingAdhkarTarget = null;
        webView.postDelayed(() -> webView.evaluateJavascript(
            "if('quran'==='" + target + "'){if(window.openQuranFromNotification)window.openQuranFromNotification();}"
                + "else if(window.openAdhkarFromNotification){window.openAdhkarFromNotification('" + target + "');}", null), 250);
    }

    private void handleStopIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("stop_adhan", false)) {
            intent.removeExtra("stop_adhan");
            AdhanService.stop(this);
        }
    }


    // ===== Quran competition: speech recognition (text is compared to the page in JS) =====
    private void jsCall(final String js) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void beginSpeech() {
        if (!speechWanted) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!speechAwaitingPermission) {
                speechAwaitingPermission = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, SPEECH_PERMISSION_REQUEST);
            }
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(speechListener);
            }
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
            i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            muteBeep();
            recognizer.startListening(i);
        } catch (Throwable t) {
            speechWanted = false;
            jsCall("window.qcompSpeechState&&qcompSpeechState('unavailable')");
        }
    }

    // Silence the recognizer's start/stop "ding" while the competition reader is listening.
    private static final int[] BEEP_STREAMS = {android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.STREAM_MUSIC};

    private void muteBeep() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("speech", MODE_PRIVATE);
            if (sp.getInt("muted_mask", 0) != 0) return;
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || Build.VERSION.SDK_INT < 23) return;
            int mask = 0;
            for (int i = 0; i < BEEP_STREAMS.length; i++) {
                try {
                    if (am.isStreamMute(BEEP_STREAMS[i])) continue;
                    am.adjustStreamVolume(BEEP_STREAMS[i], android.media.AudioManager.ADJUST_MUTE, 0);
                    mask |= (1 << i);
                } catch (Throwable ignored) {}
            }
            if (mask != 0) sp.edit().putInt("muted_mask", mask).commit();
        } catch (Throwable ignored) {}
    }

    private void unmuteBeep() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("speech", MODE_PRIVATE);
            int mask = sp.getInt("muted_mask", 0);
            if (mask == 0) return;
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= 23) {
                for (int i = 0; i < BEEP_STREAMS.length; i++) {
                    if ((mask & (1 << i)) == 0) continue;
                    try { am.adjustStreamVolume(BEEP_STREAMS[i], android.media.AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignored) {}
                }
            }
            sp.edit().putInt("muted_mask", 0).commit();
        } catch (Throwable ignored) {}
    }

    private void restartSpeechSoon(long delay) {
        speechHandler.removeCallbacksAndMessages(null);
        if (speechWanted) speechHandler.postDelayed(this::beginSpeech, delay);
    }

    private void stopSpeechInternal() {
        speechWanted = false;
        speechHandler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        unmuteBeep();
    }

    private String firstResult(Bundle b) {
        if (b == null) return "";
        java.util.ArrayList<String> l = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return (l == null || l.isEmpty()) ? "" : l.get(0);
    }

    private final android.speech.RecognitionListener speechListener = new android.speech.RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { speechErrors = 0; jsCall("window.qcompSpeechState&&qcompSpeechState('listening')"); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rms) {
            long now = System.currentTimeMillis();
            if (now - lastRmsSent < 100) return;
            lastRmsSent = now;
            float level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
            jsCall("window.qcompSpeechLevel&&qcompSpeechLevel(" + level + ")");
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle partialResults) {
            String t = firstResult(partialResults);
            if (!t.isEmpty()) jsCall("window.qcompSpeechResult&&qcompSpeechResult(" + JSONObject.quote(t) + ",false)");
        }
        @Override public void onResults(Bundle results) {
            String t = firstResult(results);
            if (!t.isEmpty()) jsCall("window.qcompSpeechResult&&qcompSpeechResult(" + JSONObject.quote(t) + ",true)");
            jsCall("window.qcompSpeechEnd&&qcompSpeechEnd()");
            restartSpeechSoon(150);
        }
        @Override public void onError(int error) {
            jsCall("window.qcompSpeechEnd&&qcompSpeechEnd()");
            if (error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                speechWanted = false;
                jsCall("window.qcompSpeechState&&qcompSpeechState('denied')");
                return;
            }
            if (error == android.speech.SpeechRecognizer.ERROR_NETWORK
                    || error == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == android.speech.SpeechRecognizer.ERROR_SERVER) {
                if (++speechErrors >= 5) {
                    stopSpeechInternal();
                    jsCall("window.qcompSpeechState&&qcompSpeechState('unavailable')");
                    return;
                }
                restartSpeechSoon(800);
                return;
            }
            restartSpeechSoon(error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 800 : 200);
        }
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    @Override
    protected void onStop() {
        speechResume = speechWanted;
        stopSpeechInternal();
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        unmuteBeep(); // recovers if the app was killed while muted
        if (speechResume) { speechResume = false; speechWanted = true; beginSpeech(); }
    }

    @Override
    protected void onDestroy() {
        stopSpeechInternal();
        if (assetPackPrompt != null) assetPackPrompt.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopUsageHeartbeat(true);
        super.onPause();
        if (specialLaunched) specialWentAway = true;
        if (webView != null) webView.evaluateJavascript(
            "if(window.pauseReligiousStatuses){window.pauseReligiousStatuses();}if(window.saveView){saveView();}", null);
    }

    private String stableUsageParticipantKey() {
        try {
            String androidId = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) return "";
            return java.util.UUID.nameUUIDFromBytes(
                ("rafeeq-almuslim-device:" + androidId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ).toString();
        } catch (Exception e) { return ""; }
    }

    private void startUsageHeartbeat() {
        if (usageHeartbeatRunning) return;
        usageSessionKey = java.util.UUID.randomUUID().toString();
        usageHeartbeatRunning = true;
        usageHandler.removeCallbacks(usageHeartbeat);
        sendUsageHeartbeat(false);
        usageHandler.postDelayed(usageHeartbeat, 5000);
    }

    private void stopUsageHeartbeat(boolean finish) {
        if (!usageHeartbeatRunning) return;
        usageHeartbeatRunning = false;
        usageHandler.removeCallbacks(usageHeartbeat);
        if (finish) sendUsageHeartbeat(true);
    }

    private void sendUsageHeartbeat(boolean end) {
        final String participant = stableUsageParticipantKey();
        final String session = usageSessionKey;
        if (participant.isEmpty() || session.isEmpty()) return;
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                java.net.URL url = new java.net.URL(USAGE_SUPABASE_BASE + "/rest/v1/rpc/app_usage_heartbeat");
                c = (java.net.HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", USAGE_SUPABASE_KEY);
                c.setRequestProperty("Authorization", "Bearer " + USAGE_SUPABASE_KEY);
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("p_participant_key", participant);
                body.put("p_session_key", session);
                body.put("p_end", end);
                byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                c.getOutputStream().write(bytes);
                int code = c.getResponseCode();
                java.io.InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                if (in != null) in.close();
            } catch (Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }


    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) { return true; }
    }

    private void handleWalletPushIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("open_wallet", false)) return;
        intent.removeExtra("open_wallet");
        if (webView != null) webView.postDelayed(() -> webView.evaluateJavascript("if(typeof openQuranWallet==='function'){openQuranWallet();}", null), 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCacheMode();
        startUsageHeartbeat();
        WalletPushTokenRegistrar.register(this);
        if (assetPackPrompt != null) assetPackPrompt.onResume();
        if (remotePatchManager != null && System.currentTimeMillis() - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) checkRemoteUpdate();
        if (webView != null) webView.postDelayed(() -> webView.evaluateJavascript(
            "if(window.updateOverlayToggle){window.updateOverlayToggle();}", null), 400);
        if (specialLaunched && specialWentAway) {
            specialLaunched = false; specialWentAway = false;
            requestSpecialAccessOnce();
        }
    }

    private void requestNextPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        // Ask for location on the first launch. If declined, the app keeps using
        // the saved/manual city (Dahr by default) without blocking any feature.
        requestLocationPermission();
    }

    private void requestLocationPermission() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            refreshWebPrayerTimes();
            permissionsDone = true;
            requestSpecialAccessOnce();
            return;
        }
        requestPermissions(new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_PERMISSION_REQUEST);
    }

    // Overlay + battery exemption are asked once, so the 5-minute reminders work out of the box.
    private boolean permissionsDone = false;
    private boolean specialLaunched = false, specialWentAway = false;
    private void requestSpecialAccessOnce() {
        if (!permissionsDone || Build.VERSION.SDK_INT < 23 || specialLaunched) return;
        android.content.SharedPreferences sp = getSharedPreferences("setup", MODE_PRIVATE);
        try {
            if (!sp.getBoolean("overlay_asked", false) && !Settings.canDrawOverlays(this)) {
                sp.edit().putBoolean("overlay_asked", true).apply();
                specialLaunched = true; specialWentAway = false;
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
                return;
            }
            if (!sp.getBoolean("battery_asked", false)) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    sp.edit().putBoolean("battery_asked", true).apply();
                    specialLaunched = true; specialWentAway = false;
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                }
            }
        } catch (Exception e) { specialLaunched = false; /* some vendors block these screens; ignore */ }
    }

    private void refreshWebPrayerTimes() {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(
            "if(window.refreshPrayerTimes){window.refreshPrayerTimes();}", null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SPEECH_PERMISSION_REQUEST) {
            speechAwaitingPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginSpeech();
            else { speechWanted = false; jsCall("window.qcompSpeechState&&qcompSpeechState('denied')"); }
        } else if (requestCode == MIC_PERMISSION_REQUEST) {
            android.webkit.PermissionRequest req = pendingMicRequest; pendingMicRequest = null;
            if (req != null) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    req.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else req.deny();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            requestLocationPermission();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            // Either choice is valid: granted uses GPS/network location; denied falls back to Dahr/manual city.
            refreshWebPrayerTimes();
            permissionsDone = true;
            requestSpecialAccessOnce();
        }
    }

    public class ReminderBridge {
        private final Context context;
        ReminderBridge(Context context) { this.context = context.getApplicationContext(); }

        // Stable per-device UUID: survives app uninstall/reinstall on the same Android device.
        @JavascriptInterface
        public String getStableParticipantKey() {
            try {
                String androidId = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                if (androidId == null || androidId.trim().isEmpty()) return "";
                return java.util.UUID.nameUUIDFromBytes(
                    ("rafeeq-almuslim-device:" + androidId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ).toString();
            } catch (Exception e) { return ""; }
        }

        private void reportStatusResult(String message, boolean error) {
            jsCall("window.statusActionResult&&window.statusActionResult("
                + JSONObject.quote(message) + "," + error + ")");
        }

        @JavascriptInterface
        public void downloadReciterMp3(String audioUrl, String fileName) {
            runOnUiThread(() -> {
                try {
                    if (audioUrl == null || !(audioUrl.startsWith("https://") || audioUrl.startsWith("http://"))) {
                        jsCall("window.reciterDownloadResult&&window.reciterDownloadResult(false,'رابط التنزيل غير صالح.')");
                        return;
                    }
                    String safeName = (fileName == null ? "Quran-Recitation.mp3" : fileName)
                            .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".mp3")) safeName += ".mp3";
                    DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(audioUrl));
                    request.setTitle("تنزيل تلاوة القرآن");
                    request.setDescription("جاري حفظ ملف MP3 في مجلد الموسيقى / رفيق المسلم");
                    request.setMimeType("audio/mpeg");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "RafeeqAlMuslim/" + safeName);
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(true);
                    manager.enqueue(request);
                    jsCall("window.reciterDownloadResult&&window.reciterDownloadResult(true,'بدأ التنزيل. ستجد الملف في الاستوديو أو مشغل الموسيقى داخل مجلد رفيق المسلم.')");
                } catch (Exception e) {
                    jsCall("window.reciterDownloadResult&&window.reciterDownloadResult(false,'تعذر بدء التنزيل. تحقق من الاتصال ومساحة الهاتف.')");
                }
            });
        }

        @JavascriptInterface
        public void saveStatusImage(String dataUrl, String fileName, boolean share, String caption) {
            if (dataUrl == null || !dataUrl.startsWith("data:image/jpeg;base64,")) {
                reportStatusResult("تعذر تجهيز الصورة للمشاركة أو الحفظ.", true);
                return;
            }
            final byte[] imageBytes;
            try {
                imageBytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                reportStatusResult("بيانات الصورة غير صالحة.", true);
                return;
            }
            if (imageBytes.length == 0 || imageBytes.length > 8 * 1024 * 1024) {
                reportStatusResult("حجم الصورة غير صالح أو يتجاوز الحد المسموح.", true);
                return;
            }
            new Thread(() -> {
                try {
                    String safeName = (fileName == null ? "Rafeeq-Status.jpg" : fileName)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".jpg")) safeName += ".jpg";
                    Uri savedUri;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/RafeeqAlMuslim");
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);
                        savedUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (savedUri == null) throw new IllegalStateException("تعذر إنشاء ملف الصورة");
                        try (OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                            if (out == null) throw new IllegalStateException("تعذر كتابة ملف الصورة");
                            out.write(imageBytes);
                        }
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(savedUri, ready, null, null);
                    } else {
                        File externalPictures = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                        if (externalPictures == null) throw new IllegalStateException("مساحة التخزين غير متاحة");
                        File pictures = new File(externalPictures, "RafeeqAlMuslim");
                        if (!pictures.exists() && !pictures.mkdirs()) throw new IllegalStateException("تعذر إنشاء مجلد الصور");
                        File file = new File(pictures, safeName);
                        try (FileOutputStream out = new FileOutputStream(file)) { out.write(imageBytes); }
                        savedUri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", file);
                    }
                    final Uri uri = savedUri;
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this,
                            share ? "تم تجهيز الحالة للمشاركة" : "تم حفظ الحالة على الجهاز",
                            android.widget.Toast.LENGTH_LONG).show();
                        if (share) {
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("image/jpeg");
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.setClipData(ClipData.newUri(getContentResolver(), "حالة دينية", uri));
                            if (caption != null && !caption.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, caption);
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            Intent chooser = Intent.createChooser(send, "مشاركة الحالة");
                            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(chooser);
                        }
                        reportStatusResult(share ? "تم تجهيز الصورة؛ اختر واتساب لإكمال المشاركة." : "تم حفظ الصورة في مجلد الصور / RafeeqAlMuslim.", false);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this,
                            "تعذر حفظ الحالة أو مشاركتها", android.widget.Toast.LENGTH_LONG).show();
                        reportStatusResult("تعذر حفظ الصورة أو فتح المشاركة.", true);
                    });
                }
            }, "religious-status-save").start();
        }

        @JavascriptInterface
        public void saveStatusVideo(String videoUrl, String fileName, boolean share, String caption) {
            if (videoUrl == null) {
                reportStatusResult("رابط الفيديو غير متاح.", true);
                return;
            }
            Uri source = Uri.parse(videoUrl);
            String path = source.getPath();
            if (!"https".equalsIgnoreCase(source.getScheme())
                    || !"upload.wikimedia.org".equalsIgnoreCase(source.getHost())
                    || path == null
                    || !path.startsWith("/wikipedia/commons/transcoded/")
                    || !path.endsWith(".360p.mpeg4.mov")) {
                reportStatusResult("رابط تنزيل الفيديو غير صالح.", true);
                return;
            }

            new Thread(() -> {
                Uri savedUri = null;
                HttpURLConnection connection = null;
                try {
                    String safeName = (fileName == null ? "Rafeeq-Status.mov" : fileName)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".mov")) safeName += ".mov";
                    URL url = new URL(videoUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "RafeeqAlMuslim/1.0 (Wikimedia Commons open media)");
                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK)
                        throw new IllegalStateException("تعذر تنزيل الفيديو من المصدر");
                    String contentType = connection.getContentType();
                    if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("video/")
                            && !contentType.toLowerCase(Locale.ROOT).contains("octet-stream"))
                        throw new IllegalStateException("صيغة الفيديو غير متوقعة");
                    int contentLength = connection.getContentLength();
                    if (contentLength > 25 * 1024 * 1024)
                        throw new IllegalStateException("حجم الفيديو أكبر من الحد الآمن للتنزيل");

                    OutputStream output;
                    File legacyFile = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
                        values.put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime");
                        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/RafeeqAlMuslim");
                        values.put(MediaStore.Video.Media.IS_PENDING, 1);
                        savedUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                        if (savedUri == null) throw new IllegalStateException("تعذر إنشاء ملف الفيديو");
                        output = getContentResolver().openOutputStream(savedUri);
                    } else {
                        File moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                        if (moviesRoot == null) throw new IllegalStateException("مساحة التخزين غير متاحة");
                        File folder = new File(moviesRoot, "RafeeqAlMuslim");
                        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("تعذر إنشاء مجلد الفيديو");
                        legacyFile = new File(folder, safeName);
                        output = new FileOutputStream(legacyFile);
                        savedUri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", legacyFile);
                    }
                    if (output == null) throw new IllegalStateException("تعذر الكتابة إلى ملف الفيديو");
                    long total = 0;
                    try (InputStream input = connection.getInputStream(); OutputStream out = output) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 25L * 1024L * 1024L)
                                throw new IllegalStateException("حجم الفيديو تجاوز الحد الآمن للتنزيل");
                            out.write(buffer, 0, count);
                        }
                        out.flush();
                    }
                    if (total == 0) throw new IllegalStateException("ملف الفيديو فارغ");
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(savedUri, ready, null, null);
                    }
                    saveStatusCreditFile(safeName, caption);
                    final Uri finalUri = savedUri;
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this,
                            share ? "تم تجهيز الفيديو؛ أكّد الإرسال في واتساب" : "تم حفظ الفيديو وبيانات مصدره",
                            android.widget.Toast.LENGTH_LONG).show();
                        if (share) shareStatusVideo(finalUri, caption);
                        reportStatusResult(share ? "تم تجهيز الفيديو للمشاركة؛ أكّد الإرسال في واتساب." : "تم حفظ الفيديو وبيانات المصدر.", false);
                    });
                } catch (Exception e) {
                    if (savedUri != null && Build.VERSION.SDK_INT >= 29) {
                        try { getContentResolver().delete(savedUri, null, null); } catch (Exception ignored) {}
                    }
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this,
                            "تعذر تنزيل الفيديو. تحقق من الاتصال ثم أعد المحاولة.",
                            android.widget.Toast.LENGTH_LONG).show();
                        reportStatusResult("تعذر تنزيل الفيديو. تحقق من الاتصال ثم أعد المحاولة.", true);
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "religious-status-video").start();
        }

        @JavascriptInterface
        public void saveStatusAudio(String audioUrl, String fileName) {
            if (audioUrl == null) {
                reportStatusResult("رابط التلاوة غير متاح.", true);
                return;
            }
            Uri source = Uri.parse(audioUrl);
            String path = source.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(source.getScheme())
                    || !"upload.wikimedia.org".equalsIgnoreCase(source.getHost())
                    || path == null || !path.startsWith("/wikipedia/commons/")
                        || !(lowerPath.endsWith(".ogg") || lowerPath.endsWith(".oga")
                        || lowerPath.endsWith(".wav") || lowerPath.endsWith(".mp3"))) {
                reportStatusResult("رابط التلاوة غير صالح.", true);
                return;
            }
            final String mime = lowerPath.endsWith(".mp3") ? "audio/mpeg"
                : lowerPath.endsWith(".wav") ? "audio/wav" : "audio/ogg";
            new Thread(() -> {
                Uri savedUri = null;
                HttpURLConnection connection = null;
                try {
                    String safeName = (fileName == null ? "Rafeeq-Quran-Recitation" : fileName)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                    String ext = lowerPath.endsWith(".mp3") ? ".mp3"
                        : lowerPath.endsWith(".wav") ? ".wav"
                        : lowerPath.endsWith(".oga") ? ".oga" : ".ogg";
                    if (!safeName.toLowerCase(Locale.ROOT).endsWith(ext)) safeName += ext;
                    connection = (HttpURLConnection) new URL(audioUrl).openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "RafeeqAlMuslim/1.0 (Wikimedia Commons open media)");
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                        throw new IllegalStateException("تعذر تنزيل التلاوة من المصدر");
                    String contentType = connection.getContentType();
                    if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("audio/")
                            && !contentType.toLowerCase(Locale.ROOT).contains("ogg"))
                        throw new IllegalStateException("صيغة ملف التلاوة غير متوقعة");
                    if (connection.getContentLength() > 12 * 1024 * 1024)
                        throw new IllegalStateException("حجم التلاوة أكبر من الحد الآمن للتنزيل");
                    OutputStream output;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeName);
                        values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC + "/RafeeqAlMuslim");
                        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                        savedUri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                        if (savedUri == null) throw new IllegalStateException("تعذر إنشاء ملف التلاوة");
                        output = getContentResolver().openOutputStream(savedUri);
                    } else {
                        File musicRoot = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                        if (musicRoot == null) throw new IllegalStateException("مساحة التخزين غير متاحة");
                        File folder = new File(musicRoot, "RafeeqAlMuslim");
                        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("تعذر إنشاء مجلد التلاوات");
                        File file = new File(folder, safeName);
                        output = new FileOutputStream(file);
                        savedUri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", file);
                    }
                    if (output == null) throw new IllegalStateException("تعذر حفظ ملف التلاوة");
                    long total = 0;
                    try (InputStream input = connection.getInputStream(); OutputStream out = output) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 12L * 1024L * 1024L)
                                throw new IllegalStateException("حجم التلاوة تجاوز الحد الآمن للتنزيل");
                            out.write(buffer, 0, count);
                        }
                        out.flush();
                    }
                    if (total == 0) throw new IllegalStateException("ملف التلاوة فارغ");
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
                        getContentResolver().update(savedUri, ready, null, null);
                    }
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        "تم حفظ التلاوة في مجلد الموسيقى / RafeeqAlMuslim",
                        android.widget.Toast.LENGTH_LONG).show());
                    reportStatusResult("تم حفظ التلاوة في مجلد الموسيقى / RafeeqAlMuslim.", false);
                } catch (Exception e) {
                    if (savedUri != null && Build.VERSION.SDK_INT >= 29) {
                        try { getContentResolver().delete(savedUri, null, null); } catch (Exception ignored) {}
                    }
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this,
                            "تعذر تنزيل التلاوة. تحقق من الاتصال ثم أعد المحاولة.",
                            android.widget.Toast.LENGTH_LONG).show();
                        reportStatusResult("تعذر تنزيل التلاوة. تحقق من الاتصال ثم أعد المحاولة.", true);
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "religious-status-audio").start();
        }

        private void saveStatusCreditFile(String videoName, String caption) {
            if (caption == null || caption.isEmpty()) return;
            String creditName = videoName.replaceAll("(?i)\\.mov$", "-source.txt");
            String text = caption.length() > 6000 ? caption.substring(0, 6000) : caption;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, creditName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/RafeeqAlMuslim");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                } else {
                    File moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    if (moviesRoot == null) return;
                    File folder = new File(moviesRoot, "RafeeqAlMuslim");
                    if (!folder.exists() && !folder.mkdirs()) return;
                    try (FileOutputStream out = new FileOutputStream(new File(folder, creditName))) {
                        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) { }
        }

        private void shareStatusVideo(Uri uri, String caption) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/quicktime");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(getContentResolver(), "حالة فيديو", uri));
            if (caption != null && !caption.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, caption);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (String packageName : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
                try {
                    Intent whatsapp = new Intent(send);
                    whatsapp.setPackage(packageName);
                    startActivity(whatsapp);
                    return;
                } catch (Exception ignored) { }
            }
            try {
                Intent chooser = Intent.createChooser(send, "مشاركة الحالة عبر واتساب أو تطبيق آخر");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            } catch (Exception ignored) {
                android.widget.Toast.makeText(MainActivity.this,
                    "تم حفظ الفيديو. افتح واتساب لاختياره يدوياً.", android.widget.Toast.LENGTH_LONG).show();
            }
        }

        @JavascriptInterface
        public void setInterfaceDesign(boolean modern) {
            context.getSharedPreferences("interface_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("modern", modern).apply();
        }
        @JavascriptInterface
        public void setAdhanEnabled(boolean enabled) {
            context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
        }

        @JavascriptInterface
        public void configureSalatSound(boolean enabled, int minutes) {
            SalatSoundReceiver.configure(context, enabled, minutes);
        }

        @JavascriptInterface
        public String getSalatSoundConfig() {
            return SalatSoundReceiver.getConfig(context);
        }

        /** Returns false until salat_nabi.mp3 has been downloaded into private app storage. */
        @JavascriptInterface
        public boolean testSalatSound() {
            return SalatSoundReceiver.play(context, null);
        }

        @JavascriptInterface
        public void setAdhanVoice(String voice) {
            if (voice == null || !("original".equals(voice) || "qatami".equals(voice))) return;
            context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putString("voice", voice).apply();
        }

        @JavascriptInterface
        public boolean isInternetAvailable() {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network network = cm.getActiveNetwork();
                    if (network == null) return false;
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            runOnUiThread(() -> MainActivity.this.requestLocationPermission());
        }

        @JavascriptInterface
        public void requestCurrentLocation() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return; // permission request is already handled by the WebView button flow
                }
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) requestFreshLocation(lm);
            });
        }

        @JavascriptInterface
        public String getLocation() {
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
                for (String provider : providers) {
                    try {
                        Location l = lm.getLastKnownLocation(provider);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Exception ignored) {}
                }
                android.content.SharedPreferences lp = getSharedPreferences("loc_cache", MODE_PRIVATE);
                if (best != null) {
                    String v = String.format(Locale.US, "%.6f,%.6f", best.getLatitude(), best.getLongitude());
                    lp.edit().putString("last", v).apply();
                    return v;
                }
                // No last-known fix (common without Google services / weak GPS): ask for a fresh one
                requestFreshLocation(lm);
                return lp.getString("last", "");
            } catch (Exception e) { return ""; }
        }

        private void requestFreshLocation(final LocationManager lm) {
            if (locationRequestInProgress) return;
            locationRequestInProgress = true;
            runOnUiThread(() -> {
                try {
                    // Use any fresh cached fix first; then request a live fix from every
                    // enabled provider (GPS and network). This works on devices without
                    // Google Play location services as well.
                    Location cached = null;
                    for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                        try {
                            if (!lm.isProviderEnabled(provider)) continue;
                            Location candidate = lm.getLastKnownLocation(provider);
                            if (candidate != null && (cached == null || candidate.getTime() > cached.getTime())) cached = candidate;
                        } catch (Exception ignored) {}
                    }
                    if (cached != null && System.currentTimeMillis() - cached.getTime() < 2 * 60 * 1000L) {
                        locationRequestInProgress = false;
                        saveFreshLocation(cached);
                        return;
                    }
                    activeLocationListener = new android.location.LocationListener() {
                        @Override public void onLocationChanged(Location location) {
                            if (location == null) return;
                            locationRequestInProgress = false;
                            try { lm.removeUpdates(this); } catch (Exception ignored) {}
                            if (locationTimeout != null) locationHandler.removeCallbacks(locationTimeout);
                            saveFreshLocation(location);
                        }
                        @Override public void onProviderEnabled(String provider) {}
                        @Override public void onProviderDisabled(String provider) {}
                        @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                    };
                    boolean requested = false;
                    for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                        try {
                            if (lm.isProviderEnabled(provider)) {
                                lm.requestLocationUpdates(provider, 1000L, 0f, activeLocationListener, Looper.getMainLooper());
                                requested = true;
                            }
                        } catch (SecurityException | IllegalArgumentException ignored) {}
                    }
                    if (!requested) {
                        locationRequestInProgress = false;
                        notifyLocationUnavailable("فعّل خدمة الموقع (GPS) من إعدادات الهاتف ثم أعد المحاولة.");
                        return;
                    }
                    locationTimeout = () -> {
                        if (activeLocationListener != null) {
                            try { lm.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
                        }
                        activeLocationListener = null;
                        locationRequestInProgress = false;
                        notifyLocationUnavailable("لم يصل موقع الجهاز بعد. جرّب الوقوف قرب نافذة مع تشغيل GPS.");
                    };
                    locationHandler.postDelayed(locationTimeout, 45000L);
                } catch (SecurityException e) {
                    locationRequestInProgress = false;
                    notifyLocationUnavailable("اسمح لرفيق المسلم باستخدام الموقع من إعدادات الأذونات.");
                } catch (Exception e) {
                    locationRequestInProgress = false;
                    notifyLocationUnavailable("تعذر تشغيل خدمة تحديد الموقع. تحقق من إعدادات الموقع.");
                }
            });
        }

        private void notifyLocationUnavailable(String message) {
            if (webView != null) {
                final String safe = JSONObject.quote(message);
                webView.post(() -> webView.evaluateJavascript(
                    "if(window.onNativeLocationError){window.onNativeLocationError(" + safe + ");}", null));
            }
        }

        private void saveFreshLocation(Location location) {
            if (location == null) return;
            String value = String.format(Locale.US, "%.6f,%.6f", location.getLatitude(), location.getLongitude());
            getSharedPreferences("loc_cache", MODE_PRIVATE).edit().putString("last", value).apply();
            refreshWebPrayerTimes();
        }

        @JavascriptInterface
        public String getPrayerCalendar(int year, int month, double latitude, double longitude) {
            android.content.SharedPreferences sp = getSharedPreferences("prayer_cache", MODE_PRIVATE);
            String key = year + "-" + month + "-" + String.format(Locale.US, "%.2f,%.2f", latitude, longitude);
            String cached = sp.getString(key, null);
            if (cached != null) return cached;
            String fresh = fetchPrayerCalendar(year, month, latitude, longitude);
            if (fresh != null) {
                sp.edit().putString(key, fresh).apply();
                return fresh;
            }
            // Offline: reuse any cached copy of the same month (nearest saved location)
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                if (e.getKey().startsWith(year + "-" + month + "-") && e.getValue() instanceof String) return (String) e.getValue();
            }
            return "";
        }

        private String fetchPrayerCalendar(int year, int month, double latitude, double longitude) {
            HttpURLConnection connection = null;
            try {
                String address = "https://api.aladhan.com/v1/calendar/" + year + "/" + month
                    + "?latitude=" + latitude + "&longitude=" + longitude + "&method=4";
                URL url = new URL(address);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
                reader.close();
                String out = result.toString();
                JSONObject o = new JSONObject(out);
                return o.optInt("code") == 200 ? out : null;
            } catch (Exception e) { return null; }
            finally { if (connection != null) connection.disconnect(); }
        }

        @JavascriptInterface
        public void configureDailyAdhkar(boolean enabled, int morningHour, int morningMinute,
                int eveningHour, int eveningMinute) {
            ReminderReceiver.configureDailyAdhkar(context, enabled, morningHour, morningMinute,
                eveningHour, eveningMinute);
        }

        @JavascriptInterface
        public void configureDailyAdhkarV2(boolean enabled, int morningHour, int morningMinute,
                int eveningHour, int eveningMinute, int sleepHour, int sleepMinute) {
            ReminderReceiver.configureDailyAdhkar(context, enabled, morningHour, morningMinute,
                eveningHour, eveningMinute, sleepHour, sleepMinute);
        }

        @JavascriptInterface
        public void saveFastingSchedule(String json) {
            ReminderReceiver.saveAndScheduleFasting(context, json);
        }

        @JavascriptInterface
        public void configureDailyQuranReminder(boolean enabled, int hour, int minute) {
            ReminderReceiver.configureDailyQuranReminder(context, enabled, hour, minute);
        }

        @JavascriptInterface
        public void configureRamadanNotification(boolean enabled, int hour, int minute, long ramadanStartMillis) {
            ReminderReceiver.configureRamadanNotification(context, enabled, hour, minute, ramadanStartMillis);
        }

        @JavascriptInterface
        public void schedule(long triggerAtMillis, String title, String message, int id) {
            scheduleInternal(triggerAtMillis, title, message, id, false, false, false);
        }

        @JavascriptInterface
        public void scheduleDhikr(long triggerAtMillis, String title, String message, int id) {
            scheduleInternal(triggerAtMillis, title, message, id, false, false, true);
        }

        @JavascriptInterface
        public void startDhikrRepeat(long startMillis, long intervalMillis) {
            context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit()
                .putBoolean("repeat_on", true).putLong("repeat_interval", intervalMillis).apply();
            ReminderReceiver.armDhikr(context, startMillis, intervalMillis);
        }

        @JavascriptInterface
        public void setPrayerSchedule(String json) {
            context.getSharedPreferences("prayers", Context.MODE_PRIVATE).edit().putString("schedule", json).apply();
            PrayerCountdown.update(context);
        }

        @JavascriptInterface
        public void setPrayerCountdownEnabled(boolean on) {
            context.getSharedPreferences("prayers", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
            PrayerCountdown.update(context);
        }

        @JavascriptInterface
        public String getDhikrStatus() {
            android.content.SharedPreferences sp = context.getSharedPreferences("dhikr", Context.MODE_PRIVATE);
            return sp.getBoolean("repeat_on", false) + "|" + sp.getLong("last_fire", 0) + "|" + sp.getLong("next_at", 0);
        }

        @JavascriptInterface
        public void stopDhikrRepeat() {
            context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit().putBoolean("repeat_on", false).apply();
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(context, ReminderReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, DHIKR_REPEAT_ID, intent, flags);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
                // Play inside the app (LectureActivity). Started from the Activity context, not the application context.
                try {
                    Intent i = new Intent(MainActivity.this, LectureActivity.class);
                    i.putExtra("url", url);
                    MainActivity.this.startActivity(i);
                } catch (Throwable t) {
                    try {
                        MainActivity.this.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public boolean speechAvailable() {
            try { return android.speech.SpeechRecognizer.isRecognitionAvailable(MainActivity.this); } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void startSpeech() {
            runOnUiThread(() -> { speechWanted = true; speechErrors = 0; beginSpeech(); });
        }

        @JavascriptInterface
        public void stopSpeech() {
            runOnUiThread(MainActivity.this::stopSpeechInternal);
        }

        @JavascriptInterface
        public void restartSpeech() {
            runOnUiThread(() -> {
                if (!speechWanted) return;
                if (recognizer != null) { try { recognizer.cancel(); } catch (Throwable ignored) {} }
                restartSpeechSoon(250);
            });
        }

        @JavascriptInterface
        public void setReaderFullscreen(final boolean on) {
            runOnUiThread(() -> {
                View d = getWindow().getDecorView();
                int immersive = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                // Reader content stays inside the app window; system bars remain visible.
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (Build.VERSION.SDK_INT >= 30) {
                    getWindow().setDecorFitsSystemWindows(true);
                    android.view.WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.show(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                    getWindow().setAttributes(lp);
                }
                int f = d.getSystemUiVisibility();
                d.setSystemUiVisibility(f & ~immersive);
            });
        }

        @JavascriptInterface
        public void setNightMode(final boolean on) {
            runOnUiThread(() -> {
                int bg = on ? Color.rgb(9, 26, 48) : Color.rgb(246, 241, 230);
                int nav = on ? Color.rgb(8, 20, 38) : Color.rgb(236, 228, 210);
                getWindow().setStatusBarColor(bg);
                getWindow().setNavigationBarColor(nav);
                if (webView != null) webView.setBackgroundColor(bg);
                View d = getWindow().getDecorView();
                int f = d.getSystemUiVisibility();
                if (Build.VERSION.SDK_INT >= 23) {
                    f = on ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    f = on ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                d.setSystemUiVisibility(f);
            });
        }

        @JavascriptInterface
        public boolean hasOverlayPermission() {
            return OverlayDhikr.canDraw(context);
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + MainActivity.this.getPackageName()));
                MainActivity.this.startActivity(i);
            });
        }

        @JavascriptInterface
        public void setDhikrOverlay(boolean on) {
            context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit().putBoolean("overlay", on).apply();
        }

        @JavascriptInterface
        public void cancelReminder(int id) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(context, ReminderReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, id, intent, flags);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }

        @JavascriptInterface
        public void scheduleAdhan(long triggerAtMillis, String title, String message, int id, boolean adhan) {
            scheduleInternal(triggerAtMillis, title, message, id, true, adhan, false);
        }

        @JavascriptInterface
        public void testAdhan() {
            runOnUiThread(() -> {
                try { AdhanService.start(MainActivity.this, "🕌 تجربة الأذان", "هذه تجربة لصوت الأذان", 7099); }
                catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void stopAdhan() {
            AdhanService.stop(context);
        }

        private void scheduleInternal(long triggerAtMillis, String title, String message, int id, boolean prayer, boolean adhan, boolean dhikr) {
            ReminderReceiver.schedule(context, triggerAtMillis, title, message, id, prayer, adhan, dhikr);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
