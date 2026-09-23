package com.movementid.app.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

// --- Gemini wire format (camelCase) ---

private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig? = null,
    /** Present only for the grounded pass; Gson omits it when null. */
    val tools: List<GeminiTool>? = null
)

/**
 * The search tool has been spelled differently across Gemini generations (`google_search` on
 * 2.x+, `google_search_retrieval` on 1.5). Only one field is ever set, and Gson drops the nulls,
 * so each variant serialises to exactly the shape that generation expects.
 */
private data class GeminiTool(
    val google_search: Map<String, String>? = null,
    val google_search_retrieval: Map<String, String>? = null
)

private enum class SearchToolVariant(val build: () -> GeminiTool) {
    MODERN({ GeminiTool(google_search = emptyMap()) }),
    LEGACY({ GeminiTool(google_search_retrieval = emptyMap()) })
}

private data class GeminiContent(val parts: List<GeminiPart>)

private data class GeminiPart(
    val text: String? = null,
    val inlineData: InlineData? = null
)

private data class InlineData(val mimeType: String, val data: String)

private data class GenerationConfig(
    val responseMimeType: String? = "application/json",
    val temperature: Double = 0.2
)

private data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

private data class GeminiCandidate(
    val content: GeminiResponseContent?,
    val groundingMetadata: GroundingMetadata? = null
)

private data class GeminiResponseContent(val parts: List<GeminiResponsePart>?)

private data class GeminiResponsePart(val text: String?)

private data class GroundingMetadata(
    val groundingChunks: List<GroundingChunk>? = null,
    val webSearchQueries: List<String>? = null
)

private data class GroundingChunk(val web: GroundingWeb? = null)

private data class GroundingWeb(val uri: String? = null, val title: String? = null)

private data class GeminiError(val message: String?)

/**
 * Google's Gemini API — the app's only provider (free key at aistudio.google.com/apikey).
 *
 * Two passes:
 *  1. [identify] sends the photo and asks for a structured JSON identification.
 *  2. [verifyWithGoogleSearch] re-asks with Gemini's built-in `google_search` tool enabled, so
 *     the model actually looks the caliber up on Google and corrects its own specs against what
 *     it finds. This replaces the old Custom Search plumbing: no second API key, no 100/day cap,
 *     and the model sees full pages rather than three-line snippets — which is why the previous
 *     approach barely moved the needle.
 *
 * Note: Gemini rejects JSON response mode when a tool is enabled, so the grounded pass asks for
 * JSON in the prompt and the object is extracted from the reply text instead.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = GeminiModels.DEFAULT
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Pass 1: identify from one or more photos of the same movement.
     *
     * [feedback] is the user telling the model what it got wrong on a previous attempt — the
     * single most useful input available, since the user can see things the photo can't show
     * (the case, the dial side, where the watch came from).
     */
    suspend fun identify(
        imageFiles: List<File>,
        feedback: String? = null,
        maxEdge: Int = ImageEncoder.STANDARD_EDGE_PX
    ): Result<IdentificationResult> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val encoded = imageFiles.map { ImageEncoder.encodeDownscaledBase64(it, maxEdge) }

        var lastError: String? = null

        // Two attempts: free-tier overload is usually brief.
        repeat(2) { attempt ->
            val parts = buildList {
                add(GeminiPart(text = buildPrompt(feedback, imageFiles.size)))
                encoded.forEach { add(GeminiPart(inlineData = InlineData("image/jpeg", it))) }
            }
            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = parts)),
                generationConfig = GenerationConfig()
            )

            val result = execute(request)
            result.getOrNull()?.let { (identification, _) ->
                return@withContext Result.success(
                    IdentificationResult(
                        identification = identification,
                        modelUsed = model,
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                )
            }
            lastError = result.exceptionOrNull()?.message
            if (isAccountLevelFailure(lastError)) {
                return@withContext Result.failure(Exception(lastError))
            }
            // Daily quota won't recover in two seconds — let the caller fall back to another
            // model instead of spending time retrying one that's exhausted.
            if (isQuotaExhausted(lastError)) {
                return@withContext Result.failure(Exception(lastError))
            }
            if (attempt == 0 && isTransient(lastError)) delay(2000)
        }

        Result.failure(Exception(lastError ?: "Gemini request failed"))
    }

    /**
     * Pass 2: re-check the identification with Google Search grounding enabled. Returns the
     * corrected identification plus the sources Gemini actually consulted.
     */
    suspend fun verifyWithGoogleSearch(
        identification: MovementIdentification
    ): Result<Pair<MovementIdentification, List<SearchHit>>> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (variant in SearchToolVariant.entries) {
            val request = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(GeminiPart(text = buildGroundedPrompt(identification)))
                    )
                ),
                // JSON response mode is not allowed alongside tools, so ask for JSON in the prompt.
                generationConfig = GenerationConfig(responseMimeType = null),
                tools = listOf(variant.build())
            )

            val attempt = execute(request, grounded = true)
            attempt.getOrNull()?.let { (refined, sources) ->
                Log.i(TAG, "Grounded pass via ${variant.name} on $model returned ${sources.size} sources")
                return@withContext Result.success(refined to sources)
            }
            lastError = attempt.exceptionOrNull()
            Log.w(TAG, "Grounded pass via ${variant.name} on $model failed: ${lastError?.message}")
        }

        Result.failure(lastError ?: Exception("Search tool unavailable on $model"))
    }

    /** Shared call + parse. Returns the identification and any grounding sources reported. */
    private fun execute(
        request: GeminiRequest,
        grounded: Boolean = false
    ): Result<Pair<MovementIdentification, List<SearchHit>>> {
        return try {
            val url = "${GeminiModels.ENDPOINT}/$model:generateContent?key=$apiKey"
            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder().url(url).post(body).build()

            client.newCall(httpRequest).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val parsed = runCatching {
                    gson.fromJson(responseText, GeminiResponse::class.java)
                }.getOrNull()

                parsed?.error?.let { return Result.failure(Exception("[$model] ${it.message}")) }

                if (!response.isSuccessful) {
                    if (grounded) Log.w(TAG, "Grounded HTTP ${response.code}: ${responseText.take(500)}")
                    return Result.failure(
                        Exception("[$model] HTTP ${response.code}: ${responseText.take(300)}")
                    )
                }

                val candidate = parsed?.candidates?.firstOrNull()
                val text = candidate?.content?.parts?.firstOrNull { !it.text.isNullOrBlank() }?.text
                    ?: return Result.failure(Exception("[$model] Empty response"))

                val identification = gson.fromJson(
                    extractJsonObject(text),
                    MovementIdentification::class.java
                ) ?: return Result.failure(Exception("[$model] Could not parse JSON"))

                if (grounded) {
                    val queries = candidate.groundingMetadata?.webSearchQueries.orEmpty()
                    Log.i(
                        TAG,
                        "Grounded reply on $model: queries=$queries " +
                            "chunks=${candidate.groundingMetadata?.groundingChunks?.size ?: 0}"
                    )
                }

                val sources = candidate.groundingMetadata?.groundingChunks.orEmpty()
                    .mapNotNull { chunk ->
                        val web = chunk.web ?: return@mapNotNull null
                        SearchHit(
                            title = web.title ?: web.uri ?: return@mapNotNull null,
                            snippet = "",
                            link = web.uri.orEmpty()
                        )
                    }

                Result.success(identification to sources)
            }
        } catch (e: Exception) {
            Result.failure(Exception("[$model] ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private companion object {
        const val TAG = "MovementID/Gemini"
    }

    private fun isTransient(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return listOf("503", "429", "overloaded", "high demand", "unavailable", "timeout", "timed out")
            .any { m.contains(it) }
    }
}

/** Prompt for the grounded pass — tells the model to actually search rather than recall. */
internal fun buildGroundedPrompt(original: MovementIdentification): String = """
    You identified a watch movement from a photo as the following:

    markings read on the movement: ${original.visibleMarkings?.joinToString(", ") ?: "none"}

    caliber: ${original.caliber}
    movementFamily: ${original.movementFamily}
    brandGuess: ${original.brandGuess}
    movementType: ${original.movementType}
    sizeMm: ${original.sizeMm}
    beatRateVph: ${original.beatRateVph}
    powerReserveHours: ${original.powerReserveHours}
    jewelCount: ${original.jewelCount}
    liftAngle: ${original.liftAngle}
    productionYears: ${original.productionYears}
    confidence: ${original.confidence}

    Search Google for this caliber's published specifications and correct your answer.

    Rules:
    - Use the searched specifications for sizeMm, beatRateVph, powerReserveHours, jewelCount,
      liftAngle and productionYears
      rather than your own recollection. These are published figures once the caliber is known.
    - If what you find describes a movement that doesn't match, say so in notes and lower the
      confidence.
    - If you find nothing reliable, keep the original values and do not raise the confidence.
    - Only use High confidence when sources clearly corroborate this exact caliber.

    Respond with ONLY a JSON object, no markdown fences and no text around it, with exactly
    these keys: brandGuess, movementFamily, movementType, caliber, sizeMm, beatRateVph,
    powerReserveHours, jewelCount, liftAngle, productionYears, confidence, notes.

    In notes, state in 2-4 sentences what the search confirmed or contradicted.
""".trimIndent()
