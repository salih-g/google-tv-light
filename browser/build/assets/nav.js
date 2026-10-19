// Injected into every page: D-pad link navigation, overlay removal, video hooks.
// Android talks to it through window.tvnav; it talks back through the tvbridge interface.
(function () {
  if (window.tvnav) return;
  var focused = null, ring = null, lastVideoReport = "";

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) return false;
    var s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none" && parseFloat(s.opacity) > 0.05;
  }

  function candidates() {
    var sel = 'a[href], button, input, select, textarea, summary, [role="button"], [role="link"], [tabindex]:not([tabindex="-1"]), [onclick], video';
    var out = [], seen = new Set();
    document.querySelectorAll(sel).forEach(function (el) {
      if (seen.has(el) || !visible(el)) return;
      seen.add(el); out.push(el);
    });
    return out;
  }

  function rect(el) { var r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, l: r.left, t: r.top, r: r.right, b: r.bottom }; }

  function ensureRing() {
    if (ring) return ring;
    ring = document.createElement("div");
    ring.id = "__tvnav_ring";
    ring.style.cssText = "position:fixed;pointer-events:none;z-index:2147483646;border:3px solid #2f7dff;border-radius:8px;box-shadow:0 0 0 2px #fff,0 8px 24px rgba(0,0,0,.45);transition:all .12s ease-out;display:none";
    document.documentElement.appendChild(ring);
    return ring;
  }

  function highlight(el) {
    focused = el;
    var r = el.getBoundingClientRect(), g = ensureRing();
    g.style.display = "block";
    g.style.left = (r.left - 4) + "px"; g.style.top = (r.top - 4) + "px";
    g.style.width = (r.width + 2) + "px"; g.style.height = (r.height + 2) + "px";
    try { el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); } catch (e) {}
    var label = (el.getAttribute("aria-label") || el.title || el.innerText || el.alt || el.href || "").trim().slice(0, 80);
    tvbridge.focused(label, el.tagName.toLowerCase(), !!el.closest("a[href]") ? el.closest("a[href]").href : "");
  }

  function pick(dir) {
    var list = candidates();
    if (!focused || !document.contains(focused) || !visible(focused)) {
      // nothing focused: start from the top-left most visible element
      focused = null;
      var best = null, bestScore = Infinity;
      list.forEach(function (el) { var r = rect(el); var s = r.t * 3 + r.l; if (r.t >= 0 && s < bestScore) { bestScore = s; best = el; } });
      return best;
    }
    var f = rect(focused), best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === focused) return;
      var r = rect(el), dx = r.x - f.x, dy = r.y - f.y, primary, secondary;
      if (dir === "left") { primary = -dx; secondary = Math.abs(dy); if (r.r > f.l + 4) return; }
      else if (dir === "right") { primary = dx; secondary = Math.abs(dy); if (r.l < f.r - 4) return; }
      else if (dir === "up") { primary = -dy; secondary = Math.abs(dx); if (r.b > f.t + 4) return; }
      else { primary = dy; secondary = Math.abs(dx); if (r.t < f.b - 4) return; }
      if (primary <= 0) return;
      var score = primary + secondary * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    return best;
  }

  window.tvnav = {
    move: function (dir) {
      var next = pick(dir);
      if (next) { highlight(next); return true; }
      // nothing further in that direction: scroll the page a step and retry
      var step = dir === "up" || dir === "down" ? innerHeight * 0.6 : innerWidth * 0.5;
      var before = [scrollX, scrollY];
      scrollBy({ top: dir === "down" ? step : dir === "up" ? -step : 0, left: dir === "right" ? step : dir === "left" ? -step : 0, behavior: "smooth" });
      setTimeout(function () { var n = pick(dir); if (n) highlight(n); else if (focused) highlight(focused); }, 220);
      return before[0] !== scrollX || before[1] !== scrollY;
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
    refresh: function () { if (focused && document.contains(focused) && visible(focused)) highlight(focused); else this.clear(); },

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
      var v = document.querySelector("video");
      var rep = v ? [v.paused ? 0 : 1, Math.round(v.currentTime), Math.round(v.duration || 0), v.playbackRate, document.title].join("|") : "";
      if (rep !== lastVideoReport) { lastVideoReport = rep; tvbridge.video(rep); }
    }
  };

  addEventListener("scroll", function () { if (focused && ring && ring.style.display !== "none") window.tvnav.refresh(); }, { passive: true });
  addEventListener("resize", function () { window.tvnav.refresh(); });
  setInterval(function () { try { window.tvnav.pollVideo(); } catch (e) {} }, 1000);
  tvbridge.ready(document.title, location.href);
})();
