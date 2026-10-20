#!/usr/bin/env python3
"""Collect local DFRoot diagnostics without changing the connected device.

Python 3.9+ and Android platform-tools are required. Normal collection contains
only dfroot-tagged logcat and the metadata listed below. --root uses existing su
access; its authorization probe runs once per invocation. Output is private on
POSIX hosts; Windows inherits the destination directory's access controls.

Watch mode retains a bounded, rotating history. Reconnected logcat streams may
overlap: buffers are never cleared and no records are silently deduplicated.
"""

import argparse
from collections import OrderedDict, deque
import datetime
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import tempfile
import threading
import time


SOURCE_LIMIT = 2 * 1024 * 1024
STDERR_LIMIT = 64 * 1024
TOTAL_LIMIT = 64 * 1024 * 1024
FILE_LIMIT = 128
HISTORY_LIMIT = 128
COMMAND_TIMEOUT = 5.0
AUTH_TIMEOUT = 10.0
PROBE_TIMEOUT = 2.0
RECONNECT_DELAY = 1.0

METADATA = (
    ("android-version", "getprop ro.build.version.release"),
    ("security-patch", "getprop ro.build.version.security_patch"),
    ("kernel-version", "uname -r"),
    ("boot-id", "cat /proc/sys/kernel/random/boot_id"),
    ("uptime", "cat /proc/uptime"),
    ("boot-completed", "getprop sys.boot_completed"),
    ("selinux", "getenforce"),
)
ROOT_SOURCES = (
    ("dmesg", "dmesg"),
    ("pstore", (
        'test -d /sys/fs/pstore || exit 2; found=0; result=0; '
        'for path in /sys/fs/pstore/*; do '
        'test -f "$path" || continue; found=1; '
        'printf "\\n--- %s ---\\n" "$path"; cat "$path" || result=1; '
        'done; test "$found" = 1 || exit 2; exit "$result"'
    )),
    ("last-kmsg", "cat /proc/last_kmsg"),
) + tuple(
    (period + "-" + name.removesuffix(".log"),
     "cat /data/user_de/0/df.root/files/diagnostics/" + period + "/" + name)
    for period in ("current", "previous")
    for name in ("run.log", "bootstrap.log")
)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def root_command(command):
    # adb shell joins its arguments into a remote shell command. Quote the
    # complete, fixed su command here; user arguments never enter this string.
    return ["shell", "su -c " + shlex.quote(command)]


class CaptureStore:
    """Bound disk use, including empty files, while keeping active files intact."""

    def __init__(self, directory, total_limit=TOTAL_LIMIT, file_limit=FILE_LIMIT):
        self.directory = directory
        self.total_limit = total_limit
        self.file_limit = file_limit
        self.files = OrderedDict()
        self.active = set()
        self.total = 0
        self.evicted_files = 0
        self.evicted_bytes = 0
        self.lock = threading.RLock()

    def _make_room(self, size=0, new_file=False):
        while (self.total + size > self.total_limit or
               len(self.files) + int(new_file) > self.file_limit):
            victim = next((name for name in self.files
                           if name not in self.active), None)
            if victim is None:
                return False
            info = self.files.pop(victim)
            (self.directory / victim).unlink()
            self.total -= info["bytes"]
            self.evicted_bytes += info["bytes"]
            self.evicted_files += 1
        return True

    def create(self, name, command, stream):
        with self.lock:
            if not self._make_room(new_file=True):
                raise OSError("capture file limit reached")
            descriptor = os.open(self.directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.close(descriptor)
            self.files[name] = {"command": command, "stream": stream, "bytes": 0}
            self.active.add(name)

    def append(self, name, data):
        with self.lock:
            if not self._make_room(len(data)):
                raise OSError("capture storage limit reached")
            with (self.directory / name).open("ab") as output:
                output.write(data)
            self.files[name]["bytes"] += len(data)
            self.total += len(data)

    def release(self, name):
        with self.lock:
            self.active.discard(name)

    def snapshot(self):
        with self.lock:
            return {
                "retained_bytes": self.total,
                "evicted_bytes": self.evicted_bytes,
                "evicted_files": self.evicted_files,
                "files": {name: dict(info) for name, info in self.files.items()},
            }


class OutputSink:
    def __init__(self, command, stream, limit, rotate):
        self.command = command
        self.stream = stream
        self.limit = limit
        self.rotate = rotate
        self.part = 0
        self.size = 0
        self.name = None
        self.preview = bytearray()
        self.bytes_seen = 0

    def _open(self):
        self.part += 1
        self.name = "%05d-%s.%s-%04d.log" % (
            self.command.number, self.command.name, self.stream, self.part)
        self.command.store.create(self.name, self.command.number, self.stream)
        self.size = 0

    def write(self, data):
        self.bytes_seen += len(data)
        self.preview.extend(data[:max(0, 4096 - len(self.preview))])
        while data:
            if self.size == self.limit:
                if not self.rotate:
                    self.command.limit_reached.set()
                    return
                if self.name:
                    self.command.store.release(self.name)
                self.name = None
                self.size = 0
            if self.command.store and self.name is None:
                self._open()
            chunk, data = data[:self.limit - self.size], data[self.limit - self.size:]
            if self.command.store:
                self.command.store.append(self.name, chunk)
            self.size += len(chunk)

    def close(self):
        if self.name:
            self.command.store.release(self.name)


class Command:
    """Drain both pipes concurrently so a noisy stderr cannot deadlock stdout."""

    def __init__(self, argv, name, number, stop, deadline=None, timeout=None,
                 store=None, rotate=False, source_limit=SOURCE_LIMIT):
        self.name, self.number, self.store = name, number, store
        self.stop_event, self.deadline, self.timeout = stop, deadline, timeout
        self.started = time.monotonic()
        self.limit_reached = threading.Event()
        self.io_error = None
        self.process = None
        self.threads = []
        self.record = {
            "id": number, "source": name, "started_at": utc_now(),
            "status": "running", "exit_code": None,
        }
        self.stdout = OutputSink(self, "stdout", source_limit, rotate)
        self.stderr = OutputSink(self, "stderr", STDERR_LIMIT, False)
        try:
            options = {"start_new_session": True} if os.name != "nt" else {}
            self.process = subprocess.Popen(
                argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, shell=False, bufsize=0, **options)
            for pipe, sink in ((self.process.stdout, self.stdout),
                               (self.process.stderr, self.stderr)):
                thread = threading.Thread(target=self._drain,
                                          args=(pipe, sink), daemon=True)
                thread.start()
                self.threads.append(thread)
        except OSError as error:
            self.io_error = str(error)
            self.finish("spawn-error")

    def _drain(self, pipe, sink):
        try:
            with pipe:
                while True:
                    data = pipe.read(65536)
                    if not data:
                        break
                    sink.write(data)
        except OSError as error:
            self.io_error = str(error)
            self.limit_reached.set()
        finally:
            sink.close()

    def finish(self, status):
        if self.record["status"] != "running":
            return
        if self.process is not None:
            if self.process.poll() is None:
                try:
                    self.process.terminate()
                except OSError:
                    if self.process.poll() is None:
                        raise
                try:
                    self.process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=1)
            for thread in self.threads:
                thread.join(timeout=2)
            self.record["exit_code"] = self.process.returncode
        if status in ("ok", "failed") and self.limit_reached.is_set():
            status = "output-error" if self.io_error else "source-limit"
        if self.io_error:
            self.record["error"] = self.io_error
        self.record.update(
            status=status, ended_at=utc_now(),
            stdout_bytes_seen=self.stdout.bytes_seen,
            stderr_bytes_seen=self.stderr.bytes_seen,
        )

    def poll(self):
        if self.record["status"] != "running":
            return True
        now = time.monotonic()
        # A command can finish while another source is being collected. Apply
        # deadlines only to a process that is still running when observed.
        if self.process.poll() is not None:
            self.finish("ok" if self.process.returncode == 0 else "failed")
        elif self.stop_event.is_set():
            self.finish("interrupted")
        elif self.deadline is not None and now >= self.deadline:
            self.finish("duration-reached")
        elif self.limit_reached.is_set():
            self.finish("output-error" if self.io_error else "source-limit")
        elif self.timeout is not None and now - self.started >= self.timeout:
            self.finish("timeout")
        return self.record["status"] != "running"

    def wait(self):
        while not self.poll():
            self.stop_event.wait(0.05)
        return self


class Session:
    def __init__(self, adb_command, serial, output, watch=False, duration=None,
                 root=False, source_limit=SOURCE_LIMIT, total_limit=TOTAL_LIMIT):
        output = Path(output)
        output.mkdir(mode=0o700, parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-")
        self.directory = Path(tempfile.mkdtemp(prefix=stamp, dir=output))
        self.base = list(adb_command) + ["-s", serial]
        self.watch, self.root = watch, root
        self.source_limit = source_limit
        self.store = CaptureStore(self.directory, total_limit)
        self.stop = threading.Event()
        self.deadline = time.monotonic() + duration if duration is not None else None
        self.commands = deque(maxlen=HISTORY_LIMIT)
        self.events = deque(maxlen=HISTORY_LIMIT)
        self.active = []
        self.sequence = 0
        self.retained_count = 0
        self.connections = 0
        self.root_checked = False
        self.root_authorized = False
        self.manifest = {
            "schema": 1, "started_at": utc_now(), "status": "starting",
            "watch": watch, "root_requested": root, "root_status": "not-requested",
            "limits": {"source_bytes": source_limit, "stderr_bytes": STDERR_LIMIT,
                       "retained_data_bytes": total_limit, "retained_files": FILE_LIMIT,
                       "command_history": HISTORY_LIMIT},
            "notes": [
                "Raw captures can contain private data; review before sharing.",
                "Reconnects may replay buffered records; files are not deduplicated.",
                "Older output files and command records are rotated out.",
            ],
        }

    def expired(self):
        return self.stop.is_set() or (
            self.deadline is not None and time.monotonic() >= self.deadline)

    def start_command(self, name, args, timeout=COMMAND_TIMEOUT, rotate=False,
                      retained=True, source_limit=None):
        self.sequence += 1
        command = Command(
            self.base + args, name, self.sequence, self.stop, self.deadline,
            timeout, self.store if retained else None, rotate,
            source_limit or self.source_limit)
        self.active.append(command)
        if retained:
            self.retained_count += 1
            self.commands.append(command.record)
        return command

    def run_command(self, name, args, timeout=COMMAND_TIMEOUT, retained=True,
                    source_limit=None):
        command = self.start_command(name, args, timeout, retained=retained,
                                     source_limit=source_limit)
        try:
            return command.wait()
        finally:
            if command.record["status"] != "running":
                self.active.remove(command)

    def connected(self):
        probe = self.run_command("connection", ["get-state"], PROBE_TIMEOUT,
                                 retained=False, source_limit=4096)
        connected = (probe.record["status"] == "ok" and
                     bytes(probe.stdout.preview).strip() == b"device")
        if not connected:
            self.commands.append(probe.record)
            self.retained_count += 1
            # Healthy heartbeats need no files. Retain bounded failure output
            # separately so offline/unauthorized/transport errors remain useful.
            for stream, sink in (("stdout", probe.stdout), ("stderr", probe.stderr)):
                if sink.preview:
                    name = "%05d-connection.%s-0001.log" % (probe.number, stream)
                    self.store.create(name, probe.number, stream)
                    try:
                        self.store.append(name, bytes(sink.preview))
                    finally:
                        self.store.release(name)
            probe.record["saved_output_is_preview"] = any(
                sink.bytes_seen > len(sink.preview) for sink in (probe.stdout, probe.stderr))
            self.events.append({
                "time": utc_now(), "event": "connection-unavailable",
                "status": probe.record["status"],
                "exit_code": probe.record["exit_code"],
            })
        return connected

    def save(self):
        self.manifest.update(
            updated_at=utc_now(), connections=self.connections,
            root_status=("authorized" if self.root_authorized else "unavailable")
            if self.root_checked else ("pending" if self.root else "not-requested"),
            command_records_discarded=max(0, self.retained_count - HISTORY_LIMIT),
            commands=list(self.commands), events=list(self.events),
            storage=self.store.snapshot(),
        )
        temporary = self.directory / "manifest.json.tmp"
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(json.dumps(self.manifest, indent=2) + "\n")
        temporary.replace(self.directory / "manifest.json")

    def snapshots(self, stream):
        for name, command in METADATA:
            if self.expired():
                return
            result = self.run_command(name, ["shell", command], source_limit=4096)
            if result.record["status"] != "ok" and not self.connected():
                return
            stream.poll()
        if self.root and not self.root_checked and not self.expired():
            self.root_checked = True
            authorization = self.run_command("root-authorization", root_command("id -u"),
                                             AUTH_TIMEOUT, source_limit=4096)
            self.root_authorized = (
                authorization.record["status"] == "ok" and
                bytes(authorization.stdout.preview).strip() == b"0")
        if self.root_authorized:
            for name, command in ROOT_SOURCES:
                if self.expired():
                    return
                result = self.run_command(name, root_command(command))
                if result.record["status"] != "ok" and not self.connected():
                    return
                stream.poll()
        self.save()

    def collect(self):
        logcat_ok = False
        def result(code):
            self.manifest["exit_code"] = code
            return code

        self.save()
        try:
            while not self.expired():
                if not self.connected():
                    self.manifest["status"] = "waiting-for-device"
                    self.save()
                    if not self.watch:
                        return result(1)
                    self.stop.wait(min(RECONNECT_DELAY, max(
                        0, self.deadline - time.monotonic())
                        if self.deadline is not None else RECONNECT_DELAY))
                    continue
                self.connections += 1
                args = ["logcat", "-b", "all", "-v", "threadtime"]
                if not self.watch:
                    args.append("-d")
                args += ["dfroot:V", "*:S"]
                stream = self.start_command("logcat", args, timeout=None if self.watch
                                            else COMMAND_TIMEOUT, rotate=self.watch)
                self.manifest["status"] = "recording"
                self.save()
                self.snapshots(stream)
                if not self.watch:
                    stream.wait()
                    logcat_ok = stream.record["status"] in ("ok", "source-limit")
                    return result(0 if logcat_ok else 1)
                next_probe = time.monotonic()
                while not stream.poll():
                    logcat_ok = True
                    if time.monotonic() >= next_probe:
                        if not self.connected():
                            stream.finish("disconnected")
                            break
                        next_probe = time.monotonic() + RECONNECT_DELAY
                        self.save()
                    self.stop.wait(0.05)
                logcat_ok = logcat_ok or stream.stdout.bytes_seen > 0
                self.active.remove(stream)
                if stream.record["status"] == "output-error":
                    return result(1)
                self.manifest["status"] = "waiting-for-device"
                self.save()
                if not self.expired():
                    self.stop.wait(RECONNECT_DELAY)
            return result(0 if logcat_ok else 1)
        except KeyboardInterrupt:
            self.stop.set()
            return result(130)
        finally:
            for command in list(self.active):
                command.finish("interrupted" if self.stop.is_set() else "stopped")
            self.active.clear()
            self.manifest["status"] = (
                "interrupted" if self.stop.is_set() else
                "duration-reached" if self.expired() else
                "finished" if self.manifest.get("exit_code") == 0 else "failed")
            self.manifest["ended_at"] = utc_now()
            self.save()


def positive_seconds(value):
    try:
        seconds = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be a positive number")
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("must be a positive, finite number")
    return seconds


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial; never switch devices")
    parser.add_argument("--watch", action="store_true", help="stream and reconnect until stopped")
    parser.add_argument("--duration", type=positive_seconds, help="maximum capture time in seconds")
    parser.add_argument("--root", action="store_true", help="try existing su access once")
    parser.add_argument("--output", default="out/diagnostics", help="parent directory for a new session")
    parser.add_argument("--adb", default="adb", help="ADB executable path or command name")
    args = parser.parse_args(argv)
    if not args.serial.strip():
        parser.error("--serial must not be empty")
    adb = shutil.which(args.adb)
    if adb is None:
        parser.error("ADB executable was not found; provide --adb or update PATH")
    previous_umask = os.umask(0o077)
    session = None
    old_term = None
    try:
        session = Session([adb], args.serial, args.output, args.watch, args.duration, args.root)
        old_term = signal.signal(signal.SIGTERM, lambda signum, frame: session.stop.set())
        print("Capture directory: " + str(session.directory), flush=True)
        return session.collect()
    except OSError as error:
        parser.exit(1, "Capture failed: %s\n" % error)
    finally:
        if old_term is not None:
            signal.signal(signal.SIGTERM, old_term)
        os.umask(previous_umask)


if __name__ == "__main__":
    raise SystemExit(main())
