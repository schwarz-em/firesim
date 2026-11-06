"""Drop-in replacements for Fabric's remote operations (run, put, get,
rsync_project) that run locally, without ssh, when the current Fabric host is
this machine. On any other host they defer to Fabric.

Like Fabric's run, the local run honors cd(), prefix(), warn_only and hide(),
returns the command's stdout with .return_code/.failed/.succeeded/.stderr, and
echoes the output unless it is hidden.
"""

from __future__ import annotations

import glob
import os
import shlex
import shutil
import sys
from typing import Any, List

from fabric.api import env, local, settings, hide  # type: ignore
from fabric.api import run as remote_run, put as remote_put, get as remote_get  # type: ignore
from fabric.contrib.project import rsync_project as remote_rsync_project  # type: ignore
from fabric.state import output  # type: ignore


def on_localhost() -> bool:
    """Whether the current Fabric host is this machine, which needs no ssh."""
    return env.host in ("localhost", "127.0.0.1")


def run(
    command: str,
    shell: bool = True,
    warn_only: bool = False,
    quiet: bool = False,
    **kwargs: Any,
) -> Any:
    """Fabric's run, or the same command in a local bash when the host is this
    machine. Remote-only options (pty, timeout, ...) are ignored locally."""
    if not on_localhost():
        return remote_run(
            command, shell=shell, warn_only=warn_only, quiet=quiet, **kwargs
        )
    # Fabric's local() follows lcd() and ignores cd(), which is what callers
    # of run() use, so carry the remote cwd over.
    overrides: dict = {"warn_only": warn_only or quiet or env.warn_only}
    if env.cwd:
        overrides["lcwd"] = env.cwd
    hidden = ["everything"] if quiet else []
    with settings(hide(*hidden), **overrides):
        # stdin from /dev/null so nothing (e.g. a daemonized screen) waits on it
        result = local(
            f"{{ {command}\n}} < /dev/null", capture=True, shell="/bin/bash"
        )
        if result and output.stdout:
            print(result, flush=True)
        if result.stderr and output.stderr:
            print(result.stderr, file=sys.stderr, flush=True)
    return result


def _local_paths(pattern: str) -> List[str]:
    """Expand ~, a relative path against cd(), and globs, like the remote side."""
    path = os.path.expanduser(pattern)
    if not os.path.isabs(path) and env.cwd:
        path = os.path.join(os.path.expanduser(env.cwd), path)
    return sorted(glob.glob(path)) or [path]


def _copy(src: str, dst: str, mirror_local_mode: bool) -> str:
    if os.path.isdir(dst):
        dst = os.path.join(dst, os.path.basename(src.rstrip("/")))
    if os.path.isdir(src):
        shutil.copytree(src, dst, symlinks=False, dirs_exist_ok=True)
    elif mirror_local_mode:
        shutil.copy2(src, dst)
    else:
        shutil.copyfile(src, dst)
    return dst


def put(
    local_path: str, remote_path: str, mirror_local_mode: bool = False, **kwargs: Any
) -> Any:
    """Fabric's put, or a local copy when the host is this machine."""
    if not on_localhost():
        return remote_put(
            local_path, remote_path, mirror_local_mode=mirror_local_mode, **kwargs
        )
    dst = _local_paths(remote_path)[0]
    srcs = sorted(glob.glob(os.path.expanduser(local_path)))
    if not srcs:
        raise ValueError(f"put: '{local_path}' is not a valid local path or glob.")
    return [_copy(src, dst, mirror_local_mode) for src in srcs]


def get(remote_path: str, local_path: str, **kwargs: Any) -> Any:
    """Fabric's get, or a local copy when the host is this machine."""
    if not on_localhost():
        return remote_get(remote_path, local_path, **kwargs)
    return [
        _copy(src, os.path.expanduser(local_path), mirror_local_mode=True)
        for src in _local_paths(remote_path)
    ]


def rsync_project(
    remote_dir: str,
    local_dir: str,
    exclude: Any = (),
    delete: bool = False,
    extra_opts: str = "",
    upload: bool = True,
    capture: bool = False,
    default_opts: str = "-pthrvz",
    **kwargs: Any,
) -> Any:
    """Fabric's rsync_project, or the same rsync between local directories when
    the host is this machine. ssh_opts is ignored locally."""
    if not on_localhost():
        return remote_rsync_project(
            remote_dir=remote_dir,
            local_dir=local_dir,
            exclude=exclude,
            delete=delete,
            extra_opts=extra_opts,
            upload=upload,
            capture=capture,
            default_opts=default_opts,
            **kwargs,
        )
    if isinstance(exclude, str):
        exclude = [exclude]
    excludes = "".join(f" --exclude {shlex.quote(e)}" for e in exclude)
    delete_opt = " --delete" if delete else ""
    src, dst = (local_dir, remote_dir) if upload else (remote_dir, local_dir)
    return local(
        f"rsync{excludes}{delete_opt} {default_opts} {extra_opts} {src} {dst}",
        capture=capture,
    )
