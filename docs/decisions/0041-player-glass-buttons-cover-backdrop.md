# 0041 — The player's glass buttons blur the cover and follow the theme

Status: accepted

## Problem

The player's back and overflow buttons were forced to `darkSurface = true`: a dark, translucent
circle with a white icon. On the light theme that reads as a dirty grey circle over a bright video.
With frosted ("liquid") glass selected they stayed translucent instead of blurring, because a glass
button needs a `Backdrop` to sample and the player draws its video into a SurfaceView, which Compose
cannot record. The buttons were also 44 dp and shared the top bar with a debug-only diagnostics
button, so the bar carried two icons on the right.

## Decision

- Round buttons follow the theme: on the light theme they are white with `onSurface` icons, on the
  dark theme dark with light icons. The mockup's always-dark circle is not followed; the user asked
  for theme inversion and it is the behaviour they verified.
- Size drops to 38 dp with 20 dp icons so they sit on the video without competing with it.
- In frosted mode the buttons sample the video's **cover image** through a small `PosterBackdrop`:
  the cover is scaled, centre-cropped into the button layer and blurred by the glass library. The
  cover is loaded by Coil only while frosted mode is active and falls back to the translucent path
  when it is unavailable.
- The debug diagnostics button is removed from the player top bar. The diagnostics sheet is still
  reachable from Settings; the error panel keeps its own copy entry.

## Alternatives considered

- Record the player view with a render effect or `PixelCopy`. A SurfaceView is composited by the
  system outside the RenderNode tree; per-frame captures would cost a GPU readback every frame for
  a 38 dp button.
- Keep the buttons always dark. It is what the mockup shows, but the light theme turns it into a
  grey smudge; the user explicitly rejected it.
- Keep frosted mode translucent on the player and say so in settings. The material would then
  differ between the player and the floating navigation bar on the same screen.
- Use the whole screen behind the button (screen recording). Requires MediaProjection consent and a
  foreground service for a rounded 38 dp blur; far more machinery than the cover.

## Consequences

- The blurred backdrop is the cover, not the current frame. On a video whose cover differs from the
  opening shot, the button can look slightly out of date; blur and the small size hide it.
- A local video with no cover (rare) or a failed Coil load falls back to colour-only translucency.
- The cover is decoded at 360 px and cached by Coil, so the extra cost is one small decode per
  video per process, and only in frosted mode.
- Revisit if Media3/Compose offer a cheap way to sample a SurfaceView per frame.
