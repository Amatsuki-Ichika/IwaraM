# 0002 — Encrypt stored session tokens with an AndroidKeyStore key

Status: accepted

## Problem

Iwara issues a refresh token valid for roughly three to four weeks and an access token valid for about an hour; both were persisted in the private `prefs` SharedPreferences file so a restart would not force a login every time.
`android:allowBackup="false"` closes cloud backup and `adb backup`, and the manifest comment records that reasoning, but it does not close root access or a same-user read of the data directory; the long-lived refresh token therefore sat on disk in plaintext, and stealing it keeps an account open for a month.
The same file also held the access token a user pastes manually, which had the same exposure; that second entry point was later removed, leaving one token owner (see [ADR 0026](0026-session-token-storage-policy.md)).
Backup flags were the whole mitigation, and they are not sufficient once the threat is a copied data directory rather than a restored one.

## Decision

[`TokenCrypto.kt`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/TokenCrypto.kt) encrypts token values with AES-256-GCM under a non-exportable AndroidKeyStore key (`iwara_session_token`, 256-bit, generated on first use) before they reach SharedPreferences.
The stored form is `v1:` followed by Base64 of the 12-byte GCM IV concatenated with the ciphertext and the 128-bit tag; the prefix is the version marker a later algorithm or key change migrates from.
Legacy plaintext values pass through decryption unchanged on read, and [`IwaraSession.kt`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraSession.kt) rewrites each one to ciphertext during `restore`; the manually pasted token path that once went through `IwaraRepository` was removed (see [ADR 0026](0026-session-token-storage-policy.md)).
Two failure paths are deliberate: if AndroidKeyStore is unavailable (a broken ROM, not the normal case) writes degrade to an explicit `plain:` value with a persisted degraded marker, a diagnostic log line and a settings notice (see [ADR 0026](0026-session-token-storage-policy.md)), and if a `v1:` value cannot be decrypted the read returns null and the caller treats the user as logged out.
There is no plaintext fallback for an unreadable key: losing the key costs a login, never the encryption guarantee.

## Alternatives considered

**Keep plaintext and rely on `allowBackup="false"`** — this was the shipped 0.1.0 state, and its stated reason was that backup could not restore SAF folder grants across devices anyway. It is rejected because backup is only one copy channel: a rooted device or a same-user file read never consults the backup flag, so the token stays readable for as long as the data directory does.

**Fail closed when AndroidKeyStore is unavailable** — refuse to persist, or treat the user as logged out, instead of writing plaintext. The comment in `TokenCrypto.kt` rejects this directly: a security feature must not break login, so the degraded path deliberately reproduces the old behavior. The cost is that on a broken Keystore the old exposure returns, but it is now an explicit `plain:` value, persisted as a degraded marker and shown in Settings, never a silent bare-plaintext fallback (see [ADR 0026](0026-session-token-storage-policy.md)).

## Consequences

A copied app data directory no longer yields usable tokens, which is what the change buys; the key cannot leave the Keystore, so no password or PIN handling is added.
The hand-rolled cipher keeps an explicit plaintext-degradation path alive; it must not be "cleaned up" by deleting the fallback, because that would turn a Keystore fault into a login outage.
The `v1:` prefix is the only upgrade lever: a future key or algorithm change adds a prefix and a migration, and ignoring it would strand every stored session.
Robolectric has no AndroidKeyStore, so the unit test injects a software key through `TokenCrypto.KeyProvider`; that injection point exists for tests and stays internal.
Revisit if the degrade path starts appearing in diagnostic logs, or if a platform or library API makes the hand-rolled cipher redundant.
