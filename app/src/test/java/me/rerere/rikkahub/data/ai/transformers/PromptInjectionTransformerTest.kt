package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.FilterLogic
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.isTriggered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    private val assistant = Assistant(
        id = Uuid.random(),
        name = "Test Assistant",
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
    )

    private val systemMessage = UIMessage.system("You are a helpful assistant")
    private val userMessage = UIMessage.user("Hello")

    @Test
    fun `collectInjections returns empty when no injections configured`() {
        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections includes enabled mode injection when associated`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Mode content",
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals("Mode content", result[0].content)
    }

    @Test
    fun `collectInjections skips disabled mode injection`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Disabled Mode",
            enabled = false,
            content = "Should not appear",
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections triggers lorebook entry by keyword`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "Greeting",
            keywords = listOf("hello"),
            content = "Greeting content",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        )
        val lorebook = Lorebook(
            id = lorebookId,
            name = "Test Lorebook",
            entries = listOf(entry),
        )
        val testAssistant = assistant.copy(lorebookIds = setOf(lorebookId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertEquals(1, result.size)
        assertEquals("Greeting content", result[0].content)
    }

    @Test
    fun `collectInjections does not trigger lorebook entry when keyword absent`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "Farewell",
            keywords = listOf("goodbye"),
            content = "Farewell content",
        )
        val lorebook = Lorebook(
            id = lorebookId,
            name = "Test Lorebook",
            entries = listOf(entry),
        )
        val testAssistant = assistant.copy(lorebookIds = setOf(lorebookId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections applies token budget truncation`() {
        val lorebookId = Uuid.random()
        val longContent = "a".repeat(200)
        val entry = PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "Long Entry",
            keywords = listOf("hello"),
            content = longContent,
            tokenBudget = 10,
        )
        val lorebook = Lorebook(
            id = lorebookId,
            name = "Test Lorebook",
            entries = listOf(entry),
        )
        val testAssistant = assistant.copy(lorebookIds = setOf(lorebookId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertEquals(1, result.size)
        assertTrue(result[0].content.length < longContent.length)
        assertTrue(result[0].content.endsWith("...[truncated]"))
    }

    @Test
    fun `collectInjections skips lorebook entry before activateAfterMessageCount`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = "Delayed",
            keywords = listOf("hello"),
            content = "Delayed content",
            activateAfterMessageCount = 5,
        )
        val lorebook = Lorebook(
            id = lorebookId,
            name = "Test Lorebook",
            entries = listOf(entry),
        )
        val testAssistant = assistant.copy(lorebookIds = setOf(lorebookId))

        val result = collectInjections(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `applyInjections with after_system_prompt modifies system message`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Injected after",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        val systemResult = result.first { it.role == MessageRole.SYSTEM }
        val text = systemResult.parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.contains("You are a helpful assistant"))
        assertTrue(text.contains("Injected after"))
    }

    @Test
    fun `applyInjections with before_system_prompt prepends to system message`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Injected before",
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        val systemResult = result.first { it.role == MessageRole.SYSTEM }
        val text = systemResult.parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.startsWith("Injected before"))
        assertTrue(text.contains("You are a helpful assistant"))
    }

    @Test
    fun `applyInjections with top_of_chat inserts before user message`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Top content",
            position = InjectionPosition.TOP_OF_CHAT,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertEquals(3, result.size)
        val injectedMessage = result[1]
        val text = injectedMessage.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: ""
        assertEquals("Top content", text)
    }

    @Test
    fun `applyInjections with bottom_of_chat inserts before last message`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Bottom content",
            position = InjectionPosition.BOTTOM_OF_CHAT,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertEquals(3, result.size)
        val injectedMessage = result[1]
        val text = injectedMessage.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: ""
        assertEquals("Bottom content", text)
    }

    @Test
    fun `applyInjections with at_depth inserts at correct depth`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "Depth content",
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(systemMessage, userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertEquals(3, result.size)
        val injectedMessage = result[1]
        val text = injectedMessage.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: ""
        assertEquals("Depth content", text)
    }

    @Test
    fun `applyInjections creates system message when none exists`() {
        val modeId = Uuid.random()
        val modeInjection = PromptInjection.ModeInjection(
            id = modeId,
            name = "Test Mode",
            content = "New system",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        )
        val testAssistant = assistant.copy(modeInjectionIds = setOf(modeId))

        val result = transformMessages(
            messages = listOf(userMessage),
            assistant = testAssistant,
            modeInjections = listOf(modeInjection),
            lorebooks = emptyList(),
        )
        assertTrue(result.any { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun `findSafeInsertIndex skips user to assistant-with-tools boundary`() {
        val userMsg = UIMessage.user("test")
        val assistantWithTools = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "search",
                    input = "query",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )
        )
        val messages = listOf(userMsg, assistantWithTools)
        val index = findSafeInsertIndex(messages, 1)
        assertEquals(0, index)
    }

    @Test
    fun `findSafeInsertIndex returns target when safe`() {
        val messages = listOf(UIMessage.user("a"), UIMessage.assistant("b"))
        val index = findSafeInsertIndex(messages, 1)
        assertEquals(1, index)
    }

    @Test
    fun `isTriggered returns false when disabled`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("hello"),
            enabled = false,
        )
        assertFalse(injection.isTriggered("hello world"))
    }

    @Test
    fun `isTriggered returns true when constantActive`() {
        val injection = PromptInjection.RegexInjection(
            constantActive = true,
        )
        assertTrue(injection.isTriggered(""))
    }

    @Test
    fun `isTriggered matches keyword case insensitive`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("Hello"),
            caseSensitive = false,
        )
        assertTrue(injection.isTriggered("say hello world"))
    }

    @Test
    fun `isTriggered does not match when keyword absent`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("goodbye"),
        )
        assertFalse(injection.isTriggered("say hello world"))
    }

    @Test
    fun `isTriggered with regex pattern`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("\\d{3}-\\d{4}"),
            useRegex = true,
        )
        assertTrue(injection.isTriggered("phone: 123-4567"))
        assertFalse(injection.isTriggered("no phone here"))
    }

    @Test
    fun `isTriggered with secondary keywords AND_ANY`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("hello"),
            secondaryKeywords = listOf("world", "earth"),
            filterLogic = FilterLogic.AND_ANY,
        )
        assertTrue(injection.isTriggered("hello world"))
        assertFalse(injection.isTriggered("hello moon"))
    }

    @Test
    fun `isTriggered with secondary keywords AND_ALL`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("hello"),
            secondaryKeywords = listOf("world", "earth"),
            filterLogic = FilterLogic.AND_ALL,
        )
        assertTrue(injection.isTriggered("hello world earth"))
        assertFalse(injection.isTriggered("hello world"))
    }

    @Test
    fun `isTriggered with secondary keywords NOT_ANY`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("hello"),
            secondaryKeywords = listOf("world"),
            filterLogic = FilterLogic.NOT_ANY,
        )
        assertTrue(injection.isTriggered("hello moon"))
        assertFalse(injection.isTriggered("hello world"))
    }

    @Test
    fun `isTriggered with invalid regex falls back to false`() {
        val injection = PromptInjection.RegexInjection(
            keywords = listOf("[invalid"),
            useRegex = true,
        )
        assertFalse(injection.isTriggered("hello"))
    }
}
