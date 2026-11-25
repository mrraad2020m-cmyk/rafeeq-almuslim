package com.raad.monasabatna;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A thin one-line dhikr strip that slides in from the right and leaves to the left. */
public final class OverlayDhikr {
    private static final long STAY_MS = 5000;
    // Short-lived reminders (istighfar/tasbeeh) use their own window.
    private static View current;      // transient window root
    private static View currentCard;  // transient animated strip

    // Daily morning/evening adhkar have an independent persistent banner/bubble.
    // Keeping these references separate prevents a transient reminder from replacing them.
    private static View dailyRoot;
    private static View dailyCard;
    private static WindowManager dailyWindowManager;
    private static WindowManager.LayoutParams dailyParams;
    private static Context dailyApp;
    private static String dailyTitle;
    private static String dailyText;
    private static String dailyTarget = "morning";
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable dismiss = new Runnable() {
        @Override public void run() { slideOut(); }
    };
    private static final Runnable collapseDaily = new Runnable() {
        @Override public void run() { collapseDailyToBubble(); }
    };

    public static boolean canDraw(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public static void show(final Context context, final String title, final String text) {
        show(context, title, text, null);
    }

    public static void show(final Context context, final String title, final String text, final Runnable onFail) {
        final Context app = context.getApplicationContext();
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    removeCurrent();
                    WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    DisplayMetrics dm = app.getResources().getDisplayMetrics();

                    LinearLayout card = new LinearLayout(app);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(Gravity.CENTER);
                    card.setPadding(dp(app, 16), dp(app, 7), dp(app, 16), dp(app, 7));
                    boolean modern = isModern(app);
                    GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        modern ? new int[]{0xF2143D3D, 0xF21C5C4B} : new int[]{0xF20F2A47, 0xF21E5079});
                    bg.setCornerRadius(dp(app, 20));
                    bg.setStroke(dp(app, 1), modern ? 0x66E8CA8B : 0x66F5D78D);
                    card.setBackground(bg);
                    card.setElevation(dp(app, 6));

                    TextView body = new TextView(app);
                    body.setText("🤍  " + text);
                    body.setTextColor(modern ? 0xFFEDF6F2 : 0xFFFFFFFF);
                    body.setTextSize(14);
                    body.setTypeface(Typeface.DEFAULT_BOLD);
                    body.setGravity(Gravity.CENTER);
                    body.setMaxLines(2);
                    body.setEllipsize(TextUtils.TruncateAt.END);
                    card.addView(body, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                    FrameLayout root = new FrameLayout(app);
                    root.setPadding(dp(app, 12), 0, dp(app, 12), 0);
                    root.addView(card, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP;
                    params.y = statusBarHeight(app) + dp(app, 2);

                    final float[] startX = new float[1];
                    card.setOnTouchListener(new View.OnTouchListener() {
                        @Override public boolean onTouch(final View v, MotionEvent ev) {
                            switch (ev.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    v.animate().cancel();
                                    startX[0] = ev.getRawX() - v.getTranslationX();
                                    handler.removeCallbacks(dismiss);
                                    return true;
                                case MotionEvent.ACTION_MOVE: {
                                    float dx = ev.getRawX() - startX[0];
                                    v.setTranslationX(dx);
                                    v.setAlpha(1f - Math.min(Math.abs(dx) / 400f, 0.6f));
                                    return true;
                                }
                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL: {
                                    float dx = v.getTranslationX();
                                    float threshold = dp(v.getContext(), 80);
                                    if (Math.abs(dx) < dp(v.getContext(), 6)) {
                                        removeCurrent();
                                    } else if (Math.abs(dx) > threshold) {
                                        float target = dx > 0 ? v.getWidth() : -v.getWidth();
                                        v.animate().translationX(target).alpha(0f).setDuration(160)
                                            .withEndAction(new Runnable() {
                                                @Override public void run() { removeCurrent(); }
                                            }).start();
                                    } else {
                                        v.animate().translationX(0f).alpha(1f).setDuration(150).start();
                                        handler.postDelayed(dismiss, 3000);
                                    }
                                    return true;
                                }
                            }
                            return false;
                        }
                    });

                    // Start off-screen on the right, then slide in.
                    card.setTranslationX(dm.widthPixels);
                    wm.addView(root, params);
                    current = root;
                    currentCard = card;
                    card.animate().translationX(0f).setDuration(350)
                        .setInterpolator(new DecelerateInterpolator()).start();
                    handler.removeCallbacks(dismiss);
                    handler.postDelayed(dismiss, STAY_MS);
                } catch (Throwable e) {
                    current = null;
                    currentCard = null;
                    if (onFail != null) onFail.run();
                }
            }
        });
    }

    /** Daily adhkar: show a top banner briefly, then keep a movable compact bubble. */
    public static void showPersistentDaily(final Context context, final String title, final String text,
            final String target, final Runnable onFail) {
        final Context app = context.getApplicationContext();
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    dailyApp = app;
                    dailyTitle = title;
                    dailyText = text;
                    dailyTarget = ("evening".equals(target) || "sleep".equals(target)) ? target : "morning";
                    handler.removeCallbacks(collapseDaily);
                    removeDailyView();
                    showDailyBanner(true);
                } catch (Throwable e) {
                    removeDailyView();
                    if (onFail != null) onFail.run();
                }
            }
        });
    }

    private static WindowManager.LayoutParams dailyLayoutParams(Context app, int width, int height) {
        return new WindowManager.LayoutParams(width, height,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    }

    private static void showDailyBanner(boolean scheduleCollapse) {
        final Context app = dailyApp;
        if (app == null) return;
        removeDailyView();
        dailyWindowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);

        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // close button stays on the left, like the reference
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(app, 12), dp(app, 9), dp(app, 14), dp(app, 9));
        boolean modern = isModern(app);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            modern ? new int[]{0xF2143D3D, 0xF21C5C4B} : new int[]{0xF20F2A47, 0xF21E5079});
        bg.setCornerRadius(dp(app, 34));
        bg.setStroke(dp(app, 1), modern ? 0x88E8CA8B : 0x88F5D78D);
        card.setBackground(bg);
        card.setElevation(dp(app, 10));

        TextView close = new TextView(app);
        close.setText("×");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(27);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0xFFB43B42);
        close.setBackground(closeBg);
        card.addView(close, new LinearLayout.LayoutParams(dp(app, 42), dp(app, 42)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                handler.removeCallbacks(collapseDaily);
                removeDailyView();
            }
        });

        LinearLayout words = new LinearLayout(app);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        words.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView heading = new TextView(app);
        heading.setText(dailyTitle == null ? "🤍 تذكير بالأذكار" : dailyTitle);
        heading.setTextColor(modern ? 0xFFE8CA8B : 0xFFFFE5A3);
        heading.setTextSize(15);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.RIGHT);
        TextView body = new TextView(app);
        body.setText(dailyText == null ? "ابدأ يومك بذكر الله" : dailyText);
        body.setTextColor(modern ? 0xFFEDF6F2 : 0xFFFFFFFF);
        body.setTextSize(13);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setGravity(Gravity.RIGHT);
        words.addView(heading);
        words.addView(body);
        // Tapping the banner opens the matching adhkar tab inside the app.
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                handler.removeCallbacks(collapseDaily);
                removeDailyView();
                Intent open = new Intent(app, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                open.putExtra("open_adhkar", dailyTarget);
                try { app.startActivity(open); } catch (Throwable ignored) {}
            }
        });
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.leftMargin = dp(app, 12);
        card.addView(words, wordsLp);

        FrameLayout root = new FrameLayout(app);
        root.setPadding(dp(app, 10), dp(app, 4), dp(app, 10), dp(app, 4));
        root.addView(card, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        WindowManager.LayoutParams params = dailyLayoutParams(app,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = statusBarHeight(app) + dp(app, 8);
        dailyWindowManager.addView(root, params);
        dailyRoot = root;
        dailyCard = card;
        if (scheduleCollapse) {
            handler.removeCallbacks(collapseDaily);
            handler.postDelayed(collapseDaily, 6500L);
        }
    }

    /** Replace the banner with a compact bubble; tap to expand and drag to reposition. */
    private static void collapseDailyToBubble() {
        final Context app = dailyApp;
        if (app == null) return;
        try {
            removeDailyView();
            dailyWindowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            final TextView bubble = new TextView(app);
            bubble.setText("🌿");
            bubble.setTextSize(25);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xF20F2A47, 0xF21E5079});
            bg.setShape(GradientDrawable.OVAL);
            bg.setStroke(dp(app, 2), 0x88F5D78D);
            bubble.setBackground(bg);
            bubble.setElevation(dp(app, 12));

            dailyParams = dailyLayoutParams(app, dp(app, 60), dp(app, 60));
            dailyParams.gravity = Gravity.TOP | Gravity.LEFT;
            android.content.SharedPreferences pos = app.getSharedPreferences("dhikr", Context.MODE_PRIVATE);
            android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
            int defaultX = Math.max(dp(app, 8), dm.widthPixels - dp(app, 72));
            dailyParams.x = pos.getInt("bubble_x", defaultX);
            dailyParams.y = pos.getInt("bubble_y", statusBarHeight(app) + dp(app, 140));
            dailyWindowManager.addView(bubble, dailyParams);
            dailyRoot = bubble;
            dailyCard = bubble;

            final float[] down = new float[2];
            final int[] origin = new int[2];
            final boolean[] moved = new boolean[1];
            bubble.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            down[0] = ev.getRawX(); down[1] = ev.getRawY();
                            origin[0] = dailyParams.x; origin[1] = dailyParams.y;
                            moved[0] = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            int dx = Math.round(ev.getRawX() - down[0]);
                            int dy = Math.round(ev.getRawY() - down[1]);
                            if (Math.abs(dx) + Math.abs(dy) > dp(app, 7)) moved[0] = true;
                            if (moved[0]) {
                                android.util.DisplayMetrics m = app.getResources().getDisplayMetrics();
                                dailyParams.x = Math.max(0, Math.min(m.widthPixels - v.getWidth(), origin[0] + dx));
                                dailyParams.y = Math.max(statusBarHeight(app), Math.min(m.heightPixels - v.getHeight(), origin[1] + dy));
                                try { dailyWindowManager.updateViewLayout(dailyRoot, dailyParams); } catch (Throwable ignored) {}
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (moved[0]) {
                                app.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit()
                                    .putInt("bubble_x", dailyParams.x).putInt("bubble_y", dailyParams.y).apply();
                            } else {
                                handler.removeCallbacks(collapseDaily);
                                showDailyBanner(true);
                            }
                            return true;
                    }
                    return true;
                }
            });
        } catch (Throwable ignored) {
            // If the compact bubble cannot be displayed, leave the reminder unobtrusive rather than crash.
        }
    }

    private static void removeDailyView() {
        if (dailyRoot == null) return;
        try {
            WindowManager wm = dailyWindowManager != null ? dailyWindowManager
                : (WindowManager) dailyRoot.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(dailyRoot);
        } catch (Throwable ignored) {}
        dailyRoot = null;
        dailyCard = null;
    }

    /** Leaves toward the left side of the screen. */
    private static void slideOut() {
        handler.removeCallbacks(dismiss);
        final View card = currentCard;
        if (card == null) { removeCurrent(); return; }
        card.animate().translationX(-card.getContext().getResources().getDisplayMetrics().widthPixels)
            .alpha(0f).setDuration(350)
            .withEndAction(new Runnable() {
                @Override public void run() { removeCurrent(); }
            }).start();
    }

    private static void removeCurrent() {
        handler.removeCallbacks(dismiss);
        if (current == null) return;
        try {
            WindowManager wm = (WindowManager) current.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(current);
        } catch (Exception ignored) {
        }
        current = null;
        currentCard = null;
    }

    private static boolean isModern(Context context) {
        return context.getSharedPreferences("interface_settings", Context.MODE_PRIVATE)
            .getBoolean("modern", true);
    }

    private static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : dp(context, 24);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
