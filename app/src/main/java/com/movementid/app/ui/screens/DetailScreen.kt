package com.movementid.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movementid.app.MainViewModel
import com.movementid.app.data.photoList
import com.movementid.app.network.CaliberSearch
import com.movementid.app.network.MovementIdentification
import com.movementid.app.data.MovementEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every identified field is editable. The AI is frequently wrong or vague on this task, and the
 * user often knows better — once they've looked the caliber up or opened the watch, their
 * correction should become the record rather than sitting in a notes field beside a wrong value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: MainViewModel,
    entryId: Long,
    onBack: () -> Unit
) {
    var entry by remember { mutableStateOf<MovementEntry?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Editable copies of every field.
    var title by remember { mutableStateOf("") }
    var markings by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var family by remember { mutableStateOf("") }
    var caliber by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var beatRate by remember { mutableStateOf("") }
    var powerReserve by remember { mutableStateOf("") }
    var jewels by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("") }
    var liftAngle by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    LaunchedEffect(entryId) {
        val loaded = viewModel.getEntry(entryId)
        entry = loaded
        loaded?.let {
            title = it.title.orEmpty()
            markings = it.markings.orEmpty()
            brand = it.brandGuess.orEmpty()
            family = it.movementFamily.orEmpty()
            caliber = it.caliber.orEmpty()
            type = it.movementType.orEmpty()
            size = it.sizeMm.orEmpty()
            beatRate = it.beatRateVph.orEmpty()
            powerReserve = it.powerReserveHours.orEmpty()
            jewels = it.jewelCount.orEmpty()
            years = it.productionYears.orEmpty()
            liftAngle = it.liftAngle.orEmpty()
            notes = it.userNotes.orEmpty()
        }
    }

    val current = entry

    // Adding a photo to a saved movement — a dial-side shot taken later, or the same caliber
    // found in another watch.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = current
        if (uri != null && target != null) {
            viewModel.addPhotoToEntry(target, uri) { updated -> entry = updated }
        }
    }

    fun persist() {
        current?.let {
            viewModel.updateEntry(
                it.copy(
                    title = title.trim().takeIf { v -> v.isNotBlank() },
                    markings = markings.trim().takeIf { v -> v.isNotBlank() },
                    brandGuess = brand.trim().takeIf { v -> v.isNotBlank() },
                    movementFamily = family.trim().takeIf { v -> v.isNotBlank() },
                    caliber = caliber.trim().takeIf { v -> v.isNotBlank() },
                    movementType = type.trim().takeIf { v -> v.isNotBlank() },
                    sizeMm = size.trim().takeIf { v -> v.isNotBlank() },
                    beatRateVph = beatRate.trim().takeIf { v -> v.isNotBlank() },
                    powerReserveHours = powerReserve.trim().takeIf { v -> v.isNotBlank() },
                    jewelCount = jewels.trim().takeIf { v -> v.isNotBlank() },
                    productionYears = years.trim().takeIf { v -> v.isNotBlank() },
                    liftAngle = liftAngle.trim().takeIf { v -> v.isNotBlank() },
                    userNotes = notes.trim().takeIf { v -> v.isNotBlank() }
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete this movement?") },
                text = { Text("The photo and saved details will be removed from this device.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteEntry(current)
                        showDeleteDialog = false
                        onBack()
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                }
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                val photos = current.photoList()
                val uriOpener = LocalUriHandler.current
                var shown by remember(current.id) { mutableStateOf(0) }
                val shownIndex = shown.coerceIn(0, (photos.size - 1).coerceAtLeast(0))

                var viewerOpen by remember { mutableStateOf(false) }
                AsyncImage(
                    model = photos.getOrNull(shownIndex) ?: current.photoPath,
                    contentDescription = "Tap to view full size",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewerOpen = true }
                )
                Text(
                    "Tap to view full size and zoom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (viewerOpen) {
                    PhotoViewer(
                        photos = photos,
                        startIndex = shownIndex,
                        onDismiss = { viewerOpen = false }
                    )
                }

                // Thumbnail strip: several angles of the same movement, e.g. case back plus
                // dial side, or the same caliber from another watch with different branding.
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    photos.forEachIndexed { index, path ->
                        AsyncImage(
                            model = path,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (index == shownIndex) 2.dp else 0.dp,
                                    color = if (index == shownIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { shown = index }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Photo", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(Modifier.height(16.dp))

                EditField("Title", title, { title = it }, "e.g. Seiko NH35 in the diver")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Identification", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Everything here is editable — correct whatever the AI got wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            item { EditField("Brand", brand, { brand = it }, "ETA, Seiko, Miyota…") }
            item { EditField("Movement family", family, { family = it }, "ETA 2824-2") }
            item { EditField("Caliber", caliber, { caliber = it }, "NH35A") }
            item { EditField("Type", type, { type = it }, "Automatic / Manual wind / Quartz") }
            item { EditField("Size", size, { size = it }, "25.6mm (11 1/2 lignes)") }
            item { EditField("Beat rate", beatRate, { beatRate = it }, "28800 vph (4Hz)") }
            item { EditField("Power reserve", powerReserve, { powerReserve = it }, "41 hours") }
            item { EditField("Jewels", jewels, { jewels = it }, "24 jewels") }
            item {
                EditField("Lift angle", liftAngle, { liftAngle = it }, "52° — for your timegrapher")
                current.liftAngleSource?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }
            item { EditField("Years produced", years, { years = it }, "1996-2010, or 1970s") }

            item {
                Spacer(Modifier.height(8.dp))
                EditField(
                    "Markings on the movement", markings, { markings = it },
                    "One per line: 2824-2, 25 JEWELS, SWISS", minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                EditField("Your notes", notes, { notes = it }, "Which watch it came from, service history…", minLines = 3)
                Spacer(Modifier.height(16.dp))
                // Searches what's currently in the fields, so a correction just typed is what
                // gets looked up rather than the AI's original guess.
                val uriHandler = LocalUriHandler.current
                val searchUrl = CaliberSearch.urlFor(
                    MovementIdentification(
                        brandGuess = brand.trim().ifBlank { null },
                        movementFamily = family.trim().ifBlank { null },
                        caliber = caliber.trim().ifBlank { null }
                    )
                )
                searchUrl?.let { url ->
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Look this caliber up on Google")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Ranfft carries per-caliber specs and reference photos, which is usually the
                // fastest way to confirm an identification by eye.
                CaliberSearch.ranfftUrlFor(
                    MovementIdentification(
                        brandGuess = brand.trim().ifBlank { null },
                        movementFamily = family.trim().ifBlank { null },
                        caliber = caliber.trim().ifBlank { null }
                    )
                )?.let { url ->
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Find it in the Ranfft archive")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = { persist(); onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save changes")
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                current.aiNotes?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("AI reasoning", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                }

                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Scan details", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                MetaRow("Model", current.modelUsed)
                MetaRow("Took", current.latencyMs?.let { "${it / 1000}s" })
                MetaRow("Google Search check", if (current.webVerified) "Yes" else "No")
                MetaRow(
                    "Scanned",
                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                        .format(Date(current.timestamp))
                )

                current.sources?.takeIf { it.isNotBlank() }?.let { sources ->
                    Spacer(Modifier.height(12.dp))
                    Text("Sources — tap to open", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    val uriHandler = LocalUriHandler.current
                    sources.lines().filter { it.isNotBlank() }.forEach { line ->
                        // Stored as "Title — url"; split so the row can open the link.
                        val url = line.substringAfterLast(" — ", "").trim()
                        val label = line.substringBeforeLast(" — ").trim()
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (url.startsWith("http")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = url.startsWith("http")) {
                                    runCatching { uriHandler.openUri(url) }
                                }
                                .padding(vertical = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun MetaRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.55f)
        )
    }
}
