package me.rerere.rikkahub.data.tavern

import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.png.PngChunkType
import com.drew.metadata.png.PngDirectory
import java.util.Base64

/**
 * 从 PNG 角色卡字节提取 tEXt "chara" chunk 内嵌的 Base64 JSON。
 * 纯流式解析（metadata-extractor），不依赖 Bitmap 解码，可直接用于下载的字节。
 */
object TavernPng {
    fun extractCharaJson(pngBytes: ByteArray): String? {
        val metadata = ImageMetadataReader.readMetadata(pngBytes.inputStream())
        val directory = metadata.directories
            .filterIsInstance<PngDirectory>()
            .firstOrNull { it.pngChunkType == PngChunkType.tEXt } ?: return null
        val textual = directory.getString(PngDirectory.TAG_TEXTUAL_DATA) ?: return null
        // metadata-extractor 把 tEXt 键值拼成 [chara: <base64>] 形式
        val base64 = Regex("""\[chara:\s*(.+?)]""").find(textual)?.groupValues?.get(1) ?: return null
        return runCatching {
            String(Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)
        }.getOrNull()
    }
}
