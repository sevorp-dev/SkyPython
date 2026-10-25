"""SkyPython debugger driver: runs one script under bdb and talks to the IDE over a side channel.

    python -u skydbg.py --channel unix:/path/to/socket script.py [args...]

The script runs in this process as __main__, with its own stdin / stdout / stderr untouched:
the protocol uses a separate stream socket on which this driver listens and the IDE connects
(`unix:PATH`; `tcp:HOST:PORT` exists for desktop tests only). Nothing here prints to stdout.

Protocol: one JSON object per line (UTF-8), in the style of the language server.

    request   {"id": 7, "method": "variables", "params": {...}}
    response  {"id": 7, "result": ...}  or  {"id": 7, "error": "message"}
    event     {"event": "stopped", ...}

Events:
    ready      {pid, python}                                once, right after the IDE connects
    stopped    {reason, frames: [frame], exception?}        reason: breakpoint | step | pause |
                                                            entry | exception (uncaught; the
                                                            program ends when resumed)
    continued  {}                                           the program runs again
    exited     {code}                                       last message before the process ends

    frame      {id, name, file, line, hasLocals, user}      innermost first; id = index, valid
                                                            until the program runs again; user:
                                                            under userRoots

Methods (any time):
    setBreakpoints  {file, breakpoints: [{line, condition?}]}
                    -> [{line, actualLine, verified, message?}]   replaces the file's breakpoints;
                    a line without code moves to the next line that has some (actualLine)
    pause           {}  -> true      stop as soon as the program executes Python code again
    stop            {}  -> true      ends the process at once (exit code 137)
Before the program starts:
    start           {stopOnEntry?, userRoots?}  -> true    run the script (send breakpoints
                    first). With userRoots (directories), stepping and pausing only stop in
                    code under them ("just my code"); breakpoints work everywhere
While stopped:
    continue | next | stepIn | stepOut  {}  -> true
    variables       {frameId, scope: "locals" | "globals"}  or  {ref}
                    -> {variables: [variable], more: n}       at most MAX_CHILDREN per call
    evaluate        {frameId, expression}  -> variable without name, or {error: "Type: message"}

    variable   {name, value, type, ref, size?}    value is a truncated repr; ref > 0 can be
                                                  expanded with `variables {ref}`

Only the main thread is debugged. Pause uses SIGUSR1 (not SIGINT, which stays the program's
Ctrl+C) where the platform has it.
"""

import atexit
import bdb
import builtins
import io
import json
import os
import queue
import reprlib
import signal
import socket
import sys
import threading
import traceback

PROTOCOL_VERSION = "1"
MAX_CHILDREN = 200
MAX_VALUE = 300
ACCEPT_TIMEOUT = 60.0
EXIT_STOPPED = 137

_THIS_FILE = os.path.normcase(os.path.realpath(__file__))
_BDB_FILE = os.path.normcase(os.path.realpath(bdb.__file__))
_PAUSE_SIGNAL = getattr(signal, "SIGUSR1", signal.SIGINT)


_internal_cache = {}


def _internal(filename):
    """True for frames of the debugger itself, which are never traced or shown."""
    known = _internal_cache.get(filename)
    if known is None:
        try:
            f = os.path.normcase(os.path.realpath(filename))
        except (TypeError, ValueError):
            f = ""
        known = _internal_cache[filename] = f == _THIS_FILE or f == _BDB_FILE
    return known


class _Repr(reprlib.Repr):
    def __init__(self):
        super().__init__()
        self.maxlevel = 3
        self.maxlist = self.maxtuple = self.maxset = self.maxfrozenset = self.maxdeque = 30
        self.maxdict = 20
        self.maxstring = MAX_VALUE
        self.maxlong = 100
        self.maxother = MAX_VALUE

    def repr_instance(self, obj, level):
        try:
            s = builtins.repr(obj)
        except Exception as e:  # noqa: BLE001 - a broken __repr__ must not break the debugger
            return "<%s object; repr failed: %s>" % (type(obj).__name__, _describe(e))
        if len(s) > self.maxother:
            s = s[: self.maxother - 1] + "…"
        return s


_repr = _Repr()


def safe_repr(value):
    try:
        s = _repr.repr(value)
    except Exception as e:  # noqa: BLE001
        s = "<repr failed: %s>" % _describe(e)
    if len(s) > MAX_VALUE:
        s = s[: MAX_VALUE - 1] + "…"
    return s


def _describe(e):
    text = str(e)
    return "%s: %s" % (type(e).__name__, text) if text else type(e).__name__


def executable_lines(filename, _cache={}):
    """Line numbers that have code in [filename], or None if it can't be read or compiled."""
    key = (filename, _mtime(filename))
    if key in _cache:
        return _cache[key]
    try:
        with io.open_code(filename) as f:
            source = f.read()
        code = compile(source, filename, "exec", dont_inherit=True)
    except (OSError, SyntaxError, ValueError):
        _cache[key] = None
        return None
    lines = set()
    todo = [code]
    while todo:
        c = todo.pop()
        if hasattr(c, "co_lines"):
            lines.update(line for _, _, line in c.co_lines() if line is not None and line > 0)
        else:  # pragma: no cover - Python < 3.10
            import dis
            lines.update(line for _, line in dis.findlinestarts(c))
        todo.extend(k for k in c.co_consts if hasattr(k, "co_code"))
    _cache[key] = lines
    return lines


def _mtime(path):
    try:
        return os.stat(path).st_mtime_ns
    except OSError:
        return None


class Channel:
    """The IDE connection: line-delimited JSON over one stream socket."""

    def __init__(self, sock):
        self.sock = sock
        self.reader = sock.makefile("r", encoding="utf-8", newline="\n")
        self.lock = threading.Lock()
        self.closed = False

    @staticmethod
    def listen(spec, timeout=ACCEPT_TIMEOUT):
        kind, _, address = spec.partition(":")
        if kind == "unix":
            try:
                os.unlink(address)
            except OSError:
                pass
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            old_umask = os.umask(0o077)  # only this app may connect
            try:
                server.bind(address)
            finally:
                os.umask(old_umask)
        elif kind == "tcp":
            host, _, port = address.rpartition(":")
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind((host, int(port)))
        else:
            raise ValueError("unknown channel %r" % spec)
        try:
            server.listen(1)
            server.settimeout(timeout)
            conn, _ = server.accept()
        finally:
            server.close()
            if kind == "unix":
                try:
                    os.unlink(address)
                except OSError:
                    pass
        conn.settimeout(None)
        return Channel(conn)

    def send(self, message):
        data = (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")
        with self.lock:
            if self.closed:
                return
            try:
                self.sock.sendall(data)
            except OSError:
                self.closed = True

    def read(self):
        """The next message, or None when the IDE is gone."""
        while True:
            try:
                line = self.reader.readline()
            except (OSError, ValueError):
                return None
            if not line:
                return None
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if isinstance(message, dict):
                return message

    def close(self):
        with self.lock:
            self.closed = True
        try:
            self.sock.close()
        except OSError:
            pass


class Debugger(bdb.Bdb):
    def __init__(self, channel):
        super().__init__()
        self.channel = channel
        self.commands = queue.Queue()  # messages for the main thread while it is stopped
        self.state_lock = threading.Lock()
        self.stopped = False  # main thread inside interaction()
        self.running = False  # user code started and not finished
        self.pending = []  # setBreakpoints received while running
        self.pause_requested = False
        self.mode = "continue"
        self.entry_pending = False
        self.stop_on_entry = False
        self.frames = []
        self.refs = {}
        self.exit_code = None
        self.canonic_cache = {}
        self.detached = False
        self.user_roots = []
        self.user_cache = {}

    # --- paths -----------------------------------------------------------------------------

    def canonic(self, filename):
        if filename == "<" + filename[1:-1] + ">":
            return filename
        cached = self.canonic_cache.get(filename)
        if cached is None:
            cached = os.path.normcase(os.path.realpath(filename))
            self.canonic_cache[filename] = cached
        return cached

    def is_user(self, filename):
        if not self.user_roots:
            return True
        known = self.user_cache.get(filename)
        if known is None:
            path = self.canonic(filename)
            known = self.user_cache[filename] = any(path.startswith(root) for root in self.user_roots)
        return known

    # --- tracing -----------------------------------------------------------------------------

    def stop_here(self, frame):
        if not self.is_user(frame.f_code.co_filename):
            return False
        return super().stop_here(frame)

    def dispatch_call(self, frame, arg):
        if self.botframe is not None and _internal(frame.f_code.co_filename):
            return None
        return super().dispatch_call(frame, arg)

    def user_line(self, frame):
        if _internal(frame.f_code.co_filename):
            return
        if self.entry_pending:
            self.entry_pending = False
            if not self.stop_on_entry and not self._breakpoint_hit(frame):
                self.set_continue()
                return
            reason = "entry" if self.stop_on_entry else "breakpoint"
        else:
            reason = {"continue": "breakpoint", "pause": "pause"}.get(self.mode, "step")
        self.interaction(frame, None, reason)

    def user_return(self, frame, value):
        # Leaving a function while stepping: stop at the caller's next line, not at the return.
        caller = frame.f_back
        if caller is None or caller is self.botframe:
            self.set_continue()
        elif self.mode == "stepIn" or not self.is_user(caller.f_code.co_filename):
            # Library code isn't traced, so it can't be the frame to stop in: the next user line.
            self.set_step()
        else:
            self.set_next(caller)

    def user_call(self, frame, args):
        pass

    def user_exception(self, frame, exc_info):
        pass

    def _breakpoint_hit(self, frame):
        filename = self.canonic(frame.f_code.co_filename)
        if frame.f_lineno not in self.breaks.get(filename, ()):
            return False
        bp, flag = bdb.effective(filename, frame.f_lineno, frame)
        return bp is not None

    def _retrace(self, frame):
        """Traces every user frame on the stack again (bdb drops tracing when it can)."""
        f = frame
        while f is not None and f is not self.botframe:
            if not _internal(f.f_code.co_filename):
                f.f_trace = self.trace_dispatch
                f.f_trace_lines = True
            f = f.f_back
        sys.settrace(self.trace_dispatch)

    def on_signal(self, signum, frame):
        with self.state_lock:
            pending, self.pending = self.pending, []
            pause, self.pause_requested = self.pause_requested, False
        for message in pending:
            self.handle(message)
        if self.stopped or not self.running or frame is None:
            return
        if pause or pending:
            self._retrace(frame)
        if pause:
            self.mode = "pause"
            self.set_step()

    def _wake_main(self):
        if hasattr(signal, "pthread_kill"):
            signal.pthread_kill(threading.main_thread().ident, _PAUSE_SIGNAL)
        else:  # Windows (desktop tests)
            import _thread
            _thread.interrupt_main(_PAUSE_SIGNAL)

    # --- stopped -----------------------------------------------------------------------------

    def interaction(self, frame, tb, reason, exception=None):
        if self.detached:
            self.set_continue()
            return
        self.frames = self._user_frames(frame, tb)
        self.refs = {}
        with self.state_lock:
            self.stopped = True
            pending, self.pending = self.pending, []
            self.pause_requested = False
        for message in pending:
            self.handle(message)
        event = {"event": "stopped", "reason": reason, "frames": [self._frame_info(i, f) for i, (f, _) in enumerate(self.frames)]}
        if exception is not None:
            event["exception"] = exception
        self.channel.send(event)
        resume = None
        while resume is None:
            message = self.commands.get()
            if message is None:  # the IDE is gone: let the program finish on its own
                self.detach()
                resume = "continue"
                break
            resume = self.handle(message)
        with self.state_lock:
            self.stopped = False
            leftovers = []
            while True:
                try:
                    leftovers.append(self.commands.get_nowait())
                except queue.Empty:
                    break
        for message in leftovers:
            if message is not None:
                self.reply_error(message, "not stopped")
        self.frames = []
        self.refs = {}
        self.mode = resume
        if frame is None:  # post-mortem: nothing left to run
            pass
        elif resume == "continue":
            self.set_continue()
        elif resume == "next":
            self.set_next(frame)
        elif resume == "stepIn":
            self.set_step()
        elif resume == "stepOut":
            if frame.f_back is None or frame.f_back is self.botframe:
                self.set_continue()
            else:
                self.set_return(frame)
        self.channel.send({"event": "continued"})

    def detach(self):
        self.detached = True
        self.clear_all_breaks()

    def _user_frames(self, frame, tb):
        stack, _ = self.get_stack(frame, tb)
        frames = []
        seen_bottom = self.botframe is None
        for f, lineno in stack:
            if f is self.botframe:
                seen_bottom = True
                continue
            if not seen_bottom or _internal(f.f_code.co_filename):
                continue
            frames.append((f, lineno))
        frames.reverse()
        return frames

    def _frame_info(self, index, frame):
        f = frame
        lineno = self.frames[index][1]
        return {
            "id": index,
            "name": f.f_code.co_name,
            "file": self.canonic(f.f_code.co_filename),
            "line": lineno,
            "hasLocals": f.f_locals is not f.f_globals,
            "user": self.is_user(f.f_code.co_filename),
        }

    # --- requests ----------------------------------------------------------------------------

    def reply(self, message, result):
        if "id" in message:
            self.channel.send({"id": message["id"], "result": result})

    def reply_error(self, message, error):
        if "id" in message:
            self.channel.send({"id": message["id"], "error": error})

    def handle(self, message):
        """Handles one request on the main thread; returns a resume mode or None."""
        method = message.get("method")
        params = message.get("params") or {}
        try:
            if method == "setBreakpoints":
                self.reply(message, self.set_breakpoints(params))
            elif method in ("continue", "next", "stepIn", "stepOut"):
                if not self.stopped:
                    self.reply_error(message, "not stopped")
                    return None
                self.reply(message, True)
                return method
            elif method == "variables":
                self.reply(message, self.variables(params))
            elif method == "evaluate":
                self.reply(message, self.evaluate(params))
            elif method == "start":
                self.stop_on_entry = bool(params.get("stopOnEntry"))
                roots = [self.canonic(r) for r in params.get("userRoots") or []]
                self.user_roots = [r if r.endswith(os.sep) else r + os.sep for r in roots]
                self.user_cache = {}
                with self.state_lock:
                    self.running = True  # from now on, stepping requests are refused until a stop
                self.reply(message, True)
                return "start"
            else:
                self.reply_error(message, "unknown method %r" % method)
        except Exception as e:  # noqa: BLE001 - every request gets an answer
            self.reply_error(message, _describe(e))
        return None

    def set_breakpoints(self, params):
        filename = self.canonic(params["file"])
        self.clear_all_file_breaks(filename)
        lines = executable_lines(filename)
        results = []
        for bp in params.get("breakpoints") or []:
            line = int(bp["line"])
            condition = bp.get("condition") or None
            result = {"line": line, "actualLine": line, "verified": False}
            if lines is None:
                result["message"] = "file not found or not valid Python"
            else:
                actual = min((n for n in lines if n >= line), default=None)
                if actual is None:
                    result["message"] = "no code at or after this line"
                else:
                    error = self.set_break(filename, actual, cond=condition)
                    if error:
                        result["message"] = error
                    else:
                        result["actualLine"] = actual
                        result["verified"] = True
            results.append(result)
        return results

    def _frame(self, params):
        index = int(params.get("frameId", 0))
        if not 0 <= index < len(self.frames):
            raise ValueError("no frame %d" % index)
        return self.frames[index][0]

    def variables(self, params):
        if "ref" in params:
            target = self.refs.get(int(params["ref"]))
            if target is None:
                raise ValueError("unknown or expired reference")
            return self._children(target)
        frame = self._frame(params)
        scope = params.get("scope", "locals")
        if scope == "globals":
            items = [(k, v) for k, v in frame.f_globals.items() if not (k.startswith("__") and k.endswith("__"))]
        else:
            items = list(frame.f_locals.items())
        return self._page([(str(k), v) for k, v in items])

    def _page(self, named):
        shown = named[:MAX_CHILDREN]
        return {"variables": [self.describe(name, value) for name, value in shown], "more": len(named) - len(shown)}

    def _children(self, value):
        named = []
        if isinstance(value, dict):
            for i, (k, v) in enumerate(value.items()):
                if i > MAX_CHILDREN:
                    break
                named.append((safe_repr(k), v))
            more = max(0, len(value) - MAX_CHILDREN)
            result = self._page(named)
            result["more"] = more
            return result
        if isinstance(value, (list, tuple)) or _is_sequence(value):
            count = len(value)
            named = [("[%d]" % i, value[i]) for i in range(min(count, MAX_CHILDREN))]
            result = self._page(named)
            result["more"] = max(0, count - MAX_CHILDREN)
            return result
        if isinstance(value, (set, frozenset)):
            items = []
            for i, v in enumerate(value):
                if i >= MAX_CHILDREN:
                    break
                items.append(v)
            try:
                items.sort()
            except Exception:  # noqa: BLE001 - mixed types: keep set order
                pass
            result = self._page([("{%d}" % i, v) for i, v in enumerate(items)])
            result["more"] = max(0, len(value) - MAX_CHILDREN)
            return result
        attrs = _attributes(value)
        return self._page(attrs)

    def describe(self, name, value):
        info = {"name": name, "value": safe_repr(value), "type": type(value).__name__, "ref": 0}
        size = _size(value)
        if size is not None:
            info["size"] = size
        if _expandable(value, size):
            ref = len(self.refs) + 1
            self.refs[ref] = value
            info["ref"] = ref
        return info

    def evaluate(self, params):
        frame = self._frame(params)
        expression = str(params.get("expression", "")).strip()
        if not expression:
            return {"error": "empty expression"}
        try:
            try:
                code = compile(expression, "<watch>", "eval")
            except SyntaxError:
                code = compile(expression, "<watch>", "exec")
                exec(code, frame.f_globals, frame.f_locals)
                return {"value": "", "type": "", "ref": 0}
            value = eval(code, frame.f_globals, frame.f_locals)
        except BaseException as e:  # noqa: BLE001 - errors are results, never raised
            return {"error": _describe(e)}
        result = self.describe("", value)
        del result["name"]
        return result

    # --- running -----------------------------------------------------------------------------

    def configure(self):
        """Handles requests until `start`; False if the IDE went away first."""
        while True:
            message = self.commands.get()
            if message is None:
                return False
            if self.handle(message) == "start":
                return True

    def reader_loop(self):
        while True:
            message = self.channel.read()
            if message is None:
                with self.state_lock:
                    self.detached = True
                self.commands.put(None)
                return
            method = message.get("method")
            if method == "stop":
                self.reply(message, True)
                _flush_std()
                self.channel.close()
                os._exit(EXIT_STOPPED)
            if method == "pause":
                with self.state_lock:
                    wake = self.running and not self.stopped
                    if wake:
                        self.pause_requested = True
                self.reply(message, True)
                if wake:
                    self._wake_main()
                continue
            with self.state_lock:
                if self.stopped or not self.running:
                    self.commands.put(message)
                    continue
                if method != "setBreakpoints":
                    self.reply_error(message, "not stopped")
                    continue
                self.pending.append(message)
            self._wake_main()

    def run_script(self, path, args):
        import __main__

        main_dict = __main__.__dict__
        main_dict.clear()
        main_dict.update({"__name__": "__main__", "__file__": path, "__builtins__": builtins, "__cached__": None, "__spec__": None, "__loader__": None, "__package__": None})
        sys.argv = [path] + list(args)
        sys.path[0] = os.path.dirname(path)
        with io.open_code(path) as f:
            source = f.read()
        code = compile(source, path, "exec", dont_inherit=True)
        self.entry_pending = True
        self.mode = "continue"
        self.running = True
        try:
            self.run(code, main_dict)
        finally:
            self.running = False


def _is_sequence(value):
    try:
        import collections
        return isinstance(value, (collections.deque, range))
    except Exception:  # noqa: BLE001
        return False


def _size(value):
    if isinstance(value, (str, bytes, bytearray)):
        return len(value)
    if isinstance(value, (list, tuple, dict, set, frozenset)) or _is_sequence(value):
        try:
            return len(value)
        except Exception:  # noqa: BLE001
            return None
    return None


_SCALARS = (type(None), bool, int, float, complex, str, bytes, bytearray, range)


def _expandable(value, size):
    if isinstance(value, _SCALARS) and not isinstance(value, range):
        return False
    if isinstance(value, (list, tuple, dict, set, frozenset)) or _is_sequence(value):
        return bool(size)
    return bool(_attributes(value, limit=1))


def _attributes(value, limit=MAX_CHILDREN + 1):
    """(name, value) pairs of an object's own attributes: __dict__ and __slots__."""
    named = []
    try:
        d = object.__getattribute__(value, "__dict__")
    except Exception:  # noqa: BLE001
        d = None
    if isinstance(d, dict) or type(d).__name__ == "mappingproxy":
        for k, v in list(d.items()):
            if isinstance(k, str) and k.startswith("__") and k.endswith("__"):
                continue
            named.append((str(k), v))
            if len(named) >= limit:
                return named
    for cls in type(value).__mro__:
        slots = cls.__dict__.get("__slots__", ())
        if isinstance(slots, str):
            slots = (slots,)
        for name in slots:
            if name in ("__dict__", "__weakref__"):
                continue
            try:
                named.append((name, getattr(value, name)))
            except AttributeError:
                continue
            if len(named) >= limit:
                return named
    return named


def _flush_std():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()
        except Exception:  # noqa: BLE001
            pass


def _exit_code(e):
    code = e.code
    if code is None:
        return 0
    if isinstance(code, int):
        return code
    try:
        print(code, file=sys.stderr)
    except Exception:  # noqa: BLE001
        pass
    return 1


def _print_exception(e):
    """The traceback the program would have printed, without the debugger's own frames."""
    tb = e.__traceback__
    while tb is not None and _internal(tb.tb_frame.f_code.co_filename):
        tb = tb.tb_next
    traceback.print_exception(type(e), e, tb, file=sys.stderr)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) < 3 or argv[0] != "--channel":
        print("usage: skydbg.py --channel unix:PATH script.py [args...]", file=sys.stderr)
        return 2
    spec, script, args = argv[1], argv[2], argv[3:]
    path = os.path.realpath(script)
    # The driver's own directory must not shadow the program's modules.
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path[:] = [p for p in sys.path if os.path.abspath(p or ".") != here]
    sys.path.insert(0, os.path.dirname(path))
    sys.modules.pop("skydbg", None)  # still running, but not importable by the program
    try:
        channel = Channel.listen(spec)
    except (OSError, ValueError) as e:
        print("Debugger: could not connect to SkyPython (%s); running without it." % _describe(e), file=sys.stderr)
        channel = None

    debugger = Debugger(channel) if channel else None
    if debugger is not None:
        signal.signal(_PAUSE_SIGNAL, debugger.on_signal)
        atexit.register(_report_exit, debugger)  # registered first: runs after the program's own
        threading.Thread(target=debugger.reader_loop, name="skydbg-reader", daemon=True).start()
        channel.send({"event": "ready", "pid": os.getpid(), "python": sys.version.split()[0], "protocol": PROTOCOL_VERSION})
        if not debugger.configure():
            debugger.detach()

    code = 0
    try:
        if debugger is None:
            _run_plain(path, args)
        else:
            debugger.run_script(path, args)
    except SystemExit as e:
        code = _exit_code(e)
    except BaseException as e:  # noqa: BLE001 - the program's uncaught exception
        if debugger is not None and not isinstance(e, KeyboardInterrupt) and not debugger.detached:
            _post_mortem(debugger, e)
        _print_exception(e)
        code = 1
    if debugger is not None:
        debugger.exit_code = code
    _flush_std()
    return code


def _post_mortem(debugger, e):
    tb = e.__traceback__
    last = tb
    while last is not None and last.tb_next is not None:
        last = last.tb_next
    if last is None or _internal(last.tb_frame.f_code.co_filename):
        return
    debugger.reset()
    debugger.botframe = _bottom_frame(tb)
    debugger.running = True
    try:
        debugger.interaction(None, tb, "exception", exception=_describe(e))
    finally:
        debugger.running = False
        sys.settrace(None)


def _bottom_frame(tb):
    """The frame that ran the program's code (bdb.run), so only user frames are shown."""
    while tb is not None:
        if not _internal(tb.tb_frame.f_code.co_filename):
            return tb.tb_frame.f_back
        tb = tb.tb_next
    return None


def _run_plain(path, args):
    import __main__

    main_dict = __main__.__dict__
    main_dict.clear()
    main_dict.update({"__name__": "__main__", "__file__": path, "__builtins__": builtins})
    sys.argv = [path] + list(args)
    with io.open_code(path) as f:
        source = f.read()
    exec(compile(source, path, "exec", dont_inherit=True), main_dict)


def _report_exit(debugger):
    _flush_std()
    code = debugger.exit_code if debugger.exit_code is not None else 0
    debugger.channel.send({"event": "exited", "code": code})
    debugger.channel.close()


if __name__ == "__main__":
    # Imported under its own name, so the program can take over __main__ (as pdb does).
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import skydbg

    sys.exit(skydbg.main())
