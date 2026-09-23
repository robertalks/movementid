package com.movementid.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Full-screen viewer for a movement's photos. The reason it exists: the engraving that
 * identifies a caliber is often under a millimetre tall, so being able to zoom into the saved
 * original is how you check the AI's reading — or read the number yourself.
 *
 * Pinch or double-tap to zoom, drag to pan while zoomed, swipe between photos when not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(
    photos: List<Any>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    if (photos.isEmpty()) return

    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, photos.size - 1),
        pageCount = { photos.size }
    )

    // Swiping between photos is disabled while zoomed, so a pan doesn't turn into a page change.
    var zoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(
                    model = photos[page],
                    onZoomChanged = { isZoomed -> if (page == pager.currentPage) zoomed = isZoomed }
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }

            if (photos.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${photos.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 20.dp)
                )
            }

            Text(
                "Pinch or double-tap to zoom",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ZoomableImage(model: Any, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    /** Keeps the image covering the frame, so you can't pan it off into empty black. */
    fun clamp(raw: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    fun apply(newScale: Float, newOffset: Offset) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        offset = if (scale <= MIN_SCALE) Offset.Zero else clamp(newOffset, scale)
        onZoomChanged(scale > MIN_SCALE)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > MIN_SCALE) {
                            apply(MIN_SCALE, Offset.Zero)
                        } else {
                            // Zoom towards the tapped point, which is where the user wants to look.
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            apply(DOUBLE_TAP_SCALE, (centre - tap) * (DOUBLE_TAP_SCALE - 1f))
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Hand-rolled rather than detectTransformGestures, which consumes every drag and
                // would stop the pager swiping. This only claims the gesture when it's a pinch or
                // the image is already zoomed; a plain one-finger swipe at 1x reaches the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.size > 1
                        if (pinching || scale > MIN_SCALE) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            apply(scale * zoom, offset + pan)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Coil normally decodes to the view's size, which at 8x zoom would just magnify a
        // screen-sized bitmap into blur. Decode large enough to actually resolve engraving, but
        // capped: a full 12-50 MP original as a bitmap can run to 100+ MB and crash low-memory
        // phones, and 4096px already exceeds what the zoom needs.
        val context = LocalContext.current
        val request = remember(model) {
            ImageRequest.Builder(context)
                .data(model)
                .size(VIEWER_DECODE_PX)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

private const val VIEWER_DECODE_PX = 4096
private const val MIN_SCALE = 1f
private const val DOUBLE_TAP_SCALE = 3f

/** High enough to read sub-millimetre engraving on a full-resolution original. */
private const val MAX_SCALE = 8f
