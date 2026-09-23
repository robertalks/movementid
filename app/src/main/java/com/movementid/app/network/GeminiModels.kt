package com.movementid.app.network

/**
 * The Gemini vision models the app offers. Gemini is now the only provider — the others were
 * dropped because their free vision models were too weak for this task to be worth the
 * maintenance of chasing their constantly-changing model ids.
 *
 * Users can still type any model id they like in Settings, so a newer Gemini model works the
 * day it ships without waiting on a rebuild.
 */
object GeminiModels {

    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    const val KEY_URL = "aistudio.google.com/apikey"

    /** Offered in Settings, newest first. */
    val AVAILABLE = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash"
    )

    val DEFAULT = AVAILABLE.first()
}
