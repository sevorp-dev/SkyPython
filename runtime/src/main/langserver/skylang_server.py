"""SkyPython language server: completions and navigation (jedi, parso), diagnostics (pyflakes).

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
    signature  {source, line, column, path?}  -> {name, params: [str], short: [str], index} | null
    definition {source, line, column, path?}  -> [{name, type, path, kind, line, column, endColumn, inFile}]
               kind: "project" | "stdlib" | "site-packages"; inFile: defined in [source] itself
    references {source, line, column, path?}  -> [{line, column, endColumn}]   (this file only)
    outline    {source}                       -> [{name, kind, line, column, endLine, children}]
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
    params = [p.to_string() for p in s.params]
    return {
        "name": s.name,
        "params": params,
        "short": [short_param(p) for p in params],
        "index": s.index,
    }


def _top_level(text, targets):
    """Indices of chars in [targets] outside brackets and string literals."""
    depth, quote, i, out = 0, None, 0, []
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch in targets:
            out.append(i)
        i += 1
    return out


def short_param(text):
    """`sep: str | None=" "` -> `sep=" "`: a parameter without its annotation."""
    eq = _top_level(text, "=")
    default = text[eq[0] + 1:] if eq else None
    head = text[:eq[0]] if eq else text
    colon = _top_level(head, ":")
    name = (head[:colon[0]] if colon else head).strip()
    return name if default is None else name + "=" + default.strip()


# --- navigation ----------------------------------------------------------------------------

def _norm(p):
    return os.path.normcase(os.path.realpath(str(p)))


def _stdlib_dirs():
    import sysconfig
    dirs = set()
    for key in ("stdlib", "platstdlib"):
        try:
            d = sysconfig.get_paths().get(key)
        except Exception:
            d = None
        if d:
            dirs.add(_norm(d))
    dirs.add(_norm(os.path.dirname(os.__file__)))
    return dirs


_STDLIB_DIRS = None


def _under(path, folder):
    return path == folder or path.startswith(folder.rstrip(os.sep) + os.sep)


def classify(path):
    """Where a definition lives: "site-packages", "stdlib" or "project" (the user's own code)."""
    global _STDLIB_DIRS
    p = _norm(path)
    parts = re.split(r"[\\/]", p)
    if "site-packages" in parts or "dist-packages" in parts:
        return "site-packages"
    # jedi's bundled stubs: typeshed's stdlib, or stubs of third-party packages.
    jedi_dir = _norm(os.path.join(BUNDLE_DIR, "jedi"))
    if _under(p, jedi_dir):
        slashed = p.replace("\\", "/")
        return "stdlib" if "/typeshed/stdlib/" in slashed else "site-packages"
    if _STDLIB_DIRS is None:
        _STDLIB_DIRS = _stdlib_dirs()
    if any(_under(p, d) for d in _STDLIB_DIRS):
        return "stdlib"
    return "project"


def _read_line(path, lineno, cache):
    lines = cache.get(path)
    if lines is None:
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                lines = _lines(f.read())
        except OSError:
            lines = []
        cache[path] = lines
    return _line(lines, lineno)


def _in_script(name, path):
    module = name.module_path
    if module is None:
        return not name.in_builtin_module()
    return bool(path) and _norm(module) == _norm(path)


def _location(name, source_lines, path, cache):
    """A jedi Name as {line, column, endColumn} (UTF-16) plus where it is."""
    in_file = _in_script(name, path)
    lineno = name.line or 1
    col = name.column or 0
    if in_file:
        text = _line(source_lines, lineno)
    else:
        text = _read_line(str(name.module_path), lineno, cache)
    end = col + len(name.name) if text.startswith(name.name, col) else col
    return {
        "line": lineno,
        "column": index_to_utf16(text, col),
        "endColumn": index_to_utf16(text, end),
        "inFile": in_file,
    }


def _on_name(lines, line, col):
    """True if [col] of [line] touches an identifier (not a number, operator or space)."""
    text = _line(lines, line)
    start = col
    while start > 0 and (text[start - 1].isalnum() or text[start - 1] == "_"):
        start -= 1
    end = col
    while end < len(text) and (text[end].isalnum() or text[end] == "_"):
        end += 1
    return text[start:end].isidentifier()


def definition(source, line, column, path=None):
    """Where the name at the position is defined: [{name, type, path, kind, line, column, endColumn, inFile}]."""
    lines = _lines(source)
    line, col = _jedi_position(source, line, column)
    if not _on_name(lines, line, col):
        return []
    names = _jedi_script(source, path).goto(line, col, follow_imports=True)
    out, seen, cache = [], set(), {}
    for n in names:
        if n.module_path is None and n.in_builtin_module():
            continue  # compiled, no source to show
        loc = _location(n, lines, path, cache)
        target = str(n.module_path) if n.module_path is not None else path
        key = (target, loc["line"], loc["column"])
        if key in seen:
            continue
        seen.add(key)
        loc.update({
            "name": n.name,
            "type": n.type,
            "path": target,
            "kind": classify(target) if target else "project",
        })
        out.append(loc)
    return out


def references(source, line, column, path=None):
    """Usages of the name at the position in this file: [{line, column, endColumn}], sorted."""
    lines = _lines(source)
    line, col = _jedi_position(source, line, column)
    out = []
    for n in _jedi_script(source, path).get_references(line, col, scope="file"):
        if not _in_script(n, path) or n.line is None:
            continue
        loc = _location(n, lines, path, {})
        if loc["endColumn"] > loc["column"]:
            out.append({"line": loc["line"], "column": loc["column"], "endColumn": loc["endColumn"]})
    out.sort(key=lambda r: (r["line"], r["column"]))
    return out


def outline(source):
    """Classes and functions, nested: [{name, kind, line, column, endLine, children}].

    parso recovers from syntax errors, so the outline stays useful while code is half-typed.
    kind is "class", "method" (a function directly in a class) or "function".
    """
    import parso
    lines = _lines(source)
    tree = parso.parse(source, error_recovery=True)

    def end_line(node):
        last = node.get_last_leaf()
        # The suite ends with the newline token, i.e. at column 0 of the next line.
        end_row, end_col = last.end_pos
        if end_col == 0 and end_row > node.start_pos[0]:
            end_row -= 1
        return end_row

    def walk(node, in_class):
        out = []
        for child in node.children if hasattr(node, "children") else ():
            kind = None
            target = child
            if child.type == "async_stmt" or child.type == "decorated":
                target = child.children[-1]
                if target.type == "async_funcdef":
                    target = target.children[-1]
            if target.type == "async_funcdef":
                target = target.children[-1]
            if target.type == "classdef":
                kind = "class"
            elif target.type == "funcdef":
                kind = "method" if in_class else "function"
            if kind is None:
                if child.type in ("suite", "simple_stmt", "if_stmt", "try_stmt", "with_stmt",
                                  "for_stmt", "while_stmt", "async_stmt", "decorated", "error_node"):
                    out.extend(walk(child, in_class))
                continue
            name = target.name
            row, col = name.start_pos
            out.append({
                "name": name.value,
                "kind": kind,
                "line": row,
                "column": index_to_utf16(_line(lines, row), col),
                "endLine": end_line(target),
                "children": walk(target, kind == "class"),
            })
        return out

    return walk(tree, False)


# --- server loop ---------------------------------------------------------------------------

METHODS = {
    "complete": lambda p: complete(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "signature": lambda p: signature(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "diagnose": lambda p: diagnose(p["source"], p.get("path") or "<editor>"),
    "definition": lambda p: definition(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "references": lambda p: references(p["source"], int(p["line"]), int(p["column"]), p.get("path")),
    "outline": lambda p: outline(p["source"]),
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
