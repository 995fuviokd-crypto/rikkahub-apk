package me.rerere.rikkahub.data.ai.group

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.RunStatus
import me.rerere.rikkahub.data.repository.GroupRepository

/**
 * 群组运行协调器（应用级单例）：
 *
 * 运行 Job 挂在应用级作用域上，页面退出 / ViewModel 销毁后继续执行；
 * 以 groupId -> runId 记录活跃运行，重进页面时据此恢复「进行中」展示与停止入口。
 */
class GroupRunController(
    private val scope: CoroutineScope,
    private val runner: GroupRunner,
    private val repository: GroupRepository,
) {
    private val _runningRuns = MutableStateFlow<Map<String, String>>(emptyMap())

    /** groupId -> 活跃 runId */
    val runningRuns: StateFlow<Map<String, String>> = _runningRuns.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun launch(group: Group, mission: String): String {
        val runId = GroupRunner.newRunId()
        startInternal(group, mission, runId)
        return runId
    }

    /** 重启一次已有但被中断的运行（如进程内此前未收尾的 RUNNING 记录），复用原 runId */
    fun resume(group: Group, mission: String, runId: String) {
        startInternal(group, mission, runId)
    }

    fun stop(groupId: String) {
        jobs.remove(groupId)?.cancel()
    }

    fun isRunning(groupId: String): Boolean = _runningRuns.value.containsKey(groupId)

    private fun startInternal(group: Group, mission: String, runId: String) {
        val groupId = group.id
        // 同一群组同时只允许一个运行
        if (_runningRuns.value.containsKey(groupId)) return
        _runningRuns.value = _runningRuns.value + (groupId to runId)
        val job = scope.launch {
            try {
                runner.run(group, mission, runId = runId)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val run = repository.getRunById(runId)
                    if (run != null && run.status == RunStatus.RUNNING) {
                        repository.upsertRun(
                            run.copy(status = RunStatus.STOPPED, endedAt = System.currentTimeMillis())
                        )
                    }
                }
                throw e
            } catch (e: Throwable) {
                Log.e("GroupRunController", "group run crashed: group=$groupId", e)
            } finally {
                if (_runningRuns.value[groupId] == runId) {
                    _runningRuns.value = _runningRuns.value - groupId
                }
                jobs.remove(groupId)
            }
        }
        jobs[groupId] = job
    }
}
