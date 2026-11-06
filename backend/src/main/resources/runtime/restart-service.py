"""Reload one Compose service with its current image. Resolved secrets stay local."""
import base64
import json
import os
import pathlib
import re
import signal
import subprocess
import sys
import tempfile
import time

class OperationError(Exception):
    def __init__(self, code, path=None):
        super().__init__(code)
        self.path = path

IMAGE_REFERENCE_LABEL = "com.release-platform.image-reference"
IMAGE_ID_LABEL = "com.release-platform.image-id"

def command(args, cwd=None, timeout=8, failure="DOCKER_ACCESS"):
    budget = min(timeout, max(0, DEADLINE - time.monotonic()))
    if budget <= 0:
        raise OperationError("TIMEOUT")
    try:
        result = subprocess.run(args, cwd=cwd, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=budget, check=False)
    except subprocess.TimeoutExpired:
        raise OperationError("TIMEOUT") from None
    except OSError:
        raise OperationError(failure) from None
    if result.returncode != 0 and failure == "CONFIG_INVALID":
        error = result.stderr.decode("utf-8", errors="replace")
        if "permission denied" in error.lower():
            match = re.search(r"open (/[^\r\n]+): permission denied", error, re.IGNORECASE)
            path = match.group(1) if match else None
            if path and (len(path) > 500 or not re.fullmatch(r"/[A-Za-z0-9_./ -]+", path)):
                path = None
            raise OperationError("CONFIG_PERMISSION", path)
        raise OperationError("CONFIG_INVALID")
    if result.returncode != 0 or len(result.stdout) > 2097152:
        raise OperationError(failure)
    return result.stdout.decode("utf-8", errors="strict")

def current_ids(config):
    if config["mode"] == "DOCKER_COMPOSE":
        value = command(["docker", "compose", "-f", config["file"], "ps", "-a", "-q", config["service"]],
                        cwd=config["directory"], failure="COMPOSE_ACCESS")
    else:
        value = command(["docker", "ps", "-a", "--no-trunc", "--filter",
                         "name=^/" + re.escape(config["container"]) + "$", "--format", "{{.ID}}"])
    ids = list(dict.fromkeys(value.split()))
    if not ids:
        raise OperationError("NO_CONTAINER")
    if len(ids) > 12 or any(not re.fullmatch(r"[a-f0-9]{12,64}", cid) for cid in ids):
        raise OperationError("TARGET_CHANGED")
    return ids

def inspect_ids(ids):
    raw = json.loads(command(["docker", "inspect", "--type", "container"] + ids))
    if not isinstance(raw, list):
        raise OperationError("TARGET_CHANGED")
    return raw

def confirmed(config):
    raw = inspect_ids(current_ids(config))
    actual = {item.get("Id"): item.get("Image") for item in raw}
    if actual != config["expected"] or len(raw) != len(actual):
        raise OperationError("TARGET_CHANGED")
    return raw

def image_reference(item):
    configuration = item.get("Config") or {}
    labels = configuration.get("Labels") or {}
    if labels.get(IMAGE_ID_LABEL) == item.get("Image") and labels.get(IMAGE_REFERENCE_LABEL):
        return labels[IMAGE_REFERENCE_LABEL]
    return configuration.get("Image", item.get("Image", ""))

def replacement_ids(project, service):
    value = command(["docker", "ps", "-a", "--no-trunc", "--filter",
                     "label=com.docker.compose.project=" + project, "--filter",
                     "label=com.docker.compose.service=" + service, "--filter",
                     "label=com.docker.compose.oneoff=False", "--format", "{{.ID}}"])
    ids = list(dict.fromkeys(value.split()))
    if not 1 <= len(ids) <= 12 or any(not re.fullmatch(r"[a-f0-9]{64}", cid) for cid in ids):
        raise OperationError("RECREATE_VERIFY_FAILED")
    return ids

def restart(config):
    if config["mode"] != "DOCKER_COMPOSE":
        raise OperationError("MODE_UNSUPPORTED")
    raw = confirmed(config)
    if any((item.get("State") or {}).get("Status") not in ("running", "exited", "created") for item in raw):
        raise OperationError("STATE_UNSUPPORTED")
    images = set(config["expected"].values())
    if len(images) != 1:
        raise OperationError("MIXED_IMAGES")
    image_id = next(iter(images))
    project = None
    for item in raw:
        labels = (item.get("Config") or {}).get("Labels") or {}
        current_project = labels.get("com.docker.compose.project", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,127}", current_project) or labels.get("com.docker.compose.service") != config["service"]:
            raise OperationError("TARGET_CHANGED")
        if project is not None and project != current_project:
            raise OperationError("TARGET_CHANGED")
        project = current_project
    # Resolve env_file and .env before any disruption. Private snapshot prevents
    # subsequent source edits from changing the already-validated operation.
    model = json.loads(command(["docker", "compose", "-f", config["file"], "config", "--format", "json"],
                               cwd=config["directory"], timeout=25, failure="CONFIG_INVALID"))
    if model.get("name") != project or config["service"] not in model.get("services", {}):
        raise OperationError("TARGET_CHANGED")
    selected = model["services"][config["service"]]
    if selected.get("scale", 1) == 0 or (selected.get("deploy") or {}).get("replicas", 1) == 0:
        raise OperationError("CONFIG_INVALID")
    selected["image"] = image_id
    selected["pull_policy"] = "never"
    selected.pop("build", None)
    selected.setdefault("labels", {}).update({IMAGE_REFERENCE_LABEL: image_reference(raw[0]), IMAGE_ID_LABEL: image_id})
    if command(["docker", "image", "inspect", "--format", "{{.Id}}", image_id]).strip() != image_id:
        raise OperationError("TARGET_CHANGED")
    with tempfile.TemporaryDirectory(prefix="release-config-restart-") as directory:
        snapshot = pathlib.Path(directory) / "compose.json"
        with os.fdopen(os.open(snapshot, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as output:
            # `compose config` emits an already escaped, round-trippable model.
            # Escaping $ again would corrupt passwords and shell expressions.
            json.dump(model, output)
        compose = ["docker", "compose", "--project-name", project, "--project-directory", config["directory"], "-f", str(snapshot)]
        command(compose + ["config", "--quiet"], timeout=20, failure="CONFIG_INVALID")
        confirmed(config)
        command(compose + ["up", "-d", "--no-deps", "--force-recreate", "--no-build", "--pull", "never",
                           "--scale", config["service"] + "=" + str(len(raw)), config["service"]],
                timeout=120, failure="RESTART_FAILED")
        replacements = inspect_ids(replacement_ids(project, config["service"]))
        expected = {item.get("Id"): item.get("Image") for item in replacements}
        if (len(expected) != len(raw) or len(replacements) != len(raw)
                or set(expected) & set(config["expected"]) or set(expected.values()) != {image_id}):
            raise OperationError("RECREATE_VERIFY_FAILED")
        return expected

def http_ready(item, config):
    if (item.get("HostConfig") or {}).get("NetworkMode") == "host":
        addresses = ["127.0.0.1"]
    else:
        networks = (item.get("NetworkSettings") or {}).get("Networks") or {}
        addresses = [network.get("IPAddress") for network in networks.values() if network.get("IPAddress")]
    for address in addresses:
        if not re.fullmatch(r"[0-9a-fA-F:.]{1,64}", address):
            continue
        host = "[" + address + "]" if ":" in address else address
        url = "http://" + host + ":" + str(config["port"]) + config["path"]
        try:
            command(["curl", "--noproxy", "*", "-fsS", "-o", "/dev/null", "--connect-timeout", "2", "--max-time", "4", url], timeout=5, failure="HEALTH_FAILED")
            return True
        except OperationError as error:
            if str(error) == "TIMEOUT":
                raise
    return False

def health(config):
    while time.monotonic() < DEADLINE - 1:
        items = confirmed(config)
        ready = True
        for item in items:
            state = item.get("State") or {}
            docker_health = (state.get("Health") or {}).get("Status")
            if not state.get("Running") or docker_health not in (None, "healthy") or not http_ready(item, config):
                ready = False
                break
        if ready:
            return
        time.sleep(min(3, max(0, DEADLINE - time.monotonic())))
    raise OperationError("HEALTH_FAILED")

def main():
    global DEADLINE
    try:
        config = json.loads(base64.b64decode(sys.argv[1], validate=True))
        expected = config.get("expected")
        if not isinstance(expected, dict) or not 1 <= len(expected) <= 12 or any(
            not re.fullmatch(r"[a-f0-9]{64}", cid) or not re.fullmatch(r"sha256:[a-f0-9]{64}", image)
            for cid, image in expected.items()
        ):
            raise OperationError("TARGET_CHANGED")
        if config["mode"] not in ("DOCKER_COMPOSE", "DOCKER_RUN") or config["phase"] not in ("restart", "health"):
            raise OperationError("TARGET_CHANGED")
        DEADLINE = time.monotonic() + (180 if config["phase"] == "restart" else 90)
        if config["phase"] == "restart":
            result = {"success": True, "containers": restart(config)}
        else:
            health(config)
            result = {"success": True}
    except OperationError as error:
        result = {"success": False, "code": str(error)}
        if error.path:
            result["path"] = error.path
    except Exception:
        result = {"success": False, "code": "DOCKER_ACCESS"}
    print(json.dumps(result, separators=(",", ":")))

if __name__ == "__main__":
    def interrupted(signum, frame):
        raise OperationError("INTERRUPTED")
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, interrupted)
    main()
