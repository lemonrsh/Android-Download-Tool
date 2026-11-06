// Floating "Download" button over videos, plus on-page confirmations.
// UI lives in a closed shadow root so page CSS/JS can't touch it, and only trusted
// (real user) clicks are acted on.
(() => {
  if (window !== window.top && (window.innerWidth < 200 || window.innerHeight < 120)) return;

  let overlayOn = true;
  const refreshSettings = () =>
    browser.storage.local.get(["overlay", "enabled"]).then((s) => {
      overlayOn = s.overlay !== false && s.enabled !== false;
    }).catch(() => {});
  refreshSettings();
  browser.storage.onChanged.addListener(refreshSettings);

  let host, root, btn, menu, toastEl, current = null, toastTimer = null;

  function el(tag, props = {}, text) {
    const e = document.createElement(tag);
    Object.assign(e, props);
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function ensureUI() {
    if (host) return;
    host = el("div");
    host.style.cssText = "all:initial;position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647";
    root = host.attachShadow({ mode: "closed" });
    root.appendChild(el("style", {}, `
      button{font:600 13px system-ui,sans-serif;color:#fff;background:#1a73e8;border:0;border-radius:18px;
        padding:8px 14px;box-shadow:0 2px 8px rgba(0,0,0,.45);position:fixed}
      button.small{position:static;padding:5px 10px;font-size:12px}
      #menu{position:fixed;background:#222;color:#fff;border-radius:10px;padding:6px 8px;width:min(92vw,340px);
        font:13px system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.5)}
      .row{display:flex;gap:8px;align-items:center;padding:6px 2px}
      .row span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .dim{opacity:.6}
      #toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#222;color:#fff;
        padding:10px 16px;border-radius:8px;font:13px system-ui,sans-serif;max-width:90vw;text-align:center}
      [hidden]{display:none!important}
    `));
    btn = el("button", { hidden: true }, "\u2B07 Download");
    menu = el("div", { id: "menu", hidden: true });
    toastEl = el("div", { id: "toast", hidden: true });
    root.append(btn, menu, toastEl);
    document.documentElement.appendChild(host);

    btn.addEventListener("click", (e) => { if (e.isTrusted) onButton(); });
    document.addEventListener("click", (e) => { if (!menu.hidden && e.target !== host) menu.hidden = true; }, true);
  }

  function showToast(text) {
    ensureUI();
    toastEl.textContent = text;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastEl.hidden = true; }, 4000);
  }

  const fmt = (b) => (b ? (b / 1048576 >= 1 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB") : "");

  function directUrl(v) {
    const list = [v.currentSrc, v.src, ...[...v.querySelectorAll("source")].map((s) => s.src)];
    return list.find((u) => u && /^https?:/i.test(u)) || null;
  }

  async function send(item) {
    menu.hidden = true;
    const r = await browser.runtime.sendMessage({ type: "send", item, page: location.href }).catch(() => null);
    showToast(r && r.ok ? "Sent to Fetchly: " + (r.name || item.name) : "Not sent: " + ((r && r.why) || "extension error"));
  }

  function showMenu(items) {
    menu.textContent = "";
    for (const it of items) {
      const row = el("div", { className: "row" });
      row.appendChild(el("span", { title: it.url }, it.name || it.url));
      row.appendChild(el("span", { className: "dim", style: "flex:none" }, [it.kind, fmt(it.size)].filter(Boolean).join(" \u00B7 ")));
      if (it.stream) {
        row.appendChild(el("span", { className: "dim", style: "flex:none" }, "stream"));
      } else {
        const b = el("button", { className: "small" }, "Get");
        b.addEventListener("click", (e) => { if (e.isTrusted) send(it); });
        row.appendChild(b);
      }
      menu.appendChild(row);
    }
    menu.style.left = Math.max(8, Math.min(parseInt(btn.style.left, 10) || 8, window.innerWidth - 348)) + "px";
    menu.style.top = (parseInt(btn.style.top, 10) || 0) + 44 + "px";
    menu.hidden = false;
  }

  async function onButton() {
    const direct = current ? directUrl(current) : null;
    const res = await browser.runtime.sendMessage({ type: "candidates", direct, page: location.href }).catch(() => null);
    const items = (res && res.items) || [];
    const ok = items.filter((i) => !i.stream);
    if (ok.length === 1) return send(ok[0]);
    if (ok.length > 1) return showMenu(items);
    showToast(items.length
      ? "This video uses a streaming format (HLS/DASH). Fetchly can't download those yet."
      : "No downloadable file found. The video may be protected or built from small chunks.");
  }

  function pickVideo() {
    let best = null, bestScore = 0;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      if (r.width < 160 || r.height < 90) continue;
      const w = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
      if (w * h < 160 * 90) continue;
      const score = w * h * (v.paused ? 1 : 2);
      if (score > bestScore) { best = v; bestScore = score; }
    }
    return best;
  }

  function tick() {
    if (document.hidden) return;
    const v = overlayOn ? pickVideo() : null;
    current = v;
    if (!v) {
      if (btn) { btn.hidden = true; menu.hidden = true; }
      return;
    }
    ensureUI();
    const r = v.getBoundingClientRect();
    btn.style.left = Math.max(8, Math.min(r.right, window.innerWidth) - 120) + "px";
    btn.style.top = Math.max(8, r.top + 8) + "px";
    btn.hidden = false;
  }
  setInterval(tick, 600);

  browser.runtime.onMessage.addListener((msg) => {
    if (msg && msg.type === "toast" && window === window.top) showToast(String(msg.text));
  });
})();
