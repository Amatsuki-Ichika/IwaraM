# Documentation standard

This file governs where documentation lives, how detailed it may be, and what it must contain.
Workflows belong in `AGENTS.md` files; this file owns the rules, not the procedures.

## Tiers

Every document owns one kind of fact. The "Does not belong there" column matters as much as the
job.

| Tier | Job | Does not belong there |
| --- | --- | --- |
| `AGENTS.md` (root and per-module) | Standing orders an agent needs in every session | Tutorials, examples, situational procedures |
| `docs/architecture.md` | Module boundaries, data flow, ownership | Type definitions, field tables (owners own those) |
| `docs/decisions/` | Durable rationale and what each decision beat | Current-state facts, step-by-step procedures |
| `docs/postmortem/` | One incident: evidence, cause, prevention | General rules (move them to the owning module) |
| `CONTRIBUTING.md` | Human process: signing, releasing, pull requests | Agent conventions |
| `README*.md` | What the app is, how to install and build | Internal design detail |
| `CHANGELOG.md` | User-visible changes per release | Internal refactors |
| `app/schemas/*.json` | Generated Room schemas | Hand edits |

Placement: a bug → postmortem; a reason → decision record; a procedure → `AGENTS.md` or
`CONTRIBUTING.md`; a type or field table → the module that owns it; standing orders → root
`AGENTS.md` with a reason link.

## Scope

A document's detail is fixed by its position in the tree, not by how much the author knows. Each
document describes its own subject; it summarizes a child only by purpose and responsibility, then
links down.

- Never restate a child document "briefly": a restatement is a second owner, and the copy rots.
- A reference may be exhaustive only about its own subject.
- Move lower-level detail to its owning page instead of polishing it in the wrong place.

## Prose

- **Document the current state.** History and rejected alternatives belong to a decision record or
  the pull request.
- **One physical line per paragraph.** Use editor soft-wrap so a one-sentence edit produces a
  one-line diff.
- **Name the exact thing.** Before writing `contract`, `boundary` or `shape`, ask whether a more
  exact term exists: write "response fields", "JSON validation", "module exports".
- **Do not narrate code.** No control-flow walkthroughs, no test walkthroughs, no review history.
- **No emphasis inflation.** Bold and "important" everywhere means nothing is emphasized. Reserve
  emphasis for the clause that changes behavior.
- **No reasoning leaks.** A reader at HEAD, with no access to a session transcript or an unmerged
  draft, must be able to resolve every reference and verify every claim.

## Budgets and the ratchet

Standing docs have a word-equivalent ceiling. One unit is one Latin word or two CJK characters.
The numbers live only in [`../scripts/doc-budgets.json`](../scripts/doc-budgets.json); this file
states the policy and never repeats the digits.

- Over budget means one of three repairs: **relocate** the detail to its owner and leave a link;
  **condense** (enumerate the propositions first, then delete only what is redundant); or **raise**
  the ceiling, which the pull request must justify with the old value, the new value and the reason.
- A document at or below its ceiling keeps 5% headroom and may be ratcheted down
  (`node scripts/verify-doc-budgets.mjs --ratchet`). A document above its ceiling has its ceiling
  frozen until it is relocated or condensed.
- A smaller word count is not an improvement by itself. Deletion requires that every factual clause
  survives.

## Slop checklist

1. **Duplicated rules** — search a distinctive phrase, keep one home, link the rest.
2. **History in a current-state page** — "used to", "now changed to" → delete or move to a record.
3. **Implementation-status labels** — "already done", "coming soon", "not yet supported" → status
   rots; point at the owner or delete.
4. **Hand-copied lists** — file inventories, dependency lists, command lists → generate or delete.
5. **Reasoning transcripts** — implementation narration, test walkthroughs, rejected drafts.
6. **Repeated rationale beside sibling entries** — state it once at the parent.
7. **Walls of text** — several paragraphs without headings or lists.
8. **Emphasis inflation** — bold, capitals and "critical" on every line.

## Who enforces what

- Mechanical: word counts, relative links, text hygiene (BOM, line endings, final newline),
  decision-record shape, tracked-file hygiene, APK size. These run in `node scripts/check.mjs`.
- Human review: accuracy, clarity, ownership, whether the detail is at the right level.
- A gate must say what it does not check. A reachable link is not a correct target; a word count is
  not quality; a green status is not a finished thought.
