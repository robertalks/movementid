package com.movementid.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.movementid.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.movementid.app.MainViewModel
import com.movementid.app.backup.BackupArchive
import com.movementid.app.backup.WebDavClient
import com.movementid.app.network.AiProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings

    var provider by remember { mutableStateOf(settings.getProvider()) }
    var apiKey by remember { mutableStateOf(settings.getApiKey(provider)) }
    var customModels by remember { mutableStateOf(settings.getCustomModelsRaw(provider)) }
    var verifyWithSearch by remember { mutableStateOf(settings.getVerifyWithSearch()) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(settings.getSelectedModels(provider)) } }

    // Each provider keeps its own key and model choice, so swap the fields when it changes.
    LaunchedEffect(provider) {
        apiKey = settings.getApiKey(provider)
        customModels = settings.getCustomModelsRaw(provider)
        selected.clear()
        selected.addAll(settings.getSelectedModels(provider))
    }
    var saved by remember { mutableStateOf(false) }
    val testResult by viewModel.searchTestResult.collectAsState()
    val testRunning by viewModel.searchTestRunning.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.selectableGroup()) {
                AiProvider.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .selectable(
                                selected = provider == option,
                                role = Role.RadioButton,
                                onClick = { provider = option; saved = false }
                            )
                            .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = provider == option, onClick = null)
                        Spacer(Modifier.width(6.dp))
                        Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                provider.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text("${provider.displayName} API key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Get one at ${provider.keyUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Models", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick one, or several to compare. With more than one selected, each is asked " +
                    "independently and the answers appear side by side — where they agree is a " +
                    "better signal than any single model's confidence score.\n\nEach selected " +
                    "model costs one request per scan, so four models is four requests against " +
                    "your free quota for a single photo. If you're hitting quota limits, select " +
                    "fewer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            provider.defaultModels.forEach { model ->
                val checked = model in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                if (on) selected.add(model) else selected.remove(model)
                                saved = false
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(model, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (selected.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nothing selected — ${provider.defaultModels.first()} will be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = customModels,
                onValueChange = { customModels = it; saved = false },
                label = { Text("Other model ids (optional)") },
                placeholder = { Text(provider.defaultModels.joinToString(", ")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Comma-separated. Anything you add here is used as-is, so a newly released " +
                    "model works without waiting for an app update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Caliber lookup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Every result gets a Search Google button that opens the caliber in your " +
                    "browser. That needs no key and no quota, and it's usually the fastest way " +
                    "to confirm specs — you read the actual sources instead of trusting a " +
                    "second AI pass.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let ${provider.displayName} check the specs", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The model searches the web and rewrites its own figures after " +
                            "identifying a photo. Off by default because it costs an extra " +
                            "request per scan. Each result also has a button to do this on " +
                            "demand instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = verifyWithSearch,
                    onCheckedChange = { verifyWithSearch = it; saved = false }
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    // Save first, so the test uses what's on screen rather than a stale key.
                    settings.setProvider(provider)
                    settings.setApiKey(provider, apiKey)
                    settings.setCustomModels(provider, customModels)
                    settings.setSelectedModels(provider, selected.toList())
                    settings.setVerifyWithSearch(verifyWithSearch)
                    viewModel.testGoogleSearch()
                },
                enabled = !testRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testRunning) "Testing…" else "Test ${provider.displayName} search")
            }

            testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(result, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.clearSearchTest() }) { Text("Dismiss") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    settings.setProvider(provider)
                    settings.setApiKey(provider, apiKey)
                    settings.setCustomModels(provider, customModels)
                    settings.setSelectedModels(provider, selected.toList())
                    settings.setVerifyWithSearch(verifyWithSearch)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            if (saved) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            var alwaysSharp by remember { mutableStateOf(settings.getAlwaysSharp()) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always use maximum resolution", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off by default. Scans go at a resolution that reads most engraving at " +
                            "lower cost, and a scan that reads no markings offers a sharper " +
                            "retry. Turn this on to always send full size — uses your free " +
                            "quota noticeably faster.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = alwaysSharp,
                    onCheckedChange = { alwaysSharp = it; settings.setAlwaysSharp(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            BackupSection(viewModel)

            Spacer(Modifier.height(24.dp))
            // Shown so it's possible to tell at a glance whether a rebuild actually reached the
            // phone — a failed build leaves the previous APK installed and running.
            Text(
                "MovementID ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your keys, photos and saved movements never leave this device except for the " +
                    "direct call to the provider you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}


/**
 * Backup settings.
 *
 * Automatic sync is the feature; the export button is a convenience, not an equal alternative.
 * A manual backup only helps if it was made before the phone broke, and that's exactly the
 * moment nobody thinks to make one — so the UI leads with the automatic option and says plainly
 * what a local-only copy does and doesn't protect against.
 */
@Composable
private fun BackupSection(viewModel: MainViewModel) {
    val settings = viewModel.settings

    var enabled by remember { mutableStateOf(settings.getBackupEnabled()) }
    var wifiOnly by remember { mutableStateOf(settings.getBackupWifiOnly()) }
    var url by remember { mutableStateOf(settings.getBackupUrl().ifBlank { WebDavClient.KOOFR_DEFAULT }) }
    var user by remember { mutableStateOf(settings.getBackupUser()) }
    var password by remember { mutableStateOf(settings.getBackupPassword()) }
    var showRestoreConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }

    val busy by viewModel.backupBusy.collectAsState()
    val status by viewModel.backupStatus.collectAsState()

    // Read as state and refreshed whenever an operation finishes: reading it straight from
    // prefs during composition meant a successful backup still showed "no successful backup
    // yet" until the screen was reopened.
    var lastAt by remember { mutableStateOf(settings.getLastBackupAt()) }
    LaunchedEffect(busy, status) {
        if (!busy) lastAt = settings.getLastBackupAt()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportBackup) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { picked -> showRestoreConfirm = { viewModel.restoreBackup(picked) } } }

    fun persist() {
        settings.setBackupEnabled(enabled)
        settings.setBackupWifiOnly(wifiOnly)
        settings.setBackupUrl(url)
        settings.setBackupUser(user)
        settings.setBackupPassword(password)
        viewModel.rescheduleBackups()
    }

    Text("Backup", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Keeps a copy of every identification, note and photo on your own cloud storage, so a " +
            "broken, lost or replaced phone doesn't mean redoing the work.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Back up automatically", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Runs after you save a movement, and daily as a safety net.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { enabled = it; persist() }
        )
    }

    if (enabled) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Archives include your photos, so they run to tens of megabytes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; persist() })
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("WebDAV folder URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (your Koofr email)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Koofr gives 10 GB free and speaks WebDAV, so no sign-in dance is needed. Create an " +
                "app password at app.koofr.net under Preferences → Password — your account " +
                "password won't work. Create the folder in Koofr first. Nextcloud, pCloud or any " +
                "other WebDAV server works here too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { persist(); viewModel.testBackupConnection(url, user, password) },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Test") }
            Button(
                onClick = { persist(); viewModel.backupNow() },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "Working…" else "Back up now") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (lastAt > 0) {
                "Last backup: " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                    .format(Date(lastAt))
            } else if (!settings.backupConfigured()) {
                "Not set up yet — fill in the URL, username and app password above."
            } else {
                "No successful backup yet. Press \"Back up now\" to check it works."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (lastAt > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showRestoreConfirm = { viewModel.restoreFromServer() } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore newest from server") }
    }

    Spacer(Modifier.height(16.dp))
    Text("Backup file", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Save everything as a single .zip, or restore from one. Useful for moving to a new " +
            "phone or keeping a copy elsewhere — but a file on this phone is not protection " +
            "against losing it, so leave automatic backup on as well.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { exportLauncher.launch(BackupArchive.fileName()) },
            enabled = !busy,
            modifier = Modifier.weight(1f)
        ) { Text("Export") }
        OutlinedButton(
            onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
            enabled = !busy,
            modifier = Modifier.weight(1f)
        ) { Text("Restore") }
    }

    status?.let { message ->
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::clearBackupStatus) { Text("Dismiss") }
            }
        }
    }

    // Restore replaces everything, so it always asks first.
    showRestoreConfirm?.let { action ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = null },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "Restoring replaces every movement currently saved on this phone with the " +
                        "contents of the backup. Anything identified since that backup was made " +
                        "will be lost."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRestoreConfirm = null; action() }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = null }) { Text("Cancel") }
            }
        )
    }
}
