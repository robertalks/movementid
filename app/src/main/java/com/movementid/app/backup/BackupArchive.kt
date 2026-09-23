package com.movementid.app.backup

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.movementid.app.data.MovementEntry
import com.movementid.app.data.photoList
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Written into every archive so a restore can tell what it's looking at. */
data class BackupManifest(
    val app: String = "MovementID",
    val schemaVersion: Int = CURRENT_SCHEMA,
    val createdAt: Long = System.currentTimeMillis(),
    val entryCount: Int = 0,
    val photoCount: Int = 0
) {
    companion object {
        /**
         * Bumped whenever MovementEntry changes shape. A restore refuses an archive newer than
         * the app rather than dropping columns it doesn't understand.
         */
        const val CURRENT_SCHEMA = 8
    }
}

data class RestorePreview(
    val manifest: BackupManifest,
    val entries: List<MovementEntry>
)

/**
 * The backup format: one ZIP containing
 *   manifest.json   — what this archive is
 *   movements.json  — every entry, as text
 *   photos/…        — the image files, referenced by name from the entries
 *
 * Deliberately one self-contained file. It's what gets uploaded to Koofr and what gets written
 * locally, so there is only one format to produce, restore and get right — and an archive taken
 * from one destination restores from the other without conversion.
 *
 * API keys are never included. They live in the device keystore, and a backup carrying one would
 * put a usable key in cloud storage; re-entering it on a new phone takes seconds.
 */
object BackupArchive {

    private const val TAG = "MovementID/Backup"
    private const val MANIFEST = "manifest.json"
    private const val ENTRIES = "movements.json"
    private const val PHOTO_DIR = "photos/"

    private val gson = Gson()

    fun fileName(at: Long = System.currentTimeMillis()): String =
        "movementid-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))}.zip"

    /** Writes the archive to [out]. Photos are stored by file name; entries reference them. */
    fun write(entries: List<MovementEntry>, out: OutputStream): BackupManifest {
        val photos = entries.flatMap { it.photoList() }.distinct().map(::File).filter { it.exists() }

        val manifest = BackupManifest(
            entryCount = entries.size,
            photoCount = photos.size
        )

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(gson.toJson(manifest).toByteArray())
            zip.closeEntry()

            // Paths are rewritten to bare file names: absolute paths from the old device are
            // meaningless on the new one.
            val portable = entries.map { entry ->
                entry.copy(
                    photoPath = File(entry.photoPath).name,
                    additionalPhotos = entry.additionalPhotos
                        ?.lines()
                        ?.filter { it.isNotBlank() }
                        ?.joinToString("\n") { File(it).name }
                )
            }
            zip.putNextEntry(ZipEntry(ENTRIES))
            zip.write(gson.toJson(portable).toByteArray())
            zip.closeEntry()

            photos.forEach { photo ->
                zip.putNextEntry(ZipEntry(PHOTO_DIR + photo.name))
                photo.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        Log.i(TAG, "Wrote archive: ${manifest.entryCount} entries, ${manifest.photoCount} photos")
        return manifest
    }

    /**
     * Reads an archive, restoring photos into app storage and returning the entries with their
     * paths pointing at the restored files. Does NOT touch the database — the caller decides
     * whether to replace or merge.
     */
    fun read(context: Context, input: InputStream): Result<RestorePreview> {
        return try {
            var manifest: BackupManifest? = null
            var entries: List<MovementEntry> = emptyList()
            val photoDir = File(context.filesDir, "movements").apply { mkdirs() }
            val restoredPhotos = mutableSetOf<String>()

            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == MANIFEST ->
                            manifest = gson.fromJson(zip.readBytes().decodeToString(), BackupManifest::class.java)

                        entry.name == ENTRIES -> {
                            val type = object : TypeToken<List<MovementEntry>>() {}.type
                            entries = gson.fromJson(zip.readBytes().decodeToString(), type)
                        }

                        entry.name.startsWith(PHOTO_DIR) && !entry.isDirectory -> {
                            // Guard against path traversal from a tampered archive.
                            val name = File(entry.name).name
                            if (name.isNotBlank()) {
                                File(photoDir, name).outputStream().use { zip.copyTo(it) }
                                restoredPhotos += name
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val found = manifest
                ?: return Result.failure(Exception("Not a MovementID backup — no manifest found."))

            if (found.schemaVersion > BackupManifest.CURRENT_SCHEMA) {
                return Result.failure(
                    Exception(
                        "This backup was made by a newer version of MovementID " +
                            "(format ${found.schemaVersion}, this app reads ${BackupManifest.CURRENT_SCHEMA}). " +
                            "Update the app and try again."
                    )
                )
            }

            // Re-point every entry at the restored files, dropping references to photos the
            // archive didn't carry rather than leaving broken paths behind.
            val absolute = entries.map { e ->
                val primary = File(photoDir, File(e.photoPath).name)
                val extras = e.additionalPhotos
                    ?.lines()
                    ?.map { File(it).name }
                    ?.filter { it.isNotBlank() && it in restoredPhotos }
                    ?.joinToString("\n") { File(photoDir, it).absolutePath }
                e.copy(
                    id = 0,                       // let Room assign fresh ids
                    photoPath = primary.absolutePath,
                    additionalPhotos = extras?.takeIf { it.isNotBlank() }
                )
            }

            Log.i(TAG, "Read archive: ${absolute.size} entries, ${restoredPhotos.size} photos")
            Result.success(RestorePreview(found, absolute))
        } catch (e: Exception) {
            Log.w(TAG, "Archive read failed: ${e.message}")
            Result.failure(Exception("Couldn't read that backup: ${e.message}"))
        }
    }
}
