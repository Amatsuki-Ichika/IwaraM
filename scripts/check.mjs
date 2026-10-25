#!/usr/bin/env node
// One command for every mechanically checkable rule in this repository.
// Each gate is a separate process so one failure never hides another.
// Usage: node scripts/check.mjs [--list-only]
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const listOnly = process.argv.includes("--list-only");
const GATES = [
  ["doc-budgets", "scripts/verify-doc-budgets.mjs"],
  ["md-links", "scripts/verify-md-links.mjs"],
  ["md-hygiene", "scripts/verify-md-hygiene.mjs"],
  ["decisions", "scripts/verify-decisions.mjs"],
  ["apk-size", "scripts/verify-apk-size.mjs"],
  ["tracked-files", "scripts/verify-tracked-files.mjs"],
];

let failed = 0;
for (const [label, script] of GATES) {
  process.stdout.write(`\n=== ${label} ===\n`);
  const result = spawnSync(process.execPath, [script], { cwd: ROOT, stdio: "inherit" });
  if (result.status !== 0) failed += 1;
}

const summary = failed > 0 ? `FAIL ${failed}/${GATES.length} gate(s) failed.` : `PASS ${GATES.length}/${GATES.length} gate(s) passed.`;
if (failed > 0 && !listOnly) {
  console.error(`\n${summary} Fix the first failure above; each message names the file and the repair.`);
  process.exit(1);
}
console.log(`\n${summary}`);
