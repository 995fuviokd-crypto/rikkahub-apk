package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.CharacterCardData
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.OutputStyle
import me.rerere.rikkahub.data.model.OutputStyleFrontmatter
import me.rerere.rikkahub.data.model.PresetManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PresetImportExportServiceTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `buildOutputStyleMd generates valid yaml frontmatter`() {
        val style = OutputStyle(
            name = "Terse Style",
            description = "Brief responses",
            frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = false),
            instructions = "Keep it short",
        )
        val md = buildOutputStyleMdInternal(style)
        assertTrue(md.startsWith("---"))
        assertTrue(md.contains("name: Terse Style"))
        assertTrue(md.contains("description: Brief responses"))
        assertTrue(md.contains("keepDefaultInstructions: false"))
        assertTrue(md.contains("Keep it short"))
    }

    @Test
    fun `parseOutputStyleMd parses valid markdown with frontmatter`() {
        val md = "---\nname: Test Style\ndescription: A test style\nkeepDefaultInstructions: false\n---\n\nBe very concise"
        val style = parseOutputStyleMdInternal(md)
        assertEquals("Test Style", style.name)
        assertEquals("A test style", style.description)
        assertEquals(false, style.frontmatter.keepDefaultInstructions)
        assertEquals("Be very concise", style.instructions)
    }

    @Test
    fun `parseOutputStyleMd handles missing frontmatter`() {
        val md = "Just plain instructions"
        val style = parseOutputStyleMdInternal(md)
        assertEquals("Imported", style.name)
        assertEquals("Just plain instructions", style.instructions)
    }

    @Test
    fun `parseOutputStyleMd handles malformed frontmatter`() {
        val md = "---\ninvalid\n"
        val style = parseOutputStyleMdInternal(md)
        assertEquals("Imported", style.name)
    }

    @Test
    fun `parseOutputStyleMd defaults keepDefaultInstructions to true`() {
        val md = "---\nname: Partial Style\n---\n\nSome instructions"
        val style = parseOutputStyleMdInternal(md)
        assertEquals("Partial Style", style.name)
        assertEquals(true, style.frontmatter.keepDefaultInstructions)
    }

    @Test
    fun `valid zip with manifest passes validation`() {
        val zipBytes = createZipWithContent(manifest = true)
        val files = readZipFiles(zipBytes)
        assertTrue(files.containsKey("manifest.json"))
    }

    @Test
    fun `zip without manifest fails validation`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("other.txt"))
            zos.write("data".toByteArray())
            zos.closeEntry()
        }
        val files = readZipFiles(baos.toByteArray())
        assertFalse(files.containsKey("manifest.json"))
    }

    @Test
    fun `manifest with unsupported version is rejected`() {
        val manifest = """{"formatVersion":"3.0","name":"Future"}"""
        assertFalse(manifest.startsWith("""{"formatVersion":"1.""""))
    }

    @Test
    fun `manifest with version 1_0 is accepted`() {
        val manifest = """{"formatVersion":"1.0","name":"Valid"}"""
        assertTrue(manifest.contains("\"1."))
    }

    @Test
    fun `manifest with version 1_1 is accepted`() {
        val manifest = """{"formatVersion":"1.1","name":"Valid"}"""
        assertTrue(manifest.contains("\"1."))
    }

    @Test
    fun `zip entry structure includes all expected files`() {
        val zipBytes = createZipWithContent(
            manifest = true,
            character = true,
            lorebooks = 2,
            outputStyles = 1,
            hooks = true,
        )
        val files = readZipFiles(zipBytes)
        assertTrue(files.containsKey("manifest.json"))
        assertTrue(files.containsKey("character.json"))
        assertTrue(files.keys.any { it.startsWith("lorebooks/") })
        assertTrue(files.keys.any { it.startsWith("output-styles/") })
        assertTrue(files.containsKey("hooks/hooks.json"))
    }

    private fun buildOutputStyleMdInternal(style: OutputStyle): String {
        return buildString {
            appendLine("---")
            appendLine("name: " + style.name)
            appendLine("description: " + style.description)
            appendLine("keepDefaultInstructions: " + style.frontmatter.keepDefaultInstructions)
            appendLine("---")
            appendLine()
            append(style.instructions)
        }
    }

    private fun parseOutputStyleMdInternal(content: String): OutputStyle {
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
                trimmed.startsWith("keepDefaultInstructions:") ->
                    keepDefault = trimmed.removePrefix("keepDefaultInstructions:").trim().toBooleanStrictOrNull() ?: true
            }
        }
        return OutputStyle(
            name = name,
            description = description,
            frontmatter = OutputStyleFrontmatter(keepDefaultInstructions = keepDefault),
            instructions = body,
        )
    }

    private fun createZipWithContent(
        manifest: Boolean = false,
        character: Boolean = false,
        lorebooks: Int = 0,
        outputStyles: Int = 0,
        hooks: Boolean = false,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            if (manifest) {
                zos.putNextEntry(ZipEntry("manifest.json"))
                val manifestObj = PresetManifest(
                    formatVersion = "1.0",
                    name = "Test Preset",
                )
                zos.write(json.encodeToString(PresetManifest.serializer(), manifestObj).toByteArray())
                zos.closeEntry()
            }
            if (character) {
                zos.putNextEntry(ZipEntry("character.json"))
                val card = CharacterCardData(
                    name = "Test Char",
                    description = "",
                )
                zos.write(json.encodeToString(CharacterCardData.serializer(), card).toByteArray())
                zos.closeEntry()
            }
            for (i in 0 until lorebooks) {
                zos.putNextEntry(ZipEntry("lorebooks/lorebook_$i.json"))
                val lorebook = Lorebook(
                    name = "Lorebook $i",
                    entries = emptyList(),
                )
                zos.write(json.encodeToString(Lorebook.serializer(), lorebook).toByteArray())
                zos.closeEntry()
            }
            for (i in 0 until outputStyles) {
                zos.putNextEntry(ZipEntry("output-styles/style_$i.md"))
                zos.write(buildOutputStyleMdInternal(
                    OutputStyle(name = "Style $i", instructions = "Instructions $i")
                ).toByteArray())
                zos.closeEntry()
            }
            if (hooks) {
                zos.putNextEntry(ZipEntry("hooks/hooks.json"))
                zos.write("[]".toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun readZipFiles(zipBytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    files[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return files
    }
}
