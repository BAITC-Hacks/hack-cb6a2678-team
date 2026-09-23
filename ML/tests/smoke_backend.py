"""Optional real HTTP check after `mvn package`; starts and stops both services.

Run from the repository root: python tests/smoke_backend.py
"""
from pathlib import Path
import json
import os
import socket
import subprocess
import sys
import time

import requests

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_ready(url, process):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Service exited with {process.returncode}; see outputs/integration logs")
        try:
            if requests.get(url, timeout=2).status_code == 200:
                return
        except requests.RequestException:
            pass
        time.sleep(.5)
    raise TimeoutError(url)


def main():
    output = ROOT / "outputs/integration"
    output.mkdir(parents=True, exist_ok=True)
    jar = ROOT / "spring-agent/target/wind-agent-1.0.0.jar"
    if not jar.exists():
        raise FileNotFoundError("Build spring-agent with mvn package first")
    ml_port, backend_port = free_port(), free_port()
    ml_url, backend_url = f"http://127.0.0.1:{ml_port}", f"http://127.0.0.1:{backend_port}"
    commands = [
        [sys.executable, "-m", "uvicorn", "windml.api:app", "--host", "127.0.0.1", "--port", str(ml_port)],
        ["java", "-jar", str(jar), f"--server.port={backend_port}",
         f"--windml.base-url={ml_url}", "--spring.ai.anthropic.api-key=local-integration-test-placeholder"],
    ]
    processes, logs = [], []
    try:
        for name, command in zip(("fastapi", "backend"), commands):
            log = (output / f"{name}.log").open("w", encoding="utf-8")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        wait_ready(ml_url + "/health", processes[0])
        wait_ready(backend_url + "/ml/models", processes[1])
        results = []
        for site in ("turbine_1", "turbine_2"):
            response = requests.post(backend_url + "/ml/forecast", timeout=30,
                                     json={"site": site, "issue_date": "2026-01-31"})
            assert response.status_code == 200, response.text
            data = response.json()
            assert data["site"] == site and len(data["hourly"]) == 48
            results.append({"site": site, "status": response.status_code, "hours": len(data["hourly"])})
            (output / f"{site}_response.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        invalid = requests.post(backend_url + "/ml/forecast", timeout=10,
                                json={"site": "unknown", "issue_date": "2026-01-31"})
        assert invalid.status_code == 422, invalid.text
        results.append({"invalid_site_status": invalid.status_code})
        processes[0].terminate()
        processes[0].wait(timeout=10)
        unavailable = requests.get(backend_url + "/ml/models", timeout=10)
        assert unavailable.status_code == 503, unavailable.text
        results.append({"unavailable_ml_status": unavailable.status_code})
        (output / "summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(json.dumps(results, indent=2))
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                if os.name == "nt":
                    # Oracle's java launcher can create a child JVM. Stop the
                    # process tree we started so the child cannot lock the JAR.
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
