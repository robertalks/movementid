package com.movementid.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movementid.app.MainViewModel
import com.movementid.app.data.MovementEntry
import com.movementid.app.ui.MakerMark
import com.movementid.app.ui.makerNameFrom
import com.movementid.app.ui.theme.Engraved
import com.movementid.app.ui.theme.MovementColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onEntryClick: (Long) -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val entries by viewModel.movements.collectAsState()
    val query by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Collection", style = MaterialTheme.typography.headlineMedium)
                        // The maker count is the interesting half: it says how varied the
                        // collection is, which the movement count on its own doesn't.
                        val makers = entries
                            .mapNotNull {
                                makerNameFrom(it.brandGuess, it.caliber, it.movementFamily)
                            }
                            .map { it.lowercase() }
                            .distinct().size
                        if (entries.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    append(entries.size)
                                    append(if (entries.size == 1) " movement" else " movements")
                                    if (makers > 0) {
                                        append(" · ")
                                        append(makers)
                                        append(if (makers == 1) " maker" else " makers")
                                    }
                                }.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanClick,
                icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                text = { Text("Identify") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Search only earns its place once there's enough saved to need it.
            if (entries.isNotEmpty() || query.isNotBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = { Text("Search caliber, brand, notes…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when {
                entries.isEmpty() && query.isNotBlank() -> EmptyMessage(
                    icon = Icons.Filled.Search,
                    title = "No matches",
                    body = "Nothing saved matches \"$query\"."
                )

                entries.isEmpty() -> EmptyMessage(
                    icon = Icons.Filled.WatchLater,
                    title = "No movements yet",
                    body = "Tap Identify to photograph a movement. Everything you save stays " +
                        "on this device and shows up here."
                )

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        MovementCard(entry = entry, onClick = { onEntryClick(entry.id) })
                    }
                }
            }
        }
    }
}

/**
 * A row in the collection.
 *
 * Photo on the left (68dp) with the maker mark badged over its bottom-left corner, so the
 * movement image is the first thing you see and the mark reads against it rather than floating
 * separately. Text on the right: caliber name, type/years subtitle, then spec and engraving chips.
 */
@Composable
private fun MovementCard(entry: MovementEntry, onClick: () -> Unit) {
    val maker = makerNameFrom(entry.brandGuess, entry.caliber, entry.movementFamily)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Photo + maker mark badge
            Box(modifier = Modifier.size(68.dp)) {
                AsyncImage(
                    model = entry.photoPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                )
                MakerMark(
                    maker = maker,
                    size = 24.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset((-4).dp, 4.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title?.takeIf { it.isNotBlank() }
                        ?: entry.caliber?.takeIf { it.isNotBlank() }
                        ?: entry.movementFamily?.takeIf { it.isNotBlank() }
                        ?: "Unidentified movement",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitle = listOfNotNull(
                    entry.movementType?.takeIf { it.isNotBlank() },
                    entry.productionYears?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank {
                    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(entry.timestamp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val chips = buildList {
                    if (entry.webVerified) add(Chip("Verified", MovementColors.Ok))
                    entry.beatRateVph?.takeIf { it.isNotBlank() }
                        ?.let { add(Chip(it.substringBefore(" ("), null)) }
                    entry.jewelCount?.takeIf { it.isNotBlank() }
                        ?.let { add(Chip(it, MovementColors.Gold)) }
                    val extras = entry.additionalPhotos.orEmpty()
                        .lines().count { it.isNotBlank() }
                    if (extras > 0) add(Chip("${extras + 1} photos", null))
                }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.take(3).forEach { StatusChip(it) }
                    }
                }

                // First 2 markings read off the movement — shown in monospace so they read as
                // engraved text, not the app's own label copy.
                val markings = entry.markings.orEmpty()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(2)
                if (markings.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        markings.forEach { EngravingChip(it) }
                    }
                }
            }
        }
    }
}

/** A chip's text and, when it carries a verdict, the colour that verdict earns. */
private data class Chip(val text: String, val accent: Color?)

@Composable
private fun StatusChip(chip: Chip) {
    val accent = chip.accent
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = accent?.copy(alpha = 0.10f) ?: MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            accent?.copy(alpha = 0.34f) ?: MaterialTheme.colorScheme.outline
        )
    ) {
        Text(
            text = chip.text,
            style = MaterialTheme.typography.labelMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EngravingChip(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MovementColors.SurfaceRaised,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = text,
            fontFamily = Engraved,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
