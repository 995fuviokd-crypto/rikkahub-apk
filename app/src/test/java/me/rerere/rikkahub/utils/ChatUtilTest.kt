package me.rerere.rikkahub.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilTest {
    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `still allows other files since all types are accepted`() {
        assertTrue(isAllowedFileType("archive.unknown", "application/octet-stream"))
        assertTrue(isAllowedFileType("archive.unknown", "application/octet-stream"))
    }
}
