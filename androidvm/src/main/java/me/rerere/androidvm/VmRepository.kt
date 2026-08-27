package me.rerere.androidvm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 实例清单的本地持久化（JSON 文件）。
 *
 * 仅保存实例的元数据与开关状态；rootfs 实际文件由对应引擎管理。
 */
class VmRepository(private val context: Context) {
    private val file = File(context.filesDir, "androidvm_instances.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun load(): List<VmInstance> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<VmInstance>>(file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun save(instances: List<VmInstance>) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(instances))
    }
}
