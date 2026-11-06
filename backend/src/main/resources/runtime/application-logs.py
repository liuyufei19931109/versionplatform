"""Read-only application logs from the selected container's bind mounts.

No client supplied paths are accepted. File IDs resolve only within bind mounts
strictly below ROOT; every directory and file is reopened without symlinks.
"""
import base64
import collections
import datetime as dt
import gzip
import hashlib
import io
import json
import os
import re
import select
import signal
import stat
import subprocess
import sys
import time
import zipfile
from zoneinfo import ZoneInfo

ROOT = "/var/log/apps"
MARK = "@@RELEASE_LOG "
MAX_LINE = 12000
MAX_FILES = 300
MAX_SCAN = 512 * 1024 * 1024
MAX_OUTPUT = 3 * 1024 * 1024 - 1024
STAMP = re.compile(r"^\[?(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})?)")
LOG_NAME = re.compile(r".+\.log(?:\.\d+)?(?:\.zip|\.gz)?$")


class LogError(Exception):
    pass


def marker(value):
    print(MARK + value, flush=True)


def relative(path):
    """Reject absolute paths, parent traversal, and protocol control bytes."""
    if not path or os.path.isabs(path) or "\\" in path:
        raise LogError("LOG_FILE_GONE")
    parts = path.split("/")
    if any(p in ("", ".", "..") or any(ord(c) < 32 for c in p) for p in parts):
        raise LogError("LOG_FILE_GONE")
    return parts


def safe_directory(parts):
    fd = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def safe_open(path):
    parts = relative(path)
    # Linux dirfd traversal prevents symlink replacement between discovery/open.
    fd = safe_directory(parts[:-1])
    try:
        result = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        if not stat.S_ISREG(os.fstat(result).st_mode):
            os.close(result)
            raise LogError("LOG_FILE_GONE")
        return os.fdopen(result, "rb")
    finally:
        os.close(fd)


def mount_paths(container):
    result = []
    root = os.path.normpath(ROOT)
    for mount in container.get("Mounts", []):
        source = mount.get("Source", "")
        if mount.get("Type") != "bind" or not isinstance(source, str):
            continue
        source = os.path.normpath(source)
        # A shared root is deliberately excluded: it does not identify a service.
        if not source.startswith(root + os.sep):
            continue
        rel = os.path.relpath(source, root).replace(os.sep, "/")
        relative(rel)
        result.append(rel)
    return result


def discover(container):
    files = {}
    for mount in mount_paths(container):
        source = os.path.join(ROOT, *relative(mount))
        candidates = []
        try:
            mode = os.lstat(source).st_mode
        except FileNotFoundError:
            continue
        except OSError as error:
            raise LogError("LOG_ACCESS") from error
        if stat.S_ISREG(mode):
            candidates.append(source)
        elif stat.S_ISDIR(mode):
            try:
                directory_fd = safe_directory(relative(mount))
                os.close(directory_fd)
            except PermissionError as error:
                raise LogError("LOG_ACCESS") from error
            except OSError:
                continue
            def denied(error):
                raise LogError("LOG_ACCESS") from error
            for directory, dirs, names in os.walk(source, followlinks=False, onerror=denied):
                # Logs have at most an archive directory. Bound enumeration too.
                depth = os.path.relpath(directory, source).count(os.sep)
                dirs[:] = sorted(d for d in dirs if not os.path.islink(os.path.join(directory, d)))[:40] if depth < 3 else []
                candidates.extend(os.path.join(directory, name) for name in names[:2000] if LOG_NAME.fullmatch(name))
                if len(candidates) >= 2000:
                    break
        for candidate in candidates:
            name = os.path.relpath(candidate, ROOT).replace(os.sep, "/")
            if not LOG_NAME.fullmatch(os.path.basename(name)):
                continue
            try:
                with safe_open(name) as source_file:
                    info = os.fstat(source_file.fileno())
                files[name] = {"id": hashlib.sha256(name.encode()).hexdigest(), "name": name, "mtime": info.st_mtime}
            except PermissionError as error:
                raise LogError("LOG_ACCESS") from error
            except (OSError, LogError):
                continue
    def order(item):
        name = os.path.basename(item["name"])
        preferred = {"info.log": 0, "default.log": 1, "business.log": 2, "error.log": 3}.get(name, 4)
        return (preferred, -item["mtime"], item["name"])
    return sorted(files.values(), key=order)[:MAX_FILES]


def timezone(container):
    env = container.get("Config", {}).get("Env", [])
    zone = next((v[3:] for v in env if isinstance(v, str) and v.startswith("TZ=")), None)
    # JVM property takes precedence over the environment when configured.
    for value in env:
        if isinstance(value, str) and value.split("=", 1)[0] in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            match = re.search(r"(?:^|\s)-Duser\.timezone=([^\s]+)", value.split("=", 1)[-1])
            if match:
                zone = match.group(1).strip("\"'")
    try:
        return ZoneInfo(zone or "UTC")
    except (ValueError, KeyError):
        return dt.timezone.utc


def timestamp(text, zone):
    match = STAMP.match(text)
    if not match:
        return None
    try:
        stamp = dt.datetime.fromisoformat(match.group(1).replace(",", ".").replace("Z", "+00:00"))
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=zone)
        return stamp.astimezone(dt.timezone.utc)
    except ValueError:
        return None


def boundary(value):
    if not value:
        return None
    if re.fullmatch(r"\d+[smh]", value):
        return dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=int(value[:-1]) * {"s": 1, "m": 60, "h": 3600}[value[-1]])
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def read_line(stream):
    data = stream.readline(MAX_LINE + 1)
    if not data:
        return None
    consumed = len(data)
    complete = data.endswith(b"\n")
    clipped = len(data) > MAX_LINE and not data.endswith(b"\n")
    if clipped:
        while True:
            rest = stream.readline(MAX_LINE + 1)
            consumed += len(rest)
            complete = rest.endswith(b"\n")
            if consumed > MAX_SCAN:
                raise LogError("LOG_TOO_LARGE")
            if not rest or rest.endswith(b"\n"):
                break
    text = data[:MAX_LINE].decode("utf-8", "replace").rstrip("\r\n") + (" …[本行过长，已截断]" if clipped else "")
    return text, consumed, complete


def render(text, stamp):
    return (stamp.isoformat(timespec="milliseconds").replace("+00:00", "Z") + " " if stamp else "") + text


def snapshot(stream, tail, since, until, zone, live=False):
    lines = collections.deque()
    byte_count = 0
    stamp = None
    scanned = 0
    truncated = False
    while True:
        position = stream.tell() if live else None
        record = read_line(stream)
        if record is None:
            break
        text, consumed, complete = record
        if live and not complete:
            stream.seek(position)
            break
        scanned += consumed
        if scanned > MAX_SCAN:
            raise LogError("LOG_TOO_LARGE")
        stamp = timestamp(text, zone) or stamp
        if (since or until) and stamp is None:
            continue
        if stamp and ((since and stamp < since) or (until and stamp > until)):
            continue
        value = render(text, stamp)
        size = len(value.encode("utf-8")) + 1
        lines.append((value, size))
        byte_count += size
        while len(lines) > tail:
            _, removed = lines.popleft()
            byte_count -= removed
        while byte_count > MAX_OUTPUT:
            _, removed = lines.popleft()
            byte_count -= removed
            truncated = True
    return [v for v, _ in lines], stamp, truncated


def reopen(name):
    return safe_open(name)


class Lease:
    """End abandoned IAP sessions even if sshd keeps their output pipe open."""
    def __init__(self, fd=0):
        self.fd = fd
        self.last = time.monotonic()
        self.pending = b""

    def alive(self):
        try:
            if select.select([self.fd], [], [], 0)[0]:
                data = os.read(self.fd, 4096)
                if not data:
                    return False
                self.pending += data
                if b"RELEASE_LOG_KEEPALIVE\n" in self.pending:
                    self.last = time.monotonic()
                self.pending = self.pending[-20:]
        except (OSError, ValueError):
            return False
        return time.monotonic() - self.last < 10


def follow(stream, name, zone, stamp, lease=None):
    """Drain the old inode, then reopen after rename or copytruncate rotation."""
    pending = b""
    clipped = False
    deadline, heartbeat = time.monotonic() + 1800, 0
    try:
        while time.monotonic() < deadline and (lease is None or lease.alive()):
            if time.monotonic() - heartbeat > 1:
                # An idle reader must notice a disconnected SSH pipe and terminate.
                marker("HEARTBEAT")
                heartbeat = time.monotonic()
            chunk = stream.readline(MAX_LINE + 1)
            if chunk:
                pending += chunk
                if len(pending) > MAX_LINE:
                    pending = pending[:MAX_LINE]
                    clipped = True
                if chunk.endswith(b"\n"):
                    text = pending.decode("utf-8", "replace").rstrip("\r\n") + (" …[本行过长，已截断]" if clipped else "")
                    stamp = timestamp(text, zone) or stamp
                    print(render(text, stamp), flush=True)
                    pending, clipped = b"", False
                continue
            replacement = None
            try:
                replacement = reopen(name)
                old, new = os.fstat(stream.fileno()), os.fstat(replacement.fileno())
                changed = (old.st_dev, old.st_ino) != (new.st_dev, new.st_ino)
                if changed or new.st_size < stream.tell():
                    stream.close()
                    stream, replacement = replacement, None
                    pending, clipped, stamp = b"", False, None
            except (OSError, LogError):
                pass
            finally:
                if replacement:
                    replacement.close()
            time.sleep(0.4)
    finally:
        stream.close()


def run(args):
    container_id, file_id, tail, since, until, live = args
    raw = subprocess.run(["docker", "inspect", container_id], capture_output=True, check=True, timeout=10)
    container = json.loads(raw.stdout)[0]
    if container.get("Id") != container_id:
        raise LogError("CONTAINER_GONE")
    files = discover(container)
    if not files:
        if file_id:
            raise LogError("LOG_FILE_GONE")
        return 75
    selected = next((f for f in files if f["id"] == file_id), None) if file_id else files[0]
    if selected is None:
        raise LogError("LOG_FILE_GONE")
    zone = timezone(container)
    source = safe_open(selected["name"])
    archive = None
    stream = source
    try:
        if selected["name"].endswith(".zip"):
            archive = zipfile.ZipFile(source)
            members = [m for m in archive.infolist() if not m.is_dir() and m.filename.endswith(".log")]
            if len(members) != 1 or members[0].file_size > MAX_SCAN:
                raise LogError("LOG_ARCHIVE")
            stream = archive.open(members[0])
        elif selected["name"].endswith(".gz"):
            stream = gzip.GzipFile(fileobj=source)
        lines, stamp, truncated = snapshot(stream, int(tail), boundary(since), boundary(until), zone, live == "1" and stream is source)
        marker("SOURCE application")
        for file in files:
            marker("FILE " + file["id"] + " " + base64.b64encode(file["name"].encode()).decode())
        marker("FILESELECTED " + selected["id"])
        if truncated:
            marker("TRUNCATED true")
        marker("SELECTED " + container_id)
        for line in lines:
            print(line, flush=True)
        if live == "1" and stream is source:
            follow(source, selected["name"], zone, stamp, Lease())
        return 0
    finally:
        stream.close()
        if archive:
            archive.close()
        source.close()


if __name__ == "__main__":
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    try:
        sys.exit(run(sys.argv[1:]))
    except LogError as error:
        marker("ERROR " + str(error))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError):
        marker("ERROR LOG_ACCESS")
