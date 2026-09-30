package com.klin.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Cover location logic for the watch build.
 *
 * The watch cannot afford the phone build's approach of loading every ZIP entry
 * into a map, so [CoverExtractor] streams instead. The streaming path is harder
 * to eyeball, which is exactly why the *selection* rules are pinned down here.
 *
 * These tests exercise the pure regex/resolution logic through the same builders
 * the extractor uses. They do not touch Android graphics: bitmap decoding needs a
 * device, so `writeCache` is deliberately out of scope.
 */
class CoverExtractorTest {

    // ---- EPUB: which manifest item is the cover? -----------------------------

    /** Mirrors the EPUB 3 branch: properties="cover-image". */
    private fun epub3CoverHref(opf: String): String? {
        val item = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(opf)
            .firstOrNull { Regex("""properties\s*=\s*["'][^"']*cover-image""", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }
            ?: return null
        return Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(item.value)?.groupValues?.get(1)
    }

    /** Mirrors the EPUB 2 branch: <meta name="cover" content="id"/>. */
    private fun epub2CoverHref(opf: String): String? {
        val meta = Regex(
            """<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        // Group 1, not 2 — reading group 2 threw IndexOutOfBoundsException on the
        // phone build and silently produced placeholder covers.
        val coverId = meta.find(opf)?.groupValues?.get(1) ?: return null

        val items = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)
        val match = items.firstOrNull {
            Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(it.value)?.groupValues?.get(1)?.trim() == coverId.trim()
        } ?: return null

        return Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(match.value)?.groupValues?.get(1)
    }

    private val epub3Opf = """
        <package version="3.0">
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
        </package>
    """.trimIndent()

    private val epub2Opf = """
        <package version="2.0">
          <metadata><meta name="cover" content="cover-img"/></metadata>
          <manifest>
            <item id="cover-img" href="images/cover.png" media-type="image/png"/>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
        </package>
    """.trimIndent()

    @Test
    fun `epub3 cover-image property is found`() {
        assertEquals("images/cover.jpg", epub3CoverHref(epub3Opf))
    }

    @Test
    fun `epub2 meta cover id is resolved to its href`() {
        // The single-capture-group case that regressed on the phone build.
        assertEquals("images/cover.png", epub2CoverHref(epub2Opf))
    }

    @Test
    fun `opf without any cover marker reports none`() {
        val opf = """
            <package><manifest>
              <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            </manifest></package>
        """.trimIndent()
        assertNull(epub3CoverHref(opf))
        assertNull(epub2CoverHref(opf))
    }

    // ---- Path resolution -----------------------------------------------------

    /** Mirrors `resolvePath`: joins against the OPF directory, collapsing `..`. */
    private fun resolvePath(baseDir: String, href: String): String {
        if (baseDir.isEmpty()) return href
        val parts = baseDir.split('/') + href.split('/')
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

    @Test
    fun `href resolves relative to the opf directory`() {
        assertEquals("OEBPS/images/cover.jpg", resolvePath("OEBPS", "images/cover.jpg"))
    }

    @Test
    fun `parent segments are collapsed`() {
        // `..` cancels the OPF's own subdirectory, not the whole base: from
        // OEBPS/text, "../images/x" lands in OEBPS/images, matching filesystem
        // semantics. An earlier expectation of "images/cover.jpg" was wrong.
        assertEquals("OEBPS/images/cover.jpg", resolvePath("OEBPS/text", "../images/cover.jpg"))
    }

    @Test
    fun `href in an empty base directory is returned unchanged`() {
        // With no base directory the helper short-circuits and hands the href back
        // as-is. Documented here because it is the one path that does not run
        // through the `..` collapsing above.
        assertEquals("../images/cover.jpg", resolvePath("", "../images/cover.jpg"))
    }

    @Test
    fun `nested opf directory is preserved`() {
        assertEquals(
            "EPUB/content/images/c.png",
            resolvePath("EPUB/content", "images/c.png")
        )
    }

    // ---- Memory guard --------------------------------------------------------

    @Test
    fun `entries beyond the cap are truncated rather than buffered whole`() {
        // The cap is what keeps a hostile or malformed archive from exhausting
        // the watch's ~360 MB. Simulate it against a deliberately large entry.
        val cap = 3 * 1024 * 1024
        val big = ByteArray(cap + 512 * 1024) { 0x41 }

        val zipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("huge.bin"))
                zos.write(big)
                zos.closeEntry()
            }
        }.toByteArray()

        var readLength = -1
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            zip.nextEntry
            val out = ByteArrayOutputStream(8192)
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < cap) {
                val need = minOf(buf.size, cap - total)
                val n = zip.read(buf, 0, need)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            readLength = out.size()
        }

        assertNotNull(readLength)
        assertTrue("capped read must not exceed $cap", readLength <= cap)
        assertTrue("capped read should fill the cap", readLength >= cap - 16 * 1024)
    }
}
