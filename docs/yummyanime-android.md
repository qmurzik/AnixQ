# YummyAnime integration: Android and Android TV

Implementation is in `android/core/.../data/sources`; the existing AnimeLib API,
AniLibria integration, Media3 engine and platform player controls are retained.
Reference snapshots: AnixQ `181a347`; YummyTV
`ea0248ac8c00daed95ed8b16056df68c20e4cdec`; yummy-lampa-plugin
`6f03695f99dceba4e04b64558bb5598da5a8e8f2`.

## Authentication and access

Sign in to your own YummyAnime account in Settings. AnixQ attempts to find the
signed-in user's own `AnixQ` application via `/apps`, or register it if absent,
and retrieves only its public `X-Application` key. Ownership is verified with
`/profile` and `/apps/{id}`. Registration is serialized and its ID is retained
before fetching the key, so a failed detail request does not create another app.
No application key from another client is used. If the service denies bootstrap,
Settings → YummyAnime → Advanced offers a manual public key from
https://yummyani.me/dev/applications (or the build-time `YANI_APPLICATION_TOKEN`).
Automatic setup is fixture-tested; account-authorized live registration has not
been verified without caller-owned credentials.
The official `/profile/login` request uses `need_json: true`; a personal token can
also be entered, but is verified with `/profile` before being stored. Passwords
are held only while submitting the login form. Tokens are encrypted using Android
Keystore AES/GCM and stored in the application's no-backup directory per profile.
They do not enter Gist, device hand-off or watch-together messages.

`X-Application` is application authorization; it does **not** replace a user's
bearer token. YummyAnime/Aksor may return placeholders without user authorization.
Consequently `/anime/{id}/videos` is blocked locally until both are configured.
Public catalogue availability or HTTP 200/206 is not evidence of episode access.

Only the API client adds YummyAnime credentials, only to its configured API base.
CDN/extractor requests have an independent client and explicit headers. The user
token refreshes after two days of use, and a 401 triggers one refresh/retry. Failed
401 refresh clears the session. A CAPTCHA response is reported; there is no CAPTCHA
bypass or embedded challenge solver. Interactive CAPTCHA completion is not implemented.
No borrowed credentials are present in the project.

Official contract: https://api.yani.tv/swagger and
https://api.yani.tv/swagger/swagger-ui-init.js, inspected on 2026-11-15.

## Catalogues and identity

Phone and TV catalogue/search screens can select AnimeLib or YummyAnime. Existing
home shelves keep their AnimeLib behavior. YummyAnime supports search, genres,
status, type, year and episode-count filters, rating/views/id sorting, posters,
descriptions, aired/total episode counts and next-episode dates where returned.
The schedule API is implemented in the shared client. The existing standalone
schedule screens are not replaced. The API has no documented last-episode sort;
that choice is therefore hidden for YummyAnime rather than mapped to a false sort.

Matching queries `/anime?shikimori_ids=...` or `mal_ids=...`, never title similarity.
For legacy AnimeLib `shikiId`, both YummyAnime `shikimori_id` and
`myanimelist_id` must confirm the same positive number. An explicit MAL ID is
matched directly. Conflicting known year/type rejects a match. Multiple candidates
are rejected. Episode selection uses the exact numeric episode value, retaining
fractional episodes. Ranges and nonnumeric special labels are not guessed.

AnimeLib positive numeric IDs and its existing sync keys remain unchanged.
Standalone YummyAnime titles use negative `anime_id`, `yummy:<id>` slugs and
negative video IDs for local episode keys. These IDs never reach AnimeLib's write
API. For a matched AnimeLib title, its original title and episode IDs remain the
local canonical progress keys; YummyAnime playback is not submitted to AnimeLib.
A changed YummyAnime video ID can resume through the recorded episode number.

Gist v1 progress records gain optional `playbackSource` and `balancer` fields;
old fields are retained. Both updated Android clients preserve negative IDs in
remote hand-off. Older clients do not gain YummyAnime playback support from this
change. Catalogues remain selectable rather than concatenated, avoiding duplicate
cards in a mixed feed.

## Playback

Title cards on phone and TV separate source, balancer and dubbing into three
short fields. The phone uses bounded bottom sheets with voice search; TV uses
focusable fields and bounded D-pad dialogs. Voices are filtered to the selected
balancer, generic prefixes are removed, and subtitle translations retain their
type. Source/balancer changes keep the same studio where available. Explicit
AnimeLib selections are no longer silently replaced by the AniLibria preference.
Quality changes reuse the current resolved stream instead of repeating extraction.
A signed-out YummyAnime viewer can still switch back to AnimeLib.

Sources resolve only when selected. Native streams play through the existing
ExoEngine, including HLS, MP4 and DASH. Each media source, including a queued next
episode, gets its own immutable header map. Absolute URLs bypass AnimeLib mirrors.
The player preserves episode, position, speed, subtitles and existing picture/audio
controls when a source changes. Quality labels include auto, standard resolutions,
2K and 4K. The existing Anime4K, PiP and TV controls remain in place.

| Extractor | Implemented behavior | Live verification limit |
| --- | --- | --- |
| Aksor | Video API qualities; meta/script API fallback; Referer; DASH/HLS/MP4 URLs | Anonymous API, DASH manifest and first media segment responded; **not an authenticated episode playback test** |
| CVH | Playlist → exact episode/voice → video API → MP4 ladder; raw-IP failover host normalization | Playlist and video API responded; no authenticated end-to-end playback |
| Kodik | Page cookies scoped to endpoint; script endpoint lookup; link decoding | Cookie and stream logic tested with fixtures; authenticated live playback unverified |
| VK Video | Published MP4/HLS links in supported player pages, optional video_ext page | No live playback verification |
| Rutube | Play options, HLS master and relative variants | No live playback verification |
| Sibnet | Player/source MP4 extraction and required headers | No live playback verification |
| Zedfilm / Hlamer | Static `video_Init` metadata, declared DASH/MP4 URL | Fixture verification only |
| Direct | Complete MP4/HLS/DASH/WebM URLs | Format-specific device decoding unverified |
| Alloha | Explicit unavailable message; iframe never sent to ExoPlayer | No direct resolver implemented |

Alloha in the Lampa reference uses a maintained browser/server session for direct
stream relay. That server and browser player are not transplanted. DRM, regional
and account restrictions are not bypassed. Unsupported variants are not presented
as playable. One failed extractor does not change another extractor's state.

API reads have a short bounded cache and coalesce concurrent reads. Network calls
have timeouts and cancellation wired to OkHttp. Old episode loads are cancelled.
A failed YummyAnime stream is resolved again once at the saved position, then the
user is asked to choose a different source. There is no automatic loop between
unrelated dubs. YummyAnime prebuffering is intentionally disabled to avoid preparing
a different dub under an expired URL.

Offline downloads retain AnimeLib support and can save supported direct MP4 files
with the source's headers. HLS/DASH downloads are not implemented; manifests are
never saved as pretend MP4 files. Downloads do not transfer between devices.

Watch-together adds source/voice/balancer metadata. Each participant needs their
own YummyAnime access. Private stream URLs and authentication headers are not
broadcast; old web/iOS room clients cannot resolve the new source metadata.
Existing AnimeLib room messages remain supported.

## Verification

Run from `android` with JDK 17 and Android SDK 35:

```sh
gradle :core:testDebugUnitTest
gradle :app:testDebugUnitTest --tests 'ru.qmods.anixq.tv.focus.YummySourceTest' --tests 'ru.qmods.anixq.tv.focus.PlaybackSelectorTest'
gradle :mobile:testDebugUnitTest
gradle :app:assembleDebug :mobile:assembleDebug :app:assembleRelease :mobile:assembleRelease
```

Fixture tests cover parsing, independent IDs, remote matching, episode/voice
selection, source headers, Aksor fallback, CVH API flow, Kodik cookie isolation,
request cancellation, concurrent-read coalescing, official login, restored-session token refresh, Zedfilm metadata and absolute-URL mirror bypass.
Android tests cover Gist history round-trip and the D-pad source dialog. The phone
source-switch test checks the requested Media3 position using local fixture URIs;
it is not a decoding or live playback test.

`LiveSourcesTest` is opt-in and skipped by default. It requires caller-owned
`YANI_APPLICATION_TOKEN`, `ANIXQ_YUMMY_USER_TOKEN`, and public iframe URLs in
`ANIXQ_LIVE_AKSOR`, `ANIXQ_LIVE_CVH`, `ANIXQ_LIVE_KODIK`. Supply only credentials you
are authorized to use. These checks do not replace watching an episode on a device.

The full upstream `:app:testDebugUnitTest` additionally includes Paparazzi
`Screens` tests whose `/fake/titles.json` fixture is absent in this checkout. The
pre-existing `FocusTest.everyTabReachableAndDownEntersContent` also fails on the
unchanged `181a347` baseline in this environment: it expects the Home tab to hold
focus, but the AnimeLib login button takes it. These failures must not be reported
as passing regression checks.

No physical Android/TV device or emulator is available for long playback,
remote hardware, audio, Anime4K GPU, PiP or account-authorized playback verification.
Final build/test outcomes are recorded below.

Release builds must use the existing repository signing key. The `Android TV`
GitHub Actions workflow restores it from the `anixq-tv-signing-v1` cache, or uses
the configured repository secret. Manual workflow runs also compare each APK's
certificate against the published `tv` and `android` releases before uploading
artifacts. Local release builds fail if signing credentials are missing, instead
of substituting a debug key. The initial local test-signed APKs are superseded
by the verified workflow artifacts from run 43778445569.

## Executed results (2026-11-16 UTC)

The initial signed CI build 1.0.68 was verified in
[run 43778445569](https://github.com/qmurzik/AnixQ/actions/runs/43778445569).
Both APKs match the published certificate:
`fea95b7474d396516665375c40637f1fe71c84b3af9d75aca2d4635fcc47c2a8`.

The subsequent UX/auth revision adds six bootstrap fixture tests, four shared
selection tests, two core regressions, a signed-out fallback regression and real
phone/TV picker interaction tests. Functional checks use Compose/Robolectric;
these do not claim physical device or authenticated playback validation.

The full upstream screenshot/focus suite is **not green**: the absent fixture and
baseline focus failure described above remain. Authenticated complete episodes,
Android hardware decoding and physical remote operation are **not verified**.

**1.0.69 is published** in both in-app updater channels (`android` and `tv`).
[CI run 43783277952](https://github.com/qmurzik/AnixQ/actions/runs/43783277952)
succeeded on `4c1968ef78dd227a5a37e789682779cdfd537561`. All CI regression
steps, including upstream focus tests, passed. Locally, 46 tests passed and three
opt-in authenticated live checks were skipped; both debug builds succeeded.
Both release builds passed in CI using the existing signing key.

Published APKs were downloaded and independently verified: matching certificate,
correct package IDs, versionCode 69, LICENSE/NOTICE, and matching GitHub SHA-256
digests for both versioned and rolling assets. Both updater manifests advertise
1.0.69 and point to the correct versioned APK. The signed files are also copied to
`android/build/signed/AnixQ.apk` and `AnixQ-TV.apk`.

Final APK hashes, suite counts and manifest contents are recorded in
[yummyanime-verification.json](yummyanime-verification.json).

## Attribution

**Based on YummyTV — https://github.com/Helandy/YummyTV**. Adapted extractor/API
mechanisms are CC BY-NC 4.0, with upstream LICENSE and NOTICE included in both APKs
under `assets/licenses`. This adaptation changes networking, models, matching,
headers and UI integration; it does not import YummyTV's interface or application.
