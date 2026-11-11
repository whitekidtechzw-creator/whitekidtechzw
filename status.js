const { json, ip, limited } = require("./_util");

const TARGETS = [
  { id: "prime-tv", name: "WHITEKID PRIME TV", url: "https://whitekid-prime-tv.site.je" },
  { id: "prime-bot", name: "WHITEKID PRIME BOT", url: "https://t.me/whitekid_prime_bot", telegram: true },
  { id: "venomous", name: "VENOMOUS MD", url: "https://t.me/VenomousMd_bot", telegram: true },
  { id: "astra", name: "ASTRA MINI", url: "https://astraminiv2.onrender.com/" },
  { id: "kryptix", name: "KRYPTIX X-MD", url: "https://kryptix-xmd.zone.id" },
];

async function probe(t) {
  // Telegram bots can't be health-checked from outside; t.me is always up, so we don't pretend.
  if (t.telegram) return { id: t.id, name: t.name, url: t.url, state: "N/A", note: "Telegram bot (not probeable)" };
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 7000);
  const t0 = Date.now();
  try {
    const r = await fetch(t.url, { signal: ctl.signal, redirect: "follow", headers: { "User-Agent": "whitekid-status-check" } });
    const ms = Date.now() - t0;
    return { id: t.id, name: t.name, url: t.url, state: r.status < 400 ? "ONLINE" : "DEGRADED", code: r.status, ms };
  } catch {
    return { id: t.id, name: t.name, url: t.url, state: "NO RESPONSE", note: "No reply within 7s (free hosts may be asleep)" };
  } finally {
    clearTimeout(timer);
  }
}

exports.handler = async (event) => {
  if (limited("st:" + ip(event), 20, 60000)) return json(429, { error: "Too many checks. Wait a minute." });
  const results = await Promise.all(TARGETS.map(probe));
  return json(200, { checkedAt: new Date().toISOString(), results }, { "Cache-Control": "public, max-age=30" });
};
