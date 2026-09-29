"""Tests for the Python jail and for the `run()` entry point.

Plain CPython: nothing here needs Chaquopy or an Android device, so CI runs it
with `python3 -m unittest` (see .github/workflows/android.yml).
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "main", "python"))

import pocketrun  # noqa: E402
import pocketrun_sandbox  # noqa: E402


class SandboxTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.realpath(self.tmp.name)
        self.ws = os.path.join(self.root, "workspace")
        self.private = os.path.join(self.root, "shared_prefs")
        os.makedirs(self.ws)
        os.makedirs(self.private)
        with open(os.path.join(self.private, "license.xml"), "w") as handle:
            handle.write("<string>PRK1</string>")
        with open(os.path.join(self.ws, "main.py"), "w") as handle:
            handle.write("print('hi')\n")
        self.jail = pocketrun_sandbox.Jail(
            [self.ws] + pocketrun_sandbox.interpreter_roots(),
            [self.ws],
            blocked_hosts=("127.0.0.1",),
        )

    def tearDown(self):
        self.tmp.cleanup()

    def denied(self, fn):
        with self.assertRaises(pocketrun_sandbox.SandboxViolation):
            fn()

    def test_read_and_write_inside_the_workspace_are_allowed(self):
        with self.jail:
            with open(os.path.join(self.ws, "out.txt"), "w") as handle:
                handle.write("x")
            with open(os.path.join(self.ws, "out.txt")) as handle:
                self.assertEqual(handle.read(), "x")

    def test_reading_app_private_files_is_denied(self):
        with self.jail:
            self.denied(lambda: open(os.path.join(self.private, "license.xml")).close())

    def test_writing_outside_is_denied(self):
        with self.jail:
            self.denied(lambda: open(os.path.join(self.private, "x"), "w").close())

    def test_append_and_truncate_are_writes(self):
        with self.jail:
            self.denied(lambda: open(os.path.join(self.private, "license.xml"), "a").close())
            self.denied(lambda: open(os.path.join(self.private, "license.xml"), "r+").close())

    def test_directory_operations_outside_are_denied(self):
        with self.jail:
            self.denied(lambda: os.listdir(self.private))
            self.denied(lambda: os.remove(os.path.join(self.private, "license.xml")))
            self.denied(lambda: os.rename(os.path.join(self.ws, "main.py"), os.path.join(self.private, "m.py")))
            self.denied(lambda: os.chdir(self.private))

    def test_a_symlink_out_of_the_workspace_does_not_help(self):
        link = os.path.join(self.ws, "escape")
        try:
            os.symlink(self.private, link)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable here")
        with self.jail:
            self.denied(lambda: open(os.path.join(link, "license.xml")).close())
            self.denied(lambda: os.listdir(link))

    def test_a_symlink_written_inside_is_not_followed_out(self):
        target = os.path.join(self.private, "license.xml")
        link = os.path.join(self.ws, "shortcut")
        try:
            os.symlink(target, link)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable here")
        with self.jail:
            self.denied(lambda: open(link, "w").close())

    def test_process_creation_is_denied(self):
        import subprocess

        with self.jail:
            self.denied(lambda: subprocess.Popen(["echo", "hi"]))
            self.denied(lambda: os.system("echo hi"))

    def test_native_code_is_denied(self):
        with self.jail:
            self.denied(lambda: __import__("ctypes").CDLL("libc.so.6"))

    def test_the_standard_library_stays_readable(self):
        with self.jail:
            self.assertEqual(__import__("json").dumps({"a": 1}), '{"a": 1}')
            open(os.__file__).close()

    def test_blocked_hosts_are_refused(self):
        with self.jail:
            self.denied(lambda: self.jail.check_address("127.0.0.1"))
            self.jail.check_address("93.184.216.34")

    def test_a_violation_is_not_swallowed_by_except_exception(self):
        with self.jail:
            with self.assertRaises(pocketrun_sandbox.SandboxViolation):
                try:
                    open(os.path.join(self.private, "license.xml"))
                except Exception:  # noqa: BLE001 - the point of the test
                    self.fail("a violation must not be catchable as Exception")

    def test_the_jail_is_off_outside_the_with_block(self):
        with open(os.path.join(self.private, "license.xml")) as handle:
            self.assertIn("PRK1", handle.read())

    def test_nested_jails_restore_the_outer_one(self):
        tight = os.path.join(self.root, "tight")
        os.makedirs(tight)
        inner = pocketrun_sandbox.Jail([tight], [tight])
        with self.jail:
            with inner:
                self.denied(lambda: open(os.path.join(self.ws, "main.py")).close())
            with open(os.path.join(self.ws, "main.py")) as handle:
                self.assertEqual(handle.read(), "print('hi')\n")


class RunTest(unittest.TestCase):
    """`pocketrun.run` end to end: a script that escapes ends the run, not the app."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.realpath(self.tmp.name)
        self.ws = os.path.join(self.root, "workspace")
        os.makedirs(os.path.join(self.ws, "proj"))
        self.out = os.path.join(self.root, "out.log")
        self.err = os.path.join(self.root, "err.log")

    def tearDown(self):
        self.tmp.cleanup()

    def call(self, source, sandbox=True):
        script = os.path.join(self.ws, "proj", "s.py")
        with open(script, "w") as handle:
            handle.write(source)
        return pocketrun.run(
            script, "[]", None, self.out, self.err, os.path.join(self.ws, "proj"),
            self.ws if sandbox else None,
        )

    def tail(self, path):
        try:
            with open(path) as handle:
                return handle.read()
        except OSError:
            return ""

    def test_a_normal_script_runs_and_keeps_its_output(self):
        self.assertEqual(self.call("print('работает')"), 0)
        self.assertIn("работает", self.tail(self.out))

    def test_stdlib_imports_work_under_the_jail(self):
        self.assertEqual(self.call("import json, re, os\nprint(json.dumps(sorted(re.findall(r'\\d', 'a1b2'))))"), 0)
        self.assertIn('["1", "2"]', self.tail(self.out))

    def test_a_script_reading_outside_fails_with_a_traceback(self):
        self.assertEqual(self.call("open('/etc/passwd').read()"), 1)
        self.assertIn("SandboxViolation", self.tail(self.err))

    def test_a_script_can_still_write_the_workspace(self):
        self.assertEqual(self.call("open('data.txt', 'w').write('привет')\nprint('ok')"), 0)
        self.assertIn("ok", self.tail(self.out))
        with open(os.path.join(self.ws, "proj", "data.txt")) as handle:
            self.assertEqual(handle.read(), "привет")

    def test_exit_codes_pass_through(self):
        self.assertEqual(self.call("raise SystemExit(3)"), 3)

    def test_a_missing_script_reports_instead_of_raising(self):
        code = pocketrun.run(
            os.path.join(self.ws, "nope.py"), "[]", None, self.out, self.err, self.ws, self.ws,
        )
        self.assertEqual(code, 2)
        self.assertIn("no such file", self.tail(self.err))

    def test_stdin_is_readable_by_the_script(self):
        script = os.path.join(self.ws, "proj", "s.py")
        with open(script, "w") as handle:
            handle.write("import sys\nprint(sys.stdin.read().strip().upper())\n")
        code = pocketrun.run(script, "[]", "привет\n", self.out, self.err, os.path.dirname(script), self.ws)
        self.assertEqual(code, 0)
        self.assertIn("ПРИВЕТ", self.tail(self.out))


if __name__ == "__main__":
    unittest.main()
