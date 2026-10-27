"""Run install-vidda.sh against a simulated TV.

tools/disable.sh and launcher/build-and-install.sh have their own tests, so here they are stand-ins that only
disable packages on the fake TV. What is checked is install-vidda.sh's own work: the model check, and the restore
record the launcher's "Restore the original home screen" reads back (ViddaTools.RESTORE_*).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PKG = "io.github.vesaaa.cleanviddatv"

# Stands in for tools/tv.py: answers the commands install-vidda.sh sends and keeps the TV's state.
FAKE_TV = r'''
import json
import os
import re
import sys

path = os.environ["FAKE_TV_STATE"]
with open(path) as f:
    state = json.load(f)


def run(command):
    if command == "getprop ro.product.model":
        return state["model"] + "\n"
    if command == "getprop ro.build.version.sdk":
        return state["sdk"] + "\n"
    if command.startswith("pm path "):
        return "package:/system/app/x.apk\n" if command.split()[-1] in state["installed"] else ""
    if command == "pm list packages -d":
        return "".join("package:%s\n" % p for p in sorted(state["disabled"]))
    m = re.match(r"pm disable-user --user 0 (\S+)$", command)
    if m:
        if m.group(1) not in state["disabled"]:
            state["disabled"].append(m.group(1))
        return ""
    m = re.match(r"settings get (global|secure) (\S+)$", command)
    if m:
        return state["settings"].get(m.group(2), "null") + "\n"
    out = ""
    for part in command.split("; "):
        m = re.match(r"settings put (global|secure) (\S+) '?([^']*)'?$", part)
        if m:
            state["settings"][m.group(2)] = m.group(3)
        m = re.match(r"pm grant (\S+) (\S+)( && echo .*)?$", part)
        if m:
            state["granted"].append(m.group(2))
        m = re.match(r"appops set (\S+) (\S+) allow$", part)
        if m:
            state["granted"].append(m.group(2))
    return out


args = [a for a in sys.argv[1:] if a != "-I"]
command = args[-1]
state["commands"].append(command)
sys.stdout.write(run(command))
with open(path, "w") as f:
    json.dump(state, f)
'''

# Stand-ins for the two scripts install-vidda.sh calls; they talk to the same fake TV.
DISABLE_STUB = '''#!/usr/bin/env bash
for p in com.hisense.hitv.das com.keylab.speech.view.vidaa; do tools/venv/bin/python tools/tv.py "pm disable-user --user 0 $p"; done
'''
INSTALL_STUB = '''#!/usr/bin/env bash
cd "$(dirname "$0")"
echo "STOCK_LAUNCHER=$STOCK_LAUNCHER HOME_KEY_SERVICE=$HOME_KEY_SERVICE RESTORE_KEY_SERVICES=$RESTORE_KEY_SERVICES" > ../install-args
../tools/venv/bin/python ../tools/tv.py "pm disable-user --user 0 $STOCK_LAUNCHER"
'''


class InstallViddaTest(unittest.TestCase):
    def setUp(self):
        if os.name != "posix" or not shutil.which("bash"):
            raise unittest.SkipTest("install-vidda.sh is a POSIX script; it needs bash")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        shutil.copy2(ROOT / "install-vidda.sh", self.base)
        (self.base / "launcher").mkdir()
        (self.base / "tools" / "venv" / "bin").mkdir(parents=True)
        (self.base / "examples" / "vidda-mt9653").mkdir(parents=True)
        (self.base / "examples" / "vidda-mt9653" / "debloat.txt").write_text("com.hisense.hitv.das\n")
        for path, text in ((self.base / "tools" / "disable.sh", DISABLE_STUB),
                           (self.base / "launcher" / "build-and-install.sh", INSTALL_STUB)):
            path.write_text(text)
            path.chmod(0o755)
        fake = self.base / "fake_tv.py"
        fake.write_text(FAKE_TV)
        (self.base / "tools" / "tv.py").write_text("")
        client = self.base / "tools" / "venv" / "bin" / "python"
        client.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
        client.chmod(0o755)
        self.state_path = self.base / "tv-state.json"

    def install(self, state=None, **env_extra):
        tv = {
            "model": "VIDAA_TV",
            "sdk": "30",
            "installed": ["com.jamdeo.tv.vod"],
            "disabled": ["com.factory.off"],
            "settings": {"enabled_accessibility_services": "com.hisense.vidaaassistant/.AppAccessibilityService",
                         "adb_enabled": "1"},
            "granted": [],
            "commands": [],
        }
        tv.update(state or {})
        self.state_path.write_text(json.dumps(tv))
        env = {k: v for k, v in os.environ.items() if k not in ("KEEP_ADB", "FORCE_MODEL", "APK_FILE", "LIST")}
        env.update(TV_HOST="192.168.1.50", FAKE_TV_STATE=str(self.state_path), **env_extra)
        result = subprocess.run(["bash", str(self.base / "install-vidda.sh")], cwd=self.base, env=env,
                                capture_output=True, text=True, timeout=120)
        return result, json.loads(self.state_path.read_text())

    def test_records_what_it_disabled_for_the_restore(self):
        result, tv = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = tv["settings"]["cleanviddatv_disabled"].split(",")
        self.assertEqual(sorted(recorded),
                         ["com.hisense.hitv.das", "com.jamdeo.tv.vod", "com.keylab.speech.view.vidaa"])
        self.assertNotIn("com.factory.off", recorded, "disabled at the factory: the restore must leave it alone")
        self.assertEqual(tv["settings"]["cleanviddatv_stock_home"],
                         "com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity")
        self.assertEqual(tv["settings"]["cleanviddatv_a11y_before"],
                         "com.hisense.vidaaassistant/.AppAccessibilityService")

    def test_hands_home_over_with_the_helpers_and_the_permission(self):
        result, tv = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = (self.base / "install-args").read_text()
        self.assertIn("STOCK_LAUNCHER=com.jamdeo.tv.vod HOME_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1", args)
        self.assertEqual(tv["settings"]["enabled_accessibility_services"], PKG + "/.HomeKeyService")
        self.assertIn("android.permission.WRITE_SECURE_SETTINGS", tv["granted"])
        self.assertIn("MANAGE_EXTERNAL_STORAGE", tv["granted"], "the file picker needs all-files access")

    def test_turns_adb_off_at_the_end_unless_kept(self):
        _, tv = self.install()
        self.assertEqual(tv["settings"]["adb_enabled"], "0")
        self.assertIn("settings put global adb_enabled 0", tv["commands"][-1])
        _, tv = self.install(KEEP_ADB="1")
        self.assertEqual(tv["settings"]["adb_enabled"], "1")

    def test_second_run_adds_to_the_first_record(self):
        result, tv = self.install({"disabled": ["com.factory.off", "com.first.run"],
                                   "settings": {"cleanviddatv_disabled": "com.first.run",
                                                "cleanviddatv_stock_home": "first/home",
                                                "cleanviddatv_a11y_before": "first-a11y",
                                                "enabled_accessibility_services": PKG + "/.HomeKeyService"}})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("com.first.run", tv["settings"]["cleanviddatv_disabled"].split(","))
        self.assertIn("com.hisense.hitv.das", tv["settings"]["cleanviddatv_disabled"].split(","))
        self.assertEqual(tv["settings"]["cleanviddatv_stock_home"], "first/home")
        self.assertEqual(tv["settings"]["cleanviddatv_a11y_before"], "first-a11y")

    def test_refuses_another_model_without_touching_it(self):
        result, tv = self.install({"model": "BRAVIA 4K"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("VIDAA_TV", result.stderr)  # the refusal names the supported model
        self.assertEqual(tv["disabled"], ["com.factory.off"])
        self.assertFalse(any(c.startswith(("settings put", "pm ")) for c in tv["commands"]))

    def test_refuses_a_set_without_juui(self):
        result, tv = self.install({"installed": []})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(tv["disabled"], ["com.factory.off"])


if __name__ == "__main__":
    unittest.main()
