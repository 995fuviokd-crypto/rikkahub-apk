package me.rerere.rikkahub.data.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AcpSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `save and load roundtrip`() {
        val store = AcpSessionStore()
        val root = tmp.newFolder("ws1").absolutePath
        store.save(root, "claude_code|conv-1", "sess-abc")
        assertEquals("sess-abc", store.load(root, "claude_code|conv-1"))
    }

    @Test
    fun `multiple keys coexist in one workspace`() {
        val store = AcpSessionStore()
        val root = tmp.newFolder("ws2").absolutePath
        store.save(root, "codex|conv-a", "s-a")
        store.save(root, "claude_code|conv-b", "s-b")
        assertEquals("s-a", store.load(root, "codex|conv-a"))
        assertEquals("s-b", store.load(root, "claude_code|conv-b"))
    }

    @Test
    fun `saving null clears the mapping but keeps others`() {
        val store = AcpSessionStore()
        val root = tmp.newFolder("ws3").absolutePath
        store.save(root, "k1", "s1")
        store.save(root, "k2", "s2")
        store.save(root, "k1", null)
        assertNull(store.load(root, "k1"))
        assertEquals("s2", store.load(root, "k2"))
    }

    @Test
    fun `overwriting a key replaces the value`() {
        val store = AcpSessionStore()
        val root = tmp.newFolder("ws4").absolutePath
        store.save(root, "k", "old")
        store.save(root, "k", "new")
        assertEquals("new", store.load(root, "k"))
    }

    @Test
    fun `load on empty workspace returns null`() {
        val store = AcpSessionStore()
        assertNull(store.load(tmp.newFolder("ws5").absolutePath, "missing"))
    }

    @Test
    fun `corrupted store file degrades to null and recovers on next save`() {
        val store = AcpSessionStore()
        val root = tmp.newFolder("ws6").absolutePath
        File(root, AcpSessionStore.STORE_FILE_NAME).writeText("{ not json !!!")
        assertNull(store.load(root, "k"))
        // 损坏后保存应重建文件
        store.save(root, "k", "fresh")
        assertEquals("fresh", store.load(root, "k"))
    }

    @Test
    fun `stores are isolated per workspace root`() {
        val store = AcpSessionStore()
        val rootA = tmp.newFolder("a").absolutePath
        val rootB = tmp.newFolder("b").absolutePath
        store.save(rootA, "k", "in-a")
        store.save(rootB, "k", "in-b")
        assertEquals("in-a", store.load(rootA, "k"))
        assertEquals("in-b", store.load(rootB, "k"))
    }
}
