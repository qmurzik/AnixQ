# AnixQ sync format (v1)

One private GitHub Gist per user, description `AnixQ sync`, file `anixq-sync.json`.
Every device reads it, merges with its own data and writes the merged result back.
Merging is per key: the entry with the larger `at` (Unix seconds) wins; `watched` lists are united.

```json
{
  "v": 1,
  "device": "iPhone",
  "updated": 1793900000.0,
  "titles":   { "<titleId>": { "id": 1, "slugURL": "1--slug", "rusName": "…", "name": "…", "cover": "https://…",
                                "coverThumb": "https://…", "shikiId": 123, "typeLabel": "TV", "year": 2024 } },
  "progress": { "<episodeId>": { "pos": 512.3, "dur": 1420.0, "at": 1793900000.0,
                                  "title": 1, "ep": "5", "epName": "…", "team": 32412, "teamName": "AniLibria" } },
  "watched":  { "<titleId>": { "eps": ["1", "2", "3"], "at": 1793900000.0 } },
  "bookmarks":{ "<slugURL>": { "title": 1, "status": 21, "at": 1793900000.0 } },
  "prefs":    { "dubs": ["AniLibria", "Studio Band"], "at": 1793900000.0 }
}
```

- `progress` entries with `title` / `ep` / `team` describe what the user watched last per title; together
  they form the shared "Продолжить просмотр" list (newest first).
- A bookmark with `"status": null` is a removal (tombstone) and beats older entries.
- `titles` keys of `Title` follow the iOS model (camelCase); unknown keys are ignored.

## Pairing a TV

The TV shows a QR code `anixq://pair/<channel>?k=<base64url 32-byte key>`. The phone encrypts
`{"github": {"token","gist"}, "animelib": {"token","refresh","username","userId"},
"shiki": {"token","refresh","cookies","csrf","userId","nickname"}}` with AES-256-GCM
(12-byte nonce ‖ ciphertext ‖ 16-byte tag, base64) and publishes it once to MQTT topic
`anixq/pair/<channel>` on `wss://public.cloud.shiftr.io` (user/password `public`).
Only the TV knows the key, the broker only sees ciphertext.

## Remote control (phone → TV, any network)

The TV creates `remote: {channel, key, at}` in the sync document after its first sync (newest `at` wins).
Phones synced with the same gist pick it up automatically — no extra pairing.

- `anixq/rc/<channel>/cmd` — phone → TV, AES-256-GCM sealed JSON (`nonce ‖ ciphertext ‖ tag`, base64):
  `{cmd, id, at, …}` with `cmd` = `play` (`title`, `episodeId`, `teamId?`, `pos?`), `toggle`, `pause`, `resume`,
  `seek` (`by`), `seekTo` (`to`), `next`, `prev`, `skip`, `speed` (`value`), `volume` (`dir` ±1), `stop`, `ping`.
  Commands older than 2 minutes or with a repeated `id` are ignored.
- `anixq/rc/<channel>/state` — TV → phone, retained, same sealing: `{state, title, slug, cover, ep, epName, team,
  pos, dur, hasNext, hasPrev, inOpening, speed, device, at}` (every 5 s while playing and on every change).

Relay: `wss://public.cloud.shiftr.io` (MQTT 3.1.1, user/pass `public`).
