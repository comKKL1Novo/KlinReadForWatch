package com.klin.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for format detection.
 *
 * The detector reads a fixed 4096-byte window and decides the format from it. That
 * window is a byte count, so for Chinese text (three bytes per character in UTF-8)
 * it lands mid-character about two times in three, and the truncated tail makes
 * the UTF-8 validation fail -- rejecting an ordinary novel as an unrecognised
 * format.
 *
 * These tests exist because the fix for that was ported here from the phone build
 * and initially verified only by hand, outside the repository. An unverified port
 * is exactly the kind of thing that silently rots: the phone build's own first
 * attempt at this fix handled only one of the two truncation shapes and still
 * rejected the real file.
 */
class FormatDetectorTest {

    /** A 4096-byte window with a chopped three-byte character at the end. */
    @Test
    fun detectsChineseTextEvenWhenTheWindowCutsACharacter() {
        val filler = "测试正文".repeat(400)
        val bytes = filler.toByteArray(Charsets.UTF_8)
        assertTrue("fixture should exceed the sniff window", bytes.size > 4096)

        val window = bytes.copyOf(4096)
        assertEquals(BookFormat.TXT, FormatDetector.detect(window.inputStream(), "txt"))
    }

    @Test
    fun detectsChineseTextWithoutAnExtensionHint() {
        val bytes = "中文小说正文内容".repeat(400).toByteArray(Charsets.UTF_8)
        val window = bytes.copyOf(4096)
        assertEquals(BookFormat.TXT, FormatDetector.detect(window.inputStream(), null))
    }

    /**
     * The shape a real device file hit.
     *
     * The 4096-byte window ends exactly on the LEAD byte of a three-byte character,
     * with neither continuation byte present. Handling only a truncated
     * continuation run was not enough: the real file still decoded as unrecognised
     * until the bare-lead case was handled too.
     *
     * The real file's byte 4095 was `0xE7`; this fixture uses 中 (`0xE4`) because it
     * is easier to repeat, and the code path is identical -- any three-byte lead
     * whose continuations are missing.
     */
    @Test
    fun detectsChineseWhenTheWindowEndsOnABareLeadByte() {
        // Pure three-byte characters, no ASCII prefix: 4095 is then a multiple of
        // 3 and therefore the FIRST byte of a character rather than a continuation
        // byte.
        val bytes = "中".repeat(2000).toByteArray(Charsets.UTF_8)

        // 中 is E4 B8 AD in UTF-8, so a lead byte here is E4.
        assertEquals("fixture must truncate on a lead byte", 0xE4, bytes[4095].toInt() and 0xFF)

        val window = bytes.copyOf(4096)
        assertEquals(BookFormat.TXT, FormatDetector.detect(window.inputStream(), null))
        assertEquals(
            "a title with no extension must still resolve from the bytes",
            BookFormat.TXT,
            FormatDetector.detect(window.inputStream(), "单章测试")
        )
    }

    /** A complete character at the boundary is left alone. */
    @Test
    fun doesNotTrimAlignedText() {
        val bytes = "中".repeat(1000).toByteArray(Charsets.UTF_8)
        assertEquals(0, bytes.size % 3)
        assertEquals(BookFormat.TXT, FormatDetector.detect(bytes.inputStream(), null))
    }

    @Test
    fun detectsZipAsEpub() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "rest".toByteArray()
        assertEquals(BookFormat.EPUB, FormatDetector.detect(zip.inputStream(), null))
    }

    @Test
    fun detectsHtml() {
        val html = "<!DOCTYPE html><html><body>hi</body></html>".toByteArray()
        assertEquals(BookFormat.HTML, FormatDetector.detect(html.inputStream(), null))
    }

    @Test
    fun detectsFb2() {
        val fb2 = """<?xml version="1.0"?><FictionBook><body/></FictionBook>""".toByteArray()
        assertEquals(BookFormat.FB2, FormatDetector.detect(fb2.inputStream(), null))
    }

    @Test
    fun detectsUmdFromItsBom() {
        val umd = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x55, 0x00, 0x4D, 0x00, 0x44, 0x00)
        assertEquals(BookFormat.UMD, FormatDetector.detect(umd.inputStream(), null))
    }

    /** A binary blob with NULs is not a book. */
    @Test
    fun rejectsBinaryContent() {
        val binary = ByteArray(512) { if (it % 7 == 0) 0 else (it % 256).toByte() }
        assertNull(FormatDetector.detect(binary.inputStream(), null))
    }

    @Test
    fun rejectsAnEmptyStream() {
        assertNull(FormatDetector.detect(ByteArray(0).inputStream(), null))
    }

    /** ASCII is unaffected: no continuation bytes, nothing to trim. */
    @Test
    fun detectsPlainAscii() {
        val ascii = "A plain English novel. ".repeat(300).toByteArray()
        assertEquals(BookFormat.TXT, FormatDetector.detect(ascii.copyOf(4096).inputStream(), "txt"))
    }

    /** The extension still wins when the bytes are ambiguous. */
    @Test
    fun extensionHintBreaksTies() {
        val xml = """<?xml version="1.0"?><root/>""".toByteArray()
        assertEquals(BookFormat.TXT, FormatDetector.detect(xml.inputStream(), "txt"))
    }

    /**
     * The validation `TextDecoder` itself relies on.
     *
     * Pinned here because the whole format-detection fix depends on it rejecting a
     * truncated buffer -- if this ever started accepting partial characters the
     * alignment work would be silently unnecessary, and if it started rejecting
     * complete ones the detector would break differently.
     */
    @Test
    fun utf8ValidationRejectsATruncatedCharacter() {
        val complete = "中文".toByteArray(Charsets.UTF_8)
        assertTrue("complete characters must validate", TextDecoder.isValidUtf8(complete))

        val truncated = complete.copyOf(complete.size - 1)
        assertTrue(
            "a buffer cut mid-character must NOT validate; the alignment step depends on this",
            !TextDecoder.isValidUtf8(truncated)
        )
    }
}
