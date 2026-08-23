package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.utils.SimpleCache
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

private val latencyCache = SimpleCache.builder<String, Long>()
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build()

@Composable
fun ProviderLatencyTag(
    providerSetting: ProviderSetting,
    model: Model? = null,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val targetModel = model ?: providerSetting.models.firstOrNull { it.type == ModelType.CHAT }

    val cacheKey = "${providerSetting.id},${targetModel?.modelId ?: ""}"
    var latency by remember(cacheKey) { mutableStateOf(latencyCache.getIfPresent(cacheKey)) }
    var measuring by remember { mutableStateOf(false) }

    when {
        measuring -> {
            Tag(type = TagType.INFO) {
                Text("延迟 测量中…")
            }
        }

        latency != null -> {
            val ms = latency!!
            Tag(
                type = if (ms <= 3000) TagType.SUCCESS else TagType.WARNING,
                onClick = {
                    scope.launch {
                        measuring = true
                        try {
                            val provider = providerManager.getProviderByType(providerSetting)
                            val elapsed = measureTimeMillis {
                                provider.generateText(
                                    providerSetting = providerSetting,
                                    messages = listOf(
                                        UIMessage.system("You are a helpful assistant"),
                                        UIMessage.user("hi"),
                                    ),
                                    params = TextGenerationParams(
                                        model = targetModel ?: return@launch
                                    )
                                )
                            }
                            latencyCache.put(cacheKey, elapsed)
                            latency = elapsed
                        } catch (_: Exception) {
                            latency = null
                        } finally {
                            measuring = false
                        }
                    }
                }
            ) {
                Text("延迟 ${ms}ms")
            }
        }

        else -> {
            Tag(
                type = TagType.INFO,
                onClick = {
                    if (targetModel == null) return@Tag
                    scope.launch {
                        measuring = true
                        try {
                            val provider = providerManager.getProviderByType(providerSetting)
                            val elapsed = measureTimeMillis {
                                provider.generateText(
                                    providerSetting = providerSetting,
                                    messages = listOf(
                                        UIMessage.system("You are a helpful assistant"),
                                        UIMessage.user("hi"),
                                    ),
                                    params = TextGenerationParams(
                                        model = targetModel
                                    )
                                )
                            }
                            latencyCache.put(cacheKey, elapsed)
                            latency = elapsed
                        } catch (_: Exception) {
                            latency = null
                        } finally {
                            measuring = false
                        }
                    }
                }
            ) {
                Text(if (targetModel == null) "无对话模型" else "测延迟")
            }
        }
    }
}
