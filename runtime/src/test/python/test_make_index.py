"""Desktop tests for the wheel index builder (.github/wheels/make_index.py)."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
SCRIPT = os.path.join(ROOT, ".github", "wheels", "make_index.py")


class MakeIndexTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="make-index-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.site = os.path.join(self.tmp, "site")
        os.makedirs(self.site)

    def wheel(self, name, version, arch, payload="x"):
        """A minimal wheel with METADATA, in its own folder (as CI artifacts are)."""
        folder = os.path.join(self.tmp, "in", payload)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, f"{name}-{version}-cp314-cp314-android_24_{arch}.whl")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr(
                f"{name}-{version}.dist-info/METADATA",
                f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\nSummary: Test {name}\n",
            )
            z.writestr(f"{name}/data.txt", payload)
        return path

    def run_script(self, *args):
        subprocess.run([sys.executable, SCRIPT, "--site", self.site, *args], check=True, capture_output=True)
        with open(os.path.join(self.site, "index.json"), encoding="utf-8") as f:
            return json.load(f)

    def test_index_lists_each_wheel_with_its_android_abi_size_and_sha256(self):
        a = self.wheel("demo", "1.0", "arm64_v8a")
        b = self.wheel("demo", "1.0", "x86_64")
        helper = self.wheel("helper", "2.0", "arm64_v8a")
        index = self.run_script("--requires", "demo=helper", a, b, helper)
        self.assertEqual(index["schema"], 1)
        self.assertEqual(index["python"], "cp314")
        entries = {e["abi"]: e for e in index["packages"]["demo"]}
        self.assertEqual(set(entries), {"arm64-v8a", "x86_64"})
        e = entries["arm64-v8a"]
        published = os.path.join(self.site, e["file"])
        with open(published, "rb") as f:
            data = f.read()
        self.assertEqual(e["size"], len(data))
        self.assertEqual(e["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(e["version"], "1.0")
        self.assertEqual(e["requires"], ["helper"])
        self.assertEqual(e["summary"], "Test demo")

    def test_a_requirement_missing_from_the_index_is_refused(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_script("--requires", "demo=helper", self.wheel("demo", "1.0", "x86_64"))

    def test_a_published_wheel_is_never_overwritten(self):
        first = self.wheel("demo", "1.0", "x86_64", payload="first")
        before = self.run_script(first)["packages"]["demo"][0]["sha256"]
        rebuilt = self.wheel("demo", "1.0", "x86_64", payload="rebuilt")
        after = self.run_script(rebuilt)["packages"]["demo"][0]["sha256"]
        self.assertEqual(before, after)

    def test_a_new_version_replaces_the_old_one_for_that_abi(self):
        self.run_script(self.wheel("demo", "1.0", "x86_64", payload="old"))
        index = self.run_script(self.wheel("demo", "1.1", "x86_64", payload="new"))
        self.assertEqual([e["version"] for e in index["packages"]["demo"]], ["1.1"])
        self.assertEqual([n for n in os.listdir(self.site) if n.endswith(".whl")],
                         ["demo-1.1-cp314-cp314-android_24_x86_64.whl"])


if __name__ == "__main__":
    unittest.main()
