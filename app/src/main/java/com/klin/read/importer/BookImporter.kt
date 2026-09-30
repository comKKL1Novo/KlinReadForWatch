package com.klin.read.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.klin.read.data.BookDao
import com.klin.read.data.BookEntity
import com.klin.read.reader.BookParser
import com.klin.read.reader.CoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a user-picked document URI into a shelf entry.
 *
 * Access to the file comes from the Storage Access Framework, and the permission
 * granted by the picker is persisted so the book still opens after a restart.
 * That is why the app declares no storage permission at all.
 */
class BookImporter(
    private val context: Context,
    private val bookDao: BookDao
) {

    /** Metadata a content provider can tell us before reading the file. */
    data class DocumentInfo(val displayName: String, val sizeBytes: Long)

    /**
     * Imports [uri], returning the new row id, or the existing one when the same
     * document was already on the shelf.
     *
     * Throws [BookParser.UnsupportedFormatException] for formats we cannot read,
     * and [java.io.IOException] when the file itself cannot be opened.
     */
    /**
     * Imports [uri] on the IO dispatcher.
     *
     * [onProgress] receives a 0f..1f fraction so the shelf can show a progress
     * bar. Importing a long book takes several seconds on a watch, and without
     * feedback it looks like the app has frozen.
     */
    suspend fun import(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        // Re-importing the same file should refresh it, not duplicate it.
        bookDao.findByUri(uri.toString())?.let { existing ->
            bookDao.touch(existing.id)
            onProgress(1f)
            return@withContext existing.id
        }

        onProgress(0.05f)
        val info = queryDocumentInfo(uri)

        onProgress(0.15f)
        val parsed = BookParser.parse(context, uri, info.displayName)
        onProgress(0.85f)

        // The format label comes from the display name only. An earlier version
        // reopened the file to sniff its magic bytes, which cost a second full
        // read of the book on a device where I/O is the slowest part.
        val format = info.displayName.substringAfterLast('.', "")
            .uppercase()
            .takeIf { it.isNotEmpty() }
            ?: "TXT"

        val book = BookEntity(
            title = parsed.title,
            uri = uri.toString(),
            format = format,
            sizeBytes = info.sizeBytes,
            charCount = parsed.charCount,
            lastOpenedAt = System.currentTimeMillis()
        )

        val id = bookDao.insert(book)
        onProgress(0.92f)

        // Covers are cached after the row exists, because the cache file is named
        // after the id. This is a streaming extraction capped at 3 MB, so it adds
        // one small image to peak memory rather than a second copy of the book.
        // Failure is swallowed on purpose: the shelf falls back to a generated
        // placeholder, and a book must never fail to import over artwork.
        val cover = CoverExtractor.extract(
            context = context,
            uri = uri.toString(),
            format = format,
            bookId = id,
            open = { runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() }
        )
        if (cover != null) bookDao.setCover(id, cover)

        onProgress(1f)
        id
    }

    /**
     * Reads DISPLAY_NAME and SIZE from the provider.
     *
     * Both columns are optional for a generic provider, so every field falls back
     * to a sensible default instead of failing the import.
     */
    private fun queryDocumentInfo(uri: Uri): DocumentInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)?.let { name = it }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        return DocumentInfo(displayName = name, sizeBytes = size)
    }
}
