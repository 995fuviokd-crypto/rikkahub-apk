package me.rerere.rikkahub.data.memory

import kotlin.math.sqrt

/**
 * 语义向量接口。本地哈希嵌入提供确定性离线向量信号；
 * 后续可扩展 LLM embedding 提供更强的语义检索。
 */
interface MemoryEmbedder {
    fun embed(text: String): FloatArray
    fun similarity(a: FloatArray, b: FloatArray): Float
}

/**
 * 确定性本地哈希嵌入（对应 scope-recall 的 local-hash 离线 bootstrap）。
 *
 * 对词元做 signed hashing trick 得到稀疏浮点向量，不依赖网络与密钥，
 * 用于混合检索的向量信号与写入时的近重复检测。
 */
class LocalHashEmbedder(private val dim: Int = 256) : MemoryEmbedder {

    private val cache = object : LinkedHashMap<String, FloatArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>) = size > 1024
    }

    override fun embed(text: String): FloatArray {
        return cache.getOrPut(text) {
            val vec = FloatArray(dim)
            MemoryGating.queryTokens(text).forEach { token ->
                val h1 = stableHash(token + "#s")
                val sign = if ((h1 and 1) == 0) 1.0f else -1.0f
                val h2 = stableHash(token + "#i")
                val idx = ((h2 % dim) + dim) % dim
                vec[idx] += sign
            }
            vec
        }
    }

    override fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        var na = 0.0f
        var nb = 0.0f
        var i = 0
        while (i < a.size && i < b.size) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
            i++
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom <= 1e-6f) return 0.0f
        return (dot / denom).coerceIn(0.0f, 1.0f)
    }

    /** FNV-1a 稳定哈希，保证跨进程一致。 */
    private fun stableHash(text: String): Int {
        var hash = 0x811c9dc5.toInt()
        text.forEach { c ->
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash
    }
}
