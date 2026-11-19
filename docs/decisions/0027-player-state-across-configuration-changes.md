# 0027 — Keep the player alive across font-scale and density changes

Status: accepted

## Problem

The player page keeps its playback state in composition memory: the ExoPlayer instance, the position and duration rendered by the progress bar, the fullscreen flag and the requested orientation. MainActivity declares `orientation|screenSize|screenLayout|keyboardHidden|uiMode` and not the font-scale or density changes, so changing the system font size or display size recreates the activity and discards that state. Playback then restarts from the history resume point while the player scale and the fullscreen lock reset, contradicting the rule in ui/AGENTS.md that player position, scale and orientation survive configuration changes.

## Decision

MainActivity declares `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|fontScale|density`; the existing set is kept and font-scale, density and smallest-screen-size changes are added. Compose applies a configuration change to its own view without recreating the activity: AndroidComposeView owns configuration and density state, updates it from onConfigurationChanged and recomposes the affected content, so the player instance and the page state survive a font-size or display-size change. smallestScreenSize is declared together with density because the platform derives dp-based screen size from display density, so a density change can also be reported as a smallest-width change; leaving that undeclared would keep an activity restart on exactly the setting this decision covers.

The set stays limited to changes the app absorbs in place. locale is deliberately not declared: the in-app language is process state (AppLanguage.mode, see ADR-0008) and the UI reads it through tr() at composition time, while the system mode resolves Locale.getDefault() at read time and does not observe it as state, so swallowing the locale change would leave the UI in the previous language until the process restarts. Keyboard, navigation, touchscreen and the remaining changes are not declared because no screen holds state that depends on them and Compose already applies them in place.

## Alternatives considered

Keep the manifest unchanged and save the position, fullscreen flag and orientation into saved instance state for every recreation. That adds a second state-restoration path next to prepareRebuild, which already rebuilds ExoPlayer for media and effect changes, and two paths that both claim the current position can disagree while the player pipeline is still torn down on a font-size change.

Declare every configuration change, including locale, so that the activity never restarts. Locale is the one event the UI cannot observe by itself, so this would trade an activity restart for stale strings, and it would also swallow keyboard and navigation events that no code handles.

Declare only fontScale. Density changes would still recreate the activity and lose the same player state, so the reported regression would remain for display-size changes.

## Consequences

Font-size and display-size changes now update the UI in place; the player keeps its instance, position, scale and orientation, and other screens stop recreating along with it. Code that relied on onCreate re-running after a font-scale or density change must read configuration through Compose or resources instead. A system language change still recreates the activity on purpose, which refreshes the system language mode. The declared set is wider than the player page alone needs, so a future change that relies on activity recreation for any declared change must revisit this record.
