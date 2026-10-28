"""SkyPython's matplotlib backend: plt.show() opens the figures in the app.

The app selects it with MPLBACKEND=module://skypython_plot (unless the user set MPLBACKEND
themselves) and puts this folder on PYTHONPATH. It draws with Agg, so it needs nothing but
matplotlib itself.

show() saves every open figure as a PNG in a folder of its own for this run
($SKYPYTHON_PLOT_DIR/<pid>-<time>/<n>/figure-<num>.png) and tells the app about them with a
private terminal escape sequence, which the app's terminal swallows (nothing is drawn):

    ESC ] 7777 ; plot ; <path> [; <path> ...] BEL

Each path is percent-encoded (urllib.parse.quote), so it never contains ';', BEL or ESC. The
sequence goes to the controlling terminal (/dev/tty), so it arrives even if sys.stdout is
redirected. Without a terminal (the debugger's console runs on pipes) the paths are printed as
plain text instead.

show() never blocks: there is no window to wait for. As on a computer after its windows are
closed, a blocking show() (the default outside interactive mode) closes the figures it showed,
so the next plot starts on a fresh figure; show(block=False) keeps them.
"""

import itertools
import os
import sys
import time
from urllib.parse import quote

import matplotlib
from matplotlib._pylab_helpers import Gcf
from matplotlib.backend_bases import FigureManagerBase
from matplotlib.backends.backend_agg import FigureCanvasAgg

__all__ = ["FigureCanvas", "FigureManager", "show"]

#: The private OSC number the app listens for (TerminalEmulator.APP_OSC).
OSC = 7777

#: Sharp on a phone screen without making huge files (a 6.4x4.8 in figure is 960x720).
DPI = 150

_run_dir = None
_shows = itertools.count(1)


def _plot_root():
    root = os.environ.get("SKYPYTHON_PLOT_DIR")
    if not root:
        import tempfile

        root = os.path.join(tempfile.gettempdir(), "plots")
    return root


def _next_dir():
    """A new folder for one show(): figures shown again later never overwrite earlier files."""
    global _run_dir
    if _run_dir is None:
        _run_dir = os.path.join(_plot_root(), "%d-%x" % (os.getpid(), int(time.time() * 1000)))
    path = os.path.join(_run_dir, str(next(_shows)))
    os.makedirs(path, exist_ok=True)
    return path


def _file_name(manager):
    num = manager.num
    return "figure-%s.png" % (num if isinstance(num, int) else "".join(c if c.isalnum() else "_" for c in str(num)))


def render(managers):
    """Saves the figures of [managers] as PNGs in a new folder and returns their paths."""
    if not managers:
        return []
    folder = _next_dir()
    paths = []
    for manager in managers:
        path = os.path.join(folder, _file_name(manager))
        manager.canvas.figure.savefig(path, format="png", dpi=DPI, bbox_inches="tight")
        paths.append(path)
    return paths


def message(paths):
    """The escape sequence that hands [paths] to the app."""
    return "\033]%d;plot;%s\007" % (OSC, ";".join(quote(p) for p in paths))


def _write_tty(text):
    """Writes [text] to the terminal; False if there is none."""
    target = os.environ.get("SKYPYTHON_PLOT_TTY") or "/dev/tty"
    try:
        with open(target, "a", encoding="utf-8") as tty:
            tty.write(text)
        return True
    except OSError:
        return False


def notify(paths):
    """Tells the app (or, without a terminal, the user) where the figures are."""
    if not paths:
        return
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()  # what the script printed before show() stays above the plot
        except Exception:
            pass
    if _write_tty(message(paths)):
        return
    for path in paths:
        print("Figure saved: %s" % path)


def _show(managers):
    paths = render(managers)
    notify(paths)
    return paths


class FigureManagerSky(FigureManagerBase):
    """fig.show() and plt.show() for one figure: render it and send it to the app."""

    def show(self):
        _show([self])


class FigureCanvasSky(FigureCanvasAgg):
    manager_class = FigureManagerSky


FigureCanvas = FigureCanvasSky
FigureManager = FigureManagerSky


def show(*args, block=None, **kwargs):
    """plt.show(): every open figure, at once (a pager in the app). Returns right away."""
    managers = Gcf.get_all_fig_managers()
    if not managers:
        return
    _show(managers)
    if block is None:
        # pyplot's rule: blocking unless in interactive mode (plt.ion()).
        block = not matplotlib.is_interactive()
    if block:
        # On a computer the windows were closed by now; start the next plot afresh.
        for manager in managers:
            Gcf.destroy(manager)
