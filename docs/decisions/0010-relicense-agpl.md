# 0010 — Relicense the project under AGPL-3.0

Status: accepted

## Problem

IwaraM ships under the MIT license, which lets anyone distribute a modified client as closed source and offers nothing back to the project. Because the app talks to a third-party service and its network behavior can be modified, a closed fork can also present itself to users without giving them a way to inspect or rebuild what they run.

## Decision

The repository moves to the GNU Affero General Public License v3.0. `LICENSE` contains the verbatim AGPL-3.0 text, the READMEs, `CONTRIBUTING.md` and `NOTICE.md` name it, and new contributions are accepted under its terms. The move is forward-only: releases up to and including 0.2.1 were published under MIT and keep that permission, so their recipients may continue to use and redistribute those releases as MIT allows. Third-party components keep their own licenses.

## Alternatives considered

- Stay MIT: simplest for downstream users, but keeps the closed-fork gap that motivates this change.
- GPL-3.0: same source-sharing duty for distributed builds, but no duty when a modified build is only offered over a network, which is the case this project cares about.
- Apache-2.0: adds a patent grant but keeps permissive redistribution, so it does not solve the problem.
- Dual licensing or a contributor license agreement: more legal overhead than a single-license community project needs.
- AGPL-3.0 with an app-store exception: no store distribution exists today, so the exception solves a problem the project does not have.

## Consequences

Closed-source forks are no longer permitted for new code, which may deter some commercial or privacy-minded integrators; that is accepted. Anyone who hosts a modified build must offer its corresponding source to network users. Every new dependency must now be checked for AGPL-3.0 compatibility, so a library under an incompatible license blocks adoption. The MIT grants for releases up to 0.2.1 stay irrevocable, and users of those releases are unaffected.
