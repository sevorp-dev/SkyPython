"""In-process tests of the debugger driver's pause signal handler and small helpers.

The handler runs on the main thread between two bytecodes, possibly while that thread holds one
of the driver's locks; it must never take a lock itself (a non-reentrant Lock held by the
interrupted code would deadlock the program).
"""

import importlib.util
import os
import signal
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
DRIVER = os.path.join(ROOT, "runtime", "src", "main", "assets", "debugger", "skydbg.py")

_spec = importlib.util.spec_from_file_location("skydbg_under_test", DRIVER)
skydbg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(skydbg)

SIGNAL = getattr(signal, "SIGUSR1", signal.SIGINT)


class FakeChannel:
    def __init__(self):
        self.lock = threading.Lock()
        self.sent = []
        self.closed = False

    def send(self, message):
        with self.lock:
            self.sent.append(message)


def _user_frame():
    """A frame of this (non-internal) file."""
    return sys._getframe()


class SignalHandlerTest(unittest.TestCase):
    def setUp(self):
        self.channel = FakeChannel()
        self.debugger = skydbg.Debugger(self.channel)
        self.debugger.reset()  # as bdb.run() does before the program starts
        self.debugger.running = True
        self.debugger.stopped = False
        self.addCleanup(sys.settrace, None)

    def _handler_returns(self, timeout=2.0):
        """Runs the handler on another thread; True if it returned within [timeout]."""
        done = threading.Event()
        frame = _user_frame()

        def run():
            try:
                self.debugger.on_signal(SIGNAL, frame)
            finally:
                sys.settrace(None)
                done.set()

        threading.Thread(target=run, daemon=True).start()
        return done.wait(timeout)

    def _pending_breakpoints(self):
        self.debugger.pending.append({"id": 1, "method": "setBreakpoints", "params": {"file": DRIVER, "breakpoints": []}})

    def test_handler_does_not_take_the_state_lock(self):
        self._pending_breakpoints()
        self.debugger.pause_requested = True
        with self.debugger.state_lock:
            returned = self._handler_returns()
        self.assertTrue(returned, "on_signal blocked on state_lock held by the interrupted code")

    def test_handler_does_not_send_on_the_channel(self):
        self._pending_breakpoints()
        with self.channel.lock:
            returned = self._handler_returns()
        self.assertTrue(returned, "on_signal blocked on the channel lock held by the interrupted code")

    def test_requests_are_served_from_the_trace_function(self):
        self._pending_breakpoints()
        self.debugger.pause_requested = True
        self.assertTrue(self._handler_returns())
        self.debugger._service_requests(_user_frame())
        self.assertEqual([m.get("id") for m in self.channel.sent], [1])
        self.assertEqual(self.debugger.mode, "pause")
        self.assertFalse(self.debugger.pause_requested)
        self.assertEqual(len(self.debugger.pending), 0)


class ChildrenTest(unittest.TestCase):
    def test_dict_children_are_capped_exactly(self):
        d = skydbg.Debugger(FakeChannel())
        collected = []
        original = d._page

        def page(named):
            collected.append(len(named))
            return original(named)

        d._page = page
        result = d._children({i: i for i in range(skydbg.MAX_CHILDREN + 5)})
        self.assertEqual(len(result["variables"]), skydbg.MAX_CHILDREN)
        self.assertEqual(result["more"], 5)
        self.assertEqual(collected, [skydbg.MAX_CHILDREN])


if __name__ == "__main__":
    unittest.main()
