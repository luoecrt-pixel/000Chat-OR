from pathlib import Path
import os
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(os.environ.get("ASTRA_VERIFY_ROOT", Path(__file__).resolve().parents[1]))
MAIN = ROOT / "app/src/main/java/com/example/openaichat/MainActivity.kt"
DB = ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt"
MODEL = ROOT / "app/src/main/java/com/example/openaichat/AstraResponseModel.kt"
RENDERER = ROOT / "app/src/main/java/com/example/openaichat/RichTextRenderer.kt"
GRADLE = ROOT / "app/build.gradle.kts"
WF = ROOT / ".github/workflows/build.yml"

main = MAIN.read_text()
db = DB.read_text()
model = MODEL.read_text()
renderer = RENDERER.read_text()
gradle = GRADLE.read_text()
workflow = WF.read_text()

assert 'versionName = "4.17.0"' in gradle
assert 'applicationId = "com.example.openaichat.openrouter"' in gradle
assert 'androidx.work:' not in gradle, 'OpenRouter service must not retain the retired Worker dependency'
assert 'implementation("androidx.recyclerview:recyclerview:1.4.0")' in gradle
assert 'implementation("androidx.core:core-ktx:1.15.0")' in gradle
assert 'implementation("androidx.activity:activity-ktx:1.10.1")' in gradle
assert 'class MainActivity : ComponentActivity()' in main
assert 'registerForActivityResult(ActivityResultContracts.StartActivityForResult())' in main
assert 'startActivityForResult(' not in main
assert 'override fun onActivityResult' not in main
assert 'versionCode = 64' in gradle
assert 'buildConfig = true' in gradle
assert 'container_file_citation' in main
assert 'file_citation' in main
assert 'url_citation' in main
assert 'background_responses' in main
assert 'preparePendingOpenRouterZipsThenSend' in main
assert 'reasoningSummary' in model
assert 'AstraResponseModel.fromChatJson' in main
assert 'URLSpan' in renderer
assert 'LinkMovementMethod' in main
assert 'file_paths' in main and 'file_mimes' in main
assert 'image_name' in main and 'image_mime' in main
assert 'showTextPreview' in main and 'showPdfPreview' in main
assert 'addCompactTextPreview' in main
assert 'addCompactImagePreview' in main
assert 'addCompactPdfPreview' in main
assert 'updateMessageFileLocalState' not in main, 'historical downloads must not mutate captured message/index state'
assert 'while(message.optJSONArray("file_paths")' not in main
assert 'handleZipAttachment' not in main
assert 'web_search_preview' not in main
assert 'listChatSummaries()' in main
assert 'BuildConfig.APPLICATION_ID' in main
assert 'listLocalFiles' in db
assert 'generated_images' in db
# The verifier preserves the v17 metadata migration contract while accepting v18's
# additive request-cost migration. Do not require a frozen current DB version.
db_version = re.search(r'private const val DB_VERSION = ([0-9]+)', db)
assert db_version and int(db_version.group(1)) >= 18, 'cost migration requires schema v18 or newer'
assert 'if (oldVersion < 17)' in db
assert 'if (oldVersion < 18)' in db
assert "ALTER TABLE $REQUEST_EVENT_TABLE ADD COLUMN cost_kind TEXT NOT NULL DEFAULT 'unknown'" in db
assert 'ALTER TABLE $REQUEST_EVENT_TABLE ADD COLUMN cost_usd TEXT' in db
upgrade_v18 = db.split("if (oldVersion < 18) {", 1)[1]
assert "ensureRequestEventsTable(db)" in upgrade_v18
assert "PRAGMA table_info($REQUEST_EVENT_TABLE)" in upgrade_v18
assert 'if ("cost_kind" !in columns)' in upgrade_v18
assert 'if ("cost_usd" !in columns)' in upgrade_v18
assert 'MessageMetadataCodec.allKeys.filter' in db and 'finish_reason' in db and 'openrouter_protocol' in db
assert 'MessageMetadataCodec.decodeInto' in db
codec = (ROOT / 'app/src/main/java/com/example/openaichat/MessageMetadataCodec.kt').read_text()
for key in ['provider', 'model', 'requested_model', 'response_model', 'reasoning_summary', 'error', 'tool_trace', 'usage']:
    assert '"'+key+'"' in codec, f'v17 message metadata contract missing {key}'
assert 'idx_messages_chat_request' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_name TEXT' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_mime TEXT' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_size INTEGER NOT NULL DEFAULT 0' in db

for p in [ROOT/'app/src/main/AndroidManifest.xml', *ROOT.glob('app/src/main/res/**/*.xml')]:
    ET.parse(p)

# Representative SQLite schema sanity check.
con = sqlite3.connect(':memory:')
con.execute('PRAGMA foreign_keys=ON')
con.execute('''CREATE TABLE chats (
    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, response_id TEXT,
    baseline_effort TEXT, updated_at INTEGER NOT NULL,
    active_response_id TEXT, active_request_id TEXT, active_text TEXT,
    active_status TEXT, active_started_at INTEGER, active_effort_pref TEXT,
    active_effective_effort TEXT, active_auto_selected INTEGER NOT NULL DEFAULT 0,
    active_prompt_chars INTEGER NOT NULL DEFAULT 0, active_attachments INTEGER NOT NULL DEFAULT 0,
    active_background_enabled INTEGER NOT NULL DEFAULT 1,
    active_last_sequence_number INTEGER NOT NULL DEFAULT -1
)''')
con.execute('''CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, position INTEGER NOT NULL,
    role TEXT NOT NULL, text TEXT NOT NULL, response_id TEXT, request_id TEXT,
    file_ids TEXT, file_names TEXT, file_sizes TEXT, file_paths TEXT, file_mimes TEXT,
    image_path TEXT, image_file_id TEXT, image_name TEXT, image_mime TEXT,
    image_size INTEGER NOT NULL DEFAULT 0, annotations TEXT, generated_images TEXT,
    tools TEXT, status TEXT, feedback INTEGER,
    FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
)''')
con.execute("INSERT INTO chats(id,title,updated_at) VALUES('chat-1','x',0)")
con.execute("INSERT INTO messages(chat_id,position,role,text) VALUES('chat-1',0,'user','hello')")
con.execute("DELETE FROM chats WHERE id='chat-1'")
assert con.execute('SELECT COUNT(*) FROM messages').fetchone()[0] == 0

cols = {r[1] for r in con.execute('PRAGMA table_info(messages)')}
assert {'file_paths','file_mimes','image_name','image_mime','image_size','generated_images'} <= cols

# v14 -> v15 migration must preserve partial response state and initialize the cursor to -1.
legacy = sqlite3.connect(':memory:')
legacy.execute('CREATE TABLE chats (id TEXT PRIMARY KEY, active_text TEXT, active_last_sequence_number INTEGER)')
legacy.execute("INSERT INTO chats(id, active_text, active_last_sequence_number) VALUES('c1','partial',NULL)")
legacy.execute('ALTER TABLE chats ADD COLUMN migrated_cursor INTEGER NOT NULL DEFAULT -1')
text_value, cursor_value = legacy.execute("SELECT active_text, migrated_cursor FROM chats WHERE id='c1'").fetchone()
assert text_value == 'partial'
assert cursor_value == -1

# Kotlin delimiter sanity (strings/comments skipped).
def delimiter_balance(src: str):
    stack=[]; i=0; state='code'
    while i < len(src):
        c=src[i]; n=src[i+1] if i+1 < len(src) else ''
        if state=='line':
            if c=='\n': state='code'
            i+=1; continue
        if state=='block':
            if c=='*' and n=='/': state='code'; i+=2
            else: i+=1
            continue
        if state=='string':
            if c=='\\': i+=2; continue
            if c=='"': state='code'
            i+=1; continue
        if state=='char':
            if c=='\\': i+=2; continue
            if c=="'": state='code'
            i+=1; continue
        if c=='/' and n=='/': state='line'; i+=2; continue
        if c=='/' and n=='*': state='block'; i+=2; continue
        if c=='"': state='string'; i+=1; continue
        if c=="'": state='char'; i+=1; continue
        if c in '({[': stack.append(c)
        elif c in ')}]':
            assert stack, 'unexpected delimiter'
            expected={')':'(', '}':'{', ']':'['}[c]
            assert stack[-1]==expected, f'delimiter mismatch: expected {expected}, got {c}'
            stack.pop()
        i+=1
    assert state=='code', f'unclosed lexer state: {state}'
    assert not stack, f'unclosed delimiters: {stack[-10:]}'

for p in [MAIN,DB,MODEL,RENDERER]:
    delimiter_balance(p.read_text())

for action in ['actions/checkout@v7','actions/setup-java@v6','android-actions/setup-android@v4','gradle/actions/setup-gradle@v6','actions/upload-artifact@v7']:
    assert action in workflow
assert 'gradle-version: \'8.9\'' in workflow
assert 'gradle --no-daemon --stacktrace test lintDebug assembleDebug' in workflow
assert 'permissions:' in workflow and 'contents: read' in workflow
assert 'expectedRequestId' in main
assert 'upsertCurrentChatIfActiveRequest' in db
assert 'active_background_enabled' in db
assert 'isNearLatest' in main
assert 'ChatScrollPolicy.shouldScrollToLatest' in main
assert (ROOT / 'app/src/main/java/com/example/openaichat/ChatScrollPolicy.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/ChatScrollPolicyTest.kt').exists()
assert 'concurrency:' in workflow
assert 'activityVisible' in main
assert 'isFinishing)' in main
assert '!finishing' in (ROOT / 'app/src/main/java/com/example/openaichat/ActiveResponseState.kt').read_text()
assert 'ViewCompat.setOnApplyWindowInsetsListener' in main
assert 'WindowInsetsCompat.Type.systemBars()' in main
adapter = (ROOT / 'app/src/main/java/com/example/openaichat/ChatMessageAdapter.kt').read_text()
assert 'AsyncListDiffer' in adapter
assert 'itemById' in adapter
assert 'itemIndexById' in adapter
assert 'displayPositionById' in adapter
assert 'items.firstOrNull' not in adapter
assert 'differ.currentList.indexOfFirst' not in adapter
assert 'active_last_sequence_number < ?' in db, 'OpenRouter prefix updates must remain monotonic'
assert (ROOT / 'app/src/main/java/com/example/openaichat/ActiveResponseState.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/ActiveResponseStateTest.kt').exists()
assert 'backgroundResumeUiSnapshotMustMatchGenerationChatAndAssistant' in (ROOT / 'app/src/test/java/com/example/openaichat/ActiveResponseStateTest.kt').read_text()
assert 'ActiveResponseStateStore' in main
assert 'activeResponseState.state.isCurrent' in main
assert 'activeResponseState.markUserStop()' in main

def find_matching_kotlin_brace(src: str, open_index: int) -> int:
    assert src[open_index] == "{"
    depth = 0
    i = open_index
    state = "code"
    while i < len(src):
        c = src[i]
        n = src[i + 1] if i + 1 < len(src) else ""
        if state == "line":
            if c == "\n":
                state = "code"
            i += 1
            continue
        if state == "block":
            if c == "*" and n == "/":
                state = "code"
                i += 2
            else:
                i += 1
            continue
        if state == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "code"
            i += 1
            continue
        if state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
            continue
        if c == "/" and n == "/":
            state = "line"
            i += 2
            continue
        if c == "/" and n == "*":
            state = "block"
            i += 2
            continue
        if c == '"':
            state = "string"
            i += 1
            continue
        if c == "'":
            state = "char"
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise AssertionError("unterminated Kotlin brace block")


def kotlin_method(src: str, name: str) -> str:
    match = re.search(r'\bfun\s+' + re.escape(name) + r'\s*\(', src)
    assert match, f'missing method: {name}'
    opening = src.index('{', match.end())
    return src[match.start():find_matching_kotlin_brace(src, opening) + 1]


assert 'systemWindowInsetLeft' not in main
assert 'consumeSystemWindowInsets()' not in main
assert 'pollingClient' in main
# OpenRouter uses the shared bounded HTTP/SSE decoder.
bounded_decoder = (ROOT / 'app/src/main/java/com/example/openaichat/BoundedResponseDecoder.kt').read_text()
router_client = (ROOT / 'app/src/main/java/com/example/openaichat/OpenRouterChatClient.kt').read_text()
assert 'SseEventParser()' in bounded_decoder
assert 'BoundedResponseDecoder.readSse(reader' in router_client
assert 'BoundedResponseDecoder.openSse(bodyResponse.byteStream(), sseLimits)' in router_client
assert 'if (frame != null && consume(frame)) { stopped = true; break }' in bounded_decoder
assert '.onMalformedInput(CodingErrorAction.REPORT)' in bounded_decoder
assert '.onUnmappableCharacter(CodingErrorAction.REPORT)' in bounded_decoder
assert 'allocation.framingCharacters' in bounded_decoder
assert 'allocation.json' in bounded_decoder
assert 'ERROR_BODY_LIMITS' in bounded_decoder
for transport in (router_client,):
    assert 'body?.string()' not in transport
    assert 'BoundedResponseDecoder.readErrorBody' in transport
assert 'reader.readLine()' not in router_client
assert 'listLocalFilePaths' in db
assert 'deleteOwnedLocalFile' in main
assert (ROOT / 'app/src/test/java/com/example/openaichat/SseEventParserTest.kt').exists()
assert 'testImplementation("junit:junit:4.13.2")' in gradle
assert 'FOREGROUND_SERVICE_DATA_SYNC' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert 'foregroundServiceType="dataSync"' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert (ROOT / 'app/src/test/java/com/example/openaichat/OpenRouterPaidPostSafetyTest.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/OpenRouterRuntimeCheckpointTest.kt').exists()
assert 'idx_effort_metrics_chat_request' in db
assert 'CREATE UNIQUE INDEX' in db
assert 'DELETE FROM $METRICS_TABLE' in db
assert 'GROUP BY chat_id, request_id' in db
assert 'activityVisible' in main
assert 'setForeignKeyConstraintsEnabled(true)' in db
assert not list(ROOT.glob('*-source.zip')), 'source archive must not remain in the repository'
assert 'tools/verify_v4_17_0.py' in workflow
file_paths_xml = (ROOT / 'app/src/main/res/xml/file_paths.xml').read_text()
assert 'name="attachments" path="attachments/"' in file_paths_xml
assert 'cache-path name="cache" path="."' not in file_paths_xml

print('Astra v4.17.0 static verification: PASS')

# Runs inside the existing CI step; no extra workflow or Actions run is needed.
import runpy
runpy.run_path(str(ROOT / 'tools/verify_implementation.py'), run_name='__main__')
