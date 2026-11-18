# TV GPU upscaling and black-screen recovery

The native Media3/ExoPlayer remains the playback engine. Classic, Panorama and Neon
share the same TV player and the existing D-pad dialogs. Original mode IDs 0–15
remain valid. FSR 1 is added as ID 16, immediately after Off in the chooser.

## Choices

| Mode | What actually runs | Constraints |
| --- | --- | --- |
| FSR 1 | AMD's spatial EASU reconstruction followed by RCAS sharpening | Float GLES port; up to 2× each axis, bounded by the existing screen/device target. No neural or temporal frame generation. |
| Anime4K | Bundled bloc97 CNN/GAN weights, rendered in multiple GPU passes | GLES 3 and renderable half-float textures. Intermediate allocations limited to 96 MiB; heavy modes still require a stronger box. |
| Lanczos + RCAS | Lanczos-2 resampling with anti-ringing, then sharpening | Compatible lightweight option, not relabelled as FSR. |
| Sharpening | RCAS only | Does not increase frame dimensions. |

For an unspecified Mi Box second/third generation, FSR is the recommended first
attempt; no particular Mi Box GPU/firmware was available for physical testing.
The existing weak-device policy caps the target at 1080p; this change does not
claim 4K real-time neural processing on a 2 GB box. FSR maps 720p to 1080p when that
is the target; lower input may stop at 2× and be scaled to the surface normally.
Equal/higher-resolution input is not needlessly enlarged. The player reports sizes
after successful frame drawing. Its existing A/B comparison remains available.

## Fixes and evidence

- The old Anime4K loop skipped both sampler and size uniforms when a sampler was
  optimized out. A Gaussian pass can use `HOOKED_size` without sampling `HOOKED`.
  Its zero sigma caused NaN/black pixels. Instrumented framebuffer testing first
  failed on Darken HQ; setting size uniforms independently fixes this path.
- An empty Media3 effect list still activates `PlaybackVideoGraphWrapper`.
  Disabling/recovering now rebuilds the player rather than requesting empty effects.
  The same PlayerView remains attached; seek position, speed, track selection and
  volume are preserved, and queued media with an old graph is discarded.
- A failed graph also bypasses colour processing without deleting the user's
  preset. Ordinary source/quality reloads do not silently reactivate failed effects;
  an explicit user change permits another attempt. Phone colour recovery uses the
  same shared engine path.
- GPU failures are distinguished from network failures. Startup drops during the
  first three seconds after the first frame do not disable effects. Steady neural
  overload falls back once to FSR; overload of lightweight effects disables them.
- Anime4K framebuffer completeness and texture limits are checked, partial shader
  resources are released, and the incompatible GLES 3 copy fallback is replaced by
  the GLES-compatible EASU path. Capability is checked per new GL configuration.
- Source-specific headers, the selected episode and resolved video URL are reused
  when rebuilding for effects; GPU recovery does not require fresh extractor access.
- PixelCopy retries transient first-buffer/timeout errors for at most 500 ms, and
  releases failed capture bitmaps. First-frame callbacks can precede a readable
  Surface buffer; this was reproduced during native playback instrumentation.

FSR's EASU code is adapted from the official full-precision float algorithm, with
12 texture taps replacing gathers and ordinary reciprocal/square-root operations
replacing platform approximations. MIT attribution ships in `assets/fsr/LICENSE`.
Anime4K's original MIT headers remain in the shader assets.

References pinned to the installed Media3 version / upstream implementation:

- https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java
- https://github.com/androidx/media/blob/1.5.1/libraries/effect/src/main/java/androidx/media3/effect/DefaultVideoFrameProcessor.java
- https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h
- https://github.com/bloc97/Anime4K

## Verification

`android-tv.yml` dispatch with `upscale_checks=true`, `screenshots=false` builds
the real instrumentation APK and runs it on Android TV API 30, x86, 1920×1080,
SwiftShader. No accounts, catalogue fixtures or arbitrary media URLs are injected
into the release app. The debug-only activity/test clip are excluded from releases.

The GPU suite renders the production effects, reads framebuffer pixels and writes
PNGs. It checks FSR output dimensions/difference from bilinear, lightweight modes,
all 14 Anime4K assets, and compatible fallback. The native-player test decodes a
small local H.264 clip and switches Off → FSR → Off → Anime4K → Sharpen → Off,
checking first frames, readable/nonblack Surface pixels, seek and speed. A separate
failure-injection test checks recovery with a non-neutral colour preset.

The first preflight (run 44374612586, source `40c745c`) failed on the real Darken HQ
black frame and a transient PixelCopy failure. The next (run 44376633343, source
`fded802`) passed all five initial instrumented tests, including all 14 shader
files and native playback switches. The driver reports GLES 3.0 SwiftShader even
for a requested GLES 2 context: this validates GLES 1.00 shader compilation and
rendering, not performance on an older physical GLES 2 Mi Box.

Physical-device picture quality, HDR behaviour, long-session thermal limits and
4K throughput are not verified. No claim of reconstruction of lost original detail
or equivalence to temporal/neural desktop upscalers is made.

Final preflight: run **44377937800**, source `441d88e`, **all six GPU/native tests
passed**, including deliberate graph failure with a non-neutral colour preset.
Both signed release builds and the signature-compatibility guard passed in that
preflight. GPU runtime, shaders, test harness and script in the final change are
byte-for-byte identical to that tested source; hashes are recorded in
`tv-upscaling-verification.json`. Final UI handlers also pass the current quality
when rebuilding so existing resolved streams are reused.

Local final regressions: core 55 tests (52 passed, three opt-in source-network
checks skipped), selected TV source/D-pad/Panorama tests 13 passed, phone tests
five passed. Total: **70 unit/UI checks passed, three skipped**, plus six actual
GPU/native checks. Both debug APKs and the instrumentation APK assembled.
