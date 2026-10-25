/* Hesabak — shim.js (يُحقن في onPageFinished بعد early.js)
 *
 * يعيد تعريف الدوال المسماة في index.html لتمر مباشرة عبر الجسر (طبقة ثانية فوق خطافات early.js).
 *
 * ⚠️ اعتماد على أسماء index.html: downloadBlob(blob,name) · mobileNotify(title,body) · askNotifyPermission()
 *    إن تغيّرت فلن يتعطل شيء بفضل خطافات early.js، لكن عدّل هنا ليبقى الحقن متسقاً.
 */
(function () {
  'use strict';
  var B = window.Android, core = window.__hesabakCore;
  if (window.__hesabakShim || !B || !core) return;

  window.downloadBlob = function (blob, name) {
    name = String(name || 'file');
    core.arm(name);
    core.save(blob, name);
  };
  if (typeof B.notify === 'function') {
    window.mobileNotify = function (title, body) {
      try { B.notify(String(title || ''), String(body || '')); return true; } catch (e) { return false; }
    };
  }
  if (typeof B.requestNotifyPermission === 'function') {
    window.askNotifyPermission = function () {
      try { return Promise.resolve(!!B.requestNotifyPermission()); } catch (e) { return Promise.resolve(false); }
    };
  }
  window.__hesabakShim = true;
})();
