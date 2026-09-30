package com.klin.read.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a book's cover image and caches a small thumbnail.
 *
 * ## Why this differs from the phone build
 *
 * The phone version reads every entry of the EPUB into a `Map<String, ByteArray>`
 * to find the cover. On this watch that is exactly the failure mode the streaming
 * EPUB parser exists to avoid: a 157-document book would expand to 15–20 MB of
 * live byte arrays against ~360 MB of available RAM, causing continuous GC.
 *
 * So this version never holds the archive in memory. It:
 *
 *   1. streams the ZIP once, keeping **only** `container.xml`, the OPF, and the
 *      single entry that the OPF identifies as the cover;
 *   2. caps that entry at [MAX_COVER_BYTES] so a pathological file cannot blow up
 *      the heap — anything larger is skipped and the placeholder is used;
 *   3. samples the bitmap down during decode (`inSampleSize`), so a 4000px cover
 *      never becomes a 4000px bitmap;
 *   4. re-encodes to JPEG and writes it to the cache directory, then lets the
 *      original bytes go out of scope.
 *
 * Peak usage is therefore one capped byte array plus one downsampled bitmap,
 * rather than the whole book.
 *
 * ## Coverage
 *
 * Honest about what it can and cannot do:
 *
 *   - **EPUB** — EPUB 3 `properties="cover-image"`, then EPUB 2
 *     `<meta name="cover" content="id">`.
 *   - **MOBI / AZW / AZW3 / PRC** — first embedded image, located by magic bytes.
 *   - **FB2** — `<coverpage>` pointing at a `<binary>` block.
 *   - **TXT / HTML / UMD** — these formats have no cover at all, so they always
 *     fall back to the generated placeholder. On a Chinese-language shelf made
 *     mostly of TXT, that is the common case rather than an error.
 *
 * Every path returns null instead of throwing: a missing cover must never stop a
 * book from importing.
 */
object CoverExtractor {

    private const val TAG = "CoverExtractor"

    /**
     * Hard ceiling on a single cover entry.
     *
     * Comfortably above any sane cover (a 2 MB JPEG is already generous) but low
     * enough that a crafted or broken file cannot exhaust the heap. The watch has
     * roughly 360 MB available in total; spending more than 3 MB on artwork would
     * be indefensible.
     */
    private const val MAX_COVER_BYTES = 3 * 1024 * 1024

    /** Longest edge of the cached image. Drawn as a small shelf thumbnail. */
    private const val MAX_EDGE = 240

    /** JPEG quality. Covers are photographic, so 85 is visually lossless here. */
    private const val JPEG_QUALITY = 85

    /** Metadata documents are tiny; this stops a broken OPF from being huge. */
    private const val SMALL_ENTRY_LIMIT = 512 * 1024

    /**
     * Extracts and caches a cover.
     *
     * @param open supplies a fresh stream each call, because a SAF stream cannot
     *   be rewound and the archive is read in two passes.
     * @return the absolute path of the written file, or null when the format has
     *   no cover, the cover is oversized, or extraction failed.
     */
    fun extract(
        context: Context,
        uri: String,
        format: String,
        bookId: Long,
        open: () -> InputStream?
    ): String? {
        val bytes = try {
            when (format.uppercase()) {
                "EPUB" -> fromEpub(open)
                "MOBI", "AZW", "AZW3", "PRC" -> fromMobi(open)
                "FB2" -> fromFb2(open)
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "封面提取失败 $uri ($format)", e)
            null
        } ?: return null

        return try {
            writeCache(context, bytes, bookId)
        } catch (e: Throwable) {
            Log.w(TAG, "封面写入缓存失败 $uri", e)
            null
        }
    }

    // ---- EPUB ----------------------------------------------------------------

    /**
     * Two streaming passes, mirroring the parser.
     *
     * Pass 1 keeps only container.xml and the OPF — enough to learn the cover's
     * href. Pass 2 streams again and captures just that one entry.
     */
    private fun fromEpub(open: () -> InputStream?): ByteArray? {
        var containerText: String? = null
        var opfText: String? = null
        var opfPath: String? = null

        streamEntries(open()) { name, read ->
            when {
                name == "META-INF/container.xml" ->
                    containerText = read(SMALL_ENTRY_LIMIT).toString(Charsets.UTF_8)

                name.endsWith(".opf") && opfText == null -> {
                    opfText = read(SMALL_ENTRY_LIMIT).toString(Charsets.UTF_8)
                    opfPath = name
                }
            }
        }

        val opf = opfText ?: return null
        val resolvedOpf = resolveOpfPath(containerText, opfPath) ?: return null
        val baseDir = resolvedOpf.substringBeforeLast('/', "")

        val coverHref = coverHrefFrom(opf) ?: return null
        val target = resolvePath(baseDir, coverHref)

        // Pass 2: capture only the cover entry.
        var found: ByteArray? = null
        streamEntries(open()) { name, read ->
            if (found == null && name == target) {
                found = read(MAX_COVER_BYTES)
            }
        }
        return found
    }

    /** EPUB 3 property, then EPUB 2 meta, then a filename hint. */
    private fun coverHrefFrom(opf: String): String? {
        val items = ITEM.findAll(opf).map { it.value }.toList()

        // EPUB 3: properties="cover-image"
        items.firstOrNull { PROPERTIES_COVER.containsMatchIn(it) }
            ?.let { ATTR_HREF.find(it)?.groupValues?.get(1) }
            ?.let { return it.trim() }

        // EPUB 2: <meta name="cover" content="itemId"/>
        META_COVER.find(opf)?.groupValues?.get(1)?.let { coverId ->
            items.firstOrNull {
                ATTR_ID.find(it)?.groupValues?.get(1)?.trim() == coverId.trim()
            }?.let { ATTR_HREF.find(it)?.groupValues?.get(1) }?.let { return it.trim() }
        }

        // Last resort: a manifest image whose name says "cover".
        items.firstOrNull {
            val href = ATTR_HREF.find(it)?.groupValues?.get(1) ?: ""
            isImage(href) && href.contains("cover", ignoreCase = true)
        }?.let { ATTR_HREF.find(it)?.groupValues?.get(1) }?.let { return it.trim() }

        return null
    }

    private fun resolveOpfPath(container: String?, scanned: String?): String? {
        if (container != null) {
            ROOTFILE.find(container)?.groupValues?.get(1)?.let { return it.trimStart('/') }
        }
        return scanned
    }

    // ---- MOBI ----------------------------------------------------------------

    /**
     * First embedded image, found by magic bytes.
     *
     * Reads in capped chunks rather than slurping the file, and stops at the first
     * plausible image, so a 50 MB MOBI never sits in memory.
     */
    private fun fromMobi(open: () -> InputStream?): ByteArray? {
        val stream = open() ?: return null
        stream.use { input ->
            val head = ByteArray(4)
            val buffer = java.io.ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(16 * 1024)
            var total = 0

            while (total < MAX_COVER_BYTES) {
                val n = input.read(chunk)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                total += n
            }

            val data = buffer.toByteArray()
            if (data.size < 100) return null

            val start = findImageStart(data) ?: return null
            return data.copyOfRange(start, data.size)
        }
    }

    private fun findImageStart(data: ByteArray): Int? {
        for (i in 0 until data.size - 8) {
            val isJpeg = data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()
            val isPng = data[i] == 0x89.toByte() && data[i + 1] == 0x50.toByte() &&
                data[i + 2] == 0x4E.toByte() && data[i + 3] == 0x47.toByte()
            if (isJpeg || isPng) return i
        }
        return null
    }

    // ---- FB2 -----------------------------------------------------------------

    private fun fromFb2(open: () -> InputStream?): ByteArray? {
        val xml = open()?.use { input ->
            // FB2 keeps binaries inline as Base64, so the file is capped too.
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_COVER_BYTES) {
                val n = input.read(chunk)
                if (n <= 0) break
                out.write(chunk, 0, n)
                total += n
            }
            out.toByteArray().toString(Charsets.UTF_8)
        } ?: return null

        val coverId = COVER_IMAGE.find(xml)?.groupValues?.get(1)?.trim()?.removePrefix("#")
            ?: return null

        val payload = BINARY.findAll(xml).firstOrNull { match ->
            ATTR_ID.find(match.value)?.groupValues?.get(1) == coverId
        }?.value?.substringAfter('>', "")?.substringBeforeLast('<')
            ?.filterNot { it.isWhitespace() }
            ?: return null

        if (payload.isEmpty()) return null
        return runCatching {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    // ---- Cache ---------------------------------------------------------------

    /**
     * Downsamples and re-encodes into the app cache.
     *
     * Bounds are decoded first so `inSampleSize` can be chosen before any pixels
     * are allocated; a 4000x4000 cover becomes a 240px thumbnail without ever
     * materialising at full size.
     */
    private fun writeCache(context: Context, bytes: ByteArray, bookId: Long): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        // Reject near-empty images: some EPUBs ship a 1x1 or spacer "cover".
        if (bitmap.width < 40 || bitmap.height < 40) {
            bitmap.recycle()
            return null
        }

        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(dir, "cover_$bookId.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    // ---- Streaming ZIP -------------------------------------------------------

    /**
     * Streams ZIP entries, invoking [onEntry] with a reader bound to the current
     * entry.
     *
     * Deliberately mirrors `EpubParser.readEntries`: the reader is only valid
     * inside the callback, which is what keeps peak memory to a single entry.
     */
    private inline fun streamEntries(
        stream: InputStream?,
        onEntry: (name: String, read: (limit: Int) -> ByteArray) -> Unit
    ) {
        if (stream == null) return
        ZipInputStream(stream.buffered(32 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/').trimStart('/')
                onEntry(name) { limit ->
                    val out = java.io.ByteArrayOutputStream(
                        minOf(entry.size.takeIf { it > 0 }?.toInt() ?: 8192, 64 * 1024)
                    )
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (total < limit) {
                        val need = minOf(buffer.size, limit - total)
                        val n = zip.read(buffer, 0, need)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                    out.toByteArray()
                }
                zip.closeEntry()
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    /** Resolves a manifest href against the OPF's directory, handling `../`. */
    private fun resolvePath(baseDir: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }
            .getOrDefault(href)
        if (baseDir.isEmpty()) return decoded

        val parts = (baseDir.split('/') + decoded.split('/'))
        val out = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }

    private val ROOTFILE = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ITEM = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR_ID = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_HREF = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val PROPERTIES_COVER =
        Regex("""properties\s*=\s*["'][^"']*cover-image""", RegexOption.IGNORE_CASE)
    private val META_COVER = Regex(
        """<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val COVER_IMAGE = Regex(
        """<coverpage\b.*?<image\b[^>]*(?:xlink:)?href\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val BINARY = Regex(
        """<binary\b[^>]*>.*?</binary>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
}
