import base64
import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
import pathlib
import sys
import tempfile
import types
import unittest
import zipfile
from unittest import mock

script = pathlib.Path(__file__).resolve().parents[2] / "main/resources/runtime/application-logs.py"
spec = importlib.util.spec_from_file_location("application_logs", script)
logs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(logs)
UTC = dt.timezone.utc
LINUX_OPEN = hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd


class ApplicationLogsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.patch = mock.patch.object(logs, "ROOT", str(self.root))
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def container(self, name="catalog"):
        return {"Mounts": [{"Type": "bind", "Source": str(self.root / name), "Destination": "/app/logs"}],
                "Config": {"Env": ["TZ=Etc/GMT-2"]}}

    def test_only_descendant_container_bind_mounts_are_eligible(self):
        container = self.container()
        container["Mounts"] += [
            {"Type": "bind", "Source": str(self.root)},
            {"Type": "bind", "Source": str(self.root.parent / "private")},
            {"Type": "volume", "Source": str(self.root / "sibling")},
        ]
        self.assertEqual(logs.mount_paths(container), ["catalog"])
        for name in ("../private/key", "/etc/passwd", "catalog/../sibling/log", "catalog\\log", "catalog/./log", "catalog/x\ny"):
            with self.assertRaises(logs.LogError):
                logs.relative(name)

    def test_local_timestamps_use_the_container_timezone_and_multiline_inherits(self):
        zone = logs.timezone(self.container())
        self.assertEqual(logs.timestamp("2026-10-19 08:00:00.123 INFO hello", zone).isoformat(), "2026-10-19T06:00:00.123000+00:00")
        shanghai = logs.timezone({"Config": {"Env": ["TZ=Asia/Shanghai"]}})
        self.assertEqual(logs.timestamp("2026-10-19 08:00:00.123 INFO hello", shanghai).hour, 0)
        self.assertEqual(logs.timestamp("2026-10-19T08:00:00.123+02:00 INFO hello", shanghai).hour, 6)
        source = io.BytesIO(b"2026-10-19 07:00:00.000 old\n2026-10-19 08:00:00.123 ERROR boom\n\tat stack\n2026-10-19 09:00:00.000 later\n")
        lines, _, _ = logs.snapshot(source, 30, logs.boundary("2026-10-19T06:00:00Z"), logs.boundary("2026-10-19T06:30:00Z"), zone)
        self.assertEqual(len(lines), 2)
        self.assertTrue(all(line.startswith("2026-10-19T06:00:00.123Z ") for line in lines))
        self.assertTrue(lines[1].endswith("\tat stack"))

    def test_snapshot_tail_is_bounded_and_does_not_invent_timestamps(self):
        lines, stamp, truncated = logs.snapshot(io.BytesIO(b"first\nsecond\nthird\n"), 2, None, None, UTC)
        self.assertEqual(lines, ["second", "third"])
        self.assertIsNone(stamp)
        self.assertFalse(truncated)
        filtered, _, _ = logs.snapshot(io.BytesIO(b"no timestamp\n"), 2, logs.boundary("1h"), None, UTC)
        self.assertEqual(filtered, [])

    def test_live_initial_snapshot_leaves_an_incomplete_line_for_follow(self):
        source = io.BytesIO(b"complete\npartial")
        lines, _, _ = logs.snapshot(source, 10, None, None, UTC, live=True)
        self.assertEqual(lines, ["complete"])
        self.assertEqual(source.read(), b"partial")

    def test_long_line_scan_budget_counts_discarded_bytes(self):
        with mock.patch.object(logs, "MAX_LINE", 10), mock.patch.object(logs, "MAX_SCAN", 40):
            with self.assertRaisesRegex(logs.LogError, "LOG_TOO_LARGE"):
                logs.snapshot(io.BytesIO(b"x" * 100 + b"\n"), 10, None, None, UTC)

    def test_permission_failure_is_not_reported_as_no_application_files(self):
        (self.root / "catalog").mkdir()
        with mock.patch.object(logs, "safe_directory", side_effect=PermissionError("denied")):
            with self.assertRaisesRegex(logs.LogError, "LOG_ACCESS"):
                logs.discover(self.container())

    def test_requested_file_id_must_exist_in_the_bound_mount(self):
        cid = "a" * 64
        container = self.container()
        container["Id"] = cid
        result = types.SimpleNamespace(stdout=json.dumps([container]).encode())
        with mock.patch.object(logs.subprocess, "run", return_value=result), mock.patch.object(logs, "discover", return_value=[{"id": "b" * 64, "name": "catalog/info.log"}]), mock.patch.object(logs, "safe_open") as opened:
            with self.assertRaisesRegex(logs.LogError, "LOG_FILE_GONE"):
                logs.run([cid, "c" * 64, "10", "", "", "0"])
            opened.assert_not_called()

    def test_lease_expires_without_new_heartbeats_even_when_output_stays_open(self):
        with mock.patch.object(logs.time, "monotonic", return_value=10), mock.patch.object(logs.select, "select", return_value=([], [], [])):
            lease = logs.Lease(999)
            self.assertTrue(lease.alive())
            with mock.patch.object(logs.time, "monotonic", return_value=21):
                self.assertFalse(lease.alive())

    def test_lease_reads_split_heartbeats_and_stops_immediately_on_eof(self):
        with mock.patch.object(logs.time, "monotonic", return_value=10):
            lease = logs.Lease(999)
        with mock.patch.object(logs.select, "select", return_value=([999], [], [])), mock.patch.object(logs.os, "read", side_effect=[b"RELEASE_LOG_", b"KEEPALIVE\n", b""]), mock.patch.object(logs.time, "monotonic", return_value=18):
            self.assertTrue(lease.alive())
            self.assertTrue(lease.alive())
            self.assertEqual(lease.last, 18)
            self.assertFalse(lease.alive())

    def test_zip_archive_is_read_without_extracting_paths(self):
        cid = "a" * 64
        container = self.container()
        container["Id"] = cid
        result = types.SimpleNamespace(stdout=json.dumps([container]).encode())
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as writer:
            writer.writestr("../../anything.log", "2026-10-19 08:00:00.000 archived\n")
        archive.seek(0)
        output = io.StringIO()
        with mock.patch.object(logs.subprocess, "run", return_value=result), mock.patch.object(logs, "discover", return_value=[{"id": "b" * 64, "name": "catalog/info.log.zip"}]), mock.patch.object(logs, "safe_open", return_value=archive), contextlib.redirect_stdout(output):
            self.assertEqual(logs.run([cid, "", "10", "", "", "0"]), 0)
        self.assertIn("SOURCE application", output.getvalue())
        self.assertIn("2026-10-19T06:00:00.000Z 2026-10-19 08:00:00.000 archived", output.getvalue())
        self.assertEqual(list(self.root.iterdir()), [])

    @unittest.skipUnless(LINUX_OPEN, "secure dirfd opens are a Linux server capability")
    def test_discovery_excludes_siblings_and_symlinks_and_includes_archives(self):
        service = self.root / "catalog"
        service.mkdir()
        (service / "archive").mkdir()
        (service / "info.log").write_text("own")
        (service / "error.log").write_text("")
        (service / "archive/info.2026-10-18.0.log.zip").write_bytes(b"archive")
        sibling = self.root / "management"
        sibling.mkdir()
        (sibling / "info.log").write_text("private")
        (service / "escape.log").symlink_to(sibling / "info.log")
        (service / "linked").symlink_to(sibling, target_is_directory=True)
        files = logs.discover(self.container())
        self.assertEqual([item["name"] for item in files], ["catalog/info.log", "catalog/error.log", "catalog/archive/info.2026-10-18.0.log.zip"])
        self.assertEqual(len(files[0]["id"]), 64)
        with self.assertRaises(OSError):
            logs.safe_open("catalog/escape.log")
        with self.assertRaises(OSError):
            logs.safe_open("catalog/linked/info.log")

    @unittest.skipUnless(LINUX_OPEN, "secure dirfd opens are a Linux server capability")
    def test_follow_reopens_after_rotation_and_copytruncate(self):
        service = self.root / "catalog"
        service.mkdir()
        path = service / "info.log"
        path.write_text("old initial\n")
        source = logs.safe_open("catalog/info.log")
        source.seek(0, 2)
        stage = [0]
        class Finished(Exception):
            pass
        def advance(_):
            stage[0] += 1
            if stage[0] == 1:
                with path.open("a") as writer:
                    writer.write("appended\n")
            elif stage[0] == 2:
                path.rename(service / "info.previous.log")
                path.write_text("rotated\n")
            elif stage[0] == 4:
                path.write_text("new\n")
            elif stage[0] == 6:
                raise Finished()
        output = io.StringIO()
        try:
            with contextlib.redirect_stdout(output), mock.patch.object(logs.time, "sleep", side_effect=advance):
                with self.assertRaises(Finished):
                    logs.follow(source, "catalog/info.log", UTC, None)
        finally:
            source.close()
        self.assertIn("appended\n", output.getvalue())
        self.assertIn("rotated\n", output.getvalue())
        self.assertIn("new\n", output.getvalue())
        self.assertNotIn("old initial", output.getvalue())


if __name__ == "__main__":
    unittest.main()
