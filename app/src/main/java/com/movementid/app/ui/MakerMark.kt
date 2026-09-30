package com.movementid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movementid.app.ui.theme.Display
import com.movementid.app.ui.theme.MovementColors

/**
 * The circular maker mark that leads every movement in the collection.
 *
 * Generated rather than bundled. Two reasons, in order of how much they actually matter:
 *
 * 1. Coverage. Half the makers worth identifying are defunct — A. Schild, Peseux, Felsa, FHF,
 *    Venus — and have no logo file obtainable anywhere. A scheme that only works for the dozen
 *    famous brands leaves the list visibly half-finished.
 * 2. Distribution. Shipping other companies' logo artwork inside a Play Store app is a risk
 *    that buys nothing here: identifying an Omega as an Omega is ordinary nominative use, but
 *    the artwork itself is someone else's file to license, and Play's impersonation review is
 *    automated and unsympathetic.
 *
 * A mark is a glyph plus a colour, both derived from the maker's name, so an unseen maker gets
 * a mark that looks like it belongs without anyone adding anything.
 */

/** Background and the text drawn on it. Foregrounds are hand-picked to clear 4.5:1. */
private data class MarkColors(val fill: Color, val ink: Color)

private val PALETTE = listOf(
    MarkColors(Color(0xFF5E1417), Color(0xFFF0C9AE)), // oxblood
    MarkColors(Color(0xFF15264A), Color(0xFFBFD0EC)), // navy
    MarkColors(Color(0xFF0E2E47), Color(0xFFAFD1E8)), // steel blue
    MarkColors(Color(0xFF123B2C), Color(0xFFA9D8C2)), // green
    MarkColors(Color(0xFF3A3F46), Color(0xFFD6DBE2)), // graphite
    MarkColors(Color(0xFF5A3A18), Color(0xFFE8CFAE)), // bronze
    MarkColors(Color(0xFF3F1B3A), Color(0xFFDFBDD9)), // plum
    MarkColors(Color(0xFF4A2414), Color(0xFFE5BCA3)), // rust
    MarkColors(Color(0xFF10393C), Color(0xFFA8D5D8)), // teal
    MarkColors(Color(0xFF2B3550), Color(0xFFC3CCE4))  // slate blue
)

private val UNKNOWN = MarkColors(MovementColors.SurfaceRaised, MovementColors.TextDim)

/**
 * Makers that get a fixed slot, so the ones you own most of never shuffle colour when the
 * palette or the hash changes. Everything not listed here is hashed into the same palette.
 */
private val PINNED: Map<String, Int> = mapOf(
    "omega" to 0,
    "longines" to 1,
    "seiko" to 2,
    "eta" to 3,
    "valjoux" to 4,
    "miyota" to 5,
    "sellita" to 6,
    "aschild" to 7,
    "unitas" to 8,
    "peseux" to 9
)

/**
 * Glyphs that read better than a bare initial. Ω is a Greek letter, not Omega's logo — it's the
 * character the brand is named after and it's in every system font.
 */
private val GLYPHS: Map<String, String> = mapOf(
    "omega" to "Ω",
    "sellita" to "Se",
    "seagull" to "Sg",
    "soprod" to "So",
    "schild" to "AS",
    "aschild" to "AS",
    "stowa" to "St",
    "unitas" to "U",
    "venus" to "Ve",
    "valjoux" to "V",
    "felsa" to "Fe",
    "fhf" to "FHF",
    "eta" to "ETA",
    "aspull" to "A"
)

private val IGNORED_WORDS = setOf(
    "cal", "cal.", "caliber", "calibre", "movement", "swiss", "the", "co", "sa", "ag", "ltd"
)

private fun String.key(): String = lowercase().replace(Regex("[^a-z0-9]"), "")

/**
 * A stable hash. Deliberately not [String.hashCode]: that is specified for String and would work,
 * but writing it out means a future change of key format can't silently re-colour everyone's
 * whole collection.
 */
private fun String.stableHash(): Int {
    var h = 2166136261u
    for (c in this) {
        h = h xor c.code.toUInt()
        h *= 16777619u
    }
    return (h and 0x7FFFFFFFu).toInt()
}

/** Initials for a maker with no pinned glyph: "A. Schild" -> "AS", "Miyota" -> "M". */
private fun initialsFor(maker: String): String {
    val words = maker
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() && it.lowercase() !in IGNORED_WORDS }

    if (words.isEmpty()) return "?"

    // An all-caps short name is already an abbreviation — keep it whole ("ETA", "FHF").
    val first = words.first()
    if (words.size == 1) {
        return if (first.length <= 3 && first == first.uppercase()) first
        else first.take(1).uppercase()
    }
    return words.take(2).joinToString("") { it.take(1).uppercase() }
}

private fun colorsFor(key: String): MarkColors {
    if (key.isBlank()) return UNKNOWN
    PINNED[key]?.let { return PALETTE[it] }
    return PALETTE[key.stableHash() % PALETTE.size]
}

/**
 * The maker to draw a mark for. Prefers what the model called the brand, and falls back to the
 * leading word of the caliber — "Omega 1012" is an Omega whether or not brandGuess was filled in.
 */
fun makerNameFrom(brand: String?, caliber: String?, family: String?): String? {
    brand?.trim()?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }?.let { return it }

    val source = listOfNotNull(caliber, family)
        .firstOrNull { it.isNotBlank() && !it.equals("Unknown", true) }
        ?: return null

    // "ETA 2824-2" -> "ETA"; a bare "2824-2" has no maker in it at all.
    val lead = source.trim().split(Regex("\\s+")).firstOrNull { it.any(Char::isLetter) }
    return lead?.takeIf { it.length > 1 }
}

/**
 * The mark itself. [size] drives the glyph size, so the same composable serves a 40dp list row
 * and a 72dp detail headline without a second set of dimensions to keep in step.
 */
@Composable
fun MakerMark(
    maker: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val name = maker?.trim().orEmpty()
    val key = name.key()
    val colors = colorsFor(key)
    val glyph = when {
        name.isBlank() -> "?"
        else -> GLYPHS[key] ?: initialsFor(name)
    }

    // Longer glyphs get proportionally smaller so "FHF" and "Ω" fill the circle to the same edge.
    val ratio = when (glyph.length) {
        1 -> 0.46f
        2 -> 0.36f
        else -> 0.27f
    }

    Box(
        modifier = modifier
            .size(size)
            .background(colors.fill, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .semantics {
                contentDescription = if (name.isBlank()) "Maker unknown" else name
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = colors.ink,
            style = TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.Normal,
                fontSize = (size.value * ratio).sp,
                letterSpacing = 0.sp
            )
        )
    }
}
