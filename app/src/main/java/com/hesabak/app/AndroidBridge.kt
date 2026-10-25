package com.hesabak.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * الجسر window.Android. كل الدوال تُستدعى من خيط JavaBridge (ليس الرئيسي).
 * ⚠️ راجع التنبيه في MainActivity حول اعتماد shim.js على أسماء داخل index.html.
 */
class AndroidBridge(private val activity: MainActivity, private val webView: WebView) {

    private val nextId = AtomicInteger(1000)

    // ───────────── حفظ الملفات (إلزامي) ─────────────
    @JavascriptInterface
    fun saveFile(base64Data: String, fileName: String, mimeType: String) {
        val ok = try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val mime = mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val safe = FileSaver.sanitize(fileName)
            // v7: محاولة ثانية تلقائية داخل الجسر قبل أن نُبلغ الصفحة بالفشل (الصفحة تعيد المحاولة أيضاً)
            var lastErr: Exception? = null
            for (i in 0..1) {
                try { FileSaver.save(activity, bytes, safe, mime); lastErr = null; break }
                catch (e: Exception) { lastErr = e; Log.w(TAG, "saveFile محاولة ${i + 1} فشلت: $fileName", e); if (i == 0) Thread.sleep(400) }
            }
            if (lastErr != null) throw lastErr
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveFile فشل: $fileName", e)
            false
        }
        val fn = if (ok) "onFileSaved" else "onFileError"
        // JSONObject.quote يُنتج literal آمناً حتى مع الأسماء العربية وعلامات الاقتباس
        webView.post { webView.evaluateJavascript("window.$fn && window.$fn(${JSONObject.quote(fileName)})", null) }
    }

    // ───────────── v12: الخروج من التطبيق بعد تأكيد المستخدم في الصفحة ─────────────
    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread { activity.finish() }
    }

    // ───────────── النسخة التلقائية والتطبيق مغلق (v7) ─────────────
    /** الصفحة تُرسل نسخة JSON كاملة وملف xlsx (Base64، أو "" إن لم يوجد مشترون) وبادئة اسم Excel. */
    @JavascriptInterface
    fun syncMirror(json: String, xlsxBase64: String, excelPrefix: String, excelSig: String) {
        try { AutoBackup.writeMirror(activity, json, xlsxBase64, excelPrefix, excelSig) }
        catch (e: Exception) { Log.w(TAG, "syncMirror", e) }
    }

    /** آخر يوم (YYYY-MM-DD) حُفظت فيه نسخة تلقائية بنجاح، أو "". يقرؤه early.js عند الإقلاع. */
    @JavascriptInterface
    fun lastAutoBackupDay(): String = AutoBackup.lastOkDay(activity)

    /** v8: بصمة آخر جدول Excel حُفظ فعلاً (من الصفحة أو من الخدمة الخلفية)، أو "". */
    @JavascriptInterface
    fun lastExcelSig(): String = AutoBackup.lastExcelSig(activity)

    /** v8: تُبلَّغ من early.js بعد تأكيد حفظ ملف Excel من الصفحة (يدوي أو تلقائي). */
    @JavascriptInterface
    fun setExcelSig(sig: String) { AutoBackup.setExcelSig(activity, sig) }

    /** v13: فحص فوري «هل حان وقت نسخة اليوم؟» (آمن ومتكرر: لا يحفظ إلا إن حان الوقت ولم تُحفظ نسخة اليوم). */
    @JavascriptInterface
    fun runBackupNow() { AutoBackup.checkNow(activity) }

    /** v14: وقت النسخ اليومي الذي اختاره المستخدم (ساعة 0-23، دقيقة 0-59). الصفحة تستدعيها عند التغيير وعند الإقلاع. */
    @JavascriptInterface
    fun setBackupSlot(hour: Int, minute: Int) {
        try { AutoBackup.setSlot(activity, hour, minute) } catch (e: Exception) { Log.w(TAG, "setBackupSlot", e) }
    }

    /** v14: الوقت المعتمد حالياً في الجانب الأصلي بصيغة HH:MM. */
    @JavascriptInterface
    fun getBackupSlot(): String = "%02d:%02d".format(AutoBackup.slotHour(activity), AutoBackup.slotMin(activity))

    /** v13: هل إذن التنبيهات الدقيقة ممنوح؟ (لتوقيت النسخة الساعة 12 بالضبط) */
    @JavascriptInterface
    fun canExactAlarm(): Boolean = AutoBackup.canExact(activity)

    /** v13: يفتح شاشة «التنبيهات والتذكيرات» لهذا التطبيق (Android 12+). */
    @JavascriptInterface
    fun openExactAlarmSettings() { activity.runOnUiThread { activity.openExactAlarmSettings() } }

    // ───────────── الإشعارات (اختياري) ─────────────
    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun notify(title: String, body: String) {
        if (!activity.hasNotifyPermission()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            activity, 0,
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(activity).notify(nextId.incrementAndGet(), n) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
    }

    /** حالة الإذن فقط، بلا أي طلب (تستعملها Notification.permission في early.js). */
    @JavascriptInterface
    fun hasNotifyPermission(): Boolean = activity.hasNotifyPermission()

    // ───────────── قارئ الباركود (v9) ─────────────
    /** يفتح الكاميرا لمسح باركود. النتيجة تصل عبر window.onBarcodeScanned(text) أو window.onBarcodeCancel(). */
    @JavascriptInterface
    fun scanBarcode() {
        activity.runOnUiThread { activity.scanBarcode() }
    }

    // ───────────── المظهر والمشاركة (v10) ─────────────
    /** ألوان أشرطة النظام (#RRGGBB): الشريط العلوي، السفلي، وخلفية الصفحة. */
    @JavascriptInterface
    fun setBars(top: String, bottom: String, page: String) {
        activity.runOnUiThread { activity.applyBars(top, bottom, page) }
    }

    /** هل النظام في الوضع الداكن؟ (للمظهر «تلقائي») */
    @JavascriptInterface
    fun isSystemDark(): Boolean = activity.isSystemDark()

    /** مشاركة نص عبر نافذة المشاركة. */
    @JavascriptInterface
    fun shareText(title: String, text: String) {
        activity.runOnUiThread { activity.shareText(title, text) }
    }

    // ───────────── رسالة SMS (v3) ─────────────
    /**
     * يفتح تطبيق الرسائل برقم ونص جاهزين؛ المستخدم يضغط «إرسال» بنفسه.
     * ⚠️ عمداً: Intent.ACTION_SENDTO فقط. لا SmsManager ولا إذن SEND_SMS (لا يعملان للتطبيقات العادية على Android 15+).
     */
    @JavascriptInterface
    fun openSms(phone: String, message: String) {
        activity.runOnUiThread {
            try {
                val number = phone.filter { it.isDigit() || it == '+' }
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                intent.putExtra("sms_body", message)
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.sms_missing, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "openSms", e)
            }
        }
    }

    // ───────────── اختيار ملف (النسخة الكاملة) ─────────────
    /** النتيجة تصل عبر window.onFilePicked(text) أو window.onFilePickCancel(). */
    @JavascriptInterface
    fun pickFile(mimeType: String) {
        activity.runOnUiThread { activity.pickFile(mimeType) }
    }

    /** يعيد حالة الإذن الحالية فوراً، وإن لم يكن ممنوحاً يطلبه (Android 13+) بشكل غير متزامن. */
    @JavascriptInterface
    fun requestNotifyPermission(): Boolean {
        val granted = activity.hasNotifyPermission()
        if (!granted) activity.runOnUiThread { activity.requestNotifyPermissionIfNeeded() }
        return granted
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, activity.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private companion object {
        const val TAG = "HesabakBridge"
        const val CHANNEL_ID = "hesabak_default"
    }
}
