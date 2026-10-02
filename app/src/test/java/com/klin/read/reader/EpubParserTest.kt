package com.klin.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds EPUB archives in memory so the parser can be exercised without fixture
 * files.
 */
private fun buildEpub(
    opf: String = DEFAULT_OPF,
    container: String? = DEFAULT_CONTAINER,
    extra: Map<String, String> = emptyMap()
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        zip.putNextEntry(ZipEntry("mimetype"))
        zip.write("application/epub+zip".toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        if (container != null) put("META-INF/container.xml", container)
        extra.forEach { (k, v) -> put(k, v) }
        put("OEBPS/content.opf", opf)
    }
    return out.toByteArray()
}

private val DEFAULT_CONTAINER = """
    <?xml version="1.0"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
      </rootfiles>
    </container>
""".trimIndent()

private val DEFAULT_OPF = """
    <?xml version="1.0"?>
    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>测试书</dc:title>
        <dc:creator>作者</dc:creator>
      </metadata>
      <manifest>
        <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
        <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
      </manifest>
      <spine>
        <itemref idref="c1"/>
        <itemref idref="c2"/>
      </spine>
    </package>
""".trimIndent()

/**
 * Tests the streaming EPUB parser.
 *
 * The parser was rewritten to read the archive in two passes rather than holding
 * every ZIP entry in memory, so these cases guard the behaviour that rewrite
 * could plausibly have broken: reading order, titles, path resolution, and the
 * text cleanup.
 */
class EpubParserTest {

    private fun parse(bytes: ByteArray, fallback: String = "回退名"): ParsedBook =
        EpubParser.parse({ bytes.inputStream() }, fallback)

    @Test
    fun `reads title and spine order`() {
        val bytes = buildEpub(extra = mapOf(
            "OEBPS/c1.xhtml" to "<html><body><h1>第一章</h1><p>内容甲</p></body></html>",
            "OEBPS/c2.xhtml" to "<html><body><h1>第二章</h1><p>内容乙</p></body></html>"
        ))

        val book = parse(bytes)

        assertEquals("测试书", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章", book.chapters[0].title)
        assertEquals("第二章", book.chapters[1].title)
        assertTrue(book.text.contains("内容甲"))
        assertTrue(book.text.contains("内容乙"))
    }

    @Test
    fun `falls back to the file name when the package has no title`() {
        val opf = DEFAULT_OPF.replace("<dc:title>测试书</dc:title>", "")
        val bytes = buildEpub(
            opf = opf,
            extra = mapOf("OEBPS/c1.xhtml" to "<html><body><p>甲</p></body></html>")
        )

        assertEquals("我的文件", parse(bytes, "我的文件").title)
    }

    @Test
    fun `strips markup and keeps paragraph breaks`() {
        val bytes = buildEpub(extra = mapOf(
            "OEBPS/c1.xhtml" to "<html><body><p>第一段</p><p>第二段</p></body></html>"
        ))

        val book = parse(bytes)

        assertFalse("tags must be stripped", book.text.contains("<p>"))
        assertTrue(book.text.contains("第一段"))
        assertTrue(book.text.contains("第二段"))
        assertTrue("paragraphs should be separated", book.text.contains("\n"))
    }

    @Test
    fun `drops script and style content`() {
        val bytes = buildEpub(extra = mapOf(
            "OEBPS/c1.xhtml" to """
                <html><head><style>p{color:red}</style></head>
                <body><script>var x=1;</script><p>正文</p></body></html>
            """.trimIndent()
        ))

        val book = parse(bytes)

        assertTrue(book.text.contains("正文"))
        assertFalse("script must not leak", book.text.contains("var x"))
        assertFalse("style must not leak", book.text.contains("color:red"))
    }

    @Test
    fun `decodes html entities`() {
        val bytes = buildEpub(extra = mapOf(
            "OEBPS/c1.xhtml" to "<html><body><p>&lt;龙族&gt; &amp; &ldquo;言灵&rdquo;</p></body></html>"
        ))

        val book = parse(bytes)

        assertTrue(book.text.contains("<龙族>"))
        assertTrue(book.text.contains("&"))
        assertTrue(book.text.contains("“言灵”"))
    }

    @Test
    fun `resolves content in nested folders`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>嵌套</dc:title>
              </metadata>
              <manifest>
                <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()

        val bytes = buildEpub(opf = opf, extra = mapOf(
            "OEBPS/text/c1.xhtml" to "<html><body><p>深层内容</p></body></html>"
        ))

        val book = parse(bytes, "t")
        assertTrue("nested content must be found", book.text.contains("深层内容"))
    }

    @Test
    fun `chapter ranges are contiguous and cover the text`() {
        val bytes = buildEpub(extra = mapOf(
            "OEBPS/c1.xhtml" to "<html><body><p>甲</p></body></html>",
            "OEBPS/c2.xhtml" to "<html><body><p>乙</p></body></html>"
        ))

        val book = parse(bytes, "t")

        assertEquals(0, book.chapters.first().start)
        assertEquals(book.text.length, book.chapters.last().end)
        book.chapters.zipWithNext().forEach { (a, b) ->
            assertEquals("chapters must not gap or overlap", a.end, b.start)
        }
    }

    @Test
    fun `reports a clear error when there is no opf`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("random.txt"))
            zip.write("nope".toByteArray())
            zip.closeEntry()
        }

        val ex = runCatching { parse(out.toByteArray(), "t") }.exceptionOrNull()

        assertTrue(
            "expected InvalidEpubException, got $ex",
            ex is EpubParser.InvalidEpubException
        )
    }

    /**
     * The streaming rewrite reads content documents in a second pass, so a book
     * whose spine is much larger than the metadata must still parse completely.
     */
    @Test
    fun `handles many spine documents in one pass`() {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val extra = mutableMapOf<String, String>()

        repeat(40) { i ->
            manifest.append("""<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="c$i"/>""")
            extra["OEBPS/c$i.xhtml"] = "<html><body><p>段落$i</p></body></html>"
        }

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>多章</dc:title></metadata>
              <manifest>$manifest</manifest>
              <spine>$spine</spine>
            </package>
        """.trimIndent()

        val book = parse(buildEpub(opf = opf, extra = extra), "t")

        assertEquals(40, book.chapters.size)
        assertTrue(book.text.contains("段落0"))
        assertTrue(book.text.contains("段落39"))
    }
}

