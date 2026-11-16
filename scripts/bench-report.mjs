// Summarize bench results: node scripts/bench-report.mjs <bench.jsonl>
import fs from "node:fs";

const all = fs.readFileSync(process.argv[2], "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
// Keep one row per text and mode: a success wins over an earlier error from a retry.
const byKey = new Map();
for (const r of all) {
  const k = `${r.id}|${r.mode}`;
  if (!byKey.has(k) || (byKey.get(k).error && !r.error)) byKey.set(k, r);
}
const rows = [...byKey.values()];
const pct = (a, b) => (b ? `${((100 * a) / b).toFixed(1)}%` : "-");
const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length ? s[Math.floor(s.length / 2)] : 0;
};
const out = [];
for (const mode of ["rules", "device", "shortstop", "cloudraw"]) {
  const r = rows.filter((x) => x.mode === mode && !x.error);
  if (!r.length) continue;
  const errors = rows.filter((x) => x.mode === mode && x.error).length;
  const scam = r.filter((x) => x.kind === "scam");
  const ok = r.filter((x) => x.kind !== "scam");
  const caught = scam.filter((x) => x.verdict === "scam").length;
  const flagged = scam.filter((x) => x.verdict !== "safe").length;
  const falseScam = ok.filter((x) => x.verdict === "scam").length;
  const warned = ok.filter((x) => x.verdict !== "safe").length;
  const correct = r.filter((x) => (x.kind === "scam" ? x.verdict === "scam" : x.verdict === "safe")).length;
  const cloud = r.filter((x) => x.cloudCalls > 0).length;
  const secrets = r.reduce((n, x) => n + x.secrets, 0);
  const leaked = r.reduce((n, x) => n + x.leaked.length, 0);
  out.push({
    mode,
    rows: r.length,
    errors,
    correct: pct(correct, r.length),
    scam_called_scam: pct(caught, scam.length),
    scam_missed_as_safe: pct(scam.length - flagged, scam.length),
    safe_called_scam: pct(falseScam, ok.length),
    safe_warned: pct(warned, ok.length),
    went_to_cloud: pct(cloud, r.length),
    cloud_calls: r.reduce((n, x) => n + x.cloudCalls, 0),
    median_ms: median(r.map((x) => x.ms)),
    secrets_leaked: `${leaked} / ${secrets}`,
    leaked_examples: r.flatMap((x) => x.leaked.map((s) => `${x.id}: ${s}`)).slice(0, 15),
  });
}
console.log(JSON.stringify(out, null, 1));
