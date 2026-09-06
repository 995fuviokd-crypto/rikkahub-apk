package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.CharacterCardData
import me.rerere.rikkahub.data.model.ConflictItem
import me.rerere.rikkahub.data.model.ConflictResolution
import me.rerere.rikkahub.data.model.ConflictType
import me.rerere.rikkahub.data.model.ImportSummary
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.OutputStyle
import me.rerere.rikkahub.data.model.PresetManifest
import me.rerere.rikkahub.data.model.PresetPackage
import me.rerere.rikkahub.data.model.SkillEntry
import me.rerere.rikkahub.data.model.PresetParameters
import me.rerere.rikkahub.data.model.PromptInjection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class PresetImportExportService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
) {
    companion object {
        private const val TAG = "PresetImportExportService"
        private const val MANIFEST_FILE = "manifest.json"
        private const val CHARACTER_FILE = "character.json"
        private const val LOREBOOKS_DIR = "lorebooks/"
        private const val SKILLS_DIR = "skills/"
        private const val INJECTIONS_FILE = "injections/modes.json"
        private const val OUTPUT_STYLES_DIR = "output-styles/"
        private const val HOOKS_FILE = "hooks/hooks.json"
        private const val REGEXES_FILE = "regexes.json"
        private const val PARAMETERS_FILE = "parameters.json"

        /** 单个 zip 条目大小上限，防止恶意包撑爆内存 */
        private const val MAX_ENTRY_SIZE = 50L * 1024 * 1024
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportPreset(assistantId: Uuid): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                ?: throw IllegalArgumentException("Assistant not found: $assistantId")

            val lorebooks = settings.lorebooks.filter { it.id in assistant.lorebookIds }
            val modeInjections = settings.modeInjections.filter { it.id in assistant.modeInjectionIds }
            val outputStyles = settings.outputStyles.filter { it.id == assistant.activeOutputStyleId }

            val characterCard = CharacterCardData(
                name = assistant.name,
                description = "",
                personality = "",
                scenario = "",
                systemPrompt = assistant.systemPrompt,
                firstMes = "",
                mesExample = "",
                creatorNotes = "",
                tags = emptyList(),
                characterBookEntries = lorebooks.flatMap { it.entries },
            )

            val parameters = PresetParameters(
                temperature = assistant.temperature,
                topP = assistant.topP,
                maxTokens = assistant.maxTokens,
                contextMessageLimit = if (assistant.contextMessageLimit > 0) assistant.contextMessageLimit else null,
                messageTemplate = if (assistant.messageTemplate != "{{ message }}") assistant.messageTemplate else null,
            )

            val presetPackage = PresetPackage(
                manifest = PresetManifest(
                    formatVersion = "1.0",
                    name = assistant.name,
                    description = "Exported from RikkaHub",
                    createdAt = System.currentTimeMillis(),
                ),
                characterCard = characterCard,
                lorebooks = lorebooks,
                modeInjections = modeInjections,
                outputStyles = outputStyles,
                hooks = assistant.hookConfigs,
                regexScripts = assistant.regexes,
                parameters = parameters,
            )

            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry(MANIFEST_FILE))
                zos.write(json.encodeToString(presetPackage.manifest).toByteArray())
                zos.closeEntry()

                presetPackage.characterCard?.let { card ->
                    zos.putNextEntry(ZipEntry(CHARACTER_FILE))
                    zos.write(json.encodeToString(card).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.lorebooks.forEachIndexed { index, lorebook ->
                    zos.putNextEntry(ZipEntry("${LOREBOOKS_DIR}lorebook_$index.json"))
                    zos.write(json.encodeToString(lorebook).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.modeInjections.takeIf { it.isNotEmpty() }?.let { injections ->
                    zos.putNextEntry(ZipEntry(INJECTIONS_FILE))
                    zos.write(json.encodeToString(injections).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.outputStyles.forEachIndexed { index, style ->
                    zos.putNextEntry(ZipEntry("${OUTPUT_STYLES_DIR}style_$index.md"))
                    zos.write(buildOutputStyleMd(style).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.hooks.takeIf { it.isNotEmpty() }?.let { hooks ->
                    zos.putNextEntry(ZipEntry(HOOKS_FILE))
                    zos.write(json.encodeToString(hooks).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.regexScripts.takeIf { it.isNotEmpty() }?.let { regexes ->
                    zos.putNextEntry(ZipEntry(REGEXES_FILE))
                    zos.write(json.encodeToString(regexes).toByteArray())
                    zos.closeEntry()
                }

                presetPackage.parameters?.let { params ->
                    zos.putNextEntry(ZipEntry(PARAMETERS_FILE))
                    zos.write(json.encodeToString(params).toByteArray())
                    zos.closeEntry()
                }
            }

            baos.toByteArray()
        }
    }

    suspend fun importPreset(zipBytes: ByteArray): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val files = readZipEntriesCapped(zipBytes)

            val manifestJson = files[MANIFEST_FILE]
                ?: throw IllegalArgumentException("Missing manifest.json")
            val manifest = json.decodeFromString<PresetManifest>(manifestJson.toString(Charsets.UTF_8))

            if (!manifest.formatVersion.startsWith("1.")) {
                throw IllegalArgumentException("Unsupported format version: ${manifest.formatVersion}")
            }

            val conflicts = mutableListOf<ConflictItem>()
            var characterCardImported = false
            var lorebooksImported = 0
            var skillsImported = 0
            var injectionsImported = 0
            var outputStylesImported = 0
            var hooksImported = 0
            var regexScriptsImported = 0

            files[CHARACTER_FILE]?.let { cardBytes ->
                try {
                    val card = json.decodeFromString<CharacterCardData>(cardBytes.toString(Charsets.UTF_8))
                    // 将角色卡内容应用到第一个助手：名称与系统提示词生效
                    settingsStore.update { settings ->
                        val target = settings.assistants.firstOrNull() ?: return@update settings
                        settings.copy(assistants = settings.assistants.map {
                            if (it.id == target.id) {
                                it.copy(
                                    name = card.name.ifBlank { it.name },
                                    systemPrompt = card.systemPrompt.ifBlank { it.systemPrompt },
                                )
                            } else it
                        })
                    }
                    characterCardImported = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import character card", e)
                }
            }

            files.filterKeys { it.startsWith(SKILLS_DIR) }.forEach { (path, bytes) ->
                try {
                    // skills/<name>/SKILL.md 布局；同时兼容 skills/<name>.json（SkillEntry 序列化）
                    val relative = path.removePrefix(SKILLS_DIR)
                    if (relative.endsWith("SKILL.md")) {
                        val skillName = relative.removeSuffix("SKILL.md").trimEnd('/')
                        if (skillName.isNotBlank() && skillManager.saveSkillFileBytesAtomically(skillName, mapOf("SKILL.md" to bytes))) {
                            skillsImported++
                        }
                    } else if (relative.endsWith(".json")) {
                        val entry = json.decodeFromString<SkillEntry>(bytes.toString(Charsets.UTF_8))
                        val files1 = buildMap {
                            put("SKILL.md", entry.skillMdContent.toByteArray())
                            entry.scriptContents.forEach { (scriptName, content) ->
                                put(scriptName, content.toByteArray())
                            }
                        }
                        if (skillManager.saveSkillFileBytesAtomically(entry.name, files1)) {
                            skillsImported++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import skill: $path", e)
                }
            }

            files.filterKeys { it.startsWith(LOREBOOKS_DIR) }.values.forEach { bytes ->
                try {
                    val lorebook = json.decodeFromString<Lorebook>(bytes.toString(Charsets.UTF_8))
                    val existing = settingsStore.settingsFlow.value.lorebooks.firstOrNull { it.name == lorebook.name }
                    if (existing != null) {
                        conflicts.add(ConflictItem("lorebook", lorebook.name, ConflictType.NAME_COLLISION, ConflictResolution.SKIPPED))
                    } else {
                        settingsStore.update { settings ->
                            settings.copy(lorebooks = settings.lorebooks + lorebook)
                        }
                        lorebooksImported++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import lorebook", e)
                }
            }

            files[INJECTIONS_FILE]?.let { bytes ->
                try {
                    val injections = json.decodeFromString<List<PromptInjection.ModeInjection>>(bytes.toString(Charsets.UTF_8))
                    injections.forEach { injection ->
                        val existing = settingsStore.settingsFlow.value.modeInjections.firstOrNull { it.name == injection.name }
                        if (existing != null) {
                            conflicts.add(ConflictItem("modeInjection", injection.name, ConflictType.NAME_COLLISION, ConflictResolution.SKIPPED))
                        } else {
                            settingsStore.update { settings ->
                                settings.copy(modeInjections = settings.modeInjections + injection)
                            }
                            injectionsImported++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import mode injections", e)
                }
            }

            files.filterKeys { it.startsWith(OUTPUT_STYLES_DIR) }.values.forEach { bytes ->
                try {
                    val style = parseOutputStyleMd(bytes.toString(Charsets.UTF_8))
                    val existing = settingsStore.settingsFlow.value.outputStyles.firstOrNull { it.name == style.name }
                    if (existing != null) {
                        conflicts.add(ConflictItem("outputStyle", style.name, ConflictType.NAME_COLLISION, ConflictResolution.SKIPPED))
                    } else {
                        settingsStore.update { settings ->
                            settings.copy(outputStyles = settings.outputStyles + style)
                        }
                        outputStylesImported++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import output style", e)
                }
            }

            files[HOOKS_FILE]?.let { bytes ->
                try {
                    val hooks = json.decodeFromString<List<me.rerere.rikkahub.data.model.HookConfig>>(bytes.toString(Charsets.UTF_8))
                    settingsStore.update { settings ->
                        val targetAssistant = settings.assistants.firstOrNull()
                        if (targetAssistant != null) {
                            settings.copy(
                                assistants = settings.assistants.map {
                                    if (it.id == targetAssistant.id) it.copy(hookConfigs = it.hookConfigs + hooks) else it
                                }
                            )
                        } else settings
                    }
                    hooksImported = hooks.size
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import hooks", e)
                }
            }

            files[REGEXES_FILE]?.let { bytes ->
                try {
                    val regexes = json.decodeFromString<List<AssistantRegex>>(bytes.toString(Charsets.UTF_8))
                    settingsStore.update { settings ->
                        val targetAssistant = settings.assistants.firstOrNull()
                        if (targetAssistant != null) {
                            settings.copy(
                                assistants = settings.assistants.map {
                                    if (it.id == targetAssistant.id) it.copy(regexes = it.regexes + regexes) else it
                                }
                            )
                        } else settings
                    }
                    regexScriptsImported = regexes.size
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import regex scripts", e)
                }
            }

            ImportSummary(
                characterCardImported = characterCardImported,
                lorebooksImported = lorebooksImported,
                skillsImported = skillsImported,
                injectionsImported = injectionsImported,
                outputStylesImported = outputStylesImported,
                hooksImported = hooksImported,
                regexScriptsImported = regexScriptsImported,
                conflicts = conflicts,
            )
        }
    }

    suspend fun validatePackage(zipBytes: ByteArray): ValidationResult = withContext(Dispatchers.IO) {
        runCatching {
            val files = readZipEntriesCapped(zipBytes)

            if (!files.containsKey(MANIFEST_FILE)) {
                return@withContext ValidationResult(false, "Missing manifest.json")
            }

            val manifest = json.decodeFromString<PresetManifest>(files[MANIFEST_FILE]!!.toString(Charsets.UTF_8))
            if (!manifest.formatVersion.startsWith("1.")) {
                return@withContext ValidationResult(false, "Unsupported format version: ${manifest.formatVersion}")
            }

            ValidationResult(true, "Valid preset package: ${manifest.name}")
        }.getOrElse { e ->
            ValidationResult(false, "Invalid package: ${e.message}")
        }
    }

    /**
     * 读取 zip 全部条目，单条目超过 [MAX_ENTRY_SIZE] 时抛异常，
     * 防止恶意构造的压缩包在解压时耗尽内存（zip bomb / 超大单文件）。
     */
    private fun readZipEntriesCapped(zipBytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    var total = 0L
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ENTRY_SIZE) {
                            throw IllegalArgumentException("Zip entry too large: ${entry.name}")
                        }
                        baos.write(buffer, 0, read)
                    }
                    files[entry.name] = baos.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return files
    }

    private fun buildOutputStyleMd(style: OutputStyle): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${style.name}")
            appendLine("description: ${style.description}")
            appendLine("keepDefaultInstructions: ${style.frontmatter.keepDefaultInstructions}")
            appendLine("---")
            appendLine()
            append(style.instructions)
        }
    }

    private fun parseOutputStyleMd(content: String): OutputStyle {
        if (!content.startsWith("---")) {
            return OutputStyle(name = "Imported", instructions = content)
        }

        val parts = content.split("---", limit = 3)
        if (parts.size < 3) {
            return OutputStyle(name = "Imported", instructions = content)
        }

        val frontmatter = parts[1].trim()
        val body = parts[2].trim()

        var name = "Imported"
        var description = ""
        var keepDefault = true

        frontmatter.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name:") -> name = trimmed.removePrefix("name:").trim()
                trimmed.startsWith("description:") -> description = trimmed.removePrefix("description:").trim()
                trimmed.startsWith("keepDefaultInstructions:") -> keepDefault = trimmed.removePrefix("keepDefaultInstructions:").trim().toBooleanStrictOrNull() ?: true
            }
        }

        return OutputStyle(
            name = name,
            description = description,
            frontmatter = me.rerere.rikkahub.data.model.OutputStyleFrontmatter(keepDefaultInstructions = keepDefault),
            instructions = body,
        )
    }
}

data class ValidationResult(
    val valid: Boolean,
    val message: String,
)
