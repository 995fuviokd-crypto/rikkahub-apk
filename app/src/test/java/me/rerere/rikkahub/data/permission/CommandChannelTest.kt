package me.rerere.rikkahub.data.permission

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令通道（CommandChannel）行为测试。
 * SuChannel 与 ShizukuChannel 在未提供系统权限的 JVM 环境下的降级路径：
 * 通道不存在/环境未就绪时必须返回可读错误，而不是抛异常。
 */
class CommandChannelTest {

    @Test
    fun `ShizukuApi is not loaded when shizuku library is absent`() {
        assertFalse(ShizukuApi.isLoaded())
        assertFalse(ShizukuApi.isAvailable())
        assertEquals(-1, ShizukuApi.checkSelfPermission())
    }

    @Test
    fun `Shizuku channel returns readable error when shizuku service unavailable`() = runBlocking {
        val result = ShizukuChannel().exec("echo hello")
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
        assertNotNull(result.stderr)
    }

    @Test
    fun `Su channel returns a result without crashing`() = runBlocking {
        val result = SuChannel().exec("id")
        if (result.exitCode == -1) {
            assertTrue(result.stderr.isNotBlank())
        } else {
            assertTrue(result.exitCode >= 0)
        }
    }

    @Test
    fun `Permission level enum contains expected levels`() {
        val levels = PermissionLevel.values().toList()
        assertTrue(levels.contains(PermissionLevel.NONE))
        assertTrue(levels.contains(PermissionLevel.ACCESSIBILITY))
        assertTrue(levels.contains(PermissionLevel.ADB))
        assertTrue(levels.contains(PermissionLevel.ROOT))
    }

    @Test
    fun `ChannelResult carries exit code and outputs`() {
        val result = ChannelResult(0, "out", "")
        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("", result.stderr)
    }
}