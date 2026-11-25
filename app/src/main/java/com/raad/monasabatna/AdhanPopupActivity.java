package com.raad.monasabatna;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Pop-up shown when the adhan starts: a picture of the Haram with an (X) button.
 * Pressing X (or Back) stops the adhan and closes the window. It closes by itself when the adhan ends.
 *
 * To use a real photo of the Haram: put a file named  haram_adhan.jpg  (or .png / .webp)
 * in app/src/main/res/drawable/ . If it does not exist, a drawn illustration of the Kaaba is shown.
 */
public class AdhanPopupActivity extends Activity {
    public static final String ACTION_CLOSE = "com.raad.monasabatna.ADHAN_POPUP_CLOSE";

    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Show over the lock screen and wake the screen.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        IntentFilter f = new IntentFilter(ACTION_CLOSE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closer, f);

        build(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void build(Intent intent) {
        String title = intent != null ? intent.getStringExtra("title") : null;
        String message = intent != null ? intent.getStringExtra("message") : null;
        if (title == null) title = "🕌 حان وقت الصلاة";
        if (message == null) message = "حيّ على الصلاة، حيّ على الفلاح";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);
        root.setOnClickListener(null);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cardW = Math.min((int) (dm.widthPixels * 0.9f), dp(420));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B1F3A);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        final int radius = dp(24);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        // ---- picture area with the X button
        FrameLayout pic = new FrameLayout(this);
        View image;
        int res = getResources().getIdentifier("haram_adhan", "drawable", getPackageName());
        if (res != 0) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(res);
            image = iv;
        } else {
            image = new KaabaView(this);
        }
        pic.addView(image, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextColor(Color.WHITE);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        x.setGravity(Gravity.CENTER);
        GradientDrawable xbg = new GradientDrawable();
        xbg.setShape(GradientDrawable.OVAL);
        xbg.setColor(0xAA000000);
        x.setBackground(xbg);
        x.setContentDescription("إيقاف الأذان");
        x.setOnClickListener(v -> stopAndClose());
        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        xp.setMargins(dp(12), dp(12), dp(12), 0);
        pic.addView(x, xp);

        card.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, res != 0 ? dp(360) : dp(240)));

        // ---- text
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE8C872);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMargins(dp(16), dp(16), dp(16), dp(6));
        card.addView(t, tp);

        TextView m = new TextView(this);
        m.setText(message);
        m.setTextColor(0xFFFFFFFF);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.setMargins(dp(20), 0, dp(20), dp(22));
        card.addView(m, mp);

        root.removeAllViews();
        root.addView(card, new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
    }

    private void stopAndClose() {
        try { AdhanService.stop(this); } catch (Throwable ignored) {}
        finish();
    }

    @Override
    public void onBackPressed() {
        stopAndClose();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(closer); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /** A drawn night scene of the Kaaba, used when no photo (haram_adhan) is provided. */
    private static class KaabaView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        KaabaView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;

            // night sky
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF050E22, 0xFF1D4F82, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // stars
            Random r = new Random(7);
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i < 40; i++) {
                float sx = r.nextFloat() * w, sy = r.nextFloat() * h * 0.6f;
                canvas.drawCircle(sx, sy, 1f + r.nextFloat() * 1.8f, p);
            }

            float ground = h * 0.80f;

            // glow behind the Kaaba
            p.setShader(new RadialGradient(w / 2f, ground - h * 0.25f, h * 0.7f,
                0x66F6D77A, 0x00F6D77A, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // marble floor (mataf)
            p.setShader(new LinearGradient(0, ground, 0, h, 0xFFEADFC4, 0xFFB9AD90, Shader.TileMode.CLAMP));
            canvas.drawRect(0, ground, w, h, p);
            p.setShader(null);

            // Kaaba body
            float bw = h * 0.50f, bh = h * 0.54f;
            float left = (w - bw) / 2f, top = ground - bh;
            p.setShader(new LinearGradient(left, 0, left + bw, 0, 0xFF1A1A1A, 0xFF050505, Shader.TileMode.CLAMP));
            canvas.drawRect(left, top, left + bw, ground, p);
            p.setShader(null);

            // soft top edge
            p.setColor(0xFF2B2B2B);
            canvas.drawRect(left, top, left + bw, top + bh * 0.025f, p);

            // golden band (hizam)
            p.setColor(0xFFD4AF37);
            float bandTop = top + bh * 0.20f, bandH = bh * 0.08f;
            canvas.drawRect(left, bandTop, left + bw, bandTop + bandH, p);
            p.setColor(0xFF8F6F12);
            canvas.drawRect(left, bandTop + bandH * 0.45f, left + bw, bandTop + bandH * 0.55f, p);

            // golden door
            float dw = bw * 0.20f, dh = bh * 0.36f;
            float dl = left + bw * 0.58f;
            p.setColor(0xFFC9A227);
            canvas.drawRect(dl, ground - dh, dl + dw, ground, p);
            p.setColor(0xFF7A5E10);
            canvas.drawRect(dl + dw * 0.1f, ground - dh + dh * 0.08f, dl + dw * 0.9f, ground, p);
            p.setColor(0xFFE8C872);
            canvas.drawRect(dl + dw * 0.18f, ground - dh + dh * 0.15f, dl + dw * 0.82f, ground - dh * 0.05f, p);

            // reflection on the floor
            p.setColor(0x33000000);
            canvas.drawRect(left, ground, left + bw, Math.min(h, ground + h * 0.06f), p);
        }
    }
}
