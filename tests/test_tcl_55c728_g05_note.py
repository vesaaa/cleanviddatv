"""Pins the 55C728 G05_4K_GB note and the G05 header correction it exists for.

The note is one owner's same-day report (#734), not a list: it has to keep saying so, keep the three
G05 setups apart by build, and keep the packages, keycodes and input URIs that owner actually
reported. The US list header used to call itself "the one report so far" where disabling setupwraith
did not stick, which two G05_4K_GB reports contradict, so that sentence is pinned out.
Whitespace is flattened before matching because every file here wraps its prose.
"""
import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
PAGE = ROOT / "docs" / "devices" / "tcl-55c728-g05-4k-gb.md"
US_LIST = ROOT / "examples" / "tcl-g05-google-tv" / "debloat.txt"
C735 = ROOT / "examples" / "tcl-65c735" / "debloat.txt"
EXAMPLES = ROOT / "examples" / "README.md"
NOTE_LINK = "../docs/devices/tcl-55c728-g05-4k-gb.md"

DISABLED = (
    "com.tcl.ttvs",
    "com.tcl.usercenter",
    "com.google.android.feedback",
    "com.tcl.waterfall.overseas",
    "com.tcl.browser",
    "com.tcl.channelplus",
    "com.tcl.messagebox",
    "com.tcl.tv.tclhome_passive",
    "com.tcl.esticker",
    "com.tcl.partnercustomizer",
    "android.autoinstalls.config.google.gtvpai",
    "com.tcl.guard",
    "com.android.printspooler",
    "com.google.android.play.games",
    "com.tcl.ocean.instructions",
    "com.amazon.amazonvideo.livingroom",
    "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic",
    "com.google.android.videos",
    "com.tcl.UpdatePeripheral",
    "com.tcl.gallery",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tungsten.setupwraith",
)

FACTORY_OFF = (
    "com.mediatek.AirplayAPK",
    "com.tcl.airplay2",
    "com.tcl.smartalexa",
    "com.tcl.gamebar",
    "com.tcl.logkit",
    "com.tcl.notereminder",
)


def flat(text):
    return re.sub(r"\s+", " ", text)


def links(text, base=None):
    targets = re.findall(r"\]\(([^)\s]+)\)", text)
    if base is None:
        return targets
    return [str((base / target).resolve()) for target in targets if not target.startswith("http")]


class Tcl55c728G05NoteTest(unittest.TestCase):
    def setUp(self):
        self.page = PAGE.read_text(encoding="utf-8")
        self.flat = flat(self.page)
        self.us = US_LIST.read_text(encoding="utf-8")
        self.us_flat = flat(self.us)
        self.examples = EXAMPLES.read_text(encoding="utf-8")

    def test_identifies_the_firmware_not_the_retail_name(self):
        self.assertIn("55C728", self.flat)
        self.assertIn("`G05_4K_GB`", self.flat)
        self.assertIn("TCL/G05_4K_GB/G05:11/AR2101/AR28", self.flat)
        self.assertIn("`com.tcl.tvinput`", self.flat)
        self.assertIn("AR68", self.flat, "the US incremental is what separates the two answers")

    def test_stays_honest_about_how_little_was_tested(self):
        self.assertIn("this is a note, not a list", self.flat)
        self.assertIn("same-day", self.flat)
        self.assertIn("has not lived with", self.flat)
        self.assertIn("did not exercise an undo", self.flat)
        self.assertIn("launcher/build-and-install.sh", self.flat)
        self.assertIn("`launcher/build-and-install.sh`, its rollback and the b64push transfer", self.flat)

    def test_keeps_the_three_g05_setups_apart(self):
        for needle in ("stayed off", "came back", "`AR2101`", "`AR68`"):
            self.assertIn(needle, self.flat)
        self.assertIn("the exception among the three G05 reports, not the rule", self.flat)
        self.assertIn("RecoveryActivity", self.flat)

    def test_records_the_packages_the_owner_disabled(self):
        self.assertIn("That is 23 packages", self.flat)
        for package in DISABLED:
            with self.subTest(package=package):
                self.assertIn(f"`{package}`", self.page)
        for package in FACTORY_OFF:
            with self.subTest(factory_off=package):
                self.assertIn(f"`{package}`", self.page)

    def test_names_what_this_owner_kept_instead(self):
        for package in ("com.tcl.dashboard", "com.tcl.suspension", "com.tcl.ui_mediaCenter",
                        "com.tcl.UpdatePeripheral"):
            self.assertIn(f"`{package}`", self.page)
        self.assertIn("all three of which the US list disables", self.flat)

    def test_records_the_remote_keycodes_and_scancodes(self):
        for code in ("4062", "4099", "178", "4077", "566", "752", "471", "561"):
            self.assertIn(code, self.flat)
        self.assertIn("com.android.tv.action.VIEW_INPUTS", self.flat)

    def test_records_the_vendor_input_uris(self):
        for uri in ("HW1413742848", "HW1413744128", "HW1413744384", "HW1413744640",
                    "HW1413745664", "HW1413743104"):
            self.assertIn(uri, self.page)
        self.assertIn("com.tcl.tv/.TVActivity", self.flat)

    def test_sends_the_reader_to_the_list_of_the_same_board(self):
        us = US_LIST.resolve()
        self.assertIn(str(us), links(self.page, PAGE.parent))
        self.assertIn(str(C735.resolve()), links(self.page, PAGE.parent))
        self.assertTrue(us.is_file() and C735.is_file())

    def test_every_relative_link_resolves(self):
        for target in links(self.page, PAGE.parent):
            with self.subTest(target=target):
                self.assertTrue(Path(target).exists(), "dead link from the note")

    def test_us_header_no_longer_claims_to_be_the_only_one(self):
        self.assertNotIn("the one report so far", self.us_flat)
        self.assertIn("this US build is the exception", self.us_flat)
        self.assertIn("tcl-55c728-g05-4k-gb.md", self.us_flat)
        self.assertIn("AR28", self.us_flat)
        self.assertIn("ro.build.version.incremental", self.us_flat)

    def test_examples_readme_indexes_the_note_and_the_row(self):
        self.assertIn(f"[55C728 note]({NOTE_LINK})", self.examples)
        bullet = "[TCL `55C728`](../docs/devices/tcl-55c728-g05-4k-gb.md)"
        self.assertIn(bullet, self.examples)
        self.assertIn("the G05_4K_GB units kept it off", flat(self.examples))


if __name__ == "__main__":
    unittest.main()
