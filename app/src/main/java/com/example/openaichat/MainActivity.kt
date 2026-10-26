package com.example.openaichat

import android.app.*
import android.content.*
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.text.*
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import java.util.UUID

class MainActivity : ComponentActivity(), OpenRouterUiBridge.Host {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private data class HistoricalAttachmentTarget(val chatId: String, val expected: JSONObject,
        val manifestIndex: Int? = null, val fileIndex: Int? = null, val image: Boolean = false)
    private var pendingAttachmentRestore: HistoricalAttachmentTarget? = null

    private val pendingAttachmentChange = AtomicReference<String?>(null)
    private val historicalFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingAttachmentRestore
        pendingAttachmentRestore = null
        if (uri != null && target != null) restoreHistoricalAttachment(target, uri)
    }

    private data class UploadCardViews(
        val root: LinearLayout,
        val preview: ImageView,
        val name: TextView,
        val details: TextView,
        val progress: ProgressBar,
        val actions: LinearLayout,
        val action: TextView
    )


    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val pollingClient = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var localEvidenceSourcesHookForTests: (() -> List<OpenRouterLocalAttachment>)? = null
    private var localEvidenceSnapshotChatId: String? = null
    private var localEvidenceEligible = false
    private var localEvidencePresencePending = false
    private var localEvidencePresenceVersion = 0L
    private var localEvidenceSourceSession: LocalEvidenceSourceSession? = null
    // Preparation finishes in picker order, rather than whichever image decodes first.
    // Reuse the Activity executor so response work stays independent and teardown
    // still owns every attachment task; a failed item does not block later items.
    private var attachmentPreparationTail = CompletableFuture.completedFuture(Unit)

    @Volatile private var openRouterClientFactory: () -> OpenRouterChatClient = { OpenRouterChatClient() }
    private val openRouterClient by lazy { openRouterClientFactory() }
    private val openRouterModelPreflightCall = AtomicReference<Call?>()
    private val pendingGitHubConfirmation = AtomicReference<CompletableFuture<Boolean>?>(null)
    private val pendingGitHubConfirmationDialog = AtomicReference<AlertDialog?>(null)

    private val currentGitHubToolOwner = ThreadLocal<Pair<String, Long>>()
    // Cancels an in-flight serialization before it can commit a user message or start HTTP.
    private val pendingOpenRouterPreparation = AtomicReference<String?>(null)

    @Volatile private var openRouterPreparationHook: (() -> Unit)? = null


    private data class ContextConfiguration(
        val model: String,
        val instructions: GenerationInstructions,
        val github: Boolean,
        val githubRepo: String,
        val githubBranches: String,
        val webSearch: Boolean,
        val webFetch: Boolean,
        val parallel: Boolean,
        val cacheMode: String,
        val route: OpenRouterClaudeRoute,
        val pdfEngine: String,
        val compatibleImages: Boolean,
        val responseFormat: String,
        val schema: String
    ) {
        override fun toString() = "ContextConfiguration(provider=openrouter, model=$model)"
    }

    private fun captureContextConfiguration(): ContextConfiguration = ContextConfiguration(
        model = ContextRequestPolicy.model("openrouter", prefs.getString("model", null)),
        instructions = GenerationInstructions(
            prefs.getString("system_prompt", ""),
            prefs.getString("instructions", ""),
            presentationInstructionsForRequest()
        ),
        github = prefs.getBoolean("github_tools", false),
        githubRepo = prefs.getString("github_repo", "luoecrt-pixel/OpenAIApiChat")?.trim().orEmpty(),
        githubBranches = prefs.getString("github_branches", "main")?.trim().orEmpty(),
        webSearch = prefs.getBoolean("openrouter_web_search", false),
        webFetch = prefs.getBoolean("openrouter_web_fetch", false),
        parallel = prefs.getBoolean("parallel_tools", true),
        cacheMode = prefs.getString("openrouter_cache_mode", "1h") ?: "1h",
        route = OpenRouterClaudeRoute.fromPreference(prefs.getString("openrouter_claude_route", null)),
        pdfEngine = prefs.getString("openrouter_pdf_engine", "native") ?: "native",
        compatibleImages = prefs.getString("openrouter_image_mode", "original") == "compatible",
        responseFormat = prefs.getString("openrouter_text_format", "text") ?: "text",
        schema = prefs.getString("openrouter_json_schema", "").orEmpty()
    )


    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    lateinit var prefs: SharedPreferences
    private lateinit var chatDb: ChatDatabase
    private lateinit var activeResponsePersistence: ActiveResponsePersistence
    private lateinit var diagnostics: AstraDiagnostics
    private lateinit var scroll: RecyclerView
    private lateinit var jumpToBottomBtn: TextView
    private val perChatScrollAnchors = mutableMapOf<String, ChatScrollAnchor>()
    private val userScrollLockedChats = mutableSetOf<String>()
    private var displayedChatId: String? = null
    private var pendingScrollRestoreChatId: String? = null
    private var pendingScrollRestoreAnchor: ChatScrollAnchor? = null
    private var scrollRestoreGeneration: Long = 0L
    private var scrollCallbacksStopped = false
    private var applyingProgrammaticScroll: Boolean = false
    private var laidOutFontScale: Float = 1f
    private var laidOutViewportWidthPx: Int = 0
    private lateinit var messageAdapter: ChatMessageAdapter
    private lateinit var input: EditText
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var toolLabel: TextView
    private lateinit var title: TextView
    private lateinit var attachRow: LinearLayout


    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var effortBadge: TextView
    private lateinit var modelBadge: TextView
    private lateinit var responseSettingsBtn: Button
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var headerDividerView: View
    private lateinit var drawerPanelLayout: LinearLayout
    private lateinit var drawerTitleView: TextView
    private lateinit var drawerNewChatBtn: Button
    private lateinit var drawerLayout: FingerDrawerLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var requestTelemetryCard: LinearLayout
    private lateinit var requestTelemetrySummary: TextView
    private lateinit var requestContextBadge: TextView
    private var contextInspectionSerial = 0L
    private var contextDialogPending = false
    private var contextBadgeInFlight = false
    private var contextBadgeDirty = false
    private var contextInspectionCompletedHook: (() -> Unit)? = null
    private var contextInspectionPreparationHook: (() -> Unit)? = null
    private var contextBadgePreparationHook: (() -> Unit)? = null
    private var contextBadgeMode: String? = null
    private val presentationRendererAvailable by lazy { ScientificRendererPreview.available(this) }
    private lateinit var requestTelemetryHistory: LinearLayout
    private lateinit var requestTelemetryScroll: ScrollView
    private var requestTelemetryExpanded = false


    private val activeResponseState = ActiveResponseStateStore()
    private val requestGeneration: Long
        get() = activeResponseState.state.generation
    private var currentResponseId: String?
        get() = activeResponseState.state.responseId
        set(value) = activeResponseState.setResponseId(value)
    private var activeAssistantItemId: String?
        get() = activeResponseState.state.assistantItemId
        set(value) = activeResponseState.setAssistantItemId(value)
    private var activeRequestRegenerate = false
    @Volatile private var activityVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var draftSaveRunnable: Runnable? = null
    private var restoringComposer = false

    private val toolStates = linkedMapOf<String, String>()
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val pendingUploadUris = mutableListOf<Pair<String,String>>() // uri -> image|file|zip
    private var baselineEffort: String? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val pendingUploads = AtomicInteger(0)
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var preparingZipAttachments = false

    private val uploadProgress = ConcurrentHashMap<String, Pair<Long, Int>>()

    private val composerMergeLock = Any()
    private var artifactUiOwnership: java.io.Closeable? = null
    @Volatile private var activeZipOperation: String? = null
    private val cancelledUploads = ConcurrentHashMap.newKeySet<String>()
    private val openRouterModelModalities = ConcurrentHashMap<String, Set<String>>()
    private val openRouterModelModalitiesRetryAfter = ConcurrentHashMap<String, Long>()
    private val uploadCards = mutableMapOf<String, UploadCardViews>()
    private val chat = mutableListOf<JSONObject>()
    private val attachedFileIds = mutableListOf<String>()
    private val attachedFileNames = mutableListOf<String>()
    private val attachedFileSizes = mutableListOf<Long>()
    private val attachedFilePaths = mutableListOf<String>()
    private val attachedFileMimes = mutableListOf<String>()
    private val attachedOpenRouterAttachments = mutableListOf<OpenRouterLocalAttachment>()
    private val pendingAttachments get() = attachedOpenRouterAttachments
    private var pendingImagePath: String? = null
    private var pendingImageFileId: String? = null
    private var pendingImageName: String? = null
    private var pendingImageMime: String? = null
    private var pendingImageSize: Long = 0L
    private var lastUserText: String = ""
    private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var photoPickerLauncher: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>
    private var pendingActivityResultCode: Int? = null
    private var pendingCameraPath: String? = null
    private var pendingCameraChatId: String? = null
    private var pendingCameraProvider: String? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var attachmentSheetDialog: Dialog? = null

    private val secure by lazy { SecureStore(this) }
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var openRouterAttachmentInputStreamSource: AttachmentInputStreamSource = DefaultAttachmentInputStreamSource
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val pickAny = 5100
    private val pickImage = 5101
    private val pickVoice = 5102
    private val pickCamera = 5106

    private val pickExportTxt = 5104
    private val pickSaveGenerated = 5105
    private var pendingExportPath: String? = null
    private val exportSnapshots by lazy { ConversationExportSnapshots(File(filesDir, "exports")) }
    private val conversationExportPreparing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pendingPickerChatId: String? = null
    private var pendingPickerProvider: String? = null
    private var pendingSaveSourcePath: String? = null
    private var pendingSaveFilename: String? = null
    private var pendingSaveMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        artifactUiOwnership = LocalArtifactOwnership.lease()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pendingActivityResultCode = savedInstanceState?.getInt("pending_result_code", -1)?.takeIf { it >= 0 }
        pendingCameraPath = savedInstanceState?.getString("pending_camera_path")
        pendingCameraChatId = savedInstanceState?.getString("pending_camera_chat_id")
        pendingCameraProvider = savedInstanceState?.getString("pending_camera_provider")
        pendingExportPath = exportSnapshots.retain(savedInstanceState?.getString("pending_export_path"))
        pendingSaveSourcePath = savedInstanceState?.getString("pending_save_path")
        pendingSaveFilename = savedInstanceState?.getString("pending_save_name")
        pendingSaveMime = savedInstanceState?.getString("pending_save_mime")
        pendingPickerChatId = savedInstanceState?.getString("pending_picker_chat")
        pendingPickerProvider = savedInstanceState?.getString("pending_picker_provider")
        perChatScrollAnchors.clear()
        userScrollLockedChats.clear()
        if (savedInstanceState != null) {
            perChatScrollAnchors.putAll(
                ChatScrollPolicy.decodeAnchorsJson(savedInstanceState.getString("per_chat_scroll_anchors_v1"))
            )
            perChatScrollAnchors.forEach { (chatId, anchor) ->
                if (anchor.isReadingLocked) userScrollLockedChats.add(chatId)
            }
        }
         activityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
             val requestCode = pendingActivityResultCode
             pendingActivityResultCode = null
             if (requestCode == pickCamera) {
                 completePendingCameraCapture(result.resultCode)
             } else if (requestCode != null) {
                 handleActivityResult(requestCode, result.resultCode, result.data)
                 pendingPickerChatId = null
                 pendingPickerProvider = null
             }
         }
        photoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(OpenRouterAttachmentStore.MAX_ATTACHMENTS)
        ) { uris ->
            if (uris.isNotEmpty()) {
                val result = Intent().apply {
                    data = uris.first()
                    if (uris.size > 1) {
                        val clip = ClipData.newUri(contentResolver, "Photo Picker", uris.first())
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                        clipData = clip
                    }
                }
                handleActivityResult(pickImage, RESULT_OK, result)
            }
            pendingPickerChatId = null
            pendingPickerProvider = null
        }
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        migrateLegacySettingsPreferences()
        chatDb = ChatDatabase(applicationContext)
        activeResponsePersistence = ChatDatabaseActiveResponsePersistence(chatDb)
        diagnostics = AstraDiagnostics(this)
        if (!secure.discardLegacyOpenAiCredential()) {
            diagnostics.log("legacy_credential_cleanup_failed", mapOf("provider" to "openai"))
        }
        val legacyHistoryMigrated = chatDb.migrateLegacyPrefs(prefs)
        ensureChatStore(legacyMigrationPending = !legacyHistoryMigrated)
        buildUi()
        restoreCurrentChat()
        if (!legacyHistoryMigrated) {
            status.text = "Старая история не импортирована полностью. Исходные данные сохранены; текущая история не заменена."
            diagnostics.log("legacy_history_import_deferred", emptyMap())
        }
        OpenRouterUiBridge.attach(this)
        openNotificationChat(intent)
        executor.execute { cleanupOrphanOpenRouterAttachments() }
        executor.execute { exportSnapshots.cleanup() }
        diagnostics.log("app_started", mapOf("version" to BuildConfig.VERSION_NAME, "git_sha" to BuildConfig.BUILD_GIT_SHA, "model" to normalizedModelId()))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::scroll.isInitialized && scroll.height > 0 &&
            ::messageAdapter.isInitialized && messageAdapter.itemCount > 0 &&
            pendingScrollRestoreChatId != currentChatId()
        ) {
            captureCurrentScrollAnchor()
        }
        retainPendingScrollAnchor()
        outState.putString("per_chat_scroll_anchors_v1", ChatScrollPolicy.encodeAnchorsJson(perChatScrollAnchors, currentChatId()))
        pendingActivityResultCode?.let { outState.putInt("pending_result_code", it) }
        pendingCameraPath?.let { outState.putString("pending_camera_path", it) }
        pendingCameraChatId?.let { outState.putString("pending_camera_chat_id", it) }
        pendingCameraProvider?.let { outState.putString("pending_camera_provider", it) }
        pendingExportPath?.let { outState.putString("pending_export_path", it) }
        pendingSaveSourcePath?.let { outState.putString("pending_save_path", it) }
        pendingSaveFilename?.let { outState.putString("pending_save_name", it) }
        pendingSaveMime?.let { outState.putString("pending_save_mime", it) }
        pendingPickerChatId?.let { outState.putString("pending_picker_chat", it) }
        pendingPickerProvider?.let { outState.putString("pending_picker_provider", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        scrollCallbacksStopped = false
        OpenRouterUiBridge.attach(this)
        recoverInterruptedOpenRouterRequests()
        OpenRouterResponseRuntime.recoverLocalArtifacts(applicationContext)
        executor.execute { ResponseNotifications.restorePending(applicationContext) }
        val currentId = currentChatId()
        val durable = try { chatDb.getChat(currentId) } catch (_: Exception) { null }
        val durableMessages = durable?.optJSONArray("messages")
        if (durableMessages != null && durableMessages.toString() !=
            JSONArray().apply { chat.forEach { put(it) } }.toString()) {
            persistComposerStateNow()
            restoreCurrentChat()
            renderRequestTelemetry()
        }
        refreshAttachChips()
        if (pendingScrollRestoreChatId == currentChatId()) {
            applyRestoredScrollState(currentChatId(), pendingScrollRestoreAnchor, scrollRestoreGeneration)
        }
    }

    override fun onStop() {
        if (pendingOpenRouterPreparation.getAndSet(null) != null) {
            setBusy(false)
            status.text = "Подготовка остановлена — черновик сохранён"
        }
        cancelPendingGitHubConfirmation()
        if (::scroll.isInitialized && scroll.height > 0 &&
            ::messageAdapter.isInitialized && messageAdapter.itemCount > 0 &&
            pendingScrollRestoreChatId != currentChatId()
        ) {
            captureCurrentScrollAnchor()
        }
        retainPendingScrollAnchor()
        scrollCallbacksStopped = true
        scrollRestoreGeneration++
        persistComposerStateNow()
        activityVisible = false
        val active = try { activeResponsePersistence.listActiveResponses() } catch (_: Exception) { emptyList() }
        cancelOpenRouterRequest()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        val currentActive = active.firstOrNull { it.optString("chat_id") == currentChatId() }
        val backgroundEnabled = currentActive?.optBoolean("background_enabled", true) == true
        if (!backgroundEnabled) {
            currentActive?.optString("request_id")?.let {
                OpenRouterResponseRuntime.cancel(it, "Фоновое выполнение отключено — соединение прервано")
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        localEvidenceSourceSession?.invalidate()
        retainPendingScrollAnchor()
        scrollCallbacksStopped = true
        cancelScrollRestore()
        attachmentSheetDialog?.dismiss()
        attachmentSheetDialog = null
        cancelPendingGitHubConfirmation()
        persistComposerStateNow()
        activeResponseState.nextGeneration()
        draftSaveRunnable?.let(mainHandler::removeCallbacks)
        executor.shutdownNow()
        try { cancelOpenRouterRequest() } catch (_: Exception) {}
        try { openRouterModelPreflightCall.getAndSet(null)?.cancel() } catch (_: Exception) {}
        OpenRouterUiBridge.detach(this)
        try { if (!OpenRouterResponseRuntime.uses(openRouterClient)) openRouterClient.close() } catch (_: Exception) {}
        val destroyedDatabase = chatDb
        ExecutorResourceCloser.closeAfterTermination(executor) {
            try { destroyedDatabase.close() } catch (_: Exception) {}
        }
        super.onDestroy()
        artifactUiOwnership?.close()
        artifactUiOwnership = null
        OpenRouterResponseRuntime.cleanupLocalArtifacts(applicationContext)
    }

    private fun isRequestCurrent(chatId: String, generation: Long): Boolean =
        currentChatId() == chatId &&
            activeResponseState.state.isCurrent(generation, activityVisible, isFinishing)


    /** Billing-only reconciliation; never posts to UI or reopens an active chat. */
    private fun reconcileOpenRouterGenerations(chatId: String, localRequestId: String) {
        executor.execute {
            try {
                val rounds = chatDb.listOpenRouterRounds(chatId, localRequestId)
                if (rounds.none { it.optString("generation_id").isNotBlank() &&
                        it.optString("cost_kind", "unknown") != "provider_reported" }) return@execute
                val key = secure.getOpenRouterApiKey()
                if (key.isBlank()) return@execute
                val lookedUp = OpenRouterGenerationReconciler(pollingClient, onDiagnostic = { status, reason ->
                    diagnostics.log("openrouter_generation_decode_failed", mapOf(
                        "chat_id" to chatId, "local_request_id" to localRequestId,
                        "http_status" to status, "reason" to reason))
                }).reconcile(rounds, key) {
                    generationId, metadata ->
                    chatDb.reconcileOpenRouterGeneration(chatId, localRequestId, generationId, metadata)
                }
                diagnostics.log("openrouter_generation_reconciled", mapOf(
                    "chat_id" to chatId, "local_request_id" to localRequestId,
                    "lookup_count" to lookedUp
                ))
                if (lookedUp > 0) {
                    runOnUiThread {
                        if (activityVisible && !isFinishing && currentChatId() == chatId) {
                            renderRequestTelemetry()
                        }
                    }
                }
            } catch (e: Exception) {
                diagnostics.log("openrouter_generation_reconcile_failed", mapOf(
                    "chat_id" to chatId, "local_request_id" to localRequestId,
                    "error_type" to e.javaClass.simpleName
                ))
            }
        }
    }

    private fun cancelOpenRouterRequest() {
        pendingOpenRouterPreparation.set(null)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun postAssistantTextUpdate(
        chatId: String,
        generation: Long,
        assistantItemId: String,
        snapshot: String
    ) {
        runOnUiThread {
            // Keep the lifecycle gate immediately before the mutation on the UI thread.
            if (isRequestCurrent(chatId, generation)) {
                messageAdapter.updateText(assistantItemId, snapshot)
                activeAssistantItemId = assistantItemId
                scrollToLatest()
            }
        }
    }

    private fun migrateLegacySettingsPreferences() {
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains("background_responses")) {
            editor.putBoolean("background_responses", true)
            changed = true
        }
        if (prefs.getString("provider", null) != "openrouter") {
            editor.putString("provider", "openrouter")
                .putString("provider_label", ModelCatalog.source("openrouter").label)
            changed = true
        }
        if (prefs.getString("model", null).isNullOrBlank()) {
            editor.putString("model", ModelCatalog.source("openrouter").models.first().id)
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun currentTheme(): ThemeColors =
        AppThemePalette.byId(prefs.getString("app_theme", "dark"))

    private fun applyTheme() {
        val theme = currentTheme()
        if (::rootLayout.isInitialized) {
            rootLayout.setBackgroundColor(theme.rootBg)
            AppThemeViews.buttons(rootLayout, theme)
        }
        if (::headerLayout.isInitialized) headerLayout.setBackgroundColor(theme.headerBg)
        if (::headerDividerView.isInitialized) headerDividerView.setBackgroundColor(theme.divider)
        if (::drawerLayout.isInitialized) drawerLayout.setBackgroundColor(theme.rootBg)
        if (::drawerPanelLayout.isInitialized) {
            drawerPanelLayout.setBackgroundColor(theme.drawerBg)
            AppThemeViews.buttons(drawerPanelLayout, theme)
        }
        if (::drawerTitleView.isInitialized) drawerTitleView.setTextColor(theme.textPrimary)
        if (::drawerNewChatBtn.isInitialized) {
            drawerNewChatBtn.setTextColor(theme.accent)
            drawerNewChatBtn.background = rippleRounded(theme.drawerNewChatBg, 10)
        }
        if (::title.isInitialized) title.setTextColor(theme.textPrimary)
        if (::status.isInitialized) {
            status.setTextColor(theme.textSecondary)
            status.background = AppThemeViews.surface(this, theme.cardBg, theme.surfaceBorder, 8)
        }
        if (::toolLabel.isInitialized) toolLabel.setTextColor(theme.accent)
        if (::modelBadge.isInitialized) AppThemeViews.button(modelBadge, theme)
        if (::effortBadge.isInitialized) {
            AppThemeViews.button(effortBadge, theme)
            effortBadge.setTextColor(theme.accent)
        }
        if (::input.isInitialized) {
            input.setTextColor(theme.textPrimary)
            input.setHintTextColor(theme.hintText)
            input.background = rounded(theme.inputBg, 20)
        }
        if (::send.isInitialized) {
            send.setTextColor(theme.onAccent)
            send.background = rounded(theme.accent, 20)
        }
        if (::jumpToBottomBtn.isInitialized) {
            jumpToBottomBtn.setTextColor(theme.textPrimary)
            jumpToBottomBtn.background = rippleRounded(theme.buttonBg, 19)
        }
        window?.let { win ->
            win.statusBarColor = theme.headerBg
            win.navigationBarColor = theme.rootBg
            WindowCompat.getInsetsController(win, win.decorView)?.apply {
                isAppearanceLightStatusBars = theme.isLight
                isAppearanceLightNavigationBars = theme.isLight
            }
        }
        if (::drawerList.isInitialized) refreshChatDrawer()
        if (::requestTelemetrySummary.isInitialized) renderRequestTelemetry()
        if (::messageAdapter.isInitialized) {
            val chatId = currentChatId()
            if (::scroll.isInitialized && scroll.height > 0 &&
                messageAdapter.itemCount > 0 && pendingScrollRestoreChatId != chatId &&
                perChatScrollAnchors[chatId] == null
            ) {
                captureCurrentScrollAnchor()
            }
            val savedAnchor = if (pendingScrollRestoreChatId == chatId) pendingScrollRestoreAnchor
                else perChatScrollAnchors[chatId]
            val restoreGeneration = beginScrollRestore(chatId, savedAnchor)
            messageAdapter.notifyDataSetChanged()
            applyRestoredScrollState(chatId, savedAnchor, restoreGeneration)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentTheme().rootBg)
        }
        rootLayout = root

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundColor(currentTheme().headerBg)
        }
        headerLayout = header

        val chatsBtn = button("☰") { showChats() }.apply {
            contentDescription = "Открыть чаты"
        }
        header.addView(chatsBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin=dp(6) })
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            text = currentChatTitle()
            textSize = 17f
            setTextColor(currentTheme().textPrimary)
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        heading.addView(title)
        modelBadge = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(170,180,200))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            background = rippleRounded(Color.TRANSPARENT, 7)
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener { showModelPicker() }
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("＋") { newChat() }.apply {
            contentDescription = "Новый чат"
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin=dp(4) })
        header.addView(button("⚙") { showSettings() }.apply {
            contentDescription = "Настройки"
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin=dp(4) })
        root.addView(header)
        updateModelBadge()
        headerDividerView = View(this).apply{ setBackgroundColor(currentTheme().buttonBg) }
        root.addView(headerDividerView, LinearLayout.LayoutParams(-1,dp(1)))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(currentTheme().textSecondary)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = AppThemeViews.surface(this@MainActivity, currentTheme().cardBg, currentTheme().surfaceBorder, 8)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            doAfterTextChanged {
                val message = it?.toString().orEmpty()
                visibility = if (message.isBlank() || message in setOf(
                    "Готово", "Новый чат", "Модель отвечает…", "Модель в очереди…",
                    "Продолжается генерация…", "Ответ ещё не завершён",
                    "Готовлю файлы и контекст…"
                )) View.GONE else View.VISIBLE
            }
            text = "Готово"
        }

        requestTelemetryCard = buildRequestTelemetryCard()
        root.addView(requestTelemetryCard)

        messageAdapter = ChatMessageAdapter(
            createCard = { createChatBubbleCard() },
            bindCard = { card, item -> bindChatBubbleCard(card, item) },
            renderBody = { item -> renderChatBubbleBody(item) },
            renderExtras = { card, item, body ->
                if (!item.user) {
                    body.visibility = View.GONE
                    card.findViewWithTag<RichResponsePanel>("rich-response")?.submit(item)
                }
            }
        )
        scroll = object : RecyclerView(this) {
            override fun scrollBy(x: Int, y: Int) {
                // Explicit upward scrolling wins over a delayed automatic follow.
                // Restores use the layout manager and are not user scroll intent.
                if (y < 0 && !isComputingLayout && !applyingProgrammaticScroll && pendingScrollRestoreChatId == currentChatId()) {
                    cancelScrollRestore()
                }
                super.scrollBy(x, y)
            }
        }.apply {
            id = R.id.chat_messages_recycler
            layoutManager = object : ChatMessageLayoutManager(this@MainActivity) {
                init {
                    stackFromEnd = true
                }

                private var explicitScrollPending = false
                private var adjustingLayoutAnchor = false

                override fun scrollToPosition(position: Int) {
                    if (!adjustingLayoutAnchor) explicitScrollPending = true
                    super.scrollToPosition(position)
                }

                override fun scrollToPositionWithOffset(position: Int, offset: Int) {
                    if (!adjustingLayoutAnchor) explicitScrollPending = true
                    super.scrollToPositionWithOffset(position, offset)
                }

                override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
                    val preserveViewport = !explicitScrollPending
                    if (state?.isPreLayout == false) explicitScrollPending = false
                    val mayRestore = state?.isPreLayout == false && itemCount > 0 && !applyingProgrammaticScroll &&
                        !scrollCallbacksStopped && !isFinishing && !isDestroyed && !messageAdapter.isListCommitPending()
                    if (mayRestore) {
                        val chatId = currentChatId()
                        val geometryChanged = laidOutViewportWidthPx > 0 && width > 0 &&
                            (laidOutViewportWidthPx != width ||
                                kotlin.math.abs(laidOutFontScale - resources.configuration.fontScale) > 0.01f)
                        if (preserveViewport && geometryChanged && pendingScrollRestoreChatId != chatId &&
                            chatId in userScrollLockedChats && scroll.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) {
                            // A resize can arrive after recreation's first layout has committed.
                            // Reuse its immutable reading character instead of the old pixel offset.
                            perChatScrollAnchors[chatId]?.let { beginScrollRestore(chatId, it) }
                        }
                        val anchor = pendingScrollRestoreAnchor?.takeIf { pendingScrollRestoreChatId == chatId }
                        adjustingLayoutAnchor = true
                        try {
                            if (anchor != null && (anchor.isReadingLocked || anchor.distanceFromBottomPx > dp(64))) {
                                scrollToPositionWithOffset(ChatScrollPolicy.resolveAnchorPosition(anchor, messageAdapter.committedSnapshot()), anchor.topOffsetPx)
                            } else if (pendingScrollRestoreChatId == chatId) {
                                val last = findViewByPosition(itemCount - 1)
                                if (last != null && last.height > 0 && height > 0) {
                                    val margin = (last.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                                    scrollToPositionWithOffset(itemCount - 1,
                                        height - paddingTop - paddingBottom - last.height - margin)
                                } else scrollToPosition(itemCount - 1)
                            } else if (preserveViewport && chatId in userScrollLockedChats && childCount > 0) {
                                val first = findFirstVisibleItemPosition()
                                findViewByPosition(first)?.let { view ->
                                    // A cancelled restore preserves the user's current viewport.
                                    scrollToPositionWithOffset(first, view.top - paddingTop)
                                }
                            }
                        } finally {
                            adjustingLayoutAnchor = false
                        }
                    }
                    super.onLayoutChildren(recycler, state)
                    if (!mayRestore || width <= 0 || height <= 0) return
                    val chatId = currentChatId()
                    if (pendingScrollRestoreChatId != chatId) {
                        laidOutFontScale = resources.configuration.fontScale
                        laidOutViewportWidthPx = width
                        return
                    }
                    val generation = scrollRestoreGeneration
                    val anchor = pendingScrollRestoreAnchor
                    val reading = anchor != null && (anchor.isReadingLocked || anchor.distanceFromBottomPx > dp(64))
                    val position = if (reading && anchor != null) {
                        ChatScrollPolicy.resolveAnchorPosition(anchor, messageAdapter.committedSnapshot())
                    } else itemCount - 1
                    val view = findViewByPosition(position) ?: return
                    if (view.height <= 0 || view.isLayoutRequested) return
                    if (reading && isBubbleAsyncLayoutPending(view)) return
                    val offset = if (reading && anchor != null) {
                        val lineTop = if (anchor.charOffset > 0) findLineTopForCharOffset(view, anchor.charOffset) else null
                        ChatScrollPolicy.resolveTopOffsetPx(anchor, view.height, width, resources.configuration.fontScale, lineTop)
                    } else {
                        val margin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                        height - paddingTop - paddingBottom - view.height - margin
                    }
                    if (view.top - paddingTop != offset) {
                        applyingProgrammaticScroll = true
                        adjustingLayoutAnchor = true
                        try {
                            scrollToPositionWithOffset(position, offset)
                            super.onLayoutChildren(recycler, state)
                        } finally {
                            adjustingLayoutAnchor = false
                            applyingProgrammaticScroll = false
                        }
                    }
                    val committed = findViewByPosition(position) ?: return
                    if (committed.height > 0 && !committed.isLayoutRequested &&
                        !isBubbleAsyncLayoutPending(committed)) {
                        completeScrollRestore(chatId, generation,
                            if (reading) ChatScrollMode.READING_LOCKED else ChatScrollMode.FOLLOW_LATEST)
                    }
                }

            }
            adapter = messageAdapter
            itemAnimator = null
            addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (!messageAdapter.isListCommitPending()) view.findViewWithTag<RichResponsePanel>("rich-response")
                        ?.let { applyLocalEvidenceState(it) }
                }
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
            clipToPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(12))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (applyingProgrammaticScroll) {
                        updateJumpToBottomButtonVisibility()
                        return
                    }
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        cancelScrollRestore()
                        if (!isAtBottomPixels()) userScrollLockedChats.add(currentChatId())
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE && pendingScrollRestoreChatId != currentChatId()) {
                        if (isAtBottomPixels()) userScrollLockedChats.remove(currentChatId())
                        captureCurrentScrollAnchor()
                    }
                    updateJumpToBottomButtonVisibility()
                }
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (applyingProgrammaticScroll) {
                        updateJumpToBottomButtonVisibility()
                        return
                    }
                    val draggingUp = recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING && dy < 0
                    if (draggingUp) cancelScrollRestore()
                    if (pendingScrollRestoreChatId == currentChatId()) {
                        updateJumpToBottomButtonVisibility()
                        return
                    }
                    if ((dy < -dp(2) || draggingUp) && !isAtBottomPixels()) {
                        userScrollLockedChats.add(currentChatId())
                    } else if (dy > 0 && isAtBottomPixels()) {
                        userScrollLockedChats.remove(currentChatId())
                    }
                    if (dy != 0 || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        captureCurrentScrollAnchor()
                    }
                    updateJumpToBottomButtonVisibility()
                }
            })
        }
        jumpToBottomBtn = TextView(this).apply {
            text = "↓"
            textSize = 16f
            setTextColor(Color.rgb(220, 228, 242))
            gravity = Gravity.CENTER
            alpha = 0.72f
            background = rippleRounded(Color.argb(185, 34, 38, 46), 19)
            contentDescription = "Прокрутить к ответу"
            visibility = View.GONE
            setOnClickListener { handleTwoStageJumpClick() }
        }
        val scrollContainer = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            // Transient notices overlay the lower reading area; they never move
            // the log, composer or current reading anchor.
            addView(status, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(12)
                rightMargin = dp(62)
                bottomMargin = dp(6)
            })
            addView(jumpToBottomBtn, FrameLayout.LayoutParams(dp(38), dp(38)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(14)
                bottomMargin = dp(10)
            })
        }
        root.addView(scrollContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        toolLabel = TextView(this).apply {
            visibility = View.GONE
            doAfterTextChanged { visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE }
            textSize = 12f
            setTextColor(currentTheme().accent)
            setPadding(dp(12), 0, dp(12), dp(4))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showToolDetails() }
        }
        root.addView(toolLabel)

        attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        attachScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(10), 0, dp(10), dp(6))
            visibility = View.GONE
            addView(attachRow, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(attachScroll)

        // All next-request controls stay in the composer above the keyboard.
        val composerOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), dp(3))
        }
        effortBadge = TextView(this).apply {
            textSize = 12f
            includeFontPadding = false
            gravity = Gravity.CENTER
            AppThemeViews.button(this, currentTheme())
            setTextColor(currentTheme().accent)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            contentDescription = "Усилие мышления"
            setOnClickListener { showQuickEffortPicker() }
        }
        modelBadge.setPadding(dp(9), dp(5), dp(9), dp(5))
        modelBadge.includeFontPadding = false
        modelBadge.gravity = Gravity.CENTER_VERTICAL
        AppThemeViews.button(modelBadge, currentTheme())
        modelBadge.textSize = 12f
        composerOptions.addView(modelBadge, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        composerOptions.addView(effortBadge)
        responseSettingsBtn = button("⚙") { showResponseSettings() }.apply {
            contentDescription = "Дополнительные настройки мышления и ответа"
            textSize = effortBadge.textSize / resources.displayMetrics.scaledDensity
            typeface = effortBadge.typeface
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        composerOptions.addView(responseSettingsBtn, LinearLayout.LayoutParams(dp(36), -2).apply { leftMargin = dp(4) })
        root.addView(composerOptions)
        updateEffortBadge()

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(3), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Сообщение…"
            setHintTextColor(currentTheme().hintText)
            setTextColor(currentTheme().textPrimary)
            textSize = 16f
            setPadding(dp(14),dp(11),dp(14),dp(11))
            background = rounded(Color.rgb(30,31,36),20)
            minLines = 1
            maxLines = 7
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        composer.addView(button("+") { showAttachmentSheet() }.apply {
            textSize = 22f
            contentDescription = "Добавить в чат"
            minHeight = dp(48)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) })
        composer.addView(input, LinearLayout.LayoutParams(0,-2,1f))

        composer.addView(button("🎙") { chooseVoice() }, LinearLayout.LayoutParams(dp(46),-2).apply{leftMargin=dp(6)})

        send = Button(this).apply {
            text = "➤"
            minWidth=0; minimumWidth=0
            setTextColor(Color.rgb(18,20,24))
            background = rounded(currentTheme().accent,20)
            setOnClickListener { sendMessage(false) }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(52),-2).apply{leftMargin=dp(6)})

        stop = Button(this).apply {
            text = "■"
            minWidth=0; minimumWidth=0
            setTextColor(currentTheme().textPrimary)
            background = rounded(Color.rgb(120,60,60),20)
            visibility = View.GONE
            setOnClickListener { stopCurrentResponse() }
        }
        composer.addView(stop, LinearLayout.LayoutParams(dp(52),-2).apply{leftMargin=dp(6)})
        root.addView(composer)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
            input.maxLines = if (keyboard) 3 else 7
            status.maxLines = if (keyboard) 2 else 5
            status.ellipsize = TextUtils.TruncateAt.END
            toolLabel.maxLines = if (keyboard) 1 else 4
            attachScroll.layoutParams = attachScroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            if (keyboard && currentChatId() !in userScrollLockedChats) scroll.post { scrollToLatest() }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        drawerLayout = FingerDrawerLayout(this).apply {
            swipeSurface = scroll
            setBackgroundColor(currentTheme().rootBg)
        }
        drawerLayout.addView(root, FrameLayout.LayoutParams(-1, -1))
        val sessionsPanel = buildChatDrawer()
        val panelTop = sessionsPanel.paddingTop
        val panelBottom = sessionsPanel.paddingBottom
        drawerLayout.addView(
            sessionsPanel,
            FrameLayout.LayoutParams(dp(320), -1).apply {
                gravity = GravityCompat.START
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sessionsPanel.setPadding(sessionsPanel.paddingLeft, panelTop + bars.top,
                sessionsPanel.paddingRight, panelBottom + bars.bottom)
            insets
        }
        setContentView(drawerLayout)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerVisible(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        applyTheme()
        renderRequestTelemetry()
        refreshChatDrawer()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!restoringComposer) persistComposerStateDebounced()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun showChats() {
        persistComposerStateNow()
        refreshChatDrawer()
        drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun buildChatDrawer(): LinearLayout {
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentTheme().drawerBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        drawerPanelLayout = drawer
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, dp(10))
        }
        drawerTitleView = TextView(this).apply {
            text = "Чаты"
            textSize = 20f
            setTextColor(currentTheme().textPrimary)
            setTypeface(null, Typeface.BOLD)
        }
        header.addView(drawerTitleView, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("×") { drawerLayout.closeDrawer(GravityCompat.START) })
        drawer.addView(header)
        drawerNewChatBtn = button("＋ Новый чат") {
            newChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }.apply {
            textSize = 14f
            setTextColor(Color.rgb(190,215,255))
            background = rippleRounded(Color.rgb(36,44,58), 10)
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        }
        drawer.addView(drawerNewChatBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        drawerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        drawer.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(drawerList, FrameLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return drawer
    }

    private fun refreshChatDrawer() {
        if (!::drawerList.isInitialized) return
        drawerList.removeAllViews()
        val theme = currentTheme()
        val currentId = currentChatId()
        val chats = loadAllChats()
        if (chats.isEmpty()) {
            drawerList.addView(TextView(this).apply {
                text = "Нет сохранённых чатов"
                textSize = 13f
                setTextColor(theme.textSecondary)
                setPadding(dp(8), dp(14), dp(8), dp(14))
            })
            return
        }
        chats.forEach { chatItem ->
            val id = chatItem.optString("id")
            val row = TextView(this).apply {
                text = chatItem.optString("title", "Новый чат")
                textSize = 14.5f
                setTextColor(theme.textPrimary)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                background = rippleRounded(
                    if (id == currentId) theme.drawerSelectedBg else Color.TRANSPARENT,
                    10
                )
                setOnClickListener {
                    switchChat(id)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                setOnLongClickListener {
                    showChatActions(chatItem)
                    true
                }
                contentDescription = "Чат: " + chatItem.optString("title", "Новый чат")
            }
            drawerList.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(3)
            })
        }
    }

    // Compact user-facing session telemetry card.
    private fun buildRequestTelemetryCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
            background = null
        }
        requestTelemetrySummary = TextView(this).apply {
            textSize = 12f
            setTextColor(currentTheme().textSecondary)
            tag = "request-log-preview"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                requestTelemetryExpanded = !requestTelemetryExpanded
                renderRequestTelemetry()
            }
            setOnLongClickListener {
                copyText("Диагностика сессии", text.toString())
                true
            }
        }
        val contextHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contextHeader.addView(requestTelemetrySummary, LinearLayout.LayoutParams(0, -2, 1f))
        requestContextBadge = TextView(this).apply {
            textSize = 10.5f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            tag = "context-status-badge"
            contentDescription = "Проверить контекст и фактическое использование кэша"
            setOnClickListener { showContextInspector() }
        }
        contextHeader.addView(requestContextBadge, LinearLayout.LayoutParams(-2, -2))
        card.addView(contextHeader)
        requestTelemetryHistory = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        requestTelemetryScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            isFillViewport = false
            addView(requestTelemetryHistory)
        }
        card.addView(requestTelemetryScroll, LinearLayout.LayoutParams(-1, dp(176)))
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(6)
            }
        }
    }

    private fun renderRequestTelemetry() {
        if (!::requestTelemetrySummary.isInitialized) return
        val accounts = try {
            chatDb.listRequestAccounting(currentChatId()).associateBy { it.optString("local_request_id") }
        } catch (_: Exception) { emptyMap() }
        val events = (try { chatDb.listRequestEvents(currentChatId(), 50) }
            catch (_: Exception) { emptyList() }).map { event ->
            RequestStopEventPolicy.enrich(event, accounts[event.optString("request_id")])
        }
        val active = chatDb.getActiveResponse(currentChatId())
        val activeAccount = active?.let { accounts[it.optString("request_id")] }
        val theme = currentTheme()
        if (::requestContextBadge.isInitialized) {
            val latest = ContextLastRequestPolicy.selectLatestCompletedRequest(accounts.values.toList(), events)
            val previous = latest?.accounting
            fun known(key: String) = previous?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.toString() ?: "?"
            requestContextBadge.text = (contextBadgeMode ?: "Контекст · проверка") + "\nкэш ↓${known("cached_tokens")} ↑${known("cache_write_tokens")}"
            requestContextBadge.contentDescription = "Контекст следующего запроса; кэш последнего ответа: чтение ${known("cached_tokens")}, запись ${known("cache_write_tokens")}. Нажмите для проверки."
            requestContextBadge.setTextColor(theme.accent)
            requestContextBadge.background = RippleDrawable(ColorStateList.valueOf(theme.divider),
                AppThemeViews.surface(this, theme.logBg, theme.surfaceBorder), null)
            requestContextSnapshot(false)
        }
        requestTelemetrySummary.background = RippleDrawable(ColorStateList.valueOf(theme.divider),
            AppThemeViews.surface(this, theme.logBg, theme.surfaceBorder), null)
        requestTelemetryHistory.removeAllViews()
        if (events.isEmpty() && active == null) {
            requestTelemetrySummary.text = "● Нет запросов для этой сессии"
            requestTelemetrySummary.setTextColor(currentTheme().hintText)
            requestTelemetryHistory.visibility = View.GONE
            requestTelemetryScroll.visibility = View.GONE
            return
        }
        requestTelemetrySummary.text = if (requestTelemetryExpanded) "Журнал запросов · ${events.size} событий  ▴"
            else if (active != null) RequestEventFormatter.progress(active, activeAccount) + "  ▾"
            else RequestEventFormatter.summary(events.first()) + "  ▾"
        requestTelemetrySummary.setTextColor(theme.logText)
        requestTelemetryHistory.visibility = if (requestTelemetryExpanded) View.VISIBLE else View.GONE
        requestTelemetryScroll.visibility = if (requestTelemetryExpanded) View.VISIBLE else View.GONE
        if (!requestTelemetryExpanded) return
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(5))
        }
        actions.addView(TextView(this).apply {
            text = "Последние " + events.size + " событий"
            textSize = 11.5f
            setTextColor(currentTheme().textSecondary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(TextView(this).apply {
            text = "Копировать всё"
            textSize = 11.5f
            setTextColor(currentTheme().accent)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { copyText("История запросов", events.asReversed().joinToString("\n\n") { RequestEventFormatter.details(it) }) }
        })
        requestTelemetryHistory.addView(actions)
        events.asReversed().forEach { event ->
            requestTelemetryHistory.addView(buildRequestEventView(event), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        requestTelemetryScroll.post { if (requestTelemetryExpanded) requestTelemetryScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showContextInspector() = requestContextSnapshot(true)

    private fun requestContextSnapshot(openDialog: Boolean) {
        if (!::requestContextBadge.isInitialized || !::input.isInitialized || executor.isShutdown || isFinishing || isDestroyed) return
        if (!openDialog && contextDialogPending) { contextBadgeDirty = true; return }
        if (!openDialog && contextBadgeInFlight) {
            contextBadgeDirty = true
            ++contextInspectionSerial
            return
        }
        if (openDialog) contextDialogPending = true else {
            contextBadgeInFlight = true
            contextBadgeDirty = false
        }
        val serial = ++contextInspectionSerial
        val sourceChat = currentChatId()
        val generation = activeResponseState.state.generation
        val configuration = try { captureContextConfiguration() } catch (_: IllegalArgumentException) {
            contextBadgeInFlight = false
            contextDialogPending = false
            requestContextBadge.text = "Выберите модель Claude"
            if (openDialog) status.text = "Сохранённая модель недоступна. Выберите Claude в списке моделей."
            return
        }
        val capturedHistory = ContextHistorySnapshot.capture(chat,
            if (::messageAdapter.isInitialized) messageAdapter.snapshot() else emptyList())
        val history = capturedHistory.messages
        val draft = JSONObject().put("role", "user").put("text", input.text.toString().trim()).apply {
            put("file_paths", JSONArray(attachedFilePaths.toList()))
            put("file_names", JSONArray(attachedFileNames.toList()))
            put("file_mimes", JSONArray(attachedFileMimes.toList()))
            put("attachments", JSONArray().apply { attachedOpenRouterAttachments.forEach { put(it.toJson()) } })
            pendingImagePath?.let { put("image_path", it) }
            pendingImageName?.let { put("image_name", it) }
        }
        val hasDraft = JsonProtocol.string(draft, "text").isNotEmpty() || attachedFilePaths.isNotEmpty() ||
            attachedOpenRouterAttachments.isNotEmpty() || pendingImagePath != null
        val inspectionHistory = if (hasDraft) history + draft else history
        val blocked = when {
            pendingAttachmentChange.get() != null -> "Отправка ожидает обновления вложения."
            preparingZipAttachments || pendingUploadUris.any { it.second == "zip" } -> "Перед отправкой требуется подготовить ZIP."
            pendingUploads.get() > 0 -> "Отправка ожидает загрузки вложений."
            stop.visibility == View.VISIBLE -> "Сначала завершите или остановите текущий запрос."
            !hasDraft -> "Текст нового сообщения и вложения пока не выбраны."
            else -> ""
        }
        executor.execute {
            try {
                if (openDialog) contextInspectionPreparationHook?.invoke() else contextBadgePreparationHook?.invoke()
                val localArtifacts = chatDb.listLocalFiles(sourceChat)
                val accounts = chatDb.listRequestAccounting(sourceChat)
                val events = chatDb.listRequestEvents(sourceChat, 100)
                val lastRequest = ContextLastRequestPolicy.selectLatestCompletedRequest(accounts, events)
                val key = secure.getOpenRouterApiKey()
                val localCandidates = ContextRequestPolicy.localToolCandidates(inspectionHistory, localArtifacts)
                val tools = ContextRequestPolicy.openRouterTools(
                    configuration.github, configuration.webSearch, configuration.webFetch
                )
                val names = mutableListOf<String>()
                inspectionHistory.forEach { message ->
                    message.optJSONArray("file_names")?.let { a -> for (i in 0 until a.length())
                        JsonProtocol.text(a, i)?.takeIf { it.isNotBlank() }?.let(names::add) }
                    JsonProtocol.nonBlank(message, "image_name")?.let(names::add)
                    message.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length())
                        a.optJSONObject(i)?.let { JsonProtocol.nonBlank(it, "name") }?.let(names::add) }
                }
                names.addAll(localArtifacts.mapNotNull { JsonProtocol.nonBlank(it, "filename") })
                var prefix: Boolean? = null
                var prefixReason: String? = null
                val format = OpenRouterStructuredOutputPolicy.format(configuration.responseFormat, configuration.schema)
                    val durable = chatDb.getChat(sourceChat)?.optJSONArray("messages")
                    val durableRows = durable?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }.orEmpty()
                    val exactHistory = capturedHistory.validatedDurableHistory(durableRows)
                    val prefixHistory = exactHistory.orEmpty()
                    val byMessage = prefixHistory.map { message ->
                        message.optJSONArray("attachments")?.let { a -> (0 until a.length()).mapNotNull {
                            a.optJSONObject(it)?.let(OpenRouterLocalAttachment::fromJson)
                        } }.orEmpty()
                    }
                    val requiresPreparation = prefixHistory.withIndex().any { (index, message) ->
                        val paths = byMessage[index].map { it.path }.toSet()
                        val legacy = message.optJSONArray("file_paths")
                        (legacy != null && (0 until legacy.length()).any { JsonProtocol.string(legacy, it) !in paths }) ||
                            JsonProtocol.nonBlank(message, "image_path")?.let { it !in paths } == true
                    } || configuration.compatibleImages && byMessage.flatten().any { it.kind == "image" }
                    if (exactHistory == null) prefixReason = "Точное сравнение префикса ожидает согласованного сохранённого снимка истории. Текущие настройки и черновик учтены."
                    else if (requiresPreparation) prefixReason = "Точное сравнение префикса ожидает подготовки вложений."
                    else try {
                        OpenRouterContextSnapshot.build(exactHistory, OpenRouterContextSnapshot.systemContent(configuration.instructions,
                            configuration.github, configuration.githubRepo, configuration.githubBranches), byMessage,
                            protocolPrefix = { _, matches -> prefix = matches })
                } catch (_: IOException) {
                        prefixReason = "Не все вложения доступны для сравнения префикса."
                    }
                val visibleTools = ContextStatusPolicy.toolNames(tools)
                val snapshot = ContextStatusPolicy.build(
                    model = configuration.model,
                    history = history,
                    expectedOpenRouterBinding = OpenRouterProtocolCodec.binding(
                        configuration.model, tools, key, format, configuration.route
                    ),
                    route = configuration.route,
                    cacheMode = configuration.cacheMode,
                    attachmentNames = names,
                    tools = visibleTools,
                    accounting = null,
                    lastResponseAtMillis = null,
                    nowMillis = System.currentTimeMillis(),
                    openRouterPrefixMatches = prefix,
                    openRouterPrefixReason = prefixReason,
                    lastRequest = lastRequest
                ).let { base ->
                    base.copy(
                        messages = inspectionHistory.size,
                        textCharacters = inspectionHistory.sumOf { JsonProtocol.string(it, "text").length.toLong() },
                        explanation = listOf(base.explanation, blocked,
                            if (localCandidates) "Локальные инструменты будут доступны после успешной проверки файлов." else "")
                            .filter { it.isNotBlank() }.joinToString(" ")
                    )
                }
                runOnUiThread {
                    if (openDialog && serial == contextInspectionSerial) contextDialogPending = false
                    if (!openDialog) contextBadgeInFlight = false
                    if (serial != contextInspectionSerial || sourceChat != currentChatId() ||
                        generation != activeResponseState.state.generation || !activityVisible || isFinishing || isDestroyed) {
                        if ((!openDialog || contextBadgeDirty) && activityVisible && !isFinishing && !isDestroyed) requestContextSnapshot(false)
                        return@runOnUiThread
                    }
                    contextBadgeMode = snapshot.badgeText
                    fun known(value: Long?) = value?.toString() ?: "?"
                    requestContextBadge.text = snapshot.badgeText + "\nкэш ↓${known(snapshot.cacheReadTokens)} ↑${known(snapshot.cacheWriteTokens)}"
                    requestContextBadge.contentDescription = snapshot.details()
                    if (openDialog) {
                        ContextInspectorDialog.show(this, currentTheme(), snapshot)
                        contextInspectionCompletedHook?.invoke()
                    }
                    if (contextBadgeDirty) requestContextSnapshot(false)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (openDialog && serial == contextInspectionSerial) contextDialogPending = false
                    if (!openDialog) contextBadgeInFlight = false
                    if (serial == contextInspectionSerial && sourceChat == currentChatId() &&
                        generation == activeResponseState.state.generation && !isFinishing && !isDestroyed) {
                        if (openDialog) status.text = "Не удалось проверить контекст. Проверьте настройки и доступность файлов."
                        else { contextBadgeMode = null; requestContextBadge.text = "Контекст · проверка недоступна" }
                    }
                    if (contextBadgeDirty && !isFinishing && !isDestroyed) requestContextSnapshot(false)
                }
            }
        }
    }

    private fun buildRequestEventView(event: JSONObject): View {
        val eventChatId = currentChatId()
        val eventGeneration = activeResponseState.state.generation
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "request-log-event"
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = AppThemeViews.surface(this@MainActivity, currentTheme().logBg, currentTheme().divider)
        }
        var details: TextView? = null
        val summary = TextView(this).apply {
            text = RequestEventFormatter.summary(event)
            textSize = 11.5f
            setTextColor(requestEventColor(RequestEventFormatter.match(event)))
            setTextIsSelectable(true)
            setOnClickListener { details?.visibility = if (details?.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        wrapper.addView(summary)
        details = TextView(this).apply {
            text = RequestEventFormatter.details(event)
            textSize = 11f
            setTextColor(currentTheme().textSecondary)
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
            setTypeface(Typeface.MONOSPACE)
            visibility = View.GONE
        }
        wrapper.addView(details)
        wrapper.addView(TextView(this).apply {
            text = "Копировать"
            textSize = 11f
            setTextColor(currentTheme().accent)
            setPadding(dp(2), dp(5), dp(2), 0)
            setOnClickListener { copyText("Событие запроса", RequestEventFormatter.details(event)) }
        })
        val localRequestId = event.optString("local_request_id").ifBlank { event.optString("request_id") }
        if (localRequestId.isNotBlank()) {
            val sourceMessage = JSONObject()
            LegacyOpenAiCheckpoint.attachRawSourceToMessage(sourceMessage, filesDir, eventChatId, localRequestId)
            sourceMessage.optJSONArray("annotations")?.let { sources ->
                wrapper.addView(fileActionButton("Сохранённые исходные данные") {
                    if (currentChatId() != eventChatId ||
                        activeResponseState.state.generation != eventGeneration) return@fileActionButton
                    val content = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    renderAnnotations(sources, content, sourceMessage, eventChatId, eventGeneration)
                    AppThemeViews.dialog(this, currentTheme()).setTitle("Полученные данные ответа")
                        .setView(ScrollView(this).apply { addView(content) })
                        .setPositiveButton("Закрыть", null).show()
                })
            }
        }
        wrapper.setOnLongClickListener {
            copyText("Событие запроса", RequestEventFormatter.details(event))
            true
        }
        return wrapper
    }

    private fun requestEventColor(match: ModelCatalog.Match): Int = when (match) {
        ModelCatalog.Match.MISMATCH -> currentTheme().errorText
        else -> currentTheme().logText
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        status.text = "Скопировано"
    }

    private fun manageChats(all: List<JSONObject>) {
        val names = all.map { it.optString("title","Новый чат") }
        if (names.isEmpty()) return
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Управление чатами")
            .setItems(names.toTypedArray()) { _, which ->
                showChatActions(all[which])
            }.show()
    }

    private fun showChatActions(c: JSONObject) {
        AppThemeViews.dialog(this, currentTheme())
            .setTitle(c.optString("title","Чат"))
            .setItems(arrayOf("Переименовать","Экспорт диалога","Удалить")) { _, which ->
                when (which) {
                    0 -> renameChat(c.optString("id"))
                    1 -> showConversationExport(c.optString("id"))
                    2 -> deleteChat(c.optString("id"))
                }
            }.show()
    }

    private fun showConversationExport(sourceChatId: String) {
        val formats = DialogConversationExporter.Format.values()
        AppThemeViews.dialog(this, currentTheme()).setTitle("Экспорт диалога")
            .setItems(formats.map { it.label }.toTypedArray()) { _, index ->
                exportConversation(sourceChatId, formats[index])
            }.show()
    }

    private fun exportConversation(sourceChatId: String, format: DialogConversationExporter.Format) {
        if (pendingExportPath != null || !conversationExportPreparing.compareAndSet(false, true)) {
            status.text = "Сначала завершите текущий экспорт"
            return
        }
        executor.execute {
            var snapshot: File? = null
            try {
                // History and active partial must belong to the same SQLite snapshot.
                val db = chatDb.readableDatabase
                val source: JSONObject
                val active: JSONObject?
                db.beginTransactionNonExclusive()
                try {
                    source = chatDb.getChat(sourceChatId) ?: throw IOException("Чат удалён")
                    active = chatDb.getActiveResponse(sourceChatId)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                exportSnapshots.cleanup()
                val file = exportSnapshots.create(format)
                snapshot = file
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    DialogConversationExporter.writeTo(writer, source.optString("title"),
                        source.optJSONArray("messages") ?: JSONArray(), active, format)
                }
                runOnUiThread {
                    conversationExportPreparing.set(false)
                    if (!activityVisible || isFinishing || isDestroyed) {
                        exportSnapshots.delete(file.path)
                        return@runOnUiThread
                    }
                    pendingExportPath = file.absolutePath
                    launchActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = format.mimeType
                        putExtra(Intent.EXTRA_TITLE, "integral_dialog_${System.currentTimeMillis()}.${format.extension}")
                    }, pickExportTxt)
                }
            } catch (_: Exception) {
                exportSnapshots.delete(snapshot?.path)
                conversationExportPreparing.set(false)
                runOnUiThread { if (activityVisible && !isDestroyed) status.text = "Не удалось подготовить экспорт диалога" }
            }
        }
    }

    private fun renameChat(id: String) {
        val chats = loadAllChats().toMutableList()
        val found = chats.firstOrNull { it.optString("id")==id } ?: return
        val edit = EditText(this).apply {
            setText(found.optString("title"))
            setTextColor(currentTheme().textPrimary)
        }
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Название")
            .setView(edit)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить") { _, _ ->
                found.put("title", edit.text.toString().trim().ifBlank { "Чат" })
                chatDb.renameChat(id, found.optString("title"))
                if (currentChatId()==id) title.text = found.optString("title")
                refreshChatDrawer()
            }.show()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun deleteChat(id: String) {
        val active=try{activeResponsePersistence.getActiveResponse(id)}catch(_:Exception){null}
        val requestId=active?.optString("request_id").takeIf{!it.isNullOrBlank() && it!="null"}
        val localPaths=linkedSetOf<String>().apply{
            try{addAll(chatDb.listLocalFilePaths(id))}catch(_:Exception){}
            addAll(listComposerLocalFilePaths(id))
        }
        if(currentChatId()==id){
            activeResponseState.nextGeneration()
            cancelOpenRouterRequest()
            openRouterModelPreflightCall.getAndSet(null)?.cancel()
            cancelPendingGitHubConfirmation()
        }
        requestId?.let { OpenRouterResponseRuntime.cancel(it, "Чат удалён") }
        OpenRouterResponseRuntime.cancelForChat(id, "Чат удалён")
        activeResponsePersistence.clearActiveResponse(id, expectedRequestId = requestId)
        perChatScrollAnchors.remove(id)
        userScrollLockedChats.remove(id)
        synchronized(composerMergeLock) {
            clearComposerState(id)
            chatDb.deleteChat(id)
        }
        val remainingOwners: Set<String>? = try {
            val owners = attachmentOwners()
            owners.durable + owners.active
        } catch (e: Exception) {
            diagnostics.log("local_file_delete_deferred", mapOf(
                "chat_id" to id,
                "error_type" to e.javaClass.simpleName
            ))
            null
        }
        if (remainingOwners != null) {
            localPaths.filter { it !in remainingOwners }.forEach(::deleteOwnedLocalFile)
        }
        if (currentChatId()==id) {
            val newId = newChatId()
            prefs.edit().putString("current_chat",newId).apply()
            displayedChatId = newId
            cancelScrollRestore()
            chat.clear()
            currentResponseId=null
            baselineEffort=null
            clearAttachedSelection()
            refreshAttachChips()
            messageAdapter.clearItems()
            toolLabel.text=""
            title.text="Новый чат"
            lastUserText = ""
            restoringComposer = true
            try { input.setText("") } finally { restoringComposer = false }
            toolStates.clear(); renderToolActivity()
            ensureChatEntry(newId,"Новый чат")
            setBusy(false)
            status.text="Новый чат"
            requestTelemetryExpanded = false
        }
        renderRequestTelemetry()
        refreshChatDrawer()
    }

    private fun normalizedProviderId(): String = "openrouter"

    private fun normalizedModelId(): String = try {
        ModelCatalog.normalizedClaudeModel(prefs.getString("model", null))
    } catch (_: IllegalArgumentException) {
        prefs.getString("model", null)?.trim().orEmpty()
    }

    private fun updateModelBadge() {
        if (!::modelBadge.isInitialized) return
        val source = ModelCatalog.source("openrouter")
        val savedModel = normalizedModelId()
        val modelLabel = if (OpenRouterClaudeRoute.isClaude(savedModel)) ModelCatalog.displayName(source.id, savedModel)
            else "$savedModel · выберите Claude"
        modelBadge.text = "${source.label} · ${modelLabel} ▾"
        modelBadge.contentDescription = "Источник API: ${source.label}. Модель: ${modelLabel}. Выбрать модель"
        updateEffortBadge()
        if (::requestContextBadge.isInitialized) renderRequestTelemetry()
    }

    private fun updateEffortBadge(resolvedEffort: String? = null) {
        if (!::effortBadge.isInitialized) return
        effortBadge.text = ModelRequestPolicy.effortBadge(
            prefs.getString("reasoning", "high") ?: "high", resolvedEffort
        )
    }

    private fun showModelPicker() {
        val source = ModelCatalog.source("openrouter")
        val normalized = normalizedModelId()
            val choices = source.models.toMutableList()
        if (OpenRouterClaudeRoute.isClaude(normalized) && choices.none { it.id == normalized }) {
                choices.add(0, ModelOption(normalized, "Текущая: $normalized"))
            }
        val labels = choices.map { it.label }.toTypedArray()
        val selected = choices.indexOfFirst { it.id == normalized }.coerceAtLeast(0)

        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Модель (${source.label})")
            .setSingleChoiceItems(labels, selected) { dialog, position ->
                val selectedModel = choices.getOrElse(position) { source.models.first() }.id
                prefs.edit()
                    .putString("provider", source.id)
                    .putString("provider_label", source.label)
                    .putString("model", selectedModel)
                    .apply()
                updateModelBadge()
                status.text = "Модель для следующего запроса выбрана"
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- Settings ----------
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun showQuickEffortPicker() {
        val efforts = arrayOf("auto", "low", "medium", "high", "xhigh", "max")
        val current = prefs.getString("reasoning", "high") ?: "high"
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("⚡ Усилие мышления")
            .setSingleChoiceItems(efforts, efforts.indexOf(current).coerceAtLeast(0)) { dialog, position ->
                prefs.edit().putString("reasoning", efforts[position]).apply()
                updateEffortBadge()
                status.text = "Усилие для следующего запроса: " + efforts[position]
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyDialogControlTheme(root: ViewGroup, theme: ThemeColors) {
        AppThemeViews.content(root, theme)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun showResponseSettings() {
        val model = normalizedModelId()
        val theme = currentTheme()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.rootBg)
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        fun sectionHeader(titleText: String) {
            box.addView(TextView(this).apply {
                text = titleText
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.accent)
                setPadding(0, dp(14), 0, dp(4))
            })
        }
        fun spinner(label: String, values: Array<String>, key: String, default: String): Spinner {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(10), 0, dp(4))
            })
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            sp.setSelection(values.indexOf(prefs.getString(key, default)).coerceAtLeast(0))
            box.addView(sp)
            return sp
        }
        fun chk(label: String, pref: String, def: Boolean = false) = CheckBox(this).apply {
            text = label
            setTextColor(theme.textPrimary)
            isChecked = prefs.getBoolean(pref, def)
        }

        sectionHeader("Мышление и формат (OpenRouter / Claude)")
        val efforts = arrayOf("auto","low","medium","high","xhigh","max")
        val effort = spinner("Усилие мышления", efforts, "reasoning", "high")
        box.addView(TextView(this).apply {
            text = "auto: приложение выбирает усилие по запросу; xhigh/max не понижаются."
            setTextColor(theme.textSecondary)
            textSize = 11.5f
        })
        val verbosities = arrayOf("low","medium","high")
        val verbosity = spinner("Подробность ответа", verbosities, "verbosity", "medium")
        val canSetVerbosity = OpenRouterReasoningPolicy.supportsResponseVerbosity(model)
        verbosity.isEnabled = canSetVerbosity
        if (!canSetVerbosity) box.addView(TextView(this).apply {
            text = "Claude: verbosity отключена, иначе OpenRouter может переопределить выбранное усилие."
            textSize = 11.5f
            setTextColor(theme.textSecondary)
            setPadding(0, dp(3), 0, dp(6))
        })
        box.addView(TextView(this).apply {
            text = "Max output tokens (пусто = выбирает API)"
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })
        val max = EditText(this).apply {
            hint = "Max output tokens"
            setTextColor(theme.textPrimary)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("max_output", "").orEmpty().takeUnless {
                it == "128000" && !prefs.getBoolean("max_output_explicit", false)
            }.orEmpty())
        }
        box.addView(max)
        val presentation = chk("Соглашения оформления Markdown, кода и файлов", "response_presentation_instructions", true)
        box.addView(presentation)
        val scientificPreview = chk("Офлайн-превью формул и диаграмм", "response_scientific_preview", true)
        box.addView(scientificPreview)
        val formats = arrayOf("text","json_object","json_schema")
        val format = spinner("Формат ответа", formats, "openrouter_text_format", "text")
        val schema = EditText(this).apply {
            hint = "JSON Schema (только для json_schema)"
            setTextColor(theme.textPrimary)
            setText(prefs.getString("openrouter_json_schema", "").orEmpty())
            minLines = 2
        }
        box.addView(schema)

        sectionHeader("Инструменты, маршрут и кэш OpenRouter")
        val openRouterWebSearch = chk("OpenRouter: Web Search", "openrouter_web_search", false).also { box.addView(it) }
        val openRouterWebFetch = chk("OpenRouter: Web Fetch", "openrouter_web_fetch", false).also { box.addView(it) }
        val githubTools = chk("Разрешить GitHub-инструменты", "github_tools", false).also { box.addView(it) }
        val claudeRoute = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Автоматический: с резервными провайдерами", "Прямой Anthropic: без резервного провайдера"))
            setSelection(if (OpenRouterClaudeRoute.fromPreference(prefs.getString("openrouter_claude_route", null)) == OpenRouterClaudeRoute.ANTHROPIC) 1 else 0)
        }.also {
            box.addView(TextView(this).apply {
                text = "Маршрут Claude (по умолчанию Прямой Anthropic)"
                textSize = 12f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(10), 0, dp(4))
            })
            box.addView(it)
        }
        val cacheMode = spinner("Кэш промпта Claude (1h / 5m / off)", arrayOf("1h", "5m", "off"), "openrouter_cache_mode", "1h")
        val pdfEngine = spinner("Движок PDF OpenRouter", arrayOf("native", "auto", "cloudflare-ai", "mistral-ocr"), "openrouter_pdf_engine", "native")
        val imageMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Исходное изображение", "Совместимая копия JPEG"))
            setSelection(if (prefs.getString("openrouter_image_mode", "original") == "compatible") 1 else 0)
        }.also {
            box.addView(TextView(this).apply {
                text = "Передача фото OpenRouter"
                textSize = 12f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(10), 0, dp(4))
            })
            box.addView(it)
        }

        val parallelTools = chk("Parallel tool calls", "parallel_tools", true)
        box.addView(parallelTools)

        sectionHeader("Инструкции модели")
        val systemPromptInput = EditText(this).apply {
            hint = "Системный промпт"
            setTextColor(theme.textPrimary)
            setText(prefs.getString("system_prompt", "").orEmpty())
            minLines = 2
        }
        box.addView(systemPromptInput)
        val developerInstructionsInput = EditText(this).apply {
            hint = "Developer instructions"
            setTextColor(theme.textPrimary)
            setText(prefs.getString("instructions", "").orEmpty())
            minLines = 2
        }
        box.addView(developerInstructionsInput)

        applyDialogControlTheme(box, theme)
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Настройки модели и ответа")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("reasoning", efforts[effort.selectedItemPosition])
                    .putString("verbosity", verbosities[verbosity.selectedItemPosition])
                    .putString("max_output", max.text.toString().trim())
                    .putBoolean("max_output_explicit", max.text.toString().trim().isNotEmpty())
                    .putBoolean("response_presentation_instructions", presentation.isChecked)
                    .putBoolean("response_scientific_preview", scientificPreview.isChecked)
                    .putBoolean("parallel_tools", parallelTools.isChecked)
                    .putString("system_prompt", systemPromptInput.text.toString())
                    .putString("instructions", developerInstructionsInput.text.toString())
                    .putBoolean("openrouter_web_search", openRouterWebSearch.isChecked)
                    .putBoolean("openrouter_web_fetch", openRouterWebFetch.isChecked)
                    .putBoolean("github_tools", githubTools.isChecked)
                    .putString("openrouter_claude_route", if (claudeRoute.selectedItemPosition == 1) "anthropic" else "auto")
                    .putString("openrouter_cache_mode", cacheMode.selectedItem.toString())
                    .putString("openrouter_pdf_engine", pdfEngine.selectedItem.toString())
                    .putString("openrouter_image_mode", if (imageMode.selectedItemPosition == 1) "compatible" else "original")
                    .putString("openrouter_text_format", formats[format.selectedItemPosition])
                    .putString("openrouter_json_schema", schema.text.toString())
                    .apply()
                updateEffortBadge()
                renderRequestTelemetry()
                status.text = "Настройки модели сохранены для следующего запроса"
            }
            .show()
    }

    private fun showDiagnostics() {
        val diagnosticsChatId = currentChatId()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val buildLabel = TextView(this).apply {
            text = "Версия " + BuildConfig.VERSION_NAME + " · Git " + BuildConfig.BUILD_GIT_SHA
            setTextColor(currentTheme().textSecondary)
            textSize = 12f
            setTextIsSelectable(true)
        }
        container.addView(buildLabel)
        val events = TextView(this).apply {
            text = "Загрузка последних событий…"
            setTextColor(currentTheme().textPrimary)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val eventScroll = ScrollView(this).apply {
            addView(events)
            isFillViewport = true
        }
        container.addView(eventScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var rawEvents = ""
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Копировать всё") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Astra diagnostics", rawEvents))
            status.text = "Диагностика скопирована"
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(button("Экспорт") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Version: " + BuildConfig.VERSION_NAME +
                    "\nGit: " + BuildConfig.BUILD_GIT_SHA + "\n" + rawEvents)
            }, "Экспорт диагностики"))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(button("В конец") {
            eventScroll.post { eventScroll.fullScroll(View.FOCUS_DOWN) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        container.addView(actions)
        applyDialogControlTheme(container, currentTheme())
        val diagnosticsDialog = AppThemeViews.dialog(this, currentTheme())
            .setTitle("Диагностика запросов")
            .setView(container)
            .setNegativeButton("Закрыть", null)
            .create()
        diagnosticsDialog.show()
        diagnosticsDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.75).toInt())
        executor.execute {
            val read = diagnostics.tail(300)
            val recent = try { chatDb.listRequestEvents(diagnosticsChatId, 10) } catch (_: Exception) { emptyList() }
            val accounts = try {
                val requestIds = recent.mapNotNull { JsonProtocol.nonBlank(it, "request_id") }.toSet()
                chatDb.listRequestAccounting(diagnosticsChatId, requestIds)
                    .associateBy { JsonProtocol.string(it, "local_request_id") }
            } catch (_: Exception) { emptyMap() }
            val enriched = recent.map { event ->
                RequestStopEventPolicy.enrich(event, accounts[JsonProtocol.string(event, "request_id")])
            }
            val summary = RequestDiagnosticsSummary.format(enriched)
            runOnUiThread {
                if (isFinishing || isDestroyed || !diagnosticsDialog.isShowing ||
                    currentChatId() != diagnosticsChatId) return@runOnUiThread
                // Display, Copy and Export share the same redaction boundary.
                rawEvents = diagnostics.redactDisplayText(summary + read)
                events.text = diagnostics.redactDisplayText(summary + formatDiagnostics(read))
                eventScroll.post { eventScroll.fullScroll(View.FOCUS_UP) }
            }
        }
    }

    private fun formatDiagnostics(raw: String): String {
        val fieldPattern = Regex("""(?:^|\s)([A-Za-z][A-Za-z_0-9]*)=""")
        return raw.lines().filter { it.isNotBlank() }.joinToString("\n\n") { line ->
            if (line.length < 24) return@joinToString line
            val stamp = line.take(23)
            val rest = line.drop(24)
            val event = rest.substringBefore(' ')
            val body = rest.drop(event.length).trim()
            val fields = fieldPattern.findAll(body).toList()
            buildString {
                append("● ").append(stamp).append(" · ").append(event.replace('_', ' '))
                fields.forEachIndexed { index, match ->
                    val from = match.range.last + 1
                    val to = fields.getOrNull(index + 1)?.range?.first ?: body.length
                    val value = body.substring(from, to).trim()
                    append("\n   ").append(match.groupValues[1].replace('_', ' '))
                        .append(": ").append(value)
                }
                if (fields.isEmpty() && body.isNotBlank()) append("\n   ").append(body)
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun showSettings() {
        val theme = currentTheme()
        val cardBg = theme.cardBg
        val inputBg = theme.inputBg
        val buttonBg = theme.buttonBg
        val accent = theme.accent
        val subtext = theme.textSecondary
        val divider = theme.divider

        val box = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(theme.rootBg)
            setPadding(dp(14),dp(6),dp(14),dp(10))
        }

        fun section(titleText:String){
            box.addView(TextView(this).apply{
                this.text=titleText
                textSize=12.5f
                setTypeface(null,Typeface.BOLD)
                setTextColor(accent)
                setPadding(dp(2),dp(18),0,dp(8))
            })
            box.addView(View(this).apply{ setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1,dp(1)))
        }
        fun spaced(v:View){ box.addView(v, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(10) }) }

        fun field(hint:String,value:String,secret:Boolean=false) = EditText(this).apply {
            this.hint=hint; setText(value); setTextColor(theme.textPrimary); setHintTextColor(theme.hintText)
            setPadding(dp(12),dp(11),dp(12),dp(11))
            background=rounded(inputBg)
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun labeled(caption:String,view:View){
            box.addView(TextView(this).apply{this.text=caption;textSize=12f;setTextColor(subtext);setPadding(dp(2),dp(10),0,dp(4))})
            box.addView(view)
        }

        section("Подключение OpenRouter")
        val openRouterKey = field("OpenRouter API key", secure.getOpenRouterApiKey(), true); spaced(openRouterKey)

        section("Оформление и уведомления")
        val themeIds = AppThemePalette.themes
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, AppThemePalette.labels)
            val currentThemeId = prefs.getString("app_theme", "dark") ?: "dark"
            setSelection(themeIds.indexOf(currentThemeId).coerceAtLeast(0))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(cardBg)
        }
        labeled("Тема оформления Integral LLM", themeSpinner)
        val backgroundResponses = CheckBox(this).apply {
            text = "Продолжать генерацию ответа в фоне"
            contentDescription = "Продолжать генерацию ответа в фоне"
            setTextColor(theme.textPrimary)
            isChecked = prefs.getBoolean("background_responses", true)
            setPadding(dp(4), dp(8), 0, dp(6))
        }
        box.addView(backgroundResponses)
        val notifications = CheckBox(this).apply {
            text = "Уведомлять о готовом ответе"
            setTextColor(theme.textPrimary)
            isChecked = prefs.getBoolean("response_notifications", true)
            setPadding(dp(4), dp(8), 0, dp(6))
        }
        box.addView(notifications)

        section("GitHub")
        val githubToken = field("GitHub fine-grained PAT", secure.getGitHubToken(), true); spaced(githubToken)
        val githubRepo = field("GitHub repository allow-list", prefs.getString("github_repo", "luoecrt-pixel/OpenAIApiChat") ?: "luoecrt-pixel/OpenAIApiChat"); spaced(githubRepo)
        val githubBranches = field("Allowed branches (через запятую)", prefs.getString("github_branches", "main") ?: "main"); spaced(githubBranches)
        box.addView(TextView(this).apply {
            text = "PAT хранится в Android Keystore/AES-GCM и модели не передаётся. Любая запись в GitHub требует подтверждения."
            setTextColor(subtext); textSize = 11.5f; setPadding(dp(2), dp(4), 0, dp(8))
        })

        section("Файлы и диагностика")
        spaced(button("📁 Локальные файлы") { showLocalFiles() }.apply {
            setTextColor(accent); background=rounded(buttonBg)
        })
        spaced(button("Поддерживаемые файлы и способ обработки") {
            AppThemeViews.dialog(this, currentTheme()).setTitle("Форматы файлов").setMessage(
                "OpenRouter, текстовые/научные файлы через инструменты приложения:\n" + TextFileFormatPolicy.extensions.sorted().joinToString(", ") +
                "\n\nOffice, извлечение текста: docx, pptx, odt, odp, ods. XLSX: все сохранённые строки и ячейки; формулы не пересчитываются. Встроенные графики и изображения из Office не извлекаются.\n\nИзображения: JPEG, PNG, WebP, GIF. PDF: native для моделей с прямым файловым вводом; auto/OCR может подготовить текст. ZIP: распаковка поддерживаемых файлов.")
                .setPositiveButton("Закрыть", null).show()
        })
        spaced(button("📊 Расход и токены") { showUsageStats() }.apply {
            setTextColor(accent); background=rounded(buttonBg)
        })
        spaced(button("📈 Статистика auto-эффорта") {
            AppThemeViews.dialog(this, currentTheme())
                .setTitle("Auto-эффорт")
                .setMessage(chatDb.effortStats())
                .setPositiveButton("OK", null)
                .setNeutralButton("Очистить лог") { _, _ -> diagnostics.clear(); status.text = "Диагностический лог очищен" }
                .show()
        }.apply {
            setTextColor(accent); background=rounded(buttonBg)
        })
        spaced(button("🧪 Диагностика") { showDiagnostics() }.apply {
            setTextColor(accent); background = rounded(buttonBg)
        })

        applyDialogControlTheme(box, theme)
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Настройки приложения")
            .setView(ScrollView(this).apply{addView(box)})
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить") { _,_ ->
                val openRouterKeySaved = secure.setOpenRouterApiKey(openRouterKey.text.toString().trim())
                val githubTokenSaved = secure.setGitHubToken(githubToken.text.toString().trim())
                val allSecretsSaved = openRouterKeySaved && githubTokenSaved
                val selectedTheme = themeIds.getOrElse(themeSpinner.selectedItemPosition) { "dark" }
                prefs.edit()
                    .putString("app_theme", selectedTheme)
                    .putString("github_repo", githubRepo.text.toString().trim())
                    .putString("github_branches", githubBranches.text.toString().trim())
                    .putBoolean("background_responses", backgroundResponses.isChecked)
                    .putBoolean("response_notifications", notifications.isChecked)
                    .apply()
                applyTheme()
                updateModelBadge()
                updateEffortBadge()
                if (notifications.isChecked) requestAnswerNotificationPermission()
                if (!allSecretsSaved) {
                    diagnostics.log("secure_store_save_failed", mapOf(
                        "openrouter_saved" to openRouterKeySaved,
                        "github_saved" to githubTokenSaved
                    ))
                }
                status.text = if (allSecretsSaved) {
                    "Настройки сохранены"
                } else {
                    "Ошибка сохранения ключей в Android Keystore — старый ключ сброшен, повторите ввод"
                }
            }.show()
    }

    // ---------- Chat ----------
    private fun attachmentOwners() = PreparedAttachmentFiles.Owners(
        chatDb.listAllLocalFilePaths().toSet() + LocalAttachmentReferences.allComposerPaths(prefs.all),
        OpenRouterResponseRuntime.localFilePaths() + chatDb.listLegacyCheckpointAttachmentPaths())
    private fun releasePreparedFiles(lease: PreparedAttachmentFiles.Lease) {
        try {
            executor.execute {
                try { lease.release(attachmentOwners()) }
                catch (e: Exception) { lease.defer(); diagnostics.log("prepared_file_cleanup_deferred", mapOf("type" to e.javaClass.simpleName)) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { lease.defer() }
    }

    private fun cleanupOrphanOpenRouterAttachments() {
        try {
            PreparedAttachmentFiles.cleanupPending(this) { attachmentOwners() }
            val dir = File(filesDir, "attachments")
            if (!dir.isDirectory) return
            val referenced = linkedSetOf<String>()
            referenced.addAll(chatDb.listAllLocalFilePaths())
            referenced.addAll(chatDb.listLegacyCheckpointAttachmentPaths())
            referenced.addAll(OpenRouterResponseRuntime.localFilePaths())
            referenced.addAll(LocalAttachmentReferences.allComposerPaths(prefs.all))
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (!LocalAttachmentReferences.isOrphanOldEnough(
                        file.absolutePath, file.lastModified(), now, referenced
                    )) return@forEach
                deleteOwnedLocalFile(file.absolutePath)
            }
        } catch (e: Exception) {
            diagnostics.log("openrouter_attachment_cleanup_failed", mapOf("error" to (e.message ?: e.toString()).take(300)))
        }
    }

    private fun newChatId(): String = "chat_${System.currentTimeMillis()}_${System.nanoTime()}"

    private fun resolveOpenRouterAttachments(message: JSONObject): List<OpenRouterLocalAttachment> {
        chatDb.cachedAttachmentManifest(JsonProtocol.string(message, "chat_id", currentChatId()), message)?.let { message.put("attachments", it) }
        val original = JsonProtocol.objectCopy(message)
        fun finish(items: List<OpenRouterLocalAttachment>): List<OpenRouterLocalAttachment> {
            if (items.isNotEmpty() || message.has("attachments")) message.put("attachments", JSONArray().apply { items.forEach { put(it.toJson()) } })
            if (AttachmentMessageState.fingerprint(original) != AttachmentMessageState.fingerprint(message)) {
                chatDb.updateMessageAttachments(JsonProtocol.string(message, "chat_id", currentChatId()), original, message, false)
            }
            return items
        }
        AttachmentHistoryPolicy.validate(message)
        val remoteFiles = message.optJSONArray("file_ids")?.length() ?: 0
        val localFiles = message.optJSONArray("file_paths")?.length() ?: 0
        if (remoteFiles > localFiles) {
            throw IOException("В истории есть файл без локальных байтов. " +
                "Восстановите исходное вложение перед отправкой в OpenRouter.")
        }
        val remoteImageId = JsonProtocol.string(message, "image_file_id")
            .takeIf { it.isNotBlank() && it != "null" }
        if (remoteImageId != null && JsonProtocol.string(message, "image_path").isBlank() &&
            (message.optJSONArray("attachments")?.let { manifest ->
                (0 until manifest.length()).any { manifest.optJSONObject(it)?.optString("kind") == "image" }
            } != true)) {
            throw IOException("В истории есть фото без локальных байтов. " +
                "Восстановите исходное фото перед отправкой в OpenRouter.")
        }
        val manifest = message.optJSONArray("attachments")
        if (manifest != null && manifest.length() > 0) {
            val existing = (0 until manifest.length()).mapNotNull { i -> manifest.optJSONObject(i)?.let { OpenRouterLocalAttachment.fromJson(it) } }
            val refreshed = existing.map { attachment ->
                val missingText = attachment.kind in setOf("text", "document", "table") &&
                    (attachment.textPath.isNullOrBlank() || !File(attachment.textPath!!).isFile)
                val missingWire = attachment.wirePath?.let { !File(it).isFile } == true
                if (OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(attachment) || missingText || missingWire) {
                    OpenRouterAttachmentPreflight.verifySources(listOf(attachment))
                    val rebuilt = OpenRouterAttachmentStore.classifyLocalFile(this, File(attachment.path), attachment.name, attachment.mime)
                    if (attachment.kind == "text" && !attachment.encodingGuessed && attachment.textEncoding != null) {
                        val selected = OpenRouterAttachmentStore.withTextEncoding(rebuilt, attachment.textEncoding)
                        rebuilt.textPath?.let { File(it).delete() }; selected
                    } else rebuilt
                } else {
                    attachment
                }
            }
            refreshed.forEach {
                if (!File(it.path).isFile) throw IOException("В истории нет локальной копии файла: " + it.name)
                if (it.kind == "text" && it.textPath.isNullOrBlank()) throw IOException("Текстовое вложение недоступно: " + it.name)
            }
            if (refreshed != existing) {
                message.put("attachments", JSONArray().apply { refreshed.forEach { put(it.toJson()) } })
            }
            val extraPaths = message.optJSONArray("file_paths")
            val extraNames = message.optJSONArray("file_names")
            val extraMimes = message.optJSONArray("file_mimes")
            val extra = if (extraPaths == null) emptyList() else (0 until extraPaths.length()).mapNotNull { i ->
                val path = (extraPaths.opt(i) as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (refreshed.any { it.path == path }) return@mapNotNull null
                OpenRouterAttachmentStore.classifyLocalFile(this, File(path), extraNames?.optString(i, File(path).name) ?: File(path).name, extraMimes?.optString(i).orEmpty())
            }
            val imagePath = JsonProtocol.nonBlank(message, "image_path")
            val extraImage = if (imagePath != null && (refreshed + extra).none { it.path == imagePath })
                listOf(OpenRouterAttachmentStore.classifyLocalFile(this, File(imagePath), JsonProtocol.string(message, "image_name", File(imagePath).name), JsonProtocol.string(message, "image_mime", "image/*")))
                else emptyList()
            return finish((refreshed + extra + extraImage).distinctBy { it.path + "|" + it.name })
        }
        val out = mutableListOf<OpenRouterLocalAttachment>()
        val paths = message.optJSONArray("file_paths")
        val names = message.optJSONArray("file_names")
        val sizes = message.optJSONArray("file_sizes")
        val mimes = message.optJSONArray("file_mimes")
        if (paths != null) for (i in 0 until paths.length()) {
            val path = JsonProtocol.string(paths, i)
            if (path.isBlank()) continue
            val file = File(path)
            if (!file.isFile) throw IOException("В истории нет локальной копии файла: " + names?.optString(i, "attachment"))
            out += OpenRouterAttachmentStore.classifyLocalFile(this, file, names?.optString(i, file.name) ?: file.name, mimes?.optString(i, "") ?: "")
        }
        val imagePath = JsonProtocol.string(message, "image_path").takeIf { it.isNotBlank() }
        if (imagePath != null) {
            val file = File(imagePath)
            if (!file.isFile) throw IOException("В истории нет локальной копии изображения: " + JsonProtocol.string(message, "image_name", "image"))
            out += OpenRouterAttachmentStore.classifyLocalFile(this, file, JsonProtocol.string(message, "image_name", file.name), JsonProtocol.string(message, "image_mime", "image/*"))
        }
        return finish(out)
    }
    private data class PreparedOpenRouterMessages(
        val messages: JSONArray,
        val attachments: List<OpenRouterLocalAttachment>
    )

    private fun presentationInstructionsForRequest(): String? {
        if (!prefs.getBoolean("response_presentation_instructions", true)) return null
        val preview = prefs.getBoolean("response_scientific_preview", true) && presentationRendererAvailable
        return ResponsePresentationInstructions.forRenderers(math = preview, mermaid = preview)
    }

    private fun buildOpenRouterMessagesSnapshot(
        history: List<JSONObject>, systemPrompt: String?, developerInstructions: String?,
        githubToolsEnabled: Boolean, githubRepo: String, githubBranches: String
    ): PreparedOpenRouterMessages = prepareOpenRouterSnapshot(history, systemPrompt,
        developerInstructions, githubToolsEnabled, githubRepo, githubBranches, null, false,
        presentationInstructionsForRequest())

    private fun prepareOpenRouterSnapshot(
        history: List<JSONObject>, systemPrompt: String?, developerInstructions: String?,
        githubToolsEnabled: Boolean, githubRepo: String, githubBranches: String,
        model: String?, compatibleImages: Boolean, presentationInstructions: String? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO
    ): PreparedOpenRouterMessages {
        val originalByMessage = history.map { message ->
            if (message.optString("role") == "user") resolveOpenRouterAttachments(message) else emptyList()
        }
        OpenRouterAttachmentPreflight.verifySources(originalByMessage.flatten().distinctBy { it.path })
        val imageEdge = if (originalByMessage.flatten().count { it.kind == "image" } > 20) 2000 else 2576
        val copies = mutableMapOf<String, OpenRouterLocalAttachment>()
        val byMessage = originalByMessage.map { items -> items.map { a ->
            if (compatibleImages) copies.getOrPut(a.id) { OpenRouterAttachmentPreflight.compatible(a, imageEdge) } else a
        } }
        val wireAttachments = byMessage.flatten().distinctBy { it.id }
        if (model != null) OpenRouterAttachmentPreflight.validate(model, byMessage.flatten(), claudeRoute)
        val systemContent = OpenRouterContextSnapshot.systemContent(
            GenerationInstructions(systemPrompt, developerInstructions, presentationInstructions), githubToolsEnabled, githubRepo, githubBranches)
        val result = OpenRouterContextSnapshot.build(history, systemContent, byMessage, wirePart = { message, a, part ->
                    if (a.kind in setOf("image", "pdf")) {
                        val wire = (if (a.kind == "image") part.getJSONObject("image_url").get("url") else part.getJSONObject("file").get("file_data")) as FileBackedDataUrl
                        diagnostics.log("openrouter_attachment_wire", mapOf("request_id" to message.optString("request_id"),
                            "file" to a.name, "kind" to a.kind, "wire_mime" to wire.mime,
                            "source_bytes" to a.size, "wire_bytes" to wire.sourceBytes,
                            "base64_chars" to wire.encodedChars, "sha256" to a.sha256))
                    }
        })
        return PreparedOpenRouterMessages(result, wireAttachments)
    }
    private fun resolveOpenRouterAttachmentsForChat(chatId: String): List<OpenRouterLocalAttachment> {
        return if (chatId == currentChatId()) {
            chat.flatMap { message -> if (message.optString("role") == "user") resolveOpenRouterAttachments(message) else emptyList() }
        } else emptyList()
    }

    private fun sendOpenRouterMessage(
        chatId: String, generation: Long, startedAt: Long, text: String, requestId: String,
        model: String, effort: String, verbosity: String, maxOut: Int?, taskClass: String,
        effortPref: String, autoSelected: Boolean, promptChars: Int, attachments: Int,
        preparedMessages: PreparedOpenRouterMessages, apiKey: String, githubEnabled: Boolean,
        webSearchEnabled: Boolean, webFetchEnabled: Boolean, parallelToolCallsEnabled: Boolean,
        githubRepo: String, githubBranches: String, cacheMode: String, background: Boolean, pdfEngine: String,
        responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO,
        githubCredential: String? = null
    ) {
        activeAssistantItemId = addBubble(
            AssistantModelLabel.text(JSONObject().put("provider", "openrouter").put("requested_model", model)),
            "",
            false,
            itemId = ChatScrollPolicy.activeAssistantIdentity(requestId, null, chat.size)
        )
        requestAnswerNotificationPermission()
        renderRequestTelemetry()
        OpenRouterResponseRuntime.start(applicationContext, OpenRouterRuntimeRequest(
            chatId, requestId, model, preparedMessages.messages, preparedMessages.attachments,
            effort, verbosity, maxOut, githubEnabled, githubRepo,
            githubBranches.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            webSearchEnabled, webFetchEnabled, parallelToolCallsEnabled, cacheMode, background,
            startedAt, taskClass, effortPref, autoSelected, promptChars, apiKey, pollingClient, pdfEngine, responseFormat, claudeRoute,
            githubCredential = githubCredential), openRouterClient)
    }

    override fun isViewing(chatId: String): Boolean = activityVisible && !isFinishing && currentChatId() == chatId
    override fun ownsClient(client: OpenRouterChatClient): Boolean = openRouterClient === client
    override fun protocolWarning(chatId: String, requestId: String, warning: String) {
        if (isViewing(chatId) && chatDb.getActiveResponse(chatId)?.optString("request_id") == requestId) {
            toolLabel.text = warning
            toolLabel.contentDescription = warning
        }
    }
    override fun confirmTool(chatId: String, requestId: String, name: String, arguments: JSONObject): Boolean {
        if (!isViewing(chatId) || chatDb.getActiveResponse(chatId)?.optString("request_id") != requestId) return false
        currentGitHubToolOwner.set(chatId to activeResponseState.state.generation)
        return try { confirmGitHubWrite(name, arguments) } finally { currentGitHubToolOwner.remove() }
    }
    override fun historyReconciled(chatId: String, requestId: String) {
        if (!isViewing(chatId) || chatDb.getActiveResponse(chatId) != null) return
        val rows = chatDb.getChat(chatId)?.optJSONArray("messages") ?: return
        if ((0 until rows.length()).none { index -> rows.optJSONObject(index)?.let {
                JsonProtocol.nonBlank(it, "request_id") == requestId && JsonProtocol.nonBlank(it, "provider") == "openrouter"
            } == true }) return
        val current = JSONArray().apply { chat.forEach { put(it) } }
        if (rows.toString() == current.toString()) return
        persistComposerStateNow()
        restoreCurrentChat()
        renderRequestTelemetry()
    }

    override fun responseChanged(chatId: String, requestId: String) {
        if (!isViewing(chatId)) return
        val active = chatDb.getActiveResponse(chatId)
        if (active?.optString("request_id") == requestId) {
            if (activeAssistantItemId == null) {
                val model = chatDb.listRequestAccounting(chatId).firstOrNull { it.optString("local_request_id") == requestId }?.optString("requested_model")
                activeAssistantItemId = addBubble(
                    AssistantModelLabel.text(JSONObject().put("requested_model", model)),
                    "",
                    false,
                    itemId = ChatScrollPolicy.activeAssistantIdentity(requestId, null, chat.size)
                )
            }
            activeAssistantItemId?.let { messageAdapter.updateText(it, JsonProtocol.string(active, "text")) }
            setBusy(true)
            scrollToLatest()
        } else if (active == null) {
            val rows = chatDb.getChat(chatId)?.optJSONArray("messages") ?: return
            val terminal = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.lastOrNull() ?: return
            if (terminal.optString("request_id") != requestId || terminal.optString("role") != "assistant") return
            persistComposerStateNow()
            restoreCurrentChat()
            status.text = when (terminal.optString("status")) {
                "completed" -> "Готово"
                "incomplete" -> "Ответ не завершён · ${OpenRouterFinishPolicy.description(JsonProtocol.text(terminal, "finish_reason"))}"
                else -> if (JsonProtocol.string(terminal, "text").isNotEmpty())
                    "Ошибка OpenRouter — частичный ответ сохранён"
                else "Ошибка OpenRouter — текст ответа не получен"
            }
            renderRequestTelemetry()
        }
    }
    private fun requestAnswerNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("response_notifications", true) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_asked", false)) {
            prefs.edit().putBoolean("notification_permission_asked", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun openNotificationChat(source: Intent?) {
        val id = source?.getStringExtra(ResponseNotifications.EXTRA_CHAT_ID) ?: return
        source.removeExtra(ResponseNotifications.EXTRA_CHAT_ID)
        if (chatDb.getChatTitle(id) != null && id != currentChatId()) switchChat(id)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openNotificationChat(intent) }

    private fun recoverInterruptedOpenRouterRequests() {
        chatDb.listActiveResponses().forEach { active ->
            val requestId = JsonProtocol.nonBlank(active, "request_id") ?: return@forEach
            val chatId = JsonProtocol.nonBlank(active, "chat_id") ?: return@forEach
            if (chatDb.recoverLegacyOpenAiRequestLocally(chatId, requestId)) return@forEach
            val account = chatDb.listRequestAccounting(chatId)
                .firstOrNull { it.optString("local_request_id") == requestId }
            if (account?.optString("provider") != "openrouter" ||
                OpenRouterResponseRuntime.contains(requestId)) return@forEach
            val receivedText = JsonProtocol.string(active, "text")
            val error = "Соединение прервано после остановки процесса Android. " +
                (if (receivedText.isNotEmpty()) "Частичный ответ сохранён" else "Текст ответа не получен") +
                "; повторная платная отправка не выполнялась."
            val message = JSONObject().put("role", "assistant").put("provider", "openrouter")
                .put("request_id", requestId).put("requested_model", account.opt("requested_model"))
                .put("status", "disconnected").put("error", error)
                .put("text", receivedText)
            chatDb.getOpenRouterCheckpoint(chatId, requestId)?.let {
                message.put("openrouter_protocol", OpenRouterProtocolCodec.withVisibleTail(it, message))
            }
            if (chatDb.finalizeCurrentChatIfActiveRequest(chatId, "", null, null, listOf(message), requestId,
                    expectedProvider = "openrouter")) {
                chatDb.recordRequestAccounting(chatId = chatId, localRequestId = requestId,
                    status = "disconnected", error = error)
                chatDb.sealOpenRouterRounds(chatId, requestId)
                ResponseNotifications.finished(applicationContext, chatId, requestId, false)
            }
        }
    }

    private fun githubConfirmationText(name: String, arguments: JSONObject): String =
        GitHubConfirmationPreview.text(name, arguments)

    private fun completePendingGitHubConfirmation(
        decision: CompletableFuture<Boolean>,
        approved: Boolean
    ) {
        if (pendingGitHubConfirmation.compareAndSet(decision, null)) {
            val dialog = pendingGitHubConfirmationDialog.getAndSet(null)
            if (dialog != null && dialog.isShowing) dialog.dismiss()
            decision.complete(approved)
        }
    }

    private fun cancelPendingGitHubConfirmation() {
        val decision = pendingGitHubConfirmation.getAndSet(null)
        pendingGitHubConfirmationDialog.getAndSet(null)?.let { dialog ->
            if (dialog.isShowing) runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
        decision?.complete(false)
    }

    private fun confirmGitHubWrite(name: String, arguments: JSONObject): Boolean {
        val owner = currentGitHubToolOwner.get() ?: return false
        fun stillCurrent() = isRequestCurrent(owner.first, owner.second)
        if (!activityVisible || isFinishing || !stillCurrent()) return false
        val decision = CompletableFuture<Boolean>()
        if (!pendingGitHubConfirmation.compareAndSet(null, decision)) return false
        runOnUiThread {
            if (!activityVisible || isFinishing || !stillCurrent() ||
                pendingGitHubConfirmation.get() !== decision || decision.isDone) {
                completePendingGitHubConfirmation(decision, false)
                return@runOnUiThread
            }
            val dialog = AppThemeViews.dialog(this, currentTheme())
                .setTitle("GitHub: подтверждение")
                .setMessage(githubConfirmationText(name, arguments))
                .setNegativeButton("Отмена") { _, _ ->
                    completePendingGitHubConfirmation(decision, false)
                }
                .setPositiveButton("Подтвердить") { _, _ ->
                    completePendingGitHubConfirmation(decision, stillCurrent())
                }
                .create()
            pendingGitHubConfirmationDialog.set(dialog)
            dialog.setOnCancelListener {
                completePendingGitHubConfirmation(decision, false)
            }
            dialog.show()
            if (pendingGitHubConfirmation.get() !== decision || decision.isDone) {
                completePendingGitHubConfirmation(decision, false)
            }
        }
        return try {
            decision.get(10, TimeUnit.MINUTES) && stillCurrent()
        } catch (_: Exception) {
            completePendingGitHubConfirmation(decision, false)
            false
        }
    }

    private fun openRouterAttachmentErrorSuffix(chatId: String): String {
        return try {
            val attachments = chat.filter { it.optString("role") == "user" }.flatMap { resolveOpenRouterAttachments(it) }.distinctBy { it.id }
            if (attachments.isEmpty()) "" else {
                val details = attachments.joinToString("; ") {
                    val wireSize = File(it.wirePath ?: it.path).length()
                    if (wireSize != it.size) it.name + ": исходный " + formatBytes(it.size) + ", отправляемый " + formatBytes(wireSize)
                    else it.name + ": " + formatBytes(it.size)
                }
                " Вложения: " + details + "."
            }
        } catch (_: Exception) { "" }
    }
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun newChat() {
        captureCurrentScrollAnchor()
        persistComposerStateNow()
        activeResponseState.nextGeneration()
        cancelPendingGitHubConfirmation()
        cancelOpenRouterRequest()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        persistCurrentChat()
        val id = newChatId()
        prefs.edit().putString("current_chat",id).apply()
        displayedChatId = id
        cancelScrollRestore()
        chat.clear()
        currentResponseId=null
        baselineEffort=null
        lastUserText=""
        clearAttachedSelection()
        restoringComposer = true
        try { input.setText("") } finally { restoringComposer = false }
        refreshAttachChips()
        messageAdapter.clearItems()
        toolLabel.text=""
        title.text="Новый чат"
        clearComposerState(id)
        ensureChatEntry(id,"Новый чат")
        toolStates.clear(); renderToolActivity()
        setBusy(false)
        status.text="Новый чат"
        requestTelemetryExpanded = false
        renderRequestTelemetry()
        refreshChatDrawer()
    }

    private fun switchChat(id:String) {
        captureCurrentScrollAnchor()
        persistComposerStateNow()
        activeResponseState.nextGeneration()
        cancelPendingGitHubConfirmation()
        cancelOpenRouterRequest()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        persistCurrentChat()
        prefs.edit().putString("current_chat",id).apply()
        restoreCurrentChat()
        requestTelemetryExpanded = false
        renderRequestTelemetry()
        refreshChatDrawer()
        drawerLayout.closeDrawers()
    }

    private fun classifyTask(text:String, hasAttachments:Boolean):String{
        val t=text.lowercase()
        return when {
            hasAttachments && (t.contains("анализ") || t.contains("разбери") || t.contains("проанализ") || t.contains("таблиц") || t.contains("файл")) -> "attachment_analysis"
            t.contains("код") || t.contains("функци") || t.contains("дебаг") || t.contains("отлад") || t.contains("рефактор") || t.contains("algorithm") || t.contains("debug") || t.contains("refactor") -> "coding"
            t.contains("проанализ") || t.contains("сравни") || t.contains("докажи") || t.contains("архитектур") || t.contains("исслед") || t.contains("analyze") || t.contains("compare") -> "analysis"
            text.length <= 80 -> "short"
            else -> "general"
        }
    }

    private fun autoEffortHeuristic(text:String, hasAttachments:Boolean):String{
        val len=text.length
        val heavy=Regex("(?i)(код|напиши функци|проанализируй|подробно|разбер|докажи|архитектур|дебаг|отладь|сравни|оптимизир|алгоритм|рефактор|explain in detail|analyze|debug)")
        return when {
            hasAttachments && len>200 -> "high"
            heavy.containsMatchIn(text) -> "high"
            len>600 -> "xhigh"
            len in 1..39 -> "low"
            else -> "medium"
        }
    }
    private fun fmtTokens(n:Int):String = fmtTokens(n.toLong())
    private fun fmtTokens(n:Long):String = when {
         n>=1_000_000L -> "%.1fM".format(n/1_000_000.0)
         n>=1_000L -> "%.1fK".format(n/1_000.0)
         else -> n.toString()
     }


    private fun stopCurrentResponse(){
        if (pendingOpenRouterPreparation.getAndSet(null) != null) {
            activeResponseState.markUserStop()
            cancelOpenRouterRequest()
            setBusy(false)
            activeRequestRegenerate = false
            status.text = "Подготовка отправки отменена — черновик сохранён"
            return
        }
        val chatId=currentChatId()
        val active=try{activeResponsePersistence.getActiveResponse(chatId)}catch(_:Exception){null}
        cancelOpenRouterRequest()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        cancelPendingGitHubConfirmation()
        val activeRequestId=active?.optString("request_id").takeIf{!it.isNullOrBlank() && it!="null"}
            ?: chat.lastOrNull { it.optString("role") == "user" }?.let { JsonProtocol.nonBlank(it, "request_id") }
        val stopped = try {
            activeRequestId?.let { OpenRouterResponseRuntime.stop(this, chatId, it, removeUserMessage = !activeRequestRegenerate) } ?: false
        } catch (error: Exception) {
            diagnostics.log("openrouter_stop_save_failed", mapOf("error_type" to error.javaClass.simpleName))
            status.text = "Не удалось сохранить остановку. Запрос остаётся активным; повторите Stop."
            return
        }
        activeResponseState.markUserStop()
        activeRequestId?.let { OpenRouterResponseRuntime.cancel(it, "Остановлено пользователем") }
        if (activeRequestId != null && !stopped) {
            restoreCurrentChat()
            status.text = "Ответ уже завершён"
            renderRequestTelemetry()
            return
        }
        if (activeRequestId == null) {
            activeResponsePersistence.clearActiveResponse(chatId, expectedRequestId = null)
        }
        if (stopped && activeRequestId != null) {
            chatDb.sealOpenRouterRounds(chatId, activeRequestId)
            reconcileOpenRouterGenerations(chatId, activeRequestId)
        }
        if(!activeRequestRegenerate){
            restoringComposer=true
            try{
                input.setText(lastUserText)
                input.setSelection(input.text.length)
            }finally{restoringComposer=false}
            restoreLastUserAttachments()
        }
        if(!activeRequestRegenerate && chat.isNotEmpty() && chat.lastOrNull()?.optString("role")=="user"){
            chat.removeAt(chat.lastIndex)
            persistCurrentChat()
        }
        messageAdapter.removeLast(if(activeRequestRegenerate) 1 else 2)
        persistComposerStateNow()
        activeAssistantItemId=null
        setBusy(false)
        activeRequestRegenerate=false
        refreshLocalEvidenceEligibility()
        val stoppedAccount = if (stopped && activeRequestId != null) {
            chatDb.listRequestAccounting(chatId).firstOrNull {
                it.optString("local_request_id") == activeRequestId &&
                    it.optString("local_status") == "stopped"
            }
        } else null
        status.text = "Остановлено — запрос отменён · " + RequestCostFormatter.text(stoppedAccount)
        renderRequestTelemetry()
        toolStates.clear(); renderToolActivity()
    }

    private fun prepareOpenRouterComposerAttachments(): List<OpenRouterLocalAttachment> {
        val out = mutableListOf<OpenRouterLocalAttachment>()
        out += attachedOpenRouterAttachments.filter { File(it.path).isFile }
        if (out.isEmpty()) {
            for (i in attachedFilePaths.indices) {
                val path = attachedFilePaths.getOrNull(i).orEmpty()
                if (path.isBlank()) continue
                val file = File(path)
                if (!file.isFile) throw IOException("Локальная копия вложения недоступна")
                out += OpenRouterAttachmentStore.classifyLocalFile(this, file, attachedFileNames.getOrNull(i).orEmpty().ifBlank { file.name }, attachedFileMimes.getOrNull(i).orEmpty())
            }
            pendingImagePath?.takeIf { File(it).isFile }?.let { path ->
                out += OpenRouterAttachmentStore.classifyLocalFile(this, File(path), pendingImageName ?: File(path).name, pendingImageMime ?: "image/*")
            }
        }
        if (out.size > OpenRouterAttachmentStore.MAX_ATTACHMENTS) throw IOException("Слишком много вложений за одно сообщение: максимум " + OpenRouterAttachmentStore.MAX_ATTACHMENTS)
        return out.distinctBy { it.id }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun saveOpenRouterAttachmentsToComposer(
        chatId: String,
        attachments: List<OpenRouterLocalAttachment>,
        completedPending: List<Pair<String, String>> = emptyList()
    ) {
        if (chatId.isBlank()) return
        synchronized(composerMergeLock) {
        if (chatDb.getChatTitle(chatId) == null) return
        try {
            val key = composerPrefsKey(chatId)
            val o = try { JSONObject(prefs.getString(key, null).orEmpty()) } catch (_: Exception) { JSONObject() }
            val stored = o.optJSONArray("openrouter_attachments") ?: JSONArray()
            val existing = mutableSetOf<String>()
            for (i in 0 until stored.length()) {
                val item = stored.optJSONObject(i) ?: continue
                existing += item.optString("sha256") + "|" + item.optString("name")
            }
            attachments.forEach { attachment ->
                val keyValue = attachment.sha256 + "|" + attachment.name
                if (existing.add(keyValue)) stored.put(attachment.toJson())
            }
            val completed = completedPending.toSet()
            val pending = JSONArray()
            o.optJSONArray("pending_uploads")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pair = item.optString("uri") to item.optString("kind")
                    if (pair !in completed) pending.put(item)
                }
            }
            o.put("openrouter_attachments", stored).put("pending_uploads", pending).put("saved_at", System.currentTimeMillis())
            prefs.edit().putString(key, o.toString()).commit()
            if (Looper.myLooper() == Looper.getMainLooper() && chatId == currentChatId()) {
                attachments.forEach { attachment ->
                    if (attachedOpenRouterAttachments.none { it.sha256 == attachment.sha256 && it.name == attachment.name }) {
                        attachedOpenRouterAttachments.add(attachment)
                    }
                }
                if (completed.isNotEmpty()) {
                    pendingUploadUris.removeAll { it in completed }
                }
            }
        } catch (_: Exception) {}
        }
    }

    private fun deleteOpenRouterAttachmentFiles(attachments: List<OpenRouterLocalAttachment>) {
        attachments.flatMap { listOfNotNull(it.path, it.textPath, it.wirePath) }.distinct().forEach { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
    }
    private fun validateOpenRouterModalities(
        apiKey: String,
        model: String,
        attachments: List<OpenRouterLocalAttachment>,
        chatId: String? = null,
        generation: Long? = null
    ): Boolean {
        fun requestStillCurrent(): Boolean = chatId == null || generation == null || isRequestCurrent(chatId, generation)
        if (!requestStillCurrent()) return false
        val needed = attachments.mapNotNull {
            when (it.kind) { "image" -> "image"; else -> null }
        }.toSet()
        if (needed.isEmpty()) return true
        val modalities = openRouterModelModalities[model] ?: run {
            val now = System.currentTimeMillis()
            if (openRouterModelModalitiesRetryAfter[model]?.let { it > now } == true) return true
            val req = Request.Builder().url("https://openrouter.ai/api/v1/models").addHeader("Authorization", "Bearer " + apiKey).get().build()
            val call = client.newCall(req)
            openRouterModelPreflightCall.set(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        openRouterModelModalitiesRetryAfter[model] = now + 120_000L
                        return true
                    }
                    val arr = JSONObject(BoundedResponseDecoder.readBody(response.body)).optJSONArray("data")
                    if (arr == null) {
                        openRouterModelModalitiesRetryAfter[model] = now + 120_000L
                        return true
                    }
                    var found: Set<String>? = null
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("id") == model) {
                            val mods = item.optJSONObject("architecture")?.optJSONArray("input_modalities") ?: JSONArray()
                            found = (0 until mods.length()).map { mods.optString(it) }.filter { it.isNotBlank() }.toSet()
                            break
                        }
                    }
                    if (found == null) {
                        openRouterModelModalitiesRetryAfter[model] = now + 120_000L
                        return true
                    }
                    openRouterModelModalities[model] = found
                    openRouterModelModalitiesRetryAfter.remove(model)
                    found
                }
            } finally {
                openRouterModelPreflightCall.compareAndSet(call, null)
            }
        } ?: return true
        if (!requestStillCurrent()) return false
        val missing = needed.firstOrNull { it !in modalities }
        if (missing != null) throw IOException("Модель " + model + " не поддерживает вложения типа " + missing)
        return true
    }
    private fun sendMessage(regenerate: Boolean) {
        if (chatDb.getActiveResponse(currentChatId()) != null) {
            status.text = "В чате сохранён незавершённый запрос. Остановите его перед новой отправкой."
            return
        }
        if (pendingAttachmentChange.get() != null) { status.text = "Дождитесь обновления вложения"; return }
        if (stop.visibility == View.VISIBLE || preparingZipAttachments || pendingUploads.get() > 0) {
            status.text = "Дождитесь завершения или остановите текущую операцию"
            return
        }
        val sourceToRepeat = if (regenerate) {
            chat.lastOrNull { it.optString("role") == "user" }?.let { JSONObject(it.toString()) }
                ?: run { status.text = "В этом чате нечего повторять"; return }
        } else null
        val configuration = try { captureContextConfiguration() } catch (_: IllegalArgumentException) {
            status.text = "Сохранённая модель недоступна. Выберите Claude в списке моделей."
            return
        }
        val openRouterResponseFormat = try {
            OpenRouterStructuredOutputPolicy.format(configuration.responseFormat, configuration.schema)
        } catch (_: Exception) {
            status.text = "Сначала укажите корректный формат/JSON Schema в панели «Мышление и ответ»"
            return
        }
        cancelOpenRouterRequest()
        cancelPendingGitHubConfirmation()
        val key = secure.getOpenRouterApiKey()
        if (key.isBlank()) { showSettings(); return }
        val model = configuration.model
        if (pendingUploadUris.any { it.second == "zip" } && !preparingZipAttachments) {
            preparePendingOpenRouterZipsThenSend(regenerate)
            return
        }
        if (pendingUploads.get() > 0) { status.text = "Дождитесь загрузки вложений…"; return }
        if (preparingZipAttachments) { status.text = "Готовлю ZIP-вложения…"; return }
        val requestChatId = currentChatId()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        val requestGeneration = activeResponseState.nextGeneration()
        val startedAt = SystemClock.elapsedRealtime()
        val requestWallStartedAt = System.currentTimeMillis()

        val text = sourceToRepeat?.let { JsonProtocol.text(it, "text") } ?: input.text.toString().trim()
        val imagePath = sourceToRepeat?.let { JsonProtocol.text(it, "image_path") }?.takeIf { it.isNotBlank() } ?: pendingImagePath
        val imageFileId = sourceToRepeat?.let { JsonProtocol.text(it, "image_file_id") }?.takeIf { it.isNotBlank() && it != "null" } ?: pendingImageFileId
        val requestFileIds = sourceToRepeat?.optJSONArray("file_ids")?.let { a -> (0 until a.length()).map { JsonProtocol.string(a, it) } } ?: attachedFileIds.toList()
        val requestFileNames = sourceToRepeat?.optJSONArray("file_names")?.let { a -> (0 until a.length()).map { JsonProtocol.string(a, it) } } ?: attachedFileNames.toList()
        val requestFileSizes = sourceToRepeat?.optJSONArray("file_sizes")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: attachedFileSizes.toList()
        val requestFilePaths = sourceToRepeat?.optJSONArray("file_paths")?.let { a -> (0 until a.length()).map { JsonProtocol.string(a, it) } } ?: attachedFilePaths.toList()
        val requestFileMimes = sourceToRepeat?.optJSONArray("file_mimes")?.let { a -> (0 until a.length()).map { JsonProtocol.string(a, it) } } ?: attachedFileMimes.toList()
        val requestOpenRouterAttachments = if (sourceToRepeat != null) {
            sourceToRepeat.optJSONArray("attachments")?.let { manifest ->
                (0 until manifest.length()).mapNotNull { i ->
                    manifest.optJSONObject(i)?.let { OpenRouterLocalAttachment.fromJson(it) }
                }
            }.orEmpty()
        } else {
            attachedOpenRouterAttachments.toList()
        }
        val knownFileBytes = requestFileSizes.sumOf { it.coerceAtLeast(0L) }
        if (text.isBlank() && imageFileId == null &&
            requestFileIds.isEmpty() && requestFilePaths.isEmpty() &&
            imagePath == null && requestOpenRouterAttachments.isEmpty()) return

        val localRequestId = "local_" + UUID.randomUUID().toString()
        val effortPref = prefs.getString("reasoning", "high") ?: "high"
        val hasAttachments = requestFileIds.isNotEmpty() || imageFileId != null ||
            requestFilePaths.isNotEmpty() || imagePath != null ||
            requestOpenRouterAttachments.isNotEmpty()
        val autoSelected = effortPref == "auto"
        val taskClass = classifyTask(text, hasAttachments)
        val baselineAutoEffort = autoEffortHeuristic(text, hasAttachments)
        val actualEffort = if (autoSelected) chatDb.autoEffortHint(taskClass, baselineAutoEffort) else effortPref
        updateEffortBadge(actualEffort)
        val verbosity = prefs.getString("verbosity", "medium") ?: "medium"
        val maxOut = configuredMaxOutputTokens()
        val backgroundEnabled = prefs.getBoolean("background_responses", true)
        val preflightWarning = buildPreflightWarning(text, knownFileBytes, maxOut, actualEffort)

        val sentUserMessage = JSONObject().put("role", "user").put("text", text).put("request_id", localRequestId).apply {
            put("chat_id", requestChatId)
            put("provider", "openrouter")
            put("requested_model", model)
            val fileIds = JSONArray(); requestFileIds.forEach { fileIds.put(it) }
            val fileNames = JSONArray(); requestFileNames.forEach { fileNames.put(it) }
            val fileSizes = JSONArray(); requestFileSizes.forEach { fileSizes.put(it) }
            val filePaths = JSONArray(); requestFilePaths.forEach { filePaths.put(it) }
            val fileMimes = JSONArray(); requestFileMimes.forEach { fileMimes.put(it) }
            if (fileIds.length() > 0) put("file_ids", fileIds)
            if (fileNames.length() > 0) put("file_names", fileNames)
            if (fileSizes.length() > 0) put("file_sizes", fileSizes)
            if (filePaths.length() > 0) put("file_paths", filePaths)
            if (fileMimes.length() > 0) put("file_mimes", fileMimes)
            imagePath?.let { put("image_path", it) }
            imageFileId?.let { put("image_file_id", it) }
            (sourceToRepeat?.let { JsonProtocol.nonBlank(it, "image_name") } ?: pendingImageName)?.takeIf { it.isNotBlank() }?.let { put("image_name", it) }
            (sourceToRepeat?.let { JsonProtocol.nonBlank(it, "image_mime") } ?: pendingImageMime)?.takeIf { it.isNotBlank() }?.let { put("image_mime", it) }
            put("image_size", (sourceToRepeat?.optLong("image_size", 0L) ?: pendingImageSize).coerceAtLeast(0L))
            if (requestOpenRouterAttachments.isNotEmpty()) put("attachments", JSONArray().apply { requestOpenRouterAttachments.forEach { put(it.toJson()) } })
        }

        val capturedHistory = chat.map { JsonProtocol.objectCopy(it) } + JsonProtocol.objectCopy(sentUserMessage)
        val capturedSystemPrompt = configuration.instructions.systemPrompt
        val capturedPresentation = configuration.instructions.presentationInstructions
        val capturedDeveloperInstructions = configuration.instructions.developerInstructions
        val capturedGithubEnabled = configuration.github
        val capturedGithubCredential = if (capturedGithubEnabled) secure.getGitHubToken() else null
        val capturedGithubRepo = configuration.githubRepo
        val capturedGithubBranches = configuration.githubBranches
        val capturedWebSearch = configuration.webSearch
        val capturedWebFetch = configuration.webFetch
        val capturedParallelTools = configuration.parallel
        val capturedPdfEngine = configuration.pdfEngine
        val capturedCacheMode = configuration.cacheMode
        val capturedClaudeRoute = configuration.route
        val capturedCompatibleImages = configuration.compatibleImages
        val originalComposerText = input.text.toString()
        val originalAttachmentIds = attachedOpenRouterAttachments.map { Triple(it.id, it.textPath, it.textEncoding) }
        val originalFilePaths = attachedFilePaths.toList()
        val originalImagePath = pendingImagePath
        val originalUploadUris = pendingUploadUris.toList()
        pendingOpenRouterPreparation.set(localRequestId)
        setBusy(true)
        status.text = "Готовлю файлы и контекст…"
        val preparedFiles = PreparedAttachmentFiles.lease(this)
        executor.execute {
            val scope = preparedFiles.capture()
            var handedToUi = false
            try {
                openRouterPreparationHook?.invoke()
                val prepared = prepareOpenRouterSnapshot(
                    capturedHistory, capturedSystemPrompt, capturedDeveloperInstructions,
                    capturedGithubEnabled, capturedGithubRepo, capturedGithubBranches, model,
                    capturedCompatibleImages, capturedPresentation, capturedClaudeRoute
                )
                if (!validateOpenRouterModalities(key, model, prepared.attachments, requestChatId, requestGeneration)) return@execute
                if (!isRequestCurrent(requestChatId, requestGeneration) ||
                    pendingOpenRouterPreparation.get() != localRequestId) return@execute
                handedToUi = true
                runOnUiThread {
                    try {
                        if (!isRequestCurrent(requestChatId, requestGeneration) ||
                            !pendingOpenRouterPreparation.compareAndSet(localRequestId, null)) return@runOnUiThread
                        capturedHistory.last().optJSONArray("attachments")?.let { sentUserMessage.put("attachments", JsonProtocol.arrayCopy(it)) }
                        chat.add(sentUserMessage)
                        addBubble("Ты", text, true, message = sentUserMessage)
                        persistCurrentChat()
                        if (!regenerate && input.text.toString() == originalComposerText &&
                            attachedOpenRouterAttachments.map { Triple(it.id, it.textPath, it.textEncoding) } == originalAttachmentIds &&
                            attachedFilePaths == originalFilePaths &&
                            pendingImagePath == originalImagePath &&
                            pendingUploadUris == originalUploadUris) {
                            input.setText("")
                            clearAttachedSelection()
                            clearComposerState(requestChatId)
                        }
                        lastUserText = text
                        activeRequestRegenerate = regenerate
                        activeResponsePersistence.setActiveResponse(
                            requestChatId, responseId = null, requestId = localRequestId,
                            text = "", status = "starting", startedAt = requestWallStartedAt,
                            effortPref = effortPref, effectiveEffort = actualEffort,
                            autoSelected = autoSelected, promptChars = text.length,
                            attachments = requestOpenRouterAttachments.size,
                            backgroundEnabled = backgroundEnabled
                        )
                        chatDb.startRequestAccounting(
                            chatId = requestChatId, localRequestId = localRequestId,
                            provider = "openrouter", requestedModel = model,
                            credentialSource = "openrouter_api_key"
                        )
                        status.text = if (preflightWarning.isNullOrBlank()) "Модель отвечает…"
                            else "⚠ $preflightWarning\nМодель отвечает…"
                        toolStates.clear()
                        renderToolActivity()
                        diagnostics.log("request_start", mapOf(
                            "chat_id" to requestChatId,
                            "generation" to requestGeneration,
                            "effort_pref" to effortPref,
                            "effective_effort" to actualEffort,
                            "auto" to autoSelected,
                            "task_class" to taskClass,
                            "attachments" to requestOpenRouterAttachments.size,
                            "prompt_chars" to text.length,
                            "model" to model,
                            "requested_model" to model,
                            "provider" to "openrouter"
                        ))
                        currentResponseId = null
                        toolLabel.text = ""
                        refreshAttachChips()
                        sendOpenRouterMessage(
                            chatId = requestChatId, generation = requestGeneration,
                            startedAt = startedAt, text = text, requestId = localRequestId,
                            model = model, effort = actualEffort, verbosity = verbosity,
                            maxOut = maxOut, taskClass = taskClass, effortPref = effortPref,
                            autoSelected = autoSelected, promptChars = text.length,
                            attachments = requestOpenRouterAttachments.size,
                            preparedMessages = prepared, apiKey = key,
                            githubEnabled = capturedGithubEnabled,
                            webSearchEnabled = capturedWebSearch,
                            webFetchEnabled = capturedWebFetch,
                            parallelToolCallsEnabled = capturedParallelTools,
                            githubRepo = capturedGithubRepo, githubBranches = capturedGithubBranches,
                            cacheMode = capturedCacheMode, background = backgroundEnabled, pdfEngine = capturedPdfEngine,
                            responseFormat = openRouterResponseFormat, claudeRoute = capturedClaudeRoute,
                            githubCredential = capturedGithubCredential
                        )
                    } finally { releasePreparedFiles(preparedFiles) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isRequestCurrent(requestChatId, requestGeneration) &&
                        pendingOpenRouterPreparation.compareAndSet(localRequestId, null)) {
                        status.text = "Ошибка подготовки вложений: " +
                            (e.message ?: "невозможно прочитать файлы")
                        setBusy(false)
                        diagnostics.log("openrouter_preflight_failed", mapOf(
                            "chat_id" to requestChatId,
                            "request_id" to localRequestId,
                            "stage" to "wire_prepare",
                            "error_type" to e.javaClass.simpleName
                        ))
                    }
                }
            } finally { scope.close(); if (!handedToUi) releasePreparedFiles(preparedFiles) }
        }
    }


    private fun configuredMaxOutputTokens(): Int? {
        val raw = prefs.getString("max_output", "")?.trim().orEmpty()
        if (raw.isBlank() || (raw == "128000" && !prefs.getBoolean("max_output_explicit", false))) return null
        return raw.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    private fun buildPreflightWarning(text:String, totalFileBytes:Long, maxOut:Int?, effort:String):String? {
        val warnings=mutableListOf<String>()
        val historyChars=chat.sumOf { JsonProtocol.string(it, "text").length }
        if(totalFileBytes > 40L*1024L*1024L) warnings += "вложения уже занимают ${(totalFileBytes/1024.0/1024.0).toInt()} МБ"
        if(text.length > 100_000) warnings += "очень большой текст (${text.length/1000} тыс. символов)"
        if(historyChars > 250_000) warnings += "история этого чата уже очень длинная — ответ может быть дольше и дороже"
        if(effort=="max" && (text.length > 40_000 || (maxOut ?: 0) > 100_000)) warnings += "max + большой запрос может работать заметно дольше"
        return warnings.joinToString(" · ").takeIf{it.isNotBlank()}
    }

    private fun toolCaption(key:String):String = when(key){
        "web_search"->"🔎 Web Search"
        "file_search"->"📚 File Search"
        "code_interpreter"->"🐍 Code Interpreter"
        "image_generation"->"🎨 Image Generation"
        "computer"->"🖥 Computer Use"
        else->key
    }


    private fun renderToolActivity(){
        if(!::toolLabel.isInitialized)return
        if(toolStates.isEmpty()){toolLabel.text="";return}
        val text=toolStates.entries.joinToString("\n"){(key,state)->
            val suffix=when(state){"running"->"работает…";"completed"->"готово";"failed"->"ошибка";else->state}
            "${toolCaption(key)} — $suffix"
        }
        toolLabel.text=text
    }


    private fun attachReasoningSummaryPanel(card:LinearLayout, summary:String, initiallyExpanded:Boolean=false):TextView {
         val box=LinearLayout(this).apply{
             orientation=LinearLayout.VERTICAL
             setPadding(0,dp(8),0,dp(2))
         }
         val toggle=TextView(this).apply{
             text=if(summary.isBlank())"🧠 Модель размышляет…" else if(initiallyExpanded)"🧠 Скрыть ход рассуждений ▲" else "🧠 Показать ход рассуждений ▼"
             textSize=12.5f
             setTextColor(currentTheme().accent)
             setPadding(0,dp(2),0,dp(4))
         }
         val body=TextView(this).apply{
             text=summary
             textSize=13f
             setTextColor(currentTheme().textPrimary)
             setPadding(0,dp(2),0,dp(3))
             visibility=if(initiallyExpanded && summary.isNotBlank())View.VISIBLE else View.GONE
         }
         toggle.setOnClickListener{
             if(body.text.isNullOrBlank())return@setOnClickListener
             body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE
             toggle.text=if(body.visibility==View.VISIBLE)"🧠 Скрыть ход рассуждений ▲" else "🧠 Показать ход рассуждений ▼"
         }
         box.addView(toggle)
         box.addView(body)
         card.addView(box)
         return body
     }


    private fun renderAnnotations(
        annotations:JSONArray,
        card:LinearLayout,
        message:JSONObject,
        chatId:String,
        generation:Long
    ){
        val files=mutableListOf<JSONObject>()
        val urls=mutableListOf<JSONObject>()
        for(i in 0 until annotations.length()){
            val a=annotations.optJSONObject(i) ?: continue
            when(a.optString("type")){
                "container_file_citation", "file_citation", "local_file" -> files.add(a)
                "url_citation" -> urls.add(a)
            }
        }
        files.distinctBy{if (it.optString("type") == "local_file") it.optString("local_path") else it.optString("container_id")+"/"+JsonProtocol.string(it, "file_id")}.forEach{a->
            addGeneratedFileCard(a,card,message,chatId,generation)
        }
        if(urls.isNotEmpty()){
            val header=TextView(this).apply{
                text="Источники"
                textSize=12f
                setTypeface(null,Typeface.BOLD)
                setTextColor(currentTheme().textSecondary)
                setPadding(0,dp(9),0,dp(2))
            }
            card.addView(header)
            urls.distinctBy{it.optString("url")}.take(10).forEach{u->
                val raw=u.optJSONObject("url_citation") ?: u
                val url=raw.optString("url").trim()
                if(url.isBlank()) return@forEach
                val sourceTitle=raw.optString("title").ifBlank{url}
                card.addView(TextView(this).apply{
                    text="🔗 $sourceTitle"
                    setTextColor(currentTheme().accent); textSize=12.5f
                    isSingleLine=true; ellipsize=TextUtils.TruncateAt.END
                    setPadding(0,dp(2),0,dp(2))
                    setOnClickListener{
                        try{
                            val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply{
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            }
                            startActivity(intent)
                        }catch(_:Exception){status.text="Не удалось открыть ссылку"}
                    }
                })
            }
        }
    }

    private fun addGeneratedFileCard(
        annotation:JSONObject,
        parent:LinearLayout,
        message:JSONObject,
        chatId:String,
        generation:Long
    ){
        val annotationType = annotation.optString("type").trim()
        val containerId=annotation.optString("container_id").trim()
        val fileId=JsonProtocol.string(annotation, "file_id").trim()
        val filename=safeDisplayName(JsonProtocol.string(annotation, "filename").ifBlank{"file"})
        val state=annotation.optString("state").ifBlank{"PENDING"}
        var path=annotation.optString("local_path").takeIf{it.isNotBlank()}
        // A successful download may have survived an app update while the annotation
        // itself was migrated with a stale EXPIRED/PENDING state. Always prefer a valid local copy.
        if(fileId.isNotBlank()){
            val directCachedPath=path?.takeIf{File(it).isFile}
            val cached=if(directCachedPath==null) try{chatDb.findLocalFileByFileId(fileId)}catch(_:Exception){null} else null
            val dbCachedPath=cached?.let { JsonProtocol.text(it, "path") }.orEmpty().takeIf{it.isNotBlank() && File(it).isFile}
            // Filename is never a stable identity: another response may have the same report.txt.
            val cachedPath=directCachedPath ?: dbCachedPath
            if(cachedPath!=null && (state!="READY" || path!=cachedPath)){
                path=cachedPath
                annotation.put("state","READY").put("local_path",cachedPath)
                cached?.optString("mime_type")?.takeIf{it.isNotBlank()}?.let{annotation.put("mime_type",it)}
                val cachedSize=if(cached!=null) cached.optLong("size_bytes",File(cachedPath).length()) else File(cachedPath).length()
                annotation.put("size_bytes",cachedSize)
                chatDb.updateGeneratedFileState(chatId,fileId,"READY",cachedPath,annotation.optString("mime_type").takeIf{it.isNotBlank()},cachedSize,annotationType=annotationType)
                diagnostics.log("file_cache_restored",mapOf("chat_id" to chatId,"file_id" to fileId,"path" to cachedPath))
            }
        }
        if(path!=null && File(path).isFile && annotation.optString("state").ifBlank{"PENDING"}=="READY"){
            val box=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(8),dp(10),dp(8))
                background=rounded(currentTheme().cardBg,10)
            }
            parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            populateReadyFileCard(box,annotation,filename,path)
            return
        }

        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(8),dp(10),dp(8))
            background=rounded(currentTheme().cardBg,10)
        }
        parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        box.addView(TextView(this).apply {
            text = "📄 $filename"
            textSize = 14f; setTextColor(currentTheme().textPrimary); setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = if (annotationType in setOf("container_file_citation", "file_citation"))
                "Локальная копия недоступна. Загрузка из прямого OpenAI API отключена."
                else "Локальная копия файла недоступна"
            textSize = 11.5f; setTextColor(currentTheme().textSecondary)
            setPadding(0, dp(3), 0, dp(4))
        })

    }


    private fun populateReadyFileCard(box:LinearLayout,annotation:JSONObject,filename:String,path:String){
        box.removeAllViews()
        val mime=annotation.optString("mime_type").ifBlank{mimeForFilename(filename)}
        val size=annotation.optLong("size_bytes",File(path).length())
        if(annotation.optBoolean("received_source", false)) {
            box.addView(TextView(this).apply {
                text="Полученные исходные данные ответа. Файл может содержать только часть ответа; его можно сохранить без новой генерации."
                textSize=12.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(4),0,dp(5))
            })
        }else if(mime.startsWith("image/")){
            val preview=ImageView(this).apply{
                adjustViewBounds=true
                scaleType=ImageView.ScaleType.FIT_CENTER
                background=rounded(currentTheme().cardBg,8)
                setOnClickListener{showImagePreviewFromFile(path,filename)}
            }
            loadThumbnailAsync(preview,path,900)
            box.addView(preview,LinearLayout.LayoutParams(-1,dp(220)))
        }else if(isTextLikeMime(mime,filename)){
            box.addView(TextView(this).apply{
                text="📄 Текстовый файл — нажмите «Просмотр», чтобы прочитать"
                textSize=12.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(4),0,dp(5))
            })
        }else if(mime.equals("application/pdf",true)){
            box.addView(TextView(this).apply{
                text="📕 PDF — можно открыть первый лист в приложении"
                textSize=12.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(4),0,dp(5))
            })
        }
        box.addView(TextView(this).apply{
            text="✅ $filename";textSize=14f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD)
        })
        box.addView(TextView(this).apply{
            text="$mime · ${formatBytes(size)}";textSize=11.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(3),0,dp(6))
        })
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        when{
            mime.startsWith("image/") -> row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,filename)})
            isTextLikeMime(mime,filename) -> row.addView(fileActionButton("Просмотр"){showTextPreview(path,filename)})
            mime.equals("application/pdf",true) -> row.addView(fileActionButton("Просмотр"){showPdfPreview(path,filename)})
            else -> row.addView(fileActionButton("Открыть"){openLocalFile(path,filename,mime)})
        }
        row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,filename,mime)})
        row.addView(fileActionButton("Поделиться"){shareLocalFile(path,filename,mime)})
        box.addView(row)
        if (!JsonProtocol.string(annotation, "patch_diff_path").isBlank()) {
            box.addView(LocalArtifactDiffPanel(this, annotation))
        }
    }


    private fun fileActionButton(label:String,onClick:()->Unit)=TextView(this).apply{
        text=label;textSize=12.5f;setTextColor(currentTheme().accent)
        minHeight = dp(40)
        gravity = Gravity.CENTER
        background = rippleRounded(currentTheme().buttonBg, 10)
        setPadding(dp(10),dp(6),dp(10),dp(6))
        setOnClickListener{onClick()}
    }


    private fun renderGeneratedImages(message:JSONObject,parent:LinearLayout){
        val arr=message.optJSONArray("generated_images") ?: return
        for(i in 0 until arr.length()){
            val a=arr.optJSONObject(i) ?: continue
            if (a.optString("state") == "FAILED") {
                parent.addView(TextView(this).apply {
                    text = "⚠ Не удалось сохранить изображение №${a.optInt("source_index", i) + 1}: " +
                        JsonProtocol.string(a, "error")
                    textSize = 12f
                    setTextColor(Color.rgb(225, 180, 120))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                })
                continue
            }
            val path=a.optString("local_path").takeIf{it.isNotBlank()}
            val filename=safeDisplayName(JsonProtocol.string(a, "filename").ifBlank{"astra_image.png"})
            val mime=a.optString("mime_type").ifBlank{"image/png"}
            if(path==null || !File(path).isFile) continue
            val box=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(8),dp(10),dp(8))
                background=rounded(currentTheme().cardBg,10)
            }
            parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            box.addView(ImageView(this).apply{
                loadThumbnailAsync(this,path,1200)
                adjustViewBounds=true
                scaleType=ImageView.ScaleType.FIT_CENTER
                setPadding(0,0,0,dp(6))
                setOnClickListener{showImagePreviewFromFile(path,filename)}
            },LinearLayout.LayoutParams(-1,dp(280)))
            box.addView(TextView(this).apply{
                text="🎨 $filename · ${formatBytes(File(path).length())}"
                textSize=12f;setTextColor(currentTheme().textSecondary)
            })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,filename)})
            row.addView(fileActionButton("Открыть"){openLocalFile(path,filename,mime)})
            row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,filename,mime)})
            row.addView(fileActionButton("Поделиться"){shareLocalFile(path,filename,mime)})
            box.addView(row)
        }
    }

    private fun openLocalFile(path:String,filename:String,mime:String){
        try{
            val uri=FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.fileprovider",File(path))
            val intent=Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(uri,mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,"Открыть $filename"))
        }catch(e:Exception){status.text="Нет приложения для открытия ${filename}: ${e.message ?: "неизвестная ошибка"}"}
    }

    private fun requestSaveLocalFile(path:String,filename:String,mime:String){
        pendingSaveSourcePath=path
        pendingSaveFilename=filename
        pendingSaveMime=mime
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type=mime
            putExtra(Intent.EXTRA_TITLE,filename)
        }
        try{launchActivityForResult(i, pickSaveGenerated)}catch(e:Exception){status.text="Сохранение недоступно: ${e.message}"}
    }

    private fun shareLocalFile(path:String,filename:String,mime:String){
        try{
            val uri=FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.fileprovider",File(path))
            val intent=Intent(Intent.ACTION_SEND).apply{
                type=mime
                putExtra(Intent.EXTRA_STREAM,uri)
                putExtra(Intent.EXTRA_TEXT,filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,"Поделиться $filename"))
        }catch(e:Exception){status.text="Не удалось поделиться файлом: ${e.message}"}
    }

    private fun safeFileName(name:String):String{
        val cleaned=name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"),"_").trim().ifBlank{"file"}
        return cleaned.take(180)
    }
    private fun safeDisplayName(name:String):String=safeFileName(name)
    private fun mimeForFilename(name:String):String{
        val ext=name.substringAfterLast('.').lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when(ext){
            "json"->"application/json"; "csv"->"text/csv"; "md"->"text/markdown"; "txt"->"text/plain"; else->"application/octet-stream"
        }
    }
    private fun formatBytes(size:Long):String = when{
        size>=1024L*1024*1024->"%.1f GB".format(size/1024.0/1024.0/1024.0)
        size>=1024L*1024->"%.1f MB".format(size/1024.0/1024.0)
        size>=1024L->"%.1f KB".format(size/1024.0)
        else->"$size B"
    }

    private fun persistCurrentChatFor(chatId:String){
        if(currentChatId()!=chatId)return
        persistCurrentChat()
    }

    private fun showToolDetails() {
        if(toolLabel.text.isNullOrBlank()) return
        AppThemeViews.dialog(this, currentTheme())
            .setTitle("Инструменты")
            .setMessage(toolLabel.text)
            .setPositiveButton("OK",null).show()
    }


    private fun showUsageStats(){
        executor.execute{
            try{
                val now=System.currentTimeMillis()
                val cal=java.util.Calendar.getInstance().apply{
                    set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
                }
                val today=chatDb.usageTotals(cal.timeInMillis)
                val seven=chatDb.usageTotals(now-7L*86_400_000L)
                val all=chatDb.usageTotals(0L)
                val financialToday = chatDb.requestAccountingTotals(cal.timeInMillis)
                val financialSeven = chatDb.requestAccountingTotals(now-7L*86_400_000L)
                val financialAll = chatDb.requestAccountingTotals(0L)
                val message=formatUsageStats(today,seven,all) +
                    "\n\nПодтверждённые провайдером расходы (новые запросы после обновления):\n" +
                    listOf("Сегодня" to financialToday, "Последние 7 дней" to financialSeven, "За всё время" to financialAll)
                        .joinToString("\n") { (label, totals) ->
                            val known = totals.optLong("known_cost_requests")
                            val unknown = totals.optLong("unknown_cost_requests")
                            val sum = totals.optDouble("provider_reported_cost_usd")
                            val shown = if (sum > 0.0 && sum < 0.000001) {
                                "< $0.000001"
                            } else {
                                "$" + "%.6f".format(java.util.Locale.US, sum)
                            }
                            "$label: подтверждено $known, неизвестно $unknown, сумма подтверждённых $shown"
                        }
                runOnUiThread{
                    AppThemeViews.dialog(this, currentTheme())
                        .setTitle("Расход и токены")
                        .setMessage(message)
                        .setPositiveButton("OK",null)
                        .show()
                }
            }catch(e:Exception){
                runOnUiThread{status.text="Не удалось загрузить статистику: ${e.message}"}
            }
        }
    }

    private fun formatUsageStats(today:JSONObject,seven:JSONObject,all:JSONObject):String{
        fun block(title:String,o:JSONObject):String{
            val requests=o.optLong("requests")
            val success=o.optLong("success")
            val input=o.optLong("input_tokens")
            val cached=o.optLong("cached_tokens")
            val writes=o.optLong("cache_write_tokens")
            val output=o.optLong("output_tokens")
            val reasoning=o.optLong("reasoning_tokens")
            val known = o.optLong("known_cost_requests", 0L)
            val estimates = o.optLong("estimated_cost_requests", 0L)
            val unknown = o.optLong("unknown_cost_requests", (requests - known - estimates).coerceAtLeast(0L))
            val confirmed = o.optDouble("provider_reported_cost_usd", 0.0)
            val estimate = o.optDouble("estimated_cost_usd", 0.0)
            return buildString{
                append("$title\n")
                append("Запросов: $requests (успешных $success)\n")
                append("Input: ${fmtTokens(input)} · cache: ${fmtTokens(cached)}\n")
                append("Cache write: ${fmtTokens(writes)}\n")
                append("Output: ${fmtTokens(output)} · reasoning: ${fmtTokens(reasoning)}\n")
                append("Подтверждено по метрикам: ").append(known).append(" · $")
                    .append("%.6f".format(java.util.Locale.US, confirmed)).append('\n')
                if (estimates > 0) append("Предварительные оценки: ").append(estimates).append(" · $")
                    .append("%.6f".format(java.util.Locale.US, estimate)).append(" (не подтверждены)\n")
                append("Расход неизвестен: ").append(unknown).append(" запросов\n")
            }
        }
        return block("Сегодня",today)+"\n"+block("Последние 7 дней",seven)+"\n"+block("За всё время",all)+
            "\nИсторические оценки до журнала не входят в подтверждённые суммы. Неизвестные расходы не равны нулю."
    }

    private fun showLocalFiles(){
        executor.execute{
            val files=try{chatDb.listLocalFiles()}catch(_:Exception){emptyList()}
            runOnUiThread{
                val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(10))}
                if(files.isEmpty()){
                    box.addView(TextView(this).apply{ text="Пока нет локально сохранённых файлов.";textSize=14f;setTextColor(Color.LTGRAY);setPadding(0,dp(10),0,dp(10))})
                }else{
                    files.forEach{f->
                        val row=LinearLayout(this).apply{
                            orientation=LinearLayout.VERTICAL
                            setPadding(0,dp(8),0,dp(8))
                        }
                        val name=TextView(this).apply{
                            text=(if(f.optString("kind")=="image")"🖼 " else "📄 ")+safeDisplayName(JsonProtocol.string(f, "filename"))
                            textSize=14f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD)
                        }
                        row.addView(name)
                        row.addView(TextView(this).apply{
                            text="${f.optString("chat_title","Чат")} · ${formatBytes(f.optLong("size_bytes",0L))}"
                            textSize=11.5f;setTextColor(currentTheme().textSecondary)
                        })
                        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                        val path=JsonProtocol.string(f, "path"); val fn=safeDisplayName(JsonProtocol.string(f, "filename")); val mime=f.optString("mime_type").ifBlank{mimeForFilename(fn)}
                        when{
                            mime.startsWith("image/") -> actions.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,fn)})
                            isTextLikeMime(mime,fn) -> actions.addView(fileActionButton("Просмотр"){showTextPreview(path,fn)})
                            mime.equals("application/pdf",true) -> actions.addView(fileActionButton("Просмотр"){showPdfPreview(path,fn)})
                            else -> actions.addView(fileActionButton("Открыть"){openLocalFile(path,fn,mime)})
                        }
                        actions.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,fn,mime)})
                        actions.addView(fileActionButton("Поделиться"){shareLocalFile(path,fn,mime)})
                        actions.addView(fileActionButton("Удалить"){
                            AppThemeViews.dialog(this, currentTheme())
                                .setTitle("Удалить локальную копию?")
                                .setMessage(fn)
                                .setNegativeButton("Отмена",null)
                                .setPositiveButton("Удалить"){_,_->
                                    executor.execute{
                                        val ok=File(path).delete()
                                        chatDb.removeLocalFile(path)
                                        runOnUiThread{
                                            status.text=if(ok)"Локальная копия удалена" else "Запись удалена, но файл уже отсутствовал"
                                            showLocalFiles()
                                        }
                                    }
                                }.show()
                        })
                        row.addView(actions)
                        box.addView(row)
                        box.addView(View(this).apply{setBackgroundColor(currentTheme().buttonBg);layoutParams=LinearLayout.LayoutParams(-1,dp(1))})
                    }
                }
                AppThemeViews.dialog(this, currentTheme())
                    .setTitle("Локальные файлы")
                    .setView(ScrollView(this).apply{addView(box)})
                    .setNegativeButton("Закрыть",null)
                    .show()
            }
        }
    }

    // ---------- attachments ----------
    private fun showAttachmentSheet() {
        if (attachmentSheetDialog?.isShowing == true) return
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(30))
            background = GradientDrawable().apply {
                setColor(currentTheme().drawerBg)
                val r = dp(25).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "Добавить в чат"
            setTextColor(currentTheme().textPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 18f
            setPadding(dp(10), dp(6), 0, dp(12))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(TextView(this).apply {
            text = "✕"
            contentDescription = "Закрыть меню вложений"
            gravity = Gravity.CENTER
            textSize = 21f
            setTextColor(currentTheme().textPrimary)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(heading)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AttachmentMenuPolicy.actions.forEach { action ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rippleRounded(currentTheme().cardBg, 17)
                contentDescription = action.title
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    when (action) {
                        AttachmentMenuAction.CAMERA -> chooseCamera()
                        AttachmentMenuAction.PHOTO -> chooseImage()
                        AttachmentMenuAction.FILES -> chooseAny()
                    }
                }
            }
            tile.addView(TextView(this).apply {
                text = action.icon
                textSize = 27f
                gravity = Gravity.CENTER
            })
            tile.addView(TextView(this).apply {
                text = action.title
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(currentTheme().textPrimary)
                setPadding(0, dp(10), 0, 0)
            })
            actions.addView(tile, LinearLayout.LayoutParams(0, dp(120), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        panel.addView(actions)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (attachmentSheetDialog === dialog) attachmentSheetDialog = null
        }
        attachmentSheetDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
    }

    private fun chooseCamera() {
        val captured = File(filesDir, "attachments/camera_${UUID.randomUUID()}.jpg")
        try {
            captured.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(
                this, BuildConfig.APPLICATION_ID + ".fileprovider", captured
            )
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newRawUri("Camera photo", uri)
            }
            pendingCameraPath = captured.absolutePath
            pendingCameraChatId = currentChatId()
            pendingCameraProvider = "openrouter"
            launchActivityForResult(intent, pickCamera)
            if (pendingActivityResultCode != pickCamera) {
                pendingCameraPath = null
                pendingCameraChatId = null
                pendingCameraProvider = null
                captured.delete()
            }
        } catch (e: Exception) {
            pendingCameraPath = null
            pendingCameraChatId = null
            pendingCameraProvider = null
            captured.delete()
            status.text = "Камера недоступна: ${e.message}"
        }
    }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun completePendingCameraCapture(resultCode: Int) {
        val capturedPath = pendingCameraPath
        val sourceChatId = pendingCameraChatId
        pendingCameraPath = null
        pendingCameraChatId = null
        pendingCameraProvider = null

        val file = capturedPath?.let(::File)
        if (resultCode != RESULT_OK || file == null || !file.isFile || file.length() <= 0L ||
            sourceChatId.isNullOrBlank()) {
            file?.delete()
            if (resultCode == RESULT_OK) status.text = "Фото не получено"
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file)
        } catch (e: Exception) {
            status.text = "Фото недоступно: ${e.message}"
            return
        }
        if (sourceChatId == currentChatId()) {
            handleActivityResult(pickImage, RESULT_OK, Intent().setData(uri))
            return
        }

            executor.execute {
                try {
                    val attachment = OpenRouterAttachmentStore.classifyLocalFile(this, file, file.name, "image/jpeg")
                    saveOpenRouterAttachmentsToComposer(sourceChatId, listOf(attachment))
                    runOnUiThread { status.text = "Фото сохранено в исходном чате" }
                } catch (e: Exception) {
                    diagnostics.log("camera_source_chat_save_failed", mapOf(
                        "chat_id" to sourceChatId,
                        "error" to (e.message ?: e.toString()).take(300)
                    ))
                }
            }
            }


    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun setPendingCameraCaptureForTesting(path: String, chatId: String, provider: String = "openrouter") {
        pendingCameraPath = path
        pendingCameraChatId = chatId
        pendingCameraProvider = provider
    }

    private fun chooseAny() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // Chooser lists compatible handlers; GET_CONTENT grants are transient.
        launchActivityForResult(Intent.createChooser(intent, "Файлы"), pickAny)
    }
    private fun chooseImage() {
        pendingPickerChatId = currentChatId()
        pendingPickerProvider = normalizedProviderId()
        try {
            photoPickerLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        } catch (e: Exception) {
            pendingPickerChatId = null
            pendingPickerProvider = null
            status.text = "Фото недоступно: " + (e.message ?: "нет обработчика")
        }
    }
    private fun chooseVoice() {
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
        }
        if(i.resolveActivity(packageManager)!=null){
            try{ launchActivityForResult(i, pickVoice) }
            catch(e:Exception){ status.text="Голосовой ввод недоступен: ${e.message}" }
        } else {
            status.text="На устройстве не найден сервис распознавания речи"
        }
    }


    private fun prepareOpenRouterAttachment(uri: Uri, pickerSourceChatId: String) {
        val name = OpenRouterAttachmentStore.queryName(this, uri) ?: uri.lastPathSegment ?: "attachment"
        val uploadChatId = pickerSourceChatId
        val uploadGeneration = activeResponseState.state.generation
        beginUpload(uploadChatId, uploadGeneration)
        attachmentPreparationTail = attachmentPreparationTail.handleAsync({ _, _ ->
            try {
                val local = OpenRouterAttachmentStore.copyAndClassify(this, uri, name, openRouterAttachmentInputStreamSource)
                val prepared = if (local.kind == "zip") {
                    OpenRouterAttachmentStore.extractZipFile(this, File(local.path), local.name) { message ->
                        diagnostics.log("openrouter_zip", mapOf("filename" to local.name, "message" to message.take(300)))
                    }
                } else listOf(local)
                val target = OpenRouterBackgroundOwnershipPolicy.decide(
                    uploadChatId, currentChatId(), uploadGeneration, activeResponseState.state.generation, activityVisible, false
                )
                when (target) {
                    OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER -> runOnUiThread {
                        val rechecked = OpenRouterBackgroundOwnershipPolicy.decide(
                            uploadChatId, currentChatId(), uploadGeneration, activeResponseState.state.generation, activityVisible, false
                        )
                        if (rechecked == OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER) {
                            prepared.forEach { item ->
                                if (attachedOpenRouterAttachments.none { it.sha256 == item.sha256 && it.name == item.name }) attachedOpenRouterAttachments.add(item)
                            }
                            if (local.kind == "zip") File(local.path).delete()
                            refreshAttachChips()
                            persistComposerStateNow()
                            status.text = if (local.kind == "zip") "ZIP подготовлен · файлов: " + prepared.size else "Вложение готово: " + local.name
                        } else if (rechecked == OpenRouterAttachmentCompletionTarget.SOURCE_CHAT) {
                            saveOpenRouterAttachmentsToComposer(uploadChatId, prepared)
                            if (local.kind == "zip") File(local.path).delete()
                        } else {
                            deleteOpenRouterAttachmentFiles(prepared)
                            if (local.kind == "zip") File(local.path).delete()
                        }
                    }
                    OpenRouterAttachmentCompletionTarget.SOURCE_CHAT -> {
                        saveOpenRouterAttachmentsToComposer(uploadChatId, prepared)
                        if (local.kind == "zip") File(local.path).delete()
                    }
                    OpenRouterAttachmentCompletionTarget.DISCARD -> {
                        deleteOpenRouterAttachmentFiles(prepared)
                        if (local.kind == "zip") File(local.path).delete()
                    }
                }
            } catch (e: Exception) {
                if (OpenRouterBackgroundOwnershipPolicy.decide(
                        uploadChatId,
                        currentChatId(),
                        uploadGeneration,
                        activeResponseState.state.generation,
                        activityVisible,
                        false
                    ) == OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER
                ) {
                    runOnUiThread { status.text = "Ошибка вложения: " + (e.message ?: "не удалось обработать файл") }
                } else {
                    diagnostics.log("openrouter_attachment_prepare_stale_failure", mapOf("chat_id" to uploadChatId, "error" to (e.message ?: e.toString()).take(300)))
                }
            } finally { endUpload(uploadChatId, uploadGeneration) }
            Unit
        }, executor)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun handleActivityResult(r:Int,c:Int,d:Intent?){
        if(c!=RESULT_OK || d==null) {
            if (r == pickExportTxt) {
                exportSnapshots.delete(pendingExportPath)
                pendingExportPath = null
            }
            return
        }
        if(r==pickExportTxt){
            val uri=d.data ?: run {
                exportSnapshots.delete(pendingExportPath)
                pendingExportPath=null
                return
            }
            val source=pendingExportPath
            pendingExportPath=null
            if(source.isNullOrBlank()){ status.text="Экспорт больше недоступен"; return }
            executor.execute {
                try {
                    val snapshot=exportSnapshots.requireFile(source)
                    val out=contentResolver.openOutputStream(uri)
                        ?: throw IOException("Не удалось открыть файл для записи")
                    snapshot.inputStream().use { input -> out.use { output -> input.copyTo(output, 64*1024) } }
                    runOnUiThread { if (activityVisible && !isFinishing && !isDestroyed) status.text="Экспорт сохранён" }
                } catch (_: Exception) {
                    runOnUiThread { if (activityVisible && !isFinishing && !isDestroyed) status.text="Не удалось сохранить экспорт" }
                } finally { exportSnapshots.delete(source) }
            }
            return
        }
        if(r==pickSaveGenerated){
            val uri=d.data ?: return
            val source=pendingSaveSourcePath
            if(source.isNullOrBlank()){ status.text="Файл больше недоступен"; return }
            pendingSaveSourcePath=null
            pendingSaveFilename=null
            pendingSaveMime=null
            executor.execute {
                try {
                    val sourceFile=File(source)
                    if(!sourceFile.isFile) throw IOException("Локальная копия файла не найдена")
                    val out=contentResolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл для записи")
                    sourceFile.inputStream().use{input->out.use{output->input.copyTo(output,64*1024)}}
                    runOnUiThread { status.text="Файл сохранён" }
                }catch(e:Exception){runOnUiThread { status.text="Ошибка сохранения: ${e.message}" }}
            }
            return
        }
        if(r==pickVoice){
            val results=d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heard=results?.firstOrNull()
            if(!heard.isNullOrBlank()){
                val current=input.text.toString()
                input.setText(if(current.isBlank()) heard else "$current $heard")
                input.setSelection(input.text.length)
            }
            return
        }
        val pickerSourceChatId = pendingPickerChatId ?: currentChatId()
        if (r == pickImage || r == pickAny) {
            val selectedUris = urisFromIntent(d)
            if (selectedUris.size > OpenRouterAttachmentStore.MAX_ATTACHMENTS) {
                status.text = "Слишком много вложений: максимум " + OpenRouterAttachmentStore.MAX_ATTACHMENTS
                return
            }
            selectedUris.forEach { uri ->
                prepareOpenRouterAttachment(uri, pickerSourceChatId)
            }
            return
        }
    }

    private fun urisFromIntent(data: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        data.data?.let { out.add(it) }
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) out.add(clip.getItemAt(i).uri) }
        return out.distinct()
    }

    private fun launchActivityForResult(intent: Intent, requestCode: Int) {
        pendingActivityResultCode = requestCode
        pendingPickerChatId = currentChatId()
        pendingPickerProvider = normalizedProviderId()
        try {
            activityResultLauncher.launch(intent)
        } catch (e: Exception) {
            pendingActivityResultCode = null
            pendingPickerChatId = null
            pendingPickerProvider = null
            if (requestCode == pickExportTxt) {
                exportSnapshots.delete(pendingExportPath)
                pendingExportPath = null
            }
            status.text = "Операция недоступна: ${e.message}"
        }
    }


    /** ZIP ownership is bound to the selected source draft, never to the latest UI. */
    private fun sourceStillOwnsZips(chatId: String, selected: List<Pair<String, String>>): Boolean {
        if (chatDb.getChatTitle(chatId) == null) return false
        val current = if (chatId == currentChatId()) {
            pendingUploadUris.toSet()
        } else {
            val raw = prefs.getString(composerPrefsKey(chatId), null) ?: return false
            try {
                val array = JSONObject(raw).optJSONArray("pending_uploads") ?: return false
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { it.optString("uri") to it.optString("kind") }
                }.toSet()
            } catch (_: Exception) { return false }
        }
        return selected.all { it in current } &&
            selected.none { (raw, kind) -> cancelledUploads.contains("$kind|$raw") }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun preparePendingOpenRouterZipsThenSend(regenerate: Boolean) {
        if (preparingZipAttachments) return
        val zipItems = pendingUploadUris.filter { it.second == "zip" }.toList()
        if (zipItems.isEmpty()) return
        val sourceChatId = currentChatId()
        val sourceGeneration = activeResponseState.state.generation
        val token = UUID.randomUUID().toString()
        activeZipOperation = token
        preparingZipAttachments = true
        send.isEnabled = false
        status.text = "Готовлю ZIP-вложение…"
        executor.execute {
            val extracted = mutableListOf<OpenRouterLocalAttachment>()
            try {
                zipItems.forEach { (rawUri, _) ->
                    if (activeZipOperation != token || !sourceStillOwnsZips(sourceChatId, zipItems)) {
                        throw CancellationException("ZIP preparation was cancelled")
                    }
                    val uri = Uri.parse(rawUri)
                    val zipName = OpenRouterAttachmentStore.queryName(this, uri)
                        ?: uri.lastPathSegment ?: "archive.zip"
                    extracted += OpenRouterAttachmentStore.extractZip(this, uri, zipName, { message ->
                        diagnostics.log("openrouter_zip_skip", mapOf(
                            "chat_id" to sourceChatId, "message" to message.take(300)
                        ))
                    }, openRouterAttachmentInputStreamSource)
                }
                runOnUiThread {
                    val owned = activeZipOperation == token &&
                        sourceStillOwnsZips(sourceChatId, zipItems)
                    val target = OpenRouterBackgroundOwnershipPolicy.decide(
                        sourceChatId, currentChatId(), sourceGeneration,
                        activeResponseState.state.generation, activityVisible, !owned
                    )
                    if (owned && target == OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER) {
                        zipItems.forEach { pendingUploadUris.remove(it) }
                        extracted.forEach { attachment ->
                            if (attachedOpenRouterAttachments.none {
                                it.sha256 == attachment.sha256 && it.name == attachment.name
                            }) attachedOpenRouterAttachments.add(attachment)
                        }
                        persistComposerStateNow()
                        refreshAttachChips()
                        status.text = "ZIP подготовлен · файлов: " + extracted.size
                    } else if (owned && target == OpenRouterAttachmentCompletionTarget.SOURCE_CHAT) {
                        saveOpenRouterAttachmentsToComposer(sourceChatId, extracted, zipItems)
                        diagnostics.log("openrouter_zip_saved_source_chat", mapOf("chat_id" to sourceChatId))
                    } else {
                        deleteOpenRouterAttachmentFiles(extracted)
                    }
                    if (activeZipOperation == token) {
                        activeZipOperation = null
                        preparingZipAttachments = false
                    }
                    if (currentChatId() == sourceChatId && stop.visibility != View.VISIBLE) send.isEnabled = true
                    if (owned && target == OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER) {
                        sendMessage(regenerate)
                    }
                }
            } catch (e: Exception) {
                deleteOpenRouterAttachmentFiles(extracted)
                runOnUiThread {
                    if (activeZipOperation == token) {
                        activeZipOperation = null
                        preparingZipAttachments = false
                        if (stop.visibility != View.VISIBLE) send.isEnabled = true
                        if (currentChatId() == sourceChatId && activityVisible) {
                            refreshAttachChips()
                            status.text = "Ошибка ZIP: " + (e.message ?: "не удалось обработать архив")
                        }
                    }
                }
            }
        }
    }


    private fun queryName(uri:Uri):String?{
        var n:String?=null
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
            if(c.moveToFirst()) n=c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        return n
    }


    private fun composerPrefsKey(chatId:String = currentChatId()) = "composer_state_$chatId"

    private fun persistComposerStateDebounced(){
        draftSaveRunnable?.let(mainHandler::removeCallbacks)
        draftSaveRunnable=Runnable{persistComposerStateNow()}
        mainHandler.postDelayed(draftSaveRunnable!!,300L)
    }

    private fun persistComposerStateNow(){
        val id=currentChatId()
        if(id.isBlank() || !::input.isInitialized)return
        synchronized(composerMergeLock) {
        try{
            val obj=JSONObject()
                .put("text",input.text.toString())
                .put("saved_at",System.currentTimeMillis())
            val ids=JSONArray();attachedFileIds.forEach{ids.put(it)}
            val names=JSONArray();attachedFileNames.forEach{names.put(it)}
            val sizes=JSONArray();attachedFileSizes.forEach{sizes.put(it)}
            val paths=JSONArray();attachedFilePaths.forEach{paths.put(it)}
            val mimes=JSONArray();attachedFileMimes.forEach{mimes.put(it)}
            val openRouter=JSONArray();attachedOpenRouterAttachments.forEach{openRouter.put(it.toJson())}
            obj.put("file_ids",ids).put("file_names",names).put("file_sizes",sizes).put("file_paths",paths).put("file_mimes",mimes)
            obj.put("openrouter_attachments", openRouter)
            obj.put("pending_attachments", JSONArray().apply { pendingAttachments.forEach { put(it.toJson().put("type", it.kind)) } })
            pendingImagePath?.let{obj.put("image_path",it)}
            pendingImageFileId?.let{obj.put("image_file_id",it)}
            pendingImageName?.let{obj.put("image_name",it)}
            pendingImageMime?.let{obj.put("image_mime",it)}
            obj.put("image_size",pendingImageSize.coerceAtLeast(0L))
            val pending=JSONArray()
            pendingUploadUris.forEach { (uri,kind) ->
                pending.put(JSONObject().put("uri",uri).put("kind",kind))
            }
            obj.put("pending_uploads",pending)
            prefs.edit().putString(composerPrefsKey(id),obj.toString()).commit()
        }catch(_:Exception){}
        }
    }

    private fun listComposerLocalFilePaths(chatId:String):List<String>{
        if (chatId.isBlank()) return emptyList()
        val raw = prefs.getString(composerPrefsKey(chatId), null) ?: return emptyList()
        return try {
            LocalAttachmentReferences.composerPaths(JSONObject(raw)).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deleteOwnedLocalFile(path:String){
        if(path.isBlank())return
        try{
            val file=File(path).canonicalFile
            val roots=listOf(filesDir,cacheDir).map{it.canonicalFile}
            if(roots.none{file.path==it.path || file.path.startsWith(it.path+File.separator)}){
                diagnostics.log("local_file_delete_skipped",mapOf("path" to path.take(300),"reason" to "outside_app_storage"))
                return
            }
            if(file.isFile && file.delete()){
                diagnostics.log("local_file_deleted",mapOf("path" to file.path.take(300)))
            }
        }catch(e:Exception){
            diagnostics.log("local_file_delete_failed",mapOf("path" to path.take(300),"error" to (e.message?:e.toString()).take(300)))
        }
    }

    private fun clearComposerState(chatId:String){
        if(chatId.isBlank())return
        synchronized(composerMergeLock) {
            prefs.edit().remove(composerPrefsKey(chatId)).commit()
        }
    }

    private fun restoreComposerState(){
        val id=currentChatId()
        val raw=prefs.getString(composerPrefsKey(id),null)
        if(raw.isNullOrBlank())return
        try{
            val o=JSONObject(raw)
            restoringComposer=true
            input.setText(JsonProtocol.string(o, "text",""))
            input.setSelection(input.text.length)
            clearAttachedSelection()
            o.optJSONArray("file_ids")?.let{a->for(i in 0 until a.length())attachedFileIds.add(JsonProtocol.string(a, i))}
            o.optJSONArray("file_names")?.let{a->for(i in 0 until a.length())attachedFileNames.add(JsonProtocol.string(a, i))}
            o.optJSONArray("file_sizes")?.let{a->for(i in 0 until a.length())attachedFileSizes.add(a.optLong(i,0L))}
            o.optJSONArray("file_paths")?.let{a->for(i in 0 until a.length())attachedFilePaths.add(JsonProtocol.string(a, i))}
            o.optJSONArray("file_mimes")?.let{a->for(i in 0 until a.length())attachedFileMimes.add(JsonProtocol.string(a, i))}
            while(attachedFileSizes.size<attachedFileIds.size)attachedFileSizes.add(0L)
            while(attachedFilePaths.size<attachedFileIds.size)attachedFilePaths.add("")
            while(attachedFileMimes.size<attachedFileIds.size)attachedFileMimes.add("")
            attachedOpenRouterAttachments.clear()
            (o.optJSONArray("openrouter_attachments") ?: o.optJSONArray("pending_attachments"))?.let { a ->
                for(i in 0 until a.length()) a.optJSONObject(i)?.let { item ->
                    try {
                        val attachment=OpenRouterLocalAttachment.fromJson(item)
                        val refreshed = if (OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(attachment)) {
                            OpenRouterAttachmentStore.classifyLocalFile(this, File(attachment.path), attachment.name, attachment.mime)
                        } else attachment
                        if (refreshed.path.isNotBlank() && File(refreshed.path).isFile) attachedOpenRouterAttachments.add(refreshed)
                    } catch (_:Exception) {}
                }
            }
            pendingImagePath=JsonProtocol.string(o, "image_path").takeIf{it.isNotBlank() && File(it).isFile}
            pendingImageFileId=JsonProtocol.string(o, "image_file_id").takeIf{it.isNotBlank() && it!="null"}
            pendingImageName=JsonProtocol.string(o, "image_name").takeIf{it.isNotBlank()}
            pendingImageMime=JsonProtocol.string(o, "image_mime").takeIf{it.isNotBlank()}
            pendingImageSize=o.optLong("image_size",pendingImagePath?.let{File(it).length()} ?: 0L)
            pendingUploadUris.clear()
            o.optJSONArray("pending_uploads")?.let{a->
                for(i in 0 until a.length()){
                    val item=a.optJSONObject(i) ?: continue
                    val uri=item.optString("uri").takeIf{it.isNotBlank()} ?: continue
                    val kind=item.optString("kind").ifBlank{"file"}
                    pendingUploadUris.add(uri to kind)
                }
            }
            refreshAttachChips()
        }catch(_:Exception){}finally{
            restoringComposer=false
        }
    }

    // ---------- storage ----------
    private fun ensureChatStore(legacyMigrationPending: Boolean = false){
        val existing=currentChatId()
        if(existing.isBlank()){
            val id = newChatId()
            prefs.edit().putString("current_chat",id).apply()
            chatDb.ensureChat(id,"Новый чат", legacyMigrationPlaceholder = legacyMigrationPending)
        } else {
            chatDb.ensureChat(existing,"Новый чат", legacyMigrationPlaceholder = legacyMigrationPending)
        }
    }

    private fun currentChatTitleSafe(): String {
        val id=currentChatId()
        return chatDb.getChatTitle(id) ?: "Новый чат"
    }
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun currentChatIdForTesting(): String = currentChatId()

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun activeGenerationForTesting(): Long = activeResponseState.state.generation

    private fun currentChatId()=prefs.getString("current_chat","") ?: ""
    private fun currentChatTitle():String = currentChatTitleSafe()
    private fun currentChatObj():JSONObject = chatDb.getChat(currentChatId())
        ?: JSONObject().put("id",currentChatId()).put("title","Новый чат").put("messages",JSONArray())

    private fun ensureChatEntry(id:String,titleText:String){
        chatDb.ensureChat(id,titleText)
    }
    private fun loadAllChats():List<JSONObject> = chatDb.listChatSummaries()

    private fun persistCurrentChat(){
        val id=currentChatId()
        if(id.isBlank())return
        var chatTitle=currentChatTitleSafe()
        if(chat.isNotEmpty() && chatTitle=="Новый чат"){
            val first=chat.firstOrNull{it.optString("role")=="user"}?.let { JsonProtocol.text(it, "text") }.orEmpty()
            chatTitle=first.take(36).ifBlank{"Новый чат"}
        }
        chatDb.upsertUiSnapshotPreservingTail(id,chatTitle,currentResponseId,baselineEffort,chat)
        if(::title.isInitialized) title.text=chatTitle
        if(::drawerList.isInitialized) refreshChatDrawer()
    }
    private fun restoreCurrentChat(){
        val targetChatId = currentChatId()
        if (localEvidenceSnapshotChatId != targetChatId) localEvidenceEligible = false
        if (displayedChatId == targetChatId && ::scroll.isInitialized && scroll.height > 0 &&
            ::messageAdapter.isInitialized && messageAdapter.itemCount > 0 &&
            pendingScrollRestoreChatId != targetChatId
        ) {
            captureCurrentScrollAnchor()
        }
        val savedAnchor = if (pendingScrollRestoreChatId == targetChatId) pendingScrollRestoreAnchor
            else perChatScrollAnchors[targetChatId]
        val switchingChats = displayedChatId != null && displayedChatId != targetChatId
        if (switchingChats) {
            messageAdapter.clearItems()
        }
        chat.clear()
        clearAttachedSelection()
        refreshAttachChips()
        activeAssistantItemId=null
        val c=currentChatObj()
        title.text=c.optString("title","Новый чат")
        currentResponseId=c.optString("response_id").takeIf{it.isNotBlank() && it!="null"}
        baselineEffort=c.optString("baseline_effort").takeIf{it.isNotBlank() && it!="null"}
        val arr=c.optJSONArray("messages") ?: JSONArray()
        val restoredItems=mutableListOf<ChatBubbleItem>()
        for(i in 0 until arr.length()){
            val m=arr.optJSONObject(i)?:continue
            val role=m.optString("role")
            val t=JsonProtocol.string(m, "text")
            chat.add(m)
            restoredItems += ChatBubbleItem.create(
                id=ChatScrollPolicy.messageIdentity(i, role=="user", m),
                label=if(role=="user")"Ты" else AssistantModelLabel.text(m),
                text=t,
                user=role=="user",
                annotations=m.optJSONArray("annotations"),
                message=m,
                chatIndex=chat.lastIndex,
                reasoningSummary=m.optString("reasoning_summary").trim().takeIf{it.isNotBlank()}
            )
            if(role=="user")lastUserText=t
        }
        val restoredTools = chat.lastOrNull { it.optString("role") == "assistant" }
            ?.optJSONArray("tools")
        toolLabel.text = restoredTools?.let { tools ->
            (0 until tools.length()).map { tools.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString("  ")
        }.orEmpty()
        val active=activeResponsePersistence.getActiveResponse(currentChatId())
        if (active != null && OpenRouterResponseRuntime.contains(active.optString("request_id"))) {
            val partial=JsonProtocol.string(active, "text")
            val pendingModel = chatDb.getChat(currentChatId())?.optJSONArray("messages")?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("request_id") == active.optString("request_id") }
            }
            val activeLabel = AssistantModelLabel.text(
                pendingModel?.let {
                    JSONObject().put("provider", it.optString("provider", "openrouter"))
                        .put("requested_model", it.optString("requested_model"))
                } ?: JSONObject()
            )
            val activeId = ChatScrollPolicy.activeAssistantIdentity(
                JsonProtocol.nonBlank(active, "request_id"),
                JsonProtocol.nonBlank(active, "response_id"),
                restoredItems.size
            )
            restoredItems += ChatBubbleItem.create(
                id = activeId,
                label = activeLabel,
                text = partial,
                user = false
            )
            activeAssistantItemId = activeId
            status.text=if(active.optString("status")=="queued")"Модель в очереди…" else "Продолжается генерация…"
            setBusy(true)
        } else if (active != null) {
            status.text = "Сохранён незавершённый запрос. Для новой отправки остановите его явно."
            setBusy(true)
        } else {
            val restoredStatus = chat.lastOrNull { it.optString("role") == "assistant" }?.optString("status")
            status.text = ResponseStatusLabel.restored(restoredStatus)
            setBusy(false)
        }
        restoreComposerState()
        displayedChatId = targetChatId
        val restoreGeneration = beginScrollRestore(targetChatId, savedAnchor)
        refreshLocalEvidenceEligibility()
        messageAdapter.submitItems(restoredItems) {
            applyRestoredScrollState(targetChatId, savedAnchor, restoreGeneration)
            applyLocalEvidenceEligibility()
        }
    }

    private fun refreshLocalEvidenceEligibility() {
        val sourceChatId = currentChatId()
        val version = ++localEvidencePresenceVersion
        localEvidenceSourceSession?.invalidate()
        localEvidenceSourceSession = null
        if (localEvidenceSnapshotChatId != sourceChatId) localEvidenceEligible = false
        localEvidenceSnapshotChatId = sourceChatId
        if (sourceChatId.isBlank() || executor.isShutdown) {
            localEvidencePresencePending = false
            return
        }
        evidenceSourceSession(sourceChatId)
        val generation = activeResponseState.state.generation
        localEvidencePresencePending = true
        executor.execute {
            val eligible = try { chatDb.hasLocalEvidenceCandidates(sourceChatId) } catch (_: Exception) { false }
            runOnUiThread {
                if (version != localEvidencePresenceVersion || sourceChatId != currentChatId() || isFinishing || isDestroyed) return@runOnUiThread
                localEvidencePresencePending = false
                if (generation != activeResponseState.state.generation) {
                    refreshLocalEvidenceEligibility()
                    return@runOnUiThread
                }
                localEvidenceEligible = eligible
                applyLocalEvidenceEligibility()
            }
        }
    }

    private fun applyLocalEvidenceEligibility() {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized || messageAdapter.isListCommitPending()) return
        for (index in 0 until scroll.childCount)
            scroll.getChildAt(index).findViewWithTag<RichResponsePanel>("rich-response")?.let(::applyLocalEvidenceState)
    }

    private fun evidenceSourceSession(chatId: String): LocalEvidenceSourceSession {
        localEvidenceSourceSession?.takeIf { it.chatId == chatId && it.version == localEvidencePresenceVersion }?.let { return it }
        localEvidenceSourceSession?.invalidate()
        return LocalEvidenceSourceSession(chatId, localEvidencePresenceVersion, executor) { capturedChat ->
            localEvidenceSourcesHookForTests?.invoke() ?: LocalEvidenceSources.forChat(applicationContext, capturedChat)
        }.also { localEvidenceSourceSession = it }
    }

    private fun applyLocalEvidenceState(panel: RichResponsePanel) {
        val chatId = currentChatId()
        val session = evidenceSourceSession(chatId)
        panel.setEvidenceSources(session.version, session::sources)
        panel.setEvidenceEligible(localEvidenceSnapshotChatId == chatId && localEvidenceEligible)
    }

    private fun restoreAttachmentSelectionFromMessage(message:JSONObject){
        clearAttachedSelection()
        message.optJSONArray("file_ids")?.let{arr->for(i in 0 until arr.length())attachedFileIds.add(JsonProtocol.string(arr, i))}
        message.optJSONArray("file_names")?.let{arr->for(i in 0 until arr.length())attachedFileNames.add(JsonProtocol.string(arr, i))}
        message.optJSONArray("file_sizes")?.let{arr->for(i in 0 until arr.length())attachedFileSizes.add(arr.optLong(i,0L))}
        message.optJSONArray("file_paths")?.let{arr->for(i in 0 until arr.length())attachedFilePaths.add(JsonProtocol.string(arr, i))}
        message.optJSONArray("file_mimes")?.let{arr->for(i in 0 until arr.length())attachedFileMimes.add(JsonProtocol.string(arr, i))}
        while(attachedFileSizes.size<attachedFileIds.size)attachedFileSizes.add(0L)
        while(attachedFilePaths.size<attachedFileIds.size)attachedFilePaths.add("")
        while(attachedFileMimes.size<attachedFileIds.size)attachedFileMimes.add("")
        attachedOpenRouterAttachments.clear()
        message.optJSONArray("attachments")?.let { arr ->
            for(i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                try {
                    val a = OpenRouterLocalAttachment.fromJson(it)
                    if (File(a.path).isFile) attachedOpenRouterAttachments.add(a)
                } catch (_:Exception) {}
            }
        }
        JsonProtocol.string(message, "image_path").takeIf{it.isNotBlank() && File(it).isFile}?.let{pendingImagePath=it}
        JsonProtocol.string(message, "image_file_id").takeIf{it.isNotBlank() && it!="null"}?.let{pendingImageFileId=it}
        pendingImageName=JsonProtocol.string(message, "image_name").takeIf{it.isNotBlank()}
        pendingImageMime=JsonProtocol.string(message, "image_mime").takeIf{it.isNotBlank()}
        pendingImageSize=message.optLong("image_size",pendingImagePath?.let{File(it).length()} ?: 0L)
    }

    private fun editMessage(idx:Int){
        if(idx<0 || idx>=chat.size) return
        if(chat[idx].optString("role")!="user") return
        val editingChatId = currentChatId()
        val active = activeResponsePersistence.getActiveResponse(editingChatId)
        val editingRequestId = active?.optString("request_id").takeIf { !it.isNullOrBlank() && it != "null" }
        if (editingRequestId != null) {
            val stopped = try { OpenRouterResponseRuntime.stop(this, editingChatId, editingRequestId, false) }
            catch (error: Exception) {
                diagnostics.log("openrouter_edit_stop_save_failed", mapOf("error_type" to error.javaClass.simpleName))
                status.text = "Не удалось сохранить остановку. История сохранена; повторите редактирование."
                return
            }
            if (!stopped) {
                restoreCurrentChat()
                status.text = "Ответ уже завершён. Повторите редактирование."
                return
            }
            OpenRouterResponseRuntime.cancel(editingRequestId, "Сообщение изменено")
        }
        activeResponseState.nextGeneration()
        cancelOpenRouterRequest()
        openRouterModelPreflightCall.getAndSet(null)?.cancel()
        cancelPendingGitHubConfirmation()
        val message=chat[idx]
        val text=JsonProtocol.string(message, "text")

        restoreAttachmentSelectionFromMessage(message)

        while(chat.size>idx) chat.removeAt(chat.size-1)
        messageAdapter.removeFrom(idx)
        currentResponseId=chat.lastOrNull{it.optString("role")=="assistant"}
            ?.optString("response_id")?.takeIf{it.isNotBlank() && it!="null"}
        if(chat.isEmpty()) baselineEffort=null
        chatDb.upsertCurrentChat(currentChatId(), currentChatTitleSafe(), currentResponseId, baselineEffort, chat)
        input.setText(text)
        input.setSelection(input.text.length)
        refreshAttachChips()
        input.requestFocus()
        setBusy(false)
        status.text="Отредактируйте и отправьте снова"
    }

    private fun restoreLastUserAttachments(){
        val last=chat.lastOrNull{it.optString("role")=="user"} ?: return
        restoreAttachmentSelectionFromMessage(last)
    }

    private fun decodeThumbnailFromFile(path:String, maxDim:Int=640): Bitmap?{
        return try{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
            var sample=1
            while(bounds.outWidth/sample>maxDim || bounds.outHeight/sample>maxDim) sample*=2
            val opts=BitmapFactory.Options().apply{inSampleSize=sample; inPreferredConfig=Bitmap.Config.RGB_565}
            BitmapFactory.decodeFile(path,opts)?.let { ImageOrientation.upright(it, path) }
        }catch(_:Exception){null}
    }


    // ---------- attachment UI / previews ----------
    private fun attachmentKey(uri:Uri, kind:String) = "$kind|${uri}"

    private fun clearAttachedSelection(){
        attachedFileIds.clear()
        attachedFileNames.clear()
        attachedFileSizes.clear()
        attachedFilePaths.clear()
        attachedFileMimes.clear()
        attachedOpenRouterAttachments.clear()
        pendingImagePath=null
        pendingImageFileId=null
        pendingImageName=null
        pendingImageMime=null
        pendingImageSize=0L
        pendingUploadUris.clear()
        uploadProgress.clear()
        pendingPreviewBitmaps.clear()
        uploadCards.clear()
    }

    private fun addAssetSectionHeader(parent:LinearLayout){
        parent.addView(View(this).apply{
            setBackgroundColor(currentTheme().buttonBg)
            layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(2)}
        })
    }

    private fun hasAssistantAssets(message:JSONObject):Boolean{
        if(message.optJSONArray("generated_images")?.length()?.let{it>0}==true)return true
        val anns=message.optJSONArray("annotations") ?: return false
        for(i in 0 until anns.length()){
            when(anns.optJSONObject(i)?.optString("type")){
                "container_file_citation","file_citation" -> return true
            }
        }
        return false
    }

    private fun cardBackground() = rounded(currentTheme().cardBg,10)

    private fun addCardDivider(parent:LinearLayout){
        parent.addView(View(this).apply{setBackgroundColor(currentTheme().buttonBg);layoutParams=LinearLayout.LayoutParams(-1,dp(1))})
    }

    private fun createAttachmentCard():LinearLayout = LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(10),dp(8),dp(10),dp(8))
        background=cardBackground()
    }

    private val thumbnailCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val thumbnailRequests = java.util.WeakHashMap<ImageView,String>()

    private fun thumbnailCacheKey(path:String,maxDim:Int):String{
        val file=File(path)
        return file.absolutePath+"|"+file.lastModified()+"|"+file.length()+"|"+maxDim
    }

    private fun loadThumbnailAsync(view:ImageView,path:String,maxDim:Int=640){
        view.setImageResource(android.R.drawable.ic_menu_gallery)
        val key=thumbnailCacheKey(path,maxDim)
        synchronized(thumbnailRequests){thumbnailRequests[view]=key}
        thumbnailCache.get(key)?.let{
            runOnUiThread{
                if(isFinishing || isDestroyed)return@runOnUiThread
                synchronized(thumbnailRequests){
                    if(thumbnailRequests[view]==key)view.setImageBitmap(it)
                }
            }
            return
        }
        executor.execute{
            val bmp=decodeThumbnailFromFile(path,maxDim)
            if(bmp!=null)thumbnailCache.put(key,bmp)
            runOnUiThread{
                if(isFinishing || isDestroyed || bmp==null)return@runOnUiThread
                synchronized(thumbnailRequests){
                    if(thumbnailRequests[view]!=key)return@synchronized
                    view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun showImagePreviewFromFile(path:String,name:String,uiOwner:()->Boolean={true}){
        executor.execute{
            val key=thumbnailCacheKey(path,1800)
            val bmp=thumbnailCache.get(key) ?: decodeThumbnailFromFile(path,1800)?.also{thumbnailCache.put(key,it)}
            runOnUiThread{
                if(isFinishing || isDestroyed || !uiOwner())return@runOnUiThread
                if(bmp==null){status.text="Не удалось прочитать изображение";return@runOnUiThread}
                showImagePreviewFromBitmap(bmp,name)
            }
        }
    }

    private fun createThumbnailView(bitmap:Bitmap?):ImageView = ImageView(this).apply{
        if(bitmap!=null)setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_menu_gallery)
        scaleType=ImageView.ScaleType.CENTER_CROP
        adjustViewBounds=true
        background=rounded(currentTheme().cardBg,8)
        setPadding(0,0,0,0)
    }

    private fun encodingNotice(attachment: OpenRouterLocalAttachment): String =
        if (attachment.encodingGuessed) " · кодировка предположена: " + attachment.textEncoding + "; проверьте текст"
        else attachment.textEncoding?.let { " · " + it }.orEmpty()

    private fun formatAttachmentDetails(mime:String,size:Long):String{
        val type=mime.ifBlank{"application/octet-stream"}
        return if(size>0L) "$type · ${formatBytes(size)}" else type
    }

    private fun isTextLikeMime(mime:String,name:String):Boolean{
        val m=mime.lowercase()
        if(m.startsWith("text/"))return true
        return name.substringAfterLast('.',"").lowercase() in setOf(
            "txt","md","markdown","json","csv","tsv","xml","html","htm","css","js","ts","py","java","kt","kts","c","cpp","h","hpp","sh","sql","yaml","yml","log","diff","patch","srt","vtt"
        )
    }

    private fun userFileLocalPath(message:JSONObject,index:Int):String? = message.optJSONArray("file_paths")?.let { JsonProtocol.string(it, index) }?.takeIf{it.isNotBlank() && File(it).isFile}

    private fun userFileMime(message:JSONObject,index:Int,name:String):String{
        return message.optJSONArray("file_mimes")?.let { JsonProtocol.string(it, index) }.orEmpty().ifBlank{mimeForFilename(name)}
    }

    private fun prepareComposerEncoding(path: String, name: String, mime: String) {
        if (pendingOpenRouterPreparation.get() != null) { status.text = "Дождитесь подготовки запроса"; return }
        executor.execute {
            try {
                val item = OpenRouterAttachmentStore.classifyLocalFile(this, File(path), name, mime)
                runOnUiThread {
                    if (path !in attachedFilePaths || item.kind != "text" || pendingOpenRouterPreparation.get() != null) item.textPath?.let { File(it).delete() }
                    else {
                        attachedOpenRouterAttachments.removeAll { it.path == path }
                        attachedOpenRouterAttachments.add(item)
                        persistComposerStateNow(); refreshAttachChips(); chooseAttachmentEncoding(item)
                    }
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Не удалось подготовить текст: " + e.message } }
        }
    }

    private fun chooseAttachmentEncoding(attachment: OpenRouterLocalAttachment, target: HistoricalAttachmentTarget? = null) {
        if (target != null && !attachmentRepairAvailable(target)) return
        if (pendingOpenRouterPreparation.get() != null) { status.text = "Дождитесь подготовки запроса"; return }
        val encodings = TextFileFormatPolicy.selectableEncodings
        AppThemeViews.dialog(this, currentTheme()).setTitle("Кодировка исходного файла")
            .setItems(encodings.toTypedArray()) { _, selected ->
                executor.execute {
                    try {
                        val updated = OpenRouterAttachmentStore.withTextEncoding(attachment, encodings[selected])
                        if (target != null) {
                            val replacement = JsonProtocol.objectCopy(target.expected)
                            replacement.getJSONArray("attachments").put(target.manifestIndex!!, updated.toJson())
                            applyHistoricalAttachmentChange(target, replacement) { File(updated.textPath!!).delete() }
                        } else runOnUiThread {
                            val index = attachedOpenRouterAttachments.indexOfFirst { it.id == attachment.id && it.textPath == attachment.textPath }
                            if (index < 0) File(updated.textPath!!).delete()
                            else { attachedOpenRouterAttachments[index] = updated; refreshAttachChips(); persistComposerStateNow() }
                        }
                    } catch (e: Exception) { runOnUiThread { status.text = "Не удалось изменить кодировку: " + e.message } }
                }
            }.show()
    }

    private fun historyAttachmentTarget(message: JSONObject, manifestIndex: Int? = null, fileIndex: Int? = null, image: Boolean = false) =
        HistoricalAttachmentTarget(JsonProtocol.string(message, "chat_id", currentChatId()), JsonProtocol.objectCopy(message), manifestIndex, fileIndex, image)

    private fun attachmentRepairAvailable(target: HistoricalAttachmentTarget): Boolean {
        if (pendingOpenRouterPreparation.get() != null || chatDb.getActiveResponse(target.chatId) != null) {
            status.text = "Сначала завершите или остановите текущий ответ"
            return false
        }
        return true
    }

    private fun addHistoryRepairActions(row: LinearLayout, target: HistoricalAttachmentTarget, missing: Boolean) {
        if (missing) row.addView(fileActionButton("Восстановить") {
            if (attachmentRepairAvailable(target)) {
                pendingAttachmentRestore = target
                historicalFilePicker.launch(arrayOf("*/*"))
            }
        })
        row.addView(fileActionButton("Убрать из контекста") {
            if (attachmentRepairAvailable(target)) AppThemeViews.dialog(this, currentTheme())
                .setMessage("Убрать это вложение из следующих запросов? Остальные сообщения и файлы сохранятся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Убрать") { _, _ ->
                    applyHistoricalAttachmentChange(target, AttachmentMessageState.remove(target.expected, target.manifestIndex, target.fileIndex, target.image))
                }.show()
        })
    }

    private fun applyHistoricalAttachmentChange(target: HistoricalAttachmentTarget, replacement: JSONObject, cleanup: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { applyHistoricalAttachmentChange(target, replacement, cleanup) }
            return
        }
        if (pendingOpenRouterPreparation.get() != null ||
            !pendingAttachmentChange.compareAndSet(null, target.chatId)) { cleanup?.invoke(); runOnUiThread { status.text = "Сначала завершите подготовку запроса" }; return }
        executor.execute {
            try {
                val saved = chatDb.updateMessageAttachments(target.chatId, target.expected, replacement, true)
                if (!saved) cleanup?.invoke()
                runOnUiThread {
                    pendingAttachmentChange.compareAndSet(target.chatId, null)
                    if (saved && currentChatId() == target.chatId) {
                        persistComposerStateNow(); restoreCurrentChat()
                        status.text = "Вложение обновлено. Следующий запрос использует обновлённую историю"
                    } else if (!saved) status.text = "История изменилась или ответ ещё выполняется. Откройте чат и повторите действие"
                }
            } catch (e: Exception) {
                cleanup?.invoke()
                runOnUiThread { pendingAttachmentChange.compareAndSet(target.chatId, null); status.text = "Не удалось обновить вложение: " + e.message }
            }
        }
    }

    private fun restoreHistoricalAttachment(target: HistoricalAttachmentTarget, uri: Uri) {
        if (!attachmentRepairAvailable(target)) return
        executor.execute {
            var attachment: OpenRouterLocalAttachment? = null
            try {
                val imported = OpenRouterAttachmentStore.copyAndClassify(this, uri)
                attachment = imported
                val replacement = AttachmentMessageState.remove(target.expected, target.manifestIndex, target.fileIndex, target.image)
                val manifest = replacement.optJSONArray("attachments") ?: JSONArray()
                manifest.put(imported.toJson()); replacement.put("attachments", manifest)
                applyHistoricalAttachmentChange(target, replacement) { deleteOpenRouterAttachmentFiles(listOf(imported)) }
            } catch (e: Exception) {
                attachment?.let { deleteOpenRouterAttachmentFiles(listOf(it)) }
                runOnUiThread { status.text = "Не удалось восстановить файл: " + e.message }
            }
        }
    }

    private fun renderUserAttachments(message:JSONObject,card:LinearLayout){
        val localManifest = message.optJSONArray("attachments")
        if (localManifest != null) {
            for (index in 0 until localManifest.length()) {
                val entry = localManifest.optJSONObject(index) ?: continue
                val attachment = OpenRouterLocalAttachment.fromJson(entry)
                val box = createAttachmentCard()
                if (attachment.kind == "image") {
                    if (File(attachment.path).isFile) {
                        addCompactImagePreview(box, attachment.path, attachment.name)
                    } else {
                        box.addView(TextView(this).apply {
                            text = "🖼 Нет локальной копии изображения"
                            setTextColor(Color.LTGRAY)
                        })
                    }
                } else if (attachment.kind == "pdf" && File(attachment.path).isFile) {
                    addCompactPdfPreview(box, attachment.path, attachment.name)
                } else if (attachment.kind == "text" && File(attachment.textPath ?: attachment.path).isFile) {
                    addCompactTextPreview(box, attachment.textPath ?: attachment.path, attachment.name)
                }
                box.addView(TextView(this).apply {
                    text = "📎 " + safeDisplayName(attachment.name)
                    textSize = 13.5f
                    setTextColor(currentTheme().textPrimary)
                    setTypeface(null, Typeface.BOLD)
                })
                box.addView(TextView(this).apply {
                    text = attachment.kind + " · " + formatAttachmentDetails(attachment.mime, attachment.size) + encodingNotice(attachment)
                    setTextColor(currentTheme().textSecondary)
                    textSize = 11.5f
                })
                if (File(attachment.path).isFile) {
                    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    if (attachment.kind == "image") buttons.addView(
                        fileActionButton("Просмотр") { showImagePreviewFromFile(attachment.path, attachment.name) }
                    )
                    if (attachment.kind in setOf("text", "document", "table")) buttons.addView(
                        fileActionButton("Просмотр") { showTextPreview(attachment.textPath ?: attachment.path, attachment.name) }
                    )
                    buttons.addView(fileActionButton("Сохранить") {
                        requestSaveLocalFile(attachment.path, attachment.name, attachment.mime)
                    })
                    box.addView(buttons)
                }
                val repair = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val target = historyAttachmentTarget(message, manifestIndex = index)
                if (attachment.kind == "text" && File(attachment.path).isFile) repair.addView(fileActionButton("Кодировка") { chooseAttachmentEncoding(attachment, target) })
                addHistoryRepairActions(repair, target, !File(attachment.path).isFile)
                box.addView(repair)
                card.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            if (localManifest.length() > 0) {
                card.addView(TextView(this).apply {
                    text = "Вложения OpenRouter"
                    textSize = 11.5f
                    setTextColor(currentTheme().textSecondary)
                }, 2)
            }
        }
        val fileIds=message.optJSONArray("file_ids") ?: JSONArray()
        val fileNames=message.optJSONArray("file_names") ?: JSONArray()
        val fileSizes=message.optJSONArray("file_sizes") ?: JSONArray()
        var shown=0
        val imagePath=JsonProtocol.string(message, "image_path").takeIf{it.isNotBlank() && File(it).isFile}
        val imageFileId=JsonProtocol.string(message, "image_file_id").trim()
        val manifestPaths = localManifest?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> JsonProtocol.nonBlank(o, "path") } }.toSet() } ?: emptySet()
        if((imagePath!=null || imageFileId.isNotBlank() || JsonProtocol.string(message, "image_name").isNotBlank()) && JsonProtocol.string(message, "image_path") !in manifestPaths){
            val fallbackImageName = JsonProtocol.string(message, "image_path").takeIf{it.isNotBlank()}?.let{File(it).name}
            val name=JsonProtocol.string(message, "image_name").ifBlank{fallbackImageName ?: "Изображение"}
            val mime=JsonProtocol.string(message, "image_mime").ifBlank{if(imagePath!=null) mimeForFilename(name) else "image/*"}
            val box=createAttachmentCard()
            if(imagePath!=null){
                val preview=createThumbnailView(null)
                loadThumbnailAsync(preview,imagePath,900)
                preview.setOnClickListener{showImagePreviewFromFile(imagePath,name)}
                box.addView(preview,LinearLayout.LayoutParams(-1,dp(220)))
            }else{
                box.addView(TextView(this).apply{
                    text = "🖼\nЛокальная копия изображения отсутствует"
                    gravity=Gravity.CENTER
                    textSize=13f; setTextColor(currentTheme().textSecondary)
                    background=rounded(currentTheme().cardBg,8)
                    setPadding(0,dp(22),0,dp(22))
                },LinearLayout.LayoutParams(-1,dp(130)))
            }
            box.addView(TextView(this).apply{ text="🖼 $name"; textSize=13.5f; setTextColor(currentTheme().textPrimary); setTypeface(null,Typeface.BOLD); setPadding(0,dp(6),0,dp(2)) })
            val size=message.optLong("image_size",imagePath?.let{File(it).length()} ?: 0L)
            box.addView(TextView(this).apply{ text=formatAttachmentDetails(mime,size); textSize=11.5f; setTextColor(currentTheme().textSecondary) })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(imagePath!=null){
                row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(imagePath,name)})
                row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(imagePath,name,mime)})
                row.addView(fileActionButton("Поделиться"){shareLocalFile(imagePath,name,mime)})
            }
            addHistoryRepairActions(row, historyAttachmentTarget(message, image = true), imagePath == null)
            box.addView(row)
            card.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            shown++
        }
        val filePaths = message.optJSONArray("file_paths") ?: JSONArray()
        for(i in 0 until maxOf(fileIds.length(),fileNames.length(),filePaths.length())){
            if (JsonProtocol.string(filePaths, i) in manifestPaths) continue
            val name=safeDisplayName(JsonProtocol.string(fileNames, i).ifBlank{"attachment_${i+1}"})
            val size=fileSizes.optLong(i,0L)
            val localPath=userFileLocalPath(message,i)
            val mime=userFileMime(message,i,name)
            val box=createAttachmentCard()
            when{
                localPath!=null && mime.startsWith("image/") -> addCompactImagePreview(box,localPath,name)
                localPath!=null && isTextLikeMime(mime,name) -> addCompactTextPreview(box,localPath,name)
                localPath!=null && mime.equals("application/pdf",true) -> addCompactPdfPreview(box,localPath,name)
                else -> box.addView(TextView(this).apply{
                    text=when{mime.equals("application/pdf",true)->"📕";isTextLikeMime(mime,name)->"📄";mime.startsWith("image/")->"🖼";else->"📎"}
                    gravity=Gravity.CENTER;textSize=26f;setTextColor(currentTheme().textSecondary)
                    background=rounded(currentTheme().cardBg,8);setPadding(0,dp(12),0,dp(12))
                },LinearLayout.LayoutParams(-1,dp(74)).apply{bottomMargin=dp(6)})
            }
            box.addView(TextView(this).apply{ text="📄 $name";textSize=13.5f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD) })
            box.addView(TextView(this).apply{ text=formatAttachmentDetails(mime,if(size>0)size else localPath?.let{File(it).length()} ?: 0L);textSize=11.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(3),0,dp(4)) })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(localPath!=null){
                if(isTextLikeMime(mime,name)) row.addView(fileActionButton("Просмотр"){showTextPreview(localPath,name)})
                else if(mime.equals("application/pdf",true)) row.addView(fileActionButton("Просмотр"){showPdfPreview(localPath,name)})
                else if(mime.startsWith("image/")) row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(localPath,name)})
                else row.addView(fileActionButton("Открыть"){openLocalFile(localPath,name,mime)})
                row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(localPath,name,mime)})
                row.addView(fileActionButton("Поделиться"){shareLocalFile(localPath,name,mime)})
            }
            addHistoryRepairActions(row, historyAttachmentTarget(message, fileIndex = i), localPath == null)
            box.addView(row)
            card.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            shown++
        }
        if(shown>0){
            card.addView(TextView(this).apply{ text="Вложения"; textSize=11.5f; setTextColor(currentTheme().textSecondary); setPadding(0,dp(7),0,dp(0)) },2)
        }
    }


    private fun showImagePreviewFromBitmap(bitmap:Bitmap,name:String=""){
        val image=ImageView(this).apply{
            setImageBitmap(bitmap)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(currentTheme().cardBg)
        }
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(currentTheme().cardBg,12)}
        box.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        if(name.isNotBlank()) box.addView(TextView(this).apply{ text=name;textSize=12f;setTextColor(Color.LTGRAY);setPadding(dp(4),dp(4),dp(4),dp(2));gravity=Gravity.CENTER })
        val dialog=AppThemeViews.dialog(this, currentTheme()).setView(box).setPositiveButton("Закрыть",null).create()
        dialog.setOnShowListener{ dialog.window?.setLayout(-1,-1) }
        dialog.show()
    }

    private fun showTextPreview(path:String,name:String,uiOwner:()->Boolean={true}){
        executor.execute{
            try{
                val maxPreview=2L*1024L*1024L
                val file=File(path)
                val limit=minOf(file.length(),maxPreview).toInt()
                val bytes=ByteArray(limit)
                var offset=0
                file.inputStream().use{input->
                    while(offset<bytes.size){
                        val n=input.read(bytes,offset,bytes.size-offset)
                        if(n<0)break
                        offset+=n
                    }
                }
                var text=String(bytes,0,offset,StandardCharsets.UTF_8)
                if(name.substringAfterLast('.',"").equals("json",true)){
                    text=try{JSONObject(text).toString(2)}catch(_:Exception){try{JSONArray(text).toString(2)}catch(_:Exception){text}}
                }
                val truncated=file.length()>maxPreview
                runOnUiThread{
                    if(isFinishing || isDestroyed || !uiOwner())return@runOnUiThread
                    val tv=TextView(this).apply{
                        this.text=if(truncated)"$text\n\n… Показана первая часть файла (${formatBytes(maxPreview)}). Полный файл можно открыть/сохранить."
                        else text
                        textSize=13f;setTextColor(currentTheme().textPrimary);typeface=Typeface.MONOSPACE;setPadding(dp(12),dp(10),dp(12),dp(10));setTextIsSelectable(true)
                    }
                    val box=ScrollView(this).apply{addView(tv)}
                    AppThemeViews.dialog(this, currentTheme()).setTitle(name).setView(box).setPositiveButton("Закрыть",null).setNeutralButton("Открыть файл"){_,_->openLocalFile(path,name,mimeForFilename(name))}.show()
                }
            }catch(e:Exception){runOnUiThread{
                if(!isFinishing && !isDestroyed && uiOwner())status.text="Не удалось прочитать $name: ${e.message}"
            }}
        }
    }

    private fun showPdfPreview(path:String,name:String,uiOwner:()->Boolean={true}){
        executor.execute{
            try{
                ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY).use{pfd->
                PdfRenderer(pfd).use{renderer->
                    if(renderer.pageCount<=0)throw IOException("PDF не содержит страниц")
                    renderer.openPage(0).use{page->
                        val scale=minOf(2f,1600f/maxOf(page.width,1).toFloat(),1600f/maxOf(page.height,1).toFloat())
                        val bmp=Bitmap.createBitmap(maxOf((page.width*scale).toInt(),1),maxOf((page.height*scale).toInt(),1),Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        runOnUiThread{
                            if(isFinishing || isDestroyed || !uiOwner())return@runOnUiThread
                            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6))}
                            box.addView(ImageView(this).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true},LinearLayout.LayoutParams(-1,dp(540)))
                            AppThemeViews.dialog(this, currentTheme()).setTitle(name).setView(box).setPositiveButton("Закрыть",null).setNeutralButton("Открыть полностью"){_,_->openLocalFile(path,name,"application/pdf")}.show()
                        }
                    }
                }
                }
            }catch(e:Exception){runOnUiThread{
                if(!isFinishing && !isDestroyed && uiOwner())status.text="Не удалось показать PDF: ${e.message}"
            }}
        }
    }

    private fun addUploadProgressCard(uri:Uri,kind:String):UploadCardViews{
        val key=attachmentKey(uri,kind)
        val card=createAttachmentCard()
        val name=queryName(uri) ?: if(kind=="image")"image" else "attachment"
        val mime=contentResolver.getType(uri).orEmpty().ifBlank{mimeForFilename(name)}
        val preview=createThumbnailView(null)
        if(kind=="image" || mime.startsWith("image/")){
            preview.tag="attachment_local_preview"
            card.addView(preview,LinearLayout.LayoutParams(dp(112),dp(52)))
        }
        val nameView=TextView(this).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text=if(kind=="zip")"🗜 $name" else if(kind=="image")"🖼 $name" else "📄 $name";textSize=13.5f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD);setPadding(0,dp(6),0,dp(2))}
        val details=TextView(this).apply{
            text=if(kind=="zip")"Архив будет распакован только при отправке" else {
                val base=formatAttachmentDetails(mime,querySize(uri))
                val pct=uploadProgress[key]?.second ?: 0
                if(pct>0)"$base · подготовка $pct%" else "$base · подготовка…"
            }
            textSize=11.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(2),0,dp(4))
        }
        val progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=uploadProgress[key]?.second ?: 0}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val action=TextView(this).apply{text="✕ Удалить";textSize=12.5f;setTextColor(currentTheme().errorText);setPadding(0,dp(5),dp(10),dp(2));setOnClickListener{cancelPendingUpload(uri,kind)}}
        actions.addView(action)
        card.addView(nameView);card.addView(details);card.addView(progressBar);card.addView(actions)
        attachRow.addView(card,LinearLayout.LayoutParams(dp(240),-2).apply{rightMargin=dp(6)})
        val views=UploadCardViews(card,preview,nameView,details,progressBar,actions,action)
        uploadCards[key]=views
        pendingPreviewBitmaps[key]?.let { bm ->
            preview.setImageBitmap(bm)
            preview.setOnClickListener { showImagePreviewFromBitmap(bm,name) }
        }
        return views
    }

    private fun addCompactTextPreview(parent:LinearLayout,path:String,name:String){
        val preview=TextView(this).apply{
            tag="attachment_local_preview"
            text="Читаю файл…"
            textSize=11.5f
            setTextColor(currentTheme().textPrimary)
            typeface=Typeface.MONOSPACE
            setPadding(dp(10),dp(8),dp(10),dp(8))
            setBackgroundColor(currentTheme().cardBg)
            maxLines=9
            ellipsize=TextUtils.TruncateAt.END
            setOnClickListener{showTextPreview(path,name)}
        }
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(118)).apply{bottomMargin=dp(6)})
        executor.execute{
            try{
                val maxPreview=48*1024
                val file=File(path)
                val buffer=ByteArray(maxPreview)
                var offset=0
                file.inputStream().use{input->
                    while(offset<buffer.size){
                        val n=input.read(buffer,offset,buffer.size-offset)
                        if(n<0)break
                        offset+=n
                    }
                }
                var text=String(buffer,0,offset,StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .replace("\r\n","\n")
                if(name.substringAfterLast('.',"").equals("json",true)){
                    text=try{JSONObject(text).toString(2)}catch(_:Exception){try{JSONArray(text).toString(2)}catch(_:Exception){text}}
                }
                val lines=text.lineSequence().take(9).joinToString("\n")
                runOnUiThread{
                    preview.text=if(lines.isBlank())"(пустой файл)" else lines
                }
            }catch(e:Exception){
                runOnUiThread{preview.text="Не удалось прочитать файл. Нажмите «Просмотр»."}
            }
        }
    }

    private fun addComposerImagePreview(parent: LinearLayout, path: String, name: String) {
        val preview = createThumbnailView(null)
        preview.contentDescription = "Просмотреть вложение $name"
        preview.setOnClickListener { showImagePreviewFromFile(path, name) }
        parent.addView(preview, LinearLayout.LayoutParams(dp(112), dp(52)))
        loadThumbnailAsync(preview, path, 256)
    }

    private fun addCompactImagePreview(parent:LinearLayout,path:String,name:String){
        val preview=ImageView(this).apply{
            tag="attachment_local_preview"
            scaleType=ImageView.ScaleType.CENTER_CROP
            adjustViewBounds=true
            background=rounded(currentTheme().cardBg,8)
            setOnClickListener{showImagePreviewFromFile(path,name)}
        }
        loadThumbnailAsync(preview,path,720)
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
    }

    private fun addCompactPdfPreview(parent:LinearLayout,path:String,name:String){
        val preview=ImageView(this).apply{
            tag="attachment_local_preview"
            scaleType=ImageView.ScaleType.FIT_CENTER
            background=rounded(currentTheme().cardBg,8)
            setOnClickListener{showPdfPreview(path,name)}
        }
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
        executor.execute{
            try{
                ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY).use{pfd->
                PdfRenderer(pfd).use{renderer->
                    if(renderer.pageCount>0){
                        renderer.openPage(0).use{page->
                            val scale=minOf(1.5f,720f/maxOf(page.width,1).toFloat())
                            val w=maxOf((page.width*scale).toInt(),1)
                            val h=maxOf((page.height*scale).toInt(),1)
                            val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            runOnUiThread{preview.setImageBitmap(bmp)}
                        }
                    }
                }
                }
            }catch(_:Exception){ }
        }
    }

    private fun openPreviewForLocalFile(path:String,name:String,mime:String,uiOwner:()->Boolean={true}){
        when{
            mime.startsWith("image/") -> showImagePreviewFromFile(path,name,uiOwner)
            isTextLikeMime(mime,name) -> showTextPreview(path,name,uiOwner)
            mime.equals("application/pdf",true) -> showPdfPreview(path,name,uiOwner)
            else -> if(uiOwner())openLocalFile(path,name,mime)
        }
    }


    private val pendingPreviewBitmaps = ConcurrentHashMap<String, Bitmap>()


    private fun decodeThumbnailFromUri(uri:Uri,maxDim:Int):Bitmap?{
        return try{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
            var sample=1
            while(bounds.outWidth/sample>maxDim || bounds.outHeight/sample>maxDim)sample*=2
            val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565}
            val decoded = contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)} ?: return null
            contentResolver.openInputStream(uri)?.use{ImageOrientation.upright(decoded, it)} ?: decoded
        }catch(_:Exception){null}
    }

    private fun querySize(uri:Uri):Long{
        return contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()&&!c.isNull(0))c.getLong(0) else 0L} ?: 0L
    }


    private fun cancelPendingUpload(uri:Uri,kind:String){
        val key=attachmentKey(uri,kind)
        cancelledUploads.add(key)
        if (kind == "zip") activeZipOperation = null
        uploadProgress.remove(key)
        pendingUploadUris.removeAll{it.first==uri.toString() && it.second==kind}
        pendingPreviewBitmaps.remove(key)
        persistComposerStateNow()
        refreshAttachChips()
        status.text="Вложение удалено"
    }

    private fun refreshAttachChips(){
        if (::requestContextBadge.isInitialized) requestContextSnapshot(false)
        attachRow.removeAllViews()
        uploadCards.clear()
        for((rawUri,kind) in pendingUploadUris.toList()){
            val uri=try{Uri.parse(rawUri)}catch(_:Exception){continue}
            addUploadProgressCard(uri,kind)
        }
        if(pendingImagePath!=null){
            val path=pendingImagePath!!
            val name=pendingImageName?.ifBlank{null} ?: "Изображение"
            val mime=pendingImageMime?.ifBlank{null} ?: "image/*"
            val card=createAttachmentCard()
            val preview=createThumbnailView(null)
            loadThumbnailAsync(preview,path,640)
            preview.setOnClickListener{showImagePreviewFromFile(path,name)}
            card.addView(preview,LinearLayout.LayoutParams(dp(112),dp(52)))
            card.addView(TextView(this).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text="🖼 $name";textSize=13.5f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD);setPadding(0,dp(6),0,dp(2))})
            card.addView(TextView(this).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text=formatAttachmentDetails(mime,pendingImageSize.coerceAtLeast(File(path).length()));textSize=11.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(2),0,dp(4))})
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,name)})
            row.addView(fileActionButton("Удалить"){pendingImagePath=null;pendingImageFileId=null;pendingImageName=null;pendingImageMime=null;pendingImageSize=0L;refreshAttachChips();persistComposerStateNow()})
            card.addView(row)
            attachRow.addView(card,LinearLayout.LayoutParams(dp(220),-2).apply{rightMargin=dp(6)})
        }
        attachedOpenRouterAttachments.forEach { attachment ->
            val card = createAttachmentCard()
            if (attachment.kind == "image") {
                if (File(attachment.path).isFile) {
                    addComposerImagePreview(card, attachment.path, attachment.name)
                } else {
                    card.addView(TextView(this).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        text = "🖼 Локальная копия недоступна"
                        setTextColor(Color.LTGRAY)
                    })
                }
            }
            card.addView(TextView(this).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                text = "📎 " + safeDisplayName(attachment.name)
                textSize = 13.5f
                setTextColor(currentTheme().textPrimary)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                text = attachment.kind + " · " + formatAttachmentDetails(attachment.mime, attachment.size) + encodingNotice(attachment)
                textSize = 11.5f
                setTextColor(currentTheme().textSecondary)
                setPadding(0,dp(3),0,dp(4))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (attachment.kind) {
                "image" -> row.addView(fileActionButton("Просмотр") { showImagePreviewFromFile(attachment.path, attachment.name) })
                "text", "document", "table" -> row.addView(fileActionButton("Просмотр") { showTextPreview(attachment.textPath ?: attachment.path, attachment.name) })
                "pdf" -> row.addView(fileActionButton("Просмотр") { showPdfPreview(attachment.path, attachment.name) })
            }
            if (attachment.kind == "text") row.addView(fileActionButton("Кодировка") { chooseAttachmentEncoding(attachment) })
            row.addView(fileActionButton("Удалить") {
                attachedOpenRouterAttachments.removeAll { it.id == attachment.id }
                for (i in attachedFilePaths.indices.reversed()) if (attachedFilePaths[i] == attachment.path) {
                    attachedFilePaths.removeAt(i)
                    if (i < attachedFileIds.size) attachedFileIds.removeAt(i)
                    if (i < attachedFileNames.size) attachedFileNames.removeAt(i)
                    if (i < attachedFileMimes.size) attachedFileMimes.removeAt(i)
                    if (i < attachedFileSizes.size) attachedFileSizes.removeAt(i)
                }
                refreshAttachChips()
                persistComposerStateNow()
            })
            card.addView(row)
            attachRow.addView(card, LinearLayout.LayoutParams(dp(220),-2).apply{rightMargin=dp(6)})
        }
        attachedFileNames.forEachIndexed{i,name->
            if (attachedOpenRouterAttachments.any { it.path == attachedFilePaths.getOrNull(i) }) return@forEachIndexed
            val path=attachedFilePaths.getOrNull(i).orEmpty().takeIf{it.isNotBlank() && File(it).isFile}
            val mime=attachedFileMimes.getOrNull(i).orEmpty().ifBlank{mimeForFilename(name)}
            val size=attachedFileSizes.getOrNull(i) ?: path?.let{File(it).length()} ?: 0L
            val card=createAttachmentCard()
            val title=TextView(this).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text="📄 ${safeDisplayName(name)}";textSize=13.5f;setTextColor(currentTheme().textPrimary);setTypeface(null,Typeface.BOLD)}
            card.addView(title)
            card.addView(TextView(this).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text=formatAttachmentDetails(mime,size);textSize=11.5f;setTextColor(currentTheme().textSecondary);setPadding(0,dp(3),0,dp(4))})
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(path!=null){
                if(isTextLikeMime(mime,name)) {
                    row.addView(fileActionButton("Просмотр"){showTextPreview(path,name)})
                    row.addView(fileActionButton("Кодировка"){prepareComposerEncoding(path,name,mime)})
                }
                else if(mime.equals("application/pdf",true))row.addView(fileActionButton("Просмотр"){showPdfPreview(path,name)})
                else row.addView(fileActionButton("Открыть"){openLocalFile(path,name,mime)})
            }
            row.addView(fileActionButton("Удалить"){
                attachedFileIds.removeAt(i);attachedFileNames.removeAt(i);attachedFileSizes.removeAt(i);attachedFilePaths.removeAt(i);attachedFileMimes.removeAt(i)
                refreshAttachChips();persistComposerStateNow()
            })
            card.addView(row)
            attachRow.addView(card,LinearLayout.LayoutParams(dp(220),-2).apply{rightMargin=dp(6)})
        }
        compactComposerCards()
        attachScroll.visibility=if(attachRow.childCount>0)View.VISIBLE else View.GONE
        ViewCompat.requestApplyInsets(input.rootView)
        send.isEnabled = stop.visibility != View.VISIBLE && pendingUploads.get() == 0 && !preparingZipAttachments
    }

    /** Only pending attachments: keep the sent message previews and file bytes intact. */
    private fun compactComposerCards() {
        for (i in 0 until attachRow.childCount) {
            val card = attachRow.getChildAt(i) as? LinearLayout ?: continue
            if (card.orientation == LinearLayout.HORIZONTAL) continue
            val preview = (0 until card.childCount).map { card.getChildAt(it) }.firstOrNull { it is ImageView }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val children = (0 until card.childCount).map { card.getChildAt(it) }
            card.removeAllViews()
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            preview?.let { card.addView(it, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(8) }) }
            children.filter { it !== preview }.forEach { child ->
                if (child is TextView) { child.maxLines = 1; child.ellipsize = TextUtils.TruncateAt.END }
                if (child is LinearLayout) for (j in 0 until child.childCount) {
                    val action = child.getChildAt(j) as? TextView ?: continue
                    val label = action.text.toString()
                    action.contentDescription = label
                    action.text = if (label.contains("Удалить")) "×" else if (label.contains("Просмотр") || label.contains("Открыть")) "↗" else label
                    action.setPadding(dp(10), dp(4), dp(10), dp(4))
                    action.minimumWidth = dp(40)
                }
                details.addView(child, LinearLayout.LayoutParams(-1, -2))
            }
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            card.layoutParams = (card.layoutParams as LinearLayout.LayoutParams).apply { width = dp(250) }
        }
    }

    private fun pixelDistanceFromBottom(): Int {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized) return 0
        val count = messageAdapter.itemCount
        if (count <= 0) return 0
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return 0
        val lastIndex = count - 1
        val lastVisible = lm.findLastVisibleItemPosition()
        val lastView = lm.findViewByPosition(lastIndex)
        val bottomMargin = (lastView?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val range = scroll.computeVerticalScrollRange()
        val offset = scroll.computeVerticalScrollOffset()
        val extent = scroll.computeVerticalScrollExtent()
        val scrollbarRemaining = (range - offset - extent).coerceAtLeast(0)
        return ChatScrollPolicy.actualDistanceFromBottomPx(
            itemCount = count,
            lastVisiblePosition = lastVisible,
            viewportHeightPx = scroll.height,
            paddingBottomPx = scroll.paddingBottom,
            lastItemBottomPx = lastView?.bottom,
            lastItemBottomMarginPx = bottomMargin,
            scrollbarRemainingPx = scrollbarRemaining,
            maxFollowDistancePx = dp(64)
        )
    }

    private fun isAtBottomPixels(): Boolean = pixelDistanceFromBottom() <= dp(64)

    private fun flushScrollLayoutIfReady() {
        if (!::scroll.isInitialized || scroll.isComputingLayout || scroll.width <= 0 || scroll.height <= 0 || !scroll.isLayoutRequested) return
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(scroll.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(scroll.height, View.MeasureSpec.EXACTLY)
        )
        scroll.layout(scroll.left, scroll.top, scroll.right, scroll.bottom)
    }

    private fun contentTextViewsInBubble(root: View): List<Pair<TextView, Int>> {
        val out = mutableListOf<Pair<TextView, Int>>()
        fun walk(view: View, topAcc: Int, isRootCardChildZero: Boolean) {
            if (view.visibility != View.VISIBLE) return
            if (isRootCardChildZero && view is TextView) return
            if (view is TextView && view !is Button && view.text.isNotEmpty()) {
                out.add(view to topAcc)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    walk(child, topAcc + child.top, view === root && i == 0)
                }
            }
        }
        walk(root, 0, false)
        return out
    }

    private fun findCharOffsetAtViewportTop(card: View, targetYInCard: Int): Int {
        var priorChars = 0
        for ((tv, topInCard) in contentTextViewsInBubble(card)) {
            val layout = tv.layout
            val len = tv.text.length
            if (layout != null && targetYInCard <= topInCard + tv.height) {
                val localY = (targetYInCard - topInCard - tv.totalPaddingTop).coerceAtLeast(0)
                val line = layout.getLineForVertical(localY)
                return priorChars + layout.getLineStart(line)
            }
            priorChars += len
        }
        return 0
    }

    private fun findLineTopForCharOffset(card: View, charOffset: Int): Int? {
        if (charOffset <= 0) return null
        var priorChars = 0
        val views = contentTextViewsInBubble(card)
        for ((index, pair) in views.withIndex()) {
            val (tv, topInCard) = pair
            val len = tv.text.length
            val layout = tv.layout
            if (layout != null && (charOffset <= priorChars + len || index == views.lastIndex)) {
                val localOffset = (charOffset - priorChars).coerceIn(0, len)
                val line = layout.getLineForOffset(localOffset)
                return topInCard + tv.totalPaddingTop + layout.getLineTop(line)
            }
            priorChars += len
        }
        return null
    }

    private fun captureCurrentScrollAnchor(preserveMode: ChatScrollMode? = null) {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized || messageAdapter.itemCount == 0 || messageAdapter.isListCommitPending()) return
        val chatId = currentChatId()
        if (chatId.isBlank() || pendingScrollRestoreChatId == chatId) return
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos < 0) return
        val firstView = lm.findViewByPosition(firstPos) ?: return
        if (firstView.isLayoutRequested) return
        val panel = firstView.findViewWithTag<RichResponsePanel>("rich-response")
        // A streaming parse can be pending while the prior children still represent a
        // measured, usable viewport. Preserve a user's gesture on those children, but
        // never capture a placeholder or newly installed, unmeasured rich layout.
        if (panel?.isAsyncParsePending() == true &&
            (panel.childCount == 0 || panel.height <= 0 || panel.isLayoutRequested)) return
        val topOffset = firstView.top - scroll.paddingTop
        val snapshot = messageAdapter.committedSnapshot()
        val item = snapshot.getOrNull(firstPos)
        val distanceFromBottom = pixelDistanceFromBottom()
        val mode = preserveMode ?: if (chatId in userScrollLockedChats || distanceFromBottom > dp(64)) {
            ChatScrollMode.READING_LOCKED
        } else {
            ChatScrollMode.FOLLOW_LATEST
        }
        val charOffset = if (firstView != null && topOffset < 0) {
            findCharOffsetAtViewportTop(firstView, -topOffset)
        } else 0
        val effectiveWidth = if (laidOutViewportWidthPx > 0) laidOutViewportWidthPx else scroll.width
        val effectiveFontScale = if (laidOutViewportWidthPx > 0) laidOutFontScale else resources.configuration.fontScale
        perChatScrollAnchors[chatId] = ChatScrollAnchor(
            messageId = item?.id ?: ChatScrollPolicy.messageIdentity(firstPos, item?.user == true, item?.message),
            contentSignature = item?.contentSignature.orEmpty(),
            position = firstPos,
            topOffsetPx = topOffset,
            mode = mode,
            distanceFromBottomPx = distanceFromBottom,
            itemHeightPx = firstView?.height ?: 0,
            viewportWidthPx = effectiveWidth,
            fontScale = effectiveFontScale,
            charOffset = charOffset
        )
    }

    private fun scrollToChatBottomNow() {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized || messageAdapter.isListCommitPending() || scroll.isComputingLayout) return
        val count = messageAdapter.itemCount
        if (count <= 0) return
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return
        val lastIndex = count - 1
        applyingProgrammaticScroll = true
        try {
            if (lm.findViewByPosition(lastIndex) == null) {
                lm.scrollToPosition(lastIndex)
                flushScrollLayoutIfReady()
            }
            val lastView = lm.findViewByPosition(lastIndex)
            val viewportHeight = scroll.height - scroll.paddingTop - scroll.paddingBottom
            if (lastView != null && viewportHeight > 0 && lastView.height > 0) {
                val bottomMargin = (lastView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                val targetOffset = viewportHeight - lastView.height - bottomMargin
                lm.scrollToPositionWithOffset(lastIndex, targetOffset)
                flushScrollLayoutIfReady()
            } else {
                lm.scrollToPosition(lastIndex)
                flushScrollLayoutIfReady()
            }
        } finally {
            applyingProgrammaticScroll = false
        }
    }

    private fun isBubbleAsyncLayoutPending(card: View): Boolean =
        card.findViewWithTag<RichResponsePanel>("rich-response")?.isBodyLayoutPending() == true

    private fun beginScrollRestore(chatId: String, anchor: ChatScrollAnchor?): Long {
        pendingScrollRestoreChatId = chatId
        pendingScrollRestoreAnchor = anchor
        return ++scrollRestoreGeneration
    }

    private fun retainPendingScrollAnchor() {
        val chatId = pendingScrollRestoreChatId ?: return
        val anchor = pendingScrollRestoreAnchor
        if (anchor != null) {
            perChatScrollAnchors[chatId] = anchor
        } else {
            // Null deliberately means follow the latest reply, even if an older
            // reading position is still waiting in the per-chat snapshot map.
            perChatScrollAnchors[chatId]?.let { previous ->
                perChatScrollAnchors[chatId] = previous.copy(
                    mode = ChatScrollMode.FOLLOW_LATEST, distanceFromBottomPx = 0,
                    topOffsetPx = 0, charOffset = 0
                )
            }
            userScrollLockedChats.remove(chatId)
        }
    }

    private fun cancelScrollRestore() {
        scrollRestoreGeneration++
        pendingScrollRestoreChatId = null
        pendingScrollRestoreAnchor = null
    }

    private fun isScrollRestoreCurrent(chatId: String, generation: Long): Boolean =
        !scrollCallbacksStopped && !isFinishing && !isDestroyed && currentChatId() == chatId &&
            pendingScrollRestoreChatId == chatId && scrollRestoreGeneration == generation

    private fun completeScrollRestore(chatId: String, generation: Long, mode: ChatScrollMode) {
        if (!isScrollRestoreCurrent(chatId, generation) || messageAdapter.isListCommitPending()) return
        laidOutFontScale = resources.configuration.fontScale
        laidOutViewportWidthPx = scroll.width
        pendingScrollRestoreChatId = null
        pendingScrollRestoreAnchor = null
        if (mode == ChatScrollMode.READING_LOCKED) userScrollLockedChats.add(chatId)
        else userScrollLockedChats.remove(chatId)
        captureCurrentScrollAnchor(preserveMode = mode)
        updateJumpToBottomButtonVisibility()
    }

    private fun applyReadingAnchorNow(pos: Int, savedAnchor: ChatScrollAnchor): Boolean {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized || messageAdapter.itemCount == 0 ||
            messageAdapter.isListCommitPending() || scroll.isComputingLayout || scroll.width <= 0 || scroll.height <= 0) return false
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return false
        val safePos = pos.coerceIn(0, messageAdapter.itemCount - 1)
        applyingProgrammaticScroll = true
        try {
            lm.scrollToPositionWithOffset(safePos, savedAnchor.topOffsetPx)
            flushScrollLayoutIfReady()
            val view = lm.findViewByPosition(safePos) ?: return false
            if (view.height <= 0 || view.isLayoutRequested || isBubbleAsyncLayoutPending(view)) return false
            val charLineTop = if (savedAnchor.charOffset > 0) findLineTopForCharOffset(view, savedAnchor.charOffset) else null
            val resolvedOffset = ChatScrollPolicy.resolveTopOffsetPx(
                anchor = savedAnchor,
                currentItemHeightPx = view.height,
                currentViewportWidthPx = scroll.width,
                currentFontScale = resources.configuration.fontScale,
                charLineTopPx = charLineTop
            )
            if (view.top - scroll.paddingTop != resolvedOffset) {
                lm.scrollToPositionWithOffset(safePos, resolvedOffset)
                flushScrollLayoutIfReady()
            }
            val restored = lm.findViewByPosition(safePos) ?: return false
            return restored.height > 0 && !restored.isLayoutRequested && !isBubbleAsyncLayoutPending(restored)
        } finally {
            applyingProgrammaticScroll = false
        }
    }

    private fun tryPendingScrollRestore(targetChatId: String, generation: Long) {
        if (!isScrollRestoreCurrent(targetChatId, generation) || scroll.isComputingLayout || messageAdapter.isListCommitPending()) return
        if (messageAdapter.itemCount == 0) {
            pendingScrollRestoreChatId = null
            pendingScrollRestoreAnchor = null
            updateJumpToBottomButtonVisibility()
            return
        }
        val anchor = pendingScrollRestoreAnchor
        val reading = anchor != null && (anchor.isReadingLocked || anchor.distanceFromBottomPx > dp(64))
        if (reading && anchor != null) {
            userScrollLockedChats.add(targetChatId)
            val position = ChatScrollPolicy.resolveAnchorPosition(anchor, messageAdapter.committedSnapshot())
            if (applyReadingAnchorNow(position, anchor)) {
                completeScrollRestore(targetChatId, generation, ChatScrollMode.READING_LOCKED)
            }
        } else {
            userScrollLockedChats.remove(targetChatId)
            scrollToChatBottomNow()
            val lm = scroll.layoutManager as? LinearLayoutManager ?: return
            val last = lm.findViewByPosition(messageAdapter.itemCount - 1)
            if (scroll.width > 0 && scroll.height > 0 && last != null && last.height > 0 &&
                !last.isLayoutRequested && !isBubbleAsyncLayoutPending(last)) {
                completeScrollRestore(targetChatId, generation, ChatScrollMode.FOLLOW_LATEST)
            }
        }
    }

    private fun applyRestoredScrollState(targetChatId: String, savedAnchor: ChatScrollAnchor?, restoreGeneration: Long) {
        if (!::scroll.isInitialized || !::messageAdapter.isInitialized ||
            !isScrollRestoreCurrent(targetChatId, restoreGeneration)) return
        // The pending anchor is immutable until a measured target view commits or the user cancels.
        pendingScrollRestoreAnchor = savedAnchor
        tryPendingScrollRestore(targetChatId, restoreGeneration)
        scroll.post {
            if (!isScrollRestoreCurrent(targetChatId, restoreGeneration)) return@post
            tryPendingScrollRestore(targetChatId, restoreGeneration)
        }
    }

    private fun onBubbleAsyncLayoutCommitted(sourceChatId: String, itemId: String, card: View) {
        if (scrollCallbacksStopped || isFinishing || isDestroyed || !::scroll.isInitialized ||
            !::messageAdapter.isInitialized || currentChatId() != sourceChatId || messageAdapter.isListCommitPending()) return
        val generation = scrollRestoreGeneration
        val items = messageAdapter.committedSnapshot()
        if (items.isEmpty()) return
        if (isScrollRestoreCurrent(sourceChatId, generation)) {
            val anchor = pendingScrollRestoreAnchor
            val target = if (anchor != null && (anchor.isReadingLocked || anchor.distanceFromBottomPx > dp(64))) {
                ChatScrollPolicy.resolveAnchorPosition(anchor, items)
            } else items.lastIndex
            if (items.getOrNull(target)?.id != itemId) return
            scroll.post {
                if (!isScrollRestoreCurrent(sourceChatId, generation) || messageAdapter.isListCommitPending()) return@post
                val lm = scroll.layoutManager as? LinearLayoutManager ?: return@post
                if (lm.findViewByPosition(target) !== card || messageAdapter.committedSnapshot().getOrNull(target)?.id != itemId) return@post
                tryPendingScrollRestore(sourceChatId, generation)
            }
            return
        }
        val lastIndex = items.lastIndex
        if (items[lastIndex].id != itemId) return
        scroll.post {
            if (scrollCallbacksStopped || isFinishing || isDestroyed || currentChatId() != sourceChatId ||
                scrollRestoreGeneration != generation || pendingScrollRestoreChatId != null ||
                messageAdapter.isListCommitPending() || sourceChatId in userScrollLockedChats ||
                scroll.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return@post
            val lm = scroll.layoutManager as? LinearLayoutManager ?: return@post
            if (lm.findViewByPosition(lastIndex) !== card || messageAdapter.committedSnapshot().getOrNull(lastIndex)?.id != itemId) return@post
            scrollToChatBottomNow()
            captureCurrentScrollAnchor(preserveMode = ChatScrollMode.FOLLOW_LATEST)
            updateJumpToBottomButtonVisibility()
        }
    }

    private fun updateJumpToBottomButtonVisibility() {
        if (!::jumpToBottomBtn.isInitialized || !::messageAdapter.isInitialized) return
        val shouldShow = messageAdapter.itemCount > 0 && !isAtBottomPixels()
        jumpToBottomBtn.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun handleTwoStageJumpClick() {
        if (!::messageAdapter.isInitialized || messageAdapter.itemCount == 0) return
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return
        val lastIndex = messageAdapter.itemCount - 1
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastViewTop = lm.findViewByPosition(lastIndex)?.let { it.top - scroll.paddingTop }
        val target = ChatScrollPolicy.jumpTarget(
            itemCount = messageAdapter.itemCount,
            firstVisiblePosition = firstVisible,
            lastItemTopPx = lastViewTop,
            topThresholdPx = dp(28)
        )
        val chatId = currentChatId()
        cancelScrollRestore()
        if (target == JumpButtonTarget.LAST_MESSAGE_START) {
            applyingProgrammaticScroll = true
            try {
                lm.scrollToPositionWithOffset(lastIndex, 0)
                flushScrollLayoutIfReady()
            } finally {
                applyingProgrammaticScroll = false
            }
            if (isAtBottomPixels()) {
                userScrollLockedChats.remove(chatId)
                captureCurrentScrollAnchor(preserveMode = ChatScrollMode.FOLLOW_LATEST)
            } else {
                userScrollLockedChats.add(chatId)
                captureCurrentScrollAnchor(preserveMode = ChatScrollMode.READING_LOCKED)
            }
            updateJumpToBottomButtonVisibility()
        } else {
            scrollToLatest(force = true)
        }
    }

    private fun isNearLatest(): Boolean {
        if (!::messageAdapter.isInitialized || messageAdapter.itemCount == 0) return true
        val lm = scroll.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastVisibleItemPosition()
        val locked = currentChatId() in userScrollLockedChats ||
            (scroll.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
        return ChatScrollPolicy.shouldScrollToLatest(
            itemCount = messageAdapter.itemCount,
            lastVisiblePosition = lastVisible,
            pixelDistanceFromBottom = if (scroll.width > 0 && scroll.height > 0) pixelDistanceFromBottom() else null,
            maxFollowDistancePx = dp(64),
            userScrollLocked = locked
        )
    }

    private fun scrollToLatest(force: Boolean = false) {
        if (scrollCallbacksStopped || isFinishing || isDestroyed || !::scroll.isInitialized ||
            !::messageAdapter.isInitialized) return
        val chatId = currentChatId()
        if (force) {
            userScrollLockedChats.remove(chatId)
            val generation = beginScrollRestore(chatId, null)
            if (!messageAdapter.isListCommitPending()) tryPendingScrollRestore(chatId, generation)
            scroll.post { if (isScrollRestoreCurrent(chatId, generation)) tryPendingScrollRestore(chatId, generation) }
            return
        }
        if (messageAdapter.itemCount == 0 || messageAdapter.isListCommitPending()) return
        if (pendingScrollRestoreChatId == chatId || !isNearLatest()) {
            updateJumpToBottomButtonVisibility()
            return
        }
        val generation = scrollRestoreGeneration
        if (scroll.width > 0 && scroll.height > 0 && !scroll.isComputingLayout) {
            scrollToChatBottomNow()
            captureCurrentScrollAnchor(preserveMode = ChatScrollMode.FOLLOW_LATEST)
            updateJumpToBottomButtonVisibility()
        }
        scroll.post {
            if (scrollCallbacksStopped || isFinishing || isDestroyed || currentChatId() != chatId ||
                scrollRestoreGeneration != generation || pendingScrollRestoreChatId == chatId ||
                messageAdapter.isListCommitPending()) return@post
            if (messageAdapter.itemCount > 0 && isNearLatest()) {
                scrollToChatBottomNow()
                captureCurrentScrollAnchor(preserveMode = ChatScrollMode.FOLLOW_LATEST)
            }
            updateJumpToBottomButtonVisibility()
        }
    }

    // ---------- utils ----------
    private fun setBusy(v:Boolean){
        send.isEnabled=!v && pendingUploads.get()==0 && !preparingZipAttachments
        stop.visibility=if(v)View.VISIBLE else View.GONE
    }
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun beginUpload(chatId: String, generation: Long) {
        if (!isRequestCurrent(chatId, generation)) return
        pendingUploads.incrementAndGet()
        runOnUiThread {
            if (!isRequestCurrent(chatId, generation)) return@runOnUiThread
            send.isEnabled = false
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun endUpload(chatId: String, generation: Long) {
        val remaining = pendingUploads.updateAndGet { (it - 1).coerceAtLeast(0) }
        runOnUiThread {
            if (!isRequestCurrent(chatId, generation)) return@runOnUiThread
            if (remaining == 0 && stop.visibility != View.VISIBLE) send.isEnabled = true
        }
    }
    private fun createChatBubbleCard(): LinearLayout = LinearLayout(this).apply{
        val theme = currentTheme()
        orientation=LinearLayout.VERTICAL
        setPadding(dp(13),dp(10),dp(13),dp(10))
        background=rounded(theme.assistantBubbleBg,14)
        layoutParams=RecyclerView.LayoutParams(-1,-2).apply{bottomMargin=dp(8)}
    }

    private fun renderChatBubbleBody(item:ChatBubbleItem):CharSequence{
        // item.text is the live adapter snapshot. Persisted message JSON can lag SSE deltas.
        // Reconstruct the persisted response model only to recover structured citations.
        val sourceAnnotations = if (!item.user && item.message != null) {
            AstraResponseModel.fromChatJson(item.message).annotations
        } else item.message?.optJSONArray("annotations")
        return if (item.user) markdown(item.text, item.annotations ?: sourceAnnotations) else item.text
    }

    private fun bindChatBubbleCard(card:LinearLayout,item:ChatBubbleItem):TextView{
        val theme = currentTheme()
        card.removeAllViews()
        card.orientation=LinearLayout.VERTICAL
        card.setPadding(dp(13),dp(10),dp(13),dp(10))
        card.background=rounded(if(item.user) theme.userBubbleBg else theme.assistantBubbleBg,14)

        val who=TextView(this).apply{
            text=if (item.user) item.label else item.message?.let(AssistantModelLabel::text) ?: item.label
            textSize=12f
            setTextColor(if(item.user) theme.accent else theme.textSecondary)
            setTypeface(null,Typeface.BOLD)
        }
        card.addView(who)

        val body=TextView(this).apply{
            textSize=16f
            setTextColor(theme.textPrimary)
            setLinkTextColor(theme.accent)
            movementMethod=LinkMovementMethod.getInstance()
            highlightColor=Color.TRANSPARENT
            setPadding(0,dp(5),0,dp(0))
        }
        card.addView(body)

        if (!item.user) {
            val sourceChatId = currentChatId()
            val evidenceSession = evidenceSourceSession(sourceChatId)
            card.addView(RichResponsePanel(this,
                saveFile = { block -> saveTaggedResponseFile(sourceChatId, item, block) },
                continueAnswer = {
                    if (currentChatId() != sourceChatId) status.text = "Откройте исходный чат для продолжения"
                    else if (input.text.toString().isNotBlank() || attachedOpenRouterAttachments.isNotEmpty() ||
                        attachedFileIds.isNotEmpty() || pendingImagePath != null || pendingUploadUris.isNotEmpty()) {
                        status.text = "Сначала сохраните или отправьте текущий черновик"
                    } else {
                        input.setText("Продолжи предыдущий прерванный ответ с места остановки. Не повторяй уже написанное.")
                        sendMessage(false)
                    }
                },
                onAsyncLayoutCommitted = { onBubbleAsyncLayoutCommitted(sourceChatId, item.id, card) },
                evidenceSources = evidenceSession::sources,
                evidenceEligible = localEvidenceSnapshotChatId == sourceChatId && localEvidenceEligible,
                evidenceSourceVersion = evidenceSession.version))
        }

        val message=item.message
        if(item.user && message!=null){
            renderUserAttachments(message,card)
        }
        if(!item.user){
            val savedSummary=item.reasoningSummary?.trim().takeIf{!it.isNullOrBlank()}
                ?: message?.optString("reasoning_summary")?.trim()?.takeIf{it.isNotBlank()}
            if(savedSummary!=null) attachReasoningSummaryPanel(card,savedSummary,initiallyExpanded=false)
            if(message!=null){
                if(hasAssistantAssets(message)) addAssetSectionHeader(card)
                renderGeneratedImages(message,card)
                val annotations=message.optJSONArray("annotations") ?: item.annotations
                if(annotations!=null) renderAnnotations(
                    annotations,
                    card,
                    message,
                    currentChatId(),
                    requestGeneration
                )
            }
        }

        val boundChatId = currentChatId()
        fun latestSource(): String = messageAdapter.indexOfId(item.id).takeIf { it >= 0 }
            ?.let { messageAdapter.itemAt(it)?.text } ?: item.text
        if (!item.user) {
            val actions = LinearLayout(this).apply {
                tag = "assistant-actions"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            addFeedbackRow(actions, message, boundChatId)
            actions.addView(fileActionButton("Копировать") {
                if (activityVisible && !isFinishing && !isDestroyed && currentChatId() == boundChatId &&
                    messageAdapter.indexOfId(item.id) >= 0) copyText("Ответ", latestSource())
            })
            actions.addView(fileActionButton("Повторить") {
                repeatAssistantResponse(boundChatId, item)
            })
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            })
        }

        val longPress=View.OnLongClickListener{
            if (!activityVisible || isFinishing || isDestroyed || currentChatId() != boundChatId ||
                messageAdapter.indexOfId(item.id) < 0) return@OnLongClickListener true
            val menu=if(item.user)arrayOf("Копировать","Редактировать")
            else arrayOf("Копировать","Повторить")
            AppThemeViews.dialog(this, currentTheme()).setItems(menu){_,which->
                if (!activityVisible || isFinishing || isDestroyed || currentChatId() != boundChatId ||
                    messageAdapter.indexOfId(item.id) < 0) return@setItems
                // Read the newest adapter snapshot, not the transformed TextView or stale binding.
                val currentSource = messageAdapter.indexOfId(item.id).takeIf { it >= 0 }
                    ?.let { messageAdapter.itemAt(it)?.text } ?: item.text
                when(which){
                    0->{
                        val cm=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Сообщение",currentSource))
                        status.text="Скопировано"
                    }
                    1->if(item.user){
                        resolveBoundMessageIndex(item)?.let(::editMessage)
                    }else{
                        repeatAssistantResponse(boundChatId, item)
                    }
                }
            }.show()
            true
        }
        card.setOnLongClickListener(longPress)
        body.setOnLongClickListener(longPress)
        who.setOnLongClickListener(longPress)
        return body
    }

    /** Resolve the current row by durable identity, never by the index captured by a card. */
    private fun resolveBoundMessageIndex(bound: ChatBubbleItem): Int? {
        val fresh = messageAdapter.itemAt(messageAdapter.indexOfId(bound.id)) ?: return null
        if (fresh.user != bound.user) return null
        val expected = bound.message ?: return null
        val source = fresh.message ?: return null
        val row = expected.optLong("_db_message_id", 0L)
        val request = JsonProtocol.nonBlank(expected, "request_id")
        if (row > 0L && source.optLong("_db_message_id", 0L) != row) return null
        if (JsonProtocol.nonBlank(source, "request_id") != request) return null
        if (row <= 0L && request == null) return null
        val matches = chat.indices.filter { index ->
            val candidate = chat[index]
            candidate.optString("role") == (if (bound.user) "user" else "assistant") &&
                JsonProtocol.nonBlank(candidate, "request_id") == request &&
                (if (row > 0L) candidate.optLong("_db_message_id", 0L) == row
                 else JsonProtocol.nonBlank(candidate, "request_id") == request)
        }
        return matches.singleOrNull()
    }

    private fun repeatAssistantResponse(sourceChatId: String, bound: ChatBubbleItem) {
        if (!activityVisible || isFinishing || isDestroyed || currentChatId() != sourceChatId) return
        val assistantIndex = resolveBoundMessageIndex(bound)
        val request = assistantIndex?.let { JsonProtocol.nonBlank(chat[it], "request_id") }
        val userIndex = assistantIndex?.let { position ->
            (position - 1 downTo 0).firstOrNull { chat[it].optString("role") == "user" }
        }
        val latestUser = chat.indexOfLast { it.optString("role") == "user" }
        val durable = chatDb.getChat(sourceChatId)?.optJSONArray("messages")
        val durableRows = (0 until (durable?.length() ?: 0)).mapNotNull { durable?.optJSONObject(it) }
        val latestDurable = durableRows.lastOrNull { it.optString("role") == "user" }
        val user = userIndex?.let(chat::get)
        val assistantRow = assistantIndex?.let { chat[it].optLong("_db_message_id", 0L) } ?: 0L
        val ownsDurableAssistant = durableRows.any { it.optString("role") == "assistant" &&
            JsonProtocol.nonBlank(it,"request_id") == request &&
            (assistantRow <= 0L || it.optLong("_db_message_id",0L) == assistantRow) }
        val sameLatestUser = user != null && latestDurable != null &&
            JsonProtocol.nonBlank(user, "request_id") == request &&
            JsonProtocol.nonBlank(latestDurable, "request_id") == request &&
            (user.optLong("_db_message_id", 0L) <= 0L ||
                user.optLong("_db_message_id", 0L) == latestDurable.optLong("_db_message_id", 0L))
        if (bound.user || request == null || userIndex != latestUser || !sameLatestUser || !ownsDurableAssistant) {
            AppThemeViews.dialog(this, currentTheme()).setTitle("Повторить исторический ответ")
                .setMessage("Автоматически можно повторить только ответ на последний запрос этого чата. Для исторического ответа скопируйте нужный запрос в новый чат; текущий черновик сохранён.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        if (input.text.toString().isNotBlank() || attachedOpenRouterAttachments.isNotEmpty() ||
            attachedFileIds.isNotEmpty() || attachedFilePaths.isNotEmpty() || pendingImagePath != null ||
            pendingUploadUris.isNotEmpty()) {
            status.text = "Сначала сохраните или отправьте текущий черновик"
            return
        }
        sendMessage(true)
    }

    private fun saveTaggedResponseFile(chatId: String, item: ChatBubbleItem, block: ResponseBlockParser.Block.File) {
        if (!block.closed || !ResponseRenderSafety.textFilename(block.name)) return
        val requestId = JsonProtocol.nonBlank(item.message ?: JSONObject(), "request_id") ?: item.id
        executor.execute {
            try {
                val artifact = LocalArtifactToolExecutor(applicationContext).createTextFile(JSONObject()
                    .put("filename", block.name).put("content", block.content))
                if (!chatDb.registerLocalArtifact(chatId, requestId, artifact)) {
                    File(artifact.getString("path")).delete(); throw IOException("Чат удалён")
                }
                runOnUiThread {
                    if (activityVisible && currentChatId() == chatId)
                        requestSaveLocalFile(artifact.getString("path"), artifact.getString("filename"), artifact.getString("mime_type"))
                }
            } catch (error: Exception) {
                runOnUiThread { if (activityVisible && currentChatId() == chatId) status.text = "Файл не сохранён: ${error.message}" }
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun addBubble(
        label:String,
        text:String,
        user:Boolean,
        annotations:JSONArray?=null,
        message:JSONObject?=null,
        itemId:String?=null
    ):String{
        val chatIndex=message?.let{target->chat.indexOfFirst{it===target}.takeIf{it>=0}}
        val id=when{
            !itemId.isNullOrBlank() -> itemId
            chatIndex!=null -> ChatScrollPolicy.messageIdentity(chatIndex, user, message)
            else -> "bubble-${UUID.randomUUID()}"
        }
        val sourceChatId = currentChatId()
        val followAfterCommit = user || isNearLatest() ||
            (pendingScrollRestoreChatId == sourceChatId && pendingScrollRestoreAnchor == null)
        val generation = if (user) {
            userScrollLockedChats.remove(sourceChatId)
            beginScrollRestore(sourceChatId, null)
        } else scrollRestoreGeneration
        messageAdapter.addItem(
            ChatBubbleItem.create(
                id=id,
                label=label,
                text=text,
                user=user,
                annotations=annotations,
                message=message,
                chatIndex=chatIndex,
                reasoningSummary=message?.optString("reasoning_summary")?.trim()?.takeIf{it.isNotBlank()}
            )
        ) {
            if (!scrollCallbacksStopped && !isFinishing && !isDestroyed &&
                currentChatId() == sourceChatId && scrollRestoreGeneration == generation) {
                if (isScrollRestoreCurrent(sourceChatId, generation)) tryPendingScrollRestore(sourceChatId, generation)
                else if (followAfterCommit && sourceChatId !in userScrollLockedChats) {
                    scrollToChatBottomNow()
                    captureCurrentScrollAnchor(preserveMode = ChatScrollMode.FOLLOW_LATEST)
                    updateJumpToBottomButtonVisibility()
                } else scrollToLatest()
            }
        }
        return id
    }

    private fun addFeedbackRow(card:LinearLayout, message:JSONObject?, chatId:String){
        val requestId=message?.let { JsonProtocol.nonBlank(it,"request_id") }
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,0)}
        val current=message?.optInt("feedback",0) ?: 0
        fun feedbackButton(label:String,value:Int)=TextView(this).apply{
            text=if(current==value) "$label ✓" else label
            textSize=12f
            setTextColor(currentTheme().textSecondary)
            setPadding(dp(6),dp(3),dp(10),dp(3))
            isEnabled=current==0 && requestId != null
            setOnClickListener{
                if (!activityVisible || isFinishing || isDestroyed || currentChatId() != chatId ||
                    requestId == null || message == null || message.optInt("feedback",0)!=0)return@setOnClickListener
                val owner = chat.lastOrNull { it.optString("role") == "assistant" &&
                    JsonProtocol.nonBlank(it,"request_id") == requestId } ?: return@setOnClickListener
                if (owner.optInt("feedback",0) != 0) return@setOnClickListener
                owner.put("feedback",value)
                message.put("feedback",value)
                chatDb.recordFeedback(chatId,requestId,value)
                diagnostics.log("effort_feedback",mapOf("chat_id" to chatId,"request_id" to requestId,"value" to value))
                text="$label ✓"
                for(i in 0 until row.childCount) row.getChildAt(i).isEnabled=false
                status.text="Спасибо за оценку"
            }
        }
        row.addView(feedbackButton("👍",1))
        row.addView(feedbackButton("👎",-1))
        card.addView(row)
    }

    private fun markdown(s:String, annotations:JSONArray?=null):CharSequence = RichTextRenderer.render(s,annotations,currentTheme())

    private fun button(t:String,a:()->Unit)=Button(this).apply{
        text=t;textSize=16f;minWidth=0;minimumWidth=0
        setPadding(dp(10),dp(6),dp(10),dp(6))
        AppThemeViews.button(this, currentTheme())
        setOnClickListener{a()}
    }
    private fun rippleRounded(fill:Int, radiusDp:Int=10): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(55,255,255,255)),
            rounded(fill, radiusDp),
            null
        )
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(fill:Int, radiusDp:Int=10): GradientDrawable = GradientDrawable().apply{
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

}
