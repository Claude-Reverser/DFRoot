"""Host-only collector regressions. The fake ADB never contacts a device."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "collect_logs.py"
SPEC = importlib.util.spec_from_file_location("collect_logs", MODULE_PATH)
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)

FAKE_ADB = r'''
import json
from pathlib import Path
import sys
import time

root = Path(__file__).parent
scenario = json.loads((root / "scenario.json").read_text())
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as output:
    output.write(json.dumps(args) + "\n")
if args[:2] != ["-s", "TEST-DEVICE"]:
    raise SystemExit(99)
args = args[2:]

def count(name):
    path = root / (name + ".count")
    current = int(path.read_text()) if path.exists() else 0
    path.write_text(str(current + 1))
    return current

if args == ["get-state"]:
    states = scenario.get("states", ["device"])
    state = states[min(count("state"), len(states) - 1)]
    if state == "timeout":
        time.sleep(20)
    if state != "device":
        print("fake transport unavailable", file=sys.stderr)
        raise SystemExit(1)
    print("device")
elif args[0] == "logcat":
    mode = scenario.get("logcat", "normal")
    iteration = count("logcat")
    if mode == "denied":
        print("fake permission denied", file=sys.stderr)
        raise SystemExit(3)
    if mode == "stderr-flood":
        sys.stderr.write("E" * 200000)
        sys.stderr.flush()
    elif mode == "stdout-flood":
        sys.stdout.write("L" * 200000)
        sys.stdout.flush()
    elif mode == "disconnect" and iteration == 0:
        print("dfroot: before reconnect", flush=True)
        raise SystemExit(1)
    elif "-d" in args:
        print("dfroot: fake snapshot", flush=True)
    else:
        while True:
            if mode != "silent":
                print("dfroot: fake stream " + "x" * 100, flush=True)
            time.sleep(0.005)
elif args[0] == "shell":
    command = args[1]
    if command == "getprop ro.build.version.release":
        time.sleep(scenario.get("metadata_delay", 0))
    if command == "su -c 'id -u'":
        count("authorization")
        if scenario.get("root") == "deny":
            print("fake su denied", file=sys.stderr)
            raise SystemExit(1)
        if scenario.get("root") == "timeout":
            time.sleep(20)
        print(scenario.get("uid", "0"))
    elif command.startswith("su -c "):
        print("fake protected diagnostic")
        if "/proc/last_kmsg" in command:
            print("fake source unavailable", file=sys.stderr)
            raise SystemExit(2)
    elif "random/boot_id" in command:
        print("00000000-0000-0000-0000-000000000001")
    else:
        print("fake metadata")
else:
    print("unexpected fake command", file=sys.stderr)
    raise SystemExit(98)
'''


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.fake = self.directory / "fake_adb.py"
        self.fake.write_text(FAKE_ADB, encoding="utf-8")
        self.scenario = self.directory / "scenario.json"
        self.scenario.write_text("{}", encoding="utf-8")
        self.output = self.directory / "captures"

    def configure(self, **scenario):
        self.scenario.write_text(json.dumps(scenario), encoding="utf-8")

    def session(self, **options):
        return collector.Session(
            [sys.executable, str(self.fake)], "TEST-DEVICE", self.output, **options)

    def calls(self):
        path = self.directory / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def manifest(self, session):
        return json.loads((session.directory / "manifest.json").read_text())

    def collect(self, session):
        # Short retry windows keep fake-transport tests fast. All commands still
        # execute real child processes with both stdout and stderr pipes.
        with mock.patch.object(collector, "RECONNECT_DELAY", 0.05), \
                mock.patch.object(collector, "PROBE_TIMEOUT", 0.25), \
                mock.patch.object(collector, "AUTH_TIMEOUT", 0.25):
            return session.collect()

    def test_snapshot_is_filtered_read_only_and_keeps_source_failures_separate(self):
        session = self.session()
        with mock.patch.object(collector.subprocess, "Popen",
                               wraps=collector.subprocess.Popen) as spawn:
            self.assertEqual(self.collect(session), 0)
        calls = self.calls()
        self.assertTrue(all(call[:2] == ["-s", "TEST-DEVICE"] for call in calls))
        logcat = next(call[2:] for call in calls if call[2] == "logcat")
        self.assertIn("-d", logcat)
        self.assertEqual(logcat[-2:], ["dfroot:V", "*:S"])
        allowed = [["get-state"], logcat] + [["shell", command] for _, command in collector.METADATA]
        self.assertTrue(all(call[2:] in allowed for call in calls))
        manifest = self.manifest(session)
        self.assertEqual(manifest["root_status"], "not-requested")
        self.assertEqual(manifest["status"], "finished")
        self.assertTrue(all(command["status"] == "ok" for command in manifest["commands"]))
        started = [call.args[0][4:] for call in spawn.call_args_list]
        self.assertLess(started.index(logcat),
                        min(i for i, command in enumerate(started) if command[0] == "shell"))

    def test_root_is_verified_once_and_only_fixed_protected_sources_are_read(self):
        session = self.session(root=True)
        self.assertEqual(self.collect(session), 0)
        self.assertEqual((self.directory / "authorization.count").read_text(), "1")
        calls = self.calls()
        protected = [call[2:] for call in calls
                     if call[2] == "shell" and call[3].startswith("su -c ")]
        expected = [collector.root_command("id -u")]
        expected += [collector.root_command(command) for _, command in collector.ROOT_SOURCES]
        self.assertEqual(protected, expected)
        manifest = self.manifest(session)
        self.assertEqual(manifest["root_status"], "authorized")
        failed = next(command for command in manifest["commands"] if command["source"] == "last-kmsg")
        self.assertEqual((failed["status"], failed["exit_code"]), ("failed", 2))
        streams = {info["stream"] for info in manifest["storage"]["files"].values()
                   if info["command"] == failed["id"]}
        self.assertEqual(streams, {"stdout", "stderr"})
        joined = " ".join(" ".join(call[2:]) for call in calls)
        for forbidden in ("reboot", "am start", "logcat -c", "setenforce", "adb root"):
            self.assertNotIn(forbidden, joined)

    def test_finished_snapshot_is_not_timed_out_by_slow_metadata(self):
        self.configure(metadata_delay=0.35)
        session = self.session()
        # The snapshot exits immediately. Its first poll is delayed by metadata
        # beyond its own timeout; that delay must not overwrite the real exit.
        with mock.patch.object(collector, "COMMAND_TIMEOUT", 0.1):
            self.assertEqual(self.collect(session), 0)
        logcat = next(item for item in self.manifest(session)["commands"]
                      if item["source"] == "logcat")
        self.assertEqual((logcat["status"], logcat["exit_code"]), ("ok", 0))

    def test_nonroot_uid_does_not_enable_protected_reads(self):
        self.configure(uid="2000")
        session = self.session(root=True)
        self.assertEqual(self.collect(session), 0)
        protected = [call for call in self.calls()
                     if call[2] == "shell" and call[3].startswith("su -c ")]
        self.assertEqual(len(protected), 1)
        self.assertEqual(self.manifest(session)["root_status"], "unavailable")

    def test_denied_and_timed_out_root_leave_normal_capture_working(self):
        for mode in ("deny", "timeout"):
            with self.subTest(mode=mode):
                self.configure(root=mode)
                session = self.session(root=True)
                self.assertEqual(self.collect(session), 0)
                manifest = self.manifest(session)
                self.assertEqual(manifest["root_status"], "unavailable")
                auth = next(item for item in manifest["commands"]
                            if item["source"] == "root-authorization")
                self.assertEqual(auth["status"], "failed" if mode == "deny" else "timeout")

    def test_watch_recovers_from_probe_timeout_and_stream_disconnect(self):
        self.configure(states=["timeout", "device"], logcat="disconnect")
        session = self.session(watch=True, duration=2.0)
        started = time.monotonic()
        self.assertEqual(self.collect(session), 0)
        self.assertLess(time.monotonic() - started, 4)
        manifest = self.manifest(session)
        self.assertGreaterEqual(manifest["connections"], 2)
        self.assertEqual(manifest["status"], "duration-reached")
        self.assertTrue(any(event["status"] == "timeout" for event in manifest["events"]))
        self.assertFalse(any(item["status"] == "running" for item in manifest["commands"]))
        self.assertTrue(all(call[:2] == ["-s", "TEST-DEVICE"] for call in self.calls()))
        self.assertFalse(session.active)

    def test_watch_does_not_reauthorize_root_after_reconnect(self):
        self.configure(logcat="disconnect", root="deny")
        session = self.session(watch=True, root=True, duration=2.0)
        self.assertEqual(self.collect(session), 0)
        self.assertGreaterEqual(self.manifest(session)["connections"], 2)
        self.assertEqual((self.directory / "authorization.count").read_text(), "1")

    def test_watch_terminates_a_stream_that_stalls_after_disconnect(self):
        self.configure(states=["device", "offline"], logcat="silent")
        session = self.session(watch=True, duration=1.2)
        self.collect(session)
        logs = [item for item in self.manifest(session)["commands"] if item["source"] == "logcat"]
        self.assertEqual(logs[0]["status"], "disconnected")
        self.assertFalse(session.active)

    def test_rotation_bounds_files_and_retained_bytes(self):
        session = self.session(watch=True, duration=1.2, source_limit=256, total_limit=2048)
        self.assertEqual(self.collect(session), 0)
        manifest = self.manifest(session)
        storage = manifest["storage"]
        self.assertLessEqual(storage["retained_bytes"], 2048)
        self.assertGreater(storage["evicted_files"], 0)
        actual = {path.name: path.stat().st_size for path in session.directory.glob("*.log")}
        self.assertEqual(sum(actual.values()), storage["retained_bytes"])
        self.assertTrue(all(info["bytes"] == actual[name]
                            for name, info in storage["files"].items()))
        self.assertTrue(all(size <= 256 for name, size in actual.items() if ".stdout-" in name))

    def test_flooded_stdout_and_stderr_are_bounded_and_marked(self):
        for mode in ("stdout-flood", "stderr-flood"):
            with self.subTest(mode=mode):
                self.configure(logcat=mode)
                session = self.session(source_limit=128)
                self.collect(session)
                manifest = self.manifest(session)
                logcat = next(item for item in manifest["commands"] if item["source"] == "logcat")
                self.assertEqual(logcat["status"], "source-limit")
                for info in manifest["storage"]["files"].values():
                    if info["command"] == logcat["id"]:
                        maximum = 128 if info["stream"] == "stdout" else collector.STDERR_LIMIT
                        self.assertLessEqual(info["bytes"], maximum)

    def test_offline_snapshot_is_a_recorded_failure(self):
        self.configure(states=["offline"])
        session = self.session()
        self.assertEqual(self.collect(session), 1)
        manifest = self.manifest(session)
        self.assertEqual(manifest["connections"], 0)
        self.assertEqual((manifest["status"], manifest["exit_code"]), ("failed", 1))
        self.assertTrue(any(info["stream"] == "stderr"
                            for info in manifest["storage"]["files"].values()))
        self.assertEqual(len(self.calls()), 1)

    def test_failed_logcat_is_not_reported_as_a_successful_snapshot(self):
        self.configure(logcat="denied")
        session = self.session()
        self.assertEqual(self.collect(session), 1)
        logcat = self.manifest(session)["commands"][0]
        self.assertEqual((logcat["status"], logcat["exit_code"]), ("failed", 3))

    def test_watch_with_only_failed_streams_returns_failure(self):
        self.configure(logcat="denied")
        session = self.session(watch=True, duration=1.2)
        self.assertEqual(self.collect(session), 1)
        self.assertEqual(self.manifest(session)["exit_code"], 1)

    def test_missing_executable_is_recorded_without_an_unhandled_exception(self):
        session = collector.Session(
            [str(self.directory / "absent-adb")], "TEST-DEVICE", self.output)
        self.assertEqual(self.collect(session), 1)
        command = self.manifest(session)["commands"][0]
        self.assertEqual(command["status"], "spawn-error")
        self.assertIsNone(command["exit_code"])
        self.assertFalse(session.active)

    def test_sessions_are_unique_and_private(self):
        first, second = self.session(), self.session()
        self.assertNotEqual(first.directory, second.directory)
        self.assertEqual(self.collect(first), 0)
        if os.name != "nt":
            self.assertEqual(stat.S_IMODE(first.directory.stat().st_mode), 0o700)
            for path in first.directory.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_cli_requires_serial_and_positive_finite_duration(self):
        cases = [[], ["--serial", ""], ["--serial", "TEST", "--duration", "0"],
                 ["--serial", "TEST", "--duration", "-1"],
                 ["--serial", "TEST", "--duration", "nan"],
                 ["--serial", "TEST", "--duration", "inf"]]
        for arguments in cases:
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    collector.main(arguments)
                self.assertEqual(raised.exception.code, 2)
        self.assertEqual(self.calls(), [])

    def test_stop_event_cleans_up_children_and_marks_interruption(self):
        session = self.session(watch=True)
        original = session.snapshots

        def stop_after_snapshot(stream):
            original(stream)
            session.stop.set()

        session.snapshots = stop_after_snapshot
        self.collect(session)
        manifest = self.manifest(session)
        self.assertEqual(manifest["status"], "interrupted")
        self.assertFalse(session.active)
        self.assertFalse(any(item["status"] == "running" for item in manifest["commands"]))


if __name__ == "__main__":
    unittest.main()
