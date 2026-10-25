package com.hesabak.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * ⚠️⚠️ اعتماد على index.html — اقرأ قبل تحديث الـ HTML ⚠️⚠️
 *
 * يُحقن سكربتان من assets/native/:
 *   - early.js (بداية المستند + نسخة احتياطية في onPageFinished): خطافات على مستوى المنصة
 *       HTMLAnchorElement.click (روابط blob: مع download) · Storage.setItem/getItem · Notification
 *     لأن index.html يستدعي checkAuto()/checkDailyExport أثناء الإقلاع، أي قبل onPageFinished.
 *   - shim.js (onPageFinished): يعيد تعريف downloadBlob / mobileNotify / askNotifyPermission.
 *
 * ما يعتمد عليه الحقن في index.html (إن تغيّر أي منها يتعطل بصمت):
 *   downloadBlob عبر <a download href=blob:> · المفتاح 'ledger_excel_last_day' · toast · renderBar · renderBanner
 *   mobileNotify · askNotifyPermission · المفتاح 'ledger_settings' والحقل saveNotif (قرار طلب إذن الإشعارات)
 *   ميزة النسخة الكاملة: window.onFilePicked(text) / window.onFilePickCancel()
 *   v3: زر SMS في index.html يستدعي Android.openSms(phoneLocal, msg) — راجع AndroidBridge (ACTION_SENDTO فقط)
 *   v12: زر الرجوع يستدعي window.hesabakBack() (يعيد 'ok' | 'bg' | 'none') · وزر «خروج» في الصفحة يستدعي Android.exitApp()
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var notifyChecked = false
    private var notifyRequestInFlight = false
    private var injectEarlyIntoHtml = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // v10: ألوان أشرطة النظام (تُبلَّغ من الصفحة عبر Android.setBars وتُحفظ لأول لحظة في التشغيل التالي كي لا يومض أبيض في الوضع الداكن)
    private var topInset = 0
    private var barTop = 0xFF0E1A33.toInt()
    private var barBottom = Color.WHITE
    private var pageBg = Color.WHITE

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
    private val shimJs by lazy { readAsset("native/shim.js") }
    private val earlyJs by lazy { readAsset("native/early.js") }

    private val notifyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifyRequestInFlight = false }
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* AndroidBridge يتحقق وقت الحفظ */ }

    // اختيار ملف للنسخة الكاملة (Android.pickFile)
    private val pickDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> handlePicked(uri) }
    // احتياط <input type="file"> عبر WebChromeClient.onShowFileChooser
    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileChooserCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileChooserCallback = null
        }

    // v9: قارئ الباركود (ZXing). يطلب إذن الكاميرا بنفسه عند أول استعمال.
    private val scanLauncher =
        registerForActivityResult(ScanContract()) { res ->
            val text = res.contents
            if (text.isNullOrEmpty()) {
                // v10: إن رُفض إذن الكاميرا تُبلغ الصفحة لتعرض الإرشاد بدل صمت
                val denied = res.originalIntent?.getBooleanExtra("MISSING_CAMERA_PERMISSION", false) == true
                webView.evaluateJavascript("window.onBarcodeCancel && window.onBarcodeCancel($denied)", null)
            } else webView.evaluateJavascript("window.onBarcodeScanned && window.onBarcodeScanned(${JSONObject.quote(text)})", null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge (البند 5.6)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        barTop = ui.getInt("bt", barTop); barBottom = ui.getInt("bb", barBottom); pageBg = ui.getInt("bp", pageBg)

        root = FrameLayout(this)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(webView)
        setContentView(root)
        window.setBackgroundDrawable(ColorDrawable(pageBg))
        paintBars()   // آخر ألوان محفوظة (لا وميض أبيض في الوضع الداكن)

        // المحتوى داخل حدود الشريطين/الحافة/لوحة المفاتيح. WebView لا يضمن تعبئة env(safe-area-inset-*)
        // لأشرطة النظام، لذا نطبّق الحشوة هنا بشكل حتمي.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            if (topInset != i.top) { topInset = i.top; paintBars() }
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()

        // v12: الرجوع خطوة واحدة داخل التطبيق (نافذة ← تفاصيل ← الرئيسية). في الرئيسية تعرض الصفحة «هل تريد الخروج؟»
        // ولا يُغلق التطبيق إلا بتأكيد المستخدم (Android.exitApp). المنطق في window.hesabakBack داخل index.html.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{return window.hesabakBack?window.hesabakBack():'none'}catch(e){return 'none'}})()"
                ) { r ->
                    when (r?.trim('"')) {
                        "ok" -> Unit                                  // عالجتها الصفحة
                        "bg" -> moveTaskToBack(true)                  // شاشة قفل PIN: لا نغلق، نُخفي التطبيق فقط
                        else -> if (webView.canGoBack()) webView.goBack() else finish()   // الصفحة لم تجهز بعد
                    }
                }
            }
        })

        // Android 6–9: الكتابة في Downloads تحتاج إذناً وقت التشغيل
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        AutoBackup.ensureScheduled(this)   // v7: نسخة يومية والتطبيق مغلق

        webView.loadUrl(START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // ⚠️ إلزامي لـ localStorage
            databaseEnabled = true
            allowFileAccess = false             // نحمّل عبر WebViewAssetLoader، لا file://
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // الـ HTML يدير الثيم بنفسه: امنع التعتيم التلقائي من WebView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        } else if (Build.VERSION.SDK_INT in 29..32 && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
        }

        webView.addJavascriptInterface(AndroidBridge(this, webView), "Android")

        // early.js عند بداية المستند (إن كان مدعوماً). وإلا يبقى shim.js في onPageFinished فقط.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, earlyJs, setOf(ORIGIN))
        } else {
            // WebView قديم: نضيف early.js داخل استجابة index.html نفسها (الملف على القرص لا يتغير)
            injectEarlyIntoHtml = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (injectEarlyIntoHtml && request.url.host == ASSET_HOST && request.url.path == "/assets/index.html") {
                    patchedIndexResponse()?.let { return it }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(earlyJs, null)   // idempotent؛ احتياط لو لم يعمل حقن بداية المستند
                view.evaluateJavascript(shimJs, null)
                view.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
                maybeRequestNotifyPermission(view)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    chooserLauncher.launch(params.createIntent()); true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null; callback.onReceiveValue(null); false
                }
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    /** واتساب وكل الروابط الخارجية تُفتح خارج التطبيق (5.4). الصفحة المحلية وblob:/data: تبقى داخلاً. */
    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme == "blob" || scheme == "data" || scheme == "about") return false
        if (scheme == "https" && uri.host == ASSET_HOST) return false

        val isWhatsApp = scheme == "whatsapp" || uri.host == "wa.me" || uri.host == "api.whatsapp.com"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            if (isWhatsApp) Toast.makeText(this, R.string.whatsapp_missing, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "تعذر فتح الرابط الخارجي", e)
        }
        return true
    }

    /**
     * يُطلب إذن الإشعارات (Android 13+) فقط إذا لم يعطّل المستخدم الإشعارات (settings.saveNotif !== false).
     * الإعدادات داخل localStorage ولا تتوفر في onCreate، لذلك نقرؤها بعد تحميل الصفحة (مرة واحدة).
     */
    private fun maybeRequestNotifyPermission(view: WebView) {
        if (notifyChecked || Build.VERSION.SDK_INT < 33) return
        notifyChecked = true
        val js = "(function(){try{var s=JSON.parse(localStorage.getItem('ledger_settings')||'{}');return s.saveNotif!==false}catch(e){return true}})()"
        view.evaluateJavascript(js) { r -> if (r == "true") requestNotifyPermissionIfNeeded() }
    }

    fun requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || hasNotifyPermission() || notifyRequestInFlight) return
        notifyRequestInFlight = true
        notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasNotifyPermission(): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    // ───────────── v10: المظهر وأشرطة النظام ─────────────
    fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** تُستدعى من الصفحة (Android.setBars) عند تغيّر المظهر أو ظهور قفل التطبيق. الألوان بصيغة #RRGGBB. */
    fun applyBars(topHex: String, bottomHex: String, pageHex: String) {
        try {
            barTop = Color.parseColor(topHex.trim()); barBottom = Color.parseColor(bottomHex.trim()); pageBg = Color.parseColor(pageHex.trim())
        } catch (e: IllegalArgumentException) { return }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("bt", barTop).putInt("bb", barBottom).putInt("bp", pageBg).apply()
        window.setBackgroundDrawable(ColorDrawable(pageBg))
        paintBars()
    }

    /** الشريط العلوي بلون رأس الصفحة، والسفلي بلون شريط التنقل؛ وأيقونات النظام فاتحة/داكنة حسب السطوع. */
    private fun paintBars() {
        if (Build.VERSION.SDK_INT >= 23 && topInset > 0) {
            val d = LayerDrawable(arrayOf(ColorDrawable(barBottom), ColorDrawable(barTop)))
            d.setLayerGravity(1, Gravity.TOP)
            d.setLayerHeight(1, topInset)
            root.background = d
        } else root.setBackgroundColor(barTop)
        webView.setBackgroundColor(pageBg)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(barTop) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(barBottom) > 0.5
        }
    }

    /** مشاركة نص (ملخص التقرير مثلاً) عبر نافذة المشاركة في النظام. */
    fun shareText(title: String, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, title))
        } catch (e: Exception) { Log.w(TAG, "تعذرت المشاركة", e) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // المظهر «تلقائي»: الصفحة تقرأ حالة النظام من جديد (uiMode ضمن configChanges فلا تُعاد إنشاء الواجهة)
        webView.evaluateJavascript("window.onSystemTheme && window.onSystemTheme(${isSystemDark()})", null)
    }

    /** يُستدعى من AndroidBridge.pickFile على الخيط الرئيسي. */
    fun pickFile(mimeType: String) {
        // ملفات النسخة تصل أحياناً (واتساب/إيميل) بنوع text/plain أو octet-stream، فنسمح بهما والصفحة تتحقق من المحتوى
        val types = if (mimeType == "application/json") arrayOf(mimeType, "text/plain", "application/octet-stream") else arrayOf(mimeType)
        try { pickDocLauncher.launch(types) } catch (e: ActivityNotFoundException) { sendPicked("") }
    }

    /** v13: شاشة النظام لمنح إذن التنبيهات الدقيقة لهذا التطبيق (Android 12+). */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (e: Exception) { Log.w(TAG, "تعذّر فتح إعداد التنبيهات الدقيقة", e) }
    }

    /** يُستدعى من AndroidBridge.scanBarcode على الخيط الرئيسي. */
    fun scanBarcode() {
        // v11: الكاميرا الخلفية صراحةً (cameraId=-1 ← أول كاميرا خلفية، ويرجع لأي كاميرا متاحة إن لم توجد)
        val options = ScanOptions()
            .setCameraId(-1)
            .setPrompt("وجّه الكاميرا الخلفية نحو الباركود")
            .setBeepEnabled(true)
            .setBarcodeImageEnabled(false)
            .setOrientationLocked(false)
        try { scanLauncher.launch(options) } catch (e: Exception) {
            Log.w(TAG, "تعذر فتح الماسح", e)
            webView.evaluateJavascript("window.onBarcodeCancel && window.onBarcodeCancel(false)", null)
        }
    }

    private fun handlePicked(uri: Uri?) {
        if (uri == null) { webView.evaluateJavascript("window.onFilePickCancel && window.onFilePickCancel()", null); return }
        Thread {
            val text = try { readUtf8(uri) } catch (e: Exception) { Log.w(TAG, "تعذر قراءة الملف", e); "" }  // "" => الصفحة تعرض "الملف غير صالح"
            sendPicked(text)
        }.start()
    }

    private fun sendPicked(text: String) {
        webView.post { webView.evaluateJavascript("window.onFilePicked && window.onFilePicked(${JSONObject.quote(text)})", null) }
    }

    private fun readUtf8(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("openInputStream null")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_PICK_BYTES) throw IOException("الملف أكبر من الحد")
                out.write(buf, 0, n)
            }
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(out.toByteArray())).toString()
        }
    }

    private fun patchedIndexResponse(): WebResourceResponse? = try {
        val html = readAsset("index.html")
        val marker = "<meta charset=\"utf-8\">"
        val i = html.indexOf(marker)
        if (i < 0) null else {
            val out = html.substring(0, i + marker.length) + "<script>" + earlyJs + "</script>" + html.substring(i + marker.length)
            WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
        }
    } catch (e: Exception) { null }

    private fun readAsset(path: String): String = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun onResume() {
        super.onResume(); webView.onResume()
        AutoBackup.scheduleNextSlot(this)   // v13: يتحوّل المنبّه إلى دقيق فور منح الإذن
    }
    override fun onPause() { webView.onPause(); super.onPause() }
    override fun onDestroy() {
        root.removeView(webView)
        webView.destroy()   // لا نمسح localStorage/الكاش أبداً (5.8)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Hesabak"
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$ASSET_HOST"
        private const val MAX_PICK_BYTES = 20 * 1024 * 1024
        const val START_URL = "$ORIGIN/assets/index.html"
    }
}
