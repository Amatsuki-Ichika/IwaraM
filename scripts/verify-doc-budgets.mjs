#!/usr/bin/env node
// Word-count ceiling gate for standing docs (the "documentation budget").
// Failure is actionable: it names the file, both numbers, and the three repairs.
// Usage:
//   node scripts/verify-doc-budgets.mjs            # check (exit 1 when over or missing)
//   node scripts/verify-doc-budgets.mjs --list     # report only, never fails on OVER
//   node scripts/verify-doc-budgets.mjs --ratchet  # lower ceilings toward current size + 5% headroom
import { existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const MANIFEST = resolve(ROOT, "scripts/doc-budgets.json");
const HEADROOM = 0.05;
const args = new Set(process.argv.slice(2));
const listOnly = args.has("--list");
const ratchet = args.has("--ratchet");

// One budget unit = one Latin word, or two CJK characters. A Chinese page holds
// roughly twice the information per "word", so counting raw whitespace tokens
// would let CJK docs grow to several times the intended ceiling.
const CJK = /[\u3040-\u30FF\u3400-\u9FFF\uF900-\uFAFF]/g;

function countWords(text) {
  const cjk = (text.match(CJK) ?? []).length;
  const latin = text.replace(CJK, " ").match(/[A-Za-z0-9][A-Za-z0-9_@#$%&*+=<>/\\|~^`'".,:;!?()[\]{}-]*/g) ?? [];
  return latin.length + Math.ceil(cjk / 2);
}

if (!existsSync(MANIFEST)) {
  console.error(`FAIL missing ${relative(ROOT, MANIFEST)}`);
  process.exit(2);
}

const parsed = JSON.parse(readFileSync(MANIFEST, "utf8").replace(/^\uFEFF/, ""));
const budgets = parsed.budgets ?? parsed;
const entries = Object.entries(budgets).filter(([key]) => !key.startsWith("$"));
if (entries.length === 0) {
  console.error("FAIL doc-budgets.json declares no budgets");
  process.exit(2);
}

const rows = [];
let failed = false;
let ratcheted = 0;

for (const [file, ceiling] of entries) {
  const abs = resolve(ROOT, file);
  if (!existsSync(abs) || !statSync(abs).isFile()) {
    rows.push({ file, state: "MISSING", detail: "budgeted file does not exist (renamed or deleted? update scripts/doc-budgets.json in the same change)" });
    failed = true;
    continue;
  }
  const words = countWords(readFileSync(abs, "utf8").replace(/^\uFEFF/, ""));
  if (words > ceiling) {
    rows.push({ file, state: "OVER", detail: `${words} / ${ceiling} words (+${words - ceiling}). Repairs: relocate the detail to its owning page and leave a link; condense; or raise the ceiling in scripts/doc-budgets.json with the reason stated in the PR.` });
    failed = true;
  } else if (ratchet && words < ceiling) {
    const next = Math.max(1, Math.ceil(words * (1 + HEADROOM)));
    if (next < ceiling) {
      budgets[file] = next;
      ratcheted += 1;
      rows.push({ file, state: "RATCHET", detail: `${words} / ${ceiling} words -> ceiling lowered to ${next} (5% headroom kept)` });
    } else {
      rows.push({ file, state: "OK", detail: `${words} / ${ceiling} words` });
    }
  } else {
    rows.push({ file, state: "OK", detail: `${words} / ${ceiling} words` });
  }
}

for (const row of rows) {
  console.log(`${row.state.padEnd(8)} ${row.file}  ${row.detail}`);
}

if (ratchet && ratcheted > 0) {
  writeFileSync(MANIFEST, `${JSON.stringify(parsed, null, 2)}\n`);
  console.log(`\nRATCHETED ${ratcheted} ceiling(s) in ${relative(ROOT, MANIFEST)}; commit the manifest with the content change.`);
}

if (listOnly) process.exit(0);
if (failed) {
  console.error(`\nFAIL ${rows.filter((row) => row.state !== "OK" && row.state !== "RATCHET").length} of ${rows.length} budgeted doc(s). Numbers live only in ${relative(ROOT, MANIFEST)}; do not restate them in prose.`);
  process.exit(1);
}
console.log(`\nPASS ${rows.length} budgeted doc(s) within their ceilings.`);
