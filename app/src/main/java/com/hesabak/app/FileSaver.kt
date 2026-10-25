package com.hesabak.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v7: كتابة الملفات في Downloads/Hesabak-data/excel و Downloads/Hesabak-data/json (والصور في Downloads مباشرة).
 * تُستعمل من الجسر (حفظ يدوي/تلقائي من الصفحة) ومن الخدمة الخلفية (AutoBackup).
 *
 * إصلاح خطأ «فشل التحميل» المتقطع (v7):
 *  1) كان التحقق من الحجم بعد الكتابة يقرأ عمود SIZE من MediaStore، وهذا العمود يتأخر في التحديث بعد الإغلاق.
 *     فيُرفض ملف سليم ويُحذف (إن كان جديداً)، ثم ينجح بعد لحظات عند المحاولة الثانية. الآن نتحقق من طول الملف نفسه.
 *  2) إن كان سجل MediaStore القديم لملف نفس اليوم «شبحاً» (حُذف الملف من مدير الملفات) أو لا نملك صلاحية الكتابة عليه،
 *     كان الحفظ يفشل. الآن نحذف السجل ونُنشئ ملفاً جديداً (وإن تعارض الاسم نضيف الوقت للاسم).
 */
object FileSaver {
    private const val TAG = "HesabakSaver"
    const val ROOT = "Hesabak-data"

    fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").replace("..", "_").trim().take(120)
        return s.ifEmpty { "hesabak_file" }
    }

    /** المجلد الفرعي داخل Downloads حسب الامتداد. "" = Downloads مباشرة. */
    fun subFolderFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "xlsx", "xls" -> "$ROOT/excel"
        "json" -> "$ROOT/json"
        else -> ""
    }

    fun save(ctx: Context, bytes: ByteArray, name: String, mime: String) {
        val sub = subFolderFor(name)
        if (Build.VERSION.SDK_INT >= 29) writeScoped(ctx, bytes, name, mime, sub) else writeLegacy(ctx, bytes, name, sub)
    }

    // ───────────── Android 10+ ─────────────
    @androidx.annotation.RequiresApi(29)
    private fun writeScoped(ctx: Context, bytes: ByteArray, name: String, mime: String, sub: String) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = if (sub.isEmpty()) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_DOWNLOADS + "/" + sub

        // 1) نعيد استخدام ملفنا إن وُجد (نفس اليوم). إن فشل لأي سبب نحذف السجل ونكمل بإنشاء جديد.
        val existing = findExisting(resolver, collection, name, relPath)
        if (existing != null) {
            try {
                overwrite(resolver, existing, bytes)
                return
            } catch (e: Exception) {
                Log.w(TAG, "تعذّر استبدال الملف الموجود، سيُنشأ ملف جديد: $name", e)
                try { resolver.delete(existing, null, null) } catch (_: Exception) {}
            }
        }

        // 2) إنشاء جديد (محاولتان: بالاسم نفسه ثم باسم فيه الوقت)
        var last: Exception? = null
        for (attempt in 0..1) {
            var uri: Uri? = null
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, if (attempt == 0) name else uniqueName(name))
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(collection, v) ?: throw IllegalStateException("MediaStore.insert رجع null")
                overwrite(resolver, uri, bytes)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "فشل إنشاء الملف (محاولة ${attempt + 1}): $name", e)
                if (uri != null) try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        }
        throw last ?: IllegalStateException("فشل الحفظ")
    }

    @androidx.annotation.RequiresApi(29)
    private fun findExisting(resolver: ContentResolver, collection: Uri, name: String, relPath: String): Uri? = try {
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, "$relPath/"), null
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    } catch (e: Exception) { null }

    private fun overwrite(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
            ?: throw IllegalStateException("تعذر فتح مجرى الكتابة")
        // التحقق من الحجم من الملف نفسه (وليس من عمود SIZE في MediaStore الذي يتأخر تحديثه)
        val len = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
        if (len >= 0 && len != bytes.size.toLong()) throw IllegalStateException("حجم الملف بعد الكتابة لا يطابق ($len ≠ ${bytes.size})")
    }

    private fun uniqueName(name: String): String {
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($stamp)" else name.substring(0, dot) + " ($stamp)" + name.substring(dot)
    }

    // ───────────── Android 5–9 ─────────────
    @Suppress("DEPRECATION")
    private fun writeLegacy(ctx: Context, bytes: ByteArray, name: String, sub: String) {
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("إذن التخزين غير ممنوح")
        var dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (sub.isNotEmpty()) dir = File(dir, sub)
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw IllegalStateException("تعذر إنشاء المجلد: ${dir.path}")
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
        if (file.length() != bytes.size.toLong()) throw IllegalStateException("حجم الملف بعد الكتابة لا يطابق")
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
    }
}
