# Second Android TV interface: Panorama

The original UI remains the default. Settings → Interface of the TV has two
immediately reversible choices: Classic and Panorama. This preference and the
Panorama motion switch are stored only on the TV (`tv.appearance`); they are not
part of account/library sync and cannot change the phone interface.

Panorama uses its own navigation rail, cinema hero, scene transitions, film-strip
cards, poster grids, search keyboard, collection, schedule and title page. It uses
existing Api/AnimeSources/Store/Nav, resume plans, smart/voice/phone search,
bookmarks, spoiler policy, episode menus, franchise order, preview clips,
recommendations, episode counts, profile picker, updater and screensaver. Account
and player settings remain the same controls, rendered with Panorama styling.
The existing Media3 player and its controls are shared without a replacement.

Manrope (OFL, license included in TV assets) supplies the new typeface. Wide art
comes from the actual title detail. A portrait composition is retained if wide
art is missing. No generated title artwork, fake account or pretend playback is
used by production screens.

Focus: Right enters the content from the rail, Left at the content's edge returns
to the rail. Back first returns to the rail, then Home. Menu still opens settings.
The rail expands over the content, avoiding repeated grid measurement. Returning
from a title/player restores tab state using the App's shared saveable state
holder. Focus keys for the alternate UI have their own prefix. Motion can be
disabled; weak-device mode suppresses the new transitions and focus scaling.

## Checks

`gradle :app:testDebugUnitTest --tests 'ru.qmods.anixq.tv.panorama.PanoramaTest'`
checks persistence, library identity, switching back to Classic, D-pad routing,
existing bookmark routing, and actual components at 1920×1080. Test-only catalogue
fixtures and artwork are captured public API responses, not a production data
source. Image renders are emitted under `app/build/reports/panorama`.

`android/ci/tv-shots.sh` installs the real release APK on an Android TV emulator,
captures both interfaces against live services and records remote navigation.
Launch extras `--es ui classic` / `--es ui panorama` select a normal appearance
preference for screenshot automation; no credentials or fake data are injected.
A missing account on this fresh emulator does not verify authenticated playback.

Build, emulator and release outcomes will be recorded after verification.
