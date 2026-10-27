"""The Inputs-menu hijack note (#805), and the theme key it explains.

Documentation only, so the tests pin what a reader has to be able to act on: the intent the launcher
sends for an HDMI row, the command that names the app which would take it, why `live_tv_package`
cannot help, and the one workaround that needs no rebuild. If the launcher ever restricts the row to
a TV-input package, the bullet has to lose its "until" clause - that is a doc change, not a test change.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
THEMES = ROOT / "themes" / "README.md"
ISSUE = "https://github.com/Gathin23/cleanyourtv/issues/805"
START = "- **Picking HDMI 1 in the Inputs menu opened an unrelated app**"
CHANNEL = "vnd.android.cursor.item/channel"

LINK = re.compile(r"\]\(([^)\s]+)\)")


def bullet(text, start):
    """The markdown bullet that begins with `start`, up to the next top-level bullet."""
    lines = text.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith(start))
    out = [lines[first]]
    for line in lines[first + 1:]:
        if line.startswith("- **") or line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


def broken_links(path):
    broken = []
    for target in LINK.findall(path.read_text(encoding="utf-8")):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        local = target.split("#", 1)[0]
        if local and not (path.parent / local).exists():
            broken.append(target)
    return broken


class InputIntentNoteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trouble_text = TROUBLE.read_text(encoding="utf-8")
        cls.theme_text = THEMES.read_text(encoding="utf-8")
        cls.note = bullet(cls.trouble_text, START)

    def test_bullet_exists_once(self):
        self.assertEqual(self.trouble_text.count(START), 1)

    def test_bullet_links_the_issue(self):
        self.assertIn(f"[#805]({ISSUE})", self.note)

    def test_bullet_says_the_intent_is_implicit(self):
        self.assertIn("implicit", self.note)
        self.assertIn(CHANNEL, self.note)
        self.assertIn("ACTION_VIEW", self.note)

    def test_bullet_gives_the_query_command_verbatim(self):
        # The flags are what make `query-activities` answer for the same intent the row sends; a
        # reordered or abbreviated command is what a reader would then report as "no handler".
        self.assertIn(
            "tools/tv.py 'cmd package query-activities --brief -a android.intent.action.VIEW "
            f"-t {CHANNEL}'",
            self.note,
        )

    def test_bullet_names_the_app_that_took_the_row(self):
        self.assertIn(
            "com.estrongs.android.pop/.app.transferstation.FileTransferStationActivity", self.note
        )

    def test_bullet_explains_why_live_tv_package_does_not_help(self):
        self.assertIn("`live_tv_package`", self.note)
        self.assertIn("`setPackage`", self.note)
        self.assertIn("Live TV row", self.note)

    def test_bullet_contrasts_the_two_failures(self):
        self.assertIn('"Not available on this TV"', self.note)

    def test_bullet_gives_the_workaround_with_both_keys(self):
        self.assertIn("`source_panel_service`", self.note)
        self.assertIn("`live_tv_package` empty", self.note)
        self.assertIn("source panel", self.note)

    def test_bullet_does_not_promise_a_launcher_fix(self):
        self.assertIn("needs\n  a source change, not a setting", self.note.replace("\r", ""))

    def test_theme_row_says_when_input_action_is_reached(self):
        rows = [line for line in self.theme_text.split("\n")
                if line.startswith("| `input_action` |")]
        self.assertEqual(len(rows), 1, "the input_action row moved or was duplicated")
        row = rows[0]
        self.assertIn("nothing handles the standard `VIEW`", row)
        self.assertIn("query-activities", row)
        self.assertIn(f"[#805]({ISSUE})", row)

    def test_theme_readme_still_lists_the_workaround_keys(self):
        self.assertIn("| `source_panel_service`, `source_panel_action` |", self.theme_text)
        self.assertIn("| `live_tv_package`, `live_tv_action` |", self.theme_text)

    def test_no_broken_relative_links(self):
        self.assertEqual(broken_links(TROUBLE), [])
        self.assertEqual(broken_links(THEMES), [])


if __name__ == "__main__":
    unittest.main()
