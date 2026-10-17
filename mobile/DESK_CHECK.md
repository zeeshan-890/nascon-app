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
