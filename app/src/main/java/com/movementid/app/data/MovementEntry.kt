package com.movementid.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "movement_entries")
data class MovementEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val photoPath: String,
    /**
     * Further photos of the same movement, newline-separated. A dial-side shot, or the same
     * caliber from another watch with different branding, often settles an identification that
     * a single case-back photo can't.
     */
    val additionalPhotos: String? = null,
    /** Markings the model read off the movement, one per line — the evidence behind the answer. */
    val markings: String? = null,
    val brandGuess: String? = null,
    val movementFamily: String? = null,
    val movementType: String? = null,
    val caliber: String? = null,
    val sizeMm: String? = null,
    val beatRateVph: String? = null,
    val powerReserveHours: String? = null,
    val jewelCount: String? = null,
    /** Balance lift angle in degrees — needed to set a timegrapher up correctly. */
    val liftAngle: String? = null,
    /** Where that figure came from: the WatchGuy table, or the model's own answer. */
    val liftAngleSource: String? = null,
    /** When the caliber was produced, e.g. "1996–2010" or "1970s". */
    val productionYears: String? = null,
    val confidence: String? = null,
    val aiNotes: String? = null,
    val userNotes: String? = null,
    /** Exact Gemini model id that answered, so results stay comparable across models. */
    val modelUsed: String? = null,
    /** Round-trip time in ms, useful when comparing models against each other. */
    val latencyMs: Long? = null,
    /** True when the Google Search grounded pass reconciled the answer against real sources. */
    val webVerified: Boolean = false,
    /** Newline-separated "Title — url" list from the verification pass. */
    val sources: String? = null,
    /** User's own title for this entry; falls back to the identified caliber. */
    val title: String? = null
)

/** Primary photo first, then any extras. */
fun MovementEntry.photoList(): List<String> =
    listOf(photoPath) + additionalPhotos.orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
