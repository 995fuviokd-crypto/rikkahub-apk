package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.HookConfig
import me.rerere.rikkahub.data.model.HookEvent
import me.rerere.rikkahub.data.model.HookProcessorType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class HookTransformer : InputMessageTransformer, OutputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val preSendHooks = ctx.assistant.hookConfigs.filter {
            it.enabled && it.event == HookEvent.PRE_SEND
        }
        var result = messages
        for (hook in preSendHooks) {
            result = executePreSendHook(hook, result, ctx)
        }
        return result
    }

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val postReceiveHooks = ctx.assistant.hookConfigs.filter {
            it.enabled && it.event == HookEvent.POST_RECEIVE
        }
        var result = messages
        for (hook in postReceiveHooks) {
            result = executePostReceiveHook(hook, result, ctx)
        }
        return result
    }

    private suspend fun executePreSendHook(
        hook: HookConfig,
        messages: List<UIMessage>,
        ctx: TransformerContext,
    ): List<UIMessage> {
        val inputJson = buildHookInputJson(hook.event.name, messages, ctx)
        val outputJson = executeHook(hook, inputJson) ?: return messages
        return parseMessagesFromJson(outputJson, messages) ?: messages
    }

    private suspend fun executePostReceiveHook(
        hook: HookConfig,
        messages: List<UIMessage>,
        ctx: TransformerContext,
    ): List<UIMessage> {
        val inputJson = buildHookInputJson(hook.event.name, messages, ctx)
        val outputJson = executeHook(hook, inputJson) ?: return messages
        return parseMessagesFromJson(outputJson, messages) ?: messages
    }

    private suspend fun executeHook(hook: HookConfig, inputJson: JsonObject): JsonObject? {
        return try {
            withTimeout(hook.timeoutMs) {
                when (hook.processorType) {
                    HookProcessorType.SHELL -> executeShellHook(hook.command, inputJson)
                    HookProcessorType.HTTP -> executeHttpHook(hook.command, inputJson)
                    HookProcessorType.LLM -> null
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeShellHook(command: String, inputJson: JsonObject): JsonObject? {
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command.split(" "))
                .redirectErrorStream(true)
                .start()

            val inputString = inputJson.toString()
            process.outputStream.use { os ->
                os.write(inputString.toByteArray())
                os.flush()
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()

            parseHookOutput(output)
        }
    }

    private suspend fun executeHttpHook(url: String, inputJson: JsonObject): JsonObject? {
        return withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            connection.outputStream.use { os ->
                os.write(inputJson.toString().toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val output = reader.readText()
                parseHookOutput(output)
            } else {
                null
            }
        }
    }

    private fun parseHookOutput(output: String): JsonObject? {
        return try {
            val trimmed = output.trim()
            if (trimmed.isEmpty()) return null
            val json = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            json.jsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun buildHookInputJson(
        event: String,
        messages: List<UIMessage>,
        ctx: TransformerContext,
    ): JsonObject {
        return buildJsonObject {
            put("event", JsonPrimitive(event))
            put("messages", buildMessagesJson(messages))
            put("assistant", buildJsonObject {
                put("id", JsonPrimitive(ctx.assistant.id.toString()))
                put("name", JsonPrimitive(ctx.assistant.name))
            })
            put("model", buildJsonObject {
                put("id", JsonPrimitive(ctx.model.modelId))
                put("name", JsonPrimitive(ctx.model.displayName))
            })
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }
    }

    private fun buildMessagesJson(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(message.role.name))
                    put("parts", buildJsonArray {
                        message.parts.forEach { part ->
                            add(buildJsonObject {
                                put("type", JsonPrimitive(part::class.simpleName ?: "unknown"))
                                if (part is UIMessagePart.Text) {
                                    put("text", JsonPrimitive(part.text))
                                }
                            })
                        }
                    })
                })
            }
        }
    }

    private fun parseMessagesFromJson(json: JsonObject, originalMessages: List<UIMessage>): List<UIMessage>? {
        return try {
            val messagesArray = json["messages"]?.jsonArray ?: return null
            if (messagesArray.size != originalMessages.size) return null

            val result = mutableListOf<UIMessage>()
            for (i in messagesArray.indices) {
                val msgJson = messagesArray[i].jsonObject
                val original = originalMessages[i]
                val textParts = original.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        val textValue = msgJson["text"]?.jsonPrimitive?.contentOrNull ?: part.text
                        part.copy(text = textValue)
                    } else {
                        part
                    }
                }
                result.add(original.copy(parts = textParts))
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
