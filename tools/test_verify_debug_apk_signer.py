"""Publication-boundary controls; real APK crypto checks run with Android apksigner."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import verify_debug_apk_signer as guard


PIN = "01" * 32
OTHER_PIN = "ab" * 32
CERT = f"Signer #1 certificate SHA-256 digest: {PIN}\n"


class DebugApkSigningBoundaryTest(unittest.TestCase):
    def verifier(self, stdout=CERT, returncode=0):
        return patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess(
            [], returncode, stdout, "",
        ))

    def test_matching_cert_uses_cryptographic_verify_without_a_shell(self):
        with self.verifier() as run:
            self.assertEqual(guard.verify("apksigner", Path("example.apk"), PIN), PIN)
        self.assertEqual(run.call_args.args[0], [
            "apksigner", "verify", "--print-certs", "example.apk",
        ])
        self.assertNotIn("shell", run.call_args.kwargs)

    def test_valid_but_differently_signed_apk_is_rejected(self):
        with self.verifier(), self.assertRaisesRegex(ValueError, "signer mismatch"):
            guard.verify("apksigner", Path("example.apk"), OTHER_PIN)

    def test_failed_crypto_verification_cannot_be_overridden_by_matching_output(self):
        with self.verifier(returncode=1), self.assertRaisesRegex(ValueError, "cryptographic"):
            guard.verify("apksigner", Path("example.apk"), PIN)

    def test_missing_or_multiple_signers_are_rejected(self):
        for output in ("", CERT + CERT.replace("#1", "#2")):
            with self.subTest(output=output), self.verifier(output):
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    guard.verify("apksigner", Path("example.apk"), PIN)

    def test_missing_or_truncated_pin_never_invokes_apksigner(self):
        with self.verifier() as run:
            for pin in ("", PIN[:-1], "sha256:" + PIN):
                with self.subTest(pin=pin), self.assertRaises(ValueError):
                    guard.verify("apksigner", Path("example.apk"), pin)
            run.assert_not_called()

    def test_colon_formatted_public_fingerprint_is_accepted(self):
        formatted = ":".join(PIN[i:i+2].upper() for i in range(0, 64, 2))
        with self.verifier():
            self.assertEqual(guard.verify("apksigner", Path("example.apk"), formatted), PIN)

    def test_timeout_removes_stale_publication_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "signing-identity.json"
            evidence.write_text('previous success')
            argv = ["guard", "--apksigner", "apksigner", "--apk", "example.apk",
                    "--expected", PIN, "--evidence", str(evidence)]
            with patch.object(sys, "argv", argv), contextlib.redirect_stderr(io.StringIO()):
                with patch.object(guard.subprocess, "run", side_effect=subprocess.TimeoutExpired("apksigner", 60)):
                    self.assertEqual(guard.main(), 1)
            self.assertFalse(evidence.exists())

    def test_success_manifest_identifies_exact_bytes_and_public_signer(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "example.apk"
            apk.write_bytes(b"abc")
            evidence = Path(directory) / "signing-identity.json"
            argv = ["guard", "--apksigner", "apksigner", "--apk", str(apk),
                    "--expected", PIN, "--evidence", str(evidence)]
            with patch.object(sys, "argv", argv), self.verifier():
                with patch.dict(guard.os.environ, {"GITHUB_SHA": "a" * 40}):
                    with contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(guard.main(), 0)
            self.assertEqual(json.loads(evidence.read_text()), {
                "certificate_sha256": PIN,
                "apk_sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "build_git_sha": "a" * 40,
            })


if __name__ == "__main__":
    unittest.main()
