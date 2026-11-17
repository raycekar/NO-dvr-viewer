# Architecture (MainActivity.java + TouchImageView.java)

- **Polling**: a `Handler` loop fetches each channel's JPEG on a background `ExecutorService`,
  interval 1–5 s (user setting). `fetchGrid()` for the grid, `fetchSingle()` for the single view.
  Stops in `onPause`, resumes in `onResume` — no fetching when backgrounded.
- **Layout is orientation-driven** (no manual toggle): portrait → full-width scrolling list,
  landscape → quadrant grid. Rebuilt live on rotation via `onConfigurationChanged`
  (manifest `configChanges="orientation|screenSize|keyboardHidden|screenLayout"`,
  `screenOrientation="fullSensor"`).
- **Tiles** are `TouchImageView`: pinch-zoom + pan + double-tap zoom; single tap opens the
  fullscreen single-channel view. Channel-swipe is disabled on tiles. In the portrait list, a tile
  captures touch only while zoomed/pinching, so the list still scrolls.
- **Single view** (`showSingle`): swipe left/right changes channel (wraps), pinch to zoom, tap or
  Back returns to the grid. Only the focused channel is polled.
- **Timestamp** per feed (green `HH:MM:SS`, bottom-right) updates only on a successful fetch — a
  frozen timestamp means that feed stalled.
- **Keep screen on** while foreground: `FLAG_KEEP_SCREEN_ON`.
- **Settings** (gear, top-right): IP, username, password, feed count (1–16, default 4), refresh
  1–5 s. Dialog is in a `ScrollView` so it scrolls in landscape. Prefs name `"dvr"`.
- **Icon**: "Quad Lens" adaptive icon (dark gradient, cyan lens per quadrant, red live dot).
  Vector-only resources (no PNG rasterizer):
  `<adaptive-icon>` in `mipmap-anydpi-v26` + standalone vector in `mipmap-anydpi` for pre-26.
