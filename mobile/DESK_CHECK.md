# Desk check (offline and sync)

How this was run: ATTEMPTED, NOT EXECUTED. Intended: demo mode on a physical phone (adb Note202401005107, Android 13). No step could be run because the phone was locked (see Findings).  
Device / emulator: physical phone Note202401005107  Build: release APK installed (com.nascon.league)  Date / tester: 2026-10-17, automated verification agent

Do not record a result unless the step was actually performed. Result values: NOT VERIFIED, PASS, FAIL.

## 1. Online browsing
Open Fixtures, Standings and a Club while online; data loads.
Result: ☐ NOT VERIFIED
Evidence/notes:

## 2. Airplane mode
Turn on airplane mode. The offline banner appears and previously opened screens still show cached data.
Result: ☐ NOT VERIFIED
Evidence/notes:

## 3. Queue actions offline
Organizer adds a score; manager makes a transfer. Both show as pending on Sync status.
Result: ☐ NOT VERIFIED
Evidence/notes:

## 4. Force-close and reopen offline
Still logged in; pending actions are still listed.
Result: ☐ NOT VERIFIED
Evidence/notes:

## 5. Network back on
Auto sync runs; each action shows its own result; queue becomes empty; screens refresh with the new data.
Result: ☐ NOT VERIFIED
Evidence/notes:

## 6. Sync now on empty queue
Tapping "Sync now" with nothing waiting is harmless (no error, no change).
Result: ☐ NOT VERIFIED
Evidence/notes:

## 7. Kill app during sync
Kill the app mid-sync, reopen, sync again. No duplicate scoring; the repeated action shows "Already saved earlier."
Result: ☐ NOT VERIFIED
Evidence/notes:

## 8. Rejected action
Deliberately submit an action the server rejects (transfer outside the window, or scoring on a non-live match). A readable explanation is shown.
Result: ☐ NOT VERIFIED
Evidence/notes:

## Findings
1. BLOCKER for this check (environment, not an app bug): when the run started (about 14:01 on 2026-10-17) the phone showed a secure lock screen (isKeyguardShowing=true, Trust manager deviceLocked=1, only an "Emergency" button visible after swiping up), although it was expected to be unlocked. Re-checked after about 45 s: still locked. The agent cannot enter a PIN/pattern, so no in-app step (login, fixtures, offline banner, scoring, roster transfer, sync, Task A, Task B) was run. All 8 steps above remain NOT VERIFIED.
   Note: adb screencap returns an empty file while the keyguard is up, so no screenshots were saved.
2. Crash log check (`logcat -d -b crash`): the only crash entry is com.mediatek.simprocessor (a system SIM process, "Unknown authority com.android.contacts", 10-16 20:45), unrelated to com.nascon.league. No com.nascon.league crash or AndroidRuntime entry was found. (The app was force-stopped and relaunched once behind the lock screen; no crash from that.)
3. Device state: original state recorded before any change was airplane_mode_on=0, Wi-Fi enabled, mobile_data setting=0, stay_on_while_plugged_in=0. Wi-Fi, mobile data and airplane mode were never changed. stay-awake was set on then restored to 0.

To complete: unlock the phone, disable its screen timeout (or keep it plugged in with stay awake) and re-run the agent.

## F12. Failure modes on the real server

Gap: `ApiError` has no HTTP Retry-After header. A refresh is scheduled only when the error JSON details include `retryAfterSeconds`, `retryAfter`, or `retry_after`. That refresh does not clear pending actions. `ReceiptLoadResult` has no content-type; a saved body that starts with `{` or `[` is shown as an error.

### 1. Test connection
Turn Use demo data off. Emulator address `http://10.0.2.2:8080`, or on a phone `http://<laptop LAN IP>:8080` (same Wi-Fi, port 8080). Test connection shows Connected or a plain failure. A saved address is not overwritten.
Result: NOT RUN ON DEVICE yet — the app was not operated on a phone or emulator in this session.

### 2. Wrong password
A wrong password shows a plain message, with no stack trace and no code dump.
Result: NOT RUN ON DEVICE yet — login was not attempted on a device.

### 3. Forbidden
A forbidden action shows "You don't have permission to do that."
Result: NOT RUN ON DEVICE yet — no restricted action was attempted on a device.

### 4. Too many requests
A 429 shows "Too many requests — try again in a few seconds." Any scheduled retry leaves pending actions in place.
Result: NOT RUN ON DEVICE yet — rate limiting was not triggered on a device.

### 5. Server unreachable
The existing banner says the server is unreachable and cached screens stay usable. Saved lists are not cleared.
Result: NOT RUN ON DEVICE yet — the server was not stopped while the app was open on a device.

### 6. Slow request
A request that takes about 15 seconds shows a loading indicator, then a retry button on screens that already have loading and error states.
Result: NOT RUN ON DEVICE yet — a delayed server was not used on a device.

### 7. Checkout
Success is unchanged. Out of stock shows "Sorry, this item just sold out." A second tap while paying does not start another checkout. Offline disables Pay and shows "Checkout needs an internet connection."
Result: NOT RUN ON DEVICE yet — checkout was not run on a device.

### 8. Receipt, images, dates, live updates
An in-app PDF still renders and Share works. A JSON error body shows an error instead of a blank viewer. A failed image shows the existing placeholder. Instants use the device time zone. A live event already on screen is not listed twice.
Result: NOT RUN ON DEVICE yet — receipt, image, and live-update checks were not run on a device.
