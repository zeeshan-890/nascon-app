# Appium checks for the mobile app (Part II)

`test_mobile_deeplink.py` drives the Android app with Appium 2 (uiautomator2) on `http://localhost:4723`.

## What it checks
- `adb shell am start -W -a android.intent.action.VIEW -d "nascon://entity/match-m1"` brings the app to the foreground in 2 s or less
  and shows the element with resource id `match_live_root`.
- The same for `nascon://stream/cs-session-101` and `costream_root`.
- A warm link (app already running) reaches the existing task.
- Bad links (`nascon://entity/`, `nascon://unknown/x`, `nascon://entity/%00`) do not crash and show `not_found_root`.
- A cross-surface chat scenario is a skeleton that skips with a reason (it needs the web player and the chat backend).

Test tags are exposed as Android resource ids (`testTagsAsResourceId` at the app root), so they are found by `By.ID`.
Tags: `match_live_root`, `match_score`, `match_status`, `chat_list`, `chat_input`, `chat_send`, `guest_cart_count`,
`costream_root`, `costream_tile_0` to `costream_tile_3`, `costream_host_marker`, `costream_handoff_banner`,
`progressive_sheet`, `not_found_root`.

## Run
```
pip install Appium-Python-Client
appium --port 4723            # in another terminal, with the uiautomator2 driver installed
python tests/appium/test_mobile_deeplink.py
```
JUnit XML is written to `tests/reports/appium-report.xml`. With no device or no Appium session the tests are reported as skipped,
never as passed.
