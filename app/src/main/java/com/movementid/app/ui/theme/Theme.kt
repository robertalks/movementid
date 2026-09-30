package com.movementid.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One palette, dark only.
 *
 * The app is used looking at a movement under a lamp, often at a bench — a light theme would be
 * a wall of white next to the photograph. Material's stock dark scheme was near enough to grey
 * to make every card look the same; this drops the ground to near-black so the photos and the
 * maker marks carry the colour, and keeps exactly one accent.
 */
object MovementColors {
    val Ground = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181D)
    val SurfaceRaised = Color(0xFF1B2027)
    val Line = Color(0xFF252B34)

    val Text = Color(0xFFE8ECF1)
    val TextDim = Color(0xFF8A93A0)
    val TextFaint = Color(0xFF5C6470)

    /** Blued steel, taken from the app icon. The only accent in the app. */
    val Blued = Color(0xFF6E9BD4)
    val Gold = Color(0xFFC9A227)

    val Ok = Color(0xFF4FA97E)
    val Warn = Color(0xFFD99A2B)
    val Bad = Color(0xFFD9603F)
}

private val Scheme = darkColorScheme(
    primary = MovementColors.Blued,
    onPrimary = Color(0xFF07090B),
    primaryContainer = Color(0xFF1E3350),
    onPrimaryContainer = MovementColors.Blued,

    secondary = MovementColors.Gold,
    onSecondary = Color(0xFF07090B),

    tertiary = MovementColors.Ok,
    onTertiary = Color(0xFF07090B),

    background = MovementColors.Ground,
    onBackground = MovementColors.Text,

    surface = MovementColors.Surface,
    onSurface = MovementColors.Text,
    surfaceVariant = MovementColors.SurfaceRaised,
    onSurfaceVariant = MovementColors.TextDim,

    error = MovementColors.Bad,
    onError = Color(0xFF07090B),

    outline = MovementColors.Line,
    outlineVariant = MovementColors.Line
)

/**
 * Three type roles, deliberately:
 *
 * - serif for caliber names and screen titles, because a caliber is a name and reads as one;
 * - monospace for anything actually engraved on the movement, so a transcription can't be
 *   mistaken for the app's own prose;
 * - sans for everything else.
 *
 * All three are system families. Bundling Instrument Serif and JetBrains Mono would match the
 * mockup more exactly at the cost of ~400 KB in the APK, and can be swapped in later without
 * touching any screen — only this file names a family.
 */
val Display = FontFamily.Serif
val Engraved = FontFamily.Monospace

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    // Used for the small grey provenance lines: "FROM MARKINGS", "WATCHGUY · VERIFIED".
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
        lineHeight = 14.sp, letterSpacing = 0.9.sp
    ),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MovementIdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
