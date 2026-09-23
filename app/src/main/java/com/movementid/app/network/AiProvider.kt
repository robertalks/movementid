package com.movementid.app.network

/**
 * The AI providers the app can use. Each keeps its own key, model selection and custom model
 * ids, so both can be set up and switched between to compare results on the same movement.
 */
enum class AiProvider(
    val displayName: String,
    val keyUrl: String,
    val blurb: String,
    val defaultModels: List<String>
) {
    GEMINI(
        displayName = "Google Gemini",
        keyUrl = "aistudio.google.com/apikey",
        blurb = "Free tier, no card. Search runs through Gemini's built-in grounding, which " +
            "spends an extra request from the same free quota.",
        defaultModels = listOf(
            "gemini-3-flash-preview",
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash"
        )
    ),

    OPENAI(
        displayName = "OpenAI",
        keyUrl = "platform.openai.com/api-keys",
        blurb = "Paid — needs its own billing, separate from any ChatGPT subscription. Its web " +
            "search is a different mechanism to Gemini's and returns proper source citations, " +
            "so it's worth comparing on both accuracy and searching.",
        defaultModels = listOf(
            "gpt-5.5",
            "gpt-5.4"
        )
    );

    val isGemini: Boolean get() = this == GEMINI
}
