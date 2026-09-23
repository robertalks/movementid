package com.movementid.app.network

/** Gemini models offered in Settings, newest first. */
val GEMINI_MODELS = listOf(
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash"
)

const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

/** The structured fields the model is asked to fill in. */
data class MovementIdentification(
    /**
     * Every marking the model could read, transcribed before it identifies anything. Listed
     * first in the schema on purpose: models generate JSON fields in order, so this forces it
     * to commit to what is actually written on the movement before naming a caliber.
     */
    val visibleMarkings: List<String>? = null,
    val brandGuess: String? = null,
    val movementFamily: String? = null,
    val movementType: String? = null,
    val caliber: String? = null,
    val sizeMm: String? = null,
    val beatRateVph: String? = null,
    val powerReserveHours: String? = null,
    val jewelCount: String? = null,
    val liftAngle: String? = null,
    val productionYears: String? = null,
    val confidence: String? = null,
    val notes: String? = null,
    /**
     * The model's own suggested web query for confirming this movement. Having the model write
     * the query beats building one from the caliber string: it knows what it actually saw, and
     * can fall back to describing distinctive features when it can't name a caliber at all.
     */
    val searchQuery: String? = null
)

/** True when the model named the movement specifically enough to be worth looking up. */
fun MovementIdentification.isSearchable(): Boolean {
    fun String?.usable() = !isNullOrBlank() &&
        !equals("Unknown", true) && !equals("N/A", true) && !equals("unidentified", true)
    return caliber.usable() || movementFamily.usable()
}

/** What the web-verification step actually did — surfaced so a silent no-op is impossible. */
sealed class SearchStatus {
    data object Disabled : SearchStatus()
    data object NotConfigured : SearchStatus()
    data object NoQuery : SearchStatus()
    data object NoResults : SearchStatus()
    data class Failed(val reason: String) : SearchStatus()
    data class Verified(val hitCount: Int, val changed: Boolean) : SearchStatus()

    /**
     * The reason behind a Failed status, for a small second line. Kept separate from [describe]
     * so the main line stays clean but the cause is still reachable without opening Logcat.
     */
    fun detail(): String? = (this as? Failed)?.reason

    /** Short line for the UI. */
    fun describe(): String = when (this) {
        Disabled -> "Web check off"
        NotConfigured -> "Search not set up"
        NoQuery -> "No search query produced"
        NoResults -> "No web results found"
        is Failed -> "Web check unavailable"
        is Verified -> if (changed) {
            "Corrected against $hitCount sources"
        } else {
            "Confirmed against $hitCount sources"
        }
    }
}

/** A finished identification plus everything the UI needs to judge it. */
data class IdentificationResult(
    val identification: MovementIdentification,
    val modelUsed: String,
    val latencyMs: Long,
    val searchStatus: SearchStatus = SearchStatus.Disabled,
    val sources: List<SearchHit> = emptyList(),
    /** What the WatchGuy lift angle table said, if anything. */
    val liftAngleCheck: LiftAngleCheck = LiftAngleCheck.NotListed
)

internal val MOVEMENT_IDENTIFICATION_PROMPT = """
    You are a professional watchmaker. Look closely at the photo (or photos) of a watch movement
    (the mechanical or quartz engine of a wristwatch, usually seen from the back with the
    case back removed, occasionally from the dial side). Identify the movement as specifically
    as you can from visual cues: bridge shapes and engraving, regulator style, jewel count and
    placement, screw patterns, printed text or logos, rotor shape (if automatic), and overall
    layout.

    If you are given more than one image, they are all of the SAME movement — typically the case
    back and the dial side, or the same caliber taken from a different watch where the branding
    on the movement differs. Use them together: a caliber number or feature visible in one image
    settles what is ambiguous in another, and differing branding on the same architecture points
    to an ebauche supplied to several brands. Say in notes which image told you what.

    Respond with ONLY a JSON object (no markdown fences, no commentary before or after) with
    exactly these fields. Use "Unknown" for anything you genuinely cannot determine — do not
    invent a caliber you cannot support from what you can actually see:

    {
      "visibleMarkings": [string],
      "brandGuess": string,
      "movementFamily": string,
      "movementType": string,
      "caliber": string,
      "sizeMm": string,
      "beatRateVph": string,
      "powerReserveHours": string,
      "jewelCount": string,
      "liftAngle": string,
      "productionYears": string,
      "confidence": string,
      "notes": string,
      "searchQuery": string
    }

    START WITH THE MARKINGS. Before identifying anything, read and transcribe every piece of
    text or number engraved, stamped or printed on the movement: caliber numbers, brand names
    and logos, jewel counts ("25 JEWELS"), "SWISS", "UNADJUSTED", adjustment markings, serial
    numbers, import codes. List each separately in visibleMarkings, exactly as written. If a
    marking is partly legible, transcribe what you can and mark unclear characters with "?".
    If nothing is legible, return an empty list — do not invent markings.

    Then identify the movement FROM those markings wherever possible. A legible caliber number
    outranks any visual impression: if you read "2824-2", the answer is not a 7750 because the
    layout looks similar. If the markings and your visual impression disagree, trust the
    markings and say so in notes.

    Field meanings:
    - visibleMarkings: every legible marking, one per item, exactly as written
    - brandGuess: likely manufacturer, e.g. "ETA", "Seiko", "Miyota", "Sellita", "unbranded clone"
    - movementFamily: closest known base caliber, e.g. "ETA 2824-2", "Seiko NH35A", "Miyota 8215"
    - movementType: "Automatic", "Manual wind", or "Quartz"
    - caliber: the specific caliber number if visible or confidently inferable
    - sizeMm: diameter in mm, and ligne size if known, e.g. "25.6mm (11 1/2 lignes)"
    - beatRateVph: beats per hour and Hz, e.g. "28800 vph (4Hz)"
    - powerReserveHours: typical power reserve for this caliber, e.g. "38 hours"
    - jewelCount: typical jewel count for this caliber, e.g. "21 jewels"
    - liftAngle: the balance lift angle in degrees for this caliber, e.g. "52°". This is the
      figure a timegrapher needs to read the rate correctly, and it varies by caliber — 52° and
      53° are common but far from universal, so give the published value for this movement or
      "Unknown" rather than assuming a typical one.
    - productionYears: when this caliber was made — a range like "1996-2010", "since 2011", or
      a decade like "1970s" if you only know it approximately. This dates the movement, which is
      often the most useful thing to know about it after the caliber itself.
    - confidence: "High", "Medium", or "Low" — be honest; many calibers look nearly identical
      and generic clones are common, so Low is often the correct answer
    - notes: 2-4 sentences on your reasoning, the specific features you spotted, any text you
      could read, and what would need checking to confirm
    - searchQuery: a web search query that would confirm this identification and its
      specifications. If you named a caliber, search for that caliber's specs, e.g.
      "Seiko NH35A movement specifications beat rate power reserve jewels". If you could NOT
      name a caliber, instead describe the distinctive visible features so the search might
      identify it, e.g. "automatic watch movement gold rotor signed 'Incabloc' three bridges
      identify caliber". Never leave this empty.

    Return valid JSON only.
""".trimIndent()


/**
 * The identification prompt, plus the user's correction when they've told the model it was
 * wrong. The correction is placed last and stated forcefully: the user has physical access to
 * the watch, so their claim outranks anything the model thinks it sees.
 */
internal fun buildPrompt(feedback: String?, imageCount: Int = 1): String {
    if (feedback.isNullOrBlank()) return MOVEMENT_IDENTIFICATION_PROMPT

    return MOVEMENT_IDENTIFICATION_PROMPT + """

        IMPORTANT — the person who owns this watch has reviewed your previous answer and says:

        "${feedback.trim()}"

        They are holding the movement and can see things these ${if (imageCount > 1) "photos" else "photos"}
        cannot show. Treat what they say as correct and work out what follows from it. Do not
        repeat the identification they have just rejected. If their correction rules out your
        earlier answer, say what it points to instead, and if that leaves you genuinely unsure,
        say so and lower the confidence rather than inventing a replacement.
    """.trimIndent()
}


/**
 * Returns the marking the answer contradicts, or null when they're consistent.
 *
 * Targets the specific failure this approach exists for: a caliber number legibly written on the
 * movement, and the model reasoning past it to a different answer. Written to avoid false alarms
 * — jewel counts, "SWISS", adjustment markings, serial numbers and partly-legible text are not
 * evidence of a caliber and are ignored. Tested against 14 cases including "cal. 2824" vs
 * "ETA 2824-2", a serial number alongside the real caliber, and "25 JEWELS" on its own.
 */
fun MovementIdentification.contradictsMarkings(): String? {
    val markings = visibleMarkings.orEmpty()

    val candidates = markings.filter { it.isCaliberLike() }.map { it.caliberCore() }
    if (candidates.isEmpty()) return null

    val joined = listOfNotNull(caliber, movementFamily).joinToString(" ")
    val answer = joined.normalizedKey()
    if (answer.isBlank()) return null

    val answerTokens = joined.split(Regex("[\\s/]+"))
        .filter { token -> token.any(Char::isDigit) }
        .map { it.caliberCore() }
        .filter { it.isNotBlank() }

    val consistent = candidates.any { candidate ->
        answer.contains(candidate) ||
            answerTokens.any { token -> token.contains(candidate) || candidate.contains(token) }
    }
    return if (consistent) null else markings.first { it.isCaliberLike() }
}

private val NOT_CALIBER = listOf(
    "jewel", "rubis", "rubies", "adjust", "swiss", "made",
    "positions", "temp", "incabloc", "kif", "shock"
)

private val CALIBER_PREFIX = Regex("^(calibre|caliber|kaliber|cal|kal)")

private fun String.normalizedKey(): String = lowercase().replace(Regex("[^a-z0-9]"), "")

/** "Cal. 2824" and "2824" are the same evidence; strip the prefix before comparing. */
private fun String.caliberCore(): String = CALIBER_PREFIX.replace(normalizedKey(), "")

private fun String.isCaliberLike(): Boolean {
    val text = trim()
    if ('?' in text || text.none(Char::isDigit)) return false
    if (NOT_CALIBER.any { text.contains(it, ignoreCase = true) }) return false
    val core = text.caliberCore()
    if (core.length !in 3..8) return false
    // Long pure-digit strings are serial numbers, not calibers.
    if (core.all(Char::isDigit) && core.length >= 6) return false
    return true
}
