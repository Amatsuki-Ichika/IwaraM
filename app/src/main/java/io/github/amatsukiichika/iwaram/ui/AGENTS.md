# UI layer rules

Applies to `app/src/main/java/io/github/amatsukiichika/iwaram/ui/`. Standing orders and the
definition of done are in the root AGENTS.md; this file only adds what is specific to this directory.

## Composition

- No DI framework, no Navigation library, no MVI. Screens compose directly; navigation is the
  hand-written overlay stack in `MainActivity`. Do not "modernize" this without a decision record.
- A screen takes state and callbacks; it does not reach into a repository or a service.
- The overlay stack is the single navigation authority: pushing the same destination twice, and
  popping the root, are both bugs.

## Theme and visual tokens

- Read colors, typography and shapes from `MaterialTheme`. A hardcoded `Color(0x...)` outside the
  theme or the glass-material helpers is a defect, not a preference.
- Respect dark and light mode: every new surface needs a token that resolves in both.
- Reuse the existing glass helpers rather than adding a parallel implementation of blur, tint or
  border. Three near-identical glass composables is how visual drift starts.

## Player

- Switching a super-resolution model rebuilds the player instance by design. Do not "optimize" it
  into reconfiguring a live effect pipeline without evidence that it works.
- Player state (position, scale, orientation) survives configuration changes; a change that resets
  playback position is a regression, even if it compiles.

## Text and accessibility

- User-facing strings live in string resources, not in composables. Keep sentence case.
- Icon-only buttons need a content description; decorative images need none.
- Kotlin comments are Simplified Chinese, matching the UI language; do not translate existing ones.

## Formatting

- Do not reformat unrelated code, reorder imports project-wide, or "clean up" white space in a
  change that does something else. Review noise hides the real diff.
