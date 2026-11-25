package com.raad.monasabatna;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Calendar;

public class ReminderReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "fasting_reminders_v2";
    public static final String CHANNEL_ADHAN = "prayer_adhan";
    public static final String CHANNEL_PRAYER_SILENT = "prayer_silent_v2";
    public static final String CHANNEL_DHIKR = "dhikr_reminders_v2";
    public static final String CHANNEL_ADHKAR = "daily_adhkar_persistent_v1";
    public static final String CHANNEL_RAMADAN = "ramadan_countdown_v1";
    private static final String ACTION_DISMISS_ADHKAR = "com.raad.monasabatna.DISMISS_DAILY_ADHKAR";

    public static final int DHIKR_REPEAT_ID = 901000;
    private static final int MORNING_ADHKAR_ID = 902001;
    private static final int EVENING_ADHKAR_ID = 902002;
    private static final int SLEEP_ADHKAR_ID = 902003;
    private static final int QURAN_WIRD_ID = 902004;
    private static final int RAMADAN_NOTIFICATION_ID = 902005;

    /** Backward-compatible overload: keeps the saved bedtime time (default 21:00). */
    public static void configureDailyAdhkar(Context context, boolean enabled,
            int morningHour, int morningMinute, int eveningHour, int eveningMinute) {
        android.content.SharedPreferences sp = context.getSharedPreferences("daily_adhkar", Context.MODE_PRIVATE);
        configureDailyAdhkar(context, enabled, morningHour, morningMinute, eveningHour, eveningMinute,
            sp.getInt("sleep_hour", 21), sp.getInt("sleep_minute", 0));
    }

    /** Save the user's daily adhkar times (morning / evening / sleep) and arm the next reminders. */
    public static void configureDailyAdhkar(Context context, boolean enabled,
            int morningHour, int morningMinute, int eveningHour, int eveningMinute,
            int sleepHour, int sleepMinute) {
        android.content.SharedPreferences sp = context.getSharedPreferences("daily_adhkar", Context.MODE_PRIVATE);
        sp.edit().putBoolean("enabled", enabled)
            .putInt("morning_hour", clamp(morningHour, 0, 23))
            .putInt("morning_minute", clamp(morningMinute, 0, 59))
            .putInt("evening_hour", clamp(eveningHour, 0, 23))
            .putInt("evening_minute", clamp(eveningMinute, 0, 59))
            .putInt("sleep_hour", clamp(sleepHour, 0, 23))
            .putInt("sleep_minute", clamp(sleepMinute, 0, 59)).apply();
        cancelDailyAdhkar(context, MORNING_ADHKAR_ID);
        cancelDailyAdhkar(context, EVENING_ADHKAR_ID);
        cancelDailyAdhkar(context, SLEEP_ADHKAR_ID);
        if (enabled) scheduleDailyAdhkar(context);
    }

    // ---- Fasting reminders: persisted natively so they survive reboot / app update / time change ----
    public static void saveAndScheduleFasting(Context context, String json) {
        try {
            context.getSharedPreferences("fasting", Context.MODE_PRIVATE).edit().putString("schedule", json).apply();
        } catch (Throwable ignored) { }
        scheduleFastingFromSaved(context);
    }

    public static void scheduleFastingFromSaved(Context context) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                context.getSharedPreferences("fasting", Context.MODE_PRIVATE).getString("schedule", "[]"));
            long now = System.currentTimeMillis();
            for (int k = 0; k < arr.length(); k++) {
                try {
                    org.json.JSONObject o = arr.getJSONObject(k);
                    long t = o.getLong("t");
                    if (t <= now) continue;
                    schedule(context, t, o.optString("title", "🌿 تذكير بالصيام"),
                        o.optString("msg", "غداً يوم صيام مستحب. تقبل الله طاعتك 🤲"),
                        o.getInt("id"), false, false, false);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** Daily Quran wird reminder; the selected local time is persisted and restored after reboot. */
    public static void configureDailyQuranReminder(Context context, boolean enabled, int hour, int minute) {
        android.content.SharedPreferences sp = context.getSharedPreferences("quran_wird", Context.MODE_PRIVATE);
        sp.edit().putBoolean("reminder_enabled", enabled)
            .putInt("reminder_hour", clamp(hour, 0, 23))
            .putInt("reminder_minute", clamp(minute, 0, 59)).apply();
        cancelDailyAdhkar(context, QURAN_WIRD_ID);
        if (enabled) scheduleDailyQuranReminder(context);
    }

    /** Daily status-bar notification showing the days remaining until the next Ramadan. */
    public static void configureRamadanNotification(Context context, boolean enabled, int hour, int minute, long ramadanStartMillis) {
        android.content.SharedPreferences sp = context.getSharedPreferences("ramadan_notification", Context.MODE_PRIVATE);
        sp.edit().putBoolean("enabled", enabled)
            .putInt("hour", clamp(hour, 0, 23))
            .putInt("minute", clamp(minute, 0, 59))
            .putLong("ramadan_start", ramadanStartMillis).apply();
        cancelDailyAdhkar(context, RAMADAN_NOTIFICATION_ID);
        if (enabled && ramadanStartMillis > System.currentTimeMillis()) scheduleRamadanNotification(context);
    }

    public static void scheduleRamadanNotification(Context context) {
        android.content.SharedPreferences sp = context.getSharedPreferences("ramadan_notification", Context.MODE_PRIVATE);
        if (!sp.getBoolean("enabled", false)) return;
        long ramadanStart = sp.getLong("ramadan_start", 0L);
        if (ramadanStart <= System.currentTimeMillis()) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, sp.getInt("hour", 9));
        next.set(Calendar.MINUTE, sp.getInt("minute", 0));
        next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("title", "🌙 كم باقي على رمضان؟");
        i.putExtra("message", "تذكير يومي بقرب شهر رمضان المبارك.");
        i.putExtra("notificationId", RAMADAN_NOTIFICATION_ID);
        i.putExtra("ramadan_daily", true);
        i.putExtra("ramadan_start", ramadanStart);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, RAMADAN_NOTIFICATION_ID, i, flags);
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } catch (SecurityException e) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi); }
    }

    private static long ramadanDaysRemaining(long ramadanStartMillis) {
        if (ramadanStartMillis <= 0) return -1;
        long days = (ramadanStartMillis - System.currentTimeMillis() + 86_399_999L) / 86_400_000L;
        return Math.max(0, days);
    }

    public static void scheduleDailyQuranReminder(Context context) {
        android.content.SharedPreferences sp = context.getSharedPreferences("quran_wird", Context.MODE_PRIVATE);
        if (!sp.getBoolean("reminder_enabled", true)) return;
        int hour = sp.getInt("reminder_hour", 20);
        int minute = sp.getInt("reminder_minute", 0);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour); next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("title", "📖 حان وقت وردك اليومي من القرآن");
        i.putExtra("message", "افتح ختمتك وأكمل وردك اليومي من المصحف. جعل الله القرآن نور قلبك 🌿");
        i.putExtra("notificationId", QURAN_WIRD_ID);
        i.putExtra("quran_daily", true);
        i.putExtra("open_adhkar", "quran");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, QURAN_WIRD_ID, i, flags);
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } catch (SecurityException e) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi); }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public static void scheduleDailyAdhkar(Context context) {
        android.content.SharedPreferences sp = context.getSharedPreferences("daily_adhkar", Context.MODE_PRIVATE);
        if (!sp.getBoolean("enabled", true)) return;
        scheduleDailyAdhkarOne(context, MORNING_ADHKAR_ID,
            sp.getInt("morning_hour", 5), sp.getInt("morning_minute", 0),
            "☀️ حان وقت أذكار الصباح", "ابدأ يومك بذكر الله وقراءة أذكار الصباح 🌿", "morning");
        scheduleDailyAdhkarOne(context, EVENING_ADHKAR_ID,
            sp.getInt("evening_hour", 17), sp.getInt("evening_minute", 0),
            "🌙 حان وقت أذكار المساء", "اختم يومك بذكر الله وقراءة أذكار المساء 🤍", "evening");
        // Bedtime adhkar reminder: user-editable time (default 9:00 PM local time).
        scheduleDailyAdhkarOne(context, SLEEP_ADHKAR_ID,
            sp.getInt("sleep_hour", 21), sp.getInt("sleep_minute", 0),
            "🌜 حان وقت أذكار النوم", "اختم يومك بذكر الله واقرأ أذكار النوم 🤍", "sleep");
    }

    private static void scheduleDailyAdhkarOne(Context context, int id, int hour, int minute,
            String title, String message, String target) {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("title", title);
        i.putExtra("message", message);
        i.putExtra("notificationId", id);
        i.putExtra("adhkar_daily", true);
        i.putExtra("open_adhkar", target);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, id, i, flags);
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else if (Build.VERSION.SDK_INT >= 23)
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        }
    }

    private static void cancelDailyAdhkar(Context context, int id) {
        Intent i = new Intent(context, ReminderReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, id, i, flags);
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        pi.cancel();
    }

    /** Messages shown by the repeating dhikr reminder (pop-up and notification), in rotation. */
    private static final String[] DHIKR_ITEMS = {
        "صلِّ على محمد ﷺ",
        "أستغفر الله",
        "الله كريم",
        "الحمد لله",
        "سبحان الله وبحمده، سبحان الله العظيم 🌴",
        "أستغفر الله العظيم وأتوب إليه 🍃",
        "لا حول ولا قوة إلا بالله العلي العظيم 💎",
        "اللهم صل وسلم على نبينا محمد 💛",
        "لا إله إلا أنت سبحانك إني كنت من الظالمين 🌌",
        "الحمد لله حمداً كثيراً طيباً مباركاً فيه 🌿",
        "حسبي الله لا إله إلا هو عليه توكلت وهو رب العرش العظيم 🛡️",
        "رضيت بالله رباً، وبالإسلام ديناً، وبمحمد نبياً ✨",
        "سبحان الله والحمد لله ولا إله إلا الله والله أكبر 🌸",
        "لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير ☀️",
        "اللهم إني أسألك العفو والعافية في الدنيا والآخرة 🤲",
        "يا حي يا قيوم برحمتك أستغيث، أصلح لي شأني كله 💧",
        "اللهم أجرني من النار ومن حر النار 🔥",
        "أستغفر الله الذي لا إله إلا هو الحي القيوم وأتوب إليه 🕊️",
        "اللهم إني أسألك علماً نافعاً ورزقاً طيباً وعملاً متقبلاً 🌱",
        "اللهم عافني في بدني، اللهم عافني في سمعي، اللهم عافني في بصري 👁️",
        "اللهم إني أعوذ بك من الهم والحزن، والعجز والكسل 🌬️",
        "اللهم إني أعوذ بك من زوال نعمتك وتحول عافيتك وفجاءة نقمتك 🔒",
        "اللهم إني أسألك الجنة وما قرب إليها من قول أو عمل 🌳",
        "رب اغفر لي وتب علي إنك أنت التواب الرحيم ☁️",
        "اللهم صل على محمد وعلى آل محمد كما صليت على إبراهيم 🌹",
        "بسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء 🛡️",
        "اللهم اغفر للمؤمنين والمؤمنات الأحياء منهم والأموات 🌍",
        "رب ارحمهما كما ربياني صغيراً 👨‍👩‍👦",
        "اللهم طهر قلبي من كل خلق لا يرضيك 💖",
        "اللهم ارزقني حبك وحب من يحبك وحب عمل يقربني إلى حبك ✨",
        "اللهم اجعلني من التوابين واجعلني من المتطهرين 💧",
        "ربنا آتنا في الدنيا حسنة وفي الآخرة حسنة وقنا عذاب النار 🤲",
        "يا مقلب القلوب ثبت قلبي على دينك ⚓",
        "اللهم إني أسألك الهدى والتقى والعفاف والغنى 💎",
        "حسبنا الله ونعم الوكيل في كل أمر عسير 🌟",
        "سبحان الله عدد خلقه ورضا نفسه وزنة عرشه ومداد كلماته 🍃",
        "اللهم ارزقني رزقاً حلالاً طيباً مباركاً فيه 🌾",
        "رب اشرح لي صدري ويسر لي أمري 🌤️",
        "اللهم أعني على ذكرك وشكرك وحسن عبادتك 🌻",
        "اللهم اكفني بحلالك عن حرامك وأغنني بفضلك عمن سواك 💰",
        "اللهم لا سهل إلا ما جعلته سهلاً وأنت تجعل الحزن إذا شئت سهلاً 🛤️",
        "اللهم إني استودعتك ديني وأمانتي وخواتيم أعمالي 📦",
        "يا رب لك الحمد كما ينبغي لجلال وجهك وعظيم سلطانك 🙌",
        "اللهم اجعل لي من كل هم فرجاً ومن كل ضيق مخرجاً 🚪",
        "اللهم أنسني ما أهمني ويسر لي ما ترضاه لي 🌙",
        "رضيت بالقضاء والقدر، وبكل ما كتبه الله لي ⚖️",
        "اللهم بارك لي في عمري وعملي ورزقي وصحتي 🌿",
        "لا إله إلا الله الملك الحق المبين 👑",
        "اللهم طمئن قلبي وسخر لي ملائكة سماك وجنود أرضك 🕊️",
        "اللهم حسن خُلقي كما أحسنت خَلقي ✨",
        "صلّ اللهم وسلم على نبينا محمد وعلى آله وصحبه أجمعين 💛",
        "سبحان الله العظيم وبحمده عدد ما كان وعدد ما يكون 🌌",
        "أستغفر الله عدد ما ذكره الذاكرون وغفل عن ذكره الغافلون 🍃",
        "اللهم إني أعوذ بك من البرص والجنون والجذام ومن سيء الأسقام 🛡️",
        "يا مصرف القلوب صرف قلوبنا على طاعتك 🧭",
        "اللهم اجعل القرآن ربيع قلبي ونور صدري وجلاء حزني 📖",
        "رب هب لي من لدنك ذرية طيبة إنك سميع الدعاء 🤲",
        "اللهم إني أعوذ بك من جهد البلاء ودرك الشقاء وسوء القضاء ☁️",
        "اللهم ارزقني حسن الخاتمة وثبتني عند السؤال 🕊️",
        "سبحان ذي الجبروت والملكوت والكبرياء والعظمة 👑",
        "اللهم أنت ربي لا إله إلا أنت، خلقتني وأنا عبدك 🤍",
        "أستغفر الله العظيم لي ولوالدي وللمسلمين أجمعين 🌿",
        "اللهم افتح لي أبواب رحمتك ويسر لي أسباب رزقك 🚪",
        "يا حي يا قيوم برحمتك أستغيث، لا تكلني إلى نفسي طرفة عين 💧",
        "اللهم إني أعوذ بك من منكرات الأخلاق والأعمال والأهواء 🌱",
        "اللهم بارك لنا في أوقاتنا وأعمالنا وأعمارنا ⏳",
        "لا إله إلا الله وحده لا شريك له، له الحمد وهو يحيي ويميت ☀️",
        "اللهم إني أسألك نفساً مطمئنة تؤمن بلقائك وترضى بقضائك 💖",
        "سبحان الله وبحمده، لا قوة إلا بالله، ما شاء الله كان 🌴",
        "اللهم أصلح لي ديني الذي هو عصمة أمري وأصلح لي دنياي 🛤️",
        "اللهم اجعل خير عمري آخره وخير عملي خواتمه وخير أيامي يوم ألقاك 🌅",
        "ربنا لا تزغ قلوبنا بعد إذ هديتنا وهب لنا من لدنك رحمة 🤲",
        "أعوذ بالله السميع العليم من الشيطان الرجيم من همزه ونفخه ونفثه 🔒",
        "اللهم إني أسألك من الخير كله عاجله وآجله ما علمت منه وما لم أعلم ✨",
        "اللهم إني أعوذ بك من الشر كله عاجله وآجله ما علمت منه وما لم أعلم 🛡️",
        "الحمد لله الذي بنعمته تتم الصالحات 🌸",
        "الحمد لله على كل حال ونعوذ بالله من حال أهل النار 🌿",
        "اللهم اغفر لي ذنبي كله دقه وجله وأوله وآخره وعلانيته وسره ☁️",
        "اللهم إنك عفو تحب العفو فاعف عني 💛",
        "يا رب ارزقني فرحة تغير مجرى حياتي للأفضل 🌟",
        "اللهم ارزقني التوفيق والسداد في كل خطوة أخطوها 👣",
        "اللهم احفظني من بين يدي ومن خلفي وعن يميني وعن شمالي 🔒",
        "سبحان الله، والحمد لله، والله أكبر، ولا حول ولا قوة إلا بالله 💎",
        "اللهم اجعلني من الذين لا خوف عليهم ولا هم يحزنون 🕊️",
        "اللهم ارزقني قلبًا خاشعًا ولسانًا ذاكرًا وعينًا دامعة من خشيتك 💧",
        "اللهم إني أسألك الثبات في الأمر والعزيمة على الرشد ⚓",
        "رب اغفر لي خطيئتي وجهلي وإسرافي في أمري كله 🤲",
        "اللهم إني أعوذ بك من قلب لا يخشع ومن دعاء لا يسمع 🌬️",
        "اللهم ارزقني صحبة صالحة تعينني على طاعتك وذكرك 🤝",
        "سبحان الله وبحمده زنة عرشه ومداد كلماته ورضا نفسه 🍃",
        "اللهم اجعل في قلبي نوراً وفي بصري نوراً وفي سمعي نوراً 💡",
        "يا مالك الملك تؤتي الملك من تشاء، بيدك الخير إنك على كل شيء قدير 👑",
        "اللهم ارزقني راحة البال وطمأنينة القلب وسكينة النفس 🌙",
        "اللهم لا ترد لنا دعاء ولا تخيب لنا رجاء ولا تسكن جسدنا داء 🌿",
        "أستغفر الله العظيم الذي لا إله إلا هو الحي القيوم وأتوب إليه عدد خلقه 🌴",
        "اللهم صل وسلم وبارك على سيدنا محمد عدد ما ذكره الذاكرون 🌹",
        "اللهم إني أسألك الفردوس الأعلى من الجنة بغير حساب ولا سابقة عذاب 🌳",
        "ربنا تقبل منا إنك أنت السميع العليم وتب علينا إنك أنت التواب الرحيم ✨",
        "اللهم اجعلنا ممن يستمعون القول فيتبعون أحسنه 📖",
        "سبحانك اللهم وبحمدك، أشهد أن لا إله إلا أنت، أستغفرك وأتوب إليك 🤍",
    };



    /** أذكار الصباح: تظهر في فترة الصباح فقط. */
    private static final String[] MORNING_ITEMS = {
        "أصبحنا وأصبح الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له ☀️",
        "اللهم بك أصبحنا وبك أمسينا وبك نحيا وبك نموت وإليك النشور 🌅",
        "اللهم إني أصبحت أشهُدك وأشهُد حملة عرشك أنك أنت الله 🌅",
        "اللهم ما أصبح بي من نعمة فمنك وحدك لا شريك لك، فلك الحمد ولك الشكر 🤍",
        "أصبحنا على فطرة الإسلام وعلى كلمة الإخلاص وعلى دين نبينا محمد ﷺ 🌤️",
        "اللهم إني أسألك علماً نافعاً ورزقاً طيباً وعملاً متقبلاً 🌱",
        "يا رب اجعل هذا اليوم شاهدًا لنا لا علينا واكتب لنا فيه كل خير ☀️",
        "اللهم أعني على ذكرك وشكرك وحسن عبادتك في هذا الصباح 🌻",
    };

    /** أذكار المساء: تظهر في فترة المساء فقط. */
    private static final String[] EVENING_ITEMS = {
        "أمسينا وأمسى الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له 🌙",
        "اللهم بك أمسينا وبك أصبحنا وبك نحيا وبك نموت وإليك المصير 🌆",
        "اللهم إني أمسيت أشهُدك وأشهُد حملة عرشك أنك أنت الله 🌙",
        "اللهم ما أمسى بي من نعمة فمنك وحدك لا شريك لك، فلك الحمد ولك الشكر 🤍",
        "أمسينا على فطرة الإسلام وعلى كلمة الإخلاص وعلى دين نبينا محمد ﷺ 🌃",
        "أعوذ بكلمات الله التامات من شر ما خلق 🌿",
        "اللهم احفظني من بين يدي ومن خلفي وعن يميني وعن شمالي 🔒",
        "اللهم اختم لنا هذا اليوم بالمغفرة والرضوان 🌇",
    };

    /** أذكار تُقال صباحاً ومساءً: تدخل في القائمتين. */
    private static final String[] BOTH_ITEMS = {
        "بسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء 🛡️",
        "رضيت بالله رباً، وبالإسلام ديناً، وبمحمد نبياً ✨",
        "حسبي الله لا إله إلا هو عليه توكلت وهو رب العرش العظيم 🛡️",
        "اللهم إني أسألك العفو والعافية في الدنيا والآخرة 🤲",
        "اللهم عافني في بدني، اللهم عافني في سمعي، اللهم عافني في بصري 👁️",
        "سبحان الله وبحمده 🌴",
        "يا حي يا قيوم برحمتك أستغيث، أصلح لي شأني كله ولا تكلني إلى نفسي طرفة عين 💧",
        "اللهم أنت ربي لا إله إلا أنت، خلقتني وأنا عبدك 🤍",
    };

    // فترات اليوم (بالدقائق من منتصف الليل). عدّلها هنا إن أردت تغيير الأوقات.
    private static final int MORNING_START = 4 * 60;          // 04:00
    private static final int MORNING_END = 8 * 60;            // قبل 08:00
    private static final int EVENING_START = 17 * 60;         // 17:00
    private static final int EVENING_END = 21 * 60;           // قبل 21:00

    private static String[] join(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ملفات العبارات الكبيرة في assets/dhikr (سطر لكل إشعار).
    private static String[] poolMorning, poolEvening, poolGeneral;

    private static String[] loadLines(Context context, String assetName) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                context.getAssets().open(assetName), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.length() > 0) out.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
        return out.toArray(new String[0]);
    }

    private static synchronized String[] getPool(Context context, int period) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (period == 1) {
            if (poolMorning == null)
                poolMorning = join(join(loadLines(app, "dhikr/morning.txt"), MORNING_ITEMS), BOTH_ITEMS);
            return poolMorning;
        }
        if (period == 2) {
            if (poolEvening == null)
                poolEvening = join(join(loadLines(app, "dhikr/evening.txt"), EVENING_ITEMS), BOTH_ITEMS);
            return poolEvening;
        }
        if (poolGeneral == null)
            poolGeneral = join(join(loadLines(app, "dhikr/general_1.txt"), loadLines(app, "dhikr/general_2.txt")), DHIKR_ITEMS);
        return poolGeneral;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private static int shuffleStride(int n) {
        if (n <= 1) return 1;
        int st = (int) (n * 0.6180339887);
        if (st < 1) st = 1;
        while (gcd(st, n) != 1) st++;
        return st;
    }

    /** 0 = عام، 1 = صباح، 2 = مساء بحسب الوقت الحالي. */
    private static int currentPeriod() {
        Calendar c = Calendar.getInstance();
        int t = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (t >= MORNING_START && t < MORNING_END) return 1;
        if (t >= EVENING_START && t < EVENING_END) return 2;
        return 0;
    }

    /** Arms the next dhikr reminder. Uses an idle-safe alarm that re-arms itself from onReceive. */
    public static void armDhikr(Context context, long atMillis, long intervalMillis) {
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("dhikr", true);
        i.putExtra("rotate", true);
        i.putExtra("interval", intervalMillis);
        i.putExtra("notificationId", DHIKR_REPEAT_ID);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, DHIKR_REPEAT_ID, i, flags);
        context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit().putLong("next_at", atMillis).apply();
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
        }
    }

    /** Schedules a one-shot reminder with an exact, idle-safe alarm (falls back to inexact if not permitted). */
    public static void schedule(Context context, long triggerAtMillis, String title, String message,
                                int id, boolean prayer, boolean adhan, boolean dhikr) {
        if (triggerAtMillis <= System.currentTimeMillis()) return;
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, ReminderReceiver.class);
        i.putExtra("title", title);
        i.putExtra("message", message);
        i.putExtra("notificationId", id);
        i.putExtra("prayer", prayer);
        i.putExtra("adhan", adhan);
        i.putExtra("dhikr", dhikr);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, id, i, flags);
        // Adhan: use an "alarm clock" alarm. It is the most reliable type (not delayed by Doze or battery saving,
        // and the system lets the app start the adhan service when it fires).
        if (prayer && adhan && Build.VERSION.SDK_INT >= 21) {
            try {
                Intent show = new Intent(context, MainActivity.class);
                PendingIntent showPi = PendingIntent.getActivity(context, 0, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                am.setAlarmClock(new android.app.AlarmManager.AlarmClockInfo(triggerAtMillis, showPi), pi);
                return;
            } catch (Throwable ignored) { /* fall back to the exact alarm below */ }
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            else am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent != null && ACTION_DISMISS_ADHKAR.equals(intent.getAction())) {
            int id = intent.getIntExtra("notificationId", 0);
            if (id == MORNING_ADHKAR_ID || id == EVENING_ADHKAR_ID || id == SLEEP_ADHKAR_ID) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(id);
            }
            return;
        }
        if (intent.getBooleanExtra("adhkar_daily", false)) {
            // Re-arm all daily adhkar alarms so they repeat every local calendar day.
            scheduleDailyAdhkar(context);
        }
        if (intent.getBooleanExtra("quran_daily", false)) {
            scheduleDailyQuranReminder(context);
        }
        if (intent.getBooleanExtra("prayerTick", false)) {
            PrayerCountdown.update(context);
            return;
        }
        if (intent.getBooleanExtra("rotate", false)) {
            context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).edit()
                .putLong("last_fire", System.currentTimeMillis()).apply();
            long interval = intent.getLongExtra("interval", 0);
            android.content.SharedPreferences dsp = context.getSharedPreferences("dhikr", Context.MODE_PRIVATE);
            if (interval > 0 && dsp.getBoolean("repeat_on", false)) {
                armDhikr(context, System.currentTimeMillis() + interval, interval);
            } else {
                return; // repeat was switched off
            }
        }
        String titleX = intent.getStringExtra("title");
        String messageX = intent.getStringExtra("message");
        if (titleX == null) titleX = "رفيق المسلم";
        if (messageX == null) messageX = "غداً يوم صيام مستحب. تقبل الله طاعتك.";
        if (intent.getBooleanExtra("ramadan_daily", false)) {
            scheduleRamadanNotification(context);
            long remaining = ramadanDaysRemaining(intent.getLongExtra("ramadan_start", 0L));
            if (remaining < 0) return;
            titleX = "🌙 كم باقي على رمضان؟";
            messageX = remaining == 0 ? "بدأ شهر رمضان المبارك، تقبل الله طاعتكم." : "باقي على رمضان " + remaining + (remaining == 1 ? " يوم واحد." : " يومًا.");
        }
        boolean prayer = intent.getBooleanExtra("prayer", false);
        boolean adhan = intent.getBooleanExtra("adhan", false);
        // Re-check the latest user preference when the alarm fires, not only when it was scheduled.
        if (prayer && adhan && !context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).getBoolean("enabled", true)) {
            adhan = false;
        }
        boolean dhikr = intent.getBooleanExtra("dhikr", false);
        if (dhikr && intent.getBooleanExtra("rotate", false)) {
            int period = currentPeriod();
            String[] items;
            String idxKey;
            if (period == 1) {
                items = getPool(context, 1);
                idxKey = "idx_morning";
                titleX = "☀️ ذكر الصباح";
            } else if (period == 2) {
                items = getPool(context, 2);
                idxKey = "idx_evening";
                titleX = "🌙 ذكر المساء";
            } else {
                items = getPool(context, 0);
                idxKey = "idx";
                titleX = "🤍 تذكير بالذكر";
            }
            android.content.SharedPreferences sp = context.getSharedPreferences("dhikr", Context.MODE_PRIVATE);
            int idx = sp.getInt(idxKey, 0) % items.length;
            messageX = items[idx];
            // خطوة كبيرة أولية مع n تضمن المرور على كل العبارات بترتيب مختلط دون تكرار قريب.
            sp.edit().putInt(idxKey, (idx + shuffleStride(items.length)) % items.length).apply();
        }

        final String title = titleX;
        final String message = messageX;
        // Daily adhkar use a persistent floating banner/bubble when overlay permission exists.
        // Otherwise, post a normal tappable notification as a reliable fallback.
        if (intent.getBooleanExtra("adhkar_daily", false)) {
            if (OverlayDhikr.canDraw(context)) {
                OverlayDhikr.showPersistentDaily(context, title, message,
                    intent.getStringExtra("open_adhkar"), null);
                return;
            }
        }
        if (dhikr && OverlayDhikr.canDraw(context)
                && context.getSharedPreferences("dhikr", Context.MODE_PRIVATE).getBoolean("overlay", false)) {
            // If the overlay cannot be drawn (e.g. blocked by the phone vendor), fall back to a notification.
            OverlayDhikr.show(context, title, message, new Runnable() {
                @Override public void run() { postNotification(context, intent, title, message); }
            });
            return;
        }
        postNotification(context, intent, title, message);
    }

    private static void postNotification(Context context, Intent intent, String title, String message) {
        boolean prayer = intent.getBooleanExtra("prayer", false);
        boolean adhan = intent.getBooleanExtra("adhan", false);
        boolean dhikr = intent.getBooleanExtra("dhikr", false);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Uri adhanSound = adhanSoundUri(context);

        String channelId = CHANNEL_ID;
        if (prayer) channelId = adhan ? CHANNEL_ADHAN : CHANNEL_PRAYER_SILENT;
        if (dhikr) channelId = CHANNEL_DHIKR;
        boolean dailyAdhkar = intent.getBooleanExtra("adhkar_daily", false);
        boolean quranDaily = intent.getBooleanExtra("quran_daily", false);
        boolean ramadanDaily = intent.getBooleanExtra("ramadan_daily", false);
        if (dailyAdhkar || quranDaily) channelId = CHANNEL_ADHKAR;
        if (ramadanDaily) channelId = CHANNEL_RAMADAN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels(manager, adhanSound);
        }

        // Adhan: play it from a foreground service on the alarm stream (independent of channel sound settings).
        if (prayer && adhan && adhanSound != null) {
            try {
                AdhanService.start(context, title, message,
                    intent.getIntExtra("notificationId", (int) System.currentTimeMillis()));
                return;
            } catch (Throwable t) {
                // Could not start the service: fall through to a normal notification with the adhan channel sound.
            }
        }

        Intent openApp = new Intent(context, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String adhkarTarget = intent.getStringExtra("open_adhkar");
        if (adhkarTarget != null) openApp.putExtra("open_adhkar", adhkarTarget);
        int notificationId = intent.getIntExtra("notificationId", (int) System.currentTimeMillis());
        PendingIntent contentIntent = PendingIntent.getActivity(
            context, notificationId, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        android.app.Notification.Builder builder =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(context, channelId)
                : new android.app.Notification.Builder(context);

        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(!dailyAdhkar)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setContentIntent(contentIntent);
        if (dailyAdhkar) {
            // Keep the reminder in the notification shade until the user explicitly closes it.
            builder.setOngoing(true).setCategory(android.app.Notification.CATEGORY_REMINDER);
            Intent dismiss = new Intent(context, ReminderReceiver.class);
            dismiss.setAction(ACTION_DISMISS_ADHKAR);
            dismiss.putExtra("notificationId", notificationId);
            PendingIntent dismissPi = PendingIntent.getBroadcast(context, notificationId + 100,
                dismiss, PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "✕ إغلاق", dismissPi);
        }

        // Older Android versions do not read channel sounds, so set the sound on the notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && prayer) {
            if (adhan && adhanSound != null) builder.setSound(adhanSound, AudioManager.STREAM_NOTIFICATION);
            else if (!adhan) builder.setSound(null);
        }

        manager.notify(intent.getIntExtra("notificationId", (int) System.currentTimeMillis()), builder.build());
    }

    private static Uri adhanSoundUri(Context context) {
        String voice = context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).getString("voice", "original");
        String fileName = "qatami".equals(voice) ? "adhan_nasser_alqatami.mp3" : "adhan_makkah.mp3";
        File audioFile = new RemoteAssetPackManager(context).getAudioFile(fileName);
        if (audioFile == null) return null;
        try {
            Uri sound = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", audioFile);
            try { context.grantUriPermission("com.android.systemui", sound, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            return sound;
        } catch (Exception e) {
            android.util.Log.w("ReminderReceiver", "Could not expose the private adhan file to the notification system", e);
            return null;
        }
    }

    private static void createChannels(NotificationManager manager, Uri adhanSound) {
        manager.deleteNotificationChannel("fasting_reminders");
        manager.deleteNotificationChannel("dhikr_reminders");
        manager.deleteNotificationChannel("prayer_silent");
        NotificationChannel fasting = new NotificationChannel(
            CHANNEL_ID, "تنبيهات الصيام", NotificationManager.IMPORTANCE_HIGH);
        fasting.setDescription("تنبيهات أيام الصيام المستحب");
        manager.createNotificationChannel(fasting);

        // The sound of an existing channel cannot change, so recreate it if the adhan file changed.
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ADHAN);
        boolean soundChanged = existing != null &&
            ((existing.getSound() == null) != (adhanSound == null) ||
             (existing.getSound() != null && !existing.getSound().equals(adhanSound)));
        if (soundChanged) manager.deleteNotificationChannel(CHANNEL_ADHAN);

        NotificationChannel adhan = new NotificationChannel(
            CHANNEL_ADHAN, "أذان الصلاة", NotificationManager.IMPORTANCE_HIGH);
        adhan.setDescription("تنبيه الصلاة مع صوت الأذان");
        if (adhanSound != null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            adhan.setSound(adhanSound, attrs);
        }
        manager.createNotificationChannel(adhan);

        NotificationChannel silent = new NotificationChannel(
            CHANNEL_PRAYER_SILENT, "تنبيه الصلاة بدون أذان", NotificationManager.IMPORTANCE_DEFAULT);
        silent.setDescription("تنبيه الصلاة دون صوت");
        silent.setSound(null, null);
        manager.createNotificationChannel(silent);

        NotificationChannel dhikr = new NotificationChannel(
            CHANNEL_DHIKR, "تذكير الذكر", NotificationManager.IMPORTANCE_HIGH);
        dhikr.setDescription("تنبيهات الصلاة على النبي والاستغفار والذكر");
        manager.createNotificationChannel(dhikr);

        NotificationChannel adhkar = new NotificationChannel(
            CHANNEL_ADHKAR, "تذكير أذكار الصباح والمساء", NotificationManager.IMPORTANCE_HIGH);
        adhkar.setDescription("تذكير ثابت يبقى حتى تضغط على زر الإغلاق");
        manager.createNotificationChannel(adhkar);

        NotificationChannel ramadan = new NotificationChannel(
            CHANNEL_RAMADAN, "العد التنازلي لرمضان", NotificationManager.IMPORTANCE_DEFAULT);
        ramadan.setDescription("إشعار يومي يوضح عدد الأيام المتبقية على رمضان");
        manager.createNotificationChannel(ramadan);
    }
}
