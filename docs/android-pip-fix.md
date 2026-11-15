# Android PiP correction

The video was composed at separate call sites for portrait, landscape and PiP. These sites reused the same ExoEngine PlayerView (or Kodik WebView). Entering PiP could therefore try to attach a View that still belonged to the previous AndroidView holder, causing an asynchronous UI failure outside the try/catch around enterPictureInPictureMode.

PlayerVideoLayout now owns a single video composition slot. Portrait/fullscreen/PiP only change its constraints; portrait details and player controls are separate overlays. Layout changes no longer recreate the AndroidView or release the player. PlayerScreen's playback lifetime remains independent of video layout; playback is not paused on ON_PAUSE, while ON_STOP still pauses when the window is no longer visible (unless background audio is enabled).

MainActivity owns entry and device checks. Android 12+ uses setAutoEnterEnabled based on actual native playback readiness; Android 8–11 uses onUserLeaveHint. The manual button uses the same guarded entry function. Leaving the player disables automatic PiP. The aspect ratio remains a bounded 16:9.

The regression test switches portrait → PiP → portrait → fullscreen → PiP → fullscreen → portrait and asserts that both the underlying Android View and its parent are retained. It runs the real Compose/AndroidView layout under Robolectric; it does not emulate the Android system's PiP window or prove device-specific behavior. No device crash trace was supplied.

Reference: https://developer.android.com/develop/ui/compose/system/pip-setup
