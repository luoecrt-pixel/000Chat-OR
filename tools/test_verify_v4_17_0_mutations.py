#!/usr/bin/env python3
"""Positive/negative controls for surviving OpenRouter/SQLite contracts."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "tools/verify_v4_17_0.py"


def main() -> int:
    cases = [
        ("v18_table_before_upgrade", "app/src/main/java/com/example/openaichat/ChatDatabase.kt",
         "            ensureRequestEventsTable(db)\n            val columns", "            val columns"),
        ("v18_cost_column_guard", "app/src/main/java/com/example/openaichat/ChatDatabase.kt",
         '            if ("cost_usd" !in columns) {', '            if (false) {'),
        ("stream_terminal_break", "app/src/main/java/com/example/openaichat/BoundedResponseDecoder.kt",
         "if (frame != null && consume(frame)) { stopped = true; break }", "if (false) break"),
        ("strict_utf8_decoder", "app/src/main/java/com/example/openaichat/BoundedResponseDecoder.kt",
         "CodingErrorAction.REPORT", "CodingErrorAction.REPLACE"),
        ("separate_android_identity", "app/build.gradle.kts",
         'applicationId = "com.example.openaichat.openrouter"', 'applicationId = "com.example.openaichat"'),
        ("narrow_attachment_provider", "app/src/main/res/xml/file_paths.xml",
         'name="attachments" path="attachments/"', 'name="attachments" path="./"'),
    ]
    with tempfile.TemporaryDirectory(prefix="openrouter-verify-mutations-", dir=ROOT.parent) as tmp:
        copy = Path(tmp) / "repo"
        shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns(".git", ".gradle", "build"))
        def check():
            return subprocess.run([sys.executable, str(VERIFY)], cwd=ROOT,
                                  env=dict(os.environ, ASTRA_VERIFY_ROOT=str(copy)),
                                  text=True, capture_output=True)
        positive = check()
        assert positive.returncode == 0, positive.stderr
        print("PASS positive_baseline")
        for name, relative, original, replacement in cases:
            path = copy / relative
            source = path.read_text()
            assert original in source, f"Mutation does not apply: {name}"
            try:
                path.write_text(source.replace(original, replacement, 1))
                result = check()
                assert result.returncode != 0, f"Verifier accepted {name}"
                assert "Traceback" in result.stderr, result.stderr
                print(f"PASS {name}: rejected")
            finally:
                path.write_text(source)
    print(f"Verifier mutation matrix: PASS ({len(cases)} targeted negative mutations + positive control)")
    subprocess.run([sys.executable, str(ROOT / "tools/test_verify_implementation_mutations.py")], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
