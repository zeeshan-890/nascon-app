"""Mobile deep-link checks for the NaSCon Part II app (Appium 2, uiautomator2).

Run:  python tests/appium/test_mobile_deeplink.py
Needs: an Android device or emulator on adb, the debug or release app installed, Appium 2 listening on http://localhost:4723
with the uiautomator2 driver, and `pip install Appium-Python-Client`.
Writes JUnit XML to tests/reports/appium-report.xml. Tests that cannot run (no device, no Appium) are SKIPPED with a reason,
never reported as passed.
"""
import os
import re
import shutil
import subprocess
import sys
import time
import unittest
import xml.etree.ElementTree as ET

APP_PACKAGE = "com.nascon.league"
APPIUM_URL = os.environ.get("APPIUM_URL", "http://localhost:4723")
MAX_FOREGROUND_MS = 2000
REPORT_PATH = os.path.join(os.path.dirname(__file__), "..", "reports", "appium-report.xml")


def adb(*args, timeout=30):
    exe = shutil.which("adb")
    if exe is None:
        raise unittest.SkipTest("adb is not on PATH")
    return subprocess.run([exe, *args], capture_output=True, text=True, timeout=timeout)


def device_attached():
    try:
        out = adb("devices").stdout.splitlines()[1:]
    except unittest.SkipTest:
        return False
    return any(line.strip().endswith("device") for line in out)


def start_link(uri):
    """`am start -W` blocks until the activity is displayed; returns (wait_ms, resumed_activity_line)."""
    res = adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", uri)
    m = re.search(r"WaitTime:\s*(\d+)", res.stdout)
    wait_ms = int(m.group(1)) if m else None
    act = re.search(r"Activity:\s*(\S+)", res.stdout)
    return wait_ms, act.group(1) if act else None, res.stdout


class DeepLinkBase(unittest.TestCase):
    driver = None

    @classmethod
    def setUpClass(cls):
        if not device_attached():
            raise unittest.SkipTest("no Android device or emulator attached to adb")
        try:
            from appium import webdriver
            from appium.options.android import UiAutomator2Options
        except ImportError:
            raise unittest.SkipTest("Appium-Python-Client is not installed")
        opts = UiAutomator2Options()
        opts.platform_name = "Android"
        opts.automation_name = "UiAutomator2"
        opts.app_package = APP_PACKAGE
        opts.app_activity = ".MainActivity"
        opts.no_reset = True
        opts.new_command_timeout = 120
        try:
            cls.driver = webdriver.Remote(APPIUM_URL, options=opts)
        except Exception as exc:  # Appium not running or driver missing
            raise unittest.SkipTest(f"cannot start an Appium session at {APPIUM_URL}: {type(exc).__name__}")

    @classmethod
    def tearDownClass(cls):
        if cls.driver is not None:
            cls.driver.quit()

    def open_link(self, uri, cold=True):
        if cold:
            adb("shell", "am", "force-stop", APP_PACKAGE)
            time.sleep(1)
        else:
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(1)
        return start_link(uri)

    def has_id(self, rid, timeout=6):
        from appium.webdriver.common.appiumby import AppiumBy
        end = time.time() + timeout
        while time.time() < end:
            if self.driver.find_elements(AppiumBy.ID, rid) or self.driver.find_elements(AppiumBy.ID, f"{APP_PACKAGE}:id/{rid}"):
                return True
            time.sleep(0.4)
        return False


class TestDeepLinks(DeepLinkBase):
    def test_match_link_opens_match_live(self):
        wait_ms, activity, _ = self.open_link("nascon://entity/match-m1")
        self.assertIsNotNone(wait_ms, "am start -W printed no WaitTime")
        self.assertIn("MainActivity", activity or "")
        self.assertLessEqual(wait_ms, MAX_FOREGROUND_MS, f"app took {wait_ms} ms to reach the foreground")
        self.assertEqual(self.driver.current_package, APP_PACKAGE)
        self.assertTrue(self.has_id("match_live_root"), "match_live_root not found")

    def test_costream_link_opens_costream(self):
        wait_ms, activity, _ = self.open_link("nascon://stream/cs-session-101")
        self.assertIsNotNone(wait_ms)
        self.assertLessEqual(wait_ms, MAX_FOREGROUND_MS, f"app took {wait_ms} ms to reach the foreground")
        self.assertEqual(self.driver.current_package, APP_PACKAGE)
        self.assertTrue(self.has_id("costream_root"), "costream_root not found")

    def test_warm_link_reaches_running_app(self):
        self.open_link("nascon://entity/match-m1")
        wait_ms, activity, out = self.open_link("nascon://stream/cs-session-101", cold=False)
        self.assertLessEqual(wait_ms or 0, MAX_FOREGROUND_MS)
        self.assertTrue(self.has_id("costream_root"))

    def test_bad_link_does_not_crash(self):
        for uri in ("nascon://entity/", "nascon://unknown/x", "nascon://entity/%00"):
            self.open_link(uri)
            self.assertEqual(self.driver.current_package, APP_PACKAGE, uri)
            self.assertTrue(self.has_id("not_found_root"), f"not_found_root not shown for {uri}")


class TestCrossSurfaceChat(unittest.TestCase):
    """Skeleton: the app and a Selenium browser on /web/costream/cs-session-101 must show each other's chat within 3 s."""

    def test_chat_round_trip_between_app_and_browser(self):
        if not device_attached():
            self.skipTest("no Android device or emulator attached to adb")
        self.skipTest("cross-surface chat needs the web player route and the chat backend; not implemented in this skeleton")


def write_junit(result, elapsed):
    suite = ET.Element("testsuite", name="appium-mobile-deeplink", tests=str(result.testsRun),
                       failures=str(len(result.failures)), errors=str(len(result.errors)),
                       skipped=str(len(result.skipped)), time=f"{elapsed:.3f}")
    names = {}
    for test, _ in list(result.failures) + list(result.errors) + list(result.skipped):
        names[test.id()] = test
    for test_id in sorted(set(getattr(result, "_ran_ids", []))):
        case = ET.SubElement(suite, "testcase", classname=test_id.rsplit(".", 1)[0], name=test_id.rsplit(".", 1)[-1])
        for t, tb in result.failures:
            if t.id() == test_id:
                ET.SubElement(case, "failure", message=tb.strip().splitlines()[-1][:300]).text = tb
        for t, tb in result.errors:
            if t.id() == test_id:
                ET.SubElement(case, "error", message=tb.strip().splitlines()[-1][:300]).text = tb
        for t, reason in result.skipped:
            if t.id() == test_id:
                ET.SubElement(case, "skipped", message=reason)
    os.makedirs(os.path.dirname(REPORT_PATH), exist_ok=True)
    ET.ElementTree(suite).write(REPORT_PATH, encoding="utf-8", xml_declaration=True)


class _Result(unittest.TextTestResult):
    def startTest(self, test):
        super().startTest(test)
        self.__dict__.setdefault("_ran_ids", []).append(test.id())


if __name__ == "__main__":
    started = time.time()
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    for case in (TestDeepLinks, TestCrossSurfaceChat):
        suite.addTests(loader.loadTestsFromTestCase(case))
    res = unittest.TextTestRunner(verbosity=2, resultclass=_Result).run(suite)
    write_junit(res, time.time() - started)
    print(f"JUnit XML written to {os.path.abspath(REPORT_PATH)}")
    sys.exit(0 if res.wasSuccessful() else 1)
