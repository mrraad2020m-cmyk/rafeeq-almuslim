/* Interface choice is independent of the existing light/night setting. */
(function () {
  'use strict';
  if (window.__raadInterfaceSettingsLoaded) return;
  window.__raadInterfaceSettingsLoaded = true;

  var KEY = 'raadInterfaceDesign';
  var ORIGINAL = 'original';
  var MODERN = 'modern';
  var current = MODERN;
  try {
    if (window.localStorage.getItem(KEY) === ORIGINAL) current = ORIGINAL;
  } catch (e) {
    // Storage can be unavailable; keep the default usable for this session.
  }
  // This runs synchronously in the head, before the page can be painted.
  document.documentElement.classList.toggle('modern-ui', current === MODERN);

  // These narrowly scoped rules also work when this script is loaded into an
  // older remotely patched index.html which lacks the new settings markup.
  var style = document.createElement('style');
  style.id = 'interface-settings-style';
  style.textContent = '\n' +
    '#page-settings #app-settings-entry,#page-app-settings #open-interface-choice{width:100%;min-height:64px;text-align:right;font:inherit}' +
    '#page-app-settings .back-btn,#page-interface-choice .back-btn{min-height:44px}' +
    '#page-interface-choice .interface-options{border:0;margin:0;padding:0;min-width:0;display:flex;flex-direction:column;gap:10px}' +
    '#page-interface-choice .interface-options-legend{padding:0 4px;margin-bottom:10px;color:#f5d78d;font:bold 14px Tahoma,Arial,sans-serif}' +
    '#page-interface-choice .interface-option{width:100%;min-height:76px;text-align:right;cursor:pointer}' +
    '#page-interface-choice .interface-option.is-selected{border:2px solid #f5d78d;background:linear-gradient(135deg,#374c59,#243c55)}' +
    '#page-interface-choice .interface-option input{width:22px;height:22px;min-width:22px;accent-color:#d9b45e;cursor:pointer}' +
    '#page-interface-choice .interface-option input:focus{outline:3px solid #f5d78d;outline-offset:3px}' +
    '#page-interface-choice .interface-choice-status{color:#ffe29b;font:bold 12px Tahoma,Arial,sans-serif;margin-top:4px}' +
    '#page-interface-choice .interface-choice-status[hidden],#page-interface-choice #interface-storage-status[hidden]{display:none!important}' +
    'html.light #page-interface-choice .interface-options-legend{color:#765817}' +
    'html.light #page-interface-choice .interface-option.is-selected{border-color:#9a7220;background:linear-gradient(135deg,#fff5d7,#f0dfb5)}' +
    'html.light #page-interface-choice .interface-choice-status{color:#654a0d}' +
    'html.light #page-interface-choice .interface-option input:focus{outline-color:#795716}';
  (document.head || document.documentElement).appendChild(style);

  var entryMarkup = '<button type="button" id="app-settings-entry" class="toggle-row action interface-settings-entry" aria-controls="page-app-settings">' +
    '<span class="t-ico" aria-hidden="true">⚙️</span>' +
    '<span class="t-txt"><b class="t-title">إعدادات التطبيق</b><small class="t-sub">اختيار تصميم واجهة التطبيق</small></span>' +
    '<span class="t-chev" aria-hidden="true">‹</span></button>';
  var settingsMarkup = '<section id="page-app-settings" class="app-page" hidden aria-labelledby="app-settings-title">' +
    '<div class="page-heading"><button type="button" id="app-settings-back" class="back-btn">↩ الإعدادات</button><h2 id="app-settings-title" class="section-title">⚙️ إعدادات التطبيق</h2></div>' +
    '<p class="description">اختر تصميم الواجهة المناسب لك. يبقى إعداد الوضع الليلي مستقلًا.</p>' +
    '<div class="toggle-list"><button type="button" id="open-interface-choice" class="toggle-row action interface-settings-entry" aria-controls="page-interface-choice">' +
    '<span class="t-ico" aria-hidden="true">🎨</span><span class="t-txt"><b class="t-title">اختر الواجهة</b><small class="t-sub" id="interface-current-label">الواجهة الأصلية</small></span>' +
    '<span class="t-chev" aria-hidden="true">‹</span></button></div></section>';
  var choiceMarkup = '<section id="page-interface-choice" class="app-page" hidden aria-labelledby="interface-choice-title">' +
    '<div class="page-heading"><button type="button" id="interface-choice-back" class="back-btn">↩ إعدادات التطبيق</button><h2 id="interface-choice-title" class="section-title">🎨 اختر الواجهة</h2></div>' +
    '<p class="description">يتغيّر التصميم فورًا، ويمكنك العودة إلى الواجهة الأصلية في أي وقت.</p>' +
    '<fieldset class="interface-options"><legend class="interface-options-legend">تصميم الواجهة</legend>' +
    '<label class="toggle-row interface-option"><input type="radio" name="interface-design" value="original">' +
    '<span class="t-txt"><b class="t-title">الواجهة الأصلية</b><small class="t-sub">التصميم الأصلي للتطبيق</small><span class="interface-choice-status" aria-hidden="true" hidden>المختارة الآن ✓</span></span></label>' +
    '<label class="toggle-row interface-option is-selected"><input type="radio" name="interface-design" value="modern" checked>' +
    '<span class="t-txt"><b class="t-title">واجهة بتصميم حديث</b><small class="t-sub">التصميم الافتراضي للتطبيق</small><span class="interface-choice-status" aria-hidden="true">المختارة الآن ✓</span></span></label>' +
    '</fieldset><p id="interface-storage-status" class="toggle-note" role="status" aria-live="polite" hidden></p></section>';

  function ensureMarkup() {
    var settings = document.getElementById('page-settings');
    if (!settings) return false;
    if (!document.getElementById('app-settings-entry')) {
      (settings.querySelector('.toggle-list') || settings).insertAdjacentHTML('afterbegin', entryMarkup);
    }
    if (!document.getElementById('page-app-settings')) {
      settings.insertAdjacentHTML('afterend', settingsMarkup);
    }
    if (!document.getElementById('page-interface-choice')) {
      document.getElementById('page-app-settings').insertAdjacentHTML('afterend', choiceMarkup);
    }
    return true;
  }

  function syncControls() {
    var radios = document.querySelectorAll('#page-interface-choice input[name="interface-design"]');
    Array.prototype.forEach.call(radios, function (radio) {
      var selected = radio.value === current;
      radio.checked = selected;
      var option = radio.closest('label');
      if (option) {
        option.classList.toggle('is-selected', selected);
        var status = option.querySelector('.interface-choice-status');
        if (status) status.hidden = !selected;
      }
    });
    var label = document.getElementById('interface-current-label');
    if (label) label.textContent = current === MODERN ? 'واجهة بتصميم حديث' : 'الواجهة الأصلية';
  }

  // Keep the original layout exactly as it was; in the modern layout, place
  // the existing explanatory notes with the subjects they describe instead
  // of repeating them after every screen. Nothing is removed or rewritten.
  var movedNotes = [];
  function arrangeNotes() {
    if (current === MODERN && movedNotes.length === 0) {
      var main = document.querySelector('main.container');
      if (!main) return;
      var candidates = main.querySelectorAll(':scope > .note, :scope > .prayer');
      Array.prototype.forEach.call(candidates, function (node) {
        var text = node.textContent || '';
        var destination = text.indexOf('أيام لا يجوز صيامها') !== -1 ? 'page-fasting' :
          (text.indexOf('التواريخ المعروضة تقديرية') !== -1 || text.indexOf('مواسم الخير') !== -1 ? 'page-events' : '');
        var page = destination && document.getElementById(destination);
        if (!page) return;
        var anchor = document.createComment('original note position');
        node.parentNode.insertBefore(anchor, node);
        movedNotes.push({node: node, anchor: anchor, page: page});
      });
    }
    movedNotes.forEach(function (item) {
      if (current === MODERN) item.page.appendChild(item.node);
      else if (item.anchor.parentNode) item.anchor.parentNode.insertBefore(item.node, item.anchor.nextSibling);
    });
  }

  function selectDesign(value) {
    if (value !== ORIGINAL && value !== MODERN) return;
    current = value;
    document.documentElement.classList.toggle('modern-ui', current === MODERN);
    arrangeNotes();
    var status = document.getElementById('interface-storage-status');
    try {
      window.localStorage.setItem(KEY, current);
      if (status) { status.hidden = true; status.textContent = ''; }
    } catch (e) {
      if (status) {
        status.textContent = 'تعذّر حفظ الاختيار على هذا الجهاز؛ التغيير لهذه الجلسة فقط.';
        status.hidden = false;
      }
    }
    syncControls();
  }

  function openPage(id) {
    if (typeof window.showPage === 'function') window.showPage(id);
  }

  function goBack(parentId) {
    // Normal navigation and restored views both have their parent in history.
    if (window.history.state && window.history.state.appPage) window.history.back();
    else openPage(parentId);
  }

  function prepareRestoredHistory() {
    // The app restores its last screen on DOMContentLoaded. Recreate the two
    // parent entries first so Android Back follows the same path after restart.
    try {
      if (typeof window.restoreLastView !== 'function' || !window.history.state ||
          window.history.state.appPage !== 'page-home' ||
          window.localStorage.getItem('raadGoHome') === '1') return;
      var raw = window.localStorage.getItem('raadLastView');
      var view = raw && JSON.parse(raw);
      if (!view || !view.t || Date.now() - view.t > 3 * 60 * 60 * 1000 || view.mushaf ||
          (view.page !== 'page-app-settings' && view.page !== 'page-interface-choice' && view.page !== 'page-notification-settings')) return;
      window.history.pushState({appPage: 'page-settings'}, '', '#page-settings');
      if (view.page === 'page-interface-choice') {
        window.history.pushState({appPage: 'page-app-settings'}, '', '#page-app-settings');
      } else if (view.page === 'page-notification-settings') {
        window.history.pushState({appPage: 'page-notification-settings'}, '', '#page-notification-settings');
      }
    } catch (e) {
      // The regular app restoration remains available if history/storage fails.
    }
  }

  function init() {
    if (!ensureMarkup()) return;
    arrangeNotes();
    syncControls();
    document.getElementById('app-settings-entry').addEventListener('click', function () { openPage('page-app-settings'); });
    var notificationEntry = document.getElementById('notification-settings-entry');
    if (notificationEntry) notificationEntry.addEventListener('click', function () { openPage('page-notification-settings'); });
    document.getElementById('open-interface-choice').addEventListener('click', function () { openPage('page-interface-choice'); });
    document.getElementById('app-settings-back').addEventListener('click', function () { goBack('page-settings'); });
    var notificationBack = document.getElementById('notification-settings-back');
    if (notificationBack) notificationBack.addEventListener('click', function () { goBack('page-settings'); });
    document.getElementById('interface-choice-back').addEventListener('click', function () { goBack('page-app-settings'); });
    var radios = document.querySelectorAll('#page-interface-choice input[name="interface-design"]');
    Array.prototype.forEach.call(radios, function (radio) {
      radio.addEventListener('change', function () { if (radio.checked) selectDesign(radio.value); });
    });
    prepareRestoredHistory();
    try { if (window.AndroidReminders && window.AndroidReminders.setInterfaceDesign) window.AndroidReminders.setInterfaceDesign(current === MODERN); } catch (e) {}
  }

  window.addEventListener('storage', function (event) {
    if (event.key !== KEY && event.key !== null) return;
    try { current = window.localStorage.getItem(KEY) === MODERN ? MODERN : ORIGINAL; }
    catch (e) { return; }
    document.documentElement.classList.toggle('modern-ui', current === MODERN);
    arrangeNotes();
    syncControls();
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
}());
