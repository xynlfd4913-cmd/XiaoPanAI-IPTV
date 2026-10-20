const fs = require("fs");
const path = require("path");

const sourceUrl = process.env.IPTV_SOURCE_URL;
const projectRoot = path.resolve(__dirname, "..");
const assetsDir = path.join(projectRoot, "app", "src", "main", "assets");
const distDir = path.join(projectRoot, "dist");
const blockedText = /公告|作者|更新(?:时间)?|赞助|捐赠|广告|支持|网址|测试|示例|yuanzl77|776512/i;

function parseAttrs(line) {
  const attrs = {};
  const pattern = /([\w-]+)="([^"]*)"/g;
  let match;
  while ((match = pattern.exec(line))) attrs[match[1]] = match[2];
  return attrs;
}

function normalizeName(value) {
  return String(value || "")
    .replace(/CCTV[-_\s]*(\d+)/gi, "CCTV$1")
    .replace(/CCTV5\s*\+/gi, "CCTV5+")
    .replace(/\s+/g, "")
    .trim();
}

function cleanUrl(value) {
  const url = String(value || "").split("$", 1)[0].trim().replace(/\s/g, "");
  if (!/^https?:\/\//i.test(url)) return "";
  if (!/\.m3u8(?:[?#]|$)|\.mp4(?:[?#]|$)|\.flv(?:[?#]|$)/i.test(url)) return "";
  return url;
}

function parseM3u(content) {
  const entries = [];
  const lines = content.replace(/^\uFEFF/, "").split(/\r?\n/);
  let pending = null;

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith("#EXTINF")) {
      const attrs = parseAttrs(line);
      const commaIndex = line.lastIndexOf(",");
      pending = {
        name: normalizeName(attrs["tvg-name"] || line.slice(commaIndex + 1)),
        group: String(attrs["group-title"] || "其他频道").trim(),
      };
      continue;
    }
    if (line.startsWith("#") || !pending) continue;
    const url = cleanUrl(line);
    if (pending.name && url && !blockedText.test(pending.name) && !blockedText.test(pending.group)) {
      entries.push({ ...pending, url });
    }
    pending = null;
  }
  return entries;
}

function channelRank(channel) {
  const text = channel.name + channel.group;
  if (/^CCTV/i.test(channel.name)) return 0;
  if (/^CGTN/i.test(channel.name)) return 1;
  if (/卫视/.test(text)) return 2;
  if (/广东频道/.test(channel.group)) return 3;
  if (/港|澳|台|凤凰|翡翠|明珠/.test(text)) return 4;
  if (/少儿|卡通|动漫/.test(text)) return 5;
  if (/影视|电影|戏剧/.test(text)) return 6;
  return 7;
}

function cctvNumber(name) {
  const match = name.match(/^CCTV(\d+)(\+)?/i);
  return match ? Number(match[1]) + (match[2] ? 0.5 : 0) : 999;
}

function compareChannels(a, b) {
  const rankDiff = channelRank(a) - channelRank(b);
  if (rankDiff !== 0) return rankDiff;
  if (channelRank(a) === 0) return cctvNumber(a.name) - cctvNumber(b.name);
  return a.order - b.order;
}

async function main() {
  if (!sourceUrl) {
    throw new Error("Set IPTV_SOURCE_URL to an M3U address you are authorized to use");
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  let response;
  try {
    response = await fetch(sourceUrl, {
      signal: controller.signal,
      headers: { "User-Agent": "XiaoPanAI-IPTV source updater" },
    });
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) throw new Error(`Source request failed: HTTP ${response.status}`);

  const byName = new Map();
  for (const entry of parseM3u(await response.text())) {
    if (!byName.has(entry.name)) {
      byName.set(entry.name, { ...entry, order: byName.size, sources: [] });
    }
    const channel = byName.get(entry.name);
    if (!channel.sources.includes(entry.url)) channel.sources.push(entry.url);
  }

  const channels = Array.from(byName.values())
    .filter((channel) => channel.sources.length > 0)
    .sort(compareChannels);
  const assets = [
    "# Generated core IPTV channel list",
    "# UTF-8; channel names and direct stream URLs only",
  ];
  const m3u = ["#EXTM3U"];
  const txt = [];
  let currentGroup = "";

  for (const channel of channels) {
    if (channel.group !== currentGroup) {
      currentGroup = channel.group;
      txt.push(`${currentGroup},#genre#`);
    }
    for (const url of channel.sources) {
      assets.push(`${channel.name},${url}`);
      m3u.push(`#EXTINF:-1 group-title="${channel.group}",${channel.name}`, url);
      txt.push(`${channel.name},${url}`);
    }
  }

  fs.mkdirSync(assetsDir, { recursive: true });
  fs.mkdirSync(distDir, { recursive: true });
  fs.writeFileSync(path.join(assetsDir, "channels.txt"), `${assets.join("\n")}\n`, "utf8");
  fs.writeFileSync(path.join(distDir, "live.m3u"), `${m3u.join("\n")}\n`, "utf8");
  fs.writeFileSync(path.join(distDir, "live.txt"), `${txt.join("\n")}\n`, "utf8");

  const sourceCount = channels.reduce((sum, channel) => sum + channel.sources.length, 0);
  console.log(`Updated ${channels.length} channels / ${sourceCount} sources`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
