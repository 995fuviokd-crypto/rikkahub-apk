package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenToolsTest {

    private fun imageModel() = Model(
        modelId = "test-image-model",
        displayName = "Test Image",
        type = ModelType.IMAGE,
    )

    @Test
    fun `returns null when no image model is configured`() {
        val provider = ProviderSetting.OpenAI(name = "OpenAI")
        val settings = Settings(providers = listOf(provider))

        assertNull(findChatImageGenerationTarget(settings))
    }

    @Test
    fun `returns null for chat type model`() {
        val chatModel = Model(modelId = "gpt-test", displayName = "GPT Test", type = ModelType.CHAT)
        val provider = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(chatModel))
        val settings = Settings(
            providers = listOf(provider),
            imageGenerationModelId = chatModel.id,
        )

        assertNull(findChatImageGenerationTarget(settings))
    }

    @Test
    fun `returns target for openai image model`() {
        val model = imageModel()
        val provider = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))
        val settings = Settings(
            providers = listOf(provider),
            imageGenerationModelId = model.id,
        )

        val target = findChatImageGenerationTarget(settings)
        assertNotNull(target)
        assertEquals(model.id, target!!.first.id)
        assertEquals("OpenAI", target.second.name)
    }

    @Test
    fun `returns target for google image model`() {
        val model = imageModel()
        val provider = ProviderSetting.Google(name = "Gemini", models = listOf(model))
        val settings = Settings(
            providers = listOf(provider),
            imageGenerationModelId = model.id,
        )

        val target = findChatImageGenerationTarget(settings)
        assertNotNull(target)
        assertEquals(model.id, target!!.first.id)
        assertEquals("Gemini", target.second.name)
    }

    @Test
    fun `returns null for unsupported claude provider`() {
        val model = imageModel()
        val provider = ProviderSetting.Claude(name = "Claude", models = listOf(model))
        val settings = Settings(
            providers = listOf(provider),
            imageGenerationModelId = model.id,
        )

        assertNull(findChatImageGenerationTarget(settings))
    }
}
