"""Offline lexical/schema checks. This is not Kotlin compilation or an Android test."""
import os
import codecs
import re
import sqlite3
from pathlib import Path

ROOT = Path(os.environ.get("ASTRA_VERIFY_ROOT", Path(__file__).resolve().parents[1]))


def kotlin_delimiters(source):
    def comment(i):
        depth = 1
        while i < len(source):
            if source.startswith("/*", i): depth += 1; i += 2
            elif source.startswith("*/", i):
                depth -= 1; i += 2
                if depth == 0: return i
            else: i += 1
        raise AssertionError("Unclosed block comment")

    def string(i, quote, raw=False):
        while i < len(source):
            if raw and source.startswith('"""', i): return i + 3
            if not raw and source[i] == quote: return i + 1
            if not raw and source[i] == "\\": i += 2; continue
            if quote == '"' and source.startswith("${", i): i = code(i + 2, True); continue
            i += 1
        raise AssertionError("Unclosed string")

    def code(i, template=False):
        stack = []
        while i < len(source):
            if source.startswith("//", i):
                end = source.find("\n", i); i = len(source) if end < 0 else end + 1; continue
            if source.startswith("/*", i): i = comment(i + 2); continue
            if source.startswith('"""', i): i = string(i + 3, '"', True); continue
            c = source[i]
            if c in "\"'`": i = string(i + 1, c); continue
            if c in "([{": stack.append(c)
            elif c in ")]}":
                if c == "}" and template and not stack: return i + 1
                assert stack and stack.pop() == {")": "(", "]": "[", "}": "{"}[c], f"Delimiter mismatch near line {source.count(chr(10), 0, i)+1}"
            i += 1
        assert not stack and not template, "Unclosed delimiter/template"
        return i
    code(0)


files = list((ROOT / "app/src").rglob("*.kt"))
for path in files:
    try: kotlin_delimiters(path.read_text())
    except AssertionError as error: raise AssertionError(f"{path.relative_to(ROOT)}: {error}") from error

nullable_wire_fields = r'(?:text|path|image_path|image_name|image_file_id|finish_reason|content|arguments|signature)'
for path in (ROOT / 'app/src/main/java/com/example/openaichat').glob('*.kt'):
    assert not re.search(r'\.optString\s*\(\s*"' + nullable_wire_fields + r'"', path.read_text()), \
        f'{path.name}: nullable protocol/user strings must use JsonProtocol, not Android optString'

db = (ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt").read_text()
constants = dict(re.findall(r'private const val ([A-Z_]+) = "([^"\n]+)"', db))


def expand(statement):
    for key, value in constants.items(): statement = statement.replace("$" + key, value)
    return statement


schemas = [expand(text) for text in re.findall(r'db.execSQL\("""\s*(CREATE TABLE.*?)"""', db, re.S)]
con = sqlite3.connect(":memory:")
con.execute("PRAGMA foreign_keys=ON")
for sql in schemas:
    # Definitions in onCreate/onOpen share the same table. Verify each statement's syntax.
    con.execute(re.sub(r'CREATE TABLE (?!IF NOT EXISTS)', 'CREATE TABLE IF NOT EXISTS ', sql, count=1))
columns = {row[1] for row in con.execute("PRAGMA table_info(messages)")}
assert {"finish_reason", "openrouter_protocol", "protocol_context_state"} <= columns
con.execute("INSERT INTO chats(id,title,updated_at) VALUES('c','title',0)")
con.execute("INSERT INTO local_artifacts(path,chat_id,request_id,created_at,metadata) VALUES('p','c','r',0,'{}')")
con.execute("INSERT INTO openai_tool_checkpoints(chat_id,request_id,request_checkpoint) VALUES('c','r','{}')")
con.execute("INSERT INTO openrouter_checkpoints(chat_id,request_id,protocol) VALUES('c','r','{}')")
con.execute("INSERT INTO answer_notifications(chat_id,request_id,completed,created_at) VALUES('c','r',1,0)")
con.execute("DELETE FROM chats WHERE id='c'")
assert con.execute("SELECT COUNT(*) FROM local_artifacts").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM openai_tool_checkpoints").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM openrouter_checkpoints").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM answer_notifications").fetchone()[0] == 0

# Execute the actual additive v20->v21 column statements on a legacy row.
legacy = sqlite3.connect(":memory:")
legacy.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, text TEXT, attachments TEXT)")
legacy.execute("INSERT INTO messages VALUES(1,'answer','[{}]')")
upgrade = db.split("if (oldVersion < 21) {", 1)[1].split("\n        }", 1)[0]
for sql in re.findall(r'db.execSQL\("(ALTER TABLE[^"\n]+)"\)', upgrade): legacy.execute(expand(sql))
assert legacy.execute("SELECT text,attachments,finish_reason,openrouter_protocol FROM messages").fetchone() == ("answer", "[{}]", None, None)

# Execute the actual v21->v22 additive migration without replacing old protocol/text.
upgrade22 = db.split("if (oldVersion < 22) {", 1)[1].split("\n        }", 1)[0]
for sql in re.findall(r'db.execSQL\("(ALTER TABLE[^"\n]+)"\)', upgrade22): legacy.execute(expand(sql))
assert legacy.execute("SELECT text,attachments,protocol_context_state FROM messages").fetchone() == ("answer", "[{}]", None)
assert '"protocol_context_state"' in db.split('MessageMetadataCodec.allKeys.filter', 1)[1].split('}.forEach', 1)[0]

# Execute the reader's actual SQL templates on host SQLite. This checks byte slicing,
# Unicode/NUL integrity, composite-key isolation and bounded projection size; it does
# not instantiate Android CursorWindow or compile the Kotlin cursor wrapper.
reader = (ROOT / "app/src/main/java/com/example/openaichat/SqliteLargeTextQuery.kt").read_text()
row_budget = int(re.search(r'ROW_BYTES = (\d+) \* 1024', reader).group(1)) * 1024
chunk_budget = int(re.search(r'CHUNK_BYTES = (\d+) \* 1024', reader).group(1)) * 1024
templates = re.findall(r'projection.add\("(CASE WHEN[^"\n]+)"\)', reader)
assert len(templates) == 2
key_template = re.search(r'keyColumns\.forEachIndexed[^\n]*projection.add\("([^"\n]+)"\)', reader).group(1)


def reader_expression(template, field, index, inline):
    assert re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', field)
    quoted = '"' + field + '"'
    qualified = '"data".' + quoted
    result = (template.replace('${identifier(field)}', quoted)
              .replace('${column(quotedTable, field)}', qualified)
              .replace('$name', qualified).replace('$inlineBytes', str(inline)).replace('$index', str(index)))
    assert '$' not in result, f'Unexpanded SQLite reader template: {result}'
    return result


chunk_template = re.search(r'db.rawQuery\("(SELECT substr[^"\n]+)"', reader).group(1)
large = "x" * (chunk_budget - 1) + "😀\0Философия科学\n" * 120000
other = "Подпись😀\n" * 180000
text_db = sqlite3.connect(":memory:")
text_db.execute("CREATE TABLE data(chat TEXT, request TEXT, payload TEXT, protocol TEXT, note TEXT, number INTEGER, PRIMARY KEY(chat,request))")
text_db.execute("INSERT INTO data VALUES(?,?,?,?,?,?)", ("c", "r", large, other, None, 7))
text_db.execute("INSERT INTO data VALUES(?,?,?,?,?,?)", ("c", "other", "wrong row", "wrong protocol", "next", 8))
fields = ("payload", "protocol", "note", "number")
keys = ("chat", "request")
inline = min(4096, row_budget // (len(fields) + len(keys) + 1))
projection = []
for template in templates:
    for index, field in enumerate(fields):
        projection.append(reader_expression(template, field, index, inline))
projection += [reader_expression(key_template, key, i, inline) for i, key in enumerate(keys)]
rows = text_db.execute("SELECT " + ",".join(projection) + " FROM data ORDER BY request").fetchall()
row = rows[1]
assert row[:4] == (None, None, None, 7)
assert row[4:8] == (len(large.encode()), len(other.encode()), None, None)
assert rows[0][:4] == ("wrong row", "wrong protocol", "next", 8)
assert sum(len(v.encode()) if isinstance(v, str) else 8 for v in row) < row_budget
assert text_db.execute("SELECT length(payload) FROM data WHERE request='r'").fetchone()[0] < len(large)
for index, expected in enumerate((large, other)):
    sql = chunk_template.replace("$field", '"data"."' + fields[index] + '"').replace("$table", '"data"')
    sql = sql.replace("$where", '"data"."chat"=? AND "data"."request"=?')
    length = row[len(fields) + index]
    offset = 0
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    parts = []
    while offset < length:
        count = min(chunk_budget, length - offset)
        chunk = text_db.execute(sql, (str(offset + 1), str(count), *row[len(fields) * 2:])).fetchone()[0]
        assert isinstance(chunk, bytes) and len(chunk) == count <= chunk_budget
        parts.append(decoder.decode(chunk)); offset += len(chunk)
    parts.append(decoder.decode(b"", final=True))
    assert "".join(parts) == expected

# Missing identifiers must fail during preparation even for empty/small rows;
# SQLite's permissive double-quoted string fallback must not invent owned paths.
for populated in (False, True):
    for field, key in (("missing", "id"), ("payload", "missing_key")):
        with sqlite3.connect(":memory:") as broken:
            broken.execute("CREATE TABLE data(id INTEGER PRIMARY KEY, payload TEXT)")
            if populated:
                broken.execute("INSERT INTO data VALUES(1,'short')")
            invalid_projection = [reader_expression(template, field, 0, inline) for template in templates]
            invalid_projection.append(reader_expression(key_template, key, 0, inline))
            try:
                broken.execute("SELECT " + ",".join(invalid_projection) + " FROM data").fetchall()
            except sqlite3.OperationalError as error:
                assert "no such column" in str(error), str(error)
            else:
                raise AssertionError(f"Missing SQLite identifier accepted: {field}/{key}, populated={populated}")
# Regression guards for the previously reproduced Android foreground loss:
# provider HTTP x-request-id must not become the SQLite ownership request_id.
main_activity = (ROOT / "app/src/main/java/com/example/openaichat/MainActivity.kt").read_text()
assert 'JsonProtocol.string(messages[0], "request_id") != expectedRequestId) return false' in db, "Mismatched terminal assistant must not consume active ownership"
assert '"Готово. Ответ не содержит текста."' not in main_activity, "UI status must not enter canonical response"
assert '"Ответ завершился без полученного текста: "' not in main_activity, "Exception text must stay in metadata"
xlsx = (ROOT / "app/src/main/java/com/example/openaichat/StructuredDocumentExtractor.kt").read_text()
assert 'cell?.put("formula", "")' in xlsx and '"formula_si"' in xlsx

# Task #3, #5, #24 guards:
ids_xml = (ROOT / "app/src/main/res/values/strings.xml").read_text()
assert 'name="chat_messages_recycler"' in ids_xml, "RecyclerView requires a stable resource ID"
assert 'id = R.id.chat_messages_recycler' in main_activity, "MainActivity RecyclerView must bind stable R.id.chat_messages_recycler"
assert 'outState.putString("per_chat_scroll_anchors_v1", ChatScrollPolicy.encodeAnchorsJson(perChatScrollAnchors, currentChatId()))' in main_activity, "Bounded scroll anchors must be saved with the current chat preferred"
assert 'if (anchor.isReadingLocked) userScrollLockedChats.add(chatId)' in main_activity, "Scroll lock state must be restored from bounded anchors"
assert 'ArrayList(userScrollLockedChats)' not in main_activity, "Saved state must not contain an unbounded duplicate scroll-lock list"
assert 'ChatScrollPolicy.decodeAnchorsJson(savedInstanceState.getString("per_chat_scroll_anchors_v1"))' in main_activity, "Scroll anchors must be restored from savedInstanceState"
scroll_policy = (ROOT / "app/src/main/java/com/example/openaichat/ChatScrollPolicy.kt").read_text()
assert 'fun actualDistanceFromBottomPx(' in scroll_policy and 'fun resolveAnchorPosition(' in scroll_policy and 'fun resolveTopOffsetPx(' in scroll_policy
pages_src = (ROOT / "app/src/main/java/com/example/openaichat/ResponseTextPages.kt").read_text()
assert 'MathDelimiterScanner.scan(source)' in pages_src and 'crossing.start > start' in pages_src, "ResponseTextPages must preserve closed formulas across 32K boundaries"
local_tools = (ROOT / "app/src/main/java/com/example/openaichat/LocalFileTools.kt").read_text()
assert 'val carryWindow = SEARCH_LEFT_CONTEXT + query.length - 1' in local_tools, "LocalFileTools.search overlap must include both left snippet context and query length"
orr_runtime = (ROOT / "app/src/main/java/com/example/openaichat/OpenRouterResponseRuntime.kt").read_text()
artifact_resolver = (ROOT / "app/src/main/java/com/example/openaichat/LocalArtifactAttachmentResolver.kt").read_text()
assert 'LocalArtifactAttachmentResolver.resolve(' in orr_runtime, "OpenRouterResponseRuntime must use the identity-aware artifact resolver"
assert 'OpenRouterAttachmentStore.classifyLocalFile(' in artifact_resolver and \
    'OpenRouterAttachmentPreflight.verifySources(listOf(previous))' in artifact_resolver and \
    'persistLocalArtifactAttachment(' in artifact_resolver, "Artifact reuse must retain source verification, fallback classification and durable manifest CAS"
assert 'textEncoding = "UTF-8".takeIf' not in orr_runtime, "Unconditional UTF-8 local file registration must not remain in OpenRouterResponseRuntime"
chat_db_src = (ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt").read_text()
assert 'fun listLegacyCheckpointAttachmentPaths(' in chat_db_src, "ChatDatabase must expose legacy checkpoint attachment paths separately from durable paths"
assert 'cachedLayout' in pages_src, "ResponseTextPages must cache computed page slices to avoid repeated O(N) formula scans"
assert 'legacyAliases' in local_tools, "LocalFileTools must support legacy local_<filename> alias lookup"
assert "ExecutorResourceCloser.closeAfterTermination" in main_activity, "Activity teardown must defer SQLite close until owned callbacks terminate"


# Direct OpenAI networking is retired; local historical metadata is still supported.
main_sources = ROOT / "app/src/main/java/com/example/openaichat"
retired_network_sources = (
    "OpenAiCancelReconciler.kt", "OpenAiCreateCallOwners.kt", "OpenAiFileUploadCache.kt",
    "OpenAiFileAnalysis.kt", "OpenAiLocalContextEncoder.kt", "OpenAiLocalFileToolBridge.kt",
    "ResumableResponseStreamClient.kt", "ResumableResponseRequest.kt",
    "BackgroundResponseWorker.kt", "BackgroundResponseWorkScheduler.kt", "ContextToolSchemas.kt",
)
for name in retired_network_sources:
    assert not (main_sources / name).exists(), f"Retired direct OpenAI transport remains: {name}"
for path in main_sources.glob("*.kt"):
    assert not re.search(r'https?://api\.openai\.com(?:/|["\s])', path.read_text()), \
        f"Direct OpenAI endpoint remains in production: {path.name}"
catalog = (main_sources / "ModelCatalog.kt").read_text()
model_ids = re.findall(r'ModelOption\(\s*"([^"]+)"', catalog)
assert model_ids and all(model.startswith("anthropic/") for model in model_ids), "Only Claude models may be selected"
codec = (main_sources / "MessageMetadataCodec.kt").read_text()
for key in ("openai_protocol_output", "openai_usage_ledger"):
    assert key in codec, f"Legacy metadata contract lost: {key}"
assert 'FINGERPRINT_FIELD' in codec or '"openai_instructions_sha256"' in codec, "Historical instruction fingerprint field must remain in metadata v1"
assert any('"openai_instructions_sha256"' in path.read_text() for path in main_sources.glob("*.kt")), "Historical instruction fingerprint wire key lost"
assert 'OpenAiRequestSnapshotAndUploadTest' not in (ROOT / '.github/workflows/build.yml').read_text(), "CI must not invoke the retired OpenAI suite"

print(f"Implementation lexical/schema checks: PASS ({len(files)} Kotlin files; SQLite definitions, v20->v21->v22 additive columns, FK cleanup, long UTF-8/NUL byte slices). Android compilation/tests remain separate.")
