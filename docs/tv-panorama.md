# Second Android TV interface: Panorama

The original UI remains the default. Settings → Interface of the TV has two
immediately reversible choices: Classic and Panorama. This preference and the
Panorama motion policy are stored only on the TV (`tv.appearance`); they are not
part of account/library sync and cannot change the phone interface.

Panorama uses its own navigation rail, cinema hero, scene transitions, film-strip
cards, poster grids, search keyboard, collection, schedule and title page. It uses
existing Api/AnimeSources/Store/Nav, resume plans, smart/voice/phone search,
bookmarks, spoiler policy, episode menus, franchise order, preview clips,
recommendations, episode counts, profile picker, updater and screensaver. Account
and player settings remain the same controls, rendered with Panorama styling.
The existing Media3 player and its controls are shared without a replacement.

Manrope (OFL, license included in TV assets) supplies the new typeface. Static
400/500/600/700 instances also support API 23–25; these are derived from the
official Google Fonts variable font with fontTools. Wide art
comes from the actual title detail. A portrait composition is retained if wide
art is missing. No generated title artwork, fake account or pretend playback is
used by production screens.

Focus: Right enters the content from the rail, Left at the content's edge returns
to the rail. Back first returns to the rail, then Home. Menu still opens settings.
The rail expands over the content, avoiding repeated grid measurement. Returning
from a title/player restores tab state using the App's shared saveable state
holder. Focus keys for the alternate UI have their own prefix. Motion has Auto / Full / Off choices. Auto suppresses transitions on weak
devices; Full is an explicit opt-in. The emulator scenario selects Full and sets
the system animation scale to 1, so it actually exercises transitions.

## Checks

`gradle :app:testDebugUnitTest --tests 'ru.qmods.anixq.tv.panorama.PanoramaTest'`
checks persistence, library identity, switching back to Classic, D-pad routing,
existing bookmark routing, and actual components at 1920×1080. Test-only catalogue
fixtures and artwork are captured public API responses, not a production data
source. Image renders are emitted under `app/build/reports/panorama`.

`android/ci/tv-shots.sh` installs the real release APK on an Android TV emulator,
captures both interfaces against live services and records remote navigation.
Launch extras `--es ui classic` / `--es ui panorama`, plus `--es motion full`, select a normal appearance
preference for screenshot automation; no credentials or fake data are injected.
A missing account on this fresh emulator does not verify authenticated playback.

## Executed results — 2026-11-16

**Published: 1.0.74**, available through both existing updater manifests. TV
appearance is selected in Settings → Interface of the TV → Panorama. Classic
remains the default and the choice is reversible without clearing app data.

- [Release build 43821975264](https://github.com/qmurzik/AnixQ/actions/runs/43821975264): both signed release APKs succeeded, as did all mandatory regression steps.
- 55 mandatory unit/UI tests passed: core 39, TV source/sync regressions 3,
  Panorama UI 9, phone 4. Three opt-in authenticated live checks were skipped.
- The separate pre-existing optional `FocusTest` still fails at line 60. Its
  `continue-on-error` step conclusion is not a passing test result. The full
  legacy Paparazzi suite also still lacks `/fake/titles.json`; neither is counted
  as a successful check here.
- [TV emulator run 43822120858](https://github.com/qmurzik/AnixQ/actions/runs/43822120858): Android TV API 30, x86, 1920×1080, 4 GB RAM, Full motion and system animation scale 1. Screenshots of Classic and Panorama and a 35-second D-pad recording were captured against real services. No runtime fatal error or app ANR was found.
- The capture build is 1.0.75 and the published build is 1.0.74; **both use the
  exact source commit `7f2a227ecc9e5d1991ea234ccaa4a88ae7554465`**. The preview run
  does not publish another updater version.
- Scene changes, initial focus, preserving the hero when entering the film strip,
  title-header framing, bounded artwork during zoom, and source/voice controls
  were inspected. Test fixtures are not packaged in either released APK.
- Both published APKs were downloaded and checked with `apksigner`/`aapt`. Package
  IDs, versionCode 74, original signing certificate, font/license assets and
  matching versioned/rolling asset SHA-256 digests were verified. They update
  the existing 1.0.69 packages with the same certificate.

Physical TV hardware, long playback under a signed-in account, audio and GPU
performance on actual TV boxes were not verified in this environment. The
emulator recording is not a frame-rate benchmark.

[Home screenshot](https://github.com/qmurzik/AnixQ/releases/download/tv-v1.0.74/Panorama-home.png) ·
[Title screenshot](https://github.com/qmurzik/AnixQ/releases/download/tv-v1.0.74/Panorama-title.png) ·
[D-pad recording](https://github.com/qmurzik/AnixQ/releases/download/tv-v1.0.74/Panorama-navigation.mp4) ·
[Preview bundle](https://github.com/qmurzik/AnixQ/releases/download/tv-v1.0.74/Panorama-preview.zip)

[Machine-readable verification](tv-panorama-verification.json) contains APK hashes,
manifest contents, signing certificate and test/emulator outcomes.
