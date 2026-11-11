const buckets = new Map();
exports.json = (code, body, extra = {}) => ({
  statusCode: code,
  headers: { "Content-Type": "application/json", ...extra },
  body: JSON.stringify(body),
});
exports.ip = (e) =>
  e.headers["x-nf-client-connection-ip"] || (e.headers["x-forwarded-for"] || "").split(",")[0].trim() || "unknown";
// Best-effort per-instance limiter (serverless instances don't share memory).
exports.limited = (key, max, windowMs) => {
  const now = Date.now();
  const hits = (buckets.get(key) || []).filter((t) => now - t < windowMs);
  hits.push(now);
  buckets.set(key, hits);
  if (buckets.size > 500) for (const [k, v] of buckets) if (!v.some((t) => now - t < windowMs)) buckets.delete(k);
  return hits.length > max;
};
