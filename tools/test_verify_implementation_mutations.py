"""Positive/negative controls for the offline verifier; no Android or network calls."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def check(root):
    env = dict(os.environ, ASTRA_VERIFY_ROOT=str(root))
    return subprocess.run([sys.executable, str(ROOT / "tools/verify_implementation.py")],
                          env=env, capture_output=True, text=True)


def main():
    positive = check(ROOT)
    assert positive.returncode == 0, positive.stderr
    print("PASS implementation_positive_control")
    with tempfile.TemporaryDirectory(prefix="astra-implementation-check-", dir=ROOT.parent) as temp:
        copy = Path(temp) / "repo"
        shutil.copytree(ROOT / "app/src", copy / "app/src")
        shutil.copytree(ROOT / ".github/workflows", copy / ".github/workflows")
        cases = [
            ("android_json_null_becomes_text", "app/src/main/java/com/example/openaichat/MainActivity.kt",
             lambda text: text.replace('JsonProtocol.string(message, "text")', 'message.optString("text")', 1)),
            ("unclosed_kotlin", "app/src/main/java/com/example/openaichat/JsonProtocol.kt",
             lambda text: text + "\n{\n"),
            ("checkpoint_fk_cleanup", "app/src/main/java/com/example/openaichat/ChatDatabase.kt",
             lambda text: text.replace("PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE",
                                       "PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE RESTRICT", 1)),
            ("destructive_upgrade", "app/src/main/java/com/example/openaichat/ChatDatabase.kt",
             lambda text: text.replace('ADD COLUMN finish_reason TEXT', 'RENAME COLUMN text TO finish_reason', 1)),
            ("text_slices_lose_nul_or_unicode", "app/src/main/java/com/example/openaichat/SqliteLargeTextQuery.kt",
             lambda text: text.replace("substr(CAST($field AS BLOB),?,?)", "substr($field,?,?)", 1)),
            ("missing_saved_scroll_anchors", "app/src/main/java/com/example/openaichat/MainActivity.kt",
             lambda text: text.replace('outState.putString("per_chat_scroll_anchors_v1", ChatScrollPolicy.encodeAnchorsJson(perChatScrollAnchors, currentChatId()))', '// dropped saved anchors', 1)),
            ("truncated_search_left_context_overlap", "app/src/main/java/com/example/openaichat/LocalFileTools.kt",
             lambda text: text.replace('val carryWindow = SEARCH_LEFT_CONTEXT + query.length - 1', 'val carryWindow = maxOf(query.length - 1, 100)', 1)),
            ("database_closed_before_callbacks_finish", "app/src/main/java/com/example/openaichat/MainActivity.kt",
             lambda text: text.replace('ExecutorResourceCloser.closeAfterTermination', 'UnsafeImmediateDatabaseClose.close', 1)),
            ("direct_openai_endpoint", "app/src/main/java/com/example/openaichat/MainActivity.kt",
             lambda text: text + '\nprivate const val RETIRED_ENDPOINT = "https://api.openai.com/v1/responses"\n'),
            ("non_claude_model_in_catalog", "app/src/main/java/com/example/openaichat/ModelCatalog.kt",
             lambda text: text.replace('ModelOption("anthropic/', 'ModelOption("other/', 1)),
            ("legacy_protocol_metadata_removed", "app/src/main/java/com/example/openaichat/MessageMetadataCodec.kt",
             lambda text: text.replace('"openai_protocol_output"', '"discarded_protocol"')),
        ]
        for name, relative, mutate in cases:
            path = copy / relative
            original = path.read_text()
            changed = mutate(original)
            assert changed != original, f"Mutation did not apply: {name}"
            try:
                path.write_text(changed)
                result = check(copy)
                assert result.returncode != 0, f"Verifier accepted {name}"
                assert "Traceback" in result.stderr, f"Unexpected failure: {result.stderr}"
                print(f"PASS {name}: rejected")
            finally:
                path.write_text(original)
    print(f"Implementation verifier mutation checks: PASS ({len(cases)} negative mutations + positive control)")


if __name__ == "__main__":
    main()
