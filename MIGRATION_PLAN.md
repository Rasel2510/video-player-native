# Ayesha Video Player — Flutter → Native Kotlin Migration Plan

**Source (read-only reference):** `d:\fme\video_player_android` (Flutter, ~17.5k lines of hand-written Dart in 141 files, plus ~1.2k lines of Kotlin)
**Target:** `d:\fme\video_player_kotlin` (new Android Studio project, 100% Kotlin + Jetpack Compose)
**Rule:** the Flutter project is never modified. It stays the behavioural reference: when in doubt, open the Dart file and match what it does.

---

## Status

| Phase | State |
|---|---|
| 0 — Project setup | **Done (2026-11-19).** `./gradlew assembleDebug` builds. Not yet run on a device. |
| 1 — Core and models | **Done (2026-11-19).** Theme, accent presets, appearance prefs (DataStore, same keys as Flutter), utils, models, 20 unit tests passing. |
| 2 — Data layer | **Written and compiled (2026-11-19); 35 unit tests passing.** Room (positions, durations, recents, library cache, seen paths), MediaStore + filesystem scanner, 3 scan modes, live updates, thumbnails, playback prefs, volume. Not yet checked on a device: use the "Library (debug)" tab. |
| 3 — Library UI | **Written and compiled (2026-11-19); 40 unit tests passing, no warnings.** Home, library (Continue Watching, folder resume pills, cross-library search, pull-to-refresh), folder videos (sort, search, multi-select, share, rename, delete, details, move/copy, resume FAB), folder picker, settings menu, scan-mode sheet. Opening a video goes to a stand-in player screen until phase 4. **Moved to phase 8:** "Move to Vault" actions and the "Secure Vault" menu entry. Not yet checked on a device. |
| 4 — Engine + controller | **Written and compiled.** `ExoPlayerEngine` (from `ExoPlayerPlugin.kt`), `PlayerController` (from `player_provider.dart`). |
| 5 — Player UI | **Written and compiled.** SurfaceView video, gestures, controls, overlays, all sheets, PiP. |
| 6 — Background / mini player | **Written and compiled.** Media3 `MediaSessionService` + `ControllerPlayer` adapter, audio mode, mini player. |
| 7 — Software fallback | **Written and compiled.** `MpvEngine` + `MpvSoftwareProbe` on `dev.jdtech.mpv:libmpv` 1.0.0. |
| 8 — Secure Vault | **Written and compiled; 44 unit tests passing.** PIN, biometrics, auto-lock, move/restore/delete, FLAG_SECURE. |
| 9 — Release prep | **Release build passes R8** (2026-11-19): ABI-split APKs (arm64 28.5 MB), Room schema export. Still to do on a device: the parity checklist below. |

**Nothing has run on a device yet.** Every phase compiles and the unit tests pass, but gestures, playback, the notification, PiP, libmpv and the vault need the parity checklist on real phones. Expect a round of fixes there.

### Deliberate differences from the Flutter build

- **minSdk 26** (was 24 in the plan): libmpv requires Android 8.0.
- **Storage:** Room instead of per-video SharedPreferences keys and big JSON blobs; positions keyed by the real path (no "a b" / "a_b" collisions).
- **Fixed:** the quick change check counted sub-1 KB files the scan skips, forcing a full rescan every 2 minutes.
- **Fixed (security):** "Use biometrics instead" no longer unlocks the vault on phones without biometrics, and can't fall back to the device PIN.
- **Safer:** deleting one vault video asks first; restoring never overwrites a file that has reappeared at the original path; a failed copy is cleaned up.
- **Volume boost** above 100 % actually works with ExoPlayer (LoudnessEnhancer); the Flutter ExoPlayer build clamped it.
- **Audio focus:** playback pauses for calls and when headphones are unplugged.
- **Position** is also saved every 15 s while playing (a crash no longer loses the session).
- "All files access" Settings page opens once, not on every launch; the scan-mode sheet offers it later.
- Folder screen is live; folder picker shows the selection; search-opened videos count as recent.
- **Motion:** the player zooms in over the list and zooms back out on close, carrying a still of the current frame (a SurfaceView can't fade or scale itself). Controls fade with the bars sliding in; swipe HUD, up-next card and loading poster animate in and out.
- **Library on return:** coming back from the player refreshes only Continue Watching and resume pills (after the transition); a full reload happens on first show and after the app was in the background. The MediaStore watch covers changes in between.

### Known limitations

- **Frosted controls** have no real blur: the video is a SurfaceView, which the app can't sample. Frosted uses the lighter gradient only.
- **Subtitle delay** works on the libmpv engine only (ExoPlayer has none; same as the Flutter build).
- **libmpv thumbnails** for undecodable files rely on mpv's screenshot with no video output; if that proves unreliable on devices, the player still captures a frame while playing.
- **Not done:** baseline profile (needs a device), release signing config.

**Decisions taken:** D1 = new id `com.ayesha.player` · D2 = start fresh (keep the vault folder and PIN hash format compatible anyway) · D3 = libmpv · D4 = minSdk 24 · D5 = single `:app` module.

**Toolchain (verified building together):** AGP 9.3.3 (Kotlin built in, no `kotlin-android` plugin) · Gradle 9.5.0 · Kotlin 2.3.20 · KSP 2.3.12 · Hilt 2.60.1 (2.59+ requires AGP 9; 2.60 needed to read Kotlin 2.4 metadata in current AndroidX libraries) · Coil 3.6.0 · Room 2.8.4 · compileSdk 37 / targetSdk 36 · Compose BOM 2026.09.00 · Media3 1.10.1. The current AndroidX libraries require AGP 9.1+ and compileSdk 37, so don't downgrade AGP.

---

## 1. Decisions to make before writing code

| # | Decision | Options | Recommendation |
|---|----------|---------|----------------|
| D1 | **applicationId** | (a) Keep `com.example.flutter_video_player.exo` so the Kotlin app installs as an *update* over the Flutter app. (b) New id so both install side by side. | **(b) during development** so you can compare the two apps on one phone. Switch to (a) only for release, and only if real users have the Flutter build installed, because of the vault (see D2). |
| D2 | **Existing user data** (SharedPreferences, vault files in `filesDir/vault`, the vault PIN hash) | Migrate on first launch, or start fresh | If you choose D1(a): write a one-time `LegacyDataMigrator` that reads `FlutterSharedPreferences.xml` (keys prefixed `flutter.`) and keeps the vault folder path and the PIN hash format (salted SHA-256) **exactly the same**. Otherwise vault videos become unreachable. If you choose D1(b): start fresh and skip this. |
| D3 | **Software-decoder fallback** (replaces `media_kit`/libmpv, used when MediaCodec can't decode, e.g. some 10-bit HEVC) | (a) libmpv for Android (same engine as today; prebuilt AAR such as `dev.jdtech.mpv:libmpv` from Findroid — verify it's still maintained). (b) LibVLC (`org.videolan.android:libvlc-all`, official Maven artifact). (c) Drop the fallback. | **(a) libmpv** for the same behaviour as today. Use (b) if the libmpv AAR is stale. Either one adds ~20–30 MB per ABI, so ship ABI-split APKs / an AAB. |
| D4 | **minSdk** | 21 (same as the Flutter build) or 24 | **24.** Compose and Media3 work better there, and devices below 24 are now very rare. |
| D5 | **Modules** | Single `:app` module or multi-module | **Single `:app`** with clear packages. Split later only if build times get bad. |

---

## 2. Target tech stack

| Concern | Flutter today | Kotlin replacement |
|---|---|---|
| UI | Flutter widgets, custom theme (`app_theme.dart`, 419 lines) | **Jetpack Compose + Material 3**, custom `AppColors` via `CompositionLocal` |
| State | Riverpod `Notifier`s + Freezed state classes | **ViewModel + `StateFlow`**, immutable `data class` states with `copy()` |
| DI | Riverpod providers / `Service.instance` singletons | **Hilt** (`@Singleton` for services) |
| Navigation | `Navigator` + `SmoothPageRoute` | **Navigation Compose** with custom enter/exit transitions |
| Async / isolates | `Future`, `Stream`, `Isolate` (folder scan, JSON parsing) | **Coroutines + Flow**, `Dispatchers.IO` / `Default` |
| Playback | ExoPlayer behind platform channels (`ExoPlayerPlugin.kt`) → Flutter `Texture` | **Media3 ExoPlayer called directly** (upgrade from 1.4.1 to the latest stable), rendered with `PlayerSurface` (media3-ui-compose) or `SurfaceView` in `AndroidView` |
| Software fallback | `media_kit` (libmpv) | libmpv or LibVLC (D3) behind the same `PlayerEngine` interface |
| Media session / notification | Manual `MediaSessionCompat` + custom notification + `PlaybackService` + `MediaActionReceiver` in `MainActivity.kt` | **Media3 `MediaSessionService`**, which handles the notification, lock screen, headset buttons and foreground service for you (much less code) |
| Preferences | `shared_preferences` | **DataStore (Preferences)** |
| Larger caches (durations, recents, vault DB, seen/new flags, positions) | JSON in SharedPreferences | **Room**, or DataStore + kotlinx.serialization for small sets |
| Thumbnails | `video_thumbnail` + `MediaStoreBridge.getThumbnail` + disk cache | **Coil 3** with a custom `VideoFrameFetcher` (`ContentResolver.loadThumbnail` on API 29+, `MediaMetadataRetriever` otherwise) and Coil's disk cache |
| Brightness | `screen_brightness` | `window.attributes.screenBrightness` |
| Volume / boost > 100% | `flutter_volume_controller` + engine volume | `AudioManager` (silent, no system HUD) + **`LoudnessEnhancer`** on the player's audio session |
| Keep screen on | `wakelock_plus` | `View.keepScreenOn` while playing |
| File picking (video, subtitle) | `file_picker` | `ActivityResultContracts.OpenDocument` (ExoPlayer reads `content://` URIs directly) |
| Share | `share_plus` | `Intent.ACTION_SEND(_MULTIPLE)` + `FileProvider` |
| Permissions | `permission_handler` | Activity Result `RequestPermission`; `MANAGE_EXTERNAL_STORAGE` via a Settings intent |
| Biometrics | `local_auth` | `androidx.biometric.BiometricPrompt` (Activity must be a `FragmentActivity`) |
| PIN hashing | `crypto` (SHA-256) | `java.security.MessageDigest`, **same salt and hash format** |
| Glass / blur surfaces | `BackdropFilter` (`glass_surface.dart`) | **Haze** library (backdrop blur on API 31+, translucent fallback below) |
| Splash / icon | `flutter_native_splash`, `flutter_launcher_icons` | `core-splashscreen` (bg `#050B0B`) + Android Studio Image Asset from `assets/icon.png` |
| Secure screen (vault) | Ref-counted channel call | `FLAG_SECURE` with the same ref-counting (`SecureScreenGuard`) |

---

## 3. Target project structure

```
app/src/main/java/com/ayesha/player/
├── AyeshaApp.kt                 // @HiltAndroidApp
├── MainActivity.kt              // single activity: Compose host, PiP, VIEW intents, orientation
├── core/
│   ├── theme/                   // AppColors, Typography, Theme.kt   ← app_theme.dart
│   └── util/                    // DurationFormatter, FileSizeFormatter, TrackLabels, CacheKey, VolumeColor
├── data/
│   ├── model/                   // VideoFile, VideoFolder, MediaTrack, TrackSnapshot, VideoSize
│   ├── engine/                  // PlayerEngine, ExoPlayerEngine, MpvEngine (or VlcEngine)
│   ├── media/                   // MediaStoreRepository, FolderScanner, ThumbnailFetcher, DurationCache, SoftwareProbe
│   ├── prefs/                   // PlayerPreferences, PositionStore, RecentFiles, Brightness, ScanMode
│   ├── vault/                   // VaultRepository, VaultPinStore, VaultSettings, SecureScreenGuard
│   └── db/                      // Room database + DAOs
├── playback/
│   ├── PlayerController.kt      // app-scoped; replaces PlayerNotifier (survives navigation → mini player)
│   └── PlaybackService.kt       // Media3 MediaSessionService
└── ui/
    ├── navigation/              // NavHost, routes, transitions
    ├── common/                  // GlassSurface, SheetSurface, TintableCard, SquishCard, Shimmer, ResumeDialog
    ├── home/  library/  folder/  picker/
    ├── player/
    │   ├── PlayerScreen.kt
    │   ├── layers/              // VideoLayer, GestureLayer, ControlsLayer, LockLayer
    │   ├── controls/  overlays/  sheets/
    │   └── mini/                // MiniPlayerOverlay (drawn above NavHost in the root scaffold)
    └── vault/
```

**Key design point:** `PlayerNotifier` is a global Riverpod provider today, and the mini player overlay wraps the entire `MaterialApp` (see `app.dart`). In Kotlin, `PlayerController` must be a **Hilt `@Singleton`** (or bound to `PlaybackService`), **not** a screen-scoped ViewModel. `PlayerViewModel` becomes a thin adapter over it. The `MiniPlayerOverlay` composable sits in the root `Box` above the `NavHost`.

---

## 4. Dart → Kotlin mapping

| Flutter source | Lines | Kotlin target | Notes |
|---|---|---|---|
| `main.dart`, `app.dart` | 71 | `AyeshaApp`, `MainActivity`, `ui/navigation` | Edge-to-edge, orientations, prefs preload, `SubtitleFontWarmup` (likely not needed in Compose; check for jank first) |
| `core/theme/app_theme.dart` | 419 | `core/theme/*` | Copy every color token exactly (`#0C0C0E` bg, `#FF8C00` accent, …), light and dark |
| `core/utils/*` | 145 | `core/util/*` | Direct ports |
| `data/models/*` | 107 | `data/model/*` | `data class`; `late final` → `val … by lazy` |
| `data/engines/player_engine.dart` | 125 | `engine/PlayerEngine.kt` | Streams → `StateFlow`/`SharedFlow`; `textureId` → `attachSurface(Surface)` |
| `data/engines/exoplayer_engine.dart` + `ExoPlayerPlugin.kt` | 222 + 491 | `engine/ExoPlayerEngine.kt` | **Reuse the existing Kotlin `ExoInstance` logic** (track encoding, rotation, fast seek, cues, video-unsupported detection) and remove the channel layer |
| `data/engines/media_kit_engine.dart` | 256 | `engine/MpvEngine.kt` | New implementation (D3) |
| `MediaStoreBridge.kt` + `media_store_service.dart` | 176 + 72 | `media/MediaStoreRepository.kt` | Reuse the query; ContentObserver → `callbackFlow` |
| `folder_scanner.dart` | 101 | `media/FolderScanner.kt` | Isolate → coroutine on `Dispatchers.IO` |
| `duration_cache_service.dart`, `software_probe_service.dart` | 324 | `media/DurationCache.kt`, `SoftwareProbe.kt` | Keep the concurrency cap of 2 (use a `Semaphore`) |
| `thumbnail_service.dart`, `thumbnail_widget.dart`, `shimmer_scope.dart` | 494 | Coil fetcher + `VideoThumbnail` composable | Keep the 45 s failure cooldown and the software fallback frame |
| `player_preferences_service.dart`, `position_service.dart`, `recent_files_service.dart`, `brightness_service.dart` | 400 | `data/prefs/*` | DataStore / Room |
| `media_session_service.dart` + `MainActivity.kt` (session/notification parts) + `PlaybackService.kt` | 92 + ~300 + 75 | `playback/PlaybackService.kt` | Rewrite on Media3 `MediaSessionService`, delete the custom receivers |
| `open_file_service.dart` + `MainActivity.handleViewIntent` | 27 + ~50 | `MainActivity` → nav event | Reuse `resolveToPath`/`queryDataColumn` |
| `volume_service.dart` | 43 | `data/prefs/VolumeController.kt` | AudioManager + volume observer |
| `vault_*.dart`, `secure_screen_guard.dart` | 429 | `data/vault/*` | Bounded concurrent moves; DB → Room |
| `providers/folders_provider.dart` | 822 | `LibraryRepository` + `LibraryViewModel` | Hybrid / MediaStore / File scanner modes, cache-first display, background diffing, "new" badges |
| `providers/player_provider.dart` | 1245 | `playback/PlayerController.kt` | **Largest and riskiest port.** Split into smaller pieces: `SleepTimer`, `AbRepeat`, `AutoPlayCountdown`, `SwipeGestureState`, `SoftwareFallback` |
| Other providers (theme, subtitle style, appearance, card style, controls style, scan mode, vault, continue watching) | ~510 | Matching ViewModels / prefs flows | Mostly straightforward |
| `screens/home_screen.dart` | 190 | `ui/home` | Permission flow, open-file handling |
| `screens/library_screen.dart` + `widgets/library/*` (19 files) | 551 + ~1.6k | `ui/library` | `menu_sheet.dart` (488) is the biggest widget |
| `screens/folder_videos_screen.dart` + `widgets/folder_videos/*` (16 files) | 1035 + ~1.1k | `ui/folder` | Sort, search, multi-select, share, rename, delete, move to vault |
| `screens/folder_picker_screen.dart` | 91 | `ui/picker` | |
| `screens/player_screen.dart` + `widgets/player/*` (48 files) | 404 + ~6k | `ui/player/*` | Gestures (double-tap seek, swipe brightness/volume, pinch zoom, hold for 2×), `seek_flash.dart` (414) animations, lock, PiP |
| `widgets/player/mini_player/*` | 762 | `ui/player/mini` | Drag/dismiss gestures, YouTube-style surface |
| `screens/vault_*` + `widgets/vault/*` | 575 + ~620 | `ui/vault` | Keypad, auto-lock, FLAG_SECURE |
| `widgets/common/*` | ~640 | `ui/common` | Glass → Haze; squish → `animateFloatAsState` on press |

---

## 5. Phases

Each phase ends with a build you can install and use. Test on a real low-end device after every phase, since the Flutter app has many low-end performance fixes (see the `FIX #…` comments) that must not regress.

### Phase 0 — Project setup
- Create the Android Studio project in `d:\fme\video_player_kotlin` (Empty Compose Activity, Kotlin DSL, version catalog).
- Add Hilt, Navigation Compose, Media3, DataStore, Room, Coil 3, Haze, kotlinx.serialization, biometric, splashscreen.
- Manifest: copy permissions, the `VIEW video/*` intent filter, `supportsPictureInPicture`, `configChanges`, foreground service type `mediaPlayback`.
- App icon, splash, edge-to-edge, orientation setup.
- **Done when:** an empty themed app launches with the right icon and splash.

### Phase 1 — Core and models
- Theme (every color token, light/dark, accent presets from `library_appearance_provider.dart`), utilities, data models.
- **Done when:** a theme preview screen matches the Flutter app side by side.

### Phase 2 — Data layer
- `MediaStoreRepository`, `FolderScanner`, the 3 scan modes, `DurationCache`, thumbnails, preferences, positions, recents.
- Unit tests for the scanner grouping, the duration cache, and the formatters.
- **Done when:** a debug screen lists every folder with correct counts, sizes and durations.

### Phase 3 — Library UI
- Home, Library (header, continue-watching row, folder cards, search, menu sheet, scan-mode sheet, permission prompts), Folder Videos (sort, search, select, share, rename, delete, details), Folder Picker.
- **Done when:** you can browse the whole library like in the Flutter app, without playback yet.

### Phase 4 — Playback engine and controller
- `ExoPlayerEngine` built from `ExoPlayerPlugin.ExoInstance`.
- `PlayerController`: open/resume, position saving, next/previous, auto-play countdown, loop modes, A-B repeat, sleep timer, speed, volume boost, audio and subtitle tracks, external subtitles, subtitle delay, fit modes, rotation modes.
- **Done when:** a basic player screen plays every test file that the Flutter build plays.

### Phase 5 — Player UI
- Video layer (fit, zoom, rotation), gesture layer, controls overlay (top bar, center, bottom bar, chips, slider), overlays (seek flash, swipe HUD, zoom indicator, lock, loading poster, error state, auto-play countdown, subtitle overlay with style presets), all sheets (speed, volume, audio track, subtitle and appearance, sleep timer).
- **Done when:** player gestures and animations feel the same as the Flutter app on the low-end device.

### Phase 6 — Background playback, mini player, PiP
- Media3 `MediaSessionService` (notification, lock screen, headset buttons), audio mode, `moveTaskToBack`, PiP, the global mini player with drag/restore/dismiss.
- **Done when:** playback survives leaving the app, locking the screen, and PiP, and all notification controls work.

### Phase 7 — Software fallback
- `MpvEngine` (or `VlcEngine`), wired through the same `videoUnsupported` signal and fallback logic as `PlayerNotifier._fallbackToSoftware`, plus the fallback poster art and software probes for duration and thumbnails.
- **Done when:** the 10-bit HEVC test file plays with a picture.

### Phase 8 — Secure Vault
- PIN setup/verify, biometric unlock, auto-lock, move in/out with bounded concurrency, vault list and options, FLAG_SECURE.
- If D1(a): `LegacyDataMigrator`, tested by installing the Kotlin build over the Flutter build that already has vault content.
- **Done when:** the vault works, and (if migrating) existing vault videos and the PIN still work after the update.

### Phase 9 — Parity check and release
- Go through the parity checklist below on 2–3 devices (low-end, mid, Android 14+).
- R8/ProGuard rules (Media3, libmpv/VLC JNI), ABI splits, signing, Baseline Profile for startup.
- Compare cold-start time and memory with the Flutter build.

---

## 6. Parity checklist (test both apps side by side)

- [ ] Library: 3 scan modes, live updates when files are added or deleted, cache-first instant display, "new" badges, continue watching, search
- [ ] Folder: every sort option, search, multi-select, share, rename (keeps the resume point), delete, details sheet, card styles
- [ ] Open with: cold start and already-running VIEW intents from file managers
- [ ] Player: resume dialog, double-tap ±seek with the custom 15 s icons, swipe brightness/volume, pinch zoom, hold for fast-forward, lock
- [ ] Player: fit modes, rotation modes, speed, volume boost to 200%, audio tracks, subtitles (embedded and external), subtitle delay and style presets
- [ ] Player: loop off/one/all, A-B repeat, sleep timer (duration and end of video), auto-play next with countdown
- [ ] Background: notification controls, lock screen, headset buttons, audio mode, PiP, mini player
- [ ] Fallback: undecodable file switches to software decoding with a picture
- [ ] Vault: PIN, biometrics, auto-lock, move in/out, screenshots blocked
- [ ] Theme: light/dark/system, accent presets, glass surfaces
- [ ] Performance: large folders (1000+ videos) on the low-end device, no stutter when opening

---

## 7. Main risks

1. **`PlayerNotifier` (1245 lines) holds most of the app's behaviour.** Port it in pieces, and read the `FIX #…` comments carefully: each one is a bug that was already fixed once.
2. **Vault data loss** if D1(a) is chosen without a tested migration.
3. **The fallback decoder** adds APK size and JNI complexity. Plan to ship an AAB or ABI-split APKs.
4. **Backdrop blur** only exists on API 31+. Older devices need a translucent fallback that still looks good.
5. **Gesture conflicts** in Compose (double-tap vs single-tap vs drag vs pinch on the same layer). Build the gesture layer as one `pointerInput` with explicit arbitration rather than stacking modifiers.

---

## 8. Model usage

- **Opus:** phases 0, 4, 5, 6, 7, and the migrator in phase 8. These are about architecture, timing and gestures, where subtle mistakes are costly.
- **Sonnet:** phases 1, 2, 3, and the vault UI in phase 8. Once the patterns exist, these are mostly mechanical conversions.
