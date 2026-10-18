# Part II mobile verification log

Rule: PASS means the check was run and the evidence is recorded. NOT RUN means it could not be run (reason given).
Times are PKT on 2026-10-18. Base commit `2adf2c75aa5568b17535445b601f21dfcf325cd8` must stay an ancestor of HEAD.

Quick gate (every phase): `cd mobile && ./gradlew.bat testDebugUnitTest assembleDebug` with
`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` (the default Gradle JVM here fails on Java 25).
Lint is `./gradlew.bat lintDebug` and is compared with the baseline at the end only (it takes about 4 minutes).

---

## Phase 0: discovery and baseline

- Start 12:13, end 12:21.
- `git pull --no-rebase` merged teammates' work; HEAD `4ee7c445fd1c43a163fab457883ec7e72618c0cd`.
- Ancestor check `git merge-base --is-ancestor 2adf2c7... HEAD`: PASS (base is an ancestor).
- Commands: build and unit tests `./gradlew.bat testDebugUnitTest assembleDebug`; lint `./gradlew.bat lintDebug`.
- Baseline: **492 unit tests, 0 failures, 0 errors, 0 skipped**. Debug APK builds (`app/build/outputs/apk/debug/app-debug.apk`).
- Baseline lint (pre-existing, not caused by Part II): `lintDebug` FAILS with **6 errors, 186 warnings**.
- Device: `adb devices` shows `Note202401005107` (Note 20, Android, USB). No emulator. Appium not installed here.
- Backend reachable at `http://127.0.0.1:8080` (Docker stack); phone uses `adb reverse tcp:8080 tcp:8080`.

Gate 0
- Build compiles at baseline: PASS (`assembleDebug`).
- Existing unit test counts recorded: PASS (492/0).
- Project map recorded: PASS (below).
- Device availability recorded: PASS (physical Note 20).
- No source file changed: PASS.

### Project map (Kotlin root `mobile/app/src/main/java/com/nascon/league/`, written `K/`)

- Manifest `mobile/app/src/main/AndroidManifest.xml`: launcher `.MainActivity`, exported, standard launch mode, only MAIN/LAUNCHER filter.
  Permissions INTERNET and ACCESS_NETWORK_STATE. `networkSecurityConfig=@xml/network_security_config` (cleartext allowed).
- Activity `K/MainActivity.kt` (no `onNewIntent`). `K/App.kt` holds `AppContainer` (hand-rolled locator, `LocalAppContainer`).
- Navigation `K/ui/navigation/AppNavHost.kt` (`AppRoot`, `AppShell`, `titleFor`), `Destinations.kt`, `NewRoutes.kt`, `BottomBar.kt`,
  `HeaderController.kt`. Non-tab routes get a back arrow automatically from `ArenaHeader`.
- Theme `K/ui/theme/` (`Arena`, `Spacing`, `NasconTheme`). Components `K/ui/components/` (ArenaHeader, ArenaCard, PrimaryButton,
  SecondaryButton, StatusChip, ArenaTextField, EmptyState, ErrorState, ConnectivityBanner, SystemStates...). No skeleton component existed.
- Networking `K/core/network/` (ApiFactory, AuthInterceptor, TokenAuthenticator, ApiError, NetworkResult/safeCall); base URL in
  `K/core/config/ServerConfigStore.kt` (DataStore `server_config`).
- Session `K/data/repo/SessionRepository.kt`, `K/core/security` TokenStore (EncryptedSharedPreferences), `K/data/repo/AuthRepository.kt`.
- Room `K/core/db/AppDatabase.kt` version 2 (schemas in `mobile/app/schemas`). Sync: `K/data/sync/`, WorkManager `SyncWorker`.
- Realtime `K/data/realtime/` (OkHttp WebSocket at `/ws?token=<JWT>`, topic `match:<id>`; no SSE; no guest read).
- Screens: Match `K/ui/match/MatchDetailScreen.kt` + `MatchDetailViewModel.kt` (route `match/{matchId}`), Store/Cart `K/ui/store/`,
  Login `K/ui/login/`, Home `K/ui/home/`, Player `K/ui/player/`, Club `K/ui/club/`, Notifications `K/ui/notifications/`.
- Tests: JUnit4 under `mobile/app/src/test`; no androidTest, no Compose UI test library.
- Libraries not present before Part II: media3 (video), CameraX, ML Kit.

### Backend reality (read from the repo, not assumed)

- `docs/part2-interfaces.md` describes only the shared libraries. `backend/day2/routes/` is empty at 12:15.
- Do not exist yet: guest session route, chat, co-stream, stream key, QR, cart-merge, follow, notifications, SSE, `/ws/matches/{id}`.
- Exists: `GET /api/v1/matches/:id` (JWT), `/ws?token=<JWT>` (guests rejected with 4401), `GET /audit/v1/score/:id` (no auth),
  global guest write guard (403 `GUEST_WRITE_FORBIDDEN` on POST/PUT/PATCH/DELETE with a `guest_` token).
- Match ids seeded by reset: `demo-m-live` (live), `demo-m-5` and `demo-m-6` (upcoming); `match-m1` and `cs-session-101` are NOT seeded.
- Consequence: guest reads cannot succeed against the current backend. The app builds against the contract shapes and shows
  loading, empty, offline or error states when the server has nothing. Each assumption is listed in the final report.

---

## Phase 1: manifest, deep-link parser, router, not-found screen

- Start 12:21, end 12:36. Built by parallel agents on disjoint files (parser, router and pending store, not-found screen); wiring (manifest, activity, NavHost, guest bootstrap) and all builds done by the lead.
- Built: `deeplink/DeepLinkParser.kt`, `DeepLinkRouter.kt`, `PendingDeepLink.kt` (pending link, duplicate-intent guard, login gate), `DeepLinkTarget.kt`;
  `ui/deeplink/NotFoundScreen.kt` (H15, tag `not_found_root`), `CoStreamPlaceholderScreen.kt` (tag `costream_root`, replaced in Phase 4);
  manifest browsable `nascon` filter + `singleTask`; `MainActivity` handles the launch intent and `onNewIntent`, saves a waiting link in instance state;
  `AppShell` opens Home with the target on top once the NavHost is ready and the user is not on login.
- Brought forward from Phase 2 because "no stop on a login screen" needs it: the app now starts as a Guest (token from `GuestSession`:
  debug build uses the seeded dev token, release builds a random `guest_` token) unless a member session is stored. Sign-out and a rejected
  member token drop to Guest; a rejected guest token changes nothing.

Gate 1
1. Build compiles: PASS (`assembleDebug`).
2. Unit tests: PASS, **559 total, 0 failures** (baseline 492; +67 in the deeplink package).
3. Lint: NOT RUN this phase (baseline 6 errors / 186 warnings; compared at Phase 9).
4. Manifest check: PASS, merged manifest has `exported="true"`, `launchMode="singleTask"`, VIEW + DEFAULT + BROWSABLE + `scheme="nascon"`.
5. Device (Note 20, debug build): `am start -W -a android.intent.action.VIEW -d <link>`, activity `com.nascon.league/.MainActivity`, Status ok.
   - warm `nascon://entity/match-m1`: TotalTime 0, WaitTime 22 ms. warm `nascon://stream/cs-session-101`: WaitTime 25 ms.
   - **cold: WaitTime 3042 ms (match) and 3016 ms (stream). This is ABOVE the 2 s target.** A plain launcher start with no link also takes 3028 ms,
     so the cost is general cold start of the debug build, not the link handling. Follow-up in Phase 9: measure the release build and reduce startup work.
   - logcat after all link runs: no FATAL EXCEPTION, no ANR.
   - What the screen showed: NOT RUN. The phone display was asleep behind the lock screen (`mWakefulness=Asleep`, keyguard showing), screenshots were black,
     and a secure lock cannot be bypassed from here. The resumed activity was `MainActivity`.
6. Bad links (`nascon://entity/`, `.../match-m1/extra`, `.../%00`, `nascon://unknown/x`, 200-char id): no crash (Status ok, no FATAL in logcat).
   The not-found screen itself was not seen on the device (same reason). The parser returns the right NotFound reason for each (unit tests).
7. Secret scan of the diff (old-salt prefix, salt variable names, AWS/GitHub/Slack/private-key/JWT shapes): PASS, no hits. The only `guest_anon_tok_7721`
   occurrence is the debug-only constant in `GuestSession.kt`.
8. `git status` clean after commit: see below.
- Side effect on the test phone: `screen_off_timeout` was set to 600000 ms so screenshots can be taken; the original value was not recorded.

---

## Phase 1 push

- Commit `de9c920`, merged and pushed as `9d45b95` at 12:27 PKT (target 12:40). Ancestor check against the Saturday base: PASS.

---

## Phase 2: guest session, network config, realtime client, error mapping

- Start 12:28, end 12:41. Parallel agents on disjoint files; the lead wired everything and ran every build.
- Built:
  - `data/realtime/ResilientMatchStream.kt` + `LiveModels.kt`: WebSocket -> SSE -> polling fallback, backoff 250 ms doubling to 2 s with jitter,
    resync after every reconnect and sequence gap, dedupe by id, order and stale-discard by sequence and timestamp, state flow
    CONNECTING / LIVE / RECONNECTING / OFFLINE.
  - `data/realtime/OkHttpTransports.kt` (WebSocket, SSE, polling over the unauthenticated score route) and `LiveFrameParser.kt`.
  - `core/error/Part2Errors.kt`: every code in the brief maps to a UI action; unknown codes give a generic retry state; no raw JSON is shown.
  - `core/network/CleartextPolicy.kt` + `CleartextGuardInterceptor` (wired into both OkHttp clients in `ApiFactory`), `core/config/BaseUrlValidator.kt`.
  - Guest session start (from Phase 1) with GuestSession tests.
- Not done: `BaseUrlValidator` is written and tested but is NOT yet used by the Settings screen (Settings still uses `ServerConfigStore.normalizeUrl`).
  `pollIntervalMs` on `ResilientMatchStream` is accepted but unused (polling cadence lives in `PollingTransport`, 1 s).

Gate 2
1. Build + unit tests: PASS, `assembleDebug` OK, **917 tests, 0 failures** (baseline 492).
2. Network security config: PARTIAL. The OS-level `network_security_config.xml` still has `base-config cleartextTrafficPermitted="true"` because Android cannot match a LAN IP
   range in a domain-config. The app-level `CleartextGuardInterceptor` blocks http to every non-development host (localhost, 10.0.2.2, 127.0.0.1, ::1, 10.0.0.0/8,
   172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, *.local); 47 unit tests including the interceptor. Not exercised on a device.
3. Backend probe (docker stack on 127.0.0.1:8080; NOTE this stack was built before the teammates' merge, so it does not have the Part II guest write guard):
   - `GET /audit/v1/score/demo-m-live` with guest token: **200**.
   - `GET /api/v1/matches/demo-m-live` with guest token: **401** (JWT only). So guests read through the score route.
   - `GET /audit/v1/score/match-m1`: **404** (`match-m1` is not seeded).
   - `/ws/matches/{id}` upgrade: **400**; `GET /api/v1/matches/{id}/stream`: **404**. Neither route exists, so the app falls back to polling.
   - `POST /audit/v1/checkout` with a guest token returned **400** validation, not 403, confirming the running stack lacks the guard.
4. Fake-transport tests: PASS (24 `ResilientMatchStreamTest`). Seven of them initially failed because the tests ran the collector in `backgroundScope` and used
   `advanceUntilIdle()`, which ignores background work; fixed by driving virtual time explicitly (assertions unchanged).
5. No token in logs: PASS. No `Log.*` or `println` calls in the new realtime, match, deep-link, cart or error code; the HTTP logging interceptor is debug-only and redacts `Authorization`.
6. Secret scan: PASS (no salts or key shapes). The dev token literal appears only in `GuestSession.kt` (debug constant) and its unit test.
7. Room migration 2 -> 3 (guest cart): PASS by schema comparison. `GuestCartMigrationTest` checks that the migration SQL equals the table in Room's exported `3.json`, that
   no version-2 table changed, and that only `guest_cart_item` was added. A device upgrade run was NOT RUN.

---

## Phase 3: match screens H02-H05, guest cart storage, chat panel, video

- Start 12:33, code complete 12:41 (agents), builds and fixes until 13:05.
- Built: `matchlive/` contracts, `MatchLiveLogic` (score `H - A`, PKR price, status icon+text, event application, countdown), `ChatTimeline` (strict sequence order, dedupe, pending to
  confirmed), `RemoteMatchLiveRepository` + `MatchJsonMapper`, `MatchLiveViewModel`; `ui/matchlive/` `MatchLiveScreens.kt` (Live, Preview, VOD, Notice, skeleton, offline strip, pinned merch card),
  `ChatPanel.kt`, `VideoPlayerCard.kt` (media3 ExoPlayer, HLS, muted autoplay); Room `guest_cart_item` (DB v3) with `RoomGuestCart`.
  Deep links to a match now open the Part II screen at route `matchlive/{id}?t=`; in-app match links keep the existing scoring screen.
- Assumed backend routes (do not exist yet): chat `GET /api/v1/chat/{room}/messages` and `POST /api/v1/chat` `{room_id,text}`; SSE `GET /api/v1/matches/{id}/stream`;
  WebSocket `/ws/matches/{id}`; stream URL/poster fields on the match JSON (`stream_url`, `playback_url`, `hls_url`, `poster_url`).
- Live reads for guests use `GET /audit/v1/score/{id}` (unauthenticated) because the app-facing match route needs a JWT.

Gate 3
1. Build + all tests: PASS, `assembleDebug` OK, **917 tests, 0 failures** (baseline 492; +425).
2. Device walk-through of H02-H05: **NOT RUN**. The phone was awake at 12:41 but disconnected from ADB before the walk-through could start; no device is listed by `adb devices` at 13:05.
3. Score-event-to-screen latency (< 500 ms): **NOT RUN** (needs the device). Covered only by the view-model unit tests (a snapshot event updates the score in the same step, with no debounce).
4. Offline strip and recovery on a device: **NOT RUN**. The offline state is unit tested in the view model.
5. `am start -W` with the real screens: **NOT RUN** (device). Phase 1 timings above were with placeholder screens.
6. Accessibility check (TalkBack order, touch targets, font scale 200%): **NOT RUN** on a device. Code uses >= 48 dp targets and content descriptions; unverified visually.
7. Secret scan: PASS. `git status` clean after commit: see below.

---

## Phase 2/3 push

- Merge commit `bcd1ca4` pushed at 13:09:36 PKT (target 13:10). Ancestor check: PASS.

---

## Phase 4: Co-Stream viewer H06 and chat

- Start 13:10, code complete 13:27, tests green 13:29. Parallel agents (logic/data/view model, screen) with the lead wiring.
- Built: `costream/` (`CoStreamLogic`: layouts SOLO / GRID_2 / GRID_4 / SPOTLIGHT, never more than 4 tiles, kicked excluded, host first, handoff banner "Host changed to <name>",
  `CoStreamViewModel` polling the session every 2 s, `RemoteCoStreamRepository`) and `ui/costream/CoStreamScreen.kt` (composite video, tiles with host and muted icon+text, handoff banner as a polite live region,
  shared `ChatPanel`, skeleton, offline strip, session-ended state). The deep link `nascon://stream/cs-session-*` now opens this screen instead of the Phase 1 placeholder.
- Real bug found by the tests and fixed: the view model never started polling after the first load (a guard compared the load job with itself). The app only worked because the screen calls `setActive(true)`.
- Assumed backend route (does not exist): `GET /api/v1/costream/{id}`; chat reuses the assumed chat routes from Phase 3. Realtime for co-stream is polling only.
- Not done: the host console (H07) and solo broadcast (H08, Phase 7). Chat mute/kick notices inline are not rendered (the server contract for them is unknown).

Gate 4
1. Build + tests: PASS, **1023 tests, 0 failures** (52 new co-stream tests: 27 logic, 25 view model).
2. Device walk-through: **NOT RUN**. The phone was attached at 13:29 but locked (`isKeyguardShowing=true`), screenshots are black.
3. Session with `simulate_host_drop`: **NOT RUN** (the backend co-stream routes and the audit wrapper do not exist yet).
4. Deep link `nascon://stream/cs-session-101` on the device: `am start -W` Status ok, MainActivity resumed, no FATAL in logcat; **WaitTime 3024 ms (cold, debug build), above the 2 s target**.
5. Browser-to-app chat latency: **NOT RUN** (no web player route or chat backend yet).
6. Secret scan: PASS.

---

## Phase 5: progressive sign-in H12, guest cart H13, safe cart merge

- Start 13:10, code complete 13:27, tests green 13:29.
- Built: `ui/guest/ProgressiveSignInSheet.kt` (modal bottom sheet, tag `progressive_sheet`, "Your cart is saved", Log in / Register / Close), `GuestCartScreen.kt` (list, + / - stepper capped at 10, subtotal, count tagged `guest_cart_count`, Proceed to checkout),
  `cartmerge/` (`CartMergeMachine`: the body is frozen once and re-sent byte-identical on every retry, cart cleared only on Merged / AlreadyMerged, Conflict, VariantMissing, TooManyItems, FailedRetryable, UserMissing; `RemoteCartMergeRepository` for `POST /audit/v1/guest/cart-merge`),
  `GuestCheckoutApi` (a real `POST /audit/v1/checkout` from a guest with the declined test card, so a 403 opens the sheet and nothing is paid if a server ever let it through).
- Flow wired in `AppNavHost`/`AppContainer`: guest taps Send chat or Proceed to checkout -> real request -> 403 -> sheet (origin screen recorded, the guest token staged in app storage) -> login -> merge the staged cart with the frozen body ->
  snackbar -> back to the origin screen. If the app is killed mid-merge, the next start with a member session retries the same body.
- Partial: the playback second is NOT recorded or restored (the video card has no position callback), only the origin screen is. Register uses the login screen (no separate register screen exists).
  The guest cart for a member is not shown from the server's `active_cart`; after a successful merge the local guest rows are dropped and a snackbar confirms.

Gate 5
1. Build + tests: PASS, **1023 tests, 0 failures** (31 cart merge, 24 guest UI, 18 cart storage).
2. Device walk-through of the whole flow: **NOT RUN** (device locked; backend merge/checkout routes and the guest write guard are not on the running stack).
3. Two identical merges give `total_items` 1 against the audit probe: **NOT RUN** (route not on the backend yet). Covered by `CartMergeMachineTest`: a retry and a restart send identical bodies and the cart total does not change.
4. Secret scan: PASS.

---

## Phase 8: test tags and Appium files

- `testTagsAsResourceId` is enabled at the app root (`AppRoot`). The roots `match_live_root` and `costream_root` are on the route containers, so they exist in every state (loading, error, not found, content).
- Added `tests/appium/README.md` and `tests/appium/test_mobile_deeplink.py` (two deep links, warm link, bad links, a skipping cross-surface skeleton; JUnit XML to `tests/reports/appium-report.xml`). Nothing else under `tests/` was edited.

Gate 8
1. Python syntax check of the Appium file: see below.
2. Tags present in the code: PASS (`match_live_root`, `match_score`, `match_status`, `chat_list`, `chat_input`, `chat_send`, `guest_cart_count`, `costream_root`, `costream_tile_0..3`, `costream_host_marker`, `costream_handoff_banner`, `progressive_sheet`, `not_found_root`).
3. Tags verified in a UI dump on a device: **NOT RUN** (device locked).

---

## Phase 4/5/8 push

- Merge commit `600b437` pushed at 13:31:45 PKT (target 13:40). Ancestor check: PASS.

---

## Phase 9: final verification

- Final unit tests: **1023 total, 0 failures** (baseline 492). Debug and release builds succeed.
- Lint: baseline 6 errors / 186 warnings; final **6 errors / 209 warnings**. No new errors; 23 new warnings, not triaged.
- Secret scan across `mobile/` and `tests/appium/`: PASS (no salt, key or token shapes). Only the debug-only dev token constant and its unit test contain `guest_anon_tok_7721`.
- Merged release manifest (aapt on `mobile/release/app-release.apk`): VIEW + BROWSABLE + `scheme="nascon"`, `launchMode=singleTask`, `exported`: PASS.
- Deep link timings, **release build**, Note 20, cold, `nascon://entity/match-m1`: WaitTime 3015, 3023, 3034 ms. Plain launcher cold: 3024 ms.
  **This misses the 2 s target.** The identical figure for a plain launch shows it is app cold-start, not link handling. Warm links: 22-25 ms (Phase 1, debug).
  Not fixed in time. Likely cause: startup work in `AppContainer` / splash. Needs profiling.
- What the screens look like on the device: **NOT RUN**. The phone stayed locked (`isKeyguardShowing=true`) and every screenshot was black.
  The match, co-stream, sheet and cart screens have therefore never been seen running. Verified only by compilation and unit tests.
- Lint/runtime/Appium: Appium was NOT RUN (not installed, device locked).

### NOT RUN list
Device walk-throughs (H02-H06, H12, H13), score-event latency, offline recovery on device, TalkBack and 200% font scale, Room 2->3 upgrade on a device,
Appium run, browser-to-app chat latency, UI dump of test tags.

### Not built
H07 host console, H08 solo broadcast, H09 QR scanner, H10 QR display, H11 athlete profile and follow, H14 notifications (Phases 6-7, not started).
`BaseUrlValidator` is not hooked into Settings. Playback second is not restored after sign-in.

### Backend routes assumed that do not exist
Guest session route; `/ws/matches/{id}`; `GET /api/v1/matches/{id}/stream`; chat `GET /api/v1/chat/{room}/messages` and `POST /api/v1/chat`;
`GET /api/v1/costream/{id}`; `POST /audit/v1/guest/cart-merge`. `match-m1` and `cs-session-101` are not seeded.
