/* Hesabak — early.js
 * يُحقن عند بداية المستند (addDocumentStartJavaScript) وكنسخة احتياطية في onPageFinished (idempotent).
 *
 * لماذا؟ index.html يستدعي checkAuto() → checkDailyExport() أثناء الإقلاع، أي قبل onPageFinished.
 * لو اعتمدنا على إعادة تعريف الدوال بعد التحميل فقط، لضاع تصدير أول فتح في اليوم (يُسجَّل "تم" دون ملف).
 * لذلك الخطافات هنا على مستوى المنصة (لا تتأثر بترتيب تعريف دوال الصفحة):
 *   1) HTMLAnchorElement.prototype.click : أي <a download href="blob:..."> يُحوَّل إلى Android.saveFile
 *   2) Storage.prototype.setItem/getItem : كتابة 'ledger_excel_last_day' بعد تصدير .xlsx، و'ledger_last_full_backup' بعد تصدير .json (النسخة الكاملة)، تُحجز حتى تأكيد الحفظ
 *   3) window.Notification : يوجّه إلى Android.notify (حتى تعمل mobileNotify الأصلية قبل حقن shim.js)
 *   4) window.onFileSaved / onFileError
 *
 * ⚠️ اعتماد على index.html: downloadBlob يجب أن يبقى عبر <a download> برابط blob:، والمفتاحان 'ledger_excel_last_day' و'ledger_last_full_backup'،
 *    ودوال toast / renderBar / renderBanner (اختيارية، تُستدعى إن وُجدت).
 */
(function () {
  'use strict';
  var B = window.Android;
  if (window.__hesabakCore || !B || typeof B.saveFile !== 'function') return;

  // v7: نسخة تلقائية تمت والتطبيق مغلق (AutoBackup) => حدّث «آخر نسخة» قبل أن تقرأها الصفحة، فلا يظهر شريط تنبيه خاطئ.
  try {
    if (typeof B.lastAutoBackupDay === 'function') {
      var bgDay = String(B.lastAutoBackupDay() || '');
      if (/^\d{4}-\d{2}-\d{2}$/.test(bgDay)) {
        var L0 = window.localStorage;
        ['ledger_last_full_backup', 'ledger_excel_last_day'].forEach(function (k) {
          var cur = null; try { cur = JSON.parse(L0.getItem(k)); } catch (e) {}
          if (!cur || String(cur) < bgDay) L0.setItem(k, JSON.stringify(bgDay));   // قبل تركيب الخطافات أدناه
        });
      }
    }
  } catch (e) {}

  // v8: بصمة آخر Excel حفظته الخدمة الخلفية => نسخها لـ localStorage قبل أن تقرأها الصفحة
  try {
    if (typeof B.lastExcelSig === 'function') {
      var nsig = String(B.lastExcelSig() || '');
      if (nsig) window.localStorage.setItem('ledger_excel_last_sig', JSON.stringify(nsig));
    }
  } catch (e) {}

  // مفتاح محجوز -> نمط اسم الملف الذي يسبق كتابته مباشرة (.xls لتاريخ Excel، .json لتاريخ النسخة الكاملة)
  var HOLD_KEYS = { 'ledger_excel_last_day': /\.xlsx?$/i, 'ledger_excel_last_sig': /\.xlsx?$/i, 'ledger_last_full_backup': /\.json$/i };  // v8: + بصمة Excel
  var TIMEOUT_MS = 15000;              // v7: أطول قليلاً لتتسع إعادة المحاولة التلقائية
  var MAX_RETRY = 2;                   // v7: محاولات إعادة تلقائية قبل إظهار «فشل» للمستخدم
  var st = window.setTimeout, ct = window.clearTimeout;
  var pending = Object.create(null);   // اسم الملف -> {items:{مفتاح:قيمة}, timer}
  var armedName = null;                // ملف .xls بدأ تنزيله للتو، بانتظار setItem(excelLast)
  var held = Object.create(null);      // للقراءة المتفائلة: المفتاح -> القيمة المحجوزة حالياً

  var lsSet = Storage.prototype.setItem;
  var lsGet = Storage.prototype.getItem;
  function ls() { try { return window.localStorage; } catch (e) { return null; } }
  function isXls(n) { return /\.xlsx?$/i.test(String(n || '')); }   // v6: التصدير صار .xlsx (ويقبل .xls القديم)
  function isHoldable(n) {
    n = String(n || '');
    for (var k in HOLD_KEYS) if (HOLD_KEYS[k].test(n)) return true;
    return false;
  }
  function refreshUi() {
    try { if (typeof renderBar === 'function') renderBar(); } catch (e) {}
    try { if (typeof renderBanner === 'function') renderBanner(); } catch (e) {}
  }
  function recomputeHeld() {
    held = Object.create(null);
    for (var n in pending) for (var k in pending[n].items) held[k] = pending[n].items[k];
  }

  function commit(name) {
    var p = pending[name]; if (!p) return;
    ct(p.timer); delete pending[name]; recomputeHeld();
    var l = ls(); if (l) for (var k in p.items) lsSet.call(l, k, p.items[k]);
    // v8: بصمة الجدول المحفوظ تُبلَّغ للجانب الأصلي ليعرفها الفحص الخلفي (والتطبيق مفتوح أو مغلق)
    try { if (p.items['ledger_excel_last_sig'] !== undefined && typeof B.setExcelSig === 'function') B.setExcelSig(String(p.items['ledger_excel_last_sig'])); } catch (e) {}
    refreshUi();
  }
  function drop(name) {
    var p = pending[name]; if (!p) return;
    ct(p.timer); delete pending[name]; recomputeHeld();
    refreshUi();                                          // نتراجع عن العرض المتفائل
  }
  function hold(name, key, value) {
    var old = pending[name];
    if (old) { old.items[key] = value; recomputeHeld(); return; }   // v8: مفاتيح متعددة لنفس الملف
    // لا onFileSaved ولا onFileError خلال المهلة => نعتبرها ناجحة (احتياط لو لم يُنفَّذ الجسر)
    pending[name] = { items: (function () { var o = {}; o[key] = value; return o; })(), timer: st(function () { commit(name); }, TIMEOUT_MS) };
    recomputeHeld();
  }
  function arm(name) {
    if (!isHoldable(name)) return;
    armedName = name;                                     // exportExcel / exportFullBackup يكتبان تاريخهما مباشرة بعد التنزيل
    st(function () { if (armedName === name) armedName = null; }, 0);
  }

  Storage.prototype.setItem = function (k, v) {
    if (HOLD_KEYS[k] && armedName && HOLD_KEYS[k].test(armedName) && this === ls()) {
      hold(armedName, k, String(v));                      // v8: لا نصفّر armedName هنا؛ يصفّره مؤقت الصفر بعد انتهاء الكتابات المتزامنة
      return;
    }
    return lsSet.apply(this, arguments);
  };
  Storage.prototype.getItem = function (k) {
    if (HOLD_KEYS[k] && held[k] !== undefined && this === ls()) return held[k];  // نفس سلوك الأصل: يرى الصفحة قيمتها فوراً
    return lsGet.apply(this, arguments);
  };

  var blobs = Object.create(null), tries = Object.create(null);   // v7: لإعادة المحاولة تلقائياً
  function save(blob, name) {
    blobs[name] = blob;
    var mime = String((blob && blob.type) || 'application/octet-stream').split(';')[0];
    var r = new FileReader();
    r.onload = function () {
      try {
        var s = String(r.result);
        B.saveFile(s.substring(s.indexOf(',') + 1), name, mime);
      } catch (e) { window.onFileError(name); }
    };
    r.onerror = function () { window.onFileError(name); };
    r.readAsDataURL(blob);
  }

  var aClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      if (this.hasAttribute('download') && /^blob:/i.test(this.href)) {
        var name = String(this.getAttribute('download') || 'file');
        arm(name);
        fetch(this.href).then(function (r) { return r.blob(); })
          .then(function (b) { save(b, name); })
          .catch(function () { window.onFileError(name); });
        return;
      }
    } catch (e) {}
    return aClick.apply(this, arguments);
  };

  window.onFileSaved = function (name) {
    delete blobs[name]; delete tries[name];
    commit(name);
    if (!isXls(name)) {   // .json والصور؛ .xlsx له رسالته الخاصة
      var where = /\.json$/i.test(String(name)) ? 'Downloads/Hesabak-data/json' : 'مجلد التنزيلات';
      try { toast('✅ تم الحفظ في ' + where, 'ok'); } catch (e) {}
    }
  };
  window.onFileError = function (name) {
    // v7: الفشل المتقطع يُعاد تلقائياً (بعد 0.7 ثانية ثم 1.4) قبل أن نُظهر خطأ للمستخدم
    var b = blobs[name], n = tries[name] || 0;
    if (b && n < MAX_RETRY) { tries[name] = n + 1; st(function () { save(b, name); }, 700 * (n + 1)); return; }
    delete blobs[name]; delete tries[name];
    drop(name);                                           // لا نسجّل تاريخ النسخة
    try { toast('❌ فشل حفظ الملف، حاول مرة أخرى', 'err'); } catch (e) {}
  };

  /* Notification => إشعار أندرويد حقيقي */
  if (typeof B.notify === 'function' && typeof B.hasNotifyPermission === 'function') {
    var N = function (title, opts) { B.notify(String(title || ''), String((opts && opts.body) || '')); };
    Object.defineProperty(N, 'permission', { get: function () { return B.hasNotifyPermission() ? 'granted' : 'default'; } });
    N.requestPermission = function () {
      var granted = false;
      try { granted = !!B.requestNotifyPermission(); } catch (e) {}
      return Promise.resolve(granted ? 'granted' : 'default');
    };
    try { window.Notification = N; } catch (e) {}
  }

  window.__hesabakCore = { arm: arm, save: save };
})();
