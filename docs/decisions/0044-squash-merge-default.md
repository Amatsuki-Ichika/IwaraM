# 0044 — Squash merges are the default

Status: accepted

## Problem

The repository required a merge commit on `main` and forbade squash merges. The rule was added after one squash-merged pull request had to be reverted; that incident's cause was merging without human review, not the squash itself. With a single maintainer working mostly through agents, a pull request's internal commits are usually review fixups: a merge commit preserves every fixup plus a "Merge pull request" node, so the history is noisier and reverting one change means reverting several commits.

## Decision

Squash-merge is the default: one pull request becomes one commit on `main` whose subject is the pull request title (Conventional Commits) and whose body is the pull request description. A merge commit is used only when the pull request's commit sequence itself has value, for example a multi-step change whose commits are reviewed individually. Rebase merges are never used. The protection stays: `build` must pass and a human must approve before any merge, and merging plus deleting the branch remain manual maintainer steps.

## Alternatives considered

**Keep merge-commits-only.** Rejected: it does not address the actual risk, an unreviewed merge, and the extra commits obscure a linear history that is easier to bisect and revert.

**Squash every pull request, including deliberate sequences.** Rejected: a change reviewed commit by commit loses those units on `main`.

**Rebase-merge.** Rejected: it rewrites the pull request's commits and leaves no single integration point to revert, and it encourages maintaining a rebased branch.

## Consequences

`main` gains one commit per pull request. The pull request title becomes a permanent commit message, so it must stay Conventional-Commits formatted; the detailed rationale lives in the pull request description, the CHANGELOG and decision records where applicable. Reverting a change is a single `git revert` of the squashed commit. A pull request that genuinely needs its stepwise history must say so at merge time and use a merge commit.
