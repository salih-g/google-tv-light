// Injected into every page: D-pad link navigation, overlay removal, video hooks.
// Android talks to it through window.tvnav; it talks back through the tvbridge interface.
(function () {
  if (window.tvnav) return;
  var focused = null, ring = null, lastVideoReport = "";
  var memory = new WeakMap();   // group element -> last focused element inside it (focus memory, as TV apps do)

  // ---------- what can take focus ----------

  var FOCUSABLE = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="link"], [role="tab"], [role="menuitem"], [role="option"], [role="checkbox"], [role="switch"], [contenteditable="true"], [tabindex]:not([tabindex="-1"]), [onclick], video';
  // Structural containers that form the focus tree (rows, menus, lists, forms, dialogs).
  var GROUP = 'nav, header, footer, aside, form, dialog, ul, ol, table, [role="list"], [role="listbox"], [role="menu"], [role="menubar"], [role="tablist"], [role="toolbar"], [role="grid"], [role="row"], [role="navigation"], [role="dialog"], [role="search"], [role="radiogroup"]';

  function styleHidden(el) {
    var s = getComputedStyle(el);
    return s.visibility === "hidden" || s.display === "none" || parseFloat(s.opacity) <= 0.05;
  }

  function onScreen(r) { return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; }

  // Covered by a menu, overlay or dialog at its centre? Only checkable for on-screen elements.
  function onTop(el, r) {
    var x = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 2);
    var y = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 2);
    var t = document.elementFromPoint(x, y);
    return !t || t === el || el.contains(t) || t.contains(el);
  }

  // Off-screen elements are reachable only if they are really part of the page: not clipped away by an
  // overflow:hidden ancestor (collapsed menus, carousels without scroll) and not inside a fixed layer (off-canvas drawers).
  function reachableOffscreen(el, r) {
    if (r.top > innerHeight * 2.5 || r.bottom < -innerHeight * 1.5) return false;   // keep the search local
    for (var a = el.parentElement; a && a !== document.body; a = a.parentElement) {
      var s = getComputedStyle(a);
      if (s.position === "fixed") return false;
      if (s.overflowX !== "visible" || s.overflowY !== "visible") {
        var ar = a.getBoundingClientRect();
        var clipsX = (s.overflowX === "hidden" || s.overflowX === "clip") && (r.right <= ar.left || r.left >= ar.right);
        var clipsY = (s.overflowY === "hidden" || s.overflowY === "clip") && (r.bottom <= ar.top || r.top >= ar.bottom);
        if (clipsX || clipsY) return false;
      }
    }
    return true;
  }

  function candidates() {
    var found = [];
    document.querySelectorAll(FOCUSABLE).forEach(function (el) {
      if (el.disabled || el.getAttribute("aria-disabled") === "true" || el.closest("[aria-hidden='true'],[inert]")) return;
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || styleHidden(el)) return;
      if (onScreen(r) ? !onTop(el, r) : !reachableOffscreen(el, r)) return;
      found.push(el);
    });
    // innermost wins: a clickable wrapper around a link is one target, not two
    return found.filter(function (el) {
      for (var i = 0; i < found.length; i++) if (found[i] !== el && el.contains(found[i])) return false;
      return true;
    });
  }

  function groupOf(el) {
    var g = el.parentElement ? el.parentElement.closest(GROUP) : null;
    return g || document.documentElement;
  }

  // ---------- choosing the next element (Netflix-style: frustum + Minkowski distance, searched up the focus tree) ----------

  function box(el) { var r = el.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height }; }

  // Shortest vector between the two boxes (zero on an axis where they overlap), signed toward the candidate.
  function gapVector(f, c) {
    var dx = c.l >= f.r ? c.l - f.r : f.l >= c.r ? -(f.l - c.r) : 0;
    var dy = c.t >= f.b ? c.t - f.b : f.t >= c.b ? -(f.t - c.b) : 0;
    return { x: dx, y: dy };
  }

  // Score of a candidate in a direction; Infinity when it is not in that direction.
  // cone=true keeps only candidates inside a 45-degree frustum (or overlapping on the cross axis).
  function score(f, c, dir, cone) {
    var v = gapVector(f, c);
    var along = dir === "right" ? v.x : dir === "left" ? -v.x : dir === "down" ? v.y : -v.y;
    var cross = dir === "left" || dir === "right" ? Math.abs(v.y) : Math.abs(v.x);
    // must lie beyond the focused element in that direction
    var beyond = dir === "right" ? c.l >= f.r - 4 || (c.l > f.l && c.r > f.r && v.y === 0)
               : dir === "left" ? c.r <= f.l + 4 || (c.r < f.r && c.l < f.l && v.y === 0)
               : dir === "down" ? c.t >= f.b - 4 || (c.t > f.t && c.b > f.b && v.x === 0)
               : c.b <= f.t + 4 || (c.b < f.b && c.t < f.t && v.x === 0);
    if (!beyond || along < 0) return Infinity;
    if (cone && cross > along) return Infinity;
    var overlap = dir === "left" || dir === "right" ? Math.max(0, Math.min(f.b, c.b) - Math.max(f.t, c.t))
                                                    : Math.max(0, Math.min(f.r, c.r) - Math.max(f.l, c.l));
    // distance + cross-axis penalty, aligned neighbours first; among aligned ones the most centred wins
    var centre = dir === "left" || dir === "right" ? Math.abs((c.t + c.b) - (f.t + f.b)) / 2 : Math.abs((c.l + c.r) - (f.l + f.r)) / 2;
    return Math.sqrt(along * along + cross * cross) + cross * 2 - Math.sqrt(overlap) + centre * 0.05;
  }

  // Only the frustum: an element outside the 45-degree cone is not "in that direction" (no diagonal jumps).
  function nearest(f, pool, dir) {
    var best = null, bestScore = Infinity;
    for (var i = 0; i < pool.length; i++) {
      var s = score(f, box(pool[i]), dir, true);
      if (s < bestScore) { bestScore = s; best = pool[i]; }
    }
    return best;
  }

  function firstTarget(list) {
    var a = document.activeElement;
    if (a && a !== document.body && list.indexOf(a) >= 0 && onScreen(a.getBoundingClientRect())) return a;   // the page's autofocus (e.g. a search box)
    // a page built around a search field starts there (search engines, video sites)
    for (var i = 0; i < list.length; i++) {
      var el = list[i], r = el.getBoundingClientRect();
      var searchy = el.matches('input[type="search"], [role="searchbox"], [role="combobox"], input[name="q"], textarea[name="q"], input[name="search_query"]') || !!el.closest('[role="search"]');
      if (searchy && el.matches('input, textarea, [contenteditable="true"]') && r.top >= 0 && r.bottom <= innerHeight * 0.6) return el;
    }
    var best = null, bestScore = Infinity;
    list.forEach(function (el) { var r = box(el); if (r.t < 0 || r.t > innerHeight) return; var s = r.t * 3 + r.l; if (s < bestScore) { bestScore = s; best = el; } });
    return best;
  }

  function pick(dir) {
    var list = candidates();
    if (!focused || !document.contains(focused) || list.indexOf(focused) < 0) {
      if (focused && document.contains(focused) && !styleHidden(focused)) {
        // still on the page but no longer a candidate (scrolled away, covered): continue from where it is
      } else { focused = null; return firstTarget(list); }
    }
    var f = box(focused), from = groupOf(focused), scope = from, best = null;
    // Inside running text, Left/Right follow reading order: same line first, then the next/previous link of the paragraph.
    if (dir === "left" || dir === "right") {
      var block = focused.closest("p, li, dd, dt, td, th, blockquote, figcaption");
      if (block && getComputedStyle(focused).display.indexOf("inline") === 0) {
        var inBlock = list.filter(function (el) { return el !== focused && block.contains(el); });
        var sameLine = inBlock.filter(function (el) { var c = box(el); return Math.min(f.b, c.b) - Math.max(f.t, c.t) > Math.min(f.h, c.h) / 2; });
        var onLine = nearest(f, sameLine, dir);
        if (onLine) return onLine;
        var idx = list.indexOf(focused), step = dir === "right" ? 1 : -1;
        for (var i = idx + step; i >= 0 && i < list.length; i += step) if (block.contains(list[i])) return list[i];
      }
    }
    // siblings first, then widen to the parent group, up to the whole page
    while (true) {
      var pool = list.filter(function (el) { return el !== focused && scope.contains(el) && !el.contains(focused) && !focused.contains(el); });
      best = nearest(f, pool, dir);
      if (best || scope === document.documentElement) break;
      scope = groupOf(scope);
    }
    if (!best) return null;
    // entering another group: return to where the user was in it, if that is still in this direction
    var to = groupOf(best);
    if (to !== from && to !== document.documentElement) {
      var remembered = memory.get(to);
      if (remembered && remembered !== best && list.indexOf(remembered) >= 0 && score(f, box(remembered), dir, false) < Infinity) best = remembered;
    }
    return best;
  }

  function canScroll(dir) {
    var se = document.scrollingElement || document.documentElement;
    if (dir === "up") return scrollY > 0;
    if (dir === "down") return scrollY + innerHeight < se.scrollHeight - 1;
    if (dir === "left") return scrollX > 0;
    return scrollX + innerWidth < se.scrollWidth - 1;
  }

  // ---------- showing focus ----------

  function ensureRing() {
    if (ring && document.documentElement.contains(ring)) return ring;
    ring = document.createElement("div");
    ring.id = "__tvnav_ring";
    ring.style.cssText = "position:fixed;pointer-events:none;z-index:2147483646;border:3px solid #2f7dff;border-radius:8px;box-shadow:0 0 0 2px #fff,0 8px 24px rgba(0,0,0,.45);transition:all .12s ease-out;display:none";
    document.documentElement.appendChild(ring);
    return ring;
  }

  function placeRing(el) {
    // a link wrapped over two lines has one big bounding box; ring its first line fragment instead
    var rects = el.getClientRects(), r = el.getBoundingClientRect(), g = ensureRing();
    for (var i = 0; rects.length > 1 && i < rects.length; i++) if (rects[i].width > 4 && rects[i].height > 4) { r = rects[i]; break; }
    g.style.display = "block";
    g.style.left = (r.left - 4) + "px"; g.style.top = (r.top - 4) + "px";
    g.style.width = (r.width + 2) + "px"; g.style.height = (r.height + 2) + "px";
  }

  function highlight(el) {
    if (focused && focused !== el) memory.set(groupOf(focused), focused);
    focused = el;
    memory.set(groupOf(el), el);
    // keep focus in a comfort zone instead of letting it ride the screen edge (TV apps keep the focused row in view)
    var r = el.getBoundingClientRect();
    if (r.top < innerHeight * 0.12 || r.bottom > innerHeight * 0.88 || r.left < 0 || r.right > innerWidth) {
      try { el.scrollIntoView({ block: r.height > innerHeight * 0.7 ? "start" : "center", inline: "nearest", behavior: "smooth" }); } catch (e) {}
    }
    placeRing(el);
    var label = (el.getAttribute("aria-label") || el.title || el.innerText || el.alt || el.href || "").trim().slice(0, 80);
    tvbridge.focused(label, el.tagName.toLowerCase(), el.closest("a[href]") ? el.closest("a[href]").href : "");
  }

  window.tvnav = {
    // "moved": focus went to an element; "scrolled": nothing there yet, the page scrolled a step;
    // "edge": nothing in that direction and the page cannot scroll that way (the app may take over, e.g. the top bar).
    // Initial focus after a load; idempotent, so repeated load events never push focus around.
    start: function () {
      if (focused && document.contains(focused) && !styleHidden(focused)) { placeRing(focused); return "kept"; }
      var first = firstTarget(candidates());
      if (first) { highlight(first); return "started"; }
      return "none";
    },
    move: function (dir) {
      var next = pick(dir);
      if (next) { highlight(next); return "moved"; }
      if (!canScroll(dir)) return "edge";
      var step = dir === "up" || dir === "down" ? innerHeight * 0.6 : innerWidth * 0.5;
      scrollBy({ top: dir === "down" ? step : dir === "up" ? -step : 0, left: dir === "right" ? step : dir === "left" ? -step : 0, behavior: "smooth" });
      setTimeout(function () { var n = pick(dir); if (n) highlight(n); else if (focused) highlight(focused); }, 260);
      return "scrolled";
    },
    click: function () {
      if (!focused || !document.contains(focused)) return false;
      var el = focused;
      if (el.tagName === "VIDEO") { el.paused ? el.play() : el.pause(); return true; }
      if (el.tagName === "INPUT" || el.tagName === "TEXTAREA") { el.focus(); tvbridge.editText(el.value || "", el.placeholder || ""); return true; }
      try { el.focus(); } catch (e) {}
      el.dispatchEvent(new MouseEvent("mousedown", { bubbles: true })); el.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
      el.click();
      return true;
    },
    setText: function (text) { var el = document.activeElement; if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA")) { el.value = text; el.dispatchEvent(new Event("input", { bubbles: true })); el.form && el.form.requestSubmit ? el.form.requestSubmit() : el.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", keyCode: 13, bubbles: true })); } },
    clear: function () { if (ring) ring.style.display = "none"; focused = null; },
    refresh: function () { if (focused && document.contains(focused) && !styleHidden(focused)) placeRing(focused); else this.clear(); },

    // Remove fixed overlays that cover most of the viewport and don't contain the video (anti "click to close" layers).
    removeOverlays: function () {
      var removed = 0, video = document.querySelector("video");
      document.querySelectorAll("body *").forEach(function (el) {
        if (el.id === "__tvnav_ring" || (video && (el === video || el.contains(video)))) return;
        var s = getComputedStyle(el);
        if (s.position !== "fixed") return;
        var z = parseInt(s.zIndex, 10); if (!(z >= 1000)) return;
        if (s.visibility === "hidden" || s.display === "none" || parseFloat(s.opacity) < 0.3) return;
        var r = el.getBoundingClientRect();
        if (r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth) return; // parked off-screen
        var cover = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0)) * Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
        if (cover < innerWidth * innerHeight * 0.6) return;
        el.remove(); removed++;
      });
      if (removed) { document.body.style.overflow = ""; document.documentElement.style.overflow = ""; }
      return removed;
    },

    // ---- video ----
    video: function (cmd, arg) {
      var v = document.querySelector("video");
      if (!v) return false;
      if (cmd === "toggle") v.paused ? v.play() : v.pause();
      else if (cmd === "seek") v.currentTime = Math.max(0, Math.min(v.duration || 1e9, v.currentTime + arg));
      else if (cmd === "rate") v.playbackRate = arg;
      else if (cmd === "fullscreen") { try { (v.requestFullscreen || v.webkitRequestFullscreen).call(v); } catch (e) {} }
      return true;
    },
    pollVideo: function () {
      var v = playingVideo() || document.querySelector("video");
      var rep = v ? [v.paused ? 0 : 1, Math.round(v.currentTime), Math.round(v.duration || 0), v.playbackRate, document.title].join("|") : "";
      if (rep !== lastVideoReport) { lastVideoReport = rep; tvbridge.video(rep); }
    },

    // Real fullscreen for a video the user started: unmuted and longer than a clip (muted hero loops and short ads
    // stay put), each video once (Back out of it and it stays inline). Returns "yes" and arms the F12 handler below;
    // the app then sends a real F12 key, because requestFullscreen needs a user gesture and script calls aren't one.
    wantFullscreen: function () {
      var v = playingVideo();
      if (!v || document.fullscreenElement || v.muted || v.volume === 0) return "no";
      var mediaDocument = (document.contentType || "").indexOf("video/") === 0;   // the address is the video file itself
      if (!mediaDocument && isFinite(v.duration) && v.duration > 0 && v.duration <= 30) return "no";
      var key = v.currentSrc || v.src || "";
      if (fullscreened.indexOf(key) >= 0) return "no";
      fullscreened.push(key); armedVideo = v;
      return "yes";
    }
  };

  // ---------- video fullscreen ----------

  var fullscreened = [], armedVideo = null, savedControls = null;

  function playingVideo() {
    var list = document.querySelectorAll("video");
    for (var i = 0; i < list.length; i++) if (!list[i].paused && !list[i].ended && list[i].readyState > 2) return list[i];
    return null;
  }

  // The app's F12 arrives as a trusted key event, so requestFullscreen is allowed here. The page's own controls go
  // away while the app's video HUD is on screen (one control set), and come back when fullscreen ends.
  addEventListener("keydown", function (e) {
    if (e.key !== "F12" || !armedVideo) return;
    var v = armedVideo; armedVideo = null;
    e.preventDefault(); e.stopImmediatePropagation();
    savedControls = { video: v, controls: v.controls };
    v.controls = false;
    var go = v.requestFullscreen || v.webkitRequestFullscreen;
    try { var p = go.call(v); if (p && p.catch) p.catch(function () { v.controls = savedControls.controls; savedControls = null; }); } catch (err) {}
  }, true);

  document.addEventListener("fullscreenchange", function () {
    if (!document.fullscreenElement && savedControls) { savedControls.video.controls = savedControls.controls; savedControls = null; }
  });

  addEventListener("scroll", function () { if (focused && ring && ring.style.display !== "none") window.tvnav.refresh(); }, { passive: true });
  addEventListener("resize", function () { window.tvnav.refresh(); });
  setInterval(function () { try { window.tvnav.pollVideo(); } catch (e) {} }, 1000);
  tvbridge.ready(document.title, location.href);
})();
