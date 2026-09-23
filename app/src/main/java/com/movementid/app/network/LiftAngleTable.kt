package com.movementid.app.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class LiftAngleEntry(
    val brand: String,
    val caliber: String,
    val angle: String
)

/** What the reference table had to say about a caliber, and whether the model agreed. */
sealed class LiftAngleCheck {
    /** The table had no entry — common for modern and clone movements. */
    data object NotListed : LiftAngleCheck()

    /** Table and model agree, or the model gave nothing and the table filled it in. */
    data class Found(val entry: LiftAngleEntry, val filledIn: Boolean) : LiftAngleCheck()

    /** Both had a value and they differ — worth showing, since the table is the better source. */
    data class Conflict(val entry: LiftAngleEntry, val modelSaid: String) : LiftAngleCheck()

    /**
     * Several makers list this caliber number and we can't tell which one this is. Reported
     * rather than guessed: caliber numbers collide constantly between brands, and applying the
     * wrong maker's lift angle is worse than admitting we don't know.
     */
    data class Ambiguous(val caliber: String, val brands: List<String>) : LiftAngleCheck()

    val entryOrNull: LiftAngleEntry?
        get() = when (this) {
            is Found -> entry
            is Conflict -> entry
            is Ambiguous -> null
            NotListed -> null
        }
}

/**
 * WatchGuy's lift angle list (watchguy.co.uk/cgi-bin/lift_angles), used to check the model's
 * answer against a real reference.
 *
 * Lift angle is exactly the kind of figure a language model guesses badly: it's a per-caliber
 * constant with no visual cue, and the common values (52°, 53°) are a tempting default that's
 * wrong often enough to matter on a timegrapher. A published table beats a model's recollection
 * every time, so where the two disagree, the table wins and the model's value is shown alongside.
 *
 * Fetched once and cached on disk rather than bundled, because the page says it's added to
 * regularly — a snapshot would quietly go stale. Refreshed weekly, and a failed refresh keeps
 * using the old cache rather than losing the feature.
 */
class LiftAngleTable(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cacheFile: File get() = File(context.filesDir, CACHE_NAME)

    /** Looks the caliber up, fetching the table first if it isn't cached yet. */
    suspend fun check(identification: MovementIdentification): LiftAngleCheck =
        withContext(Dispatchers.IO) {
            val entries = entries()
            if (entries.isEmpty()) return@withContext LiftAngleCheck.NotListed

            val match = when (val outcome = findMatch(entries, identification)) {
                is MatchOutcome.Single -> outcome.entry
                is MatchOutcome.Ambiguous ->
                    return@withContext LiftAngleCheck.Ambiguous(outcome.caliber, outcome.brands)
                MatchOutcome.None -> return@withContext LiftAngleCheck.NotListed
            }

            val modelAngle = identification.liftAngle?.takeIf { it.isUsableAngle() }
                ?: return@withContext LiftAngleCheck.Found(match, filledIn = true)

            val modelDegrees = modelAngle.degreesOrNull()
            val tableDegrees = match.angle.degreesOrNull()

            return@withContext when {
                modelDegrees == null || tableDegrees == null ->
                    LiftAngleCheck.Found(match, filledIn = false)
                // Within half a degree is agreement; the table stores e.g. "52.0" vs a model's "52°".
                kotlin.math.abs(modelDegrees - tableDegrees) < 0.5 ->
                    LiftAngleCheck.Found(match, filledIn = false)
                else -> LiftAngleCheck.Conflict(match, modelAngle)
            }
        }

    /** Cached entries, refreshed when missing or older than a week. */
    private suspend fun entries(): List<LiftAngleEntry> = mutex.withLock {
        memory?.let { cached ->
            if (!isStale()) return@withLock cached
        }

        if (!isStale()) {
            readCache()?.let { memory = it; return@withLock it }
        }

        val fetched = fetch()
        if (fetched.isNotEmpty()) {
            writeCache(fetched)
            memory = fetched
            return@withLock fetched
        }

        // Refresh failed — fall back to whatever is on disk rather than losing the feature.
        readCache()?.also { memory = it } ?: emptyList()
    }

    private fun isStale(): Boolean {
        val file = cacheFile
        if (!file.exists()) return true
        return System.currentTimeMillis() - file.lastModified() > REFRESH_INTERVAL_MS
    }

    private fun readCache(): List<LiftAngleEntry>? = runCatching {
        if (!cacheFile.exists()) return null
        val type = object : TypeToken<List<LiftAngleEntry>>() {}.type
        gson.fromJson<List<LiftAngleEntry>>(cacheFile.readText(), type)
    }.getOrNull()

    private fun writeCache(entries: List<LiftAngleEntry>) {
        runCatching { cacheFile.writeText(gson.toJson(entries)) }
    }

    private fun fetch(): List<LiftAngleEntry> = runCatching {
        val request = Request.Builder()
            .url(SOURCE_URL)
            .header("User-Agent", "MovementID/1.0 (personal watch movement app)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Lift angle table HTTP ${response.code}")
                return emptyList()
            }
            val html = response.body?.string().orEmpty()
            parse(html).also { Log.i(TAG, "Parsed ${it.size} lift angle entries") }
        }
    }.getOrElse {
        Log.w(TAG, "Lift angle fetch failed: ${it.message}")
        emptyList()
    }

    /** Pulls brand/caliber/angle triples out of the table rows. */
    internal fun parse(html: String): List<LiftAngleEntry> {
        val rows = ROW_REGEX.findAll(html).mapNotNull { row ->
            val cells = CELL_REGEX.findAll(row.groupValues[1])
                .map { it.groupValues[1].stripTags().trim() }
                .toList()
            if (cells.size < 3) return@mapNotNull null
            val angle = cells[2]
            if (angle.degreesOrNull() == null) return@mapNotNull null
            LiftAngleEntry(brand = cells[0], caliber = cells[1], angle = angle)
        }.toList()

        return rows.filter { it.brand.isNotBlank() && it.caliber.isNotBlank() }
    }

    private sealed class MatchOutcome {
        data object None : MatchOutcome()
        data class Single(val entry: LiftAngleEntry) : MatchOutcome()
        data class Ambiguous(val caliber: String, val brands: List<String>) : MatchOutcome()
    }

    /**
     * Requires the brand to agree. Caliber numbers are reused heavily across makers — Omega,
     * A. Schild and others all have a "1012" — so a caliber-only match is not evidence. When the
     * brand is unknown, a caliber is only accepted if exactly one maker lists it.
     */
    private fun findMatch(
        entries: List<LiftAngleEntry>,
        identification: MovementIdentification
    ): MatchOutcome {
        val candidates = listOfNotNull(
            identification.caliber?.takeIf { it.isUsableAngle() },
            identification.movementFamily?.takeIf { it.isUsableAngle() }
        ).flatMap { it.caliberTokens() }.distinct()

        if (candidates.isEmpty()) return MatchOutcome.None

        val brand = identification.brandGuess?.takeIf { it.isUsableAngle() }

        for (candidate in candidates) {
            val matches = entries.filter { it.caliber.normalizeKey() == candidate }
            if (matches.isEmpty()) continue

            if (brand != null) {
                val byBrand = matches.filter { brandMatches(it.brand, brand) }
                // Brand known but no row for it: this caliber belongs to someone else.
                if (byBrand.isEmpty()) continue
                return MatchOutcome.Single(byBrand.first())
            }

            val distinctBrands = matches.map { it.brand }.distinct()
            return if (distinctBrands.size == 1) {
                MatchOutcome.Single(matches.first())
            } else {
                MatchOutcome.Ambiguous(matches.first().caliber, distinctBrands)
            }
        }
        return MatchOutcome.None
    }

    /**
     * Table brands carry suffixes the model won't produce ("A. Schild [AS]", "Font [FHF]"), so
     * compare against each bracketed or spaced token as well as the whole string. Short tokens
     * are matched only exactly, to stop "AS" matching half the table.
     */
    internal fun brandMatches(tableBrand: String, modelBrand: String): Boolean {
        val model = modelBrand.normalizeKey()
        if (model.isBlank()) return false

        val whole = tableBrand.normalizeKey()
        if (whole == model) return true

        val tokens = tableBrand.split(Regex("[^A-Za-z0-9]+"))
            .map { it.normalizeKey() }
            .filter { it.isNotBlank() }

        if (tokens.any { it == model }) return true

        // Substring matching only for names long enough not to collide by accident.
        if (model.length >= 4 && whole.contains(model)) return true
        if (tokens.any { it.length >= 4 && model.contains(it) }) return true

        return false
    }

    companion object {
        const val SOURCE_URL = "https://watchguy.co.uk/cgi-bin/lift_angles"
        private const val TAG = "MovementID/LiftAngle"
        private const val CACHE_NAME = "lift_angles.json"
        private val REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

        private val ROW_REGEX = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        private val CELL_REGEX = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL)

        private val mutex = Mutex()
        @Volatile private var memory: List<LiftAngleEntry>? = null
    }
}

private fun String.stripTags(): String =
    replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")

private fun String.isUsableAngle(): Boolean =
    isNotBlank() && !equals("Unknown", true) && !equals("N/A", true)

/** Pulls a number out of "52", "52.0", "52°" or "52 degrees". */
internal fun String.degreesOrNull(): Double? =
    Regex("\\d+(?:[.,]\\d+)?").find(this)?.value?.replace(',', '.')?.toDoubleOrNull()

private fun String.normalizeKey(): String =
    lowercase().replace(Regex("[^a-z0-9]"), "")

/**
 * A model may answer "NH35A", "Seiko NH35A" or "ETA 2824-2". Produce the plausible caliber keys
 * so any of those forms can match a table row.
 */
private fun String.caliberTokens(): List<String> {
    val cleaned = trim()
    val whole = cleaned.normalizeKey()
    val words = cleaned.split(Regex("[\\s/]+")).filter { it.isNotBlank() }
    val tail = words.lastOrNull()?.normalizeKey()
    val lastTwo = if (words.size >= 2) words.takeLast(2).joinToString("").normalizeKey() else null
    return listOfNotNull(whole, lastTwo, tail).filter { it.isNotBlank() }.distinct()
}
