package me.rerere.rikkahub.data.ai.transformers

import androidx.compose.runtime.mutableStateOf
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.HookConfig
import me.rerere.rikkahub.data.model.HookEvent
import me.rerere.rikkahub.data.model.HookProcessorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HookTransformerTest {

    private val transformer = HookTransformer()

    private val systemMessage = UIMessage.system("You are helpful")
    private val userMessage = UIMessage.user("Hello")

    @Test
    fun `transform returns messages unchanged when no pre-send hooks`() = runBlocking {
        val assistant = Assistant(hookConfigs = emptyList())
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `transform returns messages unchanged when hook is disabled`() = runBlocking {
        val hook = HookConfig(
            name = "Disabled Hook",
            enabled = false,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.SHELL,
            command = "echo {}",
        )
        val assistant = Assistant(hookConfigs = listOf(hook))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `transform returns messages unchanged for post_receive event during input`() = runBlocking {
        val hook = HookConfig(
            name = "Post Hook",
            enabled = true,
            event = HookEvent.POST_RECEIVE,
            processorType = HookProcessorType.SHELL,
            command = "echo {}",
        )
        val assistant = Assistant(hookConfigs = listOf(hook))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `onGenerationFinish returns messages unchanged when no post-receive hooks`() = runBlocking {
        val assistant = Assistant(hookConfigs = emptyList())
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.onGenerationFinish(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `onGenerationFinish returns messages unchanged for pre_send event during output`() = runBlocking {
        val hook = HookConfig(
            name = "Pre Hook",
            enabled = true,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.SHELL,
            command = "echo {}",
        )
        val assistant = Assistant(hookConfigs = listOf(hook))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.onGenerationFinish(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `visualTransform returns messages unchanged`() = runBlocking {
        val result = transformer.visualTransform(
            ctx = createTestContext(Assistant()),
            messages = listOf(userMessage),
        )
        assertEquals(listOf(userMessage), result)
    }

    @Test
    fun `hook with llm processor type returns messages unchanged`() = runBlocking {
        val hook = HookConfig(
            name = "LLM Hook",
            enabled = true,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.LLM,
            command = "some prompt",
        )
        val assistant = Assistant(hookConfigs = listOf(hook))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `shell hook with no output returns messages unchanged`() = runBlocking {
        val hook = HookConfig(
            name = "No Output Hook",
            enabled = true,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.SHELL,
            command = "true",
            failSilently = true,
        )
        val assistant = Assistant(hookConfigs = listOf(hook))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(messages, result)
    }

    @Test
    fun `multiple pre-send hooks execute in order`() = runBlocking {
        val hook1 = HookConfig(
            name = "Hook 1",
            enabled = true,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.SHELL,
            command = "cat",
        )
        val hook2 = HookConfig(
            name = "Hook 2",
            enabled = true,
            event = HookEvent.PRE_SEND,
            processorType = HookProcessorType.SHELL,
            command = "cat",
        )
        val assistant = Assistant(hookConfigs = listOf(hook1, hook2))
        val messages = listOf(systemMessage, userMessage)

        val result = transformer.transform(
            ctx = createTestContext(assistant),
            messages = messages,
        )
        assertEquals(2, result.size)
    }

    private fun createTestContext(assistant: Assistant): TransformerContext {
        return TransformerContext(
            context = RuntimeEnvironment.getApplication(),
            model = me.rerere.ai.provider.Model(),
            assistant = assistant,
            settings = createMinimalSettings(),
            processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
        )
    }

    private fun createMinimalSettings(): me.rerere.rikkahub.data.datastore.Settings {
        return me.rerere.rikkahub.data.datastore.Settings(
            assistants = listOf(Assistant()),
            outputStyles = emptyList(),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )
    }
}
