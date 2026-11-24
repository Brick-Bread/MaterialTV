/*
 * MaterialTV web app helper. WebAppActivity injects this into every page of an installed web app
 * (only on the app's own origins), and also serves it at https://materialtv.invalid/sdk.js so a
 * page can load it in <head> and be styled before its first paint. Safe to run more than once.
 *
 * It adds:
 *  - the theme: --mtv-* CSS colour variables, the Google Sans Flex font, html.mtv + .mtv-dark/.mtv-light
 *  - spatial navigation: the remote's arrows move focus between focusable elements, unless the page
 *    handled the key itself (preventDefault) or opted out (<meta name="materialtv:spatial-navigation"
 *    content="off">, or window.materialtvSpatialNav = false)
 *  - Back: a cancelable "materialtv:back" event first, then history.back(), then the app closes
 *  - Menu: a "materialtv:menu" event
 *  - "materialtv:ready" (detail = the theme) once all of this is in place
 *
 * The native bridge itself is window.materialtv (see docs/apps.md).
 * The theme and CSS placeholders at the very end are filled in by WebAppActivity.
 */
(function (THEME, CSS) {
  'use strict';
  if (window.__mtvInstalled) return;
  window.__mtvInstalled = true;
  var doc = document;

  // ---- theme ---------------------------------------------------------------------------------
  function addTheme() {
    var root = doc.documentElement;
    if (!root) return;
    root.classList.add('mtv', THEME.isLight ? 'mtv-light' : 'mtv-dark');
    if (doc.getElementById('mtv-theme')) return;
    var s = doc.createElement('style');
    s.id = 'mtv-theme';
    s.textContent = CSS;
    var host = doc.head || root;
    host.insertBefore(s, host.firstChild); // first, so the page's own styles win
  }
  addTheme();
  if (!doc.head) doc.addEventListener('DOMContentLoaded', addTheme);

  // ---- spatial navigation ------------------------------------------------------------------
  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  var FOCUSABLE = 'a[href],area[href],button,input:not([type=hidden]),select,textarea,summary,' +
      '[tabindex],[contenteditable=""],[contenteditable="true"]';
  var NATIVE_CLICK = /^(A|AREA|BUTTON|INPUT|SELECT|TEXTAREA|SUMMARY)$/;
  var TEXT_INPUT = /^(text|search|url|email|tel|password|number)$/i;

  function enabled() {
    if (window.materialtvSpatialNav === false) return false;
    var m = doc.querySelector('meta[name="materialtv:spatial-navigation"]');
    return !(m && /^off$/i.test(m.getAttribute('content') || ''));
  }

  function usable(el) {
    if (el.disabled || el.getAttribute('tabindex') === '-1') return false;
    if (el.closest('[inert],[hidden],[aria-hidden="true"]')) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var st = getComputedStyle(el);
    return st.visibility !== 'hidden' && st.display !== 'none';
  }

  function candidates() {
    var all = doc.querySelectorAll(FOCUSABLE), out = [];
    for (var i = 0; i < all.length; i++) if (usable(all[i])) out.push(all[i]);
    return out;
  }

  function gap(a1, a2, b1, b2) { return b2 < a1 ? a1 - b2 : b1 > a2 ? b1 - a2 : 0; }

  /** The best element from rect a in direction dir: near along the way, and lined up across it. */
  function best(a, dir, list, cur) {
    var acx = (a.left + a.right) / 2, acy = (a.top + a.bottom) / 2;
    for (var pass = 0; pass < 2; pass++) {
      var pick = null, pickScore = Infinity;
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (el === cur || el.contains(cur) || cur.contains(el)) continue;
        var b = el.getBoundingClientRect();
        var bcx = (b.left + b.right) / 2, bcy = (b.top + b.bottom) / 2, along, across, off;
        // Pass 0 wants elements wholly beyond the current one; pass 1 settles for their centre.
        if (dir === 'right') { if (pass ? bcx <= acx + 1 : b.left < a.right - 4) continue; along = b.left - a.right; across = gap(a.top, a.bottom, b.top, b.bottom); off = Math.abs(bcy - acy); }
        else if (dir === 'left') { if (pass ? bcx >= acx - 1 : b.right > a.left + 4) continue; along = a.left - b.right; across = gap(a.top, a.bottom, b.top, b.bottom); off = Math.abs(bcy - acy); }
        else if (dir === 'down') { if (pass ? bcy <= acy + 1 : b.top < a.bottom - 4) continue; along = b.top - a.bottom; across = gap(a.left, a.right, b.left, b.right); off = Math.abs(bcx - acx); }
        else { if (pass ? bcy >= acy - 1 : b.bottom > a.top + 4) continue; along = a.top - b.bottom; across = gap(a.left, a.right, b.left, b.right); off = Math.abs(bcx - acx); }
        var score = Math.max(0, along) + across * 3 + off * 0.3;
        if (score < pickScore) { pickScore = score; pick = el; }
      }
      if (pick) return pick;
    }
    return null;
  }

  /** With nothing focused yet: the first element on screen, top-left first. */
  function first(list) {
    var pick = null, pickScore = Infinity, h = window.innerHeight;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      var onScreen = r.bottom > 0 && r.top < h;
      var score = (onScreen ? 0 : 1e6) + Math.max(0, r.top) * 2 + Math.max(0, r.left);
      if (score < pickScore) { pickScore = score; pick = list[i]; }
    }
    return pick;
  }

  function move(el) {
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(false); }
  }

  /** Arrow keys inside a text field move the caret until it reaches the end. */
  function caretWantsKey(el, dir) {
    var tag = el.tagName;
    if (tag === 'TEXTAREA') {
      if (dir === 'up' || dir === 'down') return true;
    } else if (!(tag === 'INPUT' && TEXT_INPUT.test(el.type || 'text'))) {
      return el.isContentEditable;
    }
    if (dir !== 'left' && dir !== 'right') return false;
    try {
      var s = el.selectionStart, e = el.selectionEnd, n = (el.value || '').length;
      if (s === null || s === undefined) return false;
      return dir === 'left' ? e > 0 : s < n;
    } catch (x) { return false; }
  }

  function onKey(e) {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || !enabled()) return;
    var cur = doc.activeElement;
    // Focus sometimes lands on a container (the WebView can focus one when it first gets focus).
    // That isn't a real stop, so start from the first thing inside it, or from the top.
    if (cur && (cur === doc.body || cur === doc.documentElement || !cur.matches(FOCUSABLE) || !usable(cur))) {
      var inside = cur === doc.body || cur === doc.documentElement ? [] : cur.querySelectorAll(FOCUSABLE);
      var pick = null;
      for (var j = 0; j < inside.length && !pick; j++) if (usable(inside[j])) pick = inside[j];
      if (pick && DIRS[e.key]) { e.preventDefault(); move(pick); return; }
      cur = null;
    }
    if (e.key === 'Enter') {
      // Make Select work on anything focusable (a div with tabindex, say), like a click.
      if (cur && !NATIVE_CLICK.test(cur.tagName) && !cur.isContentEditable) { e.preventDefault(); cur.click(); }
      return;
    }
    var dir = DIRS[e.key];
    if (!dir) return;
    if (cur && caretWantsKey(cur, dir)) return;
    var list = candidates();
    var rect = cur && cur.getBoundingClientRect();
    var next = rect && rect.width > 0 && rect.height > 0 ? best(cur.getBoundingClientRect(), dir, list, cur) : first(list);
    e.preventDefault();
    if (next) { move(next); return; }
    // Nothing further that way: scroll, so long pages can still be read.
    var step = { up: [0, -0.4], down: [0, 0.4], left: [-0.4, 0], right: [0.4, 0] }[dir];
    window.scrollBy({ left: step[0] * window.innerWidth, top: step[1] * window.innerHeight, behavior: 'smooth' });
  }
  window.addEventListener('keydown', onKey, false); // bubbling, on window: the page's own handlers go first

  // Remember the last real stop, and put focus back on it when the app (re)gains focus, so the remote
  // always has a highlighted element instead of an invisible container or nothing at all.
  var lastStop = null;
  doc.addEventListener('focusin', function (e) {
    var t = e.target;
    if (t && t.matches && t.matches(FOCUSABLE) && usable(t)) lastStop = t;
  }, true);
  function settle() {
    if (!enabled()) return;
    var a = doc.activeElement;
    if (a && a !== doc.body && a !== doc.documentElement && a.matches(FOCUSABLE) && usable(a)) return;
    var target = lastStop && doc.contains(lastStop) && usable(lastStop) ? lastStop : null;
    if (!target) { var auto = doc.querySelector('[autofocus]'); if (auto && usable(auto)) target = auto; }
    if (!target) target = first(candidates());
    if (target) try { target.focus({ preventScroll: true }); } catch (x) { target.focus(); }
  }
  window.addEventListener('focus', function () { setTimeout(settle, 0); });
  window.__mtvSettle = settle;

  // ---- remote buttons WebAppActivity forwards ------------------------------------------------
  window.__mtvBack = function () {
    if (doc.fullscreenElement && doc.exitFullscreen) { doc.exitFullscreen(); return true; }
    var ev = new CustomEvent('materialtv:back', { cancelable: true });
    window.dispatchEvent(ev);
    return ev.defaultPrevented;
  };
  window.__mtvMenu = function () {
    window.dispatchEvent(new CustomEvent('materialtv:menu'));
  };

  window.materialtvReady = true;
  window.dispatchEvent(new CustomEvent('materialtv:ready', { detail: THEME }));
})(__MTV_THEME__, __MTV_CSS__);
