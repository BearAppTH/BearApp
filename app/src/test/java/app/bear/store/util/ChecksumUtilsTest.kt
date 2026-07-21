package app.bear.store.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChecksumUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fileWithContent(content: String): File {
        val file = tempFolder.newFile()
        file.writeText(content)
        return file
    }

    @Test
    fun `sha256Hex matches known digest for empty file`() {
        val file = fileWithContent("")
        // SHA-256 of an empty input, a well-known constant.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ChecksumUtils.sha256Hex(file)
        )
    }

    @Test
    fun `sha256Hex matches known digest for abc`() {
        val file = fileWithContent("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChecksumUtils.sha256Hex(file)
        )
    }

    @Test
    fun `verify returns true when digest is null (nothing to check)`() {
        val file = fileWithContent("anything")
        assertTrue(ChecksumUtils.verify(file, null))
    }

    @Test
    fun `verify returns true when digest is blank`() {
        val file = fileWithContent("anything")
        assertTrue(ChecksumUtils.verify(file, ""))
    }

    @Test
    fun `verify returns true on matching sha256-prefixed digest`() {
        val file = fileWithContent("abc")
        val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(ChecksumUtils.verify(file, digest))
    }

    @Test
    fun `verify is case-insensitive`() {
        val file = fileWithContent("abc")
        val digest = "sha256:BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertTrue(ChecksumUtils.verify(file, digest))
    }

    @Test
    fun `verify returns false on mismatched digest`() {
        val file = fileWithContent("tampered content")
        val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertFalse(ChecksumUtils.verify(file, digest))
    }
}
