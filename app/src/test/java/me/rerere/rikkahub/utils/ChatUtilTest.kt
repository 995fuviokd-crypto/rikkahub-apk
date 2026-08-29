package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilTest {
    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `allows common code documents`() {
        assertTrue(isAllowedFileType("settings.gradle.kts", "text/plain"))
        assertTrue(isAllowedFileType("Android.bp", "text/plain"))
        assertTrue(isAllowedFileType("board-info.mk", "text/plain"))
        assertTrue(isAllowedFileType("init.rc", "text/plain"))
        assertTrue(isAllowedFileType("build.prop", "text/plain"))
    }

    @Test
    fun `allows archives and firmware via extension even with octet-stream`() {
        assertTrue(isAllowedFileType("ota-package.zip", "application/octet-stream"))
        assertTrue(isAllowedFileType("image.tar.gz", "application/octet-stream"))
        assertTrue(isAllowedFileType("app-release.apk", "application/octet-stream"))
        assertTrue(isAllowedFileType("system.img", "application/octet-stream"))
        assertTrue(isAllowedFileType("docs.docx", "application/octet-stream"))
    }

    @Test
    fun `still rejects unknown binary file types`() {
        assertFalse(isAllowedFileType("archive.unknown", "application/octet-stream"))
        assertFalse(isAllowedFileType("mystery.binrandom", "application/octet-stream"))
    }
}
