# 0028 — Content safety

Status: accepted

## Problem

IwaraM is an unofficial client for iwara.tv, whose catalogue includes adult material, and until this change nothing in the app acknowledged that before content appeared: the five-page first-run guide (see [0009](0009-first-run-onboarding.md)) ended straight in the main UI, the rating filter defaulted to `all` for both the online feed and the local library, and the activity window set no `FLAG_SECURE`, so screenshots, screen recordings and the recent-apps thumbnail exposed whatever was on screen.

## Decision

The final onboarding page requires an "I am 18 or older" checkbox and keeps "Get started" disabled until it is checked; finishing writes `adultConfirmed` through [`IwaraRepository`](../../app/src/main/java/io/github/amatsukiichika/iwaram/data/IwaraRepository.kt), and Skip shows the same notice in a dialog before it completes, so no path reaches the main UI without the declaration.

The declaration is asked once and never again: [`OnboardingScreen`](../../app/src/main/java/io/github/amatsukiichika/iwaram/ui/OnboardingScreen.kt) renders only while `onboardingDone` is false, so installs that already completed onboarding are not prompted on upgrade and keep `adultConfirmed` false; that cost is accepted to avoid interrupting every existing user, and a one-time dialog for upgraded installs remains possible later without a second flag.

The rating defaults stay `all` for both the online filter and the local library: `all` matches the iwara.tv default that users see on the site, `general` would make the first screen look empty to a new user of an adult-content client, and using `general` as the local-library filter would hide videos the user already downloaded; the default R18 switch stays a separate explicit preference instead.

Screenshot protection is an opt-in setting (`screenshotProtection`, default off) exposed as a switch in the General page's Privacy section; while it is on, [`MainActivity`](../../app/src/main/java/io/github/amatsukiichika/iwaram/MainActivity.kt) adds `FLAG_SECURE` to the activity window, so screenshots, recordings and the recent-apps preview are blank for the player and full-screen pages as well.

## Alternatives considered

**Default the rating filter to `general`** — it would keep adult thumbnails out of the first screen, but it contradicts the site's own default, hides most of the catalogue from a new user without explanation, and filters downloaded videos out of the local library; the protection it buys is available as an explicit choice on the onboarding preference page and in Settings.
**Turn screenshot protection on by default** — it would protect every screen without user action, but a user who installs the app to keep clips would find screenshots and recordings silently broken and would have to discover the switch in Settings to fix it; opt-in keeps the default behavior predictable and still protects users who care.
**Make the 18+ declaration its own first page** — it is harder to miss than a checkbox on the review page, but Skip exists on every page and would bypass it, so the gate would still need a skip path, and a sixth page whose only control is a button adds a step without adding enforcement.
**Real age verification** — document scans, payment cards or an iwara account age would be stronger, but the client has no trusted source for them, the site does not expose account age to third-party clients, and collecting identity documents is data the app has no reason to store.
**Server-side content filtering** — iwara.tv already returns a rating per video that the client filters locally; a server-side filter is outside this client's control, does not work offline, and would not change the declaration question.

## Consequences

The 18+ gate is a self-declaration, not age verification: anyone can tick the box, the app checks neither identity nor date of birth, and it must not be presented as a legal compliance measure.
Users who completed onboarding before this change are never asked and stay unrecorded; the flags live in preferences (`adult_confirmed`, `screenshot_protection`) and a future content gate can read `adultConfirmed` without a second persistence path.
`FLAG_SECURE` also blocks screenshots of debug or diagnostic overlays shown while it is on, and it does not protect content rendered by an external player or captured outside this window; these are accepted limits of a window flag.
Revisit the `all` default if a store policy or a maintainer decision requires a stricter one, and revisit the declaration if a trusted verification source becomes available.
