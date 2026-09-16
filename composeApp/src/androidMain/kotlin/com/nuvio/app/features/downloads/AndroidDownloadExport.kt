package com.nuvio.app.features.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object AndroidDownloadExport {
    private const val TAG = "NuvioDownloadExport"
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context
        get() = checkNotNull(appContext) { "Download export is not initialized" }

    fun isContentUri(uri: String): Boolean = uri.startsWith("content:", ignoreCase = true)

    fun writableFolder(treeUri: String): DocumentFile? = runCatching {
        val uri = Uri.parse(treeUri)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) return@runCatching null
        DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
    }.onFailure { Log.w(TAG, "Download location is not accessible", it) }.getOrNull()

    fun sink(uri: String): DocumentDownloadSink = DocumentDownloadSink(context, Uri.parse(uri))

    fun createPartial(folder: DocumentFile, fileName: String): Uri? = runCatching {
        folder.createFile("application/octet-stream", "$fileName.part")
    }.onFailure { Log.w(TAG, "Could not create partial download", it) }.getOrNull()?.uri

    fun probeDirectWrite(uri: Uri): Boolean = runCatching {
        val renamable = context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null,
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        } == true
        if (!renamable) return@runCatching false
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            if (pfd.statSize < 0L) return@use false
            java.io.FileOutputStream(pfd.fileDescriptor).channel.let { channel ->
                channel.position(0L)
                channel.size() >= 0L
            }
        } == true
    }.onFailure { Log.i(TAG, "Provider does not support direct downloads", it) }.getOrDefault(false)

    fun finalizePartial(partialUri: String, fileName: String): Uri {
        val uri = Uri.parse(partialUri)
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var lastError: Exception? = null
        for (attempt in 0..32) {
            val candidate = if (attempt == 0) fileName else "$base ($attempt)$extension"
            try {
                DocumentsContract.renameDocument(context.contentResolver, uri, candidate)?.let { return it }
            } catch (error: SecurityException) {
                throw IOException("Access to the selected download location was revoked", error)
            } catch (error: Exception) {
                lastError = error
                if (!exists(partialUri)) break
            }
        }
        throw IOException("Could not finalize the file in the selected download location", lastError)
    }

    suspend fun copyInto(folder: DocumentFile, source: File): Uri = withContext(Dispatchers.IO) {
        val extension = source.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val target = folder.createFile(mimeType, source.name)
            ?: throw IOException("Could not create the file in the selected download location")
        try {
            val output = context.contentResolver.openOutputStream(target.uri, "w")
                ?: throw IOException("Could not open the selected download location")
            output.use { out ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size == -1) break
                        out.write(buffer, 0, size)
                    }
                    out.flush()
                }
            }
            if (target.length() != source.length()) {
                throw IOException("Copy to the selected download location was incomplete")
            }
            target.uri
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
    }

    fun exists(uri: String): Boolean = runCatching {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
    }.getOrDefault(false)

    fun delete(uri: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
    }.getOrDefault(false)

    fun subtitleDirectory(contentUri: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(contentUri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "download-subtitles"), "$key.subtitles")
    }

    fun moveSubtitles(privateVideo: File, contentUri: String) {
        val from = File(privateVideo.path + ".subtitles")
        if (!from.isDirectory) return
        val to = subtitleDirectory(contentUri)
        to.deleteRecursively()
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }

    fun treeDocumentUri(treeUri: String): Uri? = runCatching {
        val uri = Uri.parse(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()
}
