// Fetchly browser integration.
//  1. Captures file downloads (attachments, archives, apps...) and hands them to the app.
//  2. Detects videos/audio loaded by the current page and lists them per tab (toolbar popup,
//     badge count, and the floating button injected by content.js).
// The app listens on 127.0.0.1:6877; if it is unreachable Firefox downloads normally.

const API = "http://127.0.0.1:6877";
const DEFAULTS = {
  enabled: true,
  overlay: true,
  token: "",
  minSizeMB: 1,
  extensions: "zip,rar,7z,gz,tgz,tar,xz,iso,apk,exe,msi,dmg,deb,mkv,avi",
};
const MEDIA_EXT = new Set(["mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp", "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav"]);
const AUDIO_EXT = new Set(["mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav"]);
const STREAM_EXT = new Set(["m3u8", "mpd"]);
const SEGMENT_EXT = new Set(["ts", "m4s", "cmfv", "cmfa"]);
const TYPE_EXT = {
  "video/mp4": "mp4", "video/webm": "webm", "video/x-matroska": "mkv", "video/quicktime": "mov",
  "video/x-flv": "flv", "video/3gpp": "3gp", "audio/mpeg": "mp3", "audio/mp4": "m4a", "audio/aac": "aac",
  "audio/ogg": "ogg", "audio/webm": "weba", "audio/flac": "flac", "audio/wav": "wav", "audio/x-wav": "wav",
  "audio/opus": "opus",
};

async function getConfig() {
  return { ...DEFAULTS, ...(await browser.storage.local.get()) };
}

async function log(url, result) {
  try {
    const { log: old = [] } = await browser.storage.local.get("log");
    old.unshift({ t: new Date().toLocaleTimeString(), url: String(url).slice(0, 140), result });
    await browser.storage.local.set({ log: old.slice(0, 15) });
  } catch (e) { /* logging must never break downloads */ }
}

function header(details, name) {
  const h = (details.responseHeaders || []).find((x) => x.name.toLowerCase() === name);
  return h && h.value ? h.value : "";
}

function safeDecode(s) {
  try { return decodeURIComponent(s); } catch (e) { return s; }
}

function cleanName(s) {
  return String(s).replace(/[\\/:*?"<>|\u0000-\u001f]/g, " ").replace(/\s+/g, " ").trim().slice(0, 100);
}

async function cookieHeader(url) {
  try {
    const cookies = await browser.cookies.getAll({ url });
    return cookies.map((c) => c.name + "=" + c.value).join("; ");
  } catch (e) {
    return "";
  }
}

async function sendToApp(cfg, payload) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 2000);
  try {
    const res = await fetch(API + "/add", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Fetchly-Token": cfg.token },
      body: JSON.stringify(payload),
      signal: ctrl.signal,
    });
    return res.ok ? { ok: true } : { ok: false, why: "app answered HTTP " + res.status };
  } catch (e) {
    return { ok: false, why: "can't reach app (" + (e && e.message ? e.message : e) + ")" };
  } finally {
    clearTimeout(timer);
  }
}

function toast(tabId, text) {
  if (tabId >= 0) browser.tabs.sendMessage(tabId, { type: "toast", text }, { frameId: 0 }).catch(() => {});
}

// ------------------------------------------------------------------ 1. file downloads

function decide(details, cfg) {
  if (details.statusCode < 200 || details.statusCode >= 300) {
    return { ok: false, why: "HTTP " + details.statusCode };
  }
  const disposition = header(details, "content-disposition").toLowerCase();
  const type = header(details, "content-type").toLowerCase();
  const length = parseInt(header(details, "content-length") || "0", 10);

  let ext = "";
  try {
    ext = new URL(details.url).pathname.toLowerCase().split(".").pop();
  } catch (e) {
    return { ok: false, why: "bad url" };
  }
  const wanted = new Set(cfg.extensions.toLowerCase().split(/[\s,]+/).filter(Boolean));

  const isAttachment = disposition.startsWith("attachment");
  const isBinary = /^application\/(octet-stream|zip|x-zip-compressed|x-rar-compressed|vnd\.rar|x-7z-compressed|gzip|x-gzip|x-tar|x-msdownload|vnd\.android\.package-archive|x-iso9660-image)/.test(type);
  const byExtension = wanted.has(ext) && !type.startsWith("text/html");

  if (!(isAttachment || isBinary || byExtension)) {
    return { ok: false, why: "not a download (type " + (type || "none") + ", ." + ext + ")" };
  }
  if (length && length < cfg.minSizeMB * 1048576) {
    return { ok: false, why: "smaller than " + cfg.minSizeMB + " MB (" + length + " bytes)" };
  }
  return { ok: true, why: "match" };
}

function fileNameOf(details) {
  const m = header(details, "content-disposition").match(/filename\*?\s*=\s*(?:[^']*'')?"?([^";]+)"?/i);
  if (m) return safeDecode(m[1].trim());
  try { return safeDecode(new URL(details.url).pathname.split("/").pop() || "file"); } catch (e) { return "file"; }
}

browser.webRequest.onHeadersReceived.addListener(
  async (details) => {
    const cfg = await getConfig();
    if (!cfg.enabled) return {};

    const d = decide(details, cfg);
    if (!d.ok) {
      const isPage = /^(text\/html|application\/xhtml)/i.test(header(details, "content-type"));
      if (!isPage) await log(details.url, "skipped: " + d.why);
      return {};
    }
    if (!cfg.token) {
      await log(details.url, "skipped: no token saved in options");
      return {};
    }

    const r = await sendToApp(cfg, {
      url: details.url,
      referer: details.originUrl || details.documentUrl || "",
      cookie: await cookieHeader(details.url),
      userAgent: navigator.userAgent,
    });
    await log(details.url, r.ok ? "sent to Fetchly" : "NOT sent: " + r.why);
    if (r.ok) toast(details.tabId, "Sent to Fetchly: " + fileNameOf(details));
    return r.ok ? { cancel: true } : {};
  },
  { urls: ["<all_urls>"], types: ["main_frame", "sub_frame"] },
  ["blocking", "responseHeaders"]
);

// ------------------------------------------------------------------ 2. media detection

const media = new Map(); // tabId -> Map(key -> item)

function mediaInfo(details) {
  if (details.statusCode < 200 || details.statusCode >= 300) return null;
  let u;
  try { u = new URL(details.url); } catch (e) { return null; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return null;

  const type = header(details, "content-type").toLowerCase().split(";")[0].trim();
  const ext = u.pathname.toLowerCase().split(".").pop();
  if (SEGMENT_EXT.has(ext) || type === "video/mp2t") return null;

  const stream = STREAM_EXT.has(ext) || /mpegurl|dash\+xml/.test(type);
  const isMedia = type.startsWith("video/") || type.startsWith("audio/") || (MEDIA_EXT.has(ext) && !type.startsWith("text/"));
  if (!stream && !isMedia) return null;

  let size = 0;
  const range = header(details, "content-range").match(/\/(\d+)\s*$/);
  size = range ? parseInt(range[1], 10) : parseInt(header(details, "content-length") || "0", 10);
  if (!stream && size && size < 200 * 1024) return null; // tiny chunks, ads, sound effects

  const audio = type.startsWith("audio/") || AUDIO_EXT.has(ext);
  const name = safeDecode(u.pathname.split("/").pop() || "") || u.hostname;
  return { url: details.url, type, size, name, stream, kind: stream ? "stream" : audio ? "audio" : "video" };
}

function mediaKey(url) {
  try {
    const u = new URL(url);
    u.hash = "";
    for (const p of ["range", "bytestart", "byteend"]) u.searchParams.delete(p);
    return u.toString();
  } catch (e) {
    return url;
  }
}

function listFor(tabId) {
  const m = media.get(tabId);
  return m ? [...m.values()].sort((a, b) => b.size - a.size) : [];
}

function updateBadge(tabId) {
  const n = listFor(tabId).filter((i) => !i.stream).length;
  try {
    browser.browserAction.setBadgeText({ tabId, text: n ? String(n) : "" });
    browser.browserAction.setBadgeBackgroundColor({ color: "#1a73e8" });
  } catch (e) { /* badges are optional */ }
}

function addMedia(tabId, info) {
  let m = media.get(tabId);
  if (!m) { m = new Map(); media.set(tabId, m); }
  const key = mediaKey(info.url);
  const old = m.get(key);
  if (old) {
    if (info.size > old.size) old.size = info.size;
    return;
  }
  if (m.size >= 40) m.delete(m.keys().next().value);
  m.set(key, info);
  updateBadge(tabId);
}

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const info = mediaInfo(details);
    if (info) addMedia(details.tabId, info);
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "object", "other", "main_frame", "sub_frame"] },
  ["responseHeaders"]
);

browser.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url && media.has(tabId)) {
    media.delete(tabId);
    updateBadge(tabId);
  }
});
browser.tabs.onRemoved.addListener((tabId) => media.delete(tabId));

function candidates(tabId, direct) {
  const items = listFor(tabId);
  if (direct && /^https?:/i.test(direct) && !items.some((i) => mediaKey(i.url) === mediaKey(direct))) {
    let name = "video";
    try { name = safeDecode(new URL(direct).pathname.split("/").pop() || "video"); } catch (e) { /* keep default */ }
    items.unshift({ url: direct, type: "", size: 0, name, stream: false, kind: "video" });
  }
  return items;
}

async function sendMedia(tabId, item, page) {
  const cfg = await getConfig();
  if (!cfg.token) return { ok: false, why: "Paste the pairing token in the Fetchly extension settings first." };
  let u;
  try { u = new URL(item && item.url); } catch (e) { return { ok: false, why: "bad link" }; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return { ok: false, why: "bad link" };

  // Use the URL's own file name when it looks like a media file, otherwise the page title.
  let filename = "";
  const last = safeDecode(u.pathname.split("/").pop() || "");
  const ext = last.toLowerCase().split(".").pop();
  if (last.includes(".") && MEDIA_EXT.has(ext) && last.length < 100) {
    filename = last;
  } else if (tabId >= 0) {
    try {
      const tab = await browser.tabs.get(tabId);
      const title = cleanName(tab.title || "");
      const guess = TYPE_EXT[item.type] || (MEDIA_EXT.has(ext) ? ext : item.kind === "audio" ? "m4a" : "mp4");
      if (title) filename = title + "." + guess;
    } catch (e) { /* fall back to the app's own guess */ }
  }

  const r = await sendToApp(cfg, {
    url: item.url,
    filename,
    referer: page || "",
    cookie: await cookieHeader(item.url),
    userAgent: navigator.userAgent,
  });
  await log(item.url, r.ok ? "media sent to Fetchly" : "media NOT sent: " + r.why);
  return r.ok ? { ok: true, name: filename || item.name } : r;
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (!msg || typeof msg.type !== "string") return undefined;
  const tabId = sender.tab ? sender.tab.id : msg.tabId; // popup has no sender.tab and passes its own
  if (msg.type === "list") return Promise.resolve({ items: listFor(tabId) });
  if (msg.type === "candidates") return Promise.resolve({ items: candidates(tabId, msg.direct) });
  if (msg.type === "send") return sendMedia(tabId, msg.item, msg.page);
  return undefined;
});
