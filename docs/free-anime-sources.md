# Aksor and free backup catalogues — 2026-11-18

Base: AnixQ main `3ce5345325a1abde8b8400da994034609fe6d62d`, including
Neon Tokyo, source racing, dubbing comparison and Yummy opening/ending marks.
All probes below used public endpoints without account credentials, borrowed
application keys, subscriptions or payment. Availability is a point-in-time
observation from the development network, not a guarantee for every country/title.

## Aksor: implemented on phone and TV

The previous integration contained an extractor but blocked the Yummy video list
and native player whenever a Yummy user/application session was absent. That also
hid publicly accessible Aksor episodes. The public listing is now allowed, filtered
to Aksor only. Other Yummy balancers keep their existing account requirement because
they can return anonymous placeholders. Signing in with one's own application key
restores the full balancer list. Existing source/balancer/dub controls are reused in
all three TV appearances and on mobile; playback still uses Media3/ExoEngine.

The extractor preserves every available quality, encodes spaces, accepts DASH/HLS/
MP4, rejects HTML/iframe URLs as video, checks returned video hash and any supplied
expected episode/title, and isolates failures of individual fallback scripts.
Cancellation propagates without starting fallback requests. API calls have a
six-second deadline inside the overall 25-second resolution deadline. No aggressive
fallback to a different source, episode or dub was added. Identity mismatches stop
resolution instead of falling back to an unverified HTML player.

Live sample: Yummy title 45, episode 1, StudioBand, Aksor hash
`dae2edcc93409223f9e5f940895acf27`:

- `GET https://api.yani.tv/anime/45/videos`: HTTP 200, Aksor episode links present.
- `GET https://player.aksor.tv/api/video/dae2edcc93409223f9e5f940895acf27`:
  HTTP 200, matching anime/episode metadata, q1080 DASH URL.
- DASH MPD: 26 minutes 5 seconds, video 1920×1080, Russian Studio Band audio,
  no `ContentProtection` element in this sample.
- Both video/audio initialization and first media segments: HTTP 206 with Range;
  full first segments were also downloaded for local inspection.
- `ffprobe` of initialization + first video segment: H.264, 1920×1080;
  audio: AAC, 48 kHz, two channels. These are genuine media containers, not an HTML
  access message. The first segment is ~35 s video / ~30 s audio.

One subsequent CDN probe with `Range` returned HTTP 503. A normal manifest request
then returned 200 in the Kotlin extractor smoke test. The smoke test now follows
Media3's request shape: no Range header for MPD/HLS documents, byte ranges only for
media files. The intermediate failure is recorded in the verification JSON; no
automatic retries were added to turn failing live probes green.

This is extraction, transport and codec verification. It does not establish
complete Android playback, viewing to the end, every dub, or access to restricted
episodes. No DRM or access restriction is bypassed. An account may still be needed
for a particular title. `aksor.tv` itself returned 521 through the research fetcher;
the player/API/CDN subdomains used by the app responded successfully.

## AnimeGO as a playback reserve (2026-11-19)

Android and Android TV offer **AnimeGO** in the source selector of a matched
AnimeLib or YummyAnime title. The source uses `anime-go.org` without accounts or
application keys. Search supplies candidate paths. AnimeGO's own external-ID
redirect must confirm the title's MAL ID or Shikimori ID before the app loads its
player listing. The app reads the redirect target without visiting the other site.
Only one matching title and one regular season per listing are accepted.
Season-zero episodes are kept out of regular seasons.

The selected episode and progress keep their original catalogue IDs. AnimeGO
supplies the voice and player for that episode. Kodik HLS is resolved and played
in the existing ExoPlayer. If AnimeGO lists a compatible Aksor embed, the same
Aksor resolver handles it. Public Aksor episodes remain available through
YummyAnime. Other YummyAnime sources retain their existing access rules.
AnimeGO has no independent catalogue screen in this integration.

## Other candidates and initial observations

| Address | Live observation | Native-player integration implications |
| --- | --- | --- |
| https://anime-go.org | Catalogue and `/search?q=...` respond 200. Public form POST `/api/player` with `id` returns sources → translations → episodes. Attack on Titan season 1 (`4505`) returned 9 Kodik translations. Title `157` returned 31. | Integrated as a playback reserve using Kodik and verified MAL or Shikimori redirects. The HTML and player API are undocumented and can change. |
| https://animego.online | Redirects to `/main-animego-12`; catalogue and title pages respond 200. Public GET `/engine/ajax/controller.php?mod=player&id=63&slot=1` returns a Kodik iframe. Title pages also offer Alloha. | Free viewing is exposed without login. A serial iframe still needs exact season/episode/dub resolution. The Alloha iframe is not an ExoPlayer media URL. Do not treat the catalogue HTML or iframe as successful native playback. |
| https://animego.org | HTTP 500 in this environment. | The reported redirect to `.me` could not be reproduced here. Do not use as the sole network dependency. |
| https://animego.me | HTTP 500 in this environment and research fetcher. | Unavailable for end-to-end verification. |
| https://v1.animego.bz | HTTP 500 in this environment. | Unconfirmed mirror; not configured as trusted fallback. |
| https://animevost.org / https://api.animevost.org/v1 | Site and API respond 200. `last?page=1` returned a catalogue; public form POST `playlist` with `id=4056` returned 8 episodes with `hd` and `std` URLs. | Free independent MP4 backup, predominantly the AnimeVost dub rather than a large balancer/dub aggregator. The tested HD link works over HTTPS despite API returning HTTP. HTTP 206, MP4 `ftyp` signature verified. No reliable cross-catalogue IDs were confirmed, so title-name-only merging would be unsafe. |

Additional live AnimeGO sample: `/search?q=Атака титанов` returned separate seasons,
films and specials; `/api/player` for first-season ID `4505` returned 9 Kodik dubs.
One episode's Kodik embed resolved to qualities 360p/480p/720p. The 720p HLS playlist
responded HTTP 200, ended with `#EXT-X-ENDLIST` and had 1446.411 seconds of media.
Its first TS segment responded 200 (~1 MB); `ffprobe` identified H.264 1280×720 and
AAC audio. This verifies one free extraction/transport chain, not the entire
catalogue or Android playback. The JVM live check initially failed because the
extractor depended on Android's mocked Base64; it now uses the existing Okio
Base64 implementation with the same wire format, also covered by a fixture test.

The two working AnimeGO-branded sites have different markup, player endpoints and
numeric IDs. Their common branding or the supplied Telegram listing does not prove
that they share the old AnimeGO database. They must have separate provider identity
namespaces and matching logic if implemented. Never feed an AnimeGO numeric ID to
YummyAnime or AnimeLib. Keep separate seasons, films and specials.

Anime365 is excluded as a proposed reserve here: the requirement is free viewing,
not merely a freely accessible catalogue/API. AniLibria is already integrated and
is not presented as a new candidate. No paid provider has been added.

## Reproducible opt-in extraction check

`ANIXQ_LIVE_AKSOR=https://player.aksor.tv/video/<public-hash>` enables
`LiveSourcesTest.liveAksor`; otherwise network tests skip. A caller-supplied public
iframe no longer requires an unrelated Yummy account to test an extractor. The
smoke test asserts media response bytes, not decoding or complete-episode playback.
Fixtures test anonymous filtering, authenticated full lists, quality preservation,
wrong-episode rejection, fallback isolation, header separation and cancellation.
Phone and TV source-state regression tests cover restarting a public source preserving
canonical episode ID and seek position without forcing a Yummy login.

## Local validation

Gradle 8.14.3 / JDK 17: core suite 52 tests (51 passed, CVH live check skipped),
selected TV source/D-pad/Panorama regressions 13 passed, phone suite 5 passed.
Total: 69 passed, one skipped, zero failures. Public Aksor and AnimeGO/Kodik
extraction checks ran against the network; fixtures were not counted as live
playback. Both `:app:assembleDebug` and `:mobile:assembleDebug` succeeded.
The unrelated full TV Paparazzi/legacy-focus suites were not run in this check.
Release signing uses the existing CI key and compatibility check.
