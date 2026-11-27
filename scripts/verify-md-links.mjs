#!/usr/bin/env node
// Relative-link gate for Markdown. Reports and never rewrites: a broken link is
// a human decision, and auto-fixing paths would hide the rename that caused it.
// Checks relative targets in inline links and images ([text](target),
// ![alt](target)), reference definitions ([ref]: target; a [text][ref] usage
// resolves through the definition, which is checked where it is written), and
// the href/src attribute of HTML <a> and <img> tags.
// Does not check: external http(s), mailto, tel and data targets, pure
// #fragments, protocol-relative //host URLs, template placeholders (<path>,
// {{file}}), reference usages whose definition is missing, autolinks
// (<https://...>), and targets split across lines. Targets are checked for
// existence, not for being the right page.
// Usage: node scripts/verify-md-links.mjs [--root <dir>]
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { MD_SKIP_DIRS } from "./md-skip-dirs.mjs";

const argRoot = process.argv.indexOf("--root");
const ROOT = resolve(argRoot !== -1 ? process.argv[argRoot + 1] : process.cwd());

function walk(dir, files = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!MD_SKIP_DIRS.has(entry.name)) walk(join(dir, entry.name), files);
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
const DEFINITION = /^\s{0,3}\[[^\]]+\]:\s+(\S+)/;
const HTML_TAG = /<(?:a|img)\b[^>]*?\b(?:href|src)\s*=\s*(?:"([^"]*)"|'([^']*)')/gi;
const broken = [];
let checked = 0;

function checkTarget(raw, file, line) {
  if (/^(https?:|mailto:|tel:|data:|#|\/\/)/i.test(raw)) return;
  // Template placeholders (<path>, {{file}}) are not links to resolve.
  if (/[<>{}]/.test(raw)) return;
  const target = raw.split("#")[0].split("?")[0];
  if (target === "") return;
  const abs = resolve(dirname(file), decodeURIComponent(target));
  checked += 1;
  if (!existsSync(abs)) {
    broken.push({ file: relative(ROOT, file), line, target: raw });
  }
}

for (const file of walk(ROOT)) {
  const text = readFileSync(file, "utf8");
  const lines = stripCode(text.charCodeAt(0) === 0xfeff ? text.slice(1) : text);
  lines.forEach((line, index) => {
    for (const match of line.matchAll(LINK)) checkTarget(match[1], file, index + 1);
    const definition = DEFINITION.exec(line);
    if (definition !== null) checkTarget(definition[1], file, index + 1);
    for (const match of line.matchAll(HTML_TAG)) checkTarget(match[1] ?? match[2], file, index + 1);
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
