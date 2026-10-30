# 0009 — First-run onboarding is a skippable guide

Status: accepted

## Problem

A fresh install opens on an empty library whose import entry sits behind an overlay menu, and on settings whose effect is invisible until something has been imported. Nothing explains that IwaraM indexes a folder the user picks, that a download folder is separate and optional, or which content filters are about to apply. A new user who does not find the import entry has no content to judge the app by, and one who does finds the other choices only after hunting through Settings.

## Decision

The first screen of an install that has not completed onboarding is a five-page guide: welcome with the interface language, the required video folder, an optional download folder that follows the video folder unless overridden, the default R18 and card-tag preferences, and a review page ending in "Get started". Next is blocked on the video-folder page until a folder is chosen, Skip is available on every page before the review, and the finished state is persisted as an onboarding-done flag that also gates replay from Settings. `OnboardingScreen` renders only the state and callbacks it is given: it never reads the repository, never persists anything and never launches the system folder picker itself, so the same composable serves first run and replay.

## Alternatives considered

**Empty-state hints only** — a "no videos yet, import here" card inside the existing empty library, with no new screen. Cheapest, but it covers one of five steps and leaves the language, download folder and both preference defaults as undiscoverable as before.

**A forced wizard with no skip** — guarantees every user sets a folder before seeing the library. Rejected because the folder picker can be cancelled or the chosen folder can be empty, so a hard gate either strands the user or needs an escape hatch that is Skip under another name.

**A checklist on the existing Settings screen** — no separate screen, progress shown as checkmarks on settings rows. Reuses the settings layout, but a user with an empty library has no reason to open Settings, and the folder steps are pickers, not rows.

## Consequences

Onboarding costs one persisted flag that the Settings replay entry must clear, and one screen to keep consistent with the settings it previews. It buys a first run that ends with a usable library and known filters. The pickers reuse the caller's existing SAF launchers, so onboarding adds no network, account or login step and stays fully usable offline. The review page is fed by the same state the settings screens read, so a wrong default in the repository surfaces in onboarding too instead of hiding. Revisit if the app can ever start with content that does not need a local folder, at which point the folder step becomes optional rather than blocking.
