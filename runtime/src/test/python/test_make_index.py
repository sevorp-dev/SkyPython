"""Desktop tests for the wheel index builder (.github/wheels/make_index.py)."""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import unquote

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
SCRIPT = os.path.join(ROOT, ".github", "wheels", "make_index.py")


class MakeIndexTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="make-index-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def build(self, *names):
        paths = []
        for name in names:
            path = os.path.join(self.tmp, name)
            with open(path, "wb") as f:
                f.write(name.encode())
            paths.append(path)
        site = os.path.join(self.tmp, "site")
        subprocess.run(
            [sys.executable, SCRIPT, "--site", site, "--url-base", "https://example.org/dl/"] + paths,
            check=True, capture_output=True,
        )
        return site

    def test_file_names_are_url_quoted_in_links(self):
        name = "foo-1.0+sky.1-cp314-cp314-android_24_arm64_v8a.whl"
        site = self.build(name)
        with open(os.path.join(site, "simple", "foo", "index.html"), encoding="utf-8") as f:
            text = f.read()
        [href] = re.findall(r'href="([^"]*)"', text)
        url, _, fragment = href.partition("#")
        self.assertEqual(url, "https://example.org/dl/foo-1.0%2Bsky.1-cp314-cp314-android_24_arm64_v8a.whl")
        self.assertEqual(unquote(url.rsplit("/", 1)[1]), name)
        self.assertTrue(fragment.startswith("sha256="))
        self.assertIn(">" + name + "<", text)  # the link text stays readable


if __name__ == "__main__":
    unittest.main()
