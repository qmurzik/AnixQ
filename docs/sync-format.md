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
