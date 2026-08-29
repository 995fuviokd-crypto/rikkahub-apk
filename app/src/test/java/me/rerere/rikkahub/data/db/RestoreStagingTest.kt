package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 验证 RestoreStaging：备份恢复期间不覆盖运行中的数据库文件，
 * 而是暂存到 staging 目录并标记 pending，由下次冷启动统一应用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RestoreStagingTest {

    private lateinit var appContext: Context

    @After
    fun tearDown() {
        if (::appContext.isInitialized) {
            RestoreStaging.cleanStaging(appContext)
        }
    }

    private fun fakeContext(): Context {
        appContext = InstrumentationRegistry.getInstrumentation().context
        return appContext
    }

    @Test
    fun `applyIfPending with no pending does nothing`() {
        val context = fakeContext()

        val applied = RestoreStaging.applyIfPending(context)

        assertFalse(applied)
        assertFalse(RestoreStaging.isPending(context))
    }

    @Test
    fun `applyIfPending copies staged db into database path and clears pending`() {
        val context = fakeContext()
        val stagingDir = RestoreStaging.stagingDir(context).apply { mkdirs() }
        val stagedDb = File(stagingDir, "rikka_hub.db")
        stagedDb.writeBytes(byteArrayOf(1, 2, 3, 4))
        RestoreStaging.markPending(context)

        val applied = RestoreStaging.applyIfPending(context)

        assertTrue(applied)
        assertFalse(RestoreStaging.isPending(context))
        assertFalse(stagedDb.exists())
        val target = context.getDatabasePath("rikka_hub")
        assertEquals(4L, target.length())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
    }

    @Test
    fun `applyIfPending removes stale wal shm before applying`() {
        val context = fakeContext()
        val stagingDir = RestoreStaging.stagingDir(context).apply { mkdirs() }
        val stagedDb = File(stagingDir, "rikka_hub.db")
        stagedDb.writeBytes(byteArrayOf(9))
        RestoreStaging.markPending(context)

        val target = context.getDatabasePath("rikka_hub")
        target.parentFile?.mkdirs()
        val staleWal = File(target.parentFile, "rikka_hub-wal")
        staleWal.writeBytes(byteArrayOf(0, 0))

        RestoreStaging.applyIfPending(context)

        assertFalse(staleWal.exists())
        val shm = File(target.parentFile, "rikka_hub-shm")
        assertFalse(shm.exists())
    }

    @Test
    fun `pending flag with missing staged db is cleaned without applying`() {
        val context = fakeContext()
        RestoreStaging.markPending(context)

        val applied = RestoreStaging.applyIfPending(context)

        assertFalse(applied)
        assertFalse(RestoreStaging.isPending(context))
        assertNull(RestoreStaging.stagingDir(context).listFiles())
    }

    @Test
    fun `cleanStaging removes pending and staged files`() {
        val context = fakeContext()
        val stagingDir = RestoreStaging.stagingDir(context).apply { mkdirs() }
        val stagedDb = File(stagingDir, "rikka_hub.db")
        stagedDb.writeBytes(byteArrayOf(5))
        RestoreStaging.markPending(context)

        RestoreStaging.cleanStaging(context)

        assertFalse(RestoreStaging.isPending(context))
        assertFalse(stagingDir.exists())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}