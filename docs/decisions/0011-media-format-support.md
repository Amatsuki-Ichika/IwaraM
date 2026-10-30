# 0011 — Media format support

Status: accepted

## Problem

A downloaded mp4 failed to play on a device whose AAC decoder rejected the stream: the file carries AAC Main (`audio/mp4a-latm, mp4a.40.1`), creating `c2.android.aac.decoder` returns `Error 0x80000000`, and playback stops on that first decoder instead of trying the next one. The player was built from a bare `ExoPlayer.Builder(context)`, so media3's decoder fallback — off by default — never ran, and the error panel offered only retry and exit.

Separately, the local library scan accepted five video containers only (`.mp4`, `.webm`, `.mkv`, `.mov`, `.m4v`), so downloaded audio, MPEG-TS, FLV, Ogg and AVI files never entered the library even though media3 demuxes and decodes them in-process; an audio-only file that did play showed a plain black video area, indistinguishable from a hung player.

## Decision

The player is built with `DefaultRenderersFactory(context).setEnableDecoderFallback(true)`: when a decoder fails to initialize or fails to render a sample, ExoPlayer disables that decoder for the session and retries the next candidate, which covers the AAC Main failure without a bundled codec. `DefaultMediaSourceFactory` keeps the `OkHttpDataSource` wiring unchanged and the super-resolution epoch rebuild still owns player instances.

`data/MediaFormats.kt` is the single list of formats the app admits in-process: video `mp4 m4v mov mkv webm ts m2ts mts flv ogv avi 3gp 3g2` and audio `mp3 m4a m4b aac flac ogg oga opus wav amr mka`, matched case-insensitively by final extension. It exposes `isSupportedMediaFile` and `mimeTypeFor`; the local scan uses it in place of its private `isVideo` predicate, so scan and import admit audio as well as video. Downloads still produce mp4, so `DownloadTarget` and `VideoQuality` keep their names and behaviour.

Audio-only playback is an explicit state: the player watches `onTracksChanged` and, once a non-empty track list reports no video track, the video area shows a centered music icon and the title instead of a black rectangle. The state resets on source change, so an audio file is never mistaken for a stuck video.

The playback error panel gains an `Open in another app` action whenever a media URI exists: it starts `ACTION_VIEW` through `Intent.createChooser` with `FLAG_GRANT_READ_URI_PERMISSION` for the local `content://` URI (or the http(s) URL), and a `runCatching` around `startActivity` reports with a toast when no app handles the file. This is the escape hatch for a genuinely undecodable stream, not a replacement for decoding.

## Alternatives considered

**Bundle the media3 FFmpeg extension.** It decodes the widest range of codecs, including AAC variants this device rejects, but it is a native library: the CI job would need an NDK build and the APK would grow by several megabytes for a failure mode that decoder fallback plus an installed player already covers.

**Transcode on import.** Re-encoding every file to a baseline profile would make playback uniform, but it burns CPU and battery, degrades quality, takes minutes per file and is a project of its own; the request was to play the file, not to rewrite it.

**Add the HLS, DASH and RTSP modules.** media3 ships them as separate artifacts, but every iwara playback URL in the app is a progressive file, so the modules would add dependencies with no source to feed them.

**Keep the error panel at retry and exit.** The smallest change, but it leaves an undecodable file on disk with no way to play it, while the system almost always has a player that can.

## Consequences

The decoder fallback is transparent: playback continues when the first decoder fails, at the cost of a session-scoped disable list and possibly software decoding with higher CPU use. The accepted format list is a deliberate ceiling: media3 can demux more containers than the app lists, and each admission implies codec expectations the app cannot verify on every device; formats inside the list can still fail to decode, in which case the error panel offers the external player. Revisit this record when a bundled codec, import-time transcoding or a streaming protocol becomes a real requirement.
