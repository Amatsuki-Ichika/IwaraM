#!/usr/bin/env node
// Tracked-file hygiene: secrets, signing material, build output and agent scratch
// state must never enter git. .gitignore covers the future; this covers the index.
// Usage: node scripts/verify-tracked-files.mjs [--root <dir>]
import { spawnSync } from "node:child_process";
import { closeSync, existsSync, openSync, readFileSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";

const argRoot = process.argv.indexOf("--root");
const ROOT = resolve(argRoot !== -1 ? process.argv[argRoot + 1] : process.cwd());

if (!existsSync(join(ROOT, ".git"))) {
  console.log("SKIP not a git repository (nothing to check).");
  process.exit(0);
}

// Capture stdout through a file instead of a pipe: some sandboxes reject piped
// child stdio, and a file descriptor keeps the gate usable there too.
const capture = join(tmpdir(), `dsh-tracked-files-${process.pid}.txt`);
const fd = openSync(capture, "w");
const listed = spawnSync("git", ["--no-optional-locks", "-C", ROOT, "ls-files", "-z"], { stdio: ["ignore", fd, "ignore"] });
closeSync(fd);
let raw = "";
try {
  raw = readFileSync(capture, "utf8");
} finally {
  rmSync(capture, { force: true });
}
if (listed.status !== 0) {
  console.log("SKIP cannot list tracked files with git (nothing to check).");
  process.exit(0);
}
const files = raw.split("\0").filter(Boolean);
const RULES = [
  { label: "signing material", match: (f) => /(^|\/)[^/]+\.(jks|keystore|p12|pepk)$/i.test(f) },
  { label: "local configuration", match: (f) => /(^|\/)(keystore\.properties|local\.properties)$/.test(f) || /(^|\/)keystore\//.test(f) },
  { label: "build output", match: (f) => /\.(apk|aab|ap_|dex)$/i.test(f) },
  { label: "agent scratch state", match: (f) => /(^|\/)\.zcode\//.test(f) },
  { label: "secret file", match: (f) => /(^|\/)\.env(\.|$)/.test(f) && !/\.(example|sample|template)$/.test(f) },
];
const found = [];
for (const file of files) {
  for (const rule of RULES) {
    if (rule.match(file)) {
      found.push(`${file} (${rule.label})`);
      break;
    }
  }
}

for (const item of found) console.log(`TRACKED ${item}`);
if (found.length > 0) {
  console.error(`\nFAIL ${found.length} file(s) that must stay out of git are tracked. Remove them from the index without deleting the working copy: git rm --cached <path>\nThen add a .gitignore rule so it cannot come back.`);
  process.exit(1);
}
console.log(`PASS ${files.length} tracked file(s); none is signing material, build output, local config or agent scratch state.`);
