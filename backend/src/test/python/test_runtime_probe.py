import contextlib
import importlib.util
import io
import json
import pathlib
import unittest
from unittest import mock

script = pathlib.Path(__file__).resolve().parents[2] / "main/resources/runtime/runtime-probe.py"
spec = importlib.util.spec_from_file_location("runtime_probe", script)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
CID = "a" * 64
IMAGE = "sha256:" + "b" * 64
SECRET = "PRIVATE-CREDENTIAL-DO-NOT-RETURN"

class ProbeTest(unittest.TestCase):
    def setUp(self):
        probe.DEADLINE = None

    def container(self):
        return {"Id": CID, "Name": "/example-ui", "Image": IMAGE,
                "State": {"Status": "running", "Health": {"Status": "healthy", "Log": [{"Output": SECRET}]}},
                "Config": {"Image": "registry.example/example-ui:main-b63", "Env": ["DB_PASSWORD=" + SECRET,
                    "JAVA_TOOL_OPTIONS=-Xms256m -Xmx1024m -XX:MaxRAMPercentage=75 -Dpassword=" + SECRET]},
                "HostConfig": {"Memory": 0},
                "NetworkSettings": {"Ports": {"80/tcp": [{"HostIp": "0.0.0.0", "HostPort": "80"}]}}}

    def test_snapshot_contains_only_safe_fields_and_approximate_usage(self):
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            if args[1] == "ps": return CID + "\n"
            if args[1] == "inspect": return json.dumps([self.container()])
            if args[1] == "image": return json.dumps(["registry.example/example-ui@sha256:" + "c" * 64])
            if args[1] == "top": raise probe.ProbeError("DOCKER_ACCESS")
            if args[1] == "stats": return CID[:12] + "|32.5MiB / 3.8GiB\n"
            self.fail(args)
        with mock.patch.object(probe, "command", side_effect=command):
            result = probe.inspect({"mode": "DOCKER_RUN", "container": "example.ui"})
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertEqual(result["status"], "OK")
        container = result["containers"][0]
        self.assertEqual(container["memoryLimitBytes"], 0)
        self.assertEqual(container["memoryUsageBytes"], int(32.5 * 1024**2))
        self.assertEqual(container["javaOptions"], ["-Xms256m", "-Xmx1024m", "-XX:MaxRAMPercentage=75"])
        self.assertIn("未读取 Java 进程", container["javaOptionsSource"])
        self.assertIn("name=^/example\\.ui$", calls[0])
        self.assertEqual({args[1] for args in calls}, {"ps", "inspect", "image", "top", "stats"})

    def test_compose_failure_is_not_misreported_as_container_absence(self):
        with mock.patch.object(probe, "command", side_effect=probe.ProbeError("COMPOSE_ACCESS")) as command:
            with self.assertRaisesRegex(probe.ProbeError, "COMPOSE_ACCESS"):
                probe.inspect({"mode": "DOCKER_COMPOSE", "directory": "/data/project'quoted", "file": "compose.yml", "service": "example-ui"})
            command.assert_called_once_with(["docker", "compose", "-f", "compose.yml", "ps", "-a", "-q", "example-ui"], cwd="/data/project'quoted", error="COMPOSE_ACCESS")
        with mock.patch.object(probe, "command", return_value=""):
            result = probe.inspect({"mode": "DOCKER_RUN", "container": "example-ui"})
            self.assertEqual(result["status"], "NOT_FOUND")

    def test_recreated_service_keeps_display_version_only_for_matching_image_id(self):
        for label_id, expected in [(IMAGE, "registry.example/ui:deployed"), ("sha256:" + "d" * 64, IMAGE)]:
            item = self.container()
            item["Config"]["Image"] = IMAGE
            item["Config"]["Labels"] = {"com.release-platform.image-reference": "registry.example/ui:deployed", "com.release-platform.image-id": label_id}
            def command(args, **kwargs):
                if args[1] == "ps": return CID
                if args[1] == "inspect": return json.dumps([item])
                if args[1] == "image": return "[]"
                raise probe.ProbeError("DOCKER_ACCESS")
            with mock.patch.object(probe, "command", side_effect=command):
                result = probe.inspect({"mode": "DOCKER_RUN", "container": "ui"})
            self.assertEqual(result["containers"][0]["imageReference"], expected)

    def test_real_java_args_are_filtered_and_do_not_claim_effective_heap(self):
        with mock.patch.object(probe, "command", return_value="PID COMMAND\n123 java\n"), mock.patch("builtins.open", return_value=io.BytesIO(("java\0-Xmx1536m\0-Dtoken=" + SECRET + "\0-jar\0/app.jar\0").encode())):
            options, source = probe.java_options(CID, ["JAVA_TOOL_OPTIONS=-Xms256m -Dsecret=" + SECRET], True)
        self.assertEqual(options, ["-Xms256m", "-Xmx1536m"])
        self.assertIn("非有效最大堆", source)
        self.assertNotIn(SECRET, str((options, source)))

    def test_multiple_jvms_are_not_merged_into_one_fake_heap_setting(self):
        with mock.patch.object(probe, "command", return_value="PID COMMAND\n123 java\n124 java\n"), mock.patch("builtins.open") as source:
            options, label = probe.java_options(CID, ["JAVA_TOOL_OPTIONS=-Xmx512m"], True)
        source.assert_not_called()
        self.assertEqual(options, ["-Xmx512m"])
        self.assertIn("多个 Java 进程", label)

    def test_untrusted_error_message_is_never_serialized(self):
        buffer = io.StringIO()
        with mock.patch.object(probe.sys, "argv", ["probe", "e30="]), mock.patch.object(probe, "inspect", side_effect=Exception(SECRET)), contextlib.redirect_stdout(buffer):
            probe.main()
        self.assertNotIn(SECRET, buffer.getvalue())
        self.assertEqual(json.loads(buffer.getvalue())["status"], "ERROR")

    def test_container_count_and_identifiers_fail_closed(self):
        for output, error in [("not-a-container\n", "INVALID_DATA"), ("\n".join(format(i, "064x") for i in range(13)), "TOO_MANY")]:
            with self.subTest(error=error), mock.patch.object(probe, "command", return_value=output) as command:
                with self.assertRaisesRegex(probe.ProbeError, error):
                    probe.inspect({"mode": "DOCKER_RUN", "container": "example-ui"})
                command.assert_called_once()

    def test_invalid_ports_and_negative_memory_are_not_returned(self):
        self.assertEqual(probe.ports_from({"99999/tcp": [{"HostIp": "0.0.0.0", "HostPort": "80"}], "80/tcp": [{"HostIp": SECRET, "HostPort": "80"}]}), [])
        self.assertIsNone(probe.memory_bytes("-1MiB"))
        self.assertIsNone(probe.nonnegative(-1))
        self.assertIsNone(probe.nonnegative(True))
        self.assertEqual(probe.ports_from({"8091/tcp": None}), [])

if __name__ == "__main__":
    unittest.main()
