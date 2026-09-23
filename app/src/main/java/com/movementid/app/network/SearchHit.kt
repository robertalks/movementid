package com.movementid.app.network

/** A source Gemini consulted during the grounded verification pass. */
data class SearchHit(
    val title: String,
    val snippet: String = "",
    val link: String
)
