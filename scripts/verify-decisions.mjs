#!/usr/bin/env node
// Decision records (ADR-lite). A decision without what it beat invites the same
// discussion again, so "Alternatives considered" is mandatory, not encouraged.
// Checks the shape only: four-digit filename numbers, an H1 naming the same
// number, the Status vocabulary, the required sections, and that every
// "Superseded by: NNNN" names a record in this directory. Does not judge the
// prose, the alternatives or whether the decision was right.
// Usage: node scripts/verify-decisions.mjs [--root <dir>]
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const argRoot = process.argv.indexOf("--root");
const ROOT = resolve(argRoot !== -1 ? process.argv[argRoot + 1] : process.cwd());
const DIR = resolve(ROOT, "docs/decisions");
const STATUSES = new Set(["proposed", "accepted", "superseded", "deprecated"]);
const REQUIRED = ["## Problem", "## Decision", "## Alternatives considered", "## Consequences"];
const issues = [];
const numbers = new Map();
const supersessions = [];
let checked = 0;

if (!existsSync(DIR)) {
  console.log("PASS no docs/decisions directory (nothing to check).");
  process.exit(0);
}

for (const name of readdirSync(DIR).sort()) {
  if (!name.endsWith(".md") || name === "README.md" || name === "TEMPLATE.md") continue;
  const file = join(DIR, name);
  const rel = relative(ROOT, file).split(/[\\/]/).join("/");
  const match = /^(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$/.exec(name);
  if (match === null) {
    issues.push(`${rel}: filename must be NNNN-kebab-slug.md with a four-digit number (e.g. 0007-use-room-schema-export.md)`);
    continue;
  }
  const number = match[1];
  if (numbers.has(number)) {
    issues.push(`${rel}: decision number ${number} already used by ${numbers.get(number)}`);
  } else {
    numbers.set(number, rel);
  }

  const text = readFileSync(file, "utf8").replace(/^\uFEFF/, "");
  const title = /^#\s+(.+?)\s*$/m.exec(text);
  if (title === null) {
    issues.push(`${rel}: missing "# NNNN \u2014 title" heading`);
  } else {
    const heading = /^(\d{4})\s+\u2014\s+\S/.exec(title[1]);
    if (heading === null) {
      issues.push(`${rel}: H1 must read "# NNNN \u2014 title" with a four-digit number`);
    } else if (heading[1] !== number) {
      issues.push(`${rel}: H1 number ${heading[1]} does not match filename number ${number}`);
    }
  }
  const statusLine = /^Status:\s+(\S+)\s*$/m.exec(text);
  if (statusLine === null) {
    issues.push(`${rel}: missing "Status:" line (one of proposed, accepted, superseded, deprecated)`);
  } else if (!STATUSES.has(statusLine[1])) {
    issues.push(`${rel}: Status "${statusLine[1]}" is not one of proposed, accepted, superseded, deprecated`);
  }
  for (const heading of REQUIRED) {
    if (!new RegExp(`^${heading}\\s*$`, "m").test(text)) {
      issues.push(`${rel}: missing section "${heading}"`);
    }
  }
  if (statusLine !== null && statusLine[1] === "superseded") {
    const supersededBy = /^Superseded by:\s+(\d{4})\s*$/m.exec(text);
    if (supersededBy === null) {
      issues.push(`${rel}: a superseded decision needs "Superseded by: NNNN"`);
    } else {
      supersessions.push({ rel, target: supersededBy[1] });
    }
  }
  checked += 1;
}

for (const { rel, target } of supersessions) {
  if (!numbers.has(target)) {
    issues.push(`${rel}: "Superseded by: ${target}" names no decision record in docs/decisions`);
  }
}

for (const issue of issues) console.log(`INVALID ${issue}`);
if (issues.length > 0) {
  console.error(`\nFAIL ${issues.length} problem(s) in ${checked + issues.length} decision record(s). Records are numbered ADRs: filename and H1 share the number, Status is the state.`);
  process.exit(1);
}
console.log(`PASS ${checked} decision record(s) follow the ADR-lite format.`);
