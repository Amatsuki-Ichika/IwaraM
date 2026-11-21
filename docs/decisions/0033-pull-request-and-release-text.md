# 0033 — Pull request and release text

Status: accepted

## Problem

The pull request template asks for 451 words across seven sections, including a five-stage AI
usage table and a seven-item checklist that repeats the definition of done in AGENTS.md. Real
pull request bodies run 83–89 lines. The release body appends every non-merge commit subject to
the CHANGELOG section, so a release page repeats its own changes and mixes in `chore:`, `docs:`
and `Merge pull request` lines. CHANGELOG entries under [Unreleased] average 46 words and reach
105, and explain implementation rather than what a user sees.

## Decision

The pull request template keeps five sections: Summary, Changes, Testing, AI usage and Notes. AI
usage is one line naming the level and who made the decisions; the checklist is gone, and a hidden
link points at the definition of done in AGENTS.md. The release body is the version's CHANGELOG
section (the [Unreleased] section for an RC), the APK name and `.sha256`, a compare link to the
previous stable tag, and one line for a pre-release; it does not list commits. CHANGELOG entries
are one sentence of at most 35 words, state the user-visible result, and keep settings paths,
defaults and limits. A stable section opens with one sentence that becomes the release body's
introduction.

## Alternatives considered

**Keep the five-stage AI table.** It answers a question reviewers ask once, and one line carries
the same signal.

**Keep the commit subjects and filter them to `feat` and `fix`.** This still duplicates the
CHANGELOG and needs a curation rule for every prefix, including the merge commits the repository
keeps.

**Keep the long entries and rely on the release page.** The page is the first thing a user reads,
and a long list pushes the download link and the compare link off the screen.

**Add a mechanical word-count gate.** A count cannot tell whether a sentence keeps the
user-visible fact, and the repository avoids gates that reward cutting facts.

## Consequences

Per-commit attribution is gone; the compare link and the git history cover it. Implementation
detail moves to the pull request and the decision records, so a reviewer has to ask for evidence
the template no longer prompts for. The CHANGELOG change touches only [Unreleased]; released
sections and the link definitions stay as they are. Future entries need the same discipline,
enforced by review rather than a gate.
