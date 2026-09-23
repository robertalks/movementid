package com.movementid.app.network

/** Strips markdown fences and any prose wrapped around the JSON object. */
internal fun extractJsonObject(text: String): String {
    val trimmed = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
}

/**
 * Problems with the account or key itself. Trying a different model won't help, so these should
 * surface immediately instead of cycling through every candidate.
 */
internal fun isAccountLevelFailure(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    // Google's phrasing, not bare status digits: "403" or "401" can appear anywhere in an error
    // body (a size, an id), and treating those as a dead key skipped retries that would have
    // succeeded.
    return listOf(
        "api key not valid", "api_key_invalid", "invalid api key", "api key expired",
        "api_key_expired", "permission_denied", "does not have permission",
        "insufficient credits", "requires more credits", "billing"
    ).any { m.contains(it) }
}

/** Google's out-of-quota wording. Quotas are per model, so another model may still have room. */
fun isQuotaExhausted(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    return listOf("resource_exhausted", "exceeded your current quota", "quota exceeded", "http 429")
        .any { m.contains(it) }
}
