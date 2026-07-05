#!/usr/bin/env python3
import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque


def get_json(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def stream_samples(base_url, state, stop_event, output_path):
    stream_url = base_url.rstrip("/") + "/stream"
    out = open(output_path, "a", encoding="utf-8") if output_path else None

    try:
        while not stop_event.is_set():
            try:
                with urllib.request.urlopen(stream_url, timeout=15) as response:
                    state["stream_error"] = ""
                    for raw_line in response:
                        if stop_event.is_set():
                            return

                        line = raw_line.decode("utf-8", errors="replace").strip()
                        if not line:
                            continue

                        try:
                            sample = json.loads(line)
                        except json.JSONDecodeError:
                            state["stream_error"] = "bad JSON from /stream"
                            continue

                        source = sample.get("source", "unknown")
                        now = time.time()
                        with state["lock"]:
                            state["latest"][source] = sample
                            state["counts"][source] += 1
                            state["times"][source].append(now)

                        if out:
                            out.write(json.dumps(sample, separators=(",", ":")) + "\n")
                            out.flush()
            except urllib.error.URLError as exc:
                state["stream_error"] = f"stream not reachable: {exc}"
                time.sleep(2)
            except TimeoutError:
                state["stream_error"] = "stream timed out"
                time.sleep(2)
    finally:
        if out:
            out.close()


def sample_rate(times):
    now = time.time()
    while times and now - times[0] > 5:
        times.popleft()
    if len(times) < 2:
        return 0.0
    return (len(times) - 1) / max(0.001, times[-1] - times[0])


def short_sample(sample):
    if "values" in sample:
        values = sample["values"]
        if isinstance(values, list):
            shown = ", ".join(format_number(value) for value in values[:6])
            return f"[{shown}] {sample.get('unit', '')}".strip()

    fields = []
    for key in ("lat", "lon", "altitude_m", "speed_mps", "bearing_deg", "accuracy_m", "dbm", "level", "network_type"):
        if key in sample:
            fields.append(f"{key}={format_number(sample[key])}")
    return ", ".join(fields) if fields else json.dumps(sample, separators=(",", ":"))[:120]


def format_number(value):
    if isinstance(value, float):
        val = round(value, 3)
        return f"{val:.4g}"
    return str(value)


def draw_dashboard(base_url, health, sensors, state):
    os.system("cls" if os.name == "nt" else "clear")
    uptime = time.time() - state["started"]

    print("Android Exposed live output")
    print(f"Base URL: {base_url}")
    print(f"Uptime: {uptime:.0f}s")
    print()

    if health:
        print(
            "Server: "
            f"ok={health.get('ok')} "
            f"clients={health.get('stream_client_count')} "
            f"device={health.get('device_model')} "
            f"android={health.get('android_version')}"
        )

    if sensors:
        print(f"Sensors detected: {sensors.get('count', 0)}")

    if state["stream_error"]:
        print(f"Stream error: {state['stream_error']}")

    print()
    print(f"{'source':24} {'count':>8} {'hz':>8} latest")
    print("-" * 80)

    with state["lock"]:
        sources = sorted(state["latest"].keys())
        latest = dict(state["latest"])
        counts = dict(state["counts"])
        times = {source: deque(values) for source, values in state["times"].items()}

    if not sources:
        print("Waiting for samples...")
    else:
        for source in sources:
            hz = sample_rate(times[source])
            print(f"{source[:24]:24} {counts.get(source, 0):8d} {hz:8.1f} {short_sample(latest[source])}")

    print()
    print("Press Ctrl+C to stop.")


def main():
    parser = argparse.ArgumentParser(description="Display Android Exposed output live.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8765")
    parser.add_argument("--output", help="Optional .jsonl file to write all stream samples to.")
    parser.add_argument("--refresh", type=float, default=0.5)
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    state = {
        "started": time.time(),
        "latest": {},
        "counts": defaultdict(int),
        "times": defaultdict(lambda: deque(maxlen=500)),
        "stream_error": "",
        "lock": threading.Lock(),
    }
    stop_event = threading.Event()

    try:
        health = get_json(base_url + "/health")
    except Exception as exc:
        print(f"Could not reach {base_url}/health: {exc}", file=sys.stderr)
        print("Check that the app is open and run: adb forward tcp:8765 tcp:8765", file=sys.stderr)
        return 1

    try:
        sensors = get_json(base_url + "/sensors")
    except Exception:
        sensors = {}

    stream_thread = threading.Thread(
        target=stream_samples,
        args=(base_url, state, stop_event, args.output),
        daemon=True,
    )
    stream_thread.start()

    try:
        while not stop_event.is_set():
            draw_dashboard(base_url, health, sensors, state)
            time.sleep(max(0.1, args.refresh))
    except KeyboardInterrupt:
        stop_event.set()

    stream_thread.join(timeout=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
