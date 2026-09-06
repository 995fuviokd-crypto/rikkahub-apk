package me.rerere.common.js

import com.caoccao.javet.enums.JSRuntimeType
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.options.V8RuntimeOptions
import com.caoccao.javet.interop.converters.JavetProxyConverter
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.primitive.V8ValueDouble
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueLong
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueObject

/**
 * Javet (V8) 引擎封装，替代旧 QuickJS wrapper。
 *
 * V8 相比 QuickJS 的优势：
 *  - JIT 编译执行，热循环性能远超解释器
 *  - terminateExecution() 线程安全，可从其他线程安全中断死循环
 *  - 原生完整 ES2023 支持
 */
class JsEngine(
    /** max-old-space-size（MB），0 表示使用 V8 默认值 */
    private val maxHeapMb: Int = 0,
) : AutoCloseable {

    private val runtime: V8Runtime = V8Host.getInstance(JSRuntimeType.V8).createV8Runtime(
        V8RuntimeOptions().apply {
            if (maxHeapMb > 0) {
                // V8 堆上限是全局 flag，需在首个 runtime 创建前设置
                V8RuntimeOptions.V8_FLAGS.setMaxOldSpaceSize(maxHeapMb)
            }
        }
    )

    init {
        runtime.setConverter(JavetProxyConverter())
    }

    /**
     * 注册全局 JS 函数。回调参数转为 Kotlin 原生值
     * （String/Int/Long/Double/Boolean/JsObject），返回值经转换器转回 JS 值。
     */
    fun setGlobalFunction(name: String, fn: (args: List<Any?>) -> Any?) {
        val context = JavetCallbackContext(
            name,
            JavetCallbackType.DirectCallNoThisAndResult,
            IJavetDirectCallable.NoThisAndResult<Exception> { args: Array<out V8Value> ->
                val kotlinArgs = args.map { toKotlin(it) }
                toV8Value(fn(kotlinArgs))
            },
        )
        val function = runtime.createV8ValueFunction(context)
        val global = runtime.globalObject
        try {
            global.setProperty(name, function)
        } finally {
            function.close()
            global.close(false)
        }
    }

    /** 执行脚本，结果转为 Kotlin 值；JS 对象包装为 [JsObject]（懒 stringify） */
    fun evaluate(script: String): Any? {
        val result: V8Value = runtime.getExecutor(script).execute()
        return try {
            toKotlin(result)
        } finally {
            result.close()
        }
    }

    /** 线程安全中断执行中的脚本（V8 terminateExecution） */
    fun terminate() {
        runCatching { runtime.terminateExecution() }
    }

    /** 触发 V8 低内存通知，尽快回收堆 */
    fun lowMemoryNotification() {
        runCatching { runtime.lowMemoryNotification() }
    }

    override fun close() {
        runCatching { runtime.close() }
    }

    companion object {
        /** 创建引擎实例，[maxHeapMb] 设置 V8 老生代堆上限（MB），0 表示默认 */
        @JvmStatic
        fun create(maxHeapMb: Int = 0): JsEngine = JsEngine(maxHeapMb)
    }

    private fun toKotlin(value: V8Value?): Any? {
        if (value == null || value.isNullOrUndefined()) return null
        return when (value) {
            is V8ValueString -> value.toPrimitive()
            is V8ValueInteger -> value.toPrimitive()
            is V8ValueLong -> value.toPrimitive()
            is V8ValueDouble -> value.toPrimitive()
            is V8ValueBoolean -> value.toPrimitive()
            is V8ValueObject -> JsObject(value)
            else -> value.asString()
        }
    }

    private fun toV8Value(value: Any?): V8Value = when (value) {
        null -> runtime.createV8ValueNull()
        is V8Value -> value
        is String -> runtime.createV8ValueString(value)
        is Int -> runtime.createV8ValueInteger(value)
        is Long -> runtime.createV8ValueLong(value)
        is Double -> runtime.createV8ValueDouble(value)
        is Boolean -> runtime.createV8ValueBoolean(value)
        else -> runtime.converter.toV8Value(runtime, value)
    }
}

/** JS 对象的 Kotlin 侧视图：stringify() 输出 JSON，toString() 输出 V8 字符串表示 */
class JsObject(private val v8ValueObject: V8ValueObject) {
    fun stringify(): String = v8ValueObject.toJsonString()
    override fun toString(): String = v8ValueObject.toString()
}
