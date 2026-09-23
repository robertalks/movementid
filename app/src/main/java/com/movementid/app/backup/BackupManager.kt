package com.movementid.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.movementid.app.data.AppDatabase
import com.movementid.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class BackupOutcome {
    /** Uploaded to the server. */
    data class Uploaded(val fileName: String, val bytes: Long) : BackupOutcome()

    /**
     * The upload failed, so the archive was kept on the device instead. Not a substitute for a
     * cloud copy — a local file dies with the phone — but it means the work isn't lost if the
     * server is merely unreachable for a while, and the next successful sync carries it up.
     */
    data class SavedLocally(val file: File, val reason: String) : BackupOutcome()

    data class Failed(val reason: String) : BackupOutcome()

    data object NothingToDo : BackupOutcome()
}

class BackupManager(private val context: Context) {

    private val settings = SettingsRepository(context)
    private val dao = AppDatabase.getInstance(context).movementDao()

    private val stagingDir: File
        get() = File(context.filesDir, "backup").apply { mkdirs() }

    /** Builds an archive of everything and sends it wherever it can. */
    suspend fun runBackup(): BackupOutcome = withContext(Dispatchers.IO) {
        val entries = dao.getAllOnce()
        if (entries.isEmpty()) return@withContext BackupOutcome.NothingToDo

        val archive = File(stagingDir, BackupArchive.fileName())
        val manifest = runCatching {
            archive.outputStream().use { BackupArchive.write(entries, it) }
        }.getOrElse { error ->
            return@withContext BackupOutcome.Failed("Couldn't build the archive: ${error.message}")
        }

        if (!settings.backupConfigured()) {
            keepRecentLocal()
            val missing = listOfNotNull(
                "folder URL".takeIf { settings.getBackupUrl().isBlank() },
                "username".takeIf { settings.getBackupUser().isBlank() },
                "app password".takeIf { settings.getBackupPassword().isBlank() }
            ).joinToString(", ")
            return@withContext BackupOutcome.SavedLocally(
                archive,
                "Backup isn't set up yet — missing: $missing."
            )
        }

        val client = WebDavClient(
            settings.getBackupUrl(),
            settings.getBackupUser(),
            settings.getBackupPassword()
        )

        val upload = client.upload(archive, archive.name)
        return@withContext upload.fold(
            onSuccess = {
                settings.setLastBackup(System.currentTimeMillis(), archive.name)
                pruneRemote(client)
                val size = archive.length()
                // Staged copies are only insurance against a failed upload; drop them once the
                // real one has landed.
                clearLocal()
                BackupOutcome.Uploaded(archive.name, size)
            },
            onFailure = { error ->
                keepRecentLocal()
                Log.w(TAG, "Upload failed, archive kept locally: ${error.message}")
                BackupOutcome.SavedLocally(archive, error.message ?: "Upload failed.")
            }
        )
    }

    /** Copies the newest archive out to wherever the user picked (Downloads, Drive, anywhere). */
    suspend fun exportTo(target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = dao.getAllOnce()
            if (entries.isEmpty()) throw Exception("Nothing saved yet.")
            val stream = context.contentResolver.openOutputStream(target)
                ?: throw Exception("Couldn't open that location for writing.")
            stream.use { out -> BackupArchive.write(entries, out) }
            // write() returns the manifest; the caller only needs success or failure.
            Unit
        }
    }

    /** Restores from a file the user picked. Replaces everything currently saved. */
    suspend fun restoreFrom(source: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val stream = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
            ?: return@withContext Result.failure(Exception("Couldn't open that file."))

        stream.use { input ->
            BackupArchive.read(context, input).mapCatching { preview ->
                applyRestore(preview)
            }
        }
    }

    /** Restores the newest archive held on the server. */
    suspend fun restoreFromServer(fileName: String): Result<Int> = withContext(Dispatchers.IO) {
        if (!settings.backupConfigured()) {
            return@withContext Result.failure(Exception("No backup server configured."))
        }
        val client = WebDavClient(
            settings.getBackupUrl(),
            settings.getBackupUser(),
            settings.getBackupPassword()
        )
        client.download(fileName).mapCatching { stream ->
            stream.use { input ->
                BackupArchive.read(context, input).getOrThrow().let { applyRestore(it) }
            }
        }
    }

    suspend fun listServerBackups(): Result<List<RemoteBackup>> = withContext(Dispatchers.IO) {
        if (!settings.backupConfigured()) {
            return@withContext Result.failure(Exception("No backup server configured."))
        }
        WebDavClient(
            settings.getBackupUrl(),
            settings.getBackupUser(),
            settings.getBackupPassword()
        ).list()
    }

    suspend fun testConnection(url: String, user: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) { WebDavClient(url, user, password).testConnection() }

    /**
     * Replace rather than merge. For a single-user collection there is no meaningful conflict to
     * resolve, and merging would quietly create duplicates of every entry on each restore.
     */
    private suspend fun applyRestore(preview: RestorePreview): Int {
        dao.deleteAll()
        dao.insertAll(preview.entries)
        return preview.entries.size
    }

    /** Keeps only the newest local archive; older staged copies are just dead weight. */
    private fun keepRecentLocal() {
        stagingDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(1)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun clearLocal() {
        stagingDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Server-side retention, so a 50 MB archive per save doesn't fill the quota. */
    private fun pruneRemote(client: WebDavClient) {
        val keep = settings.getBackupsToKeep()
        client.list().getOrNull()
            ?.drop(keep)
            ?.forEach { old -> client.delete(old.name) }
    }

    /** The newest archive sitting on the device, if any. */
    fun localArchive(): File? =
        stagingDir.listFiles()?.maxByOrNull { it.lastModified() }

    companion object {
        private const val TAG = "MovementID/Backup"
    }
}
