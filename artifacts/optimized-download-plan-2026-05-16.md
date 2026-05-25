# AFinity Optimized Jellyfin Downloads Plan

Date: 2026-05-16
Target device: HUAWEI MatePad Pro 12.2 2025 / MRDI-W09, 2800x1840 display, Android SDK 31 / HarmonyOS 4.3 surface.
Target server: Jellyfin 10.11.8 at `http://192.168.0.200:8096`.

## Goal

Add an optional Jellyfin video download mode that stores smaller offline files on the tablet without visibly losing quality on the tablet screen.

The client should avoid storing unnecessary 4K originals and should prefer server-side transcode before download, so the tablet receives the final optimized file directly.

## Current Behavior

`MediaDownloadWorker` currently calls:

```kotlin
apiClient.libraryApi.getDownloadUrl(itemId = itemId)
```

That downloads the original media file. It does not request server-side resolution limiting or codec conversion.

## Proposed Behavior

Add a download quality mode:

- `Original`: current behavior, download the original file.
- `Tablet optimized`: cap to the tablet display envelope and prefer HEVC.
- `Storage saver`: stricter bitrate/resolution cap for maximum offline capacity.

Initial recommended mode for this tablet:

- Container: `mp4` where possible.
- Video codec: `hevc`.
- Max resolution: `maxWidth=2800`, `maxHeight=1840`.
- Audio: copy if compatible; otherwise AAC fallback.
- Existing HEVC at or below target resolution: allow stream copy.
- Non-HEVC, 4K, or oversized sources: force video transcode.

## Jellyfin Endpoint

Use Jellyfin's existing video stream endpoint, not a server patch:

```text
/Videos/{itemId}/stream.{container}
```

Relevant query parameters from Jellyfin 10.11.8 OpenAPI:

```text
MediaSourceId={sourceId}
static=false
container=mp4
videoCodec=hevc
maxWidth=2800
maxHeight=1840
allowVideoStreamCopy={true|false}
allowAudioStreamCopy=true
audioCodec=aac
api_key={token}
```

For sources that need optimization, use:

```text
allowVideoStreamCopy=false
```

For sources already acceptable, keep original download or allow stream copy.

## Server Verification

A test request against the current Jellyfin server returned a transcoded MP4 stream:

```text
HTTP/1.1 200 OK
Content-Type: video/mp4
Accept-Ranges: none
Transfer-Encoding: chunked
```

This confirms the server can produce an optimized stream without patching Jellyfin.

## Quality Rules

"No quality loss" is not literally possible when downscaling or converting H.264 to H.265. The practical target is "no visible quality loss on this tablet".

Recommended decision rules:

1. Do not upscale.
2. Downscale only when source dimensions exceed the tablet envelope.
3. Keep HDR/Dolby Vision originals by default until HDR tone-mapping behavior is explicitly tested.
4. For SDR sources, use HEVC with a conservative bitrate.
5. Preserve subtitles and metadata in the app database as today.

Suggested bitrate presets:

| Mode | Resolution cap | Suggested HEVC video bitrate |
| --- | --- | --- |
| Tablet optimized | 2800x1840 | 8-14 Mbps for 4K/downscaled film, 5-8 Mbps for animation |
| Storage saver | 1920x1080 | 3-6 Mbps for film, 1.5-3 Mbps for animation |

These should be user-tunable later.

## Download Worker Impact

`MediaDownloadWorker` needs a branch before building `downloadUrl`:

- If mode is `Original`, keep `getDownloadUrl(itemId)`.
- If mode is optimized, build a Jellyfin stream URL with `static=false` and optimization params.

Progress caveat:

- Transcoded stream responses may use `Transfer-Encoding: chunked`.
- `Accept-Ranges` may be `none`.
- `contentLength()` may be unknown.

So optimized downloads may need indeterminate progress or byte-only progress until finished.

Resume caveat:

- Original downloads can resume with `Range`.
- Transcoded downloads should probably restart from the beginning if interrupted.

Implementation should mark optimized downloads as non-resumable unless Jellyfin returns range support for a specific stream.

## Storage Target Interaction

The optimized stream should use the same storage abstraction already added for custom folders:

- app-specific file target
- SAF `content://` custom folder target

No separate storage model is needed.

## Server CPU Guidance

Prefer server-side encoding over tablet-side encoding:

- tablet does not heat up;
- OLED is not kept awake;
- no large temporary original file on device;
- server can use CPU or hardware encoder;
- failure/retry is easier to control.

For high visual quality per byte, prefer CPU `libx265` over fast hardware encoders when the server has enough time. For speed, a hardware HEVC encoder can be added later as a separate "fast optimize" mode.

## Implemented Client Profiles

The client exposes three Jellyfin video download modes:

- `Original file`: download the server file as-is with resumable progress.
- `HEVC best quality`: protect HDR/Dolby Vision by downloading original, copy suitable SDR HEVC originals, otherwise transcode to HEVC MP4 with a tablet display cap and adaptive 4-12 Mbps target.
- `HEVC storage saver`: protect HDR/Dolby Vision by downloading original, otherwise transcode to HEVC MP4 with a tablet display cap and adaptive 2.5-8 Mbps target; already-small suitable HEVC sources are copied.

Transcoded profile parameters:

- Endpoint: `/Videos/{itemId}/stream.mp4`.
- `static=false`.
- `videoCodec=hevc`.
- `audioCodec=aac`.
- `allowVideoStreamCopy=false`.
- `allowAudioStreamCopy=true`.
- `maxWidth={tablet display width in pixels}`.
- `maxHeight={tablet display height in pixels}`.
- `videoBitRate=min(adaptive target, source bitrate)`.
- `cpuCoreLimit=10`.

This profile family is intentionally conservative for testing on a shared or unfamiliar server. It adapts the resolution cap to the tablet display, avoids re-encoding HDR/Dolby Vision, and avoids asking Jellyfin to encode above the source bitrate.

Important limitation: Jellyfin's stream OpenAPI exposes `cpuCoreLimit`, but does not expose a per-request "disable hardware encoder" flag. If the server global transcoding settings still prefer NVENC, Jellyfin may choose NVENC even for this client profile. A true CPU x265 mode must be enforced on the Jellyfin server configuration or by a server-side plugin/wrapper.

## Open Questions

- Should optimized downloads always use `mp4`, or keep `mkv` when subtitles/audio layouts require it?
- Should HDR/Dolby Vision be excluded from optimized downloads by default?
- What exact bitrate presets feel right after testing on the tablet screen?
- Should the client expose "Tablet native" and "1080p saver" as simple labels instead of numeric settings?
