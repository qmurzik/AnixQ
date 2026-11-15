# Android / TV player fixes

Based on main `39f83c5` and the published Android/TV `1.0.64` APKs.

- Phone controls read current visibility in their long-lived tap handler. Rotating the player explicitly reveals controls and restarts the hide timeout.
- The phone activity starts in user-portrait, restores it after leaving the player, and selects it when reopening from the launcher. The player still follows rotation and supports explicit fullscreen and PiP.
- A seek on the timeline offers **Отменить** for eight seconds and restores the position before that seek. Episode changes invalidate the undo. Cancelled drags do not seek. While the undo is available, automatic skipping/advancement and progress writes wait, so an accidental seek into the ending is reversible.
- Both players suspend engine polling, progress writes and automatic next-episode actions while switching sources/episodes. Cancelled requests and stale prefetch/segment results cannot overwrite a later load. Each countdown verifies the episode again before advancing.
- **Понижать качество при плохой сети** / **Понижать качество при длительно нестабильной сети** is available in player settings on phone/TV, default off. When off, Media3's adaptive bitrate reduction is disabled too; mirror retries retain the chosen resolution. When on, lowering requires three stalls of at least six seconds in three minutes, with at least three minutes between quality drops. Mirror recovery waits eighteen seconds; source changes have a ten-second grace period.
- Publishing uses the existing Android TV workflow and cached/secret release key. Before updating either release channel, CI compares each APK signing certificate against the currently published APK; a mismatch blocks publication.

Validation: compile both Android apps, run the shared StallRecovery regression tests, build signed release APKs in the existing GitHub workflow, and check the `android-version.json` / `tv-version.json` rolling update manifests. Physical device rotation, gesture and playback smoke testing remains separate from these automated checks.
