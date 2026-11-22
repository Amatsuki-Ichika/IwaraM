# 0041 — The player's glass buttons sample the live video frame and follow the theme

Status: accepted

## Problem

The player's back and overflow buttons were forced to `darkSurface = true`: a dark, translucent
circle with a white icon. On the light theme that reads as a dirty grey circle over a bright video.
With frosted ("liquid") glass selected they stayed translucent instead of blurring, because a glass
button needs a `Backdrop` to sample and the player draws its video into a SurfaceView, which Compose
cannot record. The first version solved that by sampling the video's **cover image**, which the user
rejected: the cover is a still, so on most videos the blur does not follow the frame and the buttons
look pasted on rather than part of the same material system as the floating navigation bar.

## Decision

- Round buttons follow the theme: on the light theme they are white with `onSurface` icons, on the
  dark theme dark with light icons.
- Size drops to 38 dp with 20 dp icons so they sit on the video without competing with it.
- In frosted mode the buttons use the **same recipe as the floating navigation bar**
  (`GlassRoundButton(barRecipe = true)`): the BAR glass role and `glassBarEffects` with the
  material's blur radius. Fill opacity, blur radius and theme inversion therefore track the global
  glass settings instead of a second, hand-tuned look.
- The backdrop is the **live video frame**. `rememberPlayerLiveBackdrop` asks Media3's
  `PlayerView.videoSurfaceView` for its SurfaceView and copies the frame with
  `PixelCopy.request` into an ARGB bitmap whose longest side is 360 px, at about 10 fps
  (`CAPTURE_INTERVAL_MS = 100`). PixelCopy scales the source into the destination bitmap, so the
  downscale costs nothing extra. Capturing runs only while the controls are visible; hiding them
  stops the loop and drops the frame.
- `PlayerLiveBackdrop` is coordinates-dependent: when the glass library records a button layer it
  hands over the button's window bounds, and `playerBackdropDrawPlan` (pure, unit-tested) maps the
  button (plus the library's recording padding) onto the right rectangle of the captured frame. A
  button that hangs over a black bar only samples the part that overlaps the video.
- When no frame is available (translucent or glass off, API < 31, surface not attached, capture
  failed) the button falls back to its translucent fill; nothing crashes and no placeholder image is
  drawn.

## Alternatives considered

- Keep the cover image. It is what the previous version did; the user explicitly asked for live
  sampling instead.
- Sample the current frame through Media3's `VideoFrameMetadataListener`. It reports timing, not
  pixels; there is no supported way to obtain the decoded frame from Media3.
- Switch the player to a TextureView so Compose can record it. That changes the render path for the
  whole player (power, HDR, secure surfaces) to satisfy two 38 dp buttons.
- Record the screen with MediaProjection. It needs a consent prompt and a foreground service for a
  rounded blur.
- Blur nothing (keep translucency) on the player. The material would differ from the navigation bar
  on the same screen, which is the problem being fixed.

## Consequences

- While the controls are visible the app performs one GPU readback of a 360 px frame per 100 ms and
  keeps one small bitmap (~0.5 MB). When the controls hide, both stop.
- The frame lags the video by up to one capture interval. Blur and the button size hide it.
- A SurfaceView is required; if the player is ever moved to TextureView or a secure surface,
  `PixelCopy` fails and the buttons fall back to colour-only translucency.
- The capture loop is best-effort: every exception ends in `null` rather than an error state, so a
  broken capture degrades the look, never playback.
- Revisit if Media3 exposes decoded frames to callers, or if the readback shows up in battery
  measurements on low-end devices.
