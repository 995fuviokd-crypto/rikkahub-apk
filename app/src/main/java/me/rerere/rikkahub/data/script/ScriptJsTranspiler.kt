package me.rerere.rikkahub.data.script

/**
 * 脚本转译器。
 *
 * 旧版 QuickJS wrapper 不调度微任务队列，需要把 async/await 转成 generator 同步执行。
 * 迁移到 V8 后原生支持 async/await，不再需要本转译逻辑，保留对象以兼容旧调用点。
 * 迁移到 V8（Javet 4.0.0）后，V8 原生支持 async/await 与完整的 ES2023，
 * 因此本转译器改为 no-op：直接返回原始源码，保留对象以便将来扩展。
 */
object ScriptJsTranspiler {

    /** 不再注入运行时（V8 原生支持 async/await）；保留常量避免编译断裂 */
    @Deprecated("V8 natively supports async/await; no runtime shim needed", ReplaceWith(""))
    const val RUN_GEN_RUNTIME: String = ""

    /** 执行转换，返回可直接在 V8 中评估的脚本（原样返回） */
    fun transpile(source: String): String = source
}
