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
7. Secret scan of the diff (T37D, DAY1/DAY2_SALT, AWS/GitHub/Slack/private-key/JWT shapes): PASS, no hits. The only `guest_anon_tok_7721`
   occurrence is the debug-only constant in `GuestSession.kt`.
8. `git status` clean after commit: see below.
- Side effect on the test phone: `screen_off_timeout` was set to 600000 ms so screenshots can be taken; the original value was not recorded.
