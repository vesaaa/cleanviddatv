"""Checks the example theme files that no other test reads.

`examples/*/config.json` is what `tools/theme.sh` pushes to a TV, so a typo in `app_keys` reaches the
device as a button that silently opens nothing. This pins the G03 file, which now carries the three
app buttons from the second unit in #791: the mappings have to be well formed, point at apps that
exist (listed here, or the vendor YouTube app), and keep the Live TV and gear settings they sit next to.
The debloat header is prose and is deliberately not matched here.
"""
import json
import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "examples" / "tcl-g03-google-tv"
CONFIG_PATH = EXAMPLE / "config.json"
LIST_PATH = EXAMPLE / "debloat.txt"

APP_KEY = re.compile(r"^(KEYCODE_[A-Z0-9_]+|[0-9]+):(.+)$")
PACKAGE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
# Buttons that #791 mapped. Netflix and Prime Video are in the list; YouTube is the kept system app.
EXPECTED = {
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.google.android.youtube.tv",
}


def listed_packages():
    packages = []
    for line in LIST_PATH.read_text(encoding="utf-8").split("\n"):
        body = line.split("#", 1)[0].strip()
        if body:
            packages.append(body)
    return packages


class G03ThemeTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        self.app_keys = self.config.get("app_keys") or []

    def test_app_keys_are_keycode_package_pairs(self):
        self.assertTrue(self.app_keys, "the second unit's three buttons live here")
        for entry in self.app_keys:
            with self.subTest(entry=entry):
                match = APP_KEY.match(entry)
                self.assertIsNotNone(match, "expected keycode:package, or scan:code:package for scancode remotes")
                assert match is not None
                self.assertRegex(match.group(2), PACKAGE)

    def test_app_keys_open_the_three_buttons(self):
        targets = set()
        for entry in self.app_keys:
            match = APP_KEY.match(entry)
            self.assertIsNotNone(match, entry)
            assert match is not None
            targets.add(match.group(2))
        self.assertEqual(targets, EXPECTED,
                         "Netflix, YouTube and Prime Video are the buttons the M1 remote stopped sending")
        keycodes = {entry.split(":", 1)[0] for entry in self.app_keys}
        self.assertTrue({"KEYCODE_TCL_NETFLIX", "KEYCODE_TCL_YOUTUBE"} <= keycodes,
                        "the vendor keycodes are what getevent -lt reports for those buttons")

    def test_no_keycode_is_mapped_twice(self):
        keycodes = [entry.split(":", 1)[0] for entry in self.app_keys]
        self.assertEqual(sorted(keycodes), sorted(set(keycodes)), "one button, one app")

    def test_mapped_apps_are_the_ones_this_list_knows(self):
        listed = listed_packages()
        self.assertIn("com.netflix.ninja", listed, "a mapping to an app the list does not list or keep is a dead button")
        self.assertIn("com.amazon.amazonvideo.livingroom", listed)
        self.assertIn("com.google.android.youtube.tv", EXPECTED, "YouTube is preinstalled and kept")

    def test_keeps_the_live_tv_and_gear_settings(self):
        self.assertEqual(self.config["live_tv_package"], "com.tcl.tv")
        self.assertEqual(self.config["settings_key_keycodes"], [4077])
        self.assertTrue(self.config["settings_key_panel"])


if __name__ == "__main__":
    unittest.main()
