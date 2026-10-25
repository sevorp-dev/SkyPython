"""Desktop tests for the language server (runtime/src/main/langserver/skylang_server.py).

Run from the repository root with Python 3.10+ (the app bundles 3.14):

    ./gradlew :runtime:langServerBundle          # builds runtime/src/main/assets/langserver/langserver.zip
    py -3.14 -m unittest discover -s runtime/src/test/python -v

The tests run against the bundle the app ships (jedi + parso + pyflakes, trimmed typeshed),
unpacked to a temporary directory. Set SKYLANG_BUNDLE to use another zip or an unpacked
directory; without a bundle they fall back to jedi/pyflakes installed in this Python.
"""

import atexit
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
SERVER_SRC = os.path.join(ROOT, "runtime", "src", "main", "langserver", "skylang_server.py")
DEFAULT_BUNDLE = os.path.join(ROOT, "runtime", "src", "main", "assets", "langserver", "langserver.zip")


def _bundle_dir():
    """Directory holding jedi/parso/pyflakes plus a copy of the current server source."""
    bundle = os.environ.get("SKYLANG_BUNDLE") or (DEFAULT_BUNDLE if os.path.isfile(DEFAULT_BUNDLE) else None)
    tmp = tempfile.mkdtemp(prefix="skylang-test-")
    atexit.register(shutil.rmtree, tmp, True)
    if bundle and os.path.isdir(bundle):
        shutil.copytree(bundle, tmp, dirs_exist_ok=True)
    elif bundle:
        with zipfile.ZipFile(bundle) as z:
            z.extractall(tmp)
    # Always test the source in the tree, not a possibly stale copy inside the zip.
    shutil.copy(SERVER_SRC, os.path.join(tmp, "skylang_server.py"))
    return tmp, bool(bundle)


BUNDLE_DIR, FROM_BUNDLE = _bundle_dir()
sys.path.insert(0, BUNDLE_DIR)
import skylang_server as ls  # noqa: E402


class PositionsTest(unittest.TestCase):
    def test_utf16_round_trip_with_astral_chars(self):
        text = "s = '😀' + x"
        # 😀 is one str char but two UTF-16 units.
        self.assertEqual(ls.utf16_to_index(text, 7), 6)  # just after the emoji
        self.assertEqual(ls.index_to_utf16(text, 6), 7)
        self.assertEqual(ls.utf16_to_index(text, 999), len(text))
        self.assertEqual(ls.utf16_to_index(text, -3), 0)

    def test_utf8_offsets_from_ast(self):
        text = "é = 1; y"
        # ast reports `y` at byte 9 (é is two bytes), which is str index 8.
        self.assertEqual(ls.utf8_to_index(text, 9), 8)


class DiagnoseTest(unittest.TestCase):
    def test_clean_code_has_no_diagnostics(self):
        self.assertEqual(ls.diagnose("import os\nprint(os.sep)\n"), [])

    def test_unclosed_paren_is_a_syntax_error_on_line_1(self):
        [d] = ls.diagnose("x = (\n")
        self.assertEqual(d["severity"], "error")
        self.assertEqual(d["line"], 1)
        self.assertIn("SyntaxError", d["message"])
        self.assertIn("never closed", d["message"])
        self.assertEqual((d["column"], d["endLine"], d["endColumn"]), (4, 1, 5))  # the "("

    def test_syntax_error_at_end_of_line_marks_last_char(self):
        [d] = ls.diagnose("if True\n    pass\n")
        self.assertEqual(d["line"], 1)
        self.assertLess(d["column"], d["endColumn"])
        self.assertLessEqual(d["endColumn"], len("if True"))

    def test_indentation_error(self):
        [d] = ls.diagnose("def f():\nreturn 1\n")
        self.assertEqual((d["line"], d["severity"]), (2, "error"))

    def test_undefined_name_is_an_error_spanning_the_name(self):
        [d] = ls.diagnose("print(undefined_thing)\n")
        self.assertEqual(d["severity"], "error")
        self.assertIn("undefined name 'undefined_thing'", d["message"])
        self.assertEqual((d["line"], d["column"], d["endColumn"]), (1, 6, 6 + len("undefined_thing")))

    def test_unused_import_is_a_warning_on_the_module_name(self):
        [d] = ls.diagnose("import os, sys\nprint(sys)\n")
        self.assertEqual(d["severity"], "warning")
        self.assertIn("'os' imported but unused", d["message"])
        self.assertEqual((d["column"], d["endColumn"]), (7, 9))

    def test_columns_are_utf16_after_emoji(self):
        [d] = ls.diagnose("s = '😀'; print(nope)\n")
        # "s = '😀'; print(" is 15 str chars, 16 UTF-16 units.
        self.assertEqual((d["column"], d["endColumn"]), (16, 20))

    def test_crlf_lines(self):
        [d] = ls.diagnose("a = 1\r\nprint(b)\r\n")
        self.assertEqual((d["line"], d["column"]), (2, 6))

    def test_null_byte_does_not_crash(self):
        [d] = ls.diagnose("x = 1\0\n")
        self.assertEqual(d["severity"], "error")

    def test_empty_source(self):
        self.assertEqual(ls.diagnose(""), [])


class CompleteTest(unittest.TestCase):
    def names(self, source, line=None, column=None):
        lines = source.split("\n")
        line = line or len(lines)
        column = len(lines[line - 1]) if column is None else column
        return ls.complete(source, line, column)

    def test_import_js_offers_json(self):
        items = self.names("import js")
        json_item = next(i for i in items if i["name"] == "json")
        self.assertEqual(json_item["complete"], "on")
        self.assertEqual(json_item["type"], "module")

    def test_string_method(self):
        items = self.names('"abc".up')
        self.assertIn("upper", [i["name"] for i in items])

    def test_local_names_and_columns_in_the_middle_of_a_line(self):
        src = "counter = 1\nprint(cou)\n"
        items = self.names(src, line=2, column=len("print(cou"))
        self.assertIn("counter", [i["name"] for i in items])

    def test_bundle_is_not_importable_from_user_code(self):
        names = [i["name"] for i in self.names("import jed")]
        if FROM_BUNDLE:
            self.assertNotIn("jedi", names)

    def test_signature(self):
        sig = ls.signature("divmod(7, ", 1, len("divmod(7, "))
        self.assertEqual(sig["name"], "divmod")
        self.assertEqual(sig["index"], 1)
        self.assertEqual(len(sig["params"]), 2)
        self.assertIsNone(ls.signature("x = 1", 1, 5))


class ServerLoopTest(unittest.TestCase):
    """The request loop in-process: everything queued before the loop starts is one batch."""

    def run_server(self, *messages):
        inp = io.BytesIO(b"".join((json.dumps(m) + "\n").encode() for m in messages))
        out = io.BytesIO()
        server = ls.Server(inp, out)
        server.read_loop()  # queue everything, then EOF
        server.serve(start_reader=False)
        return {r["id"]: r for r in map(json.loads, out.getvalue().decode().splitlines())}

    def test_only_the_newest_request_of_a_method_is_answered(self):
        complete = {"source": "import js", "line": 1, "column": 9}
        got = self.run_server(
            {"id": 11, "method": "complete", "params": complete},
            {"id": 12, "method": "complete", "params": complete},
            {"id": 13, "method": "complete", "params": complete},
            {"id": 14, "method": "ping"},
        )
        self.assertEqual(got[11], {"id": 11, "cancelled": True})
        self.assertEqual(got[12], {"id": 12, "cancelled": True})
        self.assertIn("json", [i["name"] for i in got[13]["result"]])
        self.assertEqual(got[14]["result"], "pong")

    def test_explicit_cancel(self):
        got = self.run_server(
            {"id": 1, "method": "diagnose", "params": {"source": "x"}},
            {"method": "cancel", "params": {"id": 1}},
            {"id": 2, "method": "ping"},
        )
        self.assertEqual(got[1], {"id": 1, "cancelled": True})
        self.assertEqual(got[2]["result"], "pong")


class ServerProcessTest(unittest.TestCase):
    """The real process: stdin/stdout JSON lines."""

    def setUp(self):
        self.userbase = tempfile.mkdtemp(prefix="skylang-userbase-")
        self.addCleanup(shutil.rmtree, self.userbase, True)
        # Like the app: PYTHONUSERBASE points at a user site that doesn't exist yet.
        self.env = dict(os.environ, PYTHONUSERBASE=self.userbase)
        self.start("--no-warmup")

    def start(self, *args):
        self.proc = subprocess.Popen(
            [sys.executable, "-u", os.path.join(BUNDLE_DIR, "skylang_server.py"), *args],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=self.env,
        )
        self.assertEqual(self.read()["event"], "ready")

    def test_ready_comes_after_the_warm_up(self):
        self.tearDown()
        started = time.monotonic()
        self.start()  # with warm-up
        self.write({"id": 1, "method": "complete", "params": {"source": "import js", "line": 1, "column": 9}})
        self.assertIn("json", [i["name"] for i in self.read()["result"]])
        self.assertGreater(time.monotonic() - started, 0)

    def test_packages_installed_after_start_complete(self):
        user_site = subprocess.check_output(
            [sys.executable, "-c", "import site; print(site.getusersitepackages())"], env=self.env, text=True,
        ).strip()
        os.makedirs(os.path.join(user_site, "freshpkg"))
        with open(os.path.join(user_site, "freshpkg", "__init__.py"), "w") as f:
            f.write("def hello():\n    pass\n")
        self.write({"id": 1, "method": "complete",
                    "params": {"source": "import freshpkg\nfreshpkg.he", "line": 2, "column": 11}})
        self.assertIn("hello", [i["name"] for i in self.read()["result"]])

    def tearDown(self):
        if self.proc.poll() is None:
            self.proc.stdin.close()
            self.proc.wait(10)
        self.proc.stdout.close()

    def write(self, obj):
        self.proc.stdin.write((json.dumps(obj) + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def read(self):
        line = self.proc.stdout.readline()
        self.assertTrue(line, "server closed stdout")
        return json.loads(line)

    def test_ping_and_unknown_method(self):
        self.write({"id": 1, "method": "ping"})
        self.assertEqual(self.read(), {"id": 1, "result": "pong"})
        self.write({"id": 2, "method": "nope"})
        self.assertIn("unknown method", self.read()["error"])

    def test_garbage_lines_are_ignored(self):
        self.proc.stdin.write(b"not json\n[1,2]\n\n")
        self.write({"id": 3, "method": "ping"})
        self.assertEqual(self.read()["id"], 3)

    def test_complete_and_diagnose_with_non_ascii_source(self):
        self.write({"id": 4, "method": "diagnose", "params": {"source": "s = 'ж😀'\nprint(zz)\n"}})
        resp = self.read()
        self.assertEqual(resp["id"], 4)
        self.assertEqual(resp["result"][0]["line"], 2)
        self.write({"id": 5, "method": "complete", "params": {"source": "import js", "line": 1, "column": 9}})
        resp = self.read()
        self.assertIn("json", [i["name"] for i in resp["result"]])

    def test_bad_params_answer_with_an_error_and_keep_running(self):
        self.write({"id": 6, "method": "complete", "params": {}})
        self.assertIn("error", self.read())
        self.write({"id": 7, "method": "ping"})
        self.assertEqual(self.read()["result"], "pong")

    def test_exits_when_stdin_closes(self):
        self.proc.stdin.close()
        self.assertEqual(self.proc.wait(10), 0)

    def test_prints_do_not_corrupt_the_protocol(self):
        # A module that prints at import time, analysed by jedi in-process.
        folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, folder, True)
        with open(os.path.join(folder, "noisy.py"), "w") as f:
            f.write("print('hello from noisy')\nvalue = 1\n")
        path = os.path.join(folder, "main.py")
        self.write({"id": 20, "method": "complete",
                    "params": {"source": "import noisy\nnoisy.va", "line": 2, "column": 8, "path": path}})
        resp = self.read()
        self.assertEqual(resp["id"], 20)
        self.assertIn("value", [i["name"] for i in resp["result"]])


if __name__ == "__main__":
    unittest.main()
