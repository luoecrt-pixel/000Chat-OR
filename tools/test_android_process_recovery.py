#!/usr/bin/env python3
"""Kill a real OpenRouter tool/SSE process, then verify local restart recovery.

Runs only on a disposable emulator. Synthetic credentials and trusted localhost
HTTPS seed a completed tool round and an open paid-response prefix. The new
process must preserve the checkpoint, Stop/replacement ownership and original
result file without creating a new inference round. The intent-gap scenario
forces native SQLite registration failure after publication, then verifies local
SQL/protocol reconciliation and a separate explicit synthetic next-turn replay.
The deleted-intent scenario verifies a late publication cannot revive its chat.
A persisted synthetic key
remains available during both restarts; a pre-create localhost transport
tripwire verifies that recovery sends no additional HTTP.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

PACKAGE = "com.example.openaichat.openrouter"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
FIXTURE = "com.example.openaichat.ProcessDeathRecoveryFixture"
SCENARIOS = ("partial", "stopped", "replaced", "intent_gap", "deleted_intent")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instrumentation-first", action="store_true",
                        help="Run connected Android tests first; still collect host recovery evidence if they fail")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path,
                        default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    parser.add_argument("--reports", type=Path, default=Path("app/build/reports/process-recovery"))
    args = parser.parse_args()
    if not args.serial:
        devices = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True).stdout
        matches = re.findall(r"^(emulator-\d+)\s+device$", devices, flags=re.MULTILINE)
        if len(matches) != 1:
            raise SystemExit("Select exactly one disposable emulator with --serial")
        args.serial = matches[0]
    adb = ["adb", "-s", args.serial]

    def command(*words: str, timeout: int = 30) -> str:
        result = subprocess.run(adb + list(words), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"adb command failed: {' '.join(words)}\n{result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    if command("shell", "getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("Refusing process-kill tests on a physical device")
    instrumentation_status = 0
    if args.instrumentation_first:
        print("Running connected Android instrumentation before host process recovery", flush=True)
        instrumentation_status = subprocess.run(
            ["gradle", "--no-daemon", "--stacktrace", "connectedDebugAndroidTest"], check=False).returncode
        print(f"Android instrumentation exit status: {instrumentation_status}; collecting host recovery evidence", flush=True)
    # connectedDebugAndroidTest uninstalls its APKs on completion. Install the
    # exact current build for this separate host-driven run, retaining app data.
    for apk in (args.app_apk, args.test_apk):
        if not apk.is_file():
            raise SystemExit(f"Required Debug test build is missing: {apk}")
    for apk in (args.app_apk, args.test_apk):
        command("install", "-r", str(apk.resolve()), timeout=60)
    installed = command("shell", "pm", "list", "instrumentation")
    if RUNNER not in installed:
        raise SystemExit("Debug AndroidTest APK is not installed")
    args.reports.mkdir(parents=True, exist_ok=True)
    summary: list[dict] = []

    def run_scenario(scenario: str) -> None:
        seed_args = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                     FIXTURE + "#seedAndWaitForHostProcessKill", "-e", "recoveryStage", "seed",
                     "-e", "recoveryScenario", scenario, RUNNER]
        seed = subprocess.Popen(adb + seed_args, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)
        output: queue.Queue[str | None] = queue.Queue()
        lines: list[str] = []

        def read_seed() -> None:
            assert seed.stdout is not None
            for line in seed.stdout:
                output.put(line)
            output.put(None)

        reader = threading.Thread(target=read_seed, daemon=True)
        reader.start()
        marker = re.compile(r"PROCESS_RECOVERY_SEEDED scenario=" + re.escape(scenario) + r" pid=(\d+)")
        deadline = time.monotonic() + 90
        seed_pid = None
        try:
            while time.monotonic() < deadline:
                try:
                    line = output.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    raise RuntimeError("Seed exited before the process-kill checkpoint\n" + "".join(lines))
                lines.append(line)
                match = marker.search(line)
                if match:
                    seed_pid = int(match.group(1))
                    break
            if seed_pid is None:
                raise RuntimeError("Seed did not reach its durable checkpoint")
            pids = command("shell", "pidof", PACKAGE).split()
            if str(seed_pid) not in pids:
                raise RuntimeError("Seed process already died; this is not a process-kill proof")
            command("shell", "am", "force-stop", PACKAGE)
            seed.wait(timeout=20)
            reader.join(timeout=5)
            while not output.empty():
                line = output.get_nowait()
                if line is not None:
                    lines.append(line)
            # pidof returns exit 1 when absent, so inspect it without check=True.
            pids_after = subprocess.run(adb + ["shell", "pidof", PACKAGE],
                                        capture_output=True, text=True, timeout=15).stdout.split()
            if str(seed_pid) in pids_after:
                raise RuntimeError("force-stop did not kill the seeded process")
        finally:
            if seed.poll() is None:
                command("shell", "am", "force-stop", PACKAGE)
                seed.terminate()
                seed.wait(timeout=15)
            (args.reports / f"{scenario}-seed.log").write_text("".join(lines), encoding="utf-8")

        verification = command("shell", "am", "instrument", "-w", "-r", "-e", "class",
                               FIXTURE + "#verifyLocalRecoveryInNewProcess",
                               "-e", "recoveryStage", "verify", "-e", "recoveryScenario", scenario,
                               RUNNER, timeout=150)
        (args.reports / f"{scenario}-verify.log").write_text(verification + "\n", encoding="utf-8")
        if ("OK (1 test)" not in verification or "FAILURES!!!" in verification or
                "INSTRUMENTATION_FAILED" in verification):
            raise RuntimeError(f"Recovery verification failed: {scenario}\n{verification}")
        summary.append({"scenario": scenario, "seed_pid": seed_pid,
                        "live_process_killed": True, "production_openrouter_recovery": "passed",
                        "automatic_restart_http_posts": 0,
                        "explicit_synthetic_next_turn_posts": 1 if scenario == "intent_gap" else 0,
                        "published_target_bytes_and_mtime_preserved": True,
                        "fixture_transport": "trusted_local_https" if scenario != "deleted_intent" else "local_sql_and_files"})
        (args.reports / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"Process recovery: {scenario} PASS (live PID {seed_pid} killed; new-process local OpenRouter recovery)", flush=True)

    for scenario in SCENARIOS:
        try:
            run_scenario(scenario)
        finally:
            # A killed seed cannot run @After/finally. Restore durable fixture
            # preferences and the synthetic credential even if verification
            # timed out, failed, or never started. No app data is cleared.
            failed = sys.exc_info()[0] is not None
            try:
                command("shell", "am", "force-stop", PACKAGE)
                cleanup = command("shell", "am", "instrument", "-w", "-r", "-e", "class",
                                  FIXTURE + "#cleanupProcessRecoveryFixture", "-e", "recoveryStage", "cleanup",
                                  "-e", "recoveryScenario", scenario, RUNNER, timeout=90)
                (args.reports / f"{scenario}-cleanup.log").write_text(cleanup + "\n", encoding="utf-8")
                if ("OK (1 test)" not in cleanup or "FAILURES!!!" in cleanup or
                        "INSTRUMENTATION_FAILED" in cleanup):
                    raise RuntimeError(f"Recovery fixture cleanup failed: {scenario}\n{cleanup}")
            except Exception as cleanup_error:
                if not failed:
                    raise
                print(f"Process recovery cleanup also failed: {scenario}: {cleanup_error}", file=sys.stderr)

    # A green recovery pass must never mask a failed connected suite. Recovery
    # exceptions above also fail the job; both kinds of evidence are uploaded.
    if instrumentation_status:
        raise SystemExit(instrumentation_status)


if __name__ == "__main__":
    main()
