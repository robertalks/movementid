package com.movementid.app.ui.screens

import android.Manifest
import androidx.compose.foundation.Image
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.movementid.app.MainViewModel
import com.movementid.app.ScanState
import com.movementid.app.network.CaliberSearch
import com.movementid.app.network.LiftAngleCheck
import com.movementid.app.network.LiftAngleTable
import com.movementid.app.network.MovementIdentification
import com.movementid.app.network.contradictsMarkings
import com.movementid.app.network.SearchStatus
import com.movementid.app.repository.ModelAttempt
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: MainViewModel,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scanState by viewModel.scanState.collectAsState()
    val verifying by viewModel.verifying.collectAsState()
    val pendingPhotos by viewModel.pendingPhotos.collectAsState()

    // Set when the user explicitly asks for the camera from the staged view.
    var cameraRequested by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted && pendingPhotos.isNotEmpty()) cameraRequested = true
    }

    // Photo Picker: no storage permission needed, and only the chosen photo is exposed.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.importFromGallery(it) } }

    fun openGallery() = galleryLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    LaunchedEffect(Unit) {
        // Don't ask for the camera when the user arrived with photos already in hand (shared
        // from Gallery, say) — they haven't asked to take a picture.
        if (!hasCameraPermission && pendingPhotos.isEmpty()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identify movement") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetScanState()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { openGallery() }) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Pick from gallery")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The camera only runs when there's nothing staged. Arriving with photos already in
            // hand — shared from Gallery, or just taken — means a live preview is both useless
            // and rude: it holds the camera open, drains battery and lights the privacy
            // indicator for a capture the user never asked for.
            val wantsCamera = pendingPhotos.isEmpty() || cameraRequested

            when {
                wantsCamera && hasCameraPermission -> CameraPreview(
                    enabled = scanState is ScanState.Idle,
                    onPhotoCaptured = { file ->
                        viewModel.addPendingPhoto(file)
                        cameraRequested = false   // back to the staged view after each shot
                    },
                    onGalleryClick = { openGallery() }
                )

                wantsCamera -> NoCameraFallback(
                    onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onGallery = { openGallery() }
                )

                else -> StagedPhotos(
                    photos = pendingPhotos,
                    onRemove = viewModel::removePendingPhoto,
                    onIdentify = { viewModel.analyze() },
                    onAddFromGallery = { openGallery() },
                    onTakePhoto = {
                        if (hasCameraPermission) {
                            cameraRequested = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = scanState is ScanState.Idle
                )
            }

            when (val state = scanState) {
                is ScanState.Analyzing -> AnalyzingOverlay(
                    stage = state.stage,
                    onCancel = viewModel::cancelAnalysis
                )

                is ScanState.Review -> ReviewSheet(
                    attempts = state.attempts,
                    onRetrySharper = viewModel::retrySharper,
                    onFeedback = viewModel::reaskWithFeedback,
                    verifying = verifying,
                    providerName = viewModel.providerName(),
                    onVerify = viewModel::verifyAttempt,
                    onSave = { attempt, edited -> viewModel.saveAttempt(attempt, edited, onSaved) },
                    onRetry = viewModel::retryAnalysis,
                    onDiscard = viewModel::discardReview
                )

                is ScanState.Error -> ErrorOverlay(
                    message = state.message,
                    onDismiss = viewModel::resetScanState
                )

                ScanState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun NoCameraFallback(onGrant: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Camera access is needed to photograph a movement — or you can pick an existing " +
                "photo instead.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Grant camera access") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGallery) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose from gallery")
        }
    }
}

/** Cancellable — verification roughly doubles the wait, so waiting it out isn't always wanted. */
@Composable
private fun AnalyzingOverlay(stage: String, onCancel: () -> Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds += 1
        }
    }

    // "Asking 2 models" → "." ".." "..." so the line itself looks alive.
    var dots by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(420)
            dots = (dots + 1) % 4
        }
    }
    val baseStage = stage.lines().first().trimEnd('…', '.', ' ')
    val restOfStage = stage.lines().drop(1).joinToString("\n")

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Column(
                modifier = Modifier.padding(24.dp).width(280.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TickingBalance(size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    // Padded to a fixed width so the text doesn't jitter as dots come and go.
                    baseStage + ".".repeat(dots) + " ".repeat(3 - dots),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )
                if (restOfStage.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        restOfStage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("${seconds}s", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Couldn't identify", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * Every field is editable here, not just on the detail screen: the model is frequently wrong, and
 * correcting it while the movement is still in front of you beats fixing it later from memory.
 *
 * With several models selected each answer gets its own tab. They're kept separate rather than
 * merged into one averaged answer, because where models agree is a better signal than any single
 * model's confidence score — and averaging would hide exactly that disagreement.
 */
@Composable
private fun ReviewSheet(
    attempts: List<ModelAttempt>,
    onRetrySharper: () -> Unit,
    onFeedback: (String) -> Unit,
    verifying: Set<String>,
    providerName: String,
    onVerify: (ModelAttempt) -> Unit,
    onSave: (ModelAttempt, MovementIdentification) -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    // Models that failed simply don't get a tab. Their errors go to Logcat rather than the UI:
    // when another model answered, a list of failures is noise, not information.
    val successes = attempts.filter { it.succeeded }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val selected = successes.getOrNull(selectedIndex.coerceIn(0, (successes.size - 1).coerceAtLeast(0)))

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (successes.size > 1) "${successes.size} answers" else "Result",
                    style = MaterialTheme.typography.titleMedium
                )

                if (successes.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex.coerceIn(0, successes.size - 1),
                        edgePadding = 0.dp
                    ) {
                        successes.forEachIndexed { index, attempt ->
                            Tab(
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                                text = {
                                    Text(
                                        attempt.model.removePrefix("gemini-"),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }
                    }
                }

                if (selected?.result != null) {
                    // key() so switching tabs rebuilds the edit fields for the newly selected model
                    // instead of carrying the previous model's edits across.
                    key(selected.model) {
                        EditableResult(
                            attempt = selected,
                            isVerifying = selected.model in verifying,
                            onRetrySharper = onRetrySharper,
                            onFeedback = onFeedback,
                            providerName = providerName,
                            onVerify = onVerify,
                            onSave = onSave,
                            onRetry = onRetry,
                            onDiscard = onDiscard
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableResult(
    attempt: ModelAttempt,
    isVerifying: Boolean,
    onRetrySharper: () -> Unit,
    onFeedback: (String) -> Unit,
    providerName: String,
    onVerify: (ModelAttempt) -> Unit,
    onSave: (ModelAttempt, MovementIdentification) -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    val result = attempt.result ?: return
    val id = result.identification

    var family by remember { mutableStateOf(id.movementFamily.orEmpty()) }
    var caliber by remember { mutableStateOf(id.caliber.orEmpty()) }
    var brand by remember { mutableStateOf(id.brandGuess.orEmpty()) }
    var type by remember { mutableStateOf(id.movementType.orEmpty()) }
    var size by remember { mutableStateOf(id.sizeMm.orEmpty()) }
    var beat by remember { mutableStateOf(id.beatRateVph.orEmpty()) }
    var reserve by remember { mutableStateOf(id.powerReserveHours.orEmpty()) }
    var jewels by remember { mutableStateOf(id.jewelCount.orEmpty()) }
    var years by remember { mutableStateOf(id.productionYears.orEmpty()) }
    var liftAngle by remember { mutableStateOf(id.liftAngle.orEmpty()) }

    Column {
        Spacer(Modifier.height(8.dp))
        Text(
            "${result.modelUsed} · ${result.latencyMs / 1000}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        attempt.fellBackFrom?.let { original ->
            Spacer(Modifier.height(4.dp))
            Text(
                "$original was out of quota, so ${result.modelUsed} answered instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(Modifier.height(8.dp))
        MarkingsPanel(id)

        // Only offered when nothing was legible: that's when extra pixels can change the answer,
        // and the only time the extra tokens are worth spending.
        if (id.visibleMarkings.orEmpty().none { it.isNotBlank() }) {
            TextButton(
                onClick = onRetrySharper,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Try again sharper", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(8.dp))
        SearchStatusRow(result.searchStatus)

        // Looking the caliber up in a browser costs nothing and always works, so it's the
        // primary action. The Gemini check is secondary: it spends the quota that runs out.
        val uriHandler = LocalUriHandler.current
        val searchUrl = CaliberSearch.urlFor(id)
        val imagesUrl = CaliberSearch.imagesUrlFor(id)
        val ranfftUrl = CaliberSearch.ranfftUrlFor(id)

        if (searchUrl != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(searchUrl) } },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Search Google", style = MaterialTheme.typography.labelMedium)
                }
                imagesUrl?.let { url ->
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Images", style = MaterialTheme.typography.labelMedium)
                    }
                }
                ranfftUrl?.let { url ->
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Ranfft", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (result.searchStatus !is SearchStatus.Verified) {
                    TextButton(
                        onClick = { onVerify(attempt) },
                        enabled = !isVerifying,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (isVerifying) "Checking…" else "Ask $providerName",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Anything wrong? Fix it here before saving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        EditField("Movement family", family) { family = it }
        EditField("Caliber", caliber) { caliber = it }
        EditField("Brand", brand) { brand = it }
        EditField("Type", type) { type = it }
        EditField("Size", size) { size = it }
        EditField("Beat rate", beat) { beat = it }
        EditField("Power reserve", reserve) { reserve = it }
        EditField("Jewels", jewels) { jewels = it }
        EditField("Lift angle", liftAngle) { liftAngle = it }
        LiftAngleNote(result.liftAngleCheck)
        EditField("Years produced", years) { years = it }

        id.notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text("Reasoning", style = MaterialTheme.typography.labelMedium)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (result.sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Sources — tap to open", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val uriHandler = LocalUriHandler.current
            result.sources.forEach { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hit.link.isNotBlank()) {
                            runCatching { uriHandler.openUri(hit.link) }
                        }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (hit.snippet.isNotBlank()) {
                        Text(
                            hit.snippet,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        var showFeedback by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showFeedback = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Not right? Tell it why")
        }
        if (showFeedback) {
            FeedbackDialog(
                onDismiss = { showFeedback = false },
                onSubmit = { text -> showFeedback = false; onFeedback(text) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text("Discard")
            }
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Text("Retry")
            }
            Button(
                onClick = {
                    onSave(
                        attempt,
                        id.copy(
                            movementFamily = family.trim().ifBlank { null },
                            caliber = caliber.trim().ifBlank { null },
                            brandGuess = brand.trim().ifBlank { null },
                            movementType = type.trim().ifBlank { null },
                            sizeMm = size.trim().ifBlank { null },
                            beatRateVph = beat.trim().ifBlank { null },
                            powerReserveHours = reserve.trim().ifBlank { null },
                            jewelCount = jewels.trim().ifBlank { null },
                            liftAngle = liftAngle.trim().ifBlank { null },
                            productionYears = years.trim().ifBlank { null }
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
        }
    }
}

/** Always shown, so "the web check did nothing" is never a silent mystery. */
/**
 * Says where the lift angle came from. Shown because a figure taken from a published table and
 * one the model recalled are very different things to trust, especially if you're about to set a
 * timegrapher by it.
 */
@Composable
private fun LiftAngleNote(check: LiftAngleCheck) {
    val uriHandler = LocalUriHandler.current

    val (text, tint) = when (check) {
        is LiftAngleCheck.Found ->
            (if (check.filledIn) {
                "From WatchGuy's list (${check.entry.brand} ${check.entry.caliber})"
            } else {
                "Confirmed by WatchGuy's list"
            }) to MaterialTheme.colorScheme.primary

        is LiftAngleCheck.Conflict ->
            "WatchGuy lists ${check.entry.angle}° for ${check.entry.brand} " +
                "${check.entry.caliber} — the model said ${check.modelSaid}. Using the list." to
                MaterialTheme.colorScheme.error

        is LiftAngleCheck.Ambiguous ->
            "WatchGuy lists caliber ${check.caliber} under ${check.brands.joinToString(", ")} " +
                "— can't tell which is yours, so the model's figure is kept. Check the list." to
                MaterialTheme.colorScheme.error

        LiftAngleCheck.NotListed ->
            "Not in WatchGuy's list for this brand — this is the model's own figure, so verify " +
                "before trusting it on a timegrapher." to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
            .clickable { runCatching { uriHandler.openUri(LiftAngleTable.SOURCE_URL) } }
    )
}

/**
 * What you see when photos are already staged — shared in from Gallery, or just captured. Shows
 * them properly rather than as thumbnails floating over a live camera preview, and leaves the
 * camera switched off until it's actually wanted.
 */
@Composable
private fun StagedPhotos(
    photos: List<File>,
    onRemove: (File) -> Unit,
    onIdentify: () -> Unit,
    onAddFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    enabled: Boolean
) {
    var shown by remember(photos.size) { mutableIntStateOf(0) }
    val index = shown.coerceIn(0, (photos.size - 1).coerceAtLeast(0))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Worth zooming before identifying: if the engraving isn't legible to you, it won't be
        // to the model either, and a retake costs less than a wasted request.
        var viewerOpen by remember { mutableStateOf(false) }
        photos.getOrNull(index)?.let { photo ->
            AsyncImage(
                model = photo,
                contentDescription = "Tap to view full size",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewerOpen = true }
            )
            Text(
                "Tap to zoom — check the engraving is sharp before identifying",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (viewerOpen) {
            PhotoViewer(photos = photos, startIndex = index, onDismiss = { viewerOpen = false })
        }

        if (photos.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                photos.forEachIndexed { i, photo ->
                    AsyncImage(
                        model = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = if (i == index) 2.dp else 0.dp,
                                color = if (i == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { shown = i }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (photos.size == 1) {
                "1 photo ready. Add more if you have the dial side, or the same caliber from " +
                    "another watch — extra angles often settle an identification."
            } else {
                "${photos.size} photos, treated as one movement."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onIdentify,
            enabled = enabled && photos.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (photos.size == 1) "Identify" else "Identify from ${photos.size} photos")
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Camera")
            }
            OutlinedButton(onClick = onAddFromGallery, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }
        }

        photos.getOrNull(index)?.let { photo ->
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onRemove(photo) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Remove this photo")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Lets the user say what the model got wrong, then re-runs with that as a constraint. */
@Composable
private fun FeedbackDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What did it get wrong?") },
        text = {
            Column {
                Text(
                    "You can see things the photo can't — the case, the dial side, where the " +
                        "watch came from. Say what's wrong and it will work from that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. It's an Omega, the case back is marked 1012") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) {
                Text("Ask again")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * What the model read off the movement. Shown because it's the evidence behind the answer, and
 * because a legible caliber number that disagrees with the answer is the clearest possible sign
 * the model reasoned past what was written in front of it.
 */
@Composable
private fun MarkingsPanel(id: MovementIdentification) {
    val markings = id.visibleMarkings.orEmpty().filter { it.isNotBlank() }
    val conflict = id.contradictsMarkings()

    if (conflict != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "It read \"$conflict\" on the movement but answered " +
                    "${id.caliber ?: id.movementFamily}. The engraving is usually right — check " +
                    "the photo, and use \"Tell it why\" if the answer is wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    Text("Markings read", style = MaterialTheme.typography.labelMedium)
    Text(
        if (markings.isEmpty()) {
            "None legible — this identification is from the layout alone, so treat it with care. " +
                "A sharper, closer shot of the engraving usually helps more than anything else."
        } else {
            markings.joinToString("  ·  ")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (markings.isEmpty()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun SearchStatusRow(status: SearchStatus) {
    val verified = status is SearchStatus.Verified
    val problem = status is SearchStatus.Failed
    val tint = when {
        verified -> MaterialTheme.colorScheme.primary
        problem -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (verified) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint
            )
            Spacer(Modifier.width(6.dp))
            Text(status.describe(), style = MaterialTheme.typography.bodySmall, color = tint)
        }

        // The cause, when there is one. Small and secondary, but visible without Logcat — a
        // web check that quietly does nothing is worse than one that says why it couldn't.
        status.detail()?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
private fun CameraPreview(
    enabled: Boolean,
    onPhotoCaptured: (File) -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: Executor = remember { Executors.newSingleThreadExecutor() }
    // Quality over speed: CameraX defaults to minimising shutter latency, which trades away
    // processing (multi-frame noise reduction on phones that support it) and doesn't ask for the
    // sensor's full resolution. For a still subject whose engraving is the key evidence, the
    // extra few hundred milliseconds are worth it — and the saved original is what you zoom into
    // later, so it should be the best capture the phone can make.
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
        ) {
            Text(
                text = "Fill the frame with the movement. Steady light, avoid glare — " +
                    "readable engraving is what makes or breaks the result.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(10.dp)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            FilledTonalButton(onClick = onGalleryClick) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }

            FloatingActionButton(
                onClick = {
                    if (!enabled) return@FloatingActionButton
                    val photoFile = createImageFile(context)
                    val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    imageCapture.takePicture(
                        options,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                onPhotoCaptured(photoFile)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                            }
                        }
                    )
                },
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Capture")
            }
        }
    }
}

private fun createImageFile(context: Context): File {
    val dir = File(context.filesDir, "movements").apply { mkdirs() }
    val name = "movement_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    return File(dir, name)
}
