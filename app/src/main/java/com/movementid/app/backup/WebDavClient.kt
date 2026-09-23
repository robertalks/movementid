package com.movementid.app.backup

import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class RemoteBackup(val name: String, val size: Long)

/**
 * Talks to Koofr (or any WebDAV server) over plain HTTP basic auth.
 *
 * WebDAV was chosen over Koofr's own API, or Dropbox/Drive, because it needs no OAuth: no
 * browser round-trip, no redirect URI, no refresh tokens to expire at the worst moment. A URL,
 * a username and an app password is the whole of it — and the same code reaches Nextcloud,
 * pCloud, or Dropbox and Google Drive linked into Koofr as pass-through folders.
 *
 * Koofr requires an app password created in Preferences → Password; the account password is
 * rejected.
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String
) {

    /** Normalised to always end in a single slash, since users paste both forms. */
    private val base: String = baseUrl.trim().trimEnd('/') + "/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // uploads can be tens of MB on a slow link
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val auth = Credentials.basic(username, password)

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", auth)

    /** Cheap credential check, used by the "Test connection" button in Settings. */
    fun testConnection(): Result<Unit> = runCatching {
        val response = client.newCall(
            request(base).method("PROPFIND", null).header("Depth", "0").build()
        ).execute()

        val code = response.use { it.code }

        // A missing folder isn't a failed test — create it, so "Test" leaves things ready to
        // use rather than reporting a problem the user then has to fix by hand.
        if (code == 404) {
            ensureFolders().getOrThrow()
            return@runCatching
        }
        if (code !in 200..299) throw Exception(describe(code))
    }

    fun upload(file: File, remoteName: String): Result<Unit> = runCatching {
        fun put(): Int {
            val body: RequestBody = file.asRequestBody("application/zip".toMediaType())
            return client.newCall(request(base + remoteName).put(body).build())
                .execute().use { it.code }
        }

        var code = put()

        // 409 means the parent folder doesn't exist. Creating it is a single MKCOL, which beats
        // making the user create the folder by hand in Koofr first and then wondering why the
        // upload failed.
        if (code == 409) {
            Log.i(TAG, "Target folder missing, creating it")
            ensureFolders().getOrThrow()
            code = put()
        }

        if (code !in 200..299) throw Exception(describe(code))
        Log.i(TAG, "Uploaded $remoteName (${file.length() / 1024} kB)")
        Unit
    }

    /**
     * Creates the target folder, and any missing parents, one MKCOL at a time. WebDAV has no
     * recursive create, and 405 simply means the folder is already there.
     */
    fun ensureFolders(): Result<Unit> = runCatching {
        val root = base.removeSuffix("/")
        val schemeAndHost = Regex("^(https?://[^/]+)").find(root)?.groupValues?.get(1)
            ?: throw Exception("Backup URL doesn't look like a full https:// address.")

        val segments = root.removePrefix(schemeAndHost).trim('/').split("/").filter { it.isNotBlank() }

        var path = schemeAndHost
        segments.forEach { segment ->
            path += "/$segment"
            val code = client.newCall(request("$path/").method("MKCOL", null).build())
                .execute().use { it.code }
            // 201 created, 405 already exists; anything else on the final segment is fatal.
            if (code !in listOf(201, 405, 301, 302)) {
                Log.w(TAG, "MKCOL $path -> $code")
                if (segment == segments.last()) throw Exception(describe(code))
            }
        }
        Unit
    }

    private fun describe(code: Int): String = when (code) {
        401 -> "Rejected (401). Koofr needs an app password from Preferences → Password — " +
            "your account password won't work here."
        403 -> "Forbidden (403). The account can't write to that folder."
        404 -> "Not found (404). Check the folder URL."
        409 -> "Conflict (409). The parent folder doesn't exist and couldn't be created."
        507 -> "Out of space (507) on the server."
        else -> "Server said $code."
    }

    fun download(remoteName: String): Result<InputStream> = runCatching {
        val response = client.newCall(request(base + remoteName).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Download failed (${response.code}).")
        }
        response.body?.byteStream() ?: throw Exception("Empty download.")
    }

    /** Backups in the folder, newest first. */
    fun list(): Result<List<RemoteBackup>> = runCatching {
        val response = client.newCall(
            request(base).method("PROPFIND", null).header("Depth", "1").build()
        ).execute()

        val xml = response.use {
            if (!it.isSuccessful) throw Exception("Listing failed (${it.code}).")
            it.body?.string().orEmpty()
        }

        // Minimal parse: WebDAV replies vary in namespace prefixes, and pulling two fields out
        // of each response block is more robust here than a full XML model.
        RESPONSE_RE.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = HREF_RE.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val name = href.trimEnd('/').substringAfterLast('/')
            if (!name.endsWith(".zip")) return@mapNotNull null
            val size = LENGTH_RE.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            RemoteBackup(name = java.net.URLDecoder.decode(name, "UTF-8"), size = size)
        }.sortedByDescending { it.name }.toList()
    }

    fun delete(remoteName: String): Result<Unit> = runCatching {
        client.newCall(request(base + remoteName).delete().build()).execute().use {
            if (!it.isSuccessful && it.code != 404) throw Exception("Delete failed (${it.code}).")
        }
    }

    companion object {
        private const val TAG = "MovementID/WebDAV"

        /** Koofr's own WebDAV endpoint, offered as the default in Settings. */
        const val KOOFR_DEFAULT = "https://app.koofr.net/dav/Koofr/MovementID/"

        private val RESPONSE_RE =
            Regex("<[a-zA-Z]*:?response[^>]*>(.*?)</[a-zA-Z]*:?response>", RegexOption.DOT_MATCHES_ALL)
        private val HREF_RE = Regex("<[a-zA-Z]*:?href[^>]*>(.*?)</[a-zA-Z]*:?href>", RegexOption.DOT_MATCHES_ALL)
        private val LENGTH_RE =
            Regex("<[a-zA-Z]*:?getcontentlength[^>]*>(\\d+)</[a-zA-Z]*:?getcontentlength>")
    }
}
