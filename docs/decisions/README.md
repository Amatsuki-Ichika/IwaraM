# Decision records

A decision record answers one question: **why does the code do this, and what else was considered?**
It is not a changelog, not a plan and not a knowledge base. If code, tests and documentation
already explain it, no record is needed.

## Format

One file per decision: `docs/decisions/NNNN-kebab-slug.md`, numbered in order, never renumbered.

```markdown
# NNNN — Short title

Status: accepted

## Problem

## Decision

## Alternatives considered

## Consequences
```

- `Status` is one of `proposed`, `accepted`, `superseded`, `deprecated`. A superseded record also
  carries `Superseded by: NNNN`.
- **`Alternatives considered` is mandatory.** A decision recorded without what it beat invites the
  same discussion next month. Record what was genuinely considered, never invent a straw man.
- `Consequences` states what was paid and what was bought: abandoned ability, new maintenance
  cost, the condition under which the idea should be revisited.
- Write the current state, in the present tense. Do not append a change history; supersede instead.

## When to write one

- The choice is not obvious from the code, and a reasonable person would ask "why not the other
  way?" within a release cycle.
- It changes something users cannot undo: Room schema, application ID, signing key, file layout,
  persisted settings.
- It deliberately accepts a known cost (an empty catch, a duplicated constant, a manual release
  step) that a future reader would otherwise "fix".

## When not to write one

- Mechanical changes, refactors with no behavior change, dependency bumps, comment edits.
- Local agent scratch plans. Those belong in a git-ignored tool directory, not in `docs/`.
- Anything already owned by an existing record: update that record instead of adding a second one.

## Review

New prose in a record is reviewed like any other prose: accuracy, placement, and whether the
alternatives are real. The mechanical gate checks the shape and status, not the thinking.
