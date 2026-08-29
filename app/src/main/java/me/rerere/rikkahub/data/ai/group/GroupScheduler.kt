package me.rerere.rikkahub.data.ai.group

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.GroupRepository
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务中心定时调度器：每分钟检查所有配置了 cron 的群组，
 * 到达下一次触发时间则自动发起一次任务运行。
 *
 * - 守护协程常驻 [scope]（应用级 AppScope）；
 * - 同一时刻同一群组只允许一个运行（running 集合去重，防止分钟级重复触发）；
 * - 触发后记录 run，复用 GroupRunner 完整管线。
 */
class GroupScheduler(
    private val scope: CoroutineScope,
    private val repository: GroupRepository,
    private val runner: GroupRunner,
) {
    private val TAG = "GroupScheduler"

    private var job: Job? = null
    private val runningGroups = ConcurrentHashMap.newKeySet<String>()

    /** 上次触发的群组 -> 触发时间，用于分钟粒度去重 */
    private val lastTrigger = ConcurrentHashMap<String, Long>()

    @Volatile
    var enabled: Boolean = true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i(TAG, "GroupScheduler started")
            while (isActive) {
                runCatching { tick() }
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun tick() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val scheduled = repository.listScheduledGroups()
        val currentMinute = now / 60_000
        for (group in scheduled) {
            val cron = GroupCron.parse(group.scheduleCron ?: "") ?: continue
            if (runningGroups.contains(group.id)) continue
            val next = GroupCron.nextRun(cron, LocalDateTime.now().minusMinutes(1)) ?: continue
            // 只触发"刚到达"的窗口：下一次执行时间落在当前分钟附近
            val triggerMinute = next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() / 60_000
            if (triggerMinute != currentMinute) continue
            val last = lastTrigger[group.id]
            if (last != null && last == triggerMinute) continue
            lastTrigger[group.id] = triggerMinute
            launchGroup(group)
        }
    }

    private suspend fun launchGroup(group: me.rerere.rikkahub.data.model.Group) {
        runningGroups.add(group.id)
        try {
            Log.i(TAG, "scheduled run triggered: group=${group.name}")
            runner.run(group = group, mission = "定时任务：${group.name}")
        } catch (e: Exception) {
            Log.e(TAG, "scheduled run failed: ${group.id}", e)
        } finally {
            runningGroups.remove(group.id)
        }
    }
}