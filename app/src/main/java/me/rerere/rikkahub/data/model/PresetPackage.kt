package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class PresetPackage(
    val manifest: PresetManifest,
    val characterCard: CharacterCardData? = null,
    val lorebooks: List<Lorebook> = emptyList(),
    val skills: List<SkillEntry> = emptyList(),
    val modeInjections: List<PromptInjection.ModeInjection> = emptyList(),
    val outputStyles: List<OutputStyle> = emptyList(),
    val hooks: List<HookConfig> = emptyList(),
    val regexScripts: List<AssistantRegex> = emptyList(),
    val parameters: PresetParameters? = null,
)

@Serializable
data class PresetManifest(
    val formatVersion: String = "1.0",
    val name: String,
    val description: String = "",
    val author: String = "",
    val createdAt: Long = 0L,
    val rikkahubVersion: String = "",
)

@Serializable
data class SkillEntry(
    val name: String,
    val skillMdContent: String,
    val scriptContents: Map<String, String> = emptyMap(),
)

@Serializable
data class PresetParameters(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val contextMessageLimit: Int? = null,
    val messageTemplate: String? = null,
)

@Serializable
data class ImportSummary(
    val characterCardImported: Boolean = false,
    val lorebooksImported: Int = 0,
    val skillsImported: Int = 0,
    val injectionsImported: Int = 0,
    val outputStylesImported: Int = 0,
    val hooksImported: Int = 0,
    val regexScriptsImported: Int = 0,
    val conflicts: List<ConflictItem> = emptyList(),
)

@Serializable
data class ConflictItem(
    val componentType: String = "",
    val componentName: String = "",
    val conflictType: ConflictType = ConflictType.NAME_COLLISION,
    val resolution: ConflictResolution = ConflictResolution.SKIPPED,
)

@Serializable
enum class ConflictType {
    @SerialName("name_collision") NAME_COLLISION,
    @SerialName("version_mismatch") VERSION_MISMATCH,
    @SerialName("incompatible") INCOMPATIBLE,
}

@Serializable
enum class ConflictResolution {
    @SerialName("skipped") SKIPPED,
    @SerialName("overwritten") OVERWRITTEN,
    @SerialName("renamed") RENAMED,
}

@Serializable
data class CharacterCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val systemPrompt: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "",
    val characterBookEntries: List<PromptInjection.RegexInjection> = emptyList(),
    val tavernCardPng: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacterCardData) return false
        return name == other.name && description == other.description
    }

    override fun hashCode(): Int = name.hashCode() * 31 + description.hashCode()
}
