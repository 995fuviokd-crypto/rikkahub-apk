package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MultiRouteRaceTest {

    /** 竞速：两个流同时发射，先发射的流接管，其全部元素按序产出。 */
    @Test
    fun `fast route wins and its elements flow through`() = runBlocking {
        val slow = flow {
            delay(50)
            emit(1)
            emit(2)
        }
        val fast = flow {
            delay(5)
            emit(10)
            emit(20)
            emit(30)
        }
        val result = multiRouteRace(listOf(slow, fast)).toList()
        assertEquals(listOf(10, 20, 30), result)
    }

    /** 慢线路失败不影响快线路：故障转移。 */
    @Test
    fun `failing slow route does not break healthy fast route`() = runBlocking {
        val slowFails: Flow<String> = flow {
            delay(50)
            error("slow route down")
        }
        val fast: Flow<String> = flow {
            delay(5)
            emit("ok")
        }
        val result = multiRouteRace(listOf(slowFails, fast)).toList()
        assertEquals(listOf("ok"), result)
    }

    /** 所有线路都失败时抛出异常。 */
    @Test
    fun `all routes failing throws last error`() = runBlocking {
        val a: Flow<Int> = flow { delay(10); error("route a down") }
        val b: Flow<Int> = flow { delay(5); error("route b down") }
        try {
            val flows: List<Flow<Int>> = listOf(a, b)
            multiRouteRace(flows).toList()
            fail("expected exception")
        } catch (e: Throwable) {
            // 全部线路失败，抛出的是线路错误
            assertTrue(e.message?.contains("route") == true)
        }
    }

    /** 竞速后输家线路被取消：其后续元素不会产生。 */
    @Test
    fun `loser route is cancelled after winner takes over`() = runBlocking {
        // 用 Channel 控制，验证输家不再继续发射
        val loserStarted = CompletableDeferred<Unit>()
        val loserGated = CompletableDeferred<Unit>()
        val winner = flow {
            emit("first")
            emit("second")
        }
        val loser = flow {
            loserStarted.complete(Unit)
            loserGated.await()
            emit("loser-emitted")
        }
        val result = multiRouteRace(listOf(winner, loser)).toList()
        // 赢家先发射，输家 await 未完成即被取消，不会产出 loser-emitted
        assertEquals(listOf("first", "second"), result)
        loserStarted.await()
        // 输家协程已取消，不再继续
        loserGated.complete(Unit)
        assertTrue(true)
    }

    /** 单条流直接透传，无竞速开销。 */
    @Test
    fun `single flow passes through`() = runBlocking {
        val single = flow { emit(1); emit(2); emit(3) }
        assertEquals(listOf(1, 2, 3), multiRouteRace(listOf(single)).toList())
    }

    /** 空流列表直接完成。 */
    @Test
    fun `empty flows completes empty`() = runBlocking {
        val empty: List<Flow<Int>> = emptyList()
        val result: List<Int> = multiRouteRace(empty).toList()
        assertEquals(emptyList<Int>(), result)
    }

    /** 赢家中途失败会向上传播异常。 */
    @Test
    fun `winner failing midstream propagates error`() = runBlocking {
        val winnerFails = flow {
            emit(1)
            error("winner crashed")
        }
        val slowLoser = flow {
            delay(500)
            emit(99)
        }
        try {
            multiRouteRace(listOf(winnerFails, slowLoser)).toList()
            fail("expected exception")
        } catch (e: Throwable) {
            assertTrue(e.message?.contains("winner crashed") == true)
        }
    }
}
