package app.bear.store.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `newer patch version is detected`() {
        assertTrue(VersionUtils.isVersionNewer("21.26.360", "21.26.359"))
    }

    @Test
    fun `newer minor version is detected`() {
        assertTrue(VersionUtils.isVersionNewer("9.27.0", "9.26.51"))
    }

    @Test
    fun `newer major version is detected`() {
        assertTrue(VersionUtils.isVersionNewer("22.0.0", "21.26.360"))
    }

    @Test
    fun `identical versions are not newer`() {
        assertFalse(VersionUtils.isVersionNewer("9.26.51", "9.26.51"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(VersionUtils.isVersionNewer("21.25.10", "21.26.1"))
    }

    @Test
    fun `shorter version string wins on higher earlier component`() {
        // 21.27 vs 21.26.360 -> missing trailing component treated as 0
        assertTrue(VersionUtils.isVersionNewer("21.27", "21.26.360"))
    }

    @Test
    fun `longer version string with extra trailing zero is not newer`() {
        assertFalse(VersionUtils.isVersionNewer("21.26.0", "21.26"))
    }

    @Test
    fun `blank config version is never newer`() {
        assertFalse(VersionUtils.isVersionNewer("", "1.0.0"))
    }

    @Test
    fun `blank installed version is never newer`() {
        assertFalse(VersionUtils.isVersionNewer("1.0.0", ""))
    }

    @Test
    fun `non-numeric segments are treated as digit-stripped values`() {
        // "v1" -> digits-only "1"; validates tolerance for stray prefix characters
        assertTrue(VersionUtils.isVersionNewer("v2.0", "v1.9"))
    }

    @Test
    fun `full release outranks pre-release with same numeric core`() {
        assertTrue(VersionUtils.isVersionNewer("1.3.0", "1.3.0-beta"))
    }

    @Test
    fun `pre-release is not newer than the full release it precedes`() {
        assertFalse(VersionUtils.isVersionNewer("1.3.0-beta", "1.3.0"))
    }

    @Test
    fun `two pre-releases with same numeric core are not newer`() {
        assertFalse(VersionUtils.isVersionNewer("1.3.0-beta", "1.3.0-rc"))
    }
}
