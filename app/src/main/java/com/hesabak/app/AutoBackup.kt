package com.hesabak.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v7: نسخة احتياطية يومية (JSON + Excel) والتطبيق مغلق.
 *
 * الفكرة: البيانات في localStorage داخل WebView ولا تستطيع خدمة خلفية قراءتها. لذلك الصفحة ترسل «مرآة»
 * (Android.syncMirror) عند كل حفظ وعند إخفاء التطبيق: نص JSON الكامل وملف xlsx جاهز. تُخزَّن في filesDir/mirror.
 * وخدمة WorkManager دورية (كل 15 دقيقة، وهو الحد الأدنى في أندرويد) تفحص: هل مرّ وقت النسخة اليومية (23:59)
 * ولم تُنفَّذ نسخة هذا اليوم؟ فتكتب الملفين في Downloads/Hesabak-data/json و /excel بتاريخ يوم الخانة.
 * الفحص idempotent (مفتاح done_day)، فلو تأخر التنفيذ (وضع Doze/إغلاق الجهاز) يُنفَّذ عند أول فرصة ولا يتكرر.
 *
 * v13 (دقّة التوقيت): WorkManager وحده لا يضمن الوقت (Doze قد يؤجّله دقائق أو ساعات، فكانت النسخة «مرات تيجي بأوقات مختلفة»).
 * لذلك أُضيف AlarmManager (setExactAndAllowWhileIdle) عند 23:59 ثم دقيقة هامش => BackupAlarmReceiver يشغّل الحفظ في وقته.
 * إن لم يكن إذن «التنبيهات الدقيقة» ممنوحاً (Android 14+ افتراضياً) نستعمل setAndAllowWhileIdle (قريب من الوقت لكن غير مضمون)،
 * وتبقى الخدمة الدورية كل 15 دقيقة شبكة أمان. والصفحة لم تعد تُصدّر بنفسها داخل التطبيق (كانت تتسابق مع الخدمة عند تغيّر اليوم).
 *
 * v14: وقت النسخة اليومية يختاره المستخدم من الإعدادات (الافتراضي 23:59). الصفحة تُبلّغ به عبر Android.setBackupSlot(h, m)
 * فيُحفظ في SharedPreferences ويُعاد جدولة المنبّه والخدمة فوراً. (index.html: slotHM/lastSlot/nextSlot يستعملان نفس القيمة.)
 * إن اختار المستخدم وقتاً مرّ اليوم ولم تُحفظ نسخة اليوم، تُحفظ عند أول فحص (نفس سلوك «فاتت الساعة»).
 */
object AutoBackup {
    private const val TAG = "HesabakAutoBackup"
    private const val PREFS = "auto_backup"
    private const val K_DONE = "done_day"          // آخر خانة عولجت (تمنع التكرار)
    private const val K_OK = "ok_day"              // آخر يوم حُفظ فعلاً بنجاح (تقرأه الصفحة لتحديث «آخر نسخة»)
    private const val K_EXCEL_PREFIX = "excel_prefix"
    private const val K_JSON_DAY = "json_day"      // v13: آخر يوم حُفظ فيه JSON (يمنع إعادة الحفظ إن فشل Excel وحده)
    private const val K_XLSX_DAY = "xlsx_day"      // v13: آخر يوم عولج فيه Excel (حُفظ أو تُخطّي لعدم وجود تغييرات)
    private const val ALARM_REQ = 7001
    private const val K_MIRROR_SIG = "mirror_sig"      // v8: بصمة الجدول في المرآة الحالية
    private const val K_LAST_SIG = "last_excel_sig"    // v8: بصمة آخر جدول Excel حُفظ فعلاً (من الصفحة أو الخدمة)
    private const val WORK_NAME = "hesabak-auto-backup"
    private const val SLOT_WORK_NAME = "hesabak-auto-backup-slot"
    private const val NOW_WORK_NAME = "hesabak-auto-backup-now"
    const val ACTION_SLOT = "com.hesabak.app.BACKUP_SLOT"
    private const val CHANNEL_ID = "hesabak_default"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val DEFAULT_SLOT_HOUR = 23
    const val DEFAULT_SLOT_MIN = 59
    private const val K_SLOT_H = "slot_h"          // v14: ساعة النسخ اليومي التي اختارها المستخدم
    private const val K_SLOT_M = "slot_m"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun mirrorDir(ctx: Context) = File(ctx.filesDir, "mirror").apply { mkdirs() }
    private fun fmt(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    fun slotHour(ctx: Context): Int = prefs(ctx).getInt(K_SLOT_H, DEFAULT_SLOT_HOUR).coerceIn(0, 23)
    fun slotMin(ctx: Context): Int = prefs(ctx).getInt(K_SLOT_M, DEFAULT_SLOT_MIN).coerceIn(0, 59)

    /** v14: تغيير وقت النسخ اليومي (من الإعدادات). لا يفعل شيئاً إن لم تتغير القيمة؛ وإلا يعيد جدولة المنبّه والخدمة ويفحص فوراً. */
    fun setSlot(ctx: Context, hour: Int, minute: Int) {
        if (hour !in 0..23 || minute !in 0..59) return
        if (hour == slotHour(ctx) && minute == slotMin(ctx)) return
        prefs(ctx).edit().putInt(K_SLOT_H, hour).putInt(K_SLOT_M, minute).apply()
        scheduleNextSlot(ctx)
        checkNow(ctx)
    }

    /** يوم آخر خانة (وقت النسخ اليومي) مرّ وقتها. قبل وقت الخانة اليوم = أمس. */
    fun slotDay(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, slotHour(ctx)); set(Calendar.MINUTE, slotMin(ctx))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -1)
        return fmt(c.timeInMillis)
    }

    fun ensureScheduled(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<DailyBackupWorker>(15, TimeUnit.MINUTES).build()
            androidx.work.WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) { Log.w(TAG, "تعذّرت الجدولة", e) }
        scheduleNextSlot(ctx)
        checkNow(ctx)
    }

    /** فحص فوري (يستدعيه فتح التطبيق، وتغيّر الوقت/التاريخ، وإعادة التشغيل). آمن: لا يحفظ إلا إن حان الوقت ولم تُحفظ نسخة اليوم. */
    fun checkNow(ctx: Context) {
        try {
            androidx.work.WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DailyBackupWorker>().build())
        } catch (e: Exception) { Log.w(TAG, "تعذّر الفحص الفوري", e) }
    }

    /** v13: هل يستطيع التطبيق جدولة تنبيهات دقيقة؟ (Android 12+ يحتاج إذن «التنبيهات والتذكيرات»؛ ومن 14 يكون مرفوضاً افتراضياً) */
    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() } catch (e: Exception) { false }
    }

    /** أقرب لحظة بعد وقت الخانة. للافتراضي 23:59 نضيف دقيقة هامش (=> 00:00 تماماً)؛ أما الوقت الذي يختاره المستخدم فيُنفَّذ في وقته بالضبط. */
    private fun nextTriggerMillis(ctx: Context, now: Long): Long {
        val h = slotHour(ctx); val m = slotMin(ctx)
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (h == DEFAULT_SLOT_HOUR && m == DEFAULT_SLOT_MIN) add(Calendar.MINUTE, 1)   // 00:00 هامش بعد الخانة
            if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    /**
     * يجدول الحفظ القادم بطريقتين:
     *  1) AlarmManager (الأساس): يوقظ الجهاز في الوقت تقريباً بالثانية ويشغّل BackupAlarmReceiver.
     *  2) WorkManager لمرة واحدة (احتياط): إن تأخر المنبّه لأي سبب (توفير بطارية/شركة الهاتف).
     * والخدمة الدورية (15 دقيقة) تبقى شبكة أمان ثالثة. كلها idempotent (done_day) فلا يتكرر الحفظ.
     */
    fun scheduleNextSlot(ctx: Context) {
        val now = System.currentTimeMillis()
        val at = nextTriggerMillis(ctx, now)
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQ,
                Intent(ctx, BackupAlarmReceiver::class.java).setAction(ACTION_SLOT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                when {
                    Build.VERSION.SDK_INT >= 23 && canExact(ctx) -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    Build.VERSION.SDK_INT >= 23 -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    else -> am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "رُفض المنبّه الدقيق، نستعمل منبّهاً غير دقيق", e)
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) { Log.w(TAG, "تعذّرت جدولة المنبّه", e) }
        try {
            val req = OneTimeWorkRequestBuilder<DailyBackupWorker>()
                .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                .build()
            androidx.work.WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(SLOT_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        } catch (e: Exception) { Log.w(TAG, "تعذّرت جدولة الخانة القادمة", e) }
    }

    /** تستدعيها الصفحة عبر الجسر. تكتب المرآة بشكل ذري (ملف مؤقت ثم إعادة تسمية). */
    @Synchronized
    fun writeMirror(ctx: Context, json: String, xlsxBase64: String, excelPrefix: String, excelSig: String) {
        val d = mirrorDir(ctx)
        atomicWrite(File(d, "backup.json"), json.toByteArray(Charsets.UTF_8))
        val x = File(d, "excel.xlsx")
        if (xlsxBase64.isEmpty()) x.delete() else atomicWrite(x, Base64.decode(xlsxBase64, Base64.DEFAULT))
        val p = prefs(ctx)
        val e = p.edit().putString(K_EXCEL_PREFIX, excelPrefix).putString(K_MIRROR_SIG, excelSig)
        // أول مرة: لا نُنفّذ نسخة «متأخرة» فور التحديث، بل عند أول خانة قادمة
        if (!p.contains(K_DONE)) e.putString(K_DONE, slotDay(ctx))
        e.apply()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: ${target.name}") }
    }

    /** v8: بصمة آخر جدول Excel حُفظ فعلاً، أو "". */
    fun lastExcelSig(ctx: Context): String = prefs(ctx).getString(K_LAST_SIG, "") ?: ""

    /** v8: الصفحة تُبلّغ بعد حفظ ملف Excel (JSON-quoted من localStorage أو نص عادي). */
    fun setExcelSig(ctx: Context, sig: String) {
        val clean = sig.trim().removeSurrounding("\"")
        if (clean.isNotEmpty()) prefs(ctx).edit().putString(K_LAST_SIG, clean).apply()
    }

    /** آخر يوم (YYYY-MM-DD) حُفظت فيه نسخة تلقائية بنجاح، أو "". */
    fun lastOkDay(ctx: Context): String = prefs(ctx).getString(K_OK, "") ?: ""

    /**
     * تُنفَّذ من المنبّه أو الخدمة. ترجع true إن اكتمل حفظ يوم الخانة.
     * v13: حالة JSON وExcel منفصلتان لليوم نفسه، فإن نجح JSON وفشل Excel لا يُعاد حفظ JSON (ولا يتكرر الإشعار) عند المحاولة التالية.
     */
    @Synchronized
    fun runIfDue(ctx: Context): Boolean {
        val p = prefs(ctx)
        val day = slotDay(ctx)
        val done = p.getString(K_DONE, null) ?: return false      // لم يفتح المستخدم التطبيق بعد التحديث
        if (done >= day) return false
        val json = File(mirrorDir(ctx), "backup.json")
        if (!json.exists()) return false

        var ok = true
        if (p.getString(K_JSON_DAY, "") != day) {
            try {
                FileSaver.save(ctx, json.readBytes(), FileSaver.sanitize("hesabak-backup-$day.json"), "application/json")
                p.edit().putString(K_JSON_DAY, day).apply()
            } catch (e: Exception) { ok = false; Log.w(TAG, "فشل حفظ JSON التلقائي", e) }
        }

        var excelSaved = false
        val xlsx = File(mirrorDir(ctx), "excel.xlsx")
        if (xlsx.exists() && p.getString(K_XLSX_DAY, "") != day) {
            val sig = p.getString(K_MIRROR_SIG, "") ?: ""
            if (sig.isNotEmpty() && sig == p.getString(K_LAST_SIG, "")) {
                // v8: الجدول مطابق لآخر ملف Excel حُفظ فعلاً => لا ملف جديد
                Log.i(TAG, "Excel بلا تغييرات منذ آخر ملف محفوظ، تُخطّي")
                p.edit().putString(K_XLSX_DAY, day).apply()
            } else {
                val prefix = p.getString(K_EXCEL_PREFIX, null) ?: "كشف - حسابك - "
                try {
                    FileSaver.save(ctx, xlsx.readBytes(), FileSaver.sanitize("$prefix$day.xlsx"), XLSX_MIME)
                    excelSaved = true
                    val e = p.edit().putString(K_XLSX_DAY, day)
                    if (sig.isNotEmpty()) e.putString(K_LAST_SIG, sig)
                    e.apply()
                } catch (e: Exception) { ok = false; Log.w(TAG, "فشل حفظ Excel التلقائي", e) }
            }
        }
        if (ok) {
            p.edit().putString(K_DONE, day).putString(K_OK, day).apply()
            notifyDone(ctx, day, excelSaved)
        }
        return ok
    }

    private fun notifyDone(ctx: Context, day: String, excelSaved: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = if (excelSaved) "حُفظت نسخة JSON وملف Excel ليوم $day في Hesabak-data"
                       else "حُفظت نسخة JSON ليوم $day (جدول Excel بلا تغييرات فلم يُحفظ ملف جديد)"
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("💾 نسخة احتياطية تلقائية")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(ctx).notify(2001, n)
        } catch (e: Exception) { Log.w(TAG, "notify", e) }
    }
}

class DailyBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { AutoBackup.runIfDue(applicationContext) } catch (e: Exception) { Log.w("HesabakAutoBackup", "worker", e) }
        AutoBackup.scheduleNextSlot(applicationContext)   // الخانة القادمة
        return Result.success()   // المحاولة التالية بعد 15 دقيقة تلقائياً إن لم تنجح هذه
    }
}

/** v13: يستقبل منبّه 23:59 الدقيق ويحفظ فوراً (في خيط خلفي عبر goAsync) ثم يجدول الخانة التالية. */
class BackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try { AutoBackup.runIfDue(app) } catch (e: Exception) { Log.w("HesabakAutoBackup", "alarm", e) }
            finally {
                AutoBackup.scheduleNextSlot(app)
                pending.finish()
            }
        }.start()
    }
}

/**
 * يُستقبل عند: تغيّر الوقت يدوياً (TIME_SET) · تغيّر المنطقة الزمنية · تغيّر التاريخ (يُرسَل عند منتصف الليل أيضاً) · إعادة تشغيل الجهاز ·
 * v13: تحديث التطبيق (المنبّهات تُمسح عند التحديث) · منح/سحب إذن التنبيهات الدقيقة.
 * مؤقتات WorkManager لا تتأثر بتغيير ساعة النظام، لذلك نعيد الجدولة ونفحص فوراً.
 */
class BackupTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("HesabakAutoBackup", "broadcast: ${intent?.action}")
        AutoBackup.scheduleNextSlot(context)
        AutoBackup.checkNow(context)
    }
}
