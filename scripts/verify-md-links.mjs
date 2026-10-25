#!/usr/bin/env node
// Relative-markdown-link gate. Reports and never rewrites: a broken link is a
// human decision, and auto-fixing paths would hide the rename that caused it.
// Usage: node scripts/verify-md-links.mjs [--root <dir>]
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";

const SKIP_DIRS = new Set([".git", "node_modules", "dist", "build", "out", "coverage", ".venv", "__pycache__", ".artifacts"]);
const argRoot = process.argv.indexOf("--root");
const ROOT = resolve(argRoot !== -1 ? process.argv[argRoot + 1] : process.cwd());

function walk(dir, files = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(join(dir, entry.name), files);
    } else if (entry.isFile() && /\.md$/i.test(entry.name)) {
      files.push(join(dir, entry.name));
    }
  }
  return files;
}

function stripCode(text) {
  let inFence = false;
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) { inFence = !inFence; out.push(""); continue; }
    out.push(inFence ? "" : line.replace(/`[^`]*`/g, ""));
  }
  return out;
}

const LINK = /!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
const broken = [];
let checked = 0;

for (const file of walk(ROOT)) {
  const lines = stripCode(readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
  lines.forEach((line, index) => {
    for (const match of line.matchAll(LINK)) {
      const raw = match[1];
      if (/^(https?:|mailto:|tel:|data:|#|\/\/)/i.test(raw)) continue;
      // Template placeholders (<path>, {{file}}) are not links to resolve.
      if (/[<>{}]/.test(raw)) continue;
      const target = raw.split("#")[0].split("?")[0];
      if (target === "") continue;
      const abs = resolve(dirname(file), decodeURIComponent(target));
      checked += 1;
      if (!existsSync(abs)) {
        broken.push({ file: relative(ROOT, file), line: index + 1, target: raw });
      }
    }
  });
}

for (const item of broken) {
  console.log(`BROKEN ${item.file}:${item.line} -> ${item.target}`);
}
if (broken.length > 0) {
  console.error(`\nFAIL ${broken.length} broken relative link(s) in ${checked} checked; fix the path or restore the target (report only, no rewrite).`);
  process.exit(1);
}
console.log(`PASS ${checked} relative link(s) resolve.`);
