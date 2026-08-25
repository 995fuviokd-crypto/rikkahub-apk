package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File

/**
 * 聊天图片生成工具：让任意聊天模型通过 function call 调用"图片生成模型"画图，
 * 结果以 UIMessagePart.Image 附在工具输出中，由消息 UI 直接渲染。
 *
 * 仅当设置中配置了图片模型和已支持的 Provider 时注册工具，避免向模型暴露死工具。
 */
internal fun findChatImageGenerationTarget(settings: Settings): Pair<Model, ProviderSetting>? {
    val model = settings.findModelById(settings.imageGenerationModelId) ?: return null
    if (model.type != ModelType.IMAGE) return null
    val providerSetting = model.findProvider(settings.providers) ?: return null
    if (providerSetting !is ProviderSetting.OpenAI && providerSetting !is ProviderSetting.Google) {
        return null
    }
    return model to providerSetting
}

fun createImageGenerationTools(
    settings: Settings,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): List<Tool> {
    val (model, providerSetting) = findChatImageGenerationTarget(settings) ?: return emptyList()

    return listOf(
        Tool(
            name = "generate_image",
            description = """
                Generate an image with the configured image generation model.
                Use this when the user asks you to draw, paint, create or generate a picture/illustration.
                Write a detailed English prompt describing subject, style, composition, lighting and quality;
                the prompt is sent to the image model as-is.
                The generated image is displayed to the user automatically; just briefly confirm what was drawn.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "Detailed image generation prompt in English")
                        })
                        put("size", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional image size")
                            put("enum", buildJsonArray { ImageGenSize.entries.forEach { add(JsonPrimitive(it.value)) } })
                        })
                        put("num_of_images", buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of images to generate, from 1 to 4")
                        })
                    },
                    required = listOf("prompt"),
                )
            },
            execute = { args ->
                val prompt = args.jsonObject["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("prompt is required")
                val size = args.jsonObject["size"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { value -> ImageGenSize.entries.any { it.value == value } }
                    ?: ImageGenSize.AUTO.value
                val numOfImages = args.jsonObject["num_of_images"]?.jsonPrimitive?.intOrNull
                    ?.coerceIn(1, 4) ?: 1
                val provider = providerManager.getProviderByType(providerSetting)
                val params = ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = numOfImages,
                    size = size,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                )
                Log.i(TAG, "generate_image: model=${model.modelId}")
                val images = collectFinalImages(provider.generateImage(providerSetting, params))
                if (images.isEmpty()) error("Image generation returned no images")
                val generatedParts = mutableListOf<UIMessagePart>()
                images.forEachIndexed { index, item ->
                    val file = saveGeneratedImage(filesManager, genMediaRepository, item, prompt, model.modelId, index)
                    generatedParts += UIMessagePart.Image(url = file.toURI().toString())
                }
                listOf(UIMessagePart.Text("Image generated successfully (${images.size} image(s))")) + generatedParts
            },
        )
    )
}

private suspend fun collectFinalImages(
    flow: Flow<ImageGenerationItem>,
): List<ImageGenerationItem> {
    val finals = mutableListOf<ImageGenerationItem>()
    flow.collect { item ->
        // 聊天工具无渐进预览场景，只保留最终成品图
        if (!item.partial && item.data.isNotBlank()) finals.add(item)
    }
    return finals
}

private suspend fun saveGeneratedImage(
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    item: ImageGenerationItem,
    prompt: String,
    modelName: String,
    index: Int,
): File {
    val timestamp = System.currentTimeMillis()
    val safeModelName = modelName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "image-model" }
    val imageFile = File(
        filesManager.getImagesDir(),
        "${timestamp}_${safeModelName}_$index.${item.mimeType.imageExtension()}"
    )
    filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    genMediaRepository.insertMedia(
        GenMediaEntity(
            path = "images/${imageFile.name}",
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = GenMediaEntity.TYPE_IMAGE_GENERATION,
        )
    )
    return imageFile
}

private fun String.imageExtension(): String = when (substringAfter('/').lowercase()) {
    "jpeg" -> "jpg"
    "webp" -> "webp"
    else -> "png"
}

private val TAG = "ImageGenTools"
