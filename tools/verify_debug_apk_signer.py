#!/usr/bin/env python3
"""Verify an installable CI APK against its pinned public signing certificate.

Does not read private keys. A valid but differently signed APK is rejected: it
cannot update an existing installation that trusts the previous certificate.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def verify(apksigner: str, apk: Path, expected: str) -> str:
    fingerprint = expected.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("Expected signer must be a full SHA-256 certificate fingerprint")
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk)],
        capture_output=True, text=True, timeout=60, check=False,
    )
    if result.returncode:
        raise ValueError("APK cryptographic signature verification failed")
    signers = re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        result.stdout, re.MULTILINE,
    )
    if len(signers) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    actual = signers[0].lower()
    if actual != fingerprint:
        raise ValueError(f"APK signer mismatch: expected {fingerprint}, received {actual}")
    return actual


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--expected", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    # A failed recheck must never leave a previous success manifest behind.
    args.evidence.unlink(missing_ok=True)
    try:
        certificate = verify(args.apksigner, args.apk, args.expected)
        with args.apk.open("rb") as apk_file:
            digest = hashlib.file_digest(apk_file, "sha256").hexdigest()
        sha = os.environ.get("GITHUB_SHA", "local-unknown")
        if sha != "local-unknown" and not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
            raise ValueError("Invalid GITHUB_SHA")
        args.evidence.write_text(json.dumps({
            "certificate_sha256": certificate,
            "apk_sha256": digest,
            "build_git_sha": sha,
        }, indent=2) + "\n", encoding="utf-8")
        print(f"APK signer verified: {certificate}; APK SHA-256: {digest}")
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f"APK publication blocked: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
