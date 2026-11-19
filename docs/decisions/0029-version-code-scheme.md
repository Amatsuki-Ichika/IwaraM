# 0029 — Collision-free versionCode scheme

Status: accepted

## Problem

[`appVersionCode`](../../app/build.gradle.kts) derives `versionCode` from the same `app.versionName` in [`gradle.properties`](../../gradle.properties) that provides `versionName`, so the two cannot drift, but the arithmetic `major * 10000 + minor * 100 + patch` lets a minor or patch value roll over into the next field: versions 0.2.100 and 0.3.0 both produce 300, and no build or release gate inspects the derived number, so two different releases can claim the same code. Android refuses a build whose versionCode is not greater than the installed one, so the second of the two releases silently fails to upgrade over the first and the collision is only detectable after installation.
Android also caps versionCode at 2,100,000,000, which the old arithmetic reaches in the major field without any check, and the conversion overflows to a negative Int for large enough inputs.

## Decision

`versionCode` is `major * 1_000_000 + minor * 1_000 + patch`, so 0.2.3-rc.1 maps to 2_003 and the fields no longer overlap while minor and patch stay below 1000.
Configuration fails with an explicit message when a part leaves its supported range (`major` in 0..2099, `minor` and `patch` in 0..999), naming the offending part, the formula and Android's ceiling, so a carry collision or an overflow becomes a build failure instead of a duplicate or negative code.
The pre-release suffix stays ignored, so an RC and its stable release share one versionCode and an RC APK upgrades in place to the stable APK; the `-Papp.versionName` override and the `gradle.properties` read are unchanged.
For every version the new arithmetic yields a code at least as large as the old one (the difference is `major * 990_000 + minor * 900`) and it never decreases between releases, so existing installs upgrade in place.

## Alternatives considered

**Keep `major * 10000 + minor * 100 + patch` and discipline the version numbers** — attractive because no code changes and no install interruption is involved, but the collision is silent, depends on every future release author remembering that minor and patch must stay below 100, and is already reachable by two plausible versions near 0.2.100; a comment cannot enforce it at build time.
**Encode the pre-release counter into versionCode** — it would give every RC a distinct code, but the project relies on an RC tester upgrading to the matching stable APK in place, which requires the stable build to keep the same versionCode; rejected, so the suffix stays outside the number.
**Move versionCode to its own `gradle.properties` key** — it makes the formula trivial and editable per release, but reintroduces a second version source that can drift from `versionName`, the exact class of bug the derived scheme prevents; rejected.
**Extract the arithmetic into a JVM-tested helper in `buildSrc`** — it would let a unit test pin the mapping, but the value is consumed during Gradle configuration of the app module, so the real path is still only exercised by configuring the build; a build-logic module adds a second compilation and a second home for the scheme without covering that path. The range check makes a bad name a deterministic configuration failure instead, and the mapping is verified through the versionCode recorded in the packaged APK.

## Consequences

`minor` and `patch` are limited to three digits and `major` to 0..2099; a version outside those ranges fails configuration with a message that names the offending part, the formula and Android's ceiling, instead of producing a duplicate or negative code.
The current release moves `versionCode` from 203 to 2_003 and later minors grow it further, so any future store migration must preserve the values already installed; Android accepts the full range and the configured cap keeps the arithmetic below its ceiling.
The scheme is a monotonic per-application-id contract that cannot be lowered for installed users, so a future change needs a new decision record and may need a staged migration; ignoring the pre-release suffix is what keeps in-place RC-to-stable upgrades working and must not be "fixed" by folding it back into the number.
The mapping has no JVM unit test by construction: it is proven by a successful configuration and by the versionCode in the built APK metadata, and a bad range is proven by the explicit configuration failure.
