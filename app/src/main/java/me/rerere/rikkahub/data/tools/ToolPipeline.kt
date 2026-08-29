package me.rerere.rikkahub.data.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.cordis.CordisEvent
import me.rerere.rikkahub.data.cordis.CordisEventBus
import me.rerere.rikkahub.data.cordis.DispatchMode

/**
 * 工具执行管线：按 dsh 事件链执行工具调用。
 *
 * 事件链：
 * 1. `tools/pre-execute`（Bail：首个返回非空即拦截/继续）
 * 2. `tools/execute`（Waterfall：前一监听器返回值作为后一入参）
 * 3. `tools/post-execute`（Emit：观察结果，不改变执行）
 * 4. `tools/result`（Emit：广播结果）
 *
 * 无插件监听时直接执行 [ToolDefinition.execute]，保证核心语义不依赖插件。
 */
internal class ToolPipeline(private val eventBus: CordisEventBus) {

    /** 执行工具调用，返回执行结果。 */
    suspend fun execute(tool: ToolDefinition, input: JsonElement): ToolExecutionResult {
        val prePayload = buildJsonObject {
            put("name", tool.name)
            put("input", input)
        }

        // pre-execute（Bail：首个真值截断）
        val pre = eventBus.dispatch(
            DispatchMode.Bail,
            CordisEvent("tools/pre-execute", prePayload)
        )
        val preResult = pre.firstOrNull()
        if (preResult != null) {
            val denied = preResult["denied"]
                ?.jsonPrimitive
                ?.content
                .equals("true", ignoreCase = true)
            val reason = preResult["reason"]
                ?.jsonPrimitive
                ?.content
            if (denied) {
                throw ToolExecutionRejected(reason ?: "rejected by pre-execute hook")
            }
        }

        // 执行（Waterfall：参数可被插件改写透传）
        val executePayload = buildJsonObject {
            put("name", tool.name)
            put("input", input)
        }
        val executeResult = eventBus.dispatch(
            DispatchMode.Waterfall,
            CordisEvent("tools/execute", executePayload)
        ).lastOrNull()

        val finalInput: JsonElement = executeResult?.get("input") ?: input
        val output: JsonElement? = try {
            tool.execute(finalInput)
        } catch (e: Throwable) {
            return fail(tool, input, e)
        }

        // post-execute（Emit）
        eventBus.emit(
            CordisEvent(
                name = "tools/post-execute",
                payload = buildJsonObject {
                    put("name", tool.name)
                    put("input", finalInput)
                    if (output != null) put("output", output)
                }
            )
        )

        // result（Emit）
        eventBus.emit(
            CordisEvent(
                name = "tools/result",
                payload = buildJsonObject {
                    put("name", tool.name)
                    put("input", finalInput)
                    if (output != null) put("output", output)
                }
            )
        )

        return ToolExecutionResult(tool, finalInput, output = output)
    }

    private suspend fun fail(tool: ToolDefinition, input: JsonElement, error: Throwable): ToolExecutionResult {
        eventBus.emit(
            CordisEvent(
                name = "tools/result",
                payload = buildJsonObject {
                    put("name", tool.name)
                    put("input", input)
                    put("error", error.message ?: error.javaClass.simpleName)
                }
            )
        )
        return ToolExecutionResult(tool, input, error = error)
    }
}