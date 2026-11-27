# 0043 — Pin release actions and keep the signing key out of the publishing job

Status: accepted

## Problem

The release workflow runs on every `v*` tag and holds the release signing key — the root of trust for in-place updates — in the workspace where third-party actions execute. It referenced those actions by mutable major tags (`actions/checkout@v7`, `softprops/action-gh-release@v3`, and others) and granted `contents: write` at the workflow level. A compromised or retagged action therefore ran with enough privilege to read `keystore/release.jks` and its passwords or to upload a different APK under the project's release; the in-app SHA-256 check would not help, because the checksum asset would come from the same compromised run.

## Decision

Every `uses:` reference names a full 40-character commit SHA, with the human-readable version in a trailing comment; updating an action is a deliberate bump of that SHA. The workflow is split into two jobs. The `build` job holds `contents: read`, restores the keystore, runs the gates, builds and signs the APK, checks its certificate fingerprint and size, and uploads the APK and `.sha256` as an artifact. The `publish` job needs only the build job's version outputs and that artifact, has no secrets, and alone carries `contents: write` for `softprops/action-gh-release`. The tag trigger, version validation, asset names and release-body construction are unchanged.

## Alternatives considered

**Keep major-tag references and only narrow permissions.** Cheaper to maintain, but action code still runs in the job that holds the keystore, so a compromised action can read the key regardless of its token scopes.

**Pin SHAs without splitting the jobs.** Removes the mutable-tag supply-chain path, but keeps the signing key in the same job as the publishing action; a future action added for release notes would widen the exposure again.

**Protected GitHub environment for the signing secrets.** Adds an approval gate for humans, but the environment is not required for agent-driven tag pushes and does not constrain the action code that runs beside the key.

## Consequences

A retagged action can no longer change what the release workflow executes, and the publish surface never sees the signing material. The cost is a manual SHA bump when an action should be updated, and the artifact majors must stay compatible with each other (`upload-artifact` v7 pairs with `download-artifact` v8). Any branch-protection or status-check configuration that named the old `release` job must refer to `build` / `publish` instead.
