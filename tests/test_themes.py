"""Checks every theme in themes/: a config.json that parses, a screenshot and a row in its README.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import struct
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
THEMES = ROOT / "themes"
MAX_SCREENSHOT = 300 * 1024


def folders():
    return sorted(p for p in THEMES.iterdir() if p.is_dir())


def jpeg_size(data):
    """(width, height) from the first SOF marker, or None if this is not a JPEG."""
    if data[:2] != b"\xff\xd8":
        return None
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            return None
        marker = data[i + 1]
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height, width = struct.unpack(">HH", data[i + 5:i + 9])
            return width, height
        i += 2 + length
    return None


class ThemesTest(unittest.TestCase):
    def test_every_theme_has_a_config(self):
        for folder in folders():
            with self.subTest(theme=folder.name):
                config = folder / "config.json"
                self.assertTrue(config.is_file(), "no config.json")
                self.assertIsInstance(json.loads(config.read_text(encoding="utf-8")), dict)

    def test_every_theme_has_a_screenshot(self):
        for folder in folders():
            with self.subTest(theme=folder.name):
                shot = folder / "screenshot.jpg"
                self.assertTrue(shot.is_file(),
                                "no screenshot.jpg: add one of Home on a real TV with this theme, see themes/README.md")
                data = shot.read_bytes()
                self.assertLessEqual(len(data), MAX_SCREENSHOT, "screenshot.jpg is over 300 KB")
                size = jpeg_size(data)
                self.assertIsNotNone(size, "screenshot.jpg is not a JPEG")
                width, height = size
                self.assertEqual(width * 9, height * 16, f"screenshot.jpg is {width}x{height}, not 16:9")
                self.assertLessEqual(width, 1920, "screenshot.jpg is wider than 1920; 1280x720 is enough")

    def test_readme_lists_every_theme_with_its_screenshot(self):
        readme = (THEMES / "README.md").read_text(encoding="utf-8")
        for folder in folders():
            with self.subTest(theme=folder.name):
                row = next((l for l in readme.split("\n") if l.startswith(f"| `{folder.name}/` |")), None)
                self.assertIsNotNone(row, "no row in themes/README.md")
                self.assertIn(f"{folder.name}/screenshot.jpg", row, "the README row does not show the screenshot")


if __name__ == "__main__":
    unittest.main()
