import base64
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import stat
import time
import unittest
from unittest import mock

script = pathlib.Path(__file__).resolve().parents[2] / "main/resources/runtime/restart-service.py"
spec = importlib.util.spec_from_file_location("runtime_restart", script)
restart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart)
CID, NEW_CID = "a" * 64, "c" * 64
IMAGE = "sha256:" + "b" * 64
SECRET = "CREDENTIAL-MUST-NOT-APPEAR"

class RestartTest(unittest.TestCase):
    def setUp(self):
        restart.DEADLINE = time.monotonic() + 180
        self.config = {"phase": "restart", "mode": "DOCKER_COMPOSE", "directory": "/data/current project",
                       "file": "compose.yml", "service": "ui", "expected": {CID: IMAGE}, "port": 3000, "path": "/healthz/"}
        self.calls, self.snapshots = [], []
        self.recreated = False
        self.model = {"name": "project", "services": {"ui": {"image": "registry/ui:outdated",
                      "environment": {"NEW_SETTING": "saved-value", "PASSWORD": SECRET + "$${LITERAL}"},
                      "depends_on": {"db": {"condition": "service_started"}}}, "db": {"image": "mysql:old"}}}

    def container(self, cid=CID, image=IMAGE, running=True):
        return {"Id": cid, "Image": image,
                "State": {"Status": "running" if running else "exited", "Running": running, "Health": {"Status": "healthy"}},
                "Config": {"Image": "registry/ui:deployed", "Env": ["PASSWORD=" + SECRET],
                           "Labels": {"com.docker.compose.project": "project", "com.docker.compose.service": "ui"}},
                "HostConfig": {"NetworkMode": "project_internal"},
                "NetworkSettings": {"Networks": {"project_internal": {"IPAddress": "172.18.0.8"}}}}

    def command(self, args, **kwargs):
        self.calls.append(args)
        if args[:2] == ["docker", "compose"]:
            if "ps" in args: return CID + "\n"
            if "--format" in args: return json.dumps(self.model)
            snapshot = pathlib.Path(args[args.index("-f") + 1])
            if "--quiet" in args:
                self.snapshots.append(snapshot)
                self.snapshot_model = json.loads(snapshot.read_text())
                if os.name != 'nt': self.assertEqual(stat.S_IMODE(snapshot.stat().st_mode) & 0o077, 0)
                return ""
            if "up" in args:
                self.assertTrue(snapshot.exists())
                self.recreated = True
                return ""
        if args[:2] == ["docker", "inspect"]:
            return json.dumps([self.container(cid=NEW_CID if self.recreated else CID)])
        if args[:2] == ["docker", "image"]: return IMAGE + "\n"
        if args[:2] == ["docker", "ps"]: return NEW_CID + "\n"
        self.fail(args)

    def writes(self):
        return [args for args in self.calls if "up" in args or args[1] in ("restart", "rm", "stop", "pull", "build")]

    def test_reload_resolves_current_files_pins_image_and_only_recreates_selected_service(self):
        with mock.patch.object(restart, "command", side_effect=self.command):
            result = restart.restart(self.config)
        self.assertEqual(result, {NEW_CID: IMAGE})
        self.assertEqual(self.snapshot_model["services"]["ui"]["image"], IMAGE)
        self.assertEqual(self.snapshot_model["services"]["ui"]["environment"]["NEW_SETTING"], "saved-value")
        self.assertEqual(self.snapshot_model["services"]["ui"]["environment"]["PASSWORD"], SECRET + "$${LITERAL}")
        self.assertEqual(self.snapshot_model["services"]["ui"]["labels"][restart.IMAGE_REFERENCE_LABEL], "registry/ui:deployed")
        self.assertEqual(len(self.writes()), 1)
        call = self.writes()[0]
        for flag in ("--no-deps", "--force-recreate", "--no-build"): self.assertIn(flag, call)
        self.assertEqual(call[call.index("--pull") + 1], "never")
        self.assertEqual(call[call.index("--scale") + 1], "ui=1")
        self.assertEqual(call[-1], "ui")
        self.assertFalse(any(path.exists() for path in self.snapshots))
        self.assertNotIn(SECRET, json.dumps(result))

    def test_configuration_failure_does_not_stop_or_replace_existing_container(self):
        def command(args, **kwargs):
            if "--format" in args and args[1] == "compose":
                raise restart.OperationError("CONFIG_PERMISSION", "/data/env/ui.env")
            return self.command(args, **kwargs)
        with mock.patch.object(restart, "command", side_effect=command):
            with self.assertRaisesRegex(restart.OperationError, "CONFIG_PERMISSION"): restart.restart(self.config)
        self.assertEqual(self.writes(), [])

    def test_target_changed_before_operation_or_during_config_validation_aborts(self):
        for change_at in (1, 2):
            self.setUp()
            inspections = 0
            def command(args, **kwargs):
                nonlocal inspections
                if args[:2] == ["docker", "inspect"]:
                    inspections += 1
                    if inspections == change_at: return json.dumps([self.container(image="sha256:" + "d" * 64)])
                return self.command(args, **kwargs)
            with mock.patch.object(restart, "command", side_effect=command):
                with self.assertRaisesRegex(restart.OperationError, "TARGET_CHANGED"): restart.restart(self.config)
            self.assertEqual(self.writes(), [])
            self.assertFalse(any(path.exists() for path in self.snapshots))

    def test_existing_unconfirmed_replica_aborts(self):
        with mock.patch.object(restart, "command", side_effect=[CID + "\n" + NEW_CID, json.dumps([self.container(), self.container(cid=NEW_CID)])]):
            with self.assertRaisesRegex(restart.OperationError, "TARGET_CHANGED"): restart.restart(self.config)

    def test_mixed_running_image_versions_are_not_collapsed_to_one_version(self):
        other_image = "sha256:" + "d" * 64
        self.config["expected"][NEW_CID] = other_image
        with mock.patch.object(restart, "confirmed", return_value=[self.container(), self.container(cid=NEW_CID, image=other_image)]):
            with self.assertRaisesRegex(restart.OperationError, "MIXED_IMAGES"): restart.restart(self.config)

    def test_project_rename_fails_before_recreate(self):
        self.model["name"] = "another-project"
        with mock.patch.object(restart, "command", side_effect=self.command):
            with self.assertRaisesRegex(restart.OperationError, "TARGET_CHANGED"): restart.restart(self.config)
        self.assertEqual(self.writes(), [])

    def test_recreate_must_produce_new_ids_and_same_actual_image(self):
        for replacement in (self.container(), self.container(cid=NEW_CID, image="sha256:" + "d" * 64)):
            self.setUp()
            def command(args, **kwargs):
                if args[:2] == ["docker", "inspect"] and self.recreated: return json.dumps([replacement])
                return self.command(args, **kwargs)
            with mock.patch.object(restart, "command", side_effect=command):
                with self.assertRaisesRegex(restart.OperationError, "RECREATE_VERIFY_FAILED"): restart.restart(self.config)
            self.assertEqual(len(self.writes()), 1)

    def test_timeout_does_not_retry_and_private_snapshot_is_removed(self):
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if "up" in args: raise restart.OperationError("TIMEOUT")
            return result
        with mock.patch.object(restart, "command", side_effect=command):
            with self.assertRaisesRegex(restart.OperationError, "TIMEOUT"): restart.restart(self.config)
        self.assertEqual(len(self.writes()), 1)
        self.assertFalse(any(path.exists() for path in self.snapshots))

    def test_direct_docker_run_cannot_silently_fall_back_to_old_restart(self):
        with mock.patch.object(restart, "command") as command:
            with self.assertRaisesRegex(restart.OperationError, "MODE_UNSUPPORTED"):
                restart.restart(dict(self.config, mode="DOCKER_RUN"))
        command.assert_not_called()

    def test_interruption_cleans_private_snapshot_and_never_retries(self):
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if "up" in args: raise restart.OperationError("INTERRUPTED")
            return result
        with mock.patch.object(restart, "command", side_effect=command):
            with self.assertRaisesRegex(restart.OperationError, "INTERRUPTED"): restart.restart(self.config)
        self.assertEqual(len(self.writes()), 1)
        self.assertFalse(any(path.exists() for path in self.snapshots))

    def test_health_binds_to_replacement_ids(self):
        config = dict(self.config, phase="health", expected={NEW_CID: IMAGE})
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            if args[1] == "compose": return NEW_CID
            if args[1] == "inspect": return json.dumps([self.container(cid=NEW_CID)])
            if args[0] == "curl": return ""
            self.fail(args)
        with mock.patch.object(restart, "command", side_effect=command): restart.health(config)
        self.assertIn("http://172.18.0.8:3000/healthz/", calls[-1])
        with mock.patch.object(restart, "confirmed", side_effect=restart.OperationError("TARGET_CHANGED")):
            with self.assertRaisesRegex(restart.OperationError, "TARGET_CHANGED"): restart.health(config)

    def test_permission_failure_only_returns_safe_path_without_raw_error(self):
        failure = mock.Mock(returncode=1, stdout=b"", stderr=b"open /data/env/ui.env: permission denied\nPASSWORD=" + SECRET.encode())
        with mock.patch.object(restart.subprocess, "run", return_value=failure):
            with self.assertRaises(restart.OperationError) as error: restart.command(["docker"], failure="CONFIG_INVALID")
        self.assertEqual(str(error.exception), "CONFIG_PERMISSION")
        self.assertEqual(error.exception.path, "/data/env/ui.env")
        self.assertNotIn(SECRET, str(error.exception))

    def test_error_output_never_contains_raw_docker_or_credential_text(self):
        encoded = base64.b64encode(json.dumps(self.config).encode()).decode()
        output = io.StringIO()
        with mock.patch.object(restart.sys, "argv", ["script", encoded]), mock.patch.object(restart, "restart", side_effect=RuntimeError(SECRET)), contextlib.redirect_stdout(output):
            restart.main()
        self.assertEqual(json.loads(output.getvalue()), {"success": False, "code": "DOCKER_ACCESS"})
        self.assertNotIn(SECRET, output.getvalue())

    def test_display_reference_survives_repeated_refresh_only_when_image_id_matches(self):
        item = self.container()
        item["Config"]["Image"] = IMAGE
        item["Config"]["Labels"].update({restart.IMAGE_REFERENCE_LABEL: "registry/ui:deployed", restart.IMAGE_ID_LABEL: IMAGE})
        self.assertEqual(restart.image_reference(item), "registry/ui:deployed")
        item["Config"]["Labels"][restart.IMAGE_ID_LABEL] = "sha256:" + "d" * 64
        self.assertEqual(restart.image_reference(item), IMAGE)

if __name__ == "__main__": unittest.main()
