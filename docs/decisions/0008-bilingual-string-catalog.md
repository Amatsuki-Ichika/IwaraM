# 0008 — Bilingual copy lives at the call site

Status: accepted

## Problem

The UI was single-language Simplified Chinese with copy written inline in composables, and the
`ui/AGENTS.md` rule said a second language required moving each screen to Android string resources
first, one screen per change. Adding English now would either leave the app half-migrated for
several releases or force a large mechanical migration whose only product is resource ids: the copy
would move from `Text("播放")` to `Text(stringResource(R.string.player_play))` with no change to
what the user sees, while every new string would need two files and a key naming rule.

## Decision

Copy stays at the call site and carries both languages: `tr("播放", "Play")` from
`i18n/Strings.kt`. `AppLanguage` holds the process-level `LanguageMode` (`SYSTEM`, `ZH`, `EN`) as a
Compose state; `tr` reads it, so every composable that uses a string recomposes when the language
changes and the activity is not recreated. `SYSTEM` resolves to Chinese when the system language is
`zh`, English otherwise; the preference is persisted by `IwaraRepository` under `language` and
restored in `IwaraApp.onCreate` before the first frame. Pure Kotlin code (view-model status
messages) uses the same helper. `trCount` covers English plural forms, and `AppLanguage.locale` is
the formatting locale.

## Alternatives considered

**Move every screen to `res/values/strings.xml` and `values-en/`.** The idiomatic Android answer,
and the previous rule. It costs a full mechanical migration before the first English string ships,
adds a key registry to keep consistent, and makes copy harder to review in place; the win —
locale-qualified resources and automatic configuration handling — is not used because the app
offers an in-app language switch independent of the system locale.

**A keyed catalog (`strings_en.json` / a generated object).** Central place to see all copy, but
every screen still has to be edited, indirection hides the text from the reader, and Compose would
not know which keys a screen read without subscribing to the whole catalog.

**A third-party i18n library (e.g. Compose Multiplatform resources).** Brings a build plugin and a
new dependency for a two-language app whose copy is mostly screen-local; against the repository's
"no dependency without a version-catalog entry and a reason" convention for little gain.

## Consequences

Every user-visible string now has an English twin written next to it; a change that touches one
language only is a review defect, and a translator cannot work from a single resource file.
Language switching is immediate and stateless, but it does not change `Locale` for number/date
formatting done outside `AppLanguage.locale` — call sites must format through it. The Chinese
default in `AppLanguage` keeps existing JVM assertions deterministic; tests that exercise English
must set `AppLanguage.set(LanguageMode.EN)` and restore it.
