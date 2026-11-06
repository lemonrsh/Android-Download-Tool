const $ = (id) => document.getElementById(id);
const fmt = (b) => (b ? (b / 1048576 >= 1 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB") : "");

async function main() {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
  const res = await browser.runtime.sendMessage({ type: "list", tabId: tab.id });
  const items = (res && res.items) || [];
  const list = $("list");
  list.textContent = "";
  if (!items.length) {
    list.textContent = "Nothing detected yet. Start playing the video, then reopen this.";
    return;
  }
  for (const it of items) {
    const row = document.createElement("div");
    row.className = "row";
    const name = document.createElement("div");
    name.className = "name";
    name.title = it.url;
    name.textContent = it.name || it.url;
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = [it.kind, fmt(it.size)].filter(Boolean).join(" · ");
    row.append(name, meta);
    if (it.stream) {
      const s = document.createElement("div");
      s.className = "meta";
      s.textContent = "not supported";
      row.append(s);
    } else {
      const b = document.createElement("button");
      b.textContent = "Download";
      b.addEventListener("click", async () => {
        b.disabled = true;
        const r = await browser.runtime.sendMessage({ type: "send", item: it, tabId: tab.id, page: tab.url });
        $("msg").textContent = r && r.ok ? "Sent to Fetchly." : "Not sent: " + ((r && r.why) || "unknown error");
        b.disabled = false;
      });
      row.append(b);
    }
    list.append(row);
  }
}
main();
