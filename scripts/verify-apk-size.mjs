#!/usr/bin/env node
// APK size ratchet: the number that only ever grows unless something compares it.
// Usage:
//   node scripts/verify-apk-size.mjs            # compare built APKs against scripts/apk-size-baseline.json
//   node scripts/verify-apk-size.mjs --update   # record the current sizes as the new baseline
//   node scripts/verify-apk-size.mjs --root <dir> --baseline <file>
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { basename, dirname, join, relative, resolve } from "node:path";

const args = process.argv.slice(2);
const valueOf = (flag) => {
  const index = args.indexOf(flag);
  return index === -1 ? undefined : args[index + 1];
};
const ROOT = resolve(valueOf("--root") ?? process.cwd());
const BASELINE = resolve(valueOf("--baseline") ?? join(ROOT, "scripts/apk-size-baseline.json"));
const UPDATE = args.includes("--update");
const APK_ROOT = join(ROOT, "app/build/outputs/apk");

if (!existsSync(BASELINE)) {
  console.error(`FAIL missing ${relative(ROOT, BASELINE)}; create it with --update after a successful assembleDebug.`);
  process.exit(1);
}
const baseline = JSON.parse(readFileSync(BASELINE, "utf8").replace(/^\uFEFF/, ""));
const tolerance = baseline.toleranceRatio ?? 0.01;
const limits = baseline.artifacts ?? {};

const found = [];
if (existsSync(APK_ROOT)) {
  for (const variant of readdirSync(APK_ROOT)) {
    const dir = join(APK_ROOT, variant);
    let entries = [];
    try {
      entries = readdirSync(dir);
    } catch {
      continue;
    }
    for (const name of entries) {
      if (!name.endsWith(".apk")) continue;
      found.push({ variant, file: join(dir, name), size: statSync(join(dir, name)).size });
    }
  }
}

if (found.length === 0) {
  console.log("SKIP no APK build outputs yet (run ./gradlew :app:assembleDebug first).");
  process.exit(0);
}

const results = [];
let failed = false;
for (const apk of found.sort((a, b) => a.variant.localeCompare(b.variant))) {
  const limit = limits[apk.variant];
  if (limit === undefined) {
    if (UPDATE) {
      results.push(`RECORD   ${apk.variant} ${apk.size.toLocaleString()} B`);
      limits[apk.variant] = apk.size;
    } else {
      results.push(`UNKNOWN  ${apk.variant} ${apk.size.toLocaleString()} B — no baseline entry; run with --update and commit the baseline`);
      failed = true;
    }
    continue;
  }
  const ceiling = Math.floor(limit * (1 + tolerance));
  if (apk.size > ceiling) {
    results.push(`OVER     ${apk.variant} ${apk.size.toLocaleString()} B > ${ceiling.toLocaleString()} B (baseline ${limit.toLocaleString()}, tolerance ${(tolerance * 100).toFixed(1)}%). Shrink the APK, or raise the baseline with a stated reason.`);
    failed = true;
  } else {
    results.push(`OK       ${apk.variant} ${apk.size.toLocaleString()} B / ceiling ${ceiling.toLocaleString()} B`);
  }
}

if (UPDATE) {
  writeFileSync(BASELINE, `${JSON.stringify({ toleranceRatio: tolerance, artifacts: limits }, null, 2)}\n`);
  console.log(results.join("\n"));
  console.log(`\nUPDATED ${relative(ROOT, BASELINE)}; commit it with the change that altered the APK.`);
  process.exit(0);
}

console.log(results.join("\n"));
if (failed) {
  console.error(`\nFAIL APK size ratchet: ${relative(ROOT, BASELINE)} is the budget of record.`);
  process.exit(1);
}
console.log(`\nPASS ${found.length} APK artifact(s) within the size budget.`);
