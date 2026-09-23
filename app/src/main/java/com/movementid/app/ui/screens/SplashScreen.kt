package com.movementid.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movementid.app.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Black screen, movement logo, app name. The system splash (Android 12+) is themed to the same
 * black with no icon, so this reads as the only splash rather than a second one.
 *
 * The balance wheel oscillates rather than the whole logo spinning — a movement's plate doesn't
 * rotate, and the ticking motion is the recognisable part.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Frame-clock driven, like the thinking animation: transition APIs freeze when the system
    // animator scale is off, which left this splash motionless on phones with Developer Options
    // or Battery Saver adjusting it.
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsed = (now - start) / 1_000_000_000f }
        }
    }
    val angle = 28f * sin(2.0 * PI * elapsed / 1.8).toFloat()

    LaunchedEffect(Unit) {
        delay(1900)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_movement_logo),
                contentDescription = null,
                modifier = Modifier.size(220.dp).rotate(angle * 0.08f)
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "MovementID",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
