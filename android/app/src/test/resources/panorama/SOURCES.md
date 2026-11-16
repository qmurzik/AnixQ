Public API/image fixtures captured on 2026-11-16 for UI rendering tests only.

- titles.json: https://api.yani.tv/anime?limit=8&sort=rating&sort_forward=false
- poster-*.jpg: each title's poster.fullsize URL in titles.json.
- anilib-title.json: https://anixq-edge.qmurzik7.workers.dev/api/anime/14421--kimetsu-no-yaiba-anime?fields[]=background&fields[]=summary&fields[]=genres
- cinema-poster.jpg / cinema-backdrop.jpg: cover.default / background.url from that title response.

These files are in test resources and are not packaged in the app or used by production screens.
