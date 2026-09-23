package com.movementid.app.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

// --- Chat Completions (identification) ---

private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_completion_tokens: Int = 1500,
    val response_format: ResponseFormat? = ResponseFormat()
)

private data class ChatMessage(val role: String, val content: List<ContentPart>)

private data class ContentPart(
    val type: String,
    val text: String? = null,
    val image_url: ImageUrl? = null
)

private data class ImageUrl(val url: String)

private data class ResponseFormat(val type: String = "json_object")

private data class ChatResponse(
    val choices: List<ChatChoice>? = null,
    val error: OpenAiError? = null
)

private data class ChatChoice(val message: ChatResponseMessage?)

private data class ChatResponseMessage(val content: String?)

// --- Responses API (web search) ---

private data class ResponsesRequest(
    val model: String,
    val input: String,
    val tools: List<ResponsesTool> = listOf(ResponsesTool()),
    val max_output_tokens: Int = 2000
)

private data class ResponsesTool(val type: String = "web_search")

private data class ResponsesResponse(
    val output: List<ResponsesOutputItem>? = null,
    val error: OpenAiError? = null
)

private data class ResponsesOutputItem(
    val type: String? = null,
    val content: List<ResponsesContent>? = null
)

private data class ResponsesContent(
    val type: String? = null,
    val text: String? = null,
    val annotations: List<ResponsesAnnotation>? = null
)

private data class ResponsesAnnotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null
)

private data class OpenAiError(val message: String? = null, val code: String? = null)

/**
 * OpenAI support, for comparing against Gemini on both identification and searching.
 *
 * Two different endpoints, because OpenAI splits them:
 *  - identification uses Chat Completions, which takes the image as a data URI;
 *  - [verifyWithWebSearch] uses the Responses API, the only place the `web_search` tool lives.
 *    Its replies carry `url_citation` annotations, so the sources are real links rather than a
 *    model's recollection.
 *
 * Note this is a paid API. A ChatGPT subscription does not cover it — billing is separate.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun identify(
        imageFiles: List<File>,
        feedback: String? = null,
        maxEdge: Int = ImageEncoder.STANDARD_EDGE_PX
    ): Result<IdentificationResult> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val encoded = imageFiles.map { ImageEncoder.encodeDownscaledBase64(it, maxEdge) }

        try {
            val parts = buildList {
                add(ContentPart(type = "text", text = buildPrompt(feedback, imageFiles.size)))
                encoded.forEach {
                    add(ContentPart(type = "image_url", image_url = ImageUrl("data:image/jpeg;base64,$it")))
                }
            }
            val request = ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = parts))
            )

            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val parsed = runCatching { gson.fromJson(responseText, ChatResponse::class.java) }.getOrNull()

                parsed?.error?.let {
                    return@withContext Result.failure(Exception("[$model] ${it.message}"))
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "Identify HTTP ${response.code}: ${responseText.take(400)}")
                    return@withContext Result.failure(
                        Exception("[$model] HTTP ${response.code}: ${responseText.take(300)}")
                    )
                }

                val text = parsed?.choices?.firstOrNull()?.message?.content
                    ?: return@withContext Result.failure(Exception("[$model] Empty response"))

                val identification = gson.fromJson(
                    extractJsonObject(text),
                    MovementIdentification::class.java
                ) ?: return@withContext Result.failure(Exception("[$model] Could not parse JSON"))

                Result.success(
                    IdentificationResult(
                        identification = identification,
                        modelUsed = model,
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("[$model] ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    /**
     * Re-checks the identification with the web_search tool. Returns the corrected answer plus
     * the cited sources, which the UI shows as tappable links.
     */
    suspend fun verifyWithWebSearch(
        identification: MovementIdentification
    ): Result<Pair<MovementIdentification, List<SearchHit>>> = withContext(Dispatchers.IO) {
        try {
            val request = ResponsesRequest(
                model = model,
                input = buildGroundedPrompt(identification)
            )

            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val parsed = runCatching {
                    gson.fromJson(responseText, ResponsesResponse::class.java)
                }.getOrNull()

                parsed?.error?.let {
                    return@withContext Result.failure(Exception(it.message ?: "OpenAI error"))
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "Web search HTTP ${response.code}: ${responseText.take(500)}")
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code}: ${responseText.take(200)}")
                    )
                }

                val searched = parsed?.output.orEmpty().count { it.type == "web_search_call" }
                Log.i(TAG, "$model ran $searched web searches")

                // The assistant message is the output item carrying output_text content.
                val textContent = parsed?.output.orEmpty()
                    .flatMap { it.content.orEmpty() }
                    .firstOrNull { !it.text.isNullOrBlank() }
                    ?: return@withContext Result.failure(Exception("Empty search response"))

                val refined = gson.fromJson(
                    extractJsonObject(textContent.text.orEmpty()),
                    MovementIdentification::class.java
                ) ?: return@withContext Result.failure(Exception("Could not parse search response"))

                val sources = parsed?.output.orEmpty()
                    .flatMap { it.content.orEmpty() }
                    .flatMap { it.annotations.orEmpty() }
                    .filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
                    .distinctBy { it.url }
                    .map { SearchHit(title = it.title ?: it.url!!, link = it.url!!) }

                Result.success(refined to sources)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "MovementID/OpenAI"
    }
}
