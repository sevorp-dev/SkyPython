"""Desktop tests for the matplotlib backend (runtime/src/main/assets/plot/skypython_plot.py).

Run from the repository root:

    python -m unittest discover -s runtime/src/test/python -v

Each test runs a script in a child process with the environment the app gives it
(MPLBACKEND=module://skypython_plot, the backend's folder on PYTHONPATH); the escape sequence that
would go to the terminal is written to a file instead (SKYPYTHON_PLOT_TTY). Tests that need
matplotlib are skipped where it is not installed; the message format is tested without it.
"""

import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import types
import unittest
from urllib.parse import unquote

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
BACKEND_DIR = os.path.join(ROOT, "runtime", "src", "main", "assets", "plot")
HAS_MPL = importlib.util.find_spec("matplotlib") is not None
SEQUENCE = re.compile("\033\\]7777;plot;([^\007]*)\007")


def _load_without_matplotlib():
    """The backend module with tiny stand-ins for the matplotlib parts it imports."""
    fake = {
        "matplotlib": types.ModuleType("matplotlib"),
        "matplotlib._pylab_helpers": types.ModuleType("matplotlib._pylab_helpers"),
        "matplotlib.backend_bases": types.ModuleType("matplotlib.backend_bases"),
        "matplotlib.backends": types.ModuleType("matplotlib.backends"),
        "matplotlib.backends.backend_agg": types.ModuleType("matplotlib.backends.backend_agg"),
    }
    fake["matplotlib"].is_interactive = lambda: False
    fake["matplotlib._pylab_helpers"].Gcf = type("Gcf", (), {})
    fake["matplotlib.backend_bases"].FigureManagerBase = type("FigureManagerBase", (), {})
    fake["matplotlib.backends.backend_agg"].FigureCanvasAgg = type("FigureCanvasAgg", (), {})
    saved = {name: sys.modules.get(name) for name in fake}
    sys.modules.update(fake)
    try:
        spec = importlib.util.spec_from_file_location("skypython_plot_fake", os.path.join(BACKEND_DIR, "skypython_plot.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        for name, old in saved.items():
            if old is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = old


class MessageTest(unittest.TestCase):
    """The sequence the app's terminal parses (TerminalEmulator.APP_OSC, PlotCommand.parse)."""

    def setUp(self):
        self.backend = _load_without_matplotlib()

    def test_one_osc_with_every_path_percent_encoded(self):
        text = self.backend.message(["/c/plots/1/figure-1.png", "/c/my plots;x/figure-2.png"])
        self.assertEqual(text, "\033]7777;plot;/c/plots/1/figure-1.png;/c/my%20plots%3Bx/figure-2.png\007")

    def test_notify_writes_to_the_terminal_and_prints_nothing(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        tty = os.path.join(tmp, "tty")
        os.environ["SKYPYTHON_PLOT_TTY"] = tty
        self.addCleanup(os.environ.pop, "SKYPYTHON_PLOT_TTY")
        self.backend.notify(["/x/figure-1.png"])
        with open(tty, encoding="utf-8") as f:
            self.assertEqual(f.read(), "\033]7777;plot;/x/figure-1.png\007")

    def test_without_a_terminal_the_paths_are_printed(self):
        os.environ["SKYPYTHON_PLOT_TTY"] = os.path.join(tempfile.gettempdir(), "no-such-dir-skyplot", "tty")
        self.addCleanup(os.environ.pop, "SKYPYTHON_PLOT_TTY")
        import io
        from contextlib import redirect_stdout

        out = io.StringIO()
        with redirect_stdout(out):
            self.backend.notify(["/x/figure-1.png"])
        self.assertEqual(out.getvalue(), "Figure saved: /x/figure-1.png\n")

    def test_figure_file_names(self):
        named = types.SimpleNamespace(num="My plot/1")
        self.assertEqual(self.backend._file_name(types.SimpleNamespace(num=3)), "figure-3.png")
        self.assertEqual(self.backend._file_name(named), "figure-My_plot_1.png")


@unittest.skipUnless(HAS_MPL, "matplotlib is not installed")
class BackendTest(unittest.TestCase):
    """The real backend under matplotlib, the way the app starts a script."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.plots = os.path.join(self.tmp, "plots")
        self.tty = os.path.join(self.tmp, "tty")

    def run_script(self, source, extra_env=None):
        env = dict(os.environ)
        env.pop("MPLBACKEND", None)
        env.update(
            MPLBACKEND="module://skypython_plot",
            PYTHONPATH=BACKEND_DIR,
            SKYPYTHON_PLOT_DIR=self.plots,
            SKYPYTHON_PLOT_TTY=self.tty,
            MPLCONFIGDIR=os.path.join(self.tmp, "mplconfig"),
        )
        env.update(extra_env or {})
        script = os.path.join(self.tmp, "script.py")
        with open(script, "w", encoding="utf-8") as f:
            f.write(textwrap.dedent(source))
        proc = subprocess.run(
            [sys.executable, script], cwd=self.tmp, env=env, capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc

    def shown(self):
        """The path lists of every show(), in order."""
        if not os.path.exists(self.tty):
            return []
        with open(self.tty, encoding="utf-8") as f:
            return [[unquote(p) for p in m.split(";")] for m in SEQUENCE.findall(f.read())]

    def assert_png(self, path):
        with open(path, "rb") as f:
            self.assertEqual(f.read(8), b"\x89PNG\r\n\x1a\n")

    def test_show_saves_every_figure_and_tells_the_app_once(self):
        proc = self.run_script("""
            import matplotlib.pyplot as plt
            print("backend", plt.get_backend())
            plt.plot([1, 2, 3], [1, 4, 9])
            plt.figure()
            plt.bar(["a", "b"], [3, 5])
            plt.show()
            print("after show")
        """)
        self.assertIn("backend module://skypython_plot", proc.stdout)
        self.assertIn("after show", proc.stdout)  # it did not block
        self.assertNotIn("\033]", proc.stdout)  # the sequence went to the terminal, not stdout
        shown = self.shown()
        self.assertEqual(len(shown), 1)
        self.assertEqual([os.path.basename(p) for p in shown[0]], ["figure-1.png", "figure-2.png"])
        for path in shown[0]:
            self.assertTrue(path.startswith(self.plots), path)
            self.assert_png(path)
        self.assertEqual("", proc.stderr)  # no "non-interactive, cannot be shown" warning

    def test_blocking_show_closes_figures_and_the_next_show_uses_a_new_folder(self):
        proc = self.run_script("""
            import matplotlib.pyplot as plt
            plt.plot([1, 2])
            plt.show(block=True)
            print("open after block:", len(plt.get_fignums()))
            plt.plot([3, 1])
            plt.show(block=False)
            print("open after no-block:", len(plt.get_fignums()))
            plt.show()
        """)
        self.assertIn("open after block: 0", proc.stdout)
        self.assertIn("open after no-block: 1", proc.stdout)
        shown = self.shown()
        self.assertEqual(len(shown), 3)
        self.assertEqual(len({os.path.dirname(paths[0]) for paths in shown}), 3)
        for paths in shown:
            self.assertEqual(len(paths), 1)
            self.assert_png(paths[0])

    def test_show_without_figures_sends_nothing(self):
        self.run_script("""
            import matplotlib.pyplot as plt
            plt.show()
        """)
        self.assertEqual(self.shown(), [])

    def test_figure_show_and_savefig(self):
        self.run_script("""
            import matplotlib
            import matplotlib.pyplot as plt
            fig, ax = plt.subplots()
            ax.plot([0, 1], [1, 0])
            fig.savefig("saved.png")
            fig.show()
        """)
        self.assert_png(os.path.join(self.tmp, "saved.png"))
        shown = self.shown()
        self.assertEqual(len(shown), 1)
        self.assert_png(shown[0][0])

    def test_users_own_backend_wins(self):
        proc = self.run_script("""
            import matplotlib
            matplotlib.use("agg")
            import matplotlib.pyplot as plt
            plt.plot([1, 2])
            print(plt.get_backend())
        """)
        self.assertIn("agg", proc.stdout.lower())
        self.assertEqual(self.shown(), [])


if __name__ == "__main__":
    unittest.main()
