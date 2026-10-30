# 0011 — Codec support: decoder fallback and bundled FFmpeg audio decoding

Status: accepted

## Problem

A downloaded mp4 failed to play on a device whose AAC decoder rejected the stream: the file carries AAC Main (`audio/mp4a-latm, mp4a.40.1`), creating `c2.android.aac.decoder` returns `Error 0x80000000`, and playback stopped at that first decoder. The player was built from a bare `ExoPlayer.Builder(context)`, so media3's decoder fallback — off by default — never ran. Video has the mirror problem: when a device has no hardware decoder for a codec, ExoPlayer never tried the AOSP software decoders that the same platform exposes.

## Decision

The player decodes with two layers of support.

`DefaultRenderersFactory(context).setEnableDecoderFallback(true)` makes ExoPlayer disable a decoder that fails to initialise or fails to render a sample and retry the next candidate. The AOSP `c2.android.avc`, `hevc`, `vp8`, `vp9` and `av1` decoders are platform decoders, so video codecs without a hardware decoder now reach software decoding through this path.

`DefaultRenderersFactory(context).setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)` plus the `org.jellyfin.media3:media3-ffmpeg-decoder` artifact supplies `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` and its `libffmpegJNI.so`. It decodes AAC (including Main), AC-3, E-AC-3, DTS, TrueHD, MLP, MP3, FLAC, ALAC and PCM mu-law/A-law in software. Extension mode PREFER selects it for audio ahead of platform decoders, so a stream the device claims to support but then rejects is decoded by FFmpeg; formats it does not handle still fall through to platform decoders.

The artifact's version is pinned in `gradle/libs.versions.toml` as `jellyfinMedia3Ffmpeg = "1.5.0+1"`: the upstream media3 version plus the artifact's build suffix. Media3 itself moves from 1.4.1 to 1.5.0 to match the artifact's compile dependencies, and the two versions must stay in step.

There is no FFmpeg video renderer: upstream media3's `ExperimentalFfmpegVideoRenderer` is a non-functional stub (`supportsFormat` returns `FORMAT_UNSUPPORTED_TYPE`, `createDecoder` returns null), and Jellyfin's build enables audio decoders only. Video software decoding therefore comes from the platform's software decoders through fallback, not from FFmpeg.

IwaraM is AGPL-3.0 and the Jellyfin artifact is distributed under GPL-3.0 for its FFmpeg build; GPLv3 section 13 makes those compatible. `NOTICE.md` lists the artifact and its licence.

## Alternatives considered

**Build the media3 FFmpeg extension from source.** It is the canonical route — NDK, FFmpeg and an enabled-decoder list — but no prebuilt artifact is published: neither Maven Central nor Google Maven serves `media3-decoder-ffmpeg` or `media3-exoplayer-ffmpeg`, so CI would need a native build. Jellyfin already publishes the same library against the same media3 API for all four ABIs; depending on it keeps the repository free of native toolchains.

**LibVLC or a custom FFmpeg video renderer.** Either would software-decode video codecs that no platform decoder handles, such as VP6 or older MPEG-4 Part 2. Both are large changes: LibVLC replaces the playback engine and its surface handling, while a custom `DecoderVideoRenderer` with JNI is a project of its own. The reported failure was an audio codec, so the bounded fix ships first.

**Transcode on import.** Re-encoding every file would make playback uniform, but it costs minutes per file, battery and quality; the request was to play the file, not to rewrite it.

**Expand the scanned file types.** The first attempt admitted more containers and audio files to the local scan. That changes nothing about which codecs decode: an AVI with MPEG-4 Part 2 still fails, and indexing an mp3 does not fix an AAC track. The scan keeps its video-only predicate.

## Consequences

Audio decodes in software when the platform decoder is missing or rejects the stream, at the cost of CPU and battery and about 1.4 MB of native code for arm64-v8a in the APK. Extension mode PREFER means FFmpeg audio decoding is used even where the platform decoder would have worked; the format list it lacks still reaches platform decoders. Video codecs with no platform decoder, hardware or software, still fail, and the error panel offers retry and exit only. Revisit this record when video software decoding becomes a requirement: that needs LibVLC or a functional FFmpeg video renderer, not another dependency bump.
