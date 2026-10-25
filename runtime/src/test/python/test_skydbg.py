"""Desktop tests for the debugger driver (runtime/src/main/assets/debugger/skydbg.py).

Run from the repository root with Python 3.10+ (the app bundles 3.14):

    py -3.14 -m unittest discover -s runtime/src/test/python -v

Each test runs a small script under the driver in a child process, exactly as the app does, and
talks to it over the side channel: a Unix socket where the platform has them (Linux, Android),
localhost TCP otherwise (Windows; that variant exists for these tests only).
"""

import json
import os
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
DRIVER = os.path.join(ROOT, "runtime", "src", "main", "assets", "debugger", "skydbg.py")
TIMEOUT = 15


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Session:
    """The driver running one script, plus a minimal client of its protocol."""

    def __init__(self, source, args=(), files=None, script="main.py"):
        self.tmp = tempfile.mkdtemp(prefix="dbg-test-")
        self.script = os.path.join(self.tmp, *script.split("/"))
        os.makedirs(os.path.dirname(self.script), exist_ok=True)
        for name, text in (files or {}).items():
            path = os.path.join(self.tmp, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(textwrap.dedent(text).lstrip("\n"))
        with open(self.script, "w", encoding="utf-8", newline="\n") as f:
            f.write(textwrap.dedent(source).lstrip("\n"))
        if hasattr(socket, "AF_UNIX"):
            self.address = os.path.join(self.tmp, "dbg.sock")
            spec = "unix:" + self.address
        else:
            self.address = ("127.0.0.1", _free_port())
            spec = "tcp:%s:%d" % self.address
        env = dict(os.environ, PYTHONUNBUFFERED="1", PYTHONIOENCODING="utf-8")
        self.proc = subprocess.Popen(
            [sys.executable, "-u", DRIVER, "--channel", spec, self.script, *args],
            cwd=self.tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
        )
        self.stdout = []
        self.stderr = []
        threading.Thread(target=self._drain, args=(self.proc.stdout, self.stdout), daemon=True).start()
        threading.Thread(target=self._drain, args=(self.proc.stderr, self.stderr), daemon=True).start()
        self.sock = self._connect()
        self.reader = self.sock.makefile("r", encoding="utf-8")
        self.events = queue.Queue()
        self.responses = {}
        self.cond = threading.Condition()
        self.next_id = 1
        threading.Thread(target=self._read, daemon=True).start()
        self.ready = self.event("ready")

    @staticmethod
    def _drain(stream, sink):
        for chunk in iter(lambda: stream.read1(4096) if hasattr(stream, "read1") else stream.read(1), b""):
            sink.append(chunk)

    def _connect(self):
        deadline = time.time() + TIMEOUT
        while True:
            try:
                if isinstance(self.address, str):
                    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                else:
                    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.connect(self.address)
                return s
            except OSError:
                s.close()
                if time.time() > deadline or self.proc.poll() is not None:
                    raise AssertionError("driver did not listen: " + b"".join(self.stderr).decode())
                time.sleep(0.05)

    def _read(self):
        try:
            lines = list(self._lines())
        except OSError:
            lines = []
        self.events.put({"event": "<closed>"})

    def _lines(self):
        for line in self.reader:
            message = json.loads(line)
            if "event" in message:
                self.events.put(message)
            else:
                with self.cond:
                    self.responses[message["id"]] = message
                    self.cond.notify_all()
            yield message

    def request(self, method, **params):
        rid = self.next_id
        self.next_id += 1
        data = json.dumps({"id": rid, "method": method, "params": params}) + "\n"
        self.sock.sendall(data.encode("utf-8"))
        with self.cond:
            if not self.cond.wait_for(lambda: rid in self.responses, TIMEOUT):
                raise AssertionError("no response to %s" % method)
            response = self.responses.pop(rid)
        if "error" in response:
            raise RuntimeError(response["error"])
        return response["result"]

    def event(self, name):
        deadline = time.time() + TIMEOUT
        while True:
            left = deadline - time.time()
            if left <= 0:
                raise AssertionError("no %s event; stderr: %s" % (name, self.err))
            try:
                e = self.events.get(timeout=left)
            except queue.Empty:
                continue
            if e["event"] == name:
                return e
            if e["event"] == "<closed>":
                raise AssertionError("channel closed waiting for %s; stderr: %s" % (name, self.err))

    def stopped(self):
        return self.event("stopped")

    def breakpoints(self, *lines, conditions=None):
        conditions = conditions or {}
        return self.request(
            "setBreakpoints", file=self.script,
            breakpoints=[{"line": n, "condition": conditions.get(n)} for n in lines],
        )

    def start(self, stop_on_entry=False, user_roots=None):
        if user_roots is None:
            return self.request("start", stopOnEntry=stop_on_entry)
        return self.request("start", stopOnEntry=stop_on_entry, userRoots=user_roots)

    def locals(self, frame_id=0, scope="locals"):
        result = self.request("variables", frameId=frame_id, scope=scope)
        return {v["name"]: v for v in result["variables"]}

    def resume(self, how="continue"):
        self.request(how)
        self.event("continued")

    def finish(self):
        """Waits for the exit: (exit event code, process return code)."""
        code = self.event("exited")["code"]
        rc = self.proc.wait(TIMEOUT)
        return code, rc

    @property
    def out(self):
        return b"".join(self.stdout).decode("utf-8", "replace")

    @property
    def err(self):
        return b"".join(self.stderr).decode("utf-8", "replace")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.wait(TIMEOUT)
        for s in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            s.close()
        shutil.rmtree(self.tmp, ignore_errors=True)


def same_file(a, b):
    return os.path.normcase(os.path.realpath(a)) == os.path.normcase(os.path.realpath(b))


class DebuggerTest(unittest.TestCase):
    def session(self, source, **kwargs):
        s = Session(source, **kwargs)
        self.addCleanup(s.close)
        return s

    def test_ready_event(self):
        s = self.session("x = 1\n")
        self.assertEqual(s.ready["protocol"], "1")
        self.assertEqual(s.ready["pid"], s.proc.pid)

    def test_runs_to_the_end_without_breakpoints(self):
        s = self.session("""
            print("hello")
            x = 6 * 7
            print(x)
        """)
        s.start()
        self.assertEqual(s.finish(), (0, 0))
        self.assertEqual(s.out.replace("\r\n", "\n"), "hello\n42\n")

    def test_breakpoint_hit_reports_file_line_and_locals(self):
        s = self.session("""
            def area(w, h):
                result = w * h
                return result

            total = area(3, 4)
            print(total)
        """)
        verified = s.breakpoints(2)
        self.assertEqual(verified, [{"line": 2, "actualLine": 2, "verified": True}])
        s.start()
        stop = s.stopped()
        self.assertEqual(stop["reason"], "breakpoint")
        top = stop["frames"][0]
        self.assertTrue(same_file(top["file"], s.script))
        self.assertEqual((top["name"], top["line"], top["hasLocals"]), ("area", 2, True))
        self.assertEqual([f["name"] for f in stop["frames"]], ["area", "<module>"])
        self.assertEqual(stop["frames"][1]["line"], 5)
        self.assertFalse(stop["frames"][1]["hasLocals"])
        names = s.locals()
        self.assertEqual(names["w"]["value"], "3")
        self.assertEqual(names["h"]["type"], "int")
        self.assertNotIn("result", names)
        s.resume()
        self.assertEqual(s.finish(), (0, 0))
        self.assertEqual(s.out.strip(), "12")

    def test_breakpoint_on_a_blank_line_moves_to_the_next_code_line(self):
        s = self.session("""
            a = 1

            # comment
            b = 2
        """)
        self.assertEqual(s.breakpoints(2), [{"line": 2, "actualLine": 4, "verified": True}])
        bad = s.breakpoints(40)[0]
        self.assertFalse(bad["verified"])
        self.assertIn("message", bad)

    def test_conditional_breakpoint(self):
        s = self.session("""
            total = 0
            for i in range(10):
                total += i
            print(total)
        """)
        s.breakpoints(3, conditions={3: "i == 7"})
        s.start()
        stop = s.stopped()
        self.assertEqual(stop["frames"][0]["line"], 3)
        self.assertEqual(s.locals(scope="globals")["i"]["value"], "7")
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_breakpoint_hit_on_every_pass(self):
        s = self.session("""
            for i in range(3):
                x = i
        """)
        s.breakpoints(2)
        s.start()
        seen = []
        for _ in range(3):
            s.stopped()
            seen.append(s.locals(scope="globals")["i"]["value"])
            s.resume()
        self.assertEqual(seen, ["0", "1", "2"])
        self.assertEqual(s.finish(), (0, 0))

    def test_step_over_does_not_enter_calls(self):
        s = self.session("""
            def f():
                return 1

            a = f()
            b = 2
        """)
        s.breakpoints(4)
        s.start()
        self.assertEqual(s.stopped()["frames"][0]["line"], 4)
        s.resume("next")
        stop = s.stopped()
        self.assertEqual(stop["reason"], "step")
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("<module>", 5))

    def test_step_into_and_out(self):
        s = self.session("""
            def f():
                x = 1
                y = 2
                return x + y

            a = f()
            b = a * 2
        """)
        s.breakpoints(6)
        s.start()
        s.stopped()
        s.resume("stepIn")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("f", 2))
        s.resume("next")
        self.assertEqual(s.stopped()["frames"][0]["line"], 3)
        s.resume("stepOut")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("<module>", 7))
        self.assertEqual(s.locals(scope="globals")["a"]["value"], "3")
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_step_over_at_the_end_of_a_function_returns_to_the_caller(self):
        s = self.session("""
            def f():
                return 5

            a = f()
            b = a
        """)
        s.breakpoints(2)
        s.start()
        s.stopped()
        s.resume("next")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("<module>", 5))

    LIB = """
        def twice(fn, x):
            y = fn(x)
            return fn(y)

        def plain(x):
            z = x + 1
            return z
    """

    APP = """
        import sys, os
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
        import helpers

        def inc(v):
            return v + 1

        a = helpers.plain(1)
        b = helpers.twice(inc, a)
        c = b
    """

    def app_session(self):
        s = self.session(self.APP, script="app/main.py", files={"lib/helpers.py": self.LIB})
        return s, [os.path.dirname(s.script)]

    def test_just_my_code_steps_over_library_code(self):
        s, roots = self.app_session()
        s.breakpoints(8)
        s.start(user_roots=roots)
        s.stopped()
        s.resume("stepIn")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("<module>", 9))
        self.assertTrue(stop["frames"][0]["user"])
        self.assertEqual(s.locals(scope="globals")["a"]["value"], "2")
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_just_my_code_stops_in_callbacks_from_library_code(self):
        s, roots = self.app_session()
        s.breakpoints(9)
        s.start(user_roots=roots)
        s.stopped()
        s.resume("stepIn")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("inc", 6))
        # The library frame between them is shown, marked as not the user's.
        self.assertEqual([f["name"] for f in stop["frames"]], ["inc", "twice", "<module>"])
        self.assertEqual([f["user"] for f in stop["frames"]], [True, False, True])
        self.assertEqual(s.locals()["v"]["value"], "2")
        # Returning into library code: the next user line is the second callback.
        s.resume("next")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], s.locals()["v"]["value"]), ("inc", "3"))
        s.resume("stepOut")
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("<module>", 10))
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_breakpoints_in_library_code_still_stop(self):
        s, roots = self.app_session()
        lib = os.path.join(s.tmp, "lib", "helpers.py")
        s.request("setBreakpoints", file=lib, breakpoints=[{"line": 7}])
        s.start(user_roots=roots)
        stop = s.stopped()
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["user"]), ("plain", False))
        self.assertTrue(same_file(stop["frames"][0]["file"], lib))

    def test_stop_on_entry(self):
        s = self.session("""
            a = 1
            b = 2
        """)
        s.start(stop_on_entry=True)
        stop = s.stopped()
        self.assertEqual((stop["reason"], stop["frames"][0]["line"]), ("entry", 1))
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_breakpoint_on_the_first_line(self):
        s = self.session("""
            a = 1
            b = 2
        """)
        s.breakpoints(1)
        s.start()
        stop = s.stopped()
        self.assertEqual((stop["reason"], stop["frames"][0]["line"]), ("breakpoint", 1))

    def test_containers_expand_lazily_and_are_capped(self):
        s = self.session("""
            class Point:
                def __init__(self):
                    self.x = 1
                    self.y = [1, 2]

            data = {"name": "sky", "items": list(range(500)), "p": Point()}
            empty = []
            done = True
        """)
        s.breakpoints(8)
        s.start()
        s.stopped()
        g = s.locals(scope="globals")
        self.assertNotIn("__name__", g)
        self.assertEqual(g["empty"]["ref"], 0)
        self.assertGreater(g["data"]["ref"], 0)
        self.assertEqual(g["data"]["size"], 3)
        children = s.request("variables", ref=g["data"]["ref"])
        named = {v["name"]: v for v in children["variables"]}
        self.assertEqual(set(named), {"'name'", "'items'", "'p'"})
        self.assertEqual(named["'name'"]["value"], "'sky'")
        items = s.request("variables", ref=named["'items'"]["ref"])
        self.assertEqual(len(items["variables"]), 200)
        self.assertEqual(items["more"], 300)
        self.assertEqual(items["variables"][199]["name"], "[199]")
        point = s.request("variables", ref=named["'p'"]["ref"])
        self.assertEqual([v["name"] for v in point["variables"]], ["x", "y"])
        self.assertLessEqual(len(named["'items'"]["value"]), 300)

    def test_long_repr_is_truncated_and_broken_repr_is_reported(self):
        s = self.session("""
            class Bad:
                def __repr__(self):
                    raise ValueError("nope")

            big = "x" * 10000
            bad = Bad()
            done = True
        """)
        s.breakpoints(7)
        s.start()
        s.stopped()
        g = s.locals(scope="globals")
        self.assertLessEqual(len(g["big"]["value"]), 300)
        self.assertEqual(g["big"]["size"], 10000)
        self.assertIn("nope", g["bad"]["value"])

    def test_references_expire_when_the_program_runs_again(self):
        s = self.session("""
            data = [1, 2, 3]
            a = 1
            b = 2
        """)
        s.breakpoints(2)
        s.start()
        s.stopped()
        ref = s.locals(scope="globals")["data"]["ref"]
        s.resume("next")
        s.stopped()
        with self.assertRaises(RuntimeError):
            s.request("variables", ref=ref)

    def test_evaluate_in_the_selected_frame_returns_errors(self):
        s = self.session("""
            def inner(n):
                return n * 2

            def outer():
                m = 10
                return inner(m + 1)

            outer()
        """)
        s.breakpoints(2)
        s.start()
        stop = s.stopped()
        self.assertEqual([f["name"] for f in stop["frames"]], ["inner", "outer", "<module>"])
        self.assertEqual(s.request("evaluate", frameId=0, expression="n + 1")["value"], "12")
        self.assertEqual(s.request("evaluate", frameId=1, expression="m")["value"], "10")
        error = s.request("evaluate", frameId=0, expression="m")
        self.assertEqual(error, {"error": "NameError: name 'm' is not defined"})
        self.assertIn("ZeroDivisionError", s.request("evaluate", frameId=0, expression="1/0")["error"])
        self.assertIn("SyntaxError", s.request("evaluate", frameId=0, expression="n +")["error"])
        listed = s.request("evaluate", frameId=0, expression="[n, n]")
        self.assertGreater(listed["ref"], 0)
        self.assertEqual(s.request("variables", ref=listed["ref"])["variables"][1]["value"], "11")
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_uncaught_exception_stops_then_exits_with_code_1(self):
        s = self.session("""
            def divide(a, b):
                return a / b

            divide(1, 0)
        """)
        s.start()
        stop = s.stopped()
        self.assertEqual(stop["reason"], "exception")
        self.assertEqual(stop["exception"], "ZeroDivisionError: division by zero")
        self.assertEqual((stop["frames"][0]["name"], stop["frames"][0]["line"]), ("divide", 2))
        self.assertEqual(s.locals()["b"]["value"], "0")
        s.resume()
        self.assertEqual(s.finish(), (1, 1))
        self.assertIn("ZeroDivisionError", s.err)
        self.assertIn('main.py", line 2, in divide', s.err)
        self.assertNotIn("skydbg", s.err)
        self.assertNotIn("bdb.py", s.err)

    def test_caught_exceptions_do_not_stop(self):
        s = self.session("""
            try:
                1 / 0
            except ZeroDivisionError:
                pass
            print("ok")
        """)
        s.start()
        self.assertEqual(s.finish(), (0, 0))
        self.assertEqual(s.out.strip(), "ok")

    def test_sys_exit_code(self):
        s = self.session("""
            import sys
            print("bye")
            sys.exit(3)
        """)
        s.start()
        self.assertEqual(s.finish(), (3, 3))

    def test_program_io_stays_on_stdin_and_stdout(self):
        s = self.session("""
            name = input("name? ")
            print("hi", name)
        """)
        s.breakpoints(2)
        s.start()
        s.proc.stdin.write(b"sky\n")
        s.proc.stdin.flush()
        s.stopped()
        self.assertEqual(s.locals(scope="globals")["name"]["value"], "'sky'")
        s.resume()
        self.assertEqual(s.finish(), (0, 0))
        self.assertEqual(s.out.replace("\r\n", "\n"), "name? hi sky\n")

    def test_script_sees_its_own_argv_main_and_path(self):
        s = self.session("""
            import sys, os
            print(__name__, os.path.basename(sys.argv[0]), sys.argv[1:])
            print(os.path.basename(sys.path[0]) == os.path.basename(os.path.dirname(__file__)))
            try:
                import skydbg
                print("leak")
            except ImportError:
                print("isolated")
        """, args=("a", "b"))
        s.start()
        self.assertEqual(s.finish(), (0, 0))
        self.assertEqual(s.out.split(), ["__main__", "main.py", "['a',", "'b']", "True", "isolated"])

    def test_pause_a_busy_program(self):
        s = self.session("""
            n = 0
            while True:
                n += 1
        """)
        s.start()
        time.sleep(0.3)
        s.request("pause")
        stop = s.stopped()
        self.assertEqual(stop["reason"], "pause")
        self.assertIn(stop["frames"][0]["line"], (2, 3))
        n = int(s.locals(scope="globals")["n"]["value"])
        self.assertGreater(n, 0)

    def test_breakpoint_added_while_running(self):
        s = self.session("""
            import time
            def work(i):
                return i + 1

            i = 0
            while True:
                i = work(i)
        """)
        s.start()
        time.sleep(0.3)
        result = s.breakpoints(3)
        self.assertTrue(result[0]["verified"])
        stop = s.stopped()
        self.assertEqual((stop["reason"], stop["frames"][0]["name"]), ("breakpoint", "work"))

    def test_pause_and_breakpoints_hammered_while_running(self):
        # Pauses and breakpoint updates arrive as signals at arbitrary points, also while the
        # main thread is inside the driver's own locked sections (sending "continued", ...).
        s = self.session("""
            def work(i):
                return i + 1

            i = 0
            while True:
                i = work(i)
        """)
        s.start()
        for _ in range(15):
            for _ in range(3):
                s.breakpoints()
            s.request("pause")
            self.assertEqual(s.stopped()["reason"], "pause")
            s.request("continue")  # no wait for "continued": the next requests race with it
        s.event("continued")
        result = s.breakpoints(2)
        self.assertTrue(result[0]["verified"])
        while True:
            stop = s.stopped()
            if stop["reason"] == "breakpoint":
                break
            s.resume()  # a late pause from the loop above
        self.assertEqual(stop["frames"][0]["name"], "work")

    def test_breakpoints_cleared_while_stopped(self):
        s = self.session("""
            for i in range(3):
                x = i
            print("done")
        """)
        s.breakpoints(2)
        s.start()
        s.stopped()
        self.assertEqual(s.breakpoints(), [])
        s.resume()
        self.assertEqual(s.finish(), (0, 0))

    def test_stepping_commands_need_a_stopped_program(self):
        s = self.session("""
            while True:
                pass
        """)
        s.start()
        with self.assertRaises(RuntimeError):
            s.request("next")

    def test_stop_ends_the_process(self):
        s = self.session("""
            while True:
                pass
        """)
        s.start()
        s.request("stop")
        self.assertEqual(s.proc.wait(TIMEOUT), 137)

    def test_ide_disconnect_lets_the_program_finish(self):
        s = self.session("""
            for i in range(3):
                x = i
            print("finished")
        """)
        s.breakpoints(2)
        s.start()
        s.stopped()
        s.sock.shutdown(socket.SHUT_RDWR)
        s.sock.close()
        self.assertEqual(s.proc.wait(TIMEOUT), 0)
        time.sleep(0.1)
        self.assertIn("finished", s.out)


if __name__ == "__main__":
    unittest.main()
