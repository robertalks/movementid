package com.movementid.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movementid.app.backup.BackupManager
import com.movementid.app.backup.BackupOutcome
import com.movementid.app.backup.BackupWorker
import com.movementid.app.data.AppDatabase
import com.movementid.app.data.MovementEntry
import com.movementid.app.network.GeminiClient
import com.movementid.app.network.LiftAngleTable
import com.movementid.app.network.OpenAiClient
import com.movementid.app.network.MovementIdentification
import com.movementid.app.repository.ModelAttempt
import com.movementid.app.repository.MovementRepository
import com.movementid.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ScanState {
    data object Idle : ScanState()

    /** Analyzing, with a human-readable stage line for the overlay. */
    data class Analyzing(val stage: String) : ScanState()

    /** Results in hand, nothing saved yet — the user picks which answer to keep. */
    data class Review(val photoFiles: List<File>, val attempts: List<ModelAttempt>) : ScanState()

    data class Error(val message: String) : ScanState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settings = SettingsRepository(application)
    private val repository = MovementRepository(
        AppDatabase.getInstance(application).movementDao(),
        settings,
        LiftAngleTable(application)
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val movements: StateFlow<List<MovementEntry>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.allEntries else repository.search(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Held so the user can cancel a run that's dragging on. */
    private var analysisJob: Job? = null

    /**
     * Photos staged for the next scan. More than one is genuinely useful here: a dial-side shot,
     * or the same caliber from another watch with different branding, often settles what a
     * single case-back photo can't.
     */
    private val _pendingPhotos = MutableStateFlow<List<File>>(emptyList())
    val pendingPhotos: StateFlow<List<File>> = _pendingPhotos.asStateFlow()

    /**
     * Set when images arrive from the Android share sheet, so navigation can jump straight to
     * the scan screen with them already staged.
     */
    private val _sharedImagesReady = MutableStateFlow(false)
    val sharedImagesReady: StateFlow<Boolean> = _sharedImagesReady.asStateFlow()

    /** Copies shared images into app storage and stages them for identification. */
    fun receiveSharedImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Replace rather than append: a fresh share is a new subject, not more photos of
            // whatever happened to be staged from an earlier session.
            clearPendingPhotos()
            resetScanState()
            val copied = uris.mapNotNull { copyToStorage(it) }
            if (copied.isEmpty()) {
                _scanState.value = ScanState.Error("Couldn't read the shared image.")
                return@launch
            }
            _pendingPhotos.value = copied
            _sharedImagesReady.value = true
        }
    }

    fun onSharedImagesHandled() {
        _sharedImagesReady.value = false
    }

    fun addPendingPhoto(file: File) {
        _pendingPhotos.value = _pendingPhotos.value + file
    }

    fun removePendingPhoto(file: File) {
        _pendingPhotos.value = _pendingPhotos.value - file
        runCatching { file.delete() }
    }

    fun clearPendingPhotos() {
        _pendingPhotos.value.forEach { runCatching { it.delete() } }
        _pendingPhotos.value = emptyList()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Copies a gallery pick into app storage so the entry owns its own image file. */
    fun importFromGallery(uri: Uri) {
        viewModelScope.launch {
            val file = copyToStorage(uri)
            if (file != null) {
                addPendingPhoto(file)
            } else {
                _scanState.value = ScanState.Error("Couldn't read that image.")
            }
        }
    }

    /** Runs identification on every staged photo, optionally with the user's correction. */
    fun analyze(feedback: String? = null, sharp: Boolean = false) {
        val photos = _pendingPhotos.value
        if (photos.isEmpty()) {
            _scanState.value = ScanState.Error("Take or choose a photo first.")
            return
        }
        val provider = settings.getProvider()
        if (!settings.hasApiKey(provider)) {
            _scanState.value =
                ScanState.Error("Add your ${provider.displayName} API key in Settings first.")
            return
        }

        val models = settings.getSelectedModels()
        analysisJob?.cancel()
        _scanState.value = ScanState.Analyzing(
            describeStage(models, settings.getVerifyWithSearch(), sharp || settings.getAlwaysSharp())
        )

        analysisJob = viewModelScope.launch {
            try {
                val attempts = repository.identifyAll(photos, models, feedback, sharp)

                // Per-model errors go to Logcat, never the UI. If one model answered, the others'
                // failures are noise; if none did, one plain sentence beats a wall of them.
                attempts.filterNot { it.succeeded }.forEach {
                    Log.w(TAG, "${it.model} failed: ${it.error}")
                }

                _scanState.value = if (attempts.any { it.succeeded }) {
                    ScanState.Review(photos, attempts)
                } else {
                    ScanState.Error(summarizeFailure(attempts))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** User-initiated cancel while a scan is running; keeps the photo so they can retry. */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _scanState.value = ScanState.Idle
    }

    /** Saves the chosen attempt using whatever the user corrected in the review sheet. */
    fun saveAttempt(
        attempt: ModelAttempt,
        edited: MovementIdentification,
        onSaved: (Long) -> Unit
    ) {
        val state = _scanState.value as? ScanState.Review ?: return
        viewModelScope.launch {
            val entry = repository.save(state.photoFiles, attempt, edited)
            _pendingPhotos.value = emptyList()   // ownership passes to the saved entry
            BackupWorker.backupSoon(getApplication())
            _scanState.value = ScanState.Idle
            onSaved(entry.id)
        }
    }

    /**
     * One sentence for the whole run. The common causes (bad key, exhausted quota, no network,
     * a model id that doesn't exist) each deserve their own wording; anything else falls back to
     * a single representative error rather than every model's version of the same thing.
     */
    private fun summarizeFailure(attempts: List<ModelAttempt>): String {
        val provider = settings.getProvider()
        val name = provider.displayName
        val errors = attempts.mapNotNull { it.error }
        if (errors.isEmpty()) return "Couldn't identify this photo."

        // The provider's own words, always shown — named after whichever provider answered, not
        // assumed to be Google.
        val raw = errors.first().substringAfter("] ").trim().take(220)
        val suffix = "\n\n$name said: $raw"

        val cause = if (provider.isGemini) geminiCause(errors) else openAiCause(errors)
        return cause + suffix
    }

    private fun geminiCause(errors: List<String>): String {
        fun has(vararg needles: String) =
            errors.any { e -> needles.any { e.contains(it, ignoreCase = true) } }
        return when {
            has("is not found", "not found for api version", "does not exist",
                "not supported for generatecontent", "model_not_found") ->
                "That model isn't available to your key — Google may have retired or restricted " +
                    "it. Preview models are the usual culprit. Pick another in Settings."
            has("api key not valid", "api_key_invalid", "invalid api key",
                "api key expired", "api_key_expired") ->
                "Google says the API key isn't valid. If it worked before, check it wasn't " +
                    "deleted or regenerated at aistudio.google.com."
            has("permission_denied", "permission denied", "does not have permission") ->
                "Google refused access. The key is recognised, but this model or feature isn't " +
                    "enabled for it — try a different model first."
            has("every model is out of quota") ->
                "Every Gemini model is out of free quota for today. Limits reset at midnight " +
                    "Pacific time — 09:00 in Central Europe."
            has("resource_exhausted", "exceeded your current quota", "quota", "http 429") ->
                "Gemini's free quota is used up. It resets daily — or run fewer models per scan."
            has("unable to resolve host", "failed to connect", "no address", "unknown host") ->
                "No connection to Gemini. Check your network."
            has("timeout", "timed out") -> "Gemini didn't respond in time. Try again."
            has("payload size", "request too large", "http 413") ->
                "The photos were too large to send together. Try fewer photos per scan."
            else -> "Couldn't identify this photo."
        }
    }

    /**
     * OpenAI's failures differ in kind, not just wording: there's no free tier, and "quota" means
     * the account has run out of prepaid credit — which applies to every model at once, so no
     * model switch will help.
     */
    private fun openAiCause(errors: List<String>): String {
        fun has(vararg needles: String) =
            errors.any { e -> needles.any { e.contains(it, ignoreCase = true) } }
        return when {
            has("insufficient_quota", "exceeded your current quota", "billing", "credit") ->
                "Your OpenAI account has no credit left. OpenAI has no free tier — add prepaid " +
                    "credit at platform.openai.com under Billing. This applies to every OpenAI " +
                    "model, so switching models won't help; switching to Gemini will."
            has("incorrect api key", "invalid api key", "invalid_api_key") ->
                "OpenAI says the API key isn't valid. Check it at platform.openai.com/api-keys."
            has("does not exist", "model_not_found", "do not have access") ->
                "That model isn't available on your OpenAI account. Model names change often — " +
                    "check the model list in your OpenAI dashboard and pick another in Settings."
            has("rate limit", "http 429") ->
                "OpenAI is rate-limiting requests. Wait a moment and try again."
            has("unable to resolve host", "failed to connect", "no address", "unknown host") ->
                "No connection to OpenAI. Check your network."
            has("timeout", "timed out") -> "OpenAI didn't respond in time. Try again."
            else -> "Couldn't identify this photo."
        }
    }

    /** Models currently running a manual grounded check, so the button can show progress. */
    private val _verifying = MutableStateFlow<Set<String>>(emptySet())
    val verifying: StateFlow<Set<String>> = _verifying.asStateFlow()

    /**
     * Runs the grounded Google Search check for one result on demand. Automatic verification is
     * limited to a single result per scan to protect the free-tier quota; this is how the user
     * checks any of the others.
     */
    fun verifyAttempt(attempt: ModelAttempt) {
        if (_scanState.value !is ScanState.Review) return
        if (attempt.model in _verifying.value) return

        _verifying.value = _verifying.value + attempt.model
        viewModelScope.launch {
            val updated = repository.verify(attempt)
            val current = _scanState.value as? ScanState.Review
            if (current != null) {
                _scanState.value = current.copy(
                    attempts = current.attempts.map { if (it.model == updated.model) updated else it }
                )
            }
            _verifying.value = _verifying.value - attempt.model
        }
    }

    /** Result of the Settings self-test, so the user never has to open Logcat. */
    private val _searchTestResult = MutableStateFlow<String?>(null)
    val searchTestResult: StateFlow<String?> = _searchTestResult.asStateFlow()

    private val _searchTestRunning = MutableStateFlow(false)
    val searchTestRunning: StateFlow<Boolean> = _searchTestRunning.asStateFlow()

    /**
     * Runs one real grounded request against a known caliber. If Google Search grounding is
     * unavailable on this key or model, this says exactly why — which is the thing the generic
     * "Web check unavailable" line can't tell you.
     */
    fun testGoogleSearch() {
        val provider = settings.getProvider()
        val apiKey = settings.getApiKey(provider)
        if (apiKey.isBlank()) {
            _searchTestResult.value = "No ${provider.displayName} API key saved yet."
            return
        }
        val model = settings.getSelectedModels(provider).firstOrNull()
            ?: provider.defaultModels.first()

        _searchTestRunning.value = true
        _searchTestResult.value = null
        viewModelScope.launch {
            // A caliber with well-published specs, so a working search has something to find.
            val probe = MovementIdentification(
                caliber = "NH35A",
                movementFamily = "Seiko NH35A",
                brandGuess = "Seiko"
            )
            val searched = if (provider.isGemini) {
                GeminiClient(apiKey, model).verifyWithGoogleSearch(probe)
            } else {
                OpenAiClient(apiKey, model).verifyWithWebSearch(probe)
            }

            val outcome = searched.fold(
                onSuccess = { (refined, sources) ->
                    if (sources.isEmpty()) {
                        "$model answered, but reported no search sources.\n\n" +
                            "The request was accepted, so search isn't rejected outright — " +
                            "the model just didn't search. Beat rate came back as " +
                            "\"${refined.beatRateVph ?: "nothing"}\"."
                    } else {
                        "Working. $model searched and returned ${sources.size} sources:\n\n" +
                            sources.take(4).joinToString("\n") { "• ${it.title}" }
                    }
                },
                onFailure = { error -> "Failed on $model:\n\n${error.message}" }
            )
            _searchTestResult.value = outcome
            _searchTestRunning.value = false
        }
    }

    /** Display name of the active provider, for button labels. */
    fun providerName(): String = settings.getProvider().displayName

    fun clearSearchTest() {
        _searchTestResult.value = null
    }

    private fun describeStage(models: List<String>, verifying: Boolean, sharp: Boolean = false): String {
        val asking = if (models.size == 1) {
            "Identifying with ${models.first()}…"
        } else {
            "Asking ${models.size} models…"
        }
        val detail = listOfNotNull(
            "at full resolution".takeIf { sharp },
            (if (settings.getProvider().isGemini) "then checking against Google Search"
            else "then checking the web").takeIf { verifying }
        ).joinToString("\n")
        return if (detail.isBlank()) asking else "$asking\n$detail"
    }

    fun discardReview() {
        clearPendingPhotos()
        _scanState.value = ScanState.Idle
    }

    /**
     * Re-runs the same photos at full resolution. Offered when a scan read no markings: tiny
     * engraving sometimes needs every pixel, and only paying for that on the scans that need it
     * is what keeps the default cheap.
     */
    fun retrySharper() {
        if (_scanState.value !is ScanState.Review) return
        analyze(sharp = true)
    }

    fun retryAnalysis() {
        if (_scanState.value !is ScanState.Review) return
        analyze()
    }

    /**
     * Re-runs identification with the user's correction. This is the most valuable input the
     * model can get: the user is holding the watch and can see the case, the dial side and the
     * provenance, none of which a photo of the movement conveys.
     */
    fun reaskWithFeedback(feedback: String) {
        if (feedback.isBlank()) return
        analyze(feedback.trim())
    }

    fun resetScanState() {
        analysisJob?.cancel()
        analysisJob = null
        _scanState.value = ScanState.Idle
    }

    suspend fun getEntry(id: Long): MovementEntry? = repository.getById(id)

    fun updateEntry(entry: MovementEntry) {
        viewModelScope.launch {
            repository.update(entry)
            BackupWorker.backupSoon(getApplication())
        }
    }

    /** Copies a picked image into app storage and attaches it to a saved movement. */
    fun addPhotoToEntry(entry: MovementEntry, uri: Uri, onDone: (MovementEntry) -> Unit) {
        viewModelScope.launch {
            val file = copyToStorage(uri) ?: return@launch
            onDone(repository.addPhoto(entry, file))
        }
    }

    /** Attaches an already-captured file (from the camera) to a saved movement. */
    fun attachPhotoToEntry(entry: MovementEntry, file: File, onDone: (MovementEntry) -> Unit) {
        viewModelScope.launch { onDone(repository.addPhoto(entry, file)) }
    }

    private suspend fun copyToStorage(uri: Uri): File? = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().filesDir, "movements").apply { mkdirs() }
        val name = "movement_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val target = File(dir, name)
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.takeIf { it.length() > 0 }
    }

    fun deleteEntry(entry: MovementEntry) {
        viewModelScope.launch {
            repository.delete(entry)
            BackupWorker.backupSoon(getApplication())
        }
    }

    // --- Backup ---

    private val backupManager = BackupManager(application)

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    fun clearBackupStatus() {
        _backupStatus.value = null
    }

    /** Immediate, user-initiated backup — reports the outcome rather than failing silently. */
    fun backupNow() {
        if (_backupBusy.value) return
        _backupBusy.value = true
        _backupStatus.value = null
        viewModelScope.launch {
            _backupStatus.value = when (val outcome = backupManager.runBackup()) {
                is BackupOutcome.Uploaded ->
                    "Backed up ${outcome.fileName} (${outcome.bytes / 1024} kB)."

                is BackupOutcome.SavedLocally ->
                    "${outcome.reason}\n\nThe archive is on this phone, so it won't survive " +
                        "losing it. Use Export to move it somewhere safe, or fix the server " +
                        "settings and try again."

                is BackupOutcome.Failed -> outcome.reason
                BackupOutcome.NothingToDo -> "Nothing saved yet — no backup needed."
            }
            _backupBusy.value = false
        }
    }

    fun testBackupConnection(url: String, user: String, password: String) {
        if (_backupBusy.value) return
        _backupBusy.value = true
        _backupStatus.value = null
        viewModelScope.launch {
            _backupStatus.value = backupManager.testConnection(url, user, password).fold(
                onSuccess = { "Connected. The folder is reachable and writable." },
                onFailure = { it.message ?: "Couldn't connect." }
            )
            _backupBusy.value = false
        }
    }

    /** Writes an archive to a location the user chose. */
    fun exportBackup(target: Uri) {
        _backupBusy.value = true
        viewModelScope.launch {
            _backupStatus.value = backupManager.exportTo(target).fold(
                onSuccess = { "Exported." },
                onFailure = { it.message ?: "Export failed." }
            )
            _backupBusy.value = false
        }
    }

    /** Restores from a file the user picked, replacing what's saved now. */
    fun restoreBackup(source: Uri) {
        _backupBusy.value = true
        viewModelScope.launch {
            _backupStatus.value = backupManager.restoreFrom(source).fold(
                onSuccess = { count -> "Restored $count movements." },
                onFailure = { it.message ?: "Restore failed." }
            )
            _backupBusy.value = false
        }
    }

    /** Restores the newest archive held on the server. */
    fun restoreFromServer() {
        _backupBusy.value = true
        viewModelScope.launch {
            val newest = backupManager.listServerBackups().getOrNull()?.firstOrNull()
            _backupStatus.value = if (newest == null) {
                "No backups found on the server."
            } else {
                backupManager.restoreFromServer(newest.name).fold(
                    onSuccess = { count -> "Restored $count movements from ${newest.name}." },
                    onFailure = { it.message ?: "Restore failed." }
                )
            }
            _backupBusy.value = false
        }
    }

    /** Re-applies the schedule after any backup setting changes. */
    fun rescheduleBackups() = BackupWorker.schedule(getApplication())

    private companion object {
        const val TAG = "MovementID/Scan"
    }
}
