package com.movementid.app.repository

import android.util.Log
import com.movementid.app.network.ImageEncoder
import com.movementid.app.network.isQuotaExhausted
import com.movementid.app.data.MovementDao
import com.movementid.app.data.MovementEntry
import com.movementid.app.data.photoList
import com.movementid.app.network.GeminiClient
import com.movementid.app.network.OpenAiClient
import com.movementid.app.network.IdentificationResult
import com.movementid.app.network.LiftAngleCheck
import com.movementid.app.network.LiftAngleTable
import com.movementid.app.network.MovementIdentification
import com.movementid.app.network.SearchStatus
import com.movementid.app.network.isSearchable
import com.movementid.app.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

/** One model's attempt at a photo — success or failure, kept so the UI can show both. */
data class ModelAttempt(
    val model: String,
    val result: IdentificationResult? = null,
    val error: String? = null,
    /** Set when [model] ran out of quota and this answer came from another model instead. */
    val fellBackFrom: String? = null
) {
    val succeeded: Boolean get() = result != null
}

private const val TAG = "MovementID/Repo"
private val EXHAUSTED_TTL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(3)

/** Models found to be out of quota, shared across repository instances for the session. */
private val exhausted = java.util.concurrent.ConcurrentHashMap<String, Long>()

class MovementRepository(
    private val dao: MovementDao,
    private val settings: SettingsRepository,
    private val liftAngles: LiftAngleTable
) {

    val allEntries: Flow<List<MovementEntry>> = dao.getAll()

    fun search(query: String): Flow<List<MovementEntry>> = dao.search(query)

    /**
     * Runs the photos past each selected model in parallel. Models are independent, so one
     * failing doesn't affect the others — the UI shows successes and failures side by side.
     *
     * Every photo goes to every model in a single request: they're all of the same movement, so
     * a caliber number readable in one shot can settle what another leaves ambiguous.
     */
    suspend fun identifyAll(
        photoFiles: List<File>,
        models: List<String>,
        feedback: String? = null,
        sharp: Boolean = false
    ): List<ModelAttempt> =
        coroutineScope {
            val maxEdge = if (sharp || settings.getAlwaysSharp()) {
                ImageEncoder.SHARP_EDGE_PX
            } else {
                ImageEncoder.STANDARD_EDGE_PX
            }
            val claimed = java.util.Collections.synchronizedSet(models.toMutableSet())
            val attempts = models
                .map { model -> async { identifyWithFallback(photoFiles, model, feedback, maxEdge, claimed) } }
                .map { it.await() }

            val firstSuccess = attempts.indexOfFirst { it.succeeded }
            if (firstSuccess < 0) return@coroutineScope attempts

            // Only one result gets the grounded pass, and only when asked for: it spends an
            // extra Gemini request and that quota is the scarce one.
            if (!settings.getVerifyWithSearch()) return@coroutineScope attempts

            attempts.toMutableList().apply {
                this[firstSuccess] = verify(this[firstSuccess])
            }
        }

    /**
     * Cross-checks the model's lift angle against WatchGuy's published table. Where they differ
     * the table wins — it's a measured per-caliber constant, and a wrong lift angle silently
     * skews every amplitude reading on a timegrapher. The model's own answer is kept so the
     * disagreement stays visible rather than being quietly overwritten.
     */
    private suspend fun withLiftAngleCheck(result: IdentificationResult): IdentificationResult {
        val check = runCatching { liftAngles.check(result.identification) }
            .getOrElse { LiftAngleCheck.NotListed }

        val authoritative = check.entryOrNull?.angle?.let { "$it\u00b0" }
        return result.copy(
            identification = if (authoritative != null) {
                result.identification.copy(liftAngle = authoritative)
            } else {
                result.identification
            },
            liftAngleCheck = check
        )
    }

    /** Grounded verification of a single attempt — also used by the review sheet's manual check. */
    suspend fun verify(attempt: ModelAttempt): ModelAttempt {
        val result = attempt.result ?: return attempt
        val provider = settings.getProvider()
        val apiKey = settings.getApiKey(provider)
        if (apiKey.isBlank()) return attempt

        if (!result.identification.isSearchable()) {
            return attempt.copy(result = result.copy(searchStatus = SearchStatus.NoQuery))
        }

        // Each provider's search is its own mechanism: Gemini grounding vs OpenAI's web_search
        // tool on the Responses API.
        val searched = if (provider.isGemini) {
            GeminiClient(apiKey, attempt.model).verifyWithGoogleSearch(result.identification)
        } else {
            OpenAiClient(apiKey, attempt.model).verifyWithWebSearch(result.identification)
        }

        val verified = searched.fold(
                onSuccess = { (refinedRaw, sources) ->
                    // The table outranks whatever the search pass decided about lift angle.
                    val refined = result.liftAngleCheck.entryOrNull
                        ?.let { refinedRaw.copy(liftAngle = "${it.angle}\u00b0") }
                        ?: refinedRaw
                    if (sources.isEmpty()) {
                        result.copy(searchStatus = SearchStatus.NoResults)
                    } else {
                        result.copy(
                            identification = refined,
                            searchStatus = SearchStatus.Verified(
                                hitCount = sources.size,
                                changed = refined != result.identification
                            ),
                            sources = sources
                        )
                    }
                },
                onFailure = { error ->
                    result.copy(searchStatus = SearchStatus.Failed(describeSearchError(error.message)))
                }
            )

        return attempt.copy(result = verified)
    }

    /** Turns raw API errors into something the user can act on. */
    private fun describeSearchError(raw: String?): String = when {
        raw == null -> "unknown error"
        raw.contains("insufficient_quota", true) -> "no OpenAI credit left"
        raw.contains("RESOURCE_EXHAUSTED", true) || raw.contains("quota", true) ||
            raw.contains("HTTP 429") -> "${settings.getProvider().displayName} quota exhausted — try again later"
        raw.contains("PERMISSION_DENIED", true) || raw.contains("does not have permission", true) ->
            "search not permitted on this key"
        raw.contains("not supported", true) || raw.contains("unknown field", true) ->
            "search unsupported on this model"
        raw.contains("timeout", true) -> "search timed out"
        else -> raw.substringAfter("] ").take(140)
    }

    /**
     * Tries [model]; if it's out of quota, moves on to the next model that still has room.
     *
     * Gemini's free-tier limits are per model, so one model running dry says nothing about the
     * others — and "out of quota" is far more useful as "answered by a different model" than as
     * a dead end. Falls back at standard resolution, so a fallback doesn't burn the next model's
     * quota as fast as the one that just ran out.
     */
    private suspend fun identifyWithFallback(
        photoFiles: List<File>,
        model: String,
        feedback: String?,
        maxEdge: Int,
        claimed: MutableSet<String>
    ): ModelAttempt {
        // Already known to be dry this session: don't spend a round trip finding out again.
        val first = if (isExhaustedToday(model)) {
            ModelAttempt(model, error = "RESOURCE_EXHAUSTED (known from an earlier scan)")
        } else {
            identifyOne(photoFiles, model, feedback, maxEdge)
        }
        if (first.succeeded || !isQuotaExhausted(first.error)) return first

        // Only Gemini's quotas are per model. OpenAI's "quota" is the account's prepaid credit,
        // shared by every model — trying each one in turn would just repeat the same failure
        // and delay the real message.
        if (!settings.getProvider().isGemini) return first

        markExhausted(model)
        Log.i(TAG, "$model is out of quota, looking for another model")

        for (candidate in fallbackCandidates()) {
            // Claim atomically, so parallel runs don't pile onto the same fallback.
            if (!claimed.add(candidate)) continue

            val attempt = identifyOne(photoFiles, candidate, feedback, ImageEncoder.STANDARD_EDGE_PX)
            if (attempt.succeeded) return attempt.copy(fellBackFrom = model)
            if (isQuotaExhausted(attempt.error)) {
                markExhausted(candidate)
                continue
            }
            // A different kind of failure — report it rather than trying every model.
            return attempt.copy(fellBackFrom = model)
        }

        // Phrased so the ViewModel recognises it; the user-facing wording lives there.
        return first.copy(error = "Every model is out of quota. (${first.error})")
    }

    /** Models worth trying next: the user's own list first, then the rest, minus exhausted ones. */
    private fun fallbackCandidates(): List<String> {
        val provider = settings.getProvider()
        val ordered = (settings.getSelectedModels(provider) + provider.defaultModels).distinct()
        return ordered.filterNot { isExhaustedToday(it) }
    }

    private fun markExhausted(model: String) {
        exhausted[model] = System.currentTimeMillis()
    }

    /**
     * Remembered for this app session so later scans skip models already known to be dry,
     * rather than spending time discovering it again. Cleared after a few hours in case the
     * daily reset has passed.
     */
    private fun isExhaustedToday(model: String): Boolean {
        val at = exhausted[model] ?: return false
        return System.currentTimeMillis() - at < EXHAUSTED_TTL_MS
    }

    private suspend fun identifyOne(
        photoFiles: List<File>,
        model: String,
        feedback: String?,
        maxEdge: Int
    ): ModelAttempt {
        val provider = settings.getProvider()
        val apiKey = settings.getApiKey(provider)
        if (apiKey.isBlank()) {
            return ModelAttempt(model, error = "No ${provider.displayName} API key saved")
        }

        val identified = if (provider.isGemini) {
            GeminiClient(apiKey, model).identify(photoFiles, feedback, maxEdge)
        } else {
            OpenAiClient(apiKey, model).identify(photoFiles, feedback, maxEdge)
        }

        val identifiedResult = identified.getOrElse { error ->
            return ModelAttempt(model, error = error.message ?: "Unknown error")
        }

        val result = withLiftAngleCheck(identifiedResult)

        return ModelAttempt(model, result.copy(searchStatus = SearchStatus.Disabled))
    }

    /** Saves the reviewed result, using whatever the user edited rather than the raw answer. */
    suspend fun save(
        photoFiles: List<File>,
        attempt: ModelAttempt,
        edited: MovementIdentification
    ): MovementEntry {
        val result = requireNotNull(attempt.result) { "Cannot save a failed attempt" }
        val entry = MovementEntry(
            timestamp = System.currentTimeMillis(),
            photoPath = photoFiles.first().absolutePath,
            additionalPhotos = photoFiles.drop(1)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.absolutePath },
            markings = edited.visibleMarkings?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            brandGuess = edited.brandGuess,
            movementFamily = edited.movementFamily,
            movementType = edited.movementType,
            caliber = edited.caliber,
            sizeMm = edited.sizeMm,
            beatRateVph = edited.beatRateVph,
            powerReserveHours = edited.powerReserveHours,
            jewelCount = edited.jewelCount,
            liftAngle = edited.liftAngle,
            liftAngleSource = when (val check = result.liftAngleCheck) {
                is LiftAngleCheck.Found -> "WatchGuy: ${check.entry.brand} ${check.entry.caliber}"
                is LiftAngleCheck.Conflict ->
                    "WatchGuy: ${check.entry.brand} ${check.entry.caliber} " +
                        "(model said ${check.modelSaid})"
                is LiftAngleCheck.Ambiguous ->
                    "Model's figure — WatchGuy lists ${check.caliber} under several brands"
                LiftAngleCheck.NotListed -> null
            },
            productionYears = edited.productionYears,
            confidence = edited.confidence,
            aiNotes = edited.notes,
            modelUsed = result.modelUsed,
            latencyMs = result.latencyMs,
            webVerified = result.searchStatus is SearchStatus.Verified,
            sources = result.sources
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { hit ->
                    if (hit.link.isBlank()) hit.title else "${hit.title} — ${hit.link}"
                },
            title = edited.movementFamily?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        )
        return entry.copy(id = dao.insert(entry))
    }

    suspend fun getById(id: Long): MovementEntry? = dao.getById(id)

    suspend fun update(entry: MovementEntry) = dao.update(entry)

    /** Attaches another photo to a saved movement. */
    suspend fun addPhoto(entry: MovementEntry, photo: File): MovementEntry {
        val updated = entry.copy(
            additionalPhotos = (entry.photoList().drop(1) + photo.absolutePath)
                .joinToString("\n")
        )
        dao.update(updated)
        return updated
    }

    suspend fun delete(entry: MovementEntry) {
        dao.delete(entry)
        entry.photoList().forEach { path -> runCatching { File(path).delete() } }
    }
}
