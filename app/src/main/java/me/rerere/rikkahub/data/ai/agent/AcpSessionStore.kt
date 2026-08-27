package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists ACP session ids per (platform, conversation) inside the workspace dir so a
 * restarted app can offer `session/load` the previous agent-side session and preserve
 * agent context across process restarts.
 *
 * The store file lives at `<workspaceRoot>/acp-sessions.json` — host-side, outside the
 * container-visible `files/` tree, so agents never see it.
 */
class AcpSessionStore(private val json: Json = Json) {

    @Serializable
    private data class StoredSessions(val sessions: Map<String, String> = emptyMap())

    private val ioLock = Any()

    /** Returns the stored session id for [key], or null when absent/unreadable. */
    fun load(root: String, key: String): String? = synchronized(ioLock) {
        runCatching {
            val file = storeFile(root)
            if (!file.isFile) return null
            val stored = json.decodeFromString(StoredSessions.serializer(), file.readText())
            stored.sessions[key]
        }.onFailure { warn("load failed for $key", it) }.getOrNull()
    }

    /** Upserts (or clears when [sessionId] is null) the mapping for [key]. */
    fun save(root: String, key: String, sessionId: String?) = synchronized(ioLock) {
        runCatching {
            val file = storeFile(root)
            val current = if (file.isFile) {
                runCatching {
                    json.decodeFromString(StoredSessions.serializer(), file.readText())
                }.getOrDefault(StoredSessions())
            } else {
                StoredSessions()
            }
            val updated = if (sessionId == null) {
                current.copy(sessions = current.sessions - key)
            } else {
                current.copy(sessions = current.sessions + (key to sessionId))
            }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(StoredSessions.serializer(), updated))
        }.onFailure { warn("save failed for $key", it) }
        Unit
    }

    // android.util.Log is unavailable on the JVM test classpath; swallow the failure.
    private fun warn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }

    private fun storeFile(root: String) = File(root, STORE_FILE_NAME)

    companion object {
        private const val TAG = "AcpSessionStore"
        const val STORE_FILE_NAME = "acp-sessions.json"
    }
}
