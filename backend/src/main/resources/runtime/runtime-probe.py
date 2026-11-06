"""Read-only Docker snapshot. Only the final allowlisted JSON crosses SSH."""
import base64
import json
import os
import re
import selectors
import shlex
import signal
import subprocess
import sys
import time

MAX_CONTAINERS = 12
MAX_COMMAND_BYTES = 524288
DEADLINE = None
OPTION = re.compile(r"(?:-Xm[sx]\d+[kKmMgG]?|-Xss\d+[kKmMgG]?|-XX:(?:Initial|Max|Min)RAMPercentage=\d+(?:\.\d+)?|-XX:(?:InitialHeapSize|MaxHeapSize|MinHeapSize|MaxRAM|MaxMetaspaceSize|MaxDirectMemorySize)=\d+[kKmMgG]?|-XX:[+-]Use(?:G1GC|ParallelGC|SerialGC|ZGC|ShenandoahGC|ContainerSupport))\Z")
IMAGE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,511}\Z")
DIGEST = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/+\-]{0,440}@sha256:[a-f0-9]{64}\Z")
CONTAINER_ID = re.compile(r"[a-f0-9]{12,64}\Z")
MESSAGES = {
    "NO_DOCKER": "目标服务器未安装 Docker，或当前账号无法执行 Docker。",
    "DOCKER_ACCESS": "无法读取 Docker，请检查服务器连接账号的 Docker 权限。",
    "COMPOSE_ACCESS": "无法读取现有 Compose 项目，请检查目录、配置文件及 .env 的读取权限。",
    "TIMEOUT": "运行检查超时，请稍后重新检查。",
    "INVALID_DATA": "Docker 返回的数据不完整，未将其判定为正常。",
    "OUTPUT_LIMIT": "检查结果超过大小限制，请缩小该服务的容器规模。",
    "TOO_MANY": "该服务超过 12 个容器，当前检查范围受限。",
    "CHECK_FAILED": "运行信息暂时无法读取。"
}

class ProbeError(Exception):
    pass

def remaining():
    return max(0, DEADLINE - time.monotonic()) if DEADLINE is not None else 25

def command(arguments, cwd=None, timeout=7, error="DOCKER_ACCESS"):
    """Bound both elapsed time and stdout; never return stderr or shell-evaluate input."""
    budget = min(timeout, remaining())
    if budget <= 0.1:
        raise ProbeError("TIMEOUT")
    try:
        process = subprocess.Popen(arguments, cwd=cwd, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    except FileNotFoundError:
        raise ProbeError("COMPOSE_ACCESS" if cwd else "NO_DOCKER") from None
    except OSError:
        raise ProbeError(error) from None
    output = bytearray()
    selector = selectors.DefaultSelector()
    try:
        os.set_blocking(process.stdout.fileno(), False)
        selector.register(process.stdout, selectors.EVENT_READ)
        expires = time.monotonic() + budget
        while selector.get_map():
            if time.monotonic() >= expires:
                raise ProbeError("TIMEOUT")
            for key, _ in selector.select(min(0.1, max(0, expires - time.monotonic()))):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                output.extend(chunk)
                if len(output) > MAX_COMMAND_BYTES:
                    raise ProbeError("OUTPUT_LIMIT")
        try:
            status = process.wait(timeout=max(0.1, expires - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise ProbeError("TIMEOUT") from None
        if status != 0:
            raise ProbeError(error)
        return output.decode("utf-8", errors="replace")
    finally:
        selector.close()
        # Terminate only the process group started for this inspection command (including Compose CLI).
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
        process.stdout.close()

def safe_text(value, pattern=IMAGE):
    return value if isinstance(value, str) and pattern.fullmatch(value) else ""

def nonnegative(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9223372036854775807 else None

def safe_options(tokens):
    result = []
    for token in tokens:
        if isinstance(token, str) and len(token) <= 80 and OPTION.fullmatch(token) and token not in result:
            result.append(token)
            if len(result) >= 24:
                break
    return result

def java_options(container_id, environment, running):
    configured = []
    for item in environment if isinstance(environment, list) else []:
        if not isinstance(item, str) or len(item) > 16384:
            continue
        name, separator, value = item.partition("=")
        if separator and name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            try:
                configured.extend(shlex.split(value))
            except ValueError:
                pass
    configured = safe_options(configured)
    if running and remaining() > 1:
        try:
            processes = command(["docker", "top", container_id, "-eo", "pid,comm"], timeout=2)
            java_pids = []
            for line in processes.splitlines()[1:]:
                parts = line.split()
                if len(parts) == 2 and parts[0].isdigit() and parts[1] == "java":
                    java_pids.append(parts[0])
            # Multiple Java processes have different settings: do not merge them into one JVM.
            if len(java_pids) == 1:
                with open("/proc/" + java_pids[0] + "/cmdline", "rb") as source:
                    raw = source.read(65537)
                if len(raw) <= 65536 and raw:
                    args = raw.decode("utf-8", errors="replace").split("\x00")
                    options = safe_options(configured + args[1:])
                    return options, "Java 进程参数及容器标准 JVM 环境变量（仅启动配置，非有效最大堆）"
            if len(java_pids) > 1:
                return configured, "容器标准 JVM 环境变量（多个 Java 进程，未合并进程参数）"
        except (ProbeError, OSError):
            pass
    if configured:
        return configured, "容器标准 JVM 环境变量（未读取 Java 进程；仅启动配置）"
    return [], "未读取到可展示的 Java 启动配置"

def ports_from(raw):
    result = []
    if not isinstance(raw, dict):
        return result
    for endpoint, bindings in raw.items():
        match = re.fullmatch(r"([0-9]{1,5})/(tcp|udp|sctp)", endpoint)
        if not match or not 1 <= int(match[1]) <= 65535 or not isinstance(bindings, list):
            continue
        for binding in bindings:
            if not isinstance(binding, dict):
                continue
            host = binding.get("HostIp", "")
            port = binding.get("HostPort", "")
            if not isinstance(host, str) or not re.fullmatch(r"[0-9a-fA-F:.]{0,64}", host):
                continue
            if not isinstance(port, str) or not port.isdigit() or not 1 <= int(port) <= 65535:
                continue
            result.append({"hostIp": host, "hostPort": port, "containerPort": match[1], "protocol": match[2]})
            if len(result) >= 48:
                return result
    return result

def memory_bytes(value):
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(B|kB|KB|KiB|MB|MiB|GB|GiB|TB|TiB)\s*", value)
    if not match:
        return None
    units = {"B": 1, "kB": 1000, "KB": 1000, "KiB": 1024, "MB": 1000**2,
             "MiB": 1024**2, "GB": 1000**3, "GiB": 1024**3, "TB": 1000**4, "TiB": 1024**4}
    return nonnegative(int(float(match[1]) * units[match[2]]))

def inspect(config):
    mode = config["mode"]
    if mode == "DOCKER_COMPOSE":
        found = command(["docker", "compose", "-f", config["file"], "ps", "-a", "-q", config["service"]],
                        cwd=config["directory"], error="COMPOSE_ACCESS")
    else:
        found = command(["docker", "ps", "-a", "--no-trunc", "--filter", "name=^/" + re.escape(config["container"]) + "$", "--format", "{{.ID}}"])
    ids = list(dict.fromkeys(line.strip() for line in found.splitlines() if line.strip()))
    if any(not CONTAINER_ID.fullmatch(item) for item in ids):
        raise ProbeError("INVALID_DATA")
    if not ids:
        return {"status": "NOT_FOUND", "message": "未找到该服务的容器。", "containers": []}
    if len(ids) > MAX_CONTAINERS:
        raise ProbeError("TOO_MANY")
    # Full inspect data stays inside this process. Config.Env and process cmdlines never enter the result.
    raw = json.loads(command(["docker", "inspect", "--type", "container"] + ids))
    if not isinstance(raw, list) or len(raw) != len(ids):
        raise ProbeError("INVALID_DATA")
    containers = []
    images = {}
    for item in raw:
        cid = item.get("Id", "")
        if not isinstance(cid, str) or not re.fullmatch(r"[a-f0-9]{64}", cid):
            raise ProbeError("INVALID_DATA")
        state = item.get("State") or {}
        configuration = item.get("Config") or {}
        status = state.get("Status", "unknown")
        if status not in ("created", "running", "paused", "restarting", "removing", "exited", "dead"):
            status = "unknown"
        health = (state.get("Health") or {}).get("Status", "none")
        if health not in ("starting", "healthy", "unhealthy", "none"):
            health = "unknown"
        image_id = safe_text(item.get("Image", ""), re.compile(r"sha256:[a-f0-9]{64}\Z"))
        labels = configuration.get("Labels") or {}
        image_reference = configuration.get("Image", "")
        if labels.get("com.release-platform.image-id") == image_id and image_id:
            image_reference = labels.get("com.release-platform.image-reference") or image_reference
        digests = []
        if image_id and remaining() > 1:
            if image_id not in images:
                try:
                    values = json.loads(command(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", image_id], timeout=2))
                    images[image_id] = [value for value in values[:12] if safe_text(value, DIGEST)] if isinstance(values, list) else []
                except (ProbeError, ValueError):
                    images[image_id] = []
            digests = images[image_id]
        options, source = java_options(cid, configuration.get("Env"), status == "running")
        containers.append({
            "id": cid,
            "name": safe_text(str(item.get("Name", "")).lstrip("/"), re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")),
            "state": status,
            "health": health,
            "imageReference": safe_text(image_reference),
            "imageId": image_id,
            "repoDigests": digests,
            "ports": ports_from((item.get("NetworkSettings") or {}).get("Ports")),
            "memoryLimitBytes": nonnegative((item.get("HostConfig") or {}).get("Memory")),
            "memoryUsageBytes": None,
            "javaOptions": options,
            "javaOptionsSource": source
        })
    running = [item["id"] for item in containers if item["state"] == "running"]
    if running and remaining() > 1:
        try:
            statistics = command(["docker", "stats", "--no-stream", "--format", "{{.ID}}|{{.MemUsage}}"] + running, timeout=4)
            for line in statistics.splitlines():
                cid, separator, usage = line.partition("|")
                if not separator or not CONTAINER_ID.fullmatch(cid):
                    continue
                matches = [item for item in containers if item["id"].startswith(cid)]
                if len(matches) == 1 and matches[0]["state"] == "running":
                    matches[0]["memoryUsageBytes"] = memory_bytes(usage.split("/", 1)[0])
        except ProbeError:
            pass
    return {"status": "OK", "message": "只读检查完成。", "containers": containers}

def main():
    global DEADLINE
    DEADLINE = time.monotonic() + 25
    try:
        config = json.loads(base64.b64decode(sys.argv[1], validate=True))
        result = inspect(config)
    except ProbeError as error:
        result = {"status": "ERROR", "message": MESSAGES.get(str(error), MESSAGES["CHECK_FAILED"]), "containers": []}
    except Exception:
        # Exception text can include command arguments or environment values; never serialize it.
        result = {"status": "ERROR", "message": MESSAGES["INVALID_DATA"], "containers": []}
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))

if __name__ == "__main__":
    main()
