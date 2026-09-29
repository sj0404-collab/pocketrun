"""A filesystem jail for user scripts, built on CPython audit hooks (PEP 578).

The app runs user code in its own process, so `os` and `open` reach the whole
app sandbox: the activation key in ``shared_prefs``, every other project, the
signing material. Kotlin's ``Workspace.resolve`` cannot help here because the
Python side never goes through it. What can help is the interpreter itself:
since 3.8 every security-relevant operation raises an audit event, and a hook
added with :func:`sys.addaudithook` can veto it before it happens.

What this is not: a kernel-level jail. There is no second process and no
different uid, so only *Python-level* escapes are closed. In particular native
code reached through :mod:`ctypes` would be free to do anything, so every
``ctypes.*`` event is refused. That is deliberate: ctypes in a script is never
innocent and the standard library needs none of it.

The hook is installed once and stays installed (CPython has no
``removeaudithook``); it does nothing until a :class:`Jail` is active.
"""

import os
import sys

__all__ = ["SandboxViolation", "Jail", "intercept", "interpreter_roots"]


def interpreter_roots():
    """Directories holding the interpreter itself: stdlib, pip packages, us.

    Chaquopy unpacks all of them into the app's data directory, so a script must
    be able to read them while staying out of everything else the app stores
    there (the activation key in shared_prefs, other projects).
    """
    roots = []
    try:
        import sysconfig

        for value in sysconfig.get_paths().values():
            if value:
                roots.append(value)
    except Exception:  # sysconfig is present in every CPython, but never bet on it
        pass
    for module in (os, sys.modules.get("json"), sys.modules.get("pocketrun_sandbox")):
        path = getattr(module, "__file__", None)
        if path:
            roots.append(os.path.dirname(os.path.abspath(path)))
    if getattr(sys, "prefix", None):
        roots.append(sys.prefix)
    seen = []
    for root in roots:
        real = _abs_real(root)
        if real not in seen and os.path.isdir(real):
            seen.append(real)
    return seen


class SandboxViolation(BaseException):
    """A script tried to touch something outside the sandbox.

    Derives from :class:`BaseException` on purpose. A violation must not be
    swallowed by ``except Exception`` in user code — that would turn the jail
    into advice rather than a boundary.
    """


def _text(path):
    """The path as text, or None when it is a file descriptor."""
    if isinstance(path, str):
        return path
    if isinstance(path, bytes):
        return path.decode("utf-8", "replace")
    fspath = getattr(path, "__fspath__", None)
    if fspath is not None:
        return os.fspath(fspath)
    return None


def _under(roots, path):
    """True when [path] really is inside one of [roots] (symlinks resolved)."""
    real = os.path.realpath(path)
    for root in roots:
        if real == root or real.startswith(root + os.sep):
            return True
    return False


_WRITE_FLAGS = os.O_WRONLY | os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_TRUNC
_WRITE_MODES = frozenset("wax+")


class Jail:
    """Allows reads under `read_roots`, writes under `write_roots`.

    Use it as a context manager around the code that runs untrusted. Nested
    jails work: the inner one is restored on exit.
    """

    def __init__(self, read_roots, write_roots, blocked_hosts=()):
        self.read_roots = tuple(_abs_real(r) for r in read_roots)
        self.write_roots = tuple(_abs_real(r) for r in write_roots)
        self.blocked_hosts = tuple(blocked_hosts)
        self._previous = None

    def __enter__(self):
        global _ACTIVE
        _install()
        self._previous = _ACTIVE
        _ACTIVE = self
        return self

    def __exit__(self, *_exc):
        global _ACTIVE
        _ACTIVE = self._previous
        self._previous = None
        return False

    # -------------------------------------------------------------- checks

    def check_open(self, path, mode=None, flags=None):
        """`open(path, mode, flags)` — the mode or the flags say write or read."""
        if isinstance(mode, int):
            mode, flags = None, mode
        writes = bool(_WRITE_MODES & set(mode or "")) or (
            isinstance(flags, int) and bool(flags & _WRITE_FLAGS)
        )
        self.check_path(path, write=writes)

    def check_path(self, path, write=False):
        text = _text(path)
        if text is None:
            return  # a file descriptor: there is no name to check
        if write:
            if not _under(self.write_roots, text):
                raise SandboxViolation("запись за пределами рабочей папки запрещена: %s" % text)
            return
        if not _under(self.read_roots, text):
            raise SandboxViolation("чтение за пределами рабочей папки запрещено: %s" % text)

    def check_pair(self, src, dst, write_dst=True):
        self.check_path(src, write=False)
        if write_dst:
            self.check_path(dst, write=True)

    def check_address(self, host):
        if not host:
            return
        if host in self.blocked_hosts:
            raise SandboxViolation("подключение к %s запрещено" % host)

    def deny(self, what):
        raise SandboxViolation(what)


def _abs_real(path):
    return os.path.realpath(os.path.abspath(path))


# --------------------------------------------------------------------- hook

_ACTIVE = None
_HOOKED = False

_DENY = {
    "os.exec": "запуск процессов недоступен в песочнице",
    "os.posix_spawn": "запуск процессов недоступен в песочнице",
    "os.spawn": "запуск процессов недоступен в песочнице",
    "os.fork": "запуск процессов недоступен в песочнице",
    "os.forkpty": "запуск процессов недоступен в песочнице",
    "os.system": "запуск процессов недоступен в песочнице",
    "os.putenv": "изменение окружения недоступно в песочнице",
    "os.setuid": "смена пользователя недоступна в песочнице",
    "os.setgid": "смена группы недоступна в песочнице",
    "subprocess.Popen": "запуск процессов недоступен в песочнице",
    "pty.spawn": "запуск процессов недоступен в песочнице",
    "ctypes.dlopen": "нативный код недоступен в песочнице",
    "ctypes.dlsym": "нативный код недоступен в песочнице",
    "ctypes.call_function": "нативный код недоступен в песочнице",
    "ctypes.set_exception": "нативный код недоступен в песочнице",
}

_READ_EVENTS = ("os.listdir", "os.scandir", "os.chdir", "os.stat")
_WRITE_EVENTS = (
    "os.remove",
    "os.unlink",
    "os.rename",
    "os.replace",
    "os.mkdir",
    "os.rmdir",
    "os.chmod",
    "os.chown",
    "os.truncate",
    "os.link",
    "os.symlink",
)


def _audit(event, args):
    jail = _ACTIVE
    if jail is None:
        return
    denied = _DENY.get(event)
    if denied is not None:
        jail.deny(denied)
    if event == "open":
        jail.check_open(*args[:3])
    elif event == "os.rename":
        jail.check_pair(args[0], args[1], write_dst=True)
    elif event == "os.link":
        jail.check_path(args[0], write=False)
        jail.check_path(args[1], write=True)
    elif event == "os.symlink":
        jail.check_path(args[0], write=False)
        jail.check_path(args[1], write=True)
    elif event in _WRITE_EVENTS:
        jail.check_path(args[0], write=True)
    elif event in _READ_EVENTS:
        jail.check_path(args[0], write=False)
    elif event in ("socket.connect", "socket.bind"):
        address = args[1] if len(args) > 1 else None
        if isinstance(address, (str, bytes)):
            # A unix socket path, not an IP: it can reach any local service.
            jail.deny("unix-сокеты недоступны в песочнице")
        elif isinstance(address, tuple) and address:
            jail.check_address(address[0])


def _install():
    global _HOOKED
    if _HOOKED:
        return
    sys.addaudithook(_audit)
    _HOOKED = True


class _Intercept:
    """Context manager form of `Jail`, so callers can write `with intercept(...)`."""

    def __init__(self, jail):
        self._jail = jail

    def __enter__(self):
        self._jail.__enter__()
        return self._jail

    def __exit__(self, *exc):
        return self._jail.__exit__(*exc)


def intercept(read_roots, write_roots, blocked_hosts=()):
    return _Intercept(Jail(read_roots, write_roots, blocked_hosts))
