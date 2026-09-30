package com.klin.read.reader

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal EPUB reader.
 *
 * Hand-rolled rather than pulled from a toolkit: an EPUB is a ZIP holding an OPF
 * package document, so reading it needs only the ZIP, XML and HTML facilities
 * already on the platform. That keeps dependencies at zero and the behaviour
 * verifiable.
 *
 * ## Memory
 *
 * This version reads the archive twice instead of holding every entry in memory.
 *
 * The previous implementation called `readBytes()` for every entry in the ZIP and
 * kept them all in a map. A 5 MB book with 157 content documents expanded to
 * roughly 15-20 MB of live byte arrays plus their decoded Strings, which is
 * survivable on a phone but not on a watch with ~270 MB available -- it caused
 * near-continuous GC and made import crawl.
 *
 * The two passes are:
 *   1. read only META-INF/container.xml and the OPF (small) to learn the reading
 *      order and chapter titles;
 *   2. stream the content documents one at a time, appending text and discarding
 *      each document's bytes as soon as it has been converted.
 *
 * A single entry is never larger than one chapter, so peak usage is now one
 * chapter rather than the whole book.
 */
object EpubParser {

    class InvalidEpubException(message: String) : Exception(message)

    /** Small enough to hold comfortably; used for the container and the OPF. */
    private const val SMALL_ENTRY_LIMIT = 512 * 1024

    /**
     * Parses the EPUB at [uri].
     *
     * Runs on a stream opener rather than an InputStream so the archive can be
     * read twice; a SAF stream is not rewindable.
     */
    fun parse(
        open: () -> InputStream?,
        fallbackTitle: String
    ): ParsedBook {
        // ---- Pass 1: metadata only ----
        var opfPath: String? = null
        var opfText: String? = null
        var containerText: String? = null

        readEntries(open()) { name, read ->
            when {
                name == "META-INF/container.xml" -> {
                    containerText = read(SMALL_ENTRY_LIMIT).toString(Charsets.UTF_8)
                }
                name.endsWith(".opf") && opfText == null -> {
                    // Only the first OPF is used; a book has exactly one.
                    val bytes = read(SMALL_ENTRY_LIMIT)
                    opfText = bytes.toString(Charsets.UTF_8)
                    opfPath = name
                }
            }
        }

        val opf = opfText ?: throw InvalidEpubException("不是有效的 EPUB：找不到 content.opf")
        val resolvedOpfPath = resolveOpfPath(containerText, opfPath, opfText != null)
            ?: throw InvalidEpubException("不是有效的 EPUB：找不到 content.opf")

        val opfBase = resolvedOpfPath.substringBeforeLast('/', "")
        val title = extractTitle(opf) ?: fallbackTitle

        val manifest = parseManifest(opf)
        val spineIds = parseSpineIds(opf)

        val spinePaths = spineIds.mapNotNull { id ->
            manifest[id]?.let { resolvePath(opfBase, it) }
        }.ifEmpty {
            manifest.values
                .filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
                .map { resolvePath(opfBase, it) }
        }

        if (spinePaths.isEmpty()) throw InvalidEpubException("这本书里没有可读的正文")

        // The navigation document is small and gives real chapter titles.
        var navText: String? = null
        var navBase = ""
        var ncxText: String? = null
        var ncxBase = ""
        val navCandidates = buildSet {
            manifest.values.firstOrNull {
                it.lowercase().endsWith(".xhtml") || it.lowercase().endsWith(".html")
            }?.let { add(resolvePath(opfBase, it)) }
            manifest.values.firstOrNull { it.lowercase().endsWith(".ncx") }
                ?.let { add(resolvePath(opfBase, it)) }
        }

        readEntries(open()) { name, read ->
            when {
                name in navCandidates && name.endsWith(".ncx") ->
                    ncxText = read(SMALL_ENTRY_LIMIT).toString(Charsets.UTF_8).also { ncxBase = name.substringBeforeLast('/', "") }
                name in navCandidates ->
                    navText = read(SMALL_ENTRY_LIMIT).toString(Charsets.UTF_8).also { navBase = name.substringBeforeLast('/', "") }
            }
        }

        val labels = (navText?.let { parseNavLabels(it, navBase) } ?: emptyMap())
            .ifEmpty { ncxText?.let { parseNcxLabels(it, ncxBase) } ?: emptyMap() }

        // ---- Pass 2: content, one document at a time ----
        val wanted = spinePaths.toHashSet()
        val text = StringBuilder()
        val chapters = ArrayList<Chapter>()

        readEntries(open()) { name, read ->
            if (name !in wanted) return@readEntries

            val html = read(Int.MAX_VALUE).toString(Charsets.UTF_8)
            val body = extractBodyText(html)
            if (body.isBlank()) return@readEntries

            // Prefer the navigation label, then a heading in the document itself.
            val index = spinePaths.indexOf(name)
            val label = labels[name]
                ?: labels[name.substringBefore('#')]
                ?: headingFrom(html)
                ?: "第 ${index + 1} 节"

            val start = text.length
            text.append(body.trim())
            text.append("\n\n")
            chapters += Chapter(title = label, start = start, end = text.length)
        }

        if (text.isBlank()) throw InvalidEpubException("这本书里没有可读的正文")

        if (chapters.isNotEmpty()) {
            val last = chapters.last()
            chapters[chapters.size - 1] = last.copy(end = text.length)
        }

        return ParsedBook(title = title, text = text.toString(), chapters = chapters)
    }

    /**
     * Streams entries from [stream], calling [onEntry] with a reader bound to the
     * current entry.
     *
     * The reader is only valid inside the callback, which is what keeps peak
     * memory to a single entry.
     */
    private inline fun readEntries(
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
                    // Read at most `limit` bytes so a hostile or malformed entry
                    // cannot exhaust memory.
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

    // ---- container / OPF ----

    /** Finds the OPF path via container.xml, falling back to the scanned one. */
    private fun resolveOpfPath(
        container: String?,
        scanned: String?,
        hasOpf: Boolean
    ): String? {
        if (container != null) {
            ROOTFILE.find(container)?.groupValues?.get(1)?.let { found ->
                return found.trimStart('/')
            }
        }
        return if (hasOpf) scanned else null
    }

    private fun extractTitle(opf: String): String? =
        DC_TITLE.find(opf)?.groupValues?.get(1)
            ?.let { unescape(it).trim() }
            ?.takeIf { it.isNotEmpty() }

    private fun parseManifest(opf: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ITEM.findAll(opf).forEach { match ->
            val attrs = match.value
            val id = ATTR_ID.find(attrs)?.groupValues?.get(1) ?: return@forEach
            val href = ATTR_HREF.find(attrs)?.groupValues?.get(1) ?: return@forEach
            out[id] = unescape(href).trim()
        }
        return out
    }

    private fun parseSpineIds(opf: String): List<String> {
        val spineBlock = SPINE.findAll(opf).map { it.value }.joinToString(" ")
        return ITEMREF.findAll(spineBlock).mapNotNull { match ->
            ATTR_IDREF.find(match.value)?.groupValues?.get(1)
        }.toList()
    }

    // ---- navigation ----

    private fun parseNavLabels(html: String, base: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ANCHOR.findAll(html).forEach { match ->
            val href = ATTR_HREF.find(match.value)?.groupValues?.get(1) ?: return@forEach
            val label = unescape(TAG_STRIP.replace(match.groupValues[1], "")).trim()
            if (label.isEmpty()) return@forEach
            out.putIfAbsent(resolvePath(base, unescape(href)).substringBefore('#'), label)
        }
        return out
    }

    private fun parseNcxLabels(ncx: String, base: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        NAVPOINT.findAll(ncx).forEach { point ->
            val block = point.value
            val src = ATTR_SRC.find(block)?.groupValues?.get(1) ?: return@forEach
            val label = unescape(
                TAG_STRIP.replace(TEXT_TAG.find(block)?.groupValues?.get(1) ?: "", "")
            ).trim()
            if (label.isEmpty()) return@forEach
            out.putIfAbsent(resolvePath(base, unescape(src)).substringBefore('#'), label)
        }
        return out
    }

    private fun headingFrom(html: String): String? =
        HEADING.find(html)?.groupValues?.get(1)
            ?.let { unescape(TAG_STRIP.replace(it, "")).trim() }
            ?.takeIf { it.isNotEmpty() && it.length <= 60 }

    // ---- HTML -> text ----

    private fun extractBodyText(html: String): String {
        val body = BODY.find(html)?.groupValues?.get(1) ?: html
        val withoutHead = SCRIPT_STYLE.replace(body, " ")
        val withBreaks = BLOCK_BOUNDARY.replace(withoutHead, "\n")
        val stripped = TAG_STRIP.replace(withBreaks, "")

        return unescape(stripped)
            .lineSequence()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ---- helpers ----

    private fun resolvePath(baseDir: String, relative: String): String {
        if (relative.startsWith("/")) return relative.trimStart('/')
        val parts = (if (baseDir.isEmpty()) listOf() else baseDir.split('/')) + relative.split('/')
        val stack = ArrayList<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&amp;", "&")

    private val ROOTFILE = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DC_TITLE = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ITEM = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEMREF = Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SPINE = Regex("""<spine\b[^>]*>.*?</spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ATTR_ID = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_IDREF = Regex("""\bidref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_HREF = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_SRC = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val NAVPOINT = Regex("""<navPoint\b.*?</navPoint>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TEXT_TAG = Regex("""<text[^>]*>(.*?)</text>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEADING = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BODY = Regex("""<body\b[^>]*>(.*?)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_STYLE = Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_BOUNDARY = Regex(
        """</?(p|div|br|h[1-6]|li|tr|blockquote|section|article)\b[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val TAG_STRIP = Regex("""<[^>]+>""")
}

/** Convenience overload for callers that have a resolved stream already. */
fun EpubParser.parse(context: Context, uri: Uri, fallbackTitle: String): ParsedBook =
    parse({ context.contentResolver.openInputStream(uri) }, fallbackTitle)
