package com.movementid.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A balance wheel, swinging.
 *
 * Replaces a rotating logo that didn't read as moving at all: a round image rotated a few degrees
 * looks the same at every angle. The balance is the part of a watch that visibly oscillates, and
 * it swings through a wide arc — so this swings ±150°, easing at each end the way a real balance
 * slows before reversing, while the hairspring coils and uncoils with it.
 */
@Composable
fun TickingBalance(size: Dp = 96.dp) {
    // Driven from the frame clock rather than rememberInfiniteTransition. Compose's transition
    // APIs honour Android's animator duration scale, and when that's off — Developer Options,
    // Battery Saver, or "Remove animations" in accessibility — they freeze at their start value.
    // That's exactly what happened: the wheel sat still while the delay()-driven dots and
    // seconds counter kept ticking. withFrameNanos isn't scaled, so this always runs.
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsed = (now - start) / 1_000_000_000f }
        }
    }

    // A sine wave is exactly how a balance moves: fastest through the middle, slowing to a stop
    // at each end before reversing. ±150° of a real balance's ~270° amplitude stays legible.
    val swing = AMPLITUDE * sin(2.0 * PI * elapsed / PERIOD_SECONDS).toFloat()

    val colour = MaterialTheme.colorScheme.onSurface
    val faint = colour.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(size)) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val rimRadius = this.size.minDimension * 0.42f
        val rimWidth = this.size.minDimension * 0.055f

        // Hairspring: coils tighter as the balance swings one way, looser the other — the
        // breathing motion that makes it read as a live oscillator rather than a spinning disc.
        val coil = swing / AMPLITUDE                    // -1 .. 1
        val spring = Path()
        val turns = 3.2f
        val steps = 140
        val inner = rimRadius * 0.10f
        val outer = rimRadius * (0.62f + 0.06f * coil)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val angle = Math.toRadians((t * turns * 360f + swing * 0.6f).toDouble())
            val r = inner + (outer - inner) * t
            val x = centre.x + (r * cos(angle)).toFloat()
            val y = centre.y + (r * sin(angle)).toFloat()
            if (i == 0) spring.moveTo(x, y) else spring.lineTo(x, y)
        }
        drawPath(spring, faint, style = Stroke(width = this.size.minDimension * 0.014f))

        // Everything that belongs to the wheel itself rotates together.
        rotate(degrees = swing, pivot = centre) {
            drawCircle(colour, radius = rimRadius, center = centre, style = Stroke(width = rimWidth))

            // Four spokes.
            for (k in 0 until 4) {
                val a = Math.toRadians(45.0 + k * 90.0)
                drawLine(
                    color = colour,
                    start = centre,
                    end = Offset(
                        centre.x + (rimRadius * cos(a)).toFloat(),
                        centre.y + (rimRadius * sin(a)).toFloat()
                    ),
                    strokeWidth = this.size.minDimension * 0.022f,
                    cap = StrokeCap.Round
                )
            }

            // Impulse marker. Spokes and screws are symmetric every 45°, so on their own the
            // wheel can look like it's simply spinning. One distinct point breaks the symmetry,
            // and the eye follows it back and forth — which is what makes it read as a balance.
            drawCircle(
                color = accent,
                radius = rimWidth * 1.35f,
                center = Offset(centre.x, centre.y - rimRadius)
            )

            // Timing screws on the rim — the detail that makes the rotation visible at a glance.
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                drawCircle(
                    color = colour,
                    radius = rimWidth * 0.75f,
                    center = Offset(
                        centre.x + (rimRadius * cos(a)).toFloat(),
                        centre.y + (rimRadius * sin(a)).toFloat()
                    )
                )
            }
        }

        // Staff at the centre.
        drawCircle(colour, radius = rimWidth * 0.9f, center = centre)
    }
}

private const val AMPLITUDE = 150f

/** One full swing and back. A real 4 Hz balance is far faster; this is slowed to be readable. */
private const val PERIOD_SECONDS = 1.2
