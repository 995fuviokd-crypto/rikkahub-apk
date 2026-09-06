package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OutputStyle
import me.rerere.rikkahub.data.model.OutputStyleFrontmatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputStyleTransformerTest {

    private val transformer = OutputStyleTransformer()

    private val systemMessage = UIMessage.system("You are a helpful assistant")
    private val userMessage = UIMessage.user("Hello")

    @Test
    fun `returns messages unchanged when no active output style`() = runBlocking {
        val assistant = Assistant(activeOutputStyleId = null)
        val settings = createSettings(outputStyles = emptyList())

        val result = transformer.transform(
            ctx = createTestContext(assistant, settings),
            messages = listOf(systemMessage, userMessage),
        )
        assertEquals(listOf(systemMessage, userMessage), result)
    }

    @Test
    fun `returns messages unchanged when no system message`() = runBlocking {
        val styleId = Uuid.random()
        val assistant = Assistant(activeOutputStyleId = styleId)
        val settings = createSettings(
            outputStyles = listOf(
                OutputStyle(id = styleId, name = "Test", instructions = "Be terse")
            )
        )

        val result = transformer.transform(
            ctx = createTestContext(assistant, settings),
            messages = listOf(userMessage),
        )
        assertEquals(listOf(userMessage), result)
    }

    @Test
    fun `appends instructions when keepDefaultInstructions is true`() = runBlocking {
        val styleId = Uuid.random()
        val assistant = Assistant(activeOutputStyleId = styleId)
        val settings = createSettings(
            outputStyles = listOf(
                OutputStyle(
                    id = styleId,
                    name = "Terse",
                    instructions = "Be brief",
                    frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = true),
                )
            )
        )

        val result = transformer.transform(
            ctx = createTestContext(assistant, settings),
            messages = listOf(systemMessage, userMessage),
        )
        val systemResult = result.first { it.role == MessageRole.SYSTEM }
        val text = systemResult.parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.contains("You are a helpful assistant"))
        assertTrue(text.contains("Be brief"))
    }

    @Test
    fun `replaces system prompt when keepDefaultInstructions is false`() = runBlocking {
        val styleId = Uuid.random()
        val assistant = Assistant(
            name = "CodeBot",
            activeOutputStyleId = styleId,
        )
        val settings = createSettings(
            outputStyles = listOf(
                OutputStyle(
                    id = styleId,
                    name = "Code",
                    instructions = "Write clean code",
                    frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = false),
                )
            )
        )

        val result = transformer.transform(
            ctx = createTestContext(assistant, settings),
            messages = listOf(systemMessage, userMessage),
        )
        val systemResult = result.first { it.role == MessageRole.SYSTEM }
        val text = systemResult.parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.startsWith("You are CodeBot."))
        assertTrue(text.contains("Write clean code"))
    }

    @Test
    fun `replaces with fallback name when assistant name blank`() = runBlocking {
        val styleId = Uuid.random()
        val assistant = Assistant(
            name = "",
            activeOutputStyleId = styleId,
        )
        val settings = createSettings(
            outputStyles = listOf(
                OutputStyle(
                    id = styleId,
                    name = "Code",
                    instructions = "Be helpful",
                    frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = false),
                )
            )
        )

        val result = transformer.transform(
            ctx = createTestContext(assistant, settings),
            messages = listOf(systemMessage, userMessage),
        )
        val systemResult = result.first { it.role == MessageRole.SYSTEM }
        val text = systemResult.parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.startsWith("You are an assistant."))
    }

    private fun createSettings(outputStyles: List<OutputStyle>): me.rerere.rikkahub.data.datastore.Settings {
        return me.rerere.rikkahub.data.datastore.Settings(
            assistants = listOf(Assistant()),
            outputStyles = outputStyles,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )
    }

    private fun createTestContext(
        assistant: Assistant,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): TransformerContext {
        return TransformerContext(
            context = RuntimeEnvironment.getApplication(),
            model = me.rerere.ai.provider.Model(),
            assistant = assistant,
            settings = settings,
        )
    }
}
