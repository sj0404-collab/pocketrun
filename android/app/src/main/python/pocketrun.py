"""Entry points that the Android side calls through Chaquopy.

Kotlin owns the UI, the sandbox and process lifetime; everything that only makes
sense in Python lives here: argv handling, sys.path setup, stream redirection and
traceback formatting.
"""

import io
import json
import os
import runpy
import sys
import traceback


class PipeStream:
    """A minimal text stream that appends to a file the Android side tails.

    Using a file rather than a pipe means slow readers can never stall the
    interpreter, and output survives a crash of the UI process.
    """

    def __init__(self, path, mirror=None):
        self._file = open(path, "a", encoding="utf-8", buffering=1, errors="replace")
        self._mirror = mirror

    def write(self, text):
        if not isinstance(text, str):
            text = str(text)
        self._file.write(text)
        if self._mirror is not None:
            self._mirror.write(text)
        return len(text)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def flush(self):
        self._file.flush()

    def isatty(self):
        return False

    def writable(self):
        return True

    def readable(self):
        return False

    def seekable(self):
        return False

    def close(self):
        try:
            self._file.close()
        except Exception:
            pass


def run(script, args_json, stdin_text, out_path, err_path, cwd):
    """Runs ``script`` as __main__ and returns its exit code."""
    if not os.path.isfile(script):
        _write_line(err_path, "python: no such file: %s" % script)
        return 2

    args = json.loads(args_json) if args_json else []
    previous = (sys.stdout, sys.stderr, sys.argv)
    if cwd:
        try:
            os.chdir(cwd)
        except OSError as exc:
            _write_line(err_path, "python: cannot enter %s: %s" % (cwd, exc))
            return 2

    sys.argv = [script] + list(args)
    script_dir = os.path.dirname(os.path.abspath(script))
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)

    if stdin_text is not None:
        sys.stdin = io.StringIO(stdin_text)

    out = PipeStream(out_path, previous[0])
    err = PipeStream(err_path, previous[1])
    sys.stdout, sys.stderr = out, err
    code = 0
    try:
        runpy.run_path(script, run_name="__main__")
    except SystemExit as exc:
        code = _exit_code(exc.code)
    except KeyboardInterrupt:
        _write_line(err_path, "KeyboardInterrupt")
        code = 130
    except BaseException:
        traceback.print_exc(file=err)
        code = 1
    finally:
        sys.stdout, sys.stderr, sys.argv = previous
        out.close()
        err.close()
    return code


def check_script(script):
    """Cheap syntax check so the editor can flag errors before a run."""
    if not os.path.isfile(script):
        return "no such file: %s" % script
    with open(script, "r", encoding="utf-8", errors="replace") as handle:
        source = handle.read()
    try:
        compile(source, os.path.basename(script), "exec")
        return None
    except SyntaxError as exc:
        return "line %s: %s" % (exc.lineno, exc.msg)
    except ValueError as exc:
        return str(exc)


def list_packages():
    """Installed distributions, for the packages screen."""
    try:
        from importlib import metadata
    except ImportError:
        return []
    found = []
    for dist in metadata.distributions():
        try:
            name = dist.metadata["Name"]
            if not name:
                continue
            found.append({"name": name, "version": dist.version or "?"})
        except Exception:
            continue
    found.sort(key=lambda item: item["name"].lower())
    return found


def interpreter_info():
    return {
        "version": sys.version.split()[0],
        "implementation": sys.implementation.name,
        "executable": sys.executable,
        "path": sys.path[:],
    }


def _exit_code(value):
    if value is None:
        return 0
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, int):
        return value
    _write_line_to_stderr("SystemExit: %s" % (value,))
    return 1


def _write_line(path, text):
    _write_line_to_stderr(text, path)


def _write_line_to_stderr(text, path=None):
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.__stderr__.write(text + "\n")
