# TV app: deferred findings and rulings (2026-10-26)

Collected from the execution ledger of docs/superpowers/plans/2026-10-26-pixel-pantry-tv.md. Minor items the reviewers deferred, plus the rulings made during execution.

## Deferred minors
- Task 1: minor (deferred): formatEndsAt formatter lacks Locale.ROOT; untested episodeEnd==episode / season-only / "2 h 0 min"; Format strings hard-coded (pure functions, accepted); duplicated colors; surfaceVariant == surface; README assumes cwd tv/.
- Task 2: minor (deferred): unknown card kind fails whole page (untested); resolve startsWith("http") loose; OkHttp call not cancelled on coroutine cancel; defaultHttpClient/defaultJson untested.
- Task 3+4: minor (deferred): NSD queue keeps draining after awaitClose (clear pending + closed flag); IPv4 preference only brackets IPv6 and strips scope id; no per-resolve timeout; apiProbe mutates shared baseUrl during probe. CARRY to Task 5: SettingsRepo seed job must catch IOException from DataStore (uncaught in SupervisorJob scope would crash the app).
- Task 5: minor (deferred): runCatching around setManualAddress swallows CancellationException (cancelled submit can publish Ready); isValidAddress loose; ToastHost writes remembered array in composition; MockWebServer tests not pure virtual-time; SettingsRepo cache WRITE still unguarded (carry to the first task calling refresh/update = Task 6/12); draft not prefilled with stored manual address.
- Task 6: minor (deferred): scanner poll catches only ApiException (other throwables uncaught in container scope → crash; carry to Task 13 robustness: add CoroutineExceptionHandler to container.scope); "Library updated" toast shown even if reload fails; transient failure on resume reload replaces loaded content with Error screen (Task 13: keep content, toast error); Compose 1.7.6 focus-forward workaround pinned to BOM; row recomposition on every D-pad move; real artwork unverified (no TMDB key on dev server).
- Task 7: minor (deferred): two reload tests race the mock counter (can pass vacuously); long-press release may fire onClick on the new menu (verify on real remote; else open on KeyUp); focus returns to primary button after playback (not the episode); delete in flight can be lost if Play pressed (disable actions while deleting); Specials-only test should use primaryAction(); tabs keyed by index; sidebar highlight from previousBackStackEntry. Server-side gap noted: movie delete dialog always says "1 file" (ItemDetail has no file count).
- Task 8: minor (deferred): LoadErrorBudget should also start a new run when bytesLoaded < last (seek creates a new loadable); after ENDED play() does nothing until seekTo(0) (Task 11); player-initiated resume does not restart the hide timer; LEFT/RIGHT after a skip moves button focus instead of skipping again (Task 9 owns mapping: land focus on the seek bar after a skip); ExoPlayerPort.failed plain var; dead-server error ~15 s.
- Task 9: minor (deferred): focus gap between overlay-show and Pause focus lets a quick LEFT/RIGHT skip; key-up classification uses release-time state (comment); tests mix virtual time with real-time waits; hold-repeat unverified on real remote.
- Task 10: minor (deferred): parse (not load) failures of sidecars are silent; downloadJob not cancelled on item switch; in-flight download test may pass without the guard; sidecars rely on deprecated Media3 legacy decoding (recheck on upgrade); forced-only tracks end up Off; cues overlap the overlay buttons; server could send codec/default on SubtitleTrack. Embedded selection unverified on device (no embedded-sub media).
- Task 11: minor (deferred): banner and toast share the top-right corner; unknown duration at ENDED sends no report; OK on a hidden overlay after the end replays (intended); no test for a second ENDED or RIGHT-at-end no-op.
- Task 12: minor (deferred): "N min watched" in Format.kt not strings.xml; stale revert if a debounced PUT is in flight when a newer one fails; cancelled discovery doesn't restore manual address (unreachable via UI); real-time waits in cancel test; day headers not sticky.
- Task 13: minor (deferred): "Library updated"/"Could not refresh" toast race between two live Home VMs; Search/Settings have no explicit unreachable state (no initial load); after Change server from the player BACK returns to a stale Unreachable state; LEFT from Resume goes to the Season tab not the sidebar (pre-existing).
- Final re-review (32caf0a..ae74e8c): all findings addressed, no new Critical/Important. Parked minors: no-cue heuristic false positives (resume into a >20 s gap; cues before the fetch) — Ruling: acceptable, toasts once; background re-prepare window from a late delay/download job — Ruling: acceptable. DONE.

## Rulings
- Ruling: no worktree/branch for this plan (owner: commit on main).
- Task 1: Ruling: compileSdk 35 (Media3 1.5.1 requires it), targetSdk stays 34; cmdline-tools copied into the SDK dir for avdmanager; launch with `am start -n app.pixelpantry.tv/.MainActivity`. Cost if wrong: none.
- Task 2: reviewer ⚠️ resolved by controller: server returns {ok:true} (never empty body) for watched/tracks → no gap; server treats explicit null as "clear" but the client omits nulls → Ruling: subtitles-off is persisted as the string choice "off" (Task 10), so clearing via null is not needed; carried to Task 10 dispatch.
- Task 11: Ruling: during the countdown OK on a focused overlay button activates that button (focus starts on Next episode); MEDIA_PLAY/PLAY_PAUSE always play next. Cost if wrong: none.
- Final: Ruling: the plan's PlayerKeys pass-through rule is corrected — the overlay opened from the hidden state focuses the seek bar so LEFT/RIGHT skip; buttons via DOWN. Cost if wrong: one extra DOWN to reach Pause.
- Final: Ruling: audio override only with a stored, matching, supported choice; otherwise ExoPlayer's default selection. Cost if wrong: none (user can still pick from the menu).
- Final: Ruling: player stops the decoder on host stop and re-prepares on return (paused). Cost if wrong: a ~1 s re-prepare when coming back.
- Final fix wave: commits 32caf0a..ae74e8c (14 commits), 315 tests. Ruling: All-grid column count derived from screen width (4 on 1080p at 320 dpi) rather than a fixed 7. Cost if wrong: none.
- Final re-review (32caf0a..ae74e8c): all findings addressed, no new Critical/Important. Parked minors: no-cue heuristic false positives (resume into a >20 s gap; cues before the fetch) — Ruling: acceptable, toasts once; background re-prepare window from a late delay/download job — Ruling: acceptable. DONE.

## Real-TV checklist
1. Long-press OK on an episode card: the menu opens and nothing runs on release; the episode's progress is unchanged afterwards.
2. Remote keys reach the app: CH+ / CH− switch episodes (Sony firmware may route them to the tuner); ⏮ ⏭, ⏪ ⏩, ■, and separate ▶ / ‖ versus ⏯ all do what the player overlay says.
3. Audio: a remux whose default track is DTS, plus DTS-only, TrueHD, EAC3 and AC3 files, on the TV speakers and over ARC/eARC. Expect no error screen and no silent video.
4. 4K HEVC and high-bitrate remux direct play: start, seek, hold LEFT/RIGHT (repeat rate and skip accumulation), no out-of-memory crash, no stutter.
5. Press HOME mid-playback, open YouTube or Netflix and play there, come back: the other app plays, Pixel Pantry resumes with OK, no "unsupported format" error. Also: screen saver while paused, and TV standby via HDMI-CEC.
6. Process death: after using other heavy apps, relaunch from the launcher; the restored screen loads with the right server address.
7. MKV embedded subtitles: default and forced flags, the choice restored on the next play, the correct track when there are several English tracks; sidecar `.srt` (including cp1252), sub delay, OpenSubtitles download and switching while playing.
8. Server going away mid-playback (PC sleep): the error with the file name appears within about 10–15 s; Back works.
9. mDNS discovery on the home network; a DHCP address change; Retry recovers.
10. OK to pause, then RIGHT, then OK: RIGHT skips and OK resumes (it must not switch episodes).
11. Rescan from the sidebar, then the auto-refresh toast; posters appear once the TMDB key is set on the server.
