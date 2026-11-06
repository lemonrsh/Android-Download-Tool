const DEFAULTS = {
  enabled: true,
  overlay: true,
  token: "",
  minSizeMB: 1,
  extensions: "zip,rar,7z,gz,tgz,tar,xz,iso,apk,exe,msi,dmg,deb,mkv,avi",
};
const $ = (id) => document.getElementById(id);
const say = (msg) => { $("status").textContent = msg; };

async function load() {
  const cfg = { ...DEFAULTS, ...(await browser.storage.local.get()) };
  $("enabled").checked = cfg.enabled;
  $("overlay").checked = cfg.overlay;
  $("token").value = cfg.token;
  $("minSizeMB").value = cfg.minSizeMB;
  $("extensions").value = cfg.extensions;
}

async function save() {
  await browser.storage.local.set({
    enabled: $("enabled").checked,
    overlay: $("overlay").checked,
    token: $("token").value.trim(),
    minSizeMB: Number($("minSizeMB").value) || 0,
    extensions: $("extensions").value.trim(),
  });
  say("Saved.");
}

async function test() {
  await save();
  try {
    const res = await fetch("http://127.0.0.1:6877/ping", {
      headers: { "X-Fetchly-Token": $("token").value.trim() },
    });
    if (res.ok) say("Connected to Fetchly.");
    else if (res.status === 401) say("The app is running but rejected the token.");
    else say("Unexpected response: HTTP " + res.status);
  } catch (e) {
    say("Can't reach the app. Open Fetchly and turn on Settings → Browser integration.");
  }
}

async function showLog() {
  const { log = [] } = await browser.storage.local.get("log");
  $("log").textContent = log.length
    ? log.map((e) => e.t + "  " + e.result + "\n    " + e.url).join("\n\n")
    : "Nothing yet. Tap a download link in Firefox, then come back.";
}
browser.storage.onChanged.addListener(showLog);
$("clear").addEventListener("click", async () => { await browser.storage.local.set({ log: [] }); });
showLog();

$("save").addEventListener("click", save);
$("test").addEventListener("click", test);
load();
