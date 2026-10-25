#!/usr/bin/env node
// Markdown hygiene: the rules .editorconfig claims, but nothing enforces.
// Checks UTF-8 without BOM, LF line endings, and a final newline.
// Usage: node scripts/verify-md-hygiene.mjs [--root <dir>]
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const SKIP_DIRS = new Set([".git", ".gradle", ".kotlin", ".idea", "build", "node_modules", ".zcode", "dist", "out"]);
const argRoot = process.argv.indexOf("--root");
const ROOT = resolve(argRoot !== -1 ? process.argv[argRoot + 1] : process.cwd());
const issues = [];
let checked = 0;

function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(join(dir, entry.name));
    } else if (entry.isFile() && /\.md$/i.test(entry.name)) {
      const file = join(dir, entry.name);
      const rel = relative(ROOT, file).split(/[\\/]/).join("/");
      const bytes = readFileSync(file);
      const text = bytes.toString("utf8");
      checked += 1;
      if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
        issues.push(`${rel}: UTF-8 BOM; save as UTF-8 without BOM`);
      }
      if (text.replace(/^\uFEFF/, "").includes("\r\n")) {
        issues.push(`${rel}: CRLF line endings; .editorconfig requires LF`);
      }
      if (text.length > 0 && !text.endsWith("\n")) {
        issues.push(`${rel}: no final newline; .editorconfig requires insert_final_newline`);
      }
    }
  }
}

walk(ROOT);
for (const issue of issues) console.log(`INVALID ${issue}`);
if (issues.length > 0) {
  console.error(`\nFAIL ${issues.length} hygiene problem(s) in ${checked} Markdown file(s); these are the .editorconfig rules, applied to docs.`);
  process.exit(1);
}
console.log(`PASS ${checked} Markdown file(s) follow the text hygiene rules.`);
