# Pixel Pantry TV App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android TV app for a Sony Bravia (Google TV) that browses and plays the Pixel Pantry server's library like Netflix/Plex, with the remote mapping, subtitle flow and robustness the spec demands.

**Architecture:** Kotlin app, Compose for TV for every screen, Media3/ExoPlayer for direct-play. One `ApiClient` (OkHttp + kotlinx.serialization) talks to the server's `/api/*`; one `ServerResolver` finds the server (manual address → last known → mDNS) and verifies it with `GET /api/info`. Each screen is a `ViewModel` exposing a `StateFlow<UiState>`; all logic that can be unit-tested (API mapping, key handling, progress reporting, subtitle configuration, autoplay countdown) lives in plain Kotlin classes with no Android dependencies. UI is verified on an Android TV emulator by screenshots.

**Tech Stack:** Kotlin 2.1, AGP 8.7, Gradle 8.11, JDK 17, minSdk 28 / target+compile 34, Compose BOM 2025.01.00 + `androidx.tv:tv-material:1.0.0`, Media3 1.5.1 (`media3-exoplayer`, `media3-ui`), Coil 2.7 (`coil-compose`), OkHttp 4.12 + `okhttp-mockwebserver`, kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9 (+ `-test`), DataStore preferences 1.1.1, Navigation Compose 2.8.5, JUnit 4, Turbine 1.2. No Hilt/Dagger; a hand-written `AppContainer`.

**Spec:** `docs/superpowers/specs/2026-10-26-pixel-pantry-design.md` — sections "The TV app", "Phases", "Constraints and environment". Server API contract: `server/src/types.ts` and `server/docs/tv-client-notes.md` (read both; the plan quotes them but the files win).

## Global Constraints

- App lives in `tv/` (Gradle root), package `app.pixelpantry.tv`, application id `app.pixelpantry.tv`, app name `Pixel Pantry`. Commit straight to `main` (owner's standing instruction).
- Leanback launcher: `<category android:name="android.intent.category.LEANBACK_LAUNCHER"/>`, `android:banner` 320×180, `<uses-feature android:name="android.software.leanback" android:required="true"/>`, `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, `INTERNET` and `ACCESS_NETWORK_STATE` permissions, `android:usesCleartextTraffic="true"` (LAN HTTP).
- All user-visible strings English, in `res/values/strings.xml`.
- Every server URL is relative to the base (`http://<ip>:<port>`); never hard-code a host. Images: `poster` → `<base>/api/images/w500/...` already returned by the server as a path; the client only prefixes the base.
- Progress: `POST /api/items/:id/progress {position, duration, sessionId}` at most every 30 s while playing, once on pause and once on stop; always resend the returned `sessionId` for the same item; none on the first report of a new playback. Positions in seconds (Double).
- Settings come from `GET /api/settings` (`skipBackSeconds`, `skipForwardSeconds`, `autoplayNext`), defaults 10 / 10 / true; `PUT /api/settings` with a partial body. Cached in memory and in DataStore for offline start.
- Remote mapping in the player (spec): OK/Play-Pause toggles pause and shows controls; LEFT/RIGHT skip back/forward by the settings (hold repeats); UP/DOWN show controls and move focus; CH+/CH− next/previous episode immediately (save position first; toast names the episode); BACK hides controls if visible, else stops, saves position, returns to the show page. End of episode: autoplay next after a 10 s countdown toast, cancel with BACK, toggle in Settings.
- Subtitle ids: `embedded:<streamIndex>` and `sidecar:<URL-encoded basename>`; `subtitleChoice`/`audioChoice` restored on next play by id. Sub delay in ±0.5 s steps applies to sidecar tracks only (reload the sidecar URL with `?shift=<ms>`); resets per item.
- Watched threshold and continue-watching logic are server-side; the client never computes "watched".
- The app keeps no state besides the server address, cached settings and Coil's image cache. Clearing it loses nothing.
- Unreachable server → a screen with the address tried and a Retry button, never a blank screen or endless spinner. Playback errors show ExoPlayer's error text in plain words plus the file name.
- Unit tests run with `./gradlew :app:testDebugUnitTest`; the build is `./gradlew :app:assembleDebug`; both must pass before every commit. UI tasks are verified on the Android TV emulator (AVD `pantry_tv`, see Task 1) with `adb exec-out screencap -p > shot.png` and the screenshot read back.
- Toolchain on this Mac: JDK at `/opt/homebrew/opt/openjdk@17` (`JAVA_HOME`), SDK at `~/Library/Android/sdk` (`ANDROID_HOME`), `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`, emulator + `system-images;android-34;android-tv;arm64-v8a`.

## Review Focus

1. A server that answers mDNS but is not Pixel Pantry (another device on port 8765) must be rejected by the `/api/info` check and the app must fall through to the manual-address prompt. Pinned in Task 3.
2. Pressing BACK within the first 30 s of an autoplayed next episode must stop playback and return to the show page with the position saved, and must not trigger a second autoplay. Pinned in Task 9 (keys) and Task 11 (autoplay).
3. The server going away mid-playback (PC sleeps) must surface a readable error with the file name and a "Back" action within a few seconds, not an endless buffering spinner. Pinned in Task 8.
4. A show whose `seasons` list has only "Specials" (season 0) or a single season must still render the season tabs and play on OK. Pinned in Task 7.
5. Rapid repeated CH+ presses must not fire overlapping next-episode loads or lose the saved position of the item being left. Pinned in Task 9.

---

## File structure

```
tv/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew*  gradle/wrapper/
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/{values/strings.xml, values/themes.xml, drawable/banner.xml, drawable/ic_*.xml, mipmap-*/ic_launcher.png}
  app/src/main/kotlin/app/pixelpantry/tv/
    PantryApp.kt                 # Application; owns AppContainer
    MainActivity.kt              # ComponentActivity; sets NavGraph
    di/AppContainer.kt           # ApiClient, ServerStore, ServerResolver, SettingsRepo, ImageLoader, Json
    api/ApiModels.kt             # @Serializable mirrors of server/src/types.ts
    api/ApiClient.kt             # suspend fun per endpoint; baseUrl; resolve(relative)
    api/ApiException.kt
    server/ServerStore.kt        # DataStore: manualAddress, lastKnownAddress, cachedSettings
    server/Discovery.kt          # NsdManager → Flow<Candidate>
    server/ServerResolver.kt     # resolve(): manual → lastKnown → mDNS; verify /api/info
    settings/SettingsRepo.kt     # StateFlow<Settings>; refresh(); update(partial)
    util/Format.kt               # durations, "Ends at HH:MM", "23 min left", "S2 · E4"
    ui/theme/Theme.kt, Color.kt, Type.kt
    ui/nav/NavGraph.kt           # routes: connect, home/{category}, show/{id}, movie/{id}, player/{itemId}?startFromZero=, search, history, settings
    ui/components/Sidebar.kt, HeroStrip.kt, CardRow.kt, PosterCard.kt, LandscapeCard.kt, EpisodeCard.kt, ErrorScreen.kt, LoadingScreen.kt, ToastHost.kt, ConfirmDialog.kt, ActionMenu.kt, FocusableButton.kt
    ui/connect/ConnectScreen.kt, ConnectViewModel.kt
    ui/home/HomeScreen.kt, HomeViewModel.kt
    ui/show/ShowScreen.kt, ShowViewModel.kt      # also renders movies (no seasons)
    ui/search/SearchScreen.kt, SearchViewModel.kt
    ui/history/HistoryScreen.kt, HistoryViewModel.kt
    ui/settings/SettingsScreen.kt, SettingsViewModel.kt
    player/PlayerScreen.kt       # AndroidView(PlayerView) + PlayerOverlay + menus
    player/PlayerViewModel.kt    # loads ItemDetail, owns ExoPlayer, state machine
    player/PlayerKeys.kt         # pure key → PlayerAction mapping
    player/ProgressReporter.kt   # pure: when/what to report, sessionId handling
    player/SubtitlePlan.kt       # pure: ItemDetail + choice + shift → MediaItem config
    player/AutoplayCountdown.kt  # pure countdown state machine
    player/PlayerOverlay.kt, SubtitleMenu.kt, AudioMenu.kt, OnlineSubtitleSearch.kt
  app/src/test/kotlin/app/pixelpantry/tv/...   # JUnit tests mirroring the pure classes and ViewModels
  README.md                      # build, emulator, sideload to the Bravia
```

---

### Task 1: Project scaffold, emulator, hello screen

**Files:**
- Create: `tv/settings.gradle.kts`, `tv/build.gradle.kts`, `tv/gradle.properties`, `tv/gradle/libs.versions.toml`, Gradle wrapper, `tv/app/build.gradle.kts`, `tv/app/src/main/AndroidManifest.xml`, `res/values/strings.xml`, `res/values/themes.xml`, `res/drawable/banner.xml`, launcher icons (vector-generated PNGs are fine), `PantryApp.kt`, `MainActivity.kt`, `ui/theme/*`, `util/Format.kt`, `app/src/test/kotlin/app/pixelpantry/tv/util/FormatTest.kt`, `tv/README.md`
- Modify: root `.gitignore` (add `tv/.gradle/`, `tv/build/`, `tv/app/build/`, `tv/local.properties`, `tv/.idea/`, `*.iml`)

**Interfaces:**
- Produces: `util/Format.kt` — `fun formatRuntime(seconds: Int): String` (`"52 min"`, `"1 h 35 min"`), `fun formatRemaining(positionSec: Double, durationSec: Double): String` (`"23 min left"`, `"< 1 min left"`), `fun formatEndsAt(remainingSec: Double, now: LocalTime): String` (`"Ends at 22:41"`, 24 h clock), `fun formatEpisodeTag(season: Int?, episode: Int?, episodeEnd: Int?): String` (`"S2 · E4"`, `"S3 · E1–2"`, `""` for movies), `fun formatClock(seconds: Double): String` (`"32:04"`, `"1:02:04"`).
- The Compose TV theme: dark background `#0E0F13`, surface `#13151B`, accent `#E5A50A`, text `#E8E8EA` / secondary `#9A9CA6` (from the brainstorm mockups); typography default Compose TV.

- [ ] **Step 1: Toolchain check** — `JAVA_HOME=/opt/homebrew/opt/openjdk@17 java -version` prints 17; `~/Library/Android/sdk/platform-tools/adb version` works; if `emulator` or the TV system image is missing: `sdkmanager --install "emulator" "system-images;android-34;android-tv;arm64-v8a"` and `avdmanager create avd -n pantry_tv -k "system-images;android-34;android-tv;arm64-v8a" -d tv_1080p`. Write `tv/local.properties` with `sdk.dir`.
- [ ] **Step 2: Scaffold** the Gradle project with the version catalog pinned to the Tech Stack versions; `app/build.gradle.kts` with `compose = true`, Kotlin compose plugin, `testOptions.unitTests.isReturnDefaultValues = true`; manifest per Global Constraints; `MainActivity` showing centered text "Pixel Pantry" on the dark theme.
- [ ] **Step 3: Write the failing test** `FormatTest` with the exact examples above (one `@Test` per function).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → FAIL (unresolved).
- [ ] **Step 5: Implement `Format.kt`.**
- [ ] **Step 6: Run** tests → PASS; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 7: Emulator smoke** — start `emulator -avd pantry_tv -no-snapshot -no-audio &`, `adb wait-for-device`, `adb install -r app/build/outputs/apk/debug/app-debug.apk`, `adb shell monkey -p app.pixelpantry.tv 1`, screenshot; the dark screen with "Pixel Pantry" is visible. Record the commands in `tv/README.md` together with the Bravia sideload steps (Developer options → Network debugging; `adb connect <tv-ip>:5555`; `adb install -r`).
- [ ] **Step 8: Commit** — `git add -A tv .gitignore && git commit -m "feat(tv): project scaffold, theme, format utils"`

---

### Task 2: API models and client

**Files:**
- Create: `api/ApiModels.kt`, `api/ApiClient.kt`, `api/ApiException.kt`, `test/.../api/ApiClientTest.kt`

**Interfaces:**
- Produces (`ApiModels.kt`, all `@Serializable`, field names identical to `server/src/types.ts`): `Progress`, `ItemSummary`, `ShowSummary`, `Card` (sealed: `ShowCard(show, nextUp?)`, `ItemCard(item, reason?)` — custom serializer on the `kind` discriminator), `Row`, `CategoryPage`, `SeasonDetail`, `ShowDetail`, `ItemDetail`, `Track` (`index, codec, language?, title?, channels?, default, forced`), `SubtitleTrack`, `SearchResponse`, `HistoryEntry` (`sessionId, itemId, startedAt, endedAt, secondsWatched`), `HistoryResponse`, `Stats` (`hoursWeek, hoursMonth, hoursAll, episodesAll, moviesAll, topShowMonth?{showId,title,hours}`), `ProgressResponse`, `DeleteResponse`, `SubtitleListResponse`, `OsResult` (`fileId, release, hashMatch, downloads, language, fps?`), `SubtitleSearchResponse`, `InfoResponse`, `Settings`, `SettingsPatch` (all nullable), `ScanStatus`.
- Produces (`ApiClient.kt`): `class ApiClient(private val http: OkHttpClient, private val json: Json) { var baseUrl: String; fun resolve(relative: String): String; suspend fun info(): InfoResponse; suspend fun home(): CategoryPage; suspend fun category(c: String): CategoryPage; suspend fun show(id: Int): ShowDetail; suspend fun item(id: Int): ItemDetail; suspend fun search(q: String): SearchResponse; suspend fun progress(itemId: Int, position: Double, duration: Double, sessionId: Int?): ProgressResponse; suspend fun setWatched(itemId: Int, watched: Boolean); suspend fun setShowWatched(showId: Int, watched: Boolean, season: Int? = null); suspend fun setTracks(itemId: Int, subtitle: String?, audio: String?); suspend fun subtitles(itemId: Int): SubtitleListResponse; suspend fun searchSubtitles(itemId: Int): SubtitleSearchResponse; suspend fun downloadSubtitle(itemId: Int, fileId: Long): SubtitleTrack; suspend fun deleteShow(id: Int): DeleteResponse; suspend fun deleteMovie(id: Int): DeleteResponse; suspend fun history(limit: Int = 200): HistoryResponse; suspend fun stats(): Stats; suspend fun settings(): Settings; suspend fun updateSettings(p: SettingsPatch): Settings; suspend fun scan(): ScanStatus; suspend fun scanStatus(): ScanStatus }` — all run on `Dispatchers.IO`; non-2xx → `ApiException(status, message /* server's error field or HTTP reason */)`; connection failures → `ApiException(0, "Cannot reach <baseUrl>")`. `Json { ignoreUnknownKeys = true; explicitNulls = false }`. OkHttp timeouts: connect 5 s, read 30 s.

- [ ] **Step 1: Write failing tests** (MockWebServer): `resolve` prefixes relative paths and leaves absolute URLs alone; `home()` parses a fixture with one `show` card and one `item` card with `reason`; `item()` parses `audio`/`subtitles`; `progress()` posts the exact JSON body and parses `sessionId`; a 404 `{error:"Not found"}` throws `ApiException(404, "Not found")`; a closed server throws `ApiException(0, …)` containing the base URL; `updateSettings(SettingsPatch(skipBackSeconds = 15))` sends only that key.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `assembleDebug` OK.
- [ ] **Step 5: Commit** — `feat(tv): api models and client`

---

### Task 3: Server store, discovery, resolver

**Files:**
- Create: `server/ServerStore.kt`, `server/Discovery.kt`, `server/ServerResolver.kt`, `di/AppContainer.kt`, `test/.../server/ServerResolverTest.kt`, `test/.../server/ServerStoreTest.kt`

**Interfaces:**
- `ServerStore(dataStore)`: `val manualAddress: Flow<String?>`, `val lastKnownAddress: Flow<String?>`, `val cachedSettings: Flow<Settings?>`, `suspend fun setManualAddress(v: String?)`, `setLastKnownAddress(v: String)`, `setCachedSettings(s: Settings)`. Address format `host:port`. Test with an in-memory `DataStore` (`PreferenceDataStoreFactory.create` on a temp file).
- `Discovery` interface: `fun candidates(timeoutMs: Long): Flow<Candidate>` where `data class Candidate(val host: String, val port: Int)`; `NsdDiscovery(context)` implements it for service type `_pixelpantry._tcp.` (note trailing dot) resolving each service; errors complete the flow quietly.
- `ServerResolver(store, discovery, probe: suspend (baseUrl: String) -> Boolean)`: `suspend fun resolve(): Result` where `sealed class Result { data class Found(val baseUrl: String, val source: Source) ; data class NotFound(val tried: List<String>) }`, `enum Source { MANUAL, LAST_KNOWN, MDNS }`. Order: manual (if set, only this; failure → NotFound listing it), then lastKnown, then every mDNS candidate for up to 5 s; `probe` = `ApiClient.info()` returns `name == "Pixel Pantry"`; the first success is stored as lastKnown. `baseUrl` = `http://host:port`.
- `AppContainer(app)`: lazily builds `OkHttpClient`, `Json`, `ApiClient`, `ServerStore`, `NsdDiscovery`, `ServerResolver`, `SettingsRepo` (Task 4), Coil `ImageLoader` with a 100 MB disk cache.

- [ ] **Step 1: Write failing tests** (fake discovery + fake probe): manual set and reachable → `Found(MANUAL)`; manual set but probe false → `NotFound(tried=[manual])` and mDNS never consulted; no manual, lastKnown fails, mDNS yields two candidates of which the first probes false (a non-Pantry device on 8765 — Review Focus 1) and the second true → `Found(MDNS)` with the second and lastKnown updated; nothing found → `NotFound` listing lastKnown and both candidates. Store round-trips.
- [ ] **Step 2–5:** fail → implement → pass → `feat(tv): server store, mdns discovery, resolver`

---

### Task 4: Settings repository

**Files:**
- Create: `settings/SettingsRepo.kt`, `test/.../settings/SettingsRepoTest.kt`

**Interfaces:**
- `SettingsRepo(api: ApiClient, store: ServerStore, scope: CoroutineScope)`: `val settings: StateFlow<Settings>` (initial = cached or defaults 10/10/true), `suspend fun refresh()` (GET, cache), `suspend fun update(patch: SettingsPatch)` (PUT, then emit and cache; on failure keep the old value and rethrow).

- [ ] **Step 1: Tests** (MockWebServer + in-memory store, `runTest`): initial defaults; `refresh` emits server values and caches them; `update` emits the server's returned object; a failed `update` leaves the flow unchanged.
- [ ] **Step 2–5:** fail → implement → pass → `feat(tv): settings repository`

---

### Task 5: App shell: navigation, sidebar, connect screen

**Files:**
- Create: `ui/nav/NavGraph.kt`, `ui/components/Sidebar.kt`, `ui/components/ErrorScreen.kt`, `ui/components/LoadingScreen.kt`, `ui/components/ToastHost.kt`, `ui/components/FocusableButton.kt`, `ui/connect/ConnectScreen.kt`, `ui/connect/ConnectViewModel.kt`, `test/.../ui/connect/ConnectViewModelTest.kt`
- Modify: `MainActivity.kt`

**Interfaces:**
- Routes (string constants in `NavGraph.kt`): `connect`, `home/{category}` (`home|tv|anime|movies`), `show/{id}`, `movie/{id}`, `player/{itemId}?fromStart={bool}`, `search`, `history`, `settings`.
- `Sidebar(selected: SidebarEntry, onSelect: (SidebarEntry) -> Unit, modifier)`: entries Home, TV, Anime, Movies, Search, History, then bottom group Rescan, Settings; collapsed to icons (56 dp) when focus is outside it, expands to 220 dp with labels when any entry has focus; LEFT from the first column of content moves focus onto the selected entry; selecting an entry moves focus back into content. Rescan is an action (calls `onRescan`), not a route.
- `ConnectViewModel(resolver, api)`: `UiState { Resolving(triedSoFar), Failed(tried: List<String>, manualDraft: String), Ready }`; `retry()`, `submitManual(address: String)` (validates `host:port`, probes, stores). `ConnectScreen` shows a spinner with "Looking for Pixel Pantry…", on failure the list of addresses tried, a text field for `host:port` (on-screen keyboard) and buttons Retry / Connect. On `Ready` navigate to `home/home` and set `api.baseUrl`.
- `ToastHost` + `Toaster.show(text, durationMs = 3000)` (a `StateFlow` in `AppContainer`) rendered top-right.
- `ErrorScreen(title, detail, primary: ButtonSpec, secondary: ButtonSpec?)`.

- [ ] **Step 1: ConnectViewModel tests** (fake resolver): resolving → Ready sets baseUrl; NotFound → Failed with the tried list; `submitManual("192.168.1.20:8765")` probes and on success stores + Ready; invalid input → stays Failed with an error message.
- [ ] **Step 2–4:** fail → implement all files → pass; `assembleDebug`.
- [ ] **Step 5: Emulator check** — with the server NOT running: Connect screen shows the tried addresses and the manual field; type `10.0.2.2:8765` (emulator → host) with the dev server running on this Mac (`cd server && PANTRY_DATA_DIR=/tmp/pantry-dev bun run src/index.ts`, media root pointing at a small test folder with a couple of empty `.mkv` files is enough for navigation; see `server/README.md`) → navigates to Home (an empty shell with the sidebar is fine at this point). Screenshot both states.
- [ ] **Step 6: Commit** — `feat(tv): navigation, sidebar, connect screen`

---

### Task 6: Home screen

**Files:**
- Create: `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `ui/components/HeroStrip.kt`, `ui/components/CardRow.kt`, `ui/components/PosterCard.kt`, `ui/components/LandscapeCard.kt`, `test/.../ui/home/HomeViewModelTest.kt`

**Interfaces:**
- `HomeViewModel(api, category: String, toaster)`: `UiState { Loading, Error(message), Loaded(rows: List<Row>, focused: Card?) }`; `load()`, `onFocus(card)`, `rescan()` (POST `/api/scan`, toast "Scanning…", poll `scanStatus()` every 2 s until idle, then `load()` and toast "Library updated"), `open(card)` → nav event: `ItemCard` in the `continue` row → `player/{id}`; `ItemCard` elsewhere (movie) → `movie/{id}`; `ShowCard` → `show/{id}`.
- `HeroStrip(card: Card?)`: backdrop (w1280) faded, title, for items `showTitle · S2 · E4 · <title>` and `"23 min left"` or `"Next: S1 · E7"`, for shows year · episode count · `<unwatched> unwatched`.
- `CardRow(title, cards, onFocus, onClick, onLongClick)`: `LazyRow` with pivot bring-into-view (focused card ~15 % from the left), focus restorer; `PosterCard` (2:3, w500) for shows/movies with title below; `LandscapeCard` (16:9, episode `still` or show backdrop, progress bar from `progress.position/duration`, tag `S2 · E4`, subtitle `23 min left` / `Next`) for continue-row items. Both show a 3 dp white focus ring and scale 1.05.
- `HomeScreen` layout: Sidebar | Column(HeroStrip, rows). Empty library → centered text "No videos yet. Add files on the PC and press Rescan." with a focused Rescan button.

- [ ] **Step 1: HomeViewModel tests** (MockWebServer or fake api): Loaded rows from fixture; `open` events per card kind; `rescan` posts, polls twice (scanning → idle), reloads, toasts in order; error → `Error` with the message.
- [ ] **Step 2–4:** fail → implement → pass.
- [ ] **Step 5: Emulator check** against the dev server with a seeded library (use `server/test` fixtures approach or a folder of empty files named like real releases; TMDB key optional — unmatched items render with text only). Screenshot Home and TV category; D-pad LEFT reaches the sidebar; posters load (Coil).
- [ ] **Step 6: Commit** — `feat(tv): home screen with hero and rows`

---

### Task 7: Show and movie pages, actions

**Files:**
- Create: `ui/show/ShowScreen.kt`, `ui/show/ShowViewModel.kt`, `ui/components/EpisodeCard.kt`, `ui/components/ActionMenu.kt`, `ui/components/ConfirmDialog.kt`, `test/.../ui/show/ShowViewModelTest.kt`

**Interfaces:**
- `ShowViewModel(api, showId: Int?, movieItemId: Int?, toaster)`: `UiState.Loaded(show: ShowSummary?, movie: ItemSummary?, seasons: List<SeasonDetail>, selectedSeason: Int, resume: ItemSummary?, fileCount: Int)`; `selectSeason(n)`, `play(item, fromStart: Boolean)` → nav `player/{id}?fromStart=`, `primaryAction()` (resume item or first unwatched, or the movie), `markWatched(item, watched)`, `markSeasonWatched(season, watched)`, `markShowWatched(watched)`, `deleteFromPc()` (DELETE show or movie; toast "Moved to Recycle Bin"; nav back), all re-`load()` after a write.
- Page: backdrop + poster + title/year/counts/rating/genres/overview; primary button text `"Resume S2 E4 · 23 min left"` / `"Play S1 E1"` / `"Resume"`/`"Play"` for movies; secondary `"Mark season watched"` (or `"… unwatched"` when all watched); `"⋯"` button opens `ActionMenu` [Mark show watched/unwatched, Delete from PC]. Season tabs (LEFT/RIGHT when focused; `Specials` last); episode row of `EpisodeCard` (still, `N. Title`, runtime, ✓ when watched, progress bar when in progress). OK on an episode plays; long-press OK or MENU opens `ActionMenu` [Mark watched/unwatched, Play from start]. `ConfirmDialog` for delete: title `Delete "<show>" from the PC?`, body `<fileCount> files will be moved to the Recycle Bin.`, buttons Cancel (focused by default) / Delete.
- Movie route renders the same screen without the seasons section.

- [ ] **Step 1: ShowViewModel tests:** Loaded with seasons sorted Specials-last and `selectedSeason` = the resume item's season (else first); `primaryAction` label/texts for resume, first-unwatched, all-watched; `markSeasonWatched` posts `{watched, season}` then reloads; `deleteFromPc` calls DELETE and emits a back-nav event; a show with only Specials still Loaded with `selectedSeason = 0` (Review Focus 4); a movie id loads via `item()` with no seasons.
- [ ] **Step 2–4:** fail → implement → pass.
- [ ] **Step 5: Emulator check:** show page with two seasons, tab switching, long-press menu, delete dialog (cancel), movie page. Screenshots.
- [ ] **Step 6: Commit** — `feat(tv): show and movie pages with actions`

---

### Task 8: Player core and progress reporting

**Files:**
- Create: `player/PlayerScreen.kt`, `player/PlayerViewModel.kt`, `player/PlayerOverlay.kt`, `player/ProgressReporter.kt`, `test/.../player/ProgressReporterTest.kt`, `test/.../player/PlayerViewModelTest.kt`

**Interfaces:**
- `ProgressReporter(api, clock: () -> Long, intervalMs = 30_000)`: `fun start(itemId)` (new session), `suspend fun tick(positionSec, durationSec)` (sends only if ≥ interval since the last send), `suspend fun flush(positionSec, durationSec, reason: PAUSE|STOP|SWITCH)` (always sends), keeps `sessionId` per item, resets on `start`. Never throws (errors logged, swallowed).
- `PlayerViewModel(api, settingsRepo, reporter, toaster, itemId: Int, fromStart: Boolean)`: `UiState { Loading, Error(title, detail, fileName), Playing(detail: ItemDetail, positionMs, durationMs, paused, overlayVisible, buffering, audioLabel, subtitleLabel, subDelayMs) }`; owns the `ExoPlayer` (created in `init`, released in `onCleared`); builds the `MediaItem` from `api.resolve(detail.streamUrl)` with subtitle configurations from Task 10's `SubtitlePlan` (stub: embedded only in this task); seeks to `item.progress.position` unless `fromStart` or the item is watched (then 0); `togglePause()`, `seekBy(seconds)`, `seekTo(ms)`, `showOverlay()` (auto-hide after 4 s while playing), `stopAndExit()` → `reporter.flush(STOP)` then nav back; player `onPlayerError` → `Error(exoplayer message, fileName)`; `onIsPlayingChanged(false)` due to pause → `flush(PAUSE)`; a 500 ms position poller feeds `tick`. On `Playing` the `PlayerView` has `useController = false`; subtitles render through `PlayerView`'s `SubtitleView`.
- `PlayerOverlay(state, onAction)`: title line `showTitle · S2 E4 · title` (or movie title), `Ends at HH:MM`, seek bar with knob, elapsed / `-remaining`, buttons row `⏮ Prev`, `⏸ Pause/▶ Play`, `⏭ Next episode` and on the right `Audio: <label>`, `Subtitles: <label>`, `Sub delay: +0.5s` (menus wired in Task 10). Prev/Next disabled (not focusable) when `detail.prev/next` is null.
- Error state: `ErrorScreen("Playback failed", "<exo message>\n<fileName>", primary = Back)` (Review Focus 3: a lost connection surfaces here through ExoPlayer's `HttpDataSource` error within its default timeouts; set `DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(10000)`).

- [ ] **Step 1: ProgressReporter tests** (`runTest`, fake clock, MockWebServer): first `tick` sends with no `sessionId`, second within 30 s does not send, third after 30 s sends with the returned `sessionId`; `flush` always sends; `start` on a new item drops the old `sessionId`; a 500 from the server does not throw and the next report has no `sessionId`.
- [ ] **Step 2: PlayerViewModel tests** (fake `ExoPlayer` behind a small `PlayerPort` interface so the VM is unit-testable: `prepare(mediaItem)`, `seekTo`, `play/pause`, `currentPosition`, `duration`, listener hooks): loads detail and seeks to the saved position; `fromStart` seeks 0; watched item seeks 0; `togglePause` flushes PAUSE; `stopAndExit` flushes STOP and emits nav back; player error → `Error` with file name; overlay auto-hides after 4 s (virtual time).
- [ ] **Step 3–5:** fail → implement (VM, PlayerPort + `ExoPlayerPort` adapter, screen, overlay) → pass; `assembleDebug`.
- [ ] **Step 6: Emulator check:** play a real small MP4 from the dev server (generate one with `ffmpeg-static` as in `server/test/probe.test.ts`, 30 s, with burned-in timer: `-vf drawtext=text='%{pts\:hms}'`): video plays, overlay appears on OK and hides, seek bar moves, pause works, BACK returns and the show page shows the progress bar. Stop the dev server mid-play → error screen with file name within ~15 s. Screenshots.
- [ ] **Step 7: Commit** — `feat(tv): player core with progress reporting`

---

### Task 9: Remote key mapping in the player

**Files:**
- Create: `player/PlayerKeys.kt`, `test/.../player/PlayerKeysTest.kt`
- Modify: `player/PlayerScreen.kt` (root `onPreviewKeyEvent`), `player/PlayerViewModel.kt` (`next()`, `prev()`, hold-repeat seek)

**Interfaces:**
- `PlayerKeys`: pure `fun map(key: KeyEventLike, overlayVisible: Boolean, focusOnOverlay: Boolean, countdownActive: Boolean): PlayerAction?` where `KeyEventLike(code: KeyCode, isDown: Boolean, repeatCount: Int)`, `enum KeyCode { DPAD_CENTER, ENTER, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE, DPAD_LEFT, DPAD_RIGHT, DPAD_UP, DPAD_DOWN, CHANNEL_UP, CHANNEL_DOWN, MEDIA_NEXT, MEDIA_PREVIOUS, BACK, MENU, OTHER }`, `sealed class PlayerAction { TogglePause, SkipBack, SkipForward, ShowOverlay, Next, Prev, HideOverlay, StopAndExit, CancelCountdown, PassThrough }`. Rules: when the overlay is visible and a button has focus, DPAD keys pass through to Compose focus (`PassThrough`) except LEFT/RIGHT on the seek bar which skip; when the overlay is hidden LEFT/RIGHT → skip (repeat allowed, `repeatCount > 0` still skips), UP/DOWN → ShowOverlay, CENTER/ENTER/PLAY_PAUSE → TogglePause (+ShowOverlay); CHANNEL_UP/MEDIA_NEXT → Next, CHANNEL_DOWN/MEDIA_PREVIOUS → Prev always; BACK → CancelCountdown when `countdownActive`, else HideOverlay when visible, else StopAndExit. Only `isDown` events act; key-up → null.
- `PlayerViewModel.next()`/`prev()`: guarded by an `isSwitching` flag (ignore while a switch is in flight — Review Focus 5); `reporter.flush(SWITCH)` for the current item, then load the neighbour's `ItemDetail`, `reporter.start(newId)`, prepare and play from its saved position (or 0 if watched), toast `Next episode: 5. <title>` / `Previous episode: …`. Skip amounts come from `settingsRepo.settings.value`.

- [ ] **Step 1: PlayerKeys tests**, one per rule above, plus: key-up ignored; repeat LEFT skips; BACK with countdown → CancelCountdown; BACK overlay hidden → StopAndExit (Review Focus 2).
- [ ] **Step 2: PlayerViewModel tests:** `next()` flushes SWITCH for the old item, starts a new session, plays the next from its saved position; three rapid `next()` calls perform one switch (Review Focus 5); `next()` with `detail.next == null` is a no-op with toast "No next episode".
- [ ] **Step 3–5:** fail → implement → pass.
- [ ] **Step 6: Emulator check:** `adb shell input keyevent KEYCODE_CHANNEL_UP` switches episode with the toast; LEFT/RIGHT skip by 10 s (watch the burned-in timer); BACK semantics. Screenshots.
- [ ] **Step 7: Commit** — `feat(tv): remote key mapping, next/previous episode`

---

### Task 10: Audio, subtitles, online search, sub delay

**Files:**
- Create: `player/SubtitlePlan.kt`, `player/SubtitleMenu.kt`, `player/AudioMenu.kt`, `player/OnlineSubtitleSearch.kt`, `test/.../player/SubtitlePlanTest.kt`
- Modify: `player/PlayerViewModel.kt`, `player/PlayerOverlay.kt`

**Interfaces:**
- `SubtitlePlan` (pure): `fun build(detail: ItemDetail, base: (String) -> String, choice: String?, shiftMs: Int): Plan` where `Plan(subtitleConfigs: List<SubtitleConfig>, selectedEmbeddedIndex: Int?, selectedSidecarUri: String?, audioChoice: String?)`; `SubtitleConfig(uri: String, mimeType: String /* application/x-subrip, text/vtt, text/x-ssa */, label: String, language: String?, id: String, selected: Boolean)`; sidecar URIs are `base(track.url)` plus `?shift=<ms>` when `shiftMs != 0`; the chosen sidecar gets `SELECTION_FLAG_DEFAULT`; embedded choice is applied through `TrackSelectionParameters` by stream index after prepare (the plan exposes the index). Choice id `"off"` disables text tracks.
- `PlayerViewModel` additions: `subtitleTracks: List<SubtitleTrack>` (from detail; refreshed after a download), `selectSubtitle(id: String | "off")` (rebuild the MediaItem at the current position without stopping the audio longer than a re-prepare; persist via `api.setTracks(subtitle = id)`), `selectAudio(id: "audio:<index>")` (track selection + persist), `adjustSubDelay(deltaMs: ±500)` (sidecar only; rebuild with the new shift; resets to 0 when the item changes), `searchOnline()` → `OnlineState { Searching, Results(list), Error(message) }`, `download(fileId)` → applies the returned track immediately via `selectSubtitle`. On load, restore `detail.subtitleChoice`/`audioChoice` when they match a current track id, else fall back to the first `default` embedded subtitle, else off.
- `SubtitleMenu`: list = embedded tracks, sidecar tracks, `Off`, then `Search online…`; `OnlineSubtitleSearch`: results list `release · ↓downloads · ✓ hash match` sorted as returned; OK downloads and applies, menu stays open so another pick is one click away; errors inline (`OpenSubtitles not configured` text from the server). `AudioMenu`: embedded audio tracks labelled `language · codec · channels`. Sub delay button: LEFT/RIGHT on it changes ±0.5 s, label `Sub delay: +0.5s`; disabled for embedded tracks.

- [ ] **Step 1: SubtitlePlan tests:** sidecar srt → `application/x-subrip` with the base-prefixed URI; shift 500 appends `?shift=500` only to the chosen sidecar; choice `embedded:3` → `selectedEmbeddedIndex = 3` and no sidecar selected; choice `off` → nothing selected; an unknown stored choice falls back to the default embedded track; ass → `text/x-ssa`.
- [ ] **Step 2: PlayerViewModel tests:** `selectSubtitle("sidecar:x.srt")` persists via `setTracks` and keeps the position; `adjustSubDelay(+500)` on a sidecar rebuilds with shift 500 and is ignored for embedded; `download(42)` calls the API, refreshes tracks and selects the new id; `searchOnline` error maps the 503 message.
- [ ] **Step 3–5:** fail → implement → pass.
- [ ] **Step 6: Emulator check:** play a file with a sidecar `.srt` from the dev server; subtitle menu lists it; select → subtitles render; sub delay ±; "Search online" shows the not-configured message (no key in dev). Screenshots.
- [ ] **Step 7: Commit** — `feat(tv): subtitle and audio menus, online search, sub delay`

---

### Task 11: Autoplay next with countdown

**Files:**
- Create: `player/AutoplayCountdown.kt`, `test/.../player/AutoplayCountdownTest.kt`
- Modify: `player/PlayerViewModel.kt`, `player/PlayerOverlay.kt`

**Interfaces:**
- `AutoplayCountdown` (pure): `fun onEnded(hasNext: Boolean, autoplayEnabled: Boolean): State` → `Idle | Counting(secondsLeft = 10) | Finished`; `fun tick(): State`; `fun cancel(): State(Idle)`; `fun reset()`.
- VM: on `Player.STATE_ENDED` → `reporter.flush(STOP)` (position = duration), start the countdown when `settings.autoplayNext && detail.next != null`; toast-style banner "Next episode in 10 s… press BACK to cancel" (updates each second); at 0 → `next()`; `cancel` (BACK) leaves the player on the ended frame with the overlay shown; when autoplay is off or there is no next → show overlay with Next/Back focused. Cancel must also guard that a subsequent `next()` from a stale countdown cannot fire (Review Focus 2).

- [ ] **Step 1: Countdown tests:** 10 ticks → Finished; cancel at 3 → Idle and further ticks stay Idle; `onEnded(hasNext=false)` → Idle; autoplay off → Idle.
- [ ] **Step 2: VM test:** ENDED with next and autoplay on starts the countdown and after 10 virtual seconds switches; BACK during countdown cancels and a later virtual tick does not switch.
- [ ] **Step 3–5:** fail → implement → pass. **Step 6:** emulator check with a 30 s clip: countdown banner appears and the next episode starts; BACK cancels. **Step 7: Commit** — `feat(tv): autoplay next episode with countdown`

---

### Task 12: Search, History, Settings screens

**Files:**
- Create: `ui/search/SearchScreen.kt`, `ui/search/SearchViewModel.kt`, `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, tests for the three ViewModels

**Interfaces:**
- `SearchViewModel(api)`: `query: StateFlow<String>`, `results: StateFlow<SearchResponse?>`; debounce 300 ms, min 2 chars; `setQuery`, `open(card)`. Screen: text field (on-screen keyboard), two rows "Shows" / "Movies".
- `HistoryViewModel(api)`: `Loaded(stats: Stats, days: List<DayGroup(dateLabel, entries))` grouped by local date (`Today`, `Yesterday`, else `Mon 21 Oct`); entry line `still · showTitle · S2 · E4 · title · "32 min watched" · HH:MM`; OK → show/movie page. Stat tiles: `Hours this week`, `Hours this month`, `Hours all time`, `Episodes`, `Movies`, `Top show this month` (`title · N h`).
- `SettingsViewModel(settingsRepo, store, resolver)`: rows `Skip back (s)` and `Skip forward (s)` as steppers (LEFT/RIGHT ±5, range 5–120), `Auto-play next episode` toggle, `Server address` (current base URL; "Change…" opens the manual entry; "Use automatic discovery" clears the manual address and re-resolves), `App version`. Changes save immediately via `settingsRepo.update`.

- [ ] **Step 1: Tests:** search debounces and ignores 1-char queries; history groups by day with the three labels; settings stepper clamps to 5–120 and calls `update` with only the changed key; clearing the manual address re-resolves.
- [ ] **Step 2–4:** fail → implement → pass. **Step 5:** emulator screenshots of all three. **Step 6: Commit** — `feat(tv): search, history and settings screens`

---

### Task 13: Robustness pass and sideload

**Files:**
- Modify: `ui/nav/NavGraph.kt`, `ui/components/ErrorScreen.kt`, ViewModels as needed, `tv/README.md`

**Interfaces:**
- A global `ApiException(0, …)` from any screen's load → that screen's `ErrorScreen("Cannot reach the server", "Tried <baseUrl>", Retry, "Change server" → `connect`)`; retries re-run the resolver once before failing.
- Startup with cached `lastKnownAddress` goes straight to Home while re-verifying in the background; if verification fails, show a non-blocking toast and keep working until a request fails.
- Coil: `crossfade`, placeholders (grey card), `error` fallback (title-only card) so unmatched items look intentional.
- Release build: `isMinifyEnabled = false` for now (debuggable sideload), `versionName = "0.1"`; `./gradlew :app:assembleRelease` with a debug-signed release is acceptable for sideloading (document); README gets the Bravia steps verified against the emulator commands.

- [ ] **Step 1: Tests:** a ViewModel receiving `ApiException(0)` emits the unreachable error state with the base URL; startup with lastKnown goes to Home and a failed background verify toasts.
- [ ] **Step 2–4:** fail → implement → pass; `assembleDebug` and `assembleRelease` succeed.
- [ ] **Step 5: Emulator full walkthrough** (dev server with seeded library): connect → home → show → play → CH+ → subtitles → BACK → history shows the session → settings change skip to 15 and verify RIGHT skips 15 s. Kill the server during Home → unreachable screen → Retry after restart recovers. Screenshots at each step.
- [ ] **Step 6: Commit** — `feat(tv): robustness pass, release build, sideload docs`

---

## Self-review notes

- Spec coverage: navigation/sidebar/hero/rows (5, 6), show page + actions + delete (7), player + controls + remote mapping (8, 9), subtitles incl. online search and delay, audio, remembered choices (10), autoplay (11), search/history/settings incl. skip lengths and server address (12), discovery with manual fallback (3), unreachable/playback error handling and "no state in the app" (3, 5, 13), progress reporting contract (8). Phase-2 items untouched.
- Not verifiable here: the real Bravia (codec support, remote key codes for CH+/CH−, mDNS on the home network). The emulator proves the flows; the first real run is on the TV with `adb connect`.
- Left to the builder: exact Compose focus plumbing, Coil request sizing, how `PlayerPort` wraps ExoPlayer, string copy beyond the quoted labels.
