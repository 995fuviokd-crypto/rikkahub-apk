package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.js.JsEngine
import me.rerere.common.js.JsObject

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using V8 engine (ES2023).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val logs = arrayListOf<String>()
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
        JsEngine.create(64).use { engine ->
            engine.setGlobalFunction("__captureConsole") { args ->
                val level = args.getOrNull(0)?.toString()?.uppercase() ?: "LOG"
                val message = args.drop(1).joinToString(" ") { it?.toString() ?: "null" }
                logs.add("[$level] $message")
                null
            }
            engine.evaluate(
                """
                globalThis.console = {
                    log: function() { __captureConsole('LOG', ...arguments); },
                    info: function() { __captureConsole('INFO', ...arguments); },
                    warn: function() { __captureConsole('WARN', ...arguments); },
                    error: function() { __captureConsole('ERROR', ...arguments); }
                }
                """.trimIndent()
            )
            val result = engine.evaluate(code.orEmpty())
            val payload = buildJsonObject {
                if (logs.isNotEmpty()) {
                    put("logs", JsonPrimitive(logs.joinToString("\n")))
                }
                put(
                    key = "result",
                    element = when (result) {
                        null -> JsonNull
                        is JsObject -> JsonPrimitive(result.stringify())
                        else -> JsonPrimitive(result.toString())
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)
