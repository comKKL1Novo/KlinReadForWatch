package com.klin.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {

    @Test
    fun `decodes utf8 without bom`() {
        val original = "第一章 龙族"
        assertEquals(original, TextDecoder.decode(original.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `strips a utf8 bom`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "龙族".toByteArray(Charsets.UTF_8)
        assertEquals("龙族", TextDecoder.decode(bytes))
    }

    @Test
    fun `decodes gb18030 content that is not valid utf8`() {
        val original = "第一章 龙族 江南"
        val gbk = original.toByteArray(Charset.forName("GB18030"))

        assertFalse(
            "GBK bytes for this text should not be valid UTF-8",
            TextDecoder.isValidUtf8(gbk)
        )
        assertEquals(original, TextDecoder.decode(gbk))
    }

    @Test
    fun `validates ascii as utf8`() {
        assertTrue(TextDecoder.isValidUtf8("plain ascii".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `rejects a truncated multi-byte sequence`() {
        assertFalse(TextDecoder.isValidUtf8(byteArrayOf(0xE9.toByte(), 0xBE.toByte())))
    }

    @Test
    fun `handles empty input`() {
        assertEquals("", TextDecoder.decode(ByteArray(0)))
    }
}
