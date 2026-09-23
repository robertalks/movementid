package com.movementid.app.network

import java.net.URLEncoder

/**
 * Builds a plain Google search URL for a caliber, opened in the user's browser.
 *
 * This exists because the API routes both dead-ended: Google's Custom Search JSON API is closed
 * to new projects ("This project does not have the access to Custom Search JSON API"), and the
 * Gemini-grounded pass spends the one quota that actually runs out here. Opening the browser
 * needs no key, no project, no quota, and never breaks — and since every field is editable, the
 * user reading two real pages beats a model summarising them second-hand.
 */
object CaliberSearch {

    /** The search terms for a movement, or null when nothing specific enough was identified. */
    fun queryFor(identification: MovementIdentification): String? {
        fun String?.usable() = !isNullOrBlank() &&
            !equals("Unknown", true) && !equals("N/A", true) && !equals("unidentified", true)

        val caliber = identification.caliber?.takeIf { it.usable() }
        val family = identification.movementFamily?.takeIf { it.usable() }
        val brand = identification.brandGuess?.takeIf { it.usable() }

        val core = caliber ?: family ?: return null
        // Skip the brand when the caliber string already carries it, e.g. "Seiko NH35".
        val prefix = brand?.takeIf { !core.contains(it, ignoreCase = true) }?.plus(" ") ?: ""
        return "$prefix$core watch movement specifications"
    }

    /** Google results URL for [queryFor], or null when there's nothing to search. */
    fun urlFor(identification: MovementIdentification): String? {
        val query = queryFor(identification) ?: return null
        return "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
    }

    /**
     * Ranfft DB, the community successor to Roland Ranfft's archive — the best free source for
     * per-caliber specs and reference photos.
     *
     * Routed through a Google site-search rather than Ranfft's own search box: their caliber
     * URLs are slugged with an internal id (/caliber/49-AHO-126) that can't be derived from a
     * caliber name, and their search is a JS form with a CSRF token, so any query URL built
     * here would be guesswork that breaks silently. A site-search lands on the right page and
     * keeps working regardless of what they change.
     */
    fun ranfftUrlFor(identification: MovementIdentification): String? {
        fun String?.usable() = !isNullOrBlank() &&
            !equals("Unknown", true) && !equals("N/A", true) && !equals("unidentified", true)

        val caliber = identification.caliber?.takeIf { it.usable() }
            ?: identification.movementFamily?.takeIf { it.usable() }
            ?: return null
        val brand = identification.brandGuess?.takeIf { it.usable() }
        val terms = if (brand != null && !caliber.contains(brand, ignoreCase = true)) {
            "$brand $caliber"
        } else {
            caliber
        }
        return "https://www.google.com/search?q=" +
            URLEncoder.encode("site:ranfft.org $terms", "UTF-8")
    }

    /** The archive's own caliber index, for browsing when nothing specific was identified. */
    const val RANFFT_INDEX = "https://ranfft.org/calibers"

    /** Google Images, which is often faster for confirming a movement visually. */
    fun imagesUrlFor(identification: MovementIdentification): String? {
        val query = queryFor(identification) ?: return null
        return "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode(query, "UTF-8")
    }
}
