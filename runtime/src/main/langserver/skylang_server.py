"""SkyPython language server: completions (jedi) and diagnostics (compile + pyflakes).

Protocol: one JSON object per line on stdin / stdout (ASCII, so any byte-oriented reader works).

    request   {"id": 7, "method": "complete", "params": {...}}
    response  {"id": 7, "result": ...}  or  {"id": 7, "error": "message"}
    cancel    {"method": "cancel", "params": {"id": 7}}      (no response of its own)
    event     {"event": "ready", "version": "..."}          (once, after imports and warm-up)

A request that is cancelled, or superseded by a newer request of the same method before it
started, is answered with {"id": n, "cancelled": true}.

Positions: "line" is 1-based, "column" is 0-based and counted in UTF-16 code units (what the
editor uses), both in requests and in results.

Methods:
    complete   {source, line, column, path?}  -> [{name, complete, type}]
    signature  {source, line, column, path?}  -> {name, params: [str], index} | null
    diagnose   {source, path?}                -> [{line, column, endLine, endColumn, severity, message}]
    ping       {}                             -> "pong"

stdout is reserved for the protocol: sys.stdout is pointed at stderr so stray prints (from
jedi, or from modules jedi imports) can't corrupt it.
"""

import json
import os
import queue
import re
import sys
import threading
import time

PROTOCOL_VERSION = "1"
MAX_COMPLETIONS = 150
MAX_DIAGNOSTICS = 100
BUNDLE_DIR = os.path.dirname(os.path.abspath(__file__))

_WORD = re.compile(r"\w+")


# --- positions -----------------------------------------------------------------------------

def utf16_to_index(text, col16):
    """Python str index of UTF-16 column [col16] in [text] (clamped to the text)."""
    if col16 <= 0:
        return 0
    units = 0
    for i, ch in enumerate(text):
        if units >= col16:
            return i
        units += 2 if ord(ch) > 0xFFFF else 1
    return len(text)


def index_to_utf16(text, index):
    """UTF-16 column of Python str index [index] in [text]."""
    head = text[:max(0, index)]
    return len(head) + sum(1 for ch in head if ord(ch) > 0xFFFF)


def utf8_to_index(text, col8):
    """Python str index of UTF-8 byte offset [col8] (ast col_offset) in [text]."""
    return len(text.encode("utf-8")[:max(0, col8)].decode("utf-8", "ignore"))


def _lines(source):
    # splitlines() would also split on \x0c, \x1c... which Python's tokenizer does not.
    return re.split(r"\r\n|\r|\n", source)


def _line(lines, lineno):
    return lines[lineno - 1] if 1 <= lineno <= len(lines) else ""


# --- diagnostics ---------------------------------------------------------------------------

def _diag(lines, lineno, start, end_lineno, end, severity, message):
    """A diagnostic from 1-based lines and Python str indices; columns become UTF-16."""
    if lineno < 1:
        lineno, start = 1, 0
    if lineno > len(lines):
        lineno = len(lines)
        start = len(_line(lines, lineno))
    text = _line(lines, lineno)
    start = max(0, min(start, len(text)))
    if end_lineno is None or end_lineno < lineno or end_lineno > len(lines):
        end_lineno, end = lineno, None
    if end is not None:
        end = max(0, min(end, len(_line(lines, end_lineno))))
    if end is None or (end_lineno == lineno and end <= start):
        # At least one character, so the squiggle is visible; at the end of a line take the
        # last character instead.
        end_lineno = lineno
        if start < len(text):
            end = start + 1
        elif start > 0:
            start, end = start - 1, start
        else:
            end = 0
    end = max(0, min(end, len(_line(lines, end_lineno))))
    return {
        "line": lineno,
        "column": index_to_utf16(text, start),
        "endLine": end_lineno,
        "endColumn": index_to_utf16(_line(lines, end_lineno), end),
        "severity": severity,
        "message": message,
    }


def _syntax_error(e, lines):
    lineno = e.lineno or 1
    text = _line(lines, lineno)
    # SyntaxError.offset is 1-based and counts characters (3.10+).
    start = (e.offset or 1) - 1
    end_lineno = getattr(e, "end_lineno", None)
    end = getattr(e, "end_offset", None)
    end = end - 1 if end else None
    if end_lineno is not None and end_lineno != lineno:
        # Multi-line spans (an unclosed bracket) are noisy; mark the start only.
        end_lineno, end = lineno, None
    if start >= len(text) and text.strip():
        start = len(text)  # "unexpected EOF": _diag moves it onto the last character
    msg = e.msg or "invalid syntax"
    return _diag(lines, lineno, start, end_lineno, end, "error", "SyntaxError: " + msg)


def diagnose(source, path="<editor>"):
    import ast
    lines = _lines(source)
    try:
        tree = ast.parse(source, filename=path or "<editor>")
    except SyntaxError as e:  # IndentationError / TabError are subclasses
        return [_syntax_error(e, lines)]
    except (ValueError, MemoryError, RecursionError) as e:  # null bytes, absurd nesting
        return [_diag(lines, 1, 0, None, None, "error", str(e))]

    from pyflakes import checker, messages as m
    errors = (m.UndefinedName, m.UndefinedLocal, m.UndefinedExport, m.DuplicateArgument,
              m.ReturnOutsideFunction, m.YieldOutsideFunction, m.ContinueOutsideLoop,
              m.BreakOutsideLoop, m.DefaultExceptNotLast, m.TwoStarredExpressions,
              m.TooManyExpressionsInStarredAssignment)
    w = checker.Checker(tree, filename=path or "<editor>")
    w.messages.sort(key=lambda msg: (msg.lineno, msg.col))
    out = []
    for msg in w.messages[:MAX_DIAGNOSTICS]:
        text = _line(lines, msg.lineno)
        start = utf8_to_index(text, msg.col)
        name = msg.message_args[0] if msg.message_args else None
        end = None
        if isinstance(name, str) and name:
            # Prefer the reported name (an unused import's column is the `import` keyword).
            simple = name.split(" as ")[-1].split(".")[0]
            pos = text.find(simple, start)
            if pos >= 0 and re.fullmatch(r"\w+", simple):
                start, end = pos, pos + len(simple)
        if end is None:
            word = _WORD.match(text, start)
            end = word.end() if word else None
        severity = "error" if isinstance(msg, errors) else "warning"
        out.append(_diag(lines, msg.lineno, start, msg.lineno, end, severity,
                         msg.message % msg.message_args))
    return out


# --- completion ----------------------------------------------------------------------------

_projects = {}
_environment = None


def _jedi_script(source, path):
    global _environment
    import jedi
    if _environment is None:
        _environment = jedi.InterpreterEnvironment()
    folder = os.path.dirname(path) if path else os.getcwd()
    project = _projects.get(folder)
    if project is None:
        # The user's sys.path, without this bundle: `import jedi` must not be offered to
        # scripts, which can't import it.
        sys_path = [p for p in sys.path if p and os.path.abspath(p) != BUNDLE_DIR]
        # Python adds the user site (pip --user) only if it existed at start-up; packages
        # installed later must still complete.
        try:
            import site
            user_site = site.getusersitepackages()
            if user_site and user_site not in sys_path:
                sys_path.append(user_site)
        except Exception:
            pass
        project = jedi.Project(folder, sys_path=sys_path, smart_sys_path=True)
        _projects[folder] = project
    return jedi.Script(source, path=path or None, project=project, environment=_environment)


def _jedi_position(source, line, column):
    lines = _lines(source)
    line = max(1, min(line, len(lines)))
    return line, utf16_to_index(_line(lines, line), column)


def complete(source, line, column, path=None):
    line, col = _jedi_position(source, line, column)
    script = _jedi_script(source, path)
    out = []
    for c in script.complete(line, col):
        if len(out) >= MAX_COMPLETIONS:
            break
        name = c.name
        out.append({"name": name, "complete": c.complete, "type": c.type})
    return out


def signature(source, line, column, path=None):
    line, col = _jedi_position(source, line, column)
    sigs = _jedi_script(source, path).get_signatures(line, col)
    if not sigs:
        return None
    s = sigs[0]
    return {
        "name": s.name,
        "params": [p.to_string() for p in s.params],
        "index": s.index,
    }


# --- server loop ---------------------------------------------------------------------------

METHODS = {
    "complete": lambda p: complete(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "signature": lambda p: signature(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "diagnose": lambda p: diagnose(p["source"], p.get("path") or "<editor>"),
    "ping": lambda p: "pong",
}


class Server:
    def __init__(self, inp, out):
        self.inp = inp
        self.out = out
        self.inbox = queue.Queue()
        self.cancelled = set()
        self.write_lock = threading.Lock()

    def send(self, obj):
        data = (json.dumps(obj, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")
        with self.write_lock:
            self.out.write(data)
            self.out.flush()

    def read_loop(self):
        for raw in iter(self.inp.readline, b""):
            raw = raw.strip()
            if not raw:
                continue
            try:
                msg = json.loads(raw.decode("utf-8", "replace"))
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            if msg.get("method") == "cancel":
                target = (msg.get("params") or {}).get("id")
                if target is not None:
                    self.cancelled.add(target)
                continue
            self.inbox.put(msg)
        self.inbox.put(None)  # EOF: the app closed stdin

    def next_batch(self):
        """Blocks for one message, then drains the queue. None at EOF."""
        first = self.inbox.get()
        if first is None:
            return None
        batch = [first]
        while True:
            try:
                msg = self.inbox.get_nowait()
            except queue.Empty:
                return batch
            if msg is None:
                self.inbox.put(None)
                return batch
            batch.append(msg)

    def serve(self, start_reader=True):
        if start_reader:
            threading.Thread(target=self.read_loop, name="stdin", daemon=True).start()
        while True:
            batch = self.next_batch()
            if batch is None:
                return
            # Only the newest request of each method is worth answering: older ones were made
            # stale by more typing.
            newest = {}
            for msg in batch:
                newest[msg.get("method")] = msg.get("id")
            for msg in batch:
                rid = msg.get("id")
                method = msg.get("method")
                if rid in self.cancelled or newest.get(method) != rid:
                    self.cancelled.discard(rid)
                    if rid is not None:
                        self.send({"id": rid, "cancelled": True})
                    continue
                self.handle(msg)

    def handle(self, msg):
        rid = msg.get("id")
        fn = METHODS.get(msg.get("method"))
        if fn is None:
            self.send({"id": rid, "error": "unknown method %r" % msg.get("method")})
            return
        try:
            result = fn(msg.get("params") or {})
        except Exception as e:  # never die on one bad request
            self.send({"id": rid, "error": "%s: %s" % (type(e).__name__, e)})
            return
        if rid in self.cancelled:
            self.cancelled.discard(rid)
            self.send({"id": rid, "cancelled": True})
        else:
            self.send({"id": rid, "result": result})


def _warm_up():
    """Loads jedi and the builtins stubs now, so the first real completion is fast."""
    try:
        complete("import os\nos.pa", 2, 5)
        diagnose("import os\n")
    except Exception as e:
        print("warm-up failed: %r" % (e,), file=sys.stderr)


def main():
    # Keep the real stdout for the protocol; everything printed goes to stderr.
    proto_out = os.fdopen(os.dup(sys.stdout.fileno()), "wb")
    sys.stdout = sys.stderr
    try:
        sys.setrecursionlimit(max(sys.getrecursionlimit(), 3000))
    except Exception:
        pass
    server = Server(sys.stdin.buffer, proto_out)
    started = time.monotonic()
    import jedi  # noqa: F401  fail fast if the bundle is broken
    # "ready" only after the warm-up: until then the app keeps its plain word completions
    # instead of queueing requests behind a slow first jedi run (and timing them out).
    if "--no-warmup" not in sys.argv:
        _warm_up()
    server.send({"event": "ready", "version": PROTOCOL_VERSION})
    print("ready in %.0f ms" % ((time.monotonic() - started) * 1000), file=sys.stderr)
    server.serve()


if __name__ == "__main__":
    main()
