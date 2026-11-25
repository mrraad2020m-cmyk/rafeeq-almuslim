package com.raad.monasabatna;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.media.AudioManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays a lecture full screen, landscape, inside the app.
 * If the link is a search, the first real video of the search is found in the background and played directly,
 * so the person never sees a YouTube page. Falls back to the normal page if the video cannot be found.
 */
public class LectureActivity extends Activity {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private WebView web;
    private FrameLayout root;
    private TextView loading;
    private TextView altBtn;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String sourceUrl;
    private String videoId;
    private volatile boolean destroyed = false;
    private volatile boolean embedOk = true;
    private boolean embedMode = false;
    private boolean fellBack = false;
    private volatile boolean started = false;
    private int startAt = 0;
    private boolean customUi = true;
    private TextView ctlBtn;
    private int stallRetries = 0;
    private volatile int curPos = 0;
    private volatile int curDur = 0;

    // Reports the playback position (normal YouTube page fallback) so it can be resumed later.
    private static final String PROGRESS_JS =
        "(function(){if(window.__raadProg)return;window.__raadProg=1;setInterval(function(){try{"
        + "var v=document.querySelector('video');if(v&&!v.paused&&isFinite(v.duration)&&v.currentTime>0){"
        + "LecBridge.progress(Math.floor(v.currentTime),Math.floor(v.duration));}}catch(e){}},3000);})();";

    // ---- Resume playback: remember where each episode stopped.
    private String resumeKey() { return "p:" + sourceUrl; }

    private int savedPos() {
        try { return getSharedPreferences("lecture_resume", MODE_PRIVATE).getInt(resumeKey(), 0); }
        catch (Throwable t) { return 0; }
    }

    private void saveResume(int secs, int dur) {
        if (sourceUrl == null || secs < 10) return;
        SharedPreferences.Editor ed = getSharedPreferences("lecture_resume", MODE_PRIVATE).edit();
        if (dur > 0 && secs >= dur - 20) ed.remove(resumeKey());   // finished: next time start from the beginning
        else ed.putInt(resumeKey(), secs);
        ed.apply();
    }

    private void saveFromWeb() {
        if (web == null || destroyed) return;
        if (curPos > 0) saveResume(curPos, curDur);
        String js = (embedMode && !fellBack)
            ? "(function(){try{return Math.floor(player.getCurrentTime())+','+Math.floor(player.getDuration());}catch(e){return '';}})()"
            : "(function(){var v=document.querySelector('video');return v?Math.floor(v.currentTime)+','+Math.floor(v.duration||0):'';})()";
        try {
            web.evaluateJavascript(js, value -> {
                try {
                    String v = value == null ? "" : value.replace("\"", "");
                    String[] a = v.split(",");
                    if (a.length == 2) saveResume(Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static String fmtTime(int x) {
        int h = x / 3600, m = (x % 3600) / 60, sec = x % 60;
        return (h > 0 ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private static final String UNMUTE_JS =
        "(function(){var n=0;var t=setInterval(function(){n++;var v=document.querySelector('video');"
        + "if(v){try{v.muted=false;if(n<=8){v.volume=0.5;}if(v.paused){v.play();}}catch(e){}}"
        + "var b=document.querySelector('.ytp-unmute,.ytp-unmute-inner,button[aria-label*=\"Unmute\"],button[aria-label*=\"إلغاء كتم\"]');"
        + "if(b){try{b.click();}catch(e){}}"
        + "if(n>40){clearInterval(t);}},500);})();";

    // Makes the video fill the whole (landscape) screen on the normal YouTube page.
    // Keep YouTube in a slightly reduced, centered viewing frame by default.
    // This is equivalent to leaving the oversized player view, without simulating taps
    // on YouTube's controls (which change between app/WebView versions).
    private static final String FILL_JS =
        "(function(){var n=0;var t=setInterval(function(){n++;var v=document.querySelector('video');if(v&&document.body"
        + "&&!window.__raadCtl){window.__raadCtl=1;var s=document.createElement('style');s.textContent='html,body{width:100%!important;height:100%!important;min-height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important}#movie_player,.html5-video-player{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;margin:0!important;transform:none!important}#movie_player video,.html5-video-player video,video{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;background:#000!important}';document.head.appendChild(s);}if(n>80){clearInterval(t);}},500);})();";

    // Enters YouTube's real full-screen mode on the normal watch page (same as pressing the
    // "two opposite arrows" button). A script click is not a user gesture, so the page only reports
    // where the button is and the app sends a real touch there (see LecBridge.tapAt).
    private static final String FS_JS =
        "(function(){if(window.__raadFs)return;window.__raadFs=1;var n=0,taps=0,reveal=0,last=0;"
        + "function inFs(){return !!(document.fullscreenElement||document.webkitFullscreenElement);}"
        + "function find(){return document.querySelector('.fullscreen-icon,button.ytp-fullscreen-button,.ytp-fullscreen-button,"
        + "button[aria-label*=\"ملء الشاشة\"],button[aria-label*=\"Full screen\" i],button[aria-label*=\"Fullscreen\" i]');}"
        + "function spot(el){var r=el.getBoundingClientRect();if(r.width<2||r.height<2)return null;"
        + "var cx=r.left+r.width/2,cy=r.top+r.height/2;var t=document.elementFromPoint(cx,cy);"
        + "return(t&&el.contains(t))?[cx,cy]:null;}"
        // Any real touch (the user's, or the one the app sends) is a valid gesture: click the button then, even if hidden.
        + "function onG(){if(inFs()||taps>6)return;var el=find();if(el){taps++;try{el.click();}catch(e){}}}"
        + "document.addEventListener('touchend',function(){setTimeout(onG,80);},true);"
        + "var t=setInterval(function(){n++;if(inFs()||n>90||taps>6){clearInterval(t);return;}"
        + "if(Date.now()-last<1500)return;var d=window.devicePixelRatio||1;"
        + "var el=find();var p=el?spot(el):null;"
        + "if(p){last=Date.now();taps++;LecBridge.tapAt(p[0]*d,p[1]*d);}"
        + "else if(reveal<8){reveal++;last=Date.now();var v=document.querySelector('video');"
        + "var r=v?v.getBoundingClientRect():null;var cx=(r&&r.width>10)?r.left+r.width/2:window.innerWidth/2;"
        + "var cy=(r&&r.height>10)?r.top+r.height/2:window.innerHeight/2;LecBridge.tapAt(cx*d,cy*d);}"
        + "},600);})();";

    // Ad skipper: runs in the page and (same-origin) inside the embedded player iframe.
    private static final String AD_JS =
        "(function(){if(window.__raadAd)return;window.__raadAd=1;"
        + "function run(d){try{"
        + "var p=d.querySelector('.html5-video-player,#movie_player');"
        + "var ad=p&&p.classList.contains('ad-showing');"
        + "var sk=d.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');"
        + "if(sk){sk.click();}"
        + "if(ad){var v=d.querySelector('video');"
        + "if(v&&isFinite(v.duration)&&v.duration>0){v.currentTime=v.duration;}}"
        + "var c=d.querySelector('.ytp-ad-overlay-close-button');if(c){c.click();}"
        + "var hs=d.querySelectorAll('.ytp-ad-overlay-container,.ytp-ad-module,.video-ads,.ytp-ad-player-overlay,.ytp-ad-image-overlay,.ytp-ad-text-overlay,"
        + "ytm-promoted-sparkles-web-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-text-search-renderer,"
        + ".ad-container,.advertisement,[aria-label*=\"Advertisement\"],[class*=\"ad-slot\"],[id*=\"google_ads\"]');"
        + "for(var i=0;i<hs.length;i++){hs[i].style.setProperty('display','none','important');hs[i].style.setProperty('visibility','hidden','important');}"
        + "}catch(e){}}"
        + "try{new MutationObserver(function(){run(document);}).observe(document.documentElement,{childList:true,subtree:true,attributes:true});}catch(e){}"
        + "setInterval(function(){run(document);"
        + "var fs=document.querySelectorAll('iframe');"
        + "for(var i=0;i<fs.length;i++){try{if(fs[i].contentDocument)run(fs[i].contentDocument);}catch(e){}}"
        + "},300);})();";

    // Captions killer: hides the subtitle overlay and the CC button, and switches captions off.
    // Runs in the page and (same-origin) inside the embedded player iframe.
    private static final String CAPS_JS =
        "(function(){if(window.__raadCap)return;window.__raadCap=1;"
        + "var CSS='.ytp-caption-window-container,.caption-window,.captions-text,.ytp-caption-segment,.ytp-subtitles-button,"
        + ".ytp-caption-window-bottom,.ytp-caption-window-top,.ytp-caption-window-rollup{display:none!important;visibility:hidden!important;opacity:0!important}';"
        + "function run(d){try{"
        + "if(!d.getElementById('raadnocc')){var s=d.createElement('style');s.id='raadnocc';s.textContent=CSS;(d.head||d.documentElement).appendChild(s);}"
        + "var b=d.querySelector('.ytp-subtitles-button');"
        + "if(b&&b.getAttribute('aria-pressed')==='true'){b.click();}"
        + "var p=d.querySelector('#movie_player,.html5-video-player');"
        + "if(p){try{if(p.unloadModule)p.unloadModule('captions');}catch(e){}try{if(p.setOption)p.setOption('captions','track',{});}catch(e){}}"
        + "var v=d.querySelector('video');"
        + "if(v&&v.textTracks){for(var i=0;i<v.textTracks.length;i++){try{v.textTracks[i].mode='disabled';}catch(e){}}}"
        + "}catch(e){}}"
        + "run(document);"
        + "setInterval(function(){run(document);"
        + "var fs=document.querySelectorAll('iframe');"
        + "for(var i=0;i<fs.length;i++){try{if(fs[i].contentDocument)run(fs[i].contentDocument);}catch(e){}}"
        + "},400);})();";

    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) { return true; }
    }

    private static boolean isAdRequest(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("doubleclick.net") || u.contains("googlesyndication.com")
            || u.contains("googleadservices.com") || u.contains("googletagservices.com")
            || u.contains("adservice.google.") || u.contains("imasdk.googleapis.com")
            || u.contains("securepubads.g.doubleclick.net") || u.contains("pagead2.googlesyndication.com")
            || u.contains("amazon-adsystem.com") || u.contains("adnxs.com")
            || u.contains("adsrvr.org") || u.contains("adform.net")
            || u.contains("/pagead/") || u.contains("/api/stats/ads")
            || u.contains("/ptracking") || u.contains("/get_midroll_")
            || u.contains("/youtubei/v1/player/ad_break") || u.contains("/youtubei/v1/player/ad_")
            || u.contains("/ad_data_204") || u.contains("/api/stats/atr")
            || u.contains("/pagead/interaction") || u.contains("/ptracking?");
    }

    private void fallbackToWatch() {
        if (fellBack || web == null || destroyed) return;
        fellBack = true;
        embedMode = false;
        if (loading != null) {
            loading.setText("جارٍ تحميل المحاضرة…");
            loading.setVisibility(View.VISIBLE);
        }
        if (altBtn != null) altBtn.setVisibility(View.GONE);
        if (ctlBtn != null) ctlBtn.setVisibility(View.GONE);
        web.stopLoading();
        String target = videoId != null
            ? "https://m.youtube.com/watch?v=" + videoId + (startAt > 0 ? "&t=" + startAt + "s" : "")
            : sourceUrl.replace("://www.youtube.com", "://m.youtube.com");
        web.loadUrl(target);
    }

    private String readAsset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) { return null; }
    }

    private void loadEmbed(int start) {
        if (web == null || videoId == null) return;
        String tpl = readAsset("lecture_player.html");
        if (tpl == null) { fallbackToWatch(); return; }
        String html = tpl.replace("%CLASS%", customUi ? "custom" : "")
            .replace("%CUSTOM%", customUi ? "true" : "false")
            .replace("%VID%", videoId)
            .replace("%START%", String.valueOf(Math.max(0, start)))
            .replace("%ADJS%", AD_JS + CAPS_JS);
        if (ctlBtn != null) ctlBtn.setText(customUi ? "🎛 تحكم: مخصص" : "▶ تحكم: YouTube");
        web.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null);
    }

    private void toggleControls() {
        if (web == null || !embedMode || fellBack) return;
        web.evaluateJavascript("(function(){try{return Math.floor(player.getCurrentTime());}catch(e){return 0;}})()", value -> {
            int t = 0;
            try { t = Integer.parseInt(value.replaceAll("[^0-9]", "")); } catch (Throwable ignored) {}
            customUi = !customUi;
            getSharedPreferences("lecture_ui", MODE_PRIVATE).edit().putBoolean("custom", customUi).apply();
            loadEmbed(t);
        });
    }

    private void openInYouTube() {
        String target = videoId != null
            ? "https://www.youtube.com/watch?v=" + videoId
            : sourceUrl;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
            catch (Throwable ignoredAgain) {
                if (loading != null) loading.setText("تعذر فتح YouTube. تأكد من تثبيت التطبيق أو وجود متصفح.");
            }
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackgroundColor(Color.argb(170, 0, 0, 0));
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2), 0);
        } catch (Throwable ignored) {}
        // Use YouTube's native controls by default. The old custom overlay sat over the
        // lower edge of the picture on some Android screen sizes and looked cropped.
        customUi = false;
        sourceUrl = getIntent().getStringExtra("url");
        if (sourceUrl == null || !(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"))) {
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        st.setDatabaseEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setLoadWithOverviewMode(true);
        st.setSupportZoom(false);
        // Keep the real WebView user agent: a spoofed one makes the YouTube player stall when seeking.
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void embedFailed(int code) {
                runOnUiThread(() -> {
                    // 100/101/150/152 = the owner blocked playing outside YouTube: only then switch to the normal page.
                    boolean blocked = code == 100 || code == 101 || code == 150 || code == 152;
                    if (!blocked && embedMode && !fellBack && stallRetries < 1) {
                        stallRetries++;
                        loadEmbed(Math.max(curPos, startAt));
                    } else fallbackToWatch();
                });
            }

            @JavascriptInterface
            public void stalled(int secs) {
                startAt = secs;
                runOnUiThread(() -> {
                    // Try the custom player once more at the same spot before giving up on it.
                    if (embedMode && !fellBack && stallRetries < 1) {
                        stallRetries++;
                        loadEmbed(secs);
                    } else fallbackToWatch();
                });
            }

            @JavascriptInterface
            public void progress(int secs, int dur) {
                curPos = secs;
                curDur = dur;
                saveResume(secs, dur);
            }

            @JavascriptInterface
            public void tapAt(float x, float y) {
                runOnUiThread(() -> {
                    if (web == null || destroyed || customView != null) return;
                    long t = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0);
                    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    web.dispatchTouchEvent(down);
                    web.postDelayed(() -> { if (web != null) web.dispatchTouchEvent(up); up.recycle(); }, 60);
                    down.recycle();
                });
            }

            @JavascriptInterface
            public void playing() {
                started = true;
                runOnUiThread(() -> { if (loading != null) loading.setVisibility(View.GONE); });
            }
        }, "LecBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                if (!isNetworkAvailable() && (request.getUrl().toString().startsWith("https://") || request.getUrl().toString().startsWith("http://"))) view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK); else view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                if (isAdRequest(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String u = request.getUrl().toString();
                // Keep everything inside the app: ignore links that try to open another app (vnd.youtube:, intent:, market:).
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("/watch")) {
                    // Normal YouTube page: hide the loader and make sure the sound is on.
                    if (loading != null) loading.setVisibility(View.GONE);
                    view.evaluateJavascript(UNMUTE_JS, null);
                    view.evaluateJavascript(AD_JS, null);
                    view.evaluateJavascript(CAPS_JS, null);
                    // FILL_JS disabled: its forced sizing/transform on the player breaks YouTube's own
                    // full-screen layout (black picture, sound only). FS_JS below uses real full screen instead.
                    view.evaluateJavascript(FS_JS, null);
                    view.evaluateJavascript(PROGRESS_JS, null);
                } else if (embedMode) {
                    // Embedded player: keep the loader until it plays or fails (then we switch automatically).
                    view.postDelayed(() -> { if (loading != null && !fellBack) loading.setVisibility(View.GONE); }, 12000);
                } else if (loading != null) {
                    loading.setVisibility(View.GONE);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                immersive();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setText("جارٍ تحميل المحاضرة…");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView close = pill("✕");
        close.setOnClickListener(v -> finish());
        tools.addView(close);
        altBtn = pill("⟳ تشغيل بديل");
        altBtn.setVisibility(View.GONE);
        altBtn.setOnClickListener(v -> {
            if (fellBack) openInYouTube();
            else fallbackToWatch();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        tools.addView(altBtn, lp);
        ctlBtn = pill("🎛 تحكم: مخصص");
        ctlBtn.setOnClickListener(v -> toggleControls());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(8);
        tools.addView(ctlBtn, lp2);
        ctlBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        tp.topMargin = dp(8);
        tp.leftMargin = dp(8);
        root.addView(tools, tp);

        setContentView(root);
        immersive();

        // Find the video in the background, then play it full screen.
        new Thread(() -> {
            String id = null;
            try { id = resolveId(sourceUrl); } catch (Throwable ignored) {}
            final String vid = id;
            runOnUiThread(() -> {
                if (destroyed || web == null) return;
                if (vid != null) {
                    videoId = vid;
                    if (!embedOk) {
                        fallbackToWatch();
                        return;
                    }
                    altBtn.setVisibility(View.VISIBLE);
                    embedMode = true;
                    int saved = savedPos();
                    int from = saved > 5 ? saved - 3 : 0;   // go back 3 seconds to catch the context
                    startAt = from;
                    if (from > 0 && loading != null) loading.setText("استئناف المشاهدة من " + fmtTime(from) + "…");
                    loadEmbed(from);
                    // If the embedded player does not start at all, switch automatically to the normal page.
                    web.postDelayed(() -> { if (!started && !fellBack && !destroyed) fallbackToWatch(); }, 20000);
                } else {
                    // Could not find a video: show the normal page instead.
                    web.loadUrl(sourceUrl.replace("://www.youtube.com", "://m.youtube.com"));
                }
            });
        }).start();
    }

    private static String extractId(String url) {
        if (url.contains("/results")) return null;
        Matcher m = Pattern.compile("(?:[?&]v=|youtu\\.be/|/embed/)([A-Za-z0-9_-]{11})").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private String resolveId(String url) throws Exception {
        String direct = extractId(url);
        if (direct != null) { embedOk = embeddable(direct); return direct; }
        SharedPreferences sp = getSharedPreferences("lecture_ids_v2", MODE_PRIVATE);
        String cached = sp.getString(url, null);
        if (cached != null) return cached;

        int minMin = 0;
        Matcher mm = Pattern.compile("[?&]minmin=(\\d+)").matcher(url);
        if (mm.find()) minMin = Integer.parseInt(mm.group(1));
        String fetchUrl = url.replaceAll("&minmin=\\d+", "").replace("://m.youtube.com", "://www.youtube.com");

        HttpURLConnection c = (HttpURLConnection) new URL(fetchUrl).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", DESKTOP_UA);
        c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0 && sb.length() < 3000000) sb.append(buf, 0, n);
        r.close();
        String html = sb.toString();

        // Collect the videos of the results, and pick the first one that is long enough (full lecture / full story).
        Matcher m = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        while (m.find()) { ids.add(m.group(1)); starts.add(m.end()); }
        Pattern dur = Pattern.compile("\"lengthText\":\\{.{0,500}?\"simpleText\":\"([0-9:]+)\"", Pattern.DOTALL);
        java.util.ArrayList<String> good = new java.util.ArrayList<>();
        java.util.ArrayList<String> rest = new java.util.ArrayList<>();
        for (int k = 0; k < ids.size() && k < 15; k++) {
            int from = starts.get(k);
            int to = (k + 1 < ids.size()) ? starts.get(k + 1) : Math.min(html.length(), from + 6000);
            Matcher dm = dur.matcher(html.substring(from, to));
            int secs = -1;
            if (dm.find()) {
                String[] parts = dm.group(1).split(":");
                secs = 0;
                for (String p : parts) secs = secs * 60 + Integer.parseInt(p);
            }
            if (secs >= minMin * 60) good.add(ids.get(k)); else rest.add(ids.get(k));
        }
        good.addAll(rest);
        // Skip videos whose owner blocked playing them outside YouTube (error 152) or that were removed.
        String chosen = null;
        for (int k = 0; k < good.size() && k < 12; k++) {
            if (embeddable(good.get(k))) { chosen = good.get(k); break; }
        }
        if (chosen != null) {
            embedOk = true;
            sp.edit().putString(url, chosen).apply();
            return chosen;
        }
        if (!good.isEmpty()) { embedOk = false; return good.get(0); }
        return null;
    }

    private boolean embeddable(String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                "https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + id).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", DESKTOP_UA);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable t) {
            return true; // no answer: try the embedded player anyway
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) { try { customCallback.onCustomViewHidden(); } catch (Throwable ignored) {} }
        customCallback = null;
        immersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        saveFromWeb();
        super.onPause();
    }

    @Override
    public void finish() {
        saveFromWeb();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (web != null) {
            try {
                ((ViewGroup) web.getParent()).removeView(web);
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {}
            web = null;
        }
        super.onDestroy();
    }
}
