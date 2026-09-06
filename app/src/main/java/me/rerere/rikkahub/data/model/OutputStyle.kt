package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class OutputStyle(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val frontmatter: OutputStyleFrontmatter = OutputStyleFrontmatter(),
    val instructions: String = "",
    val builtin: Boolean = false,
)

@Serializable
data class OutputStyleFrontmatter(
    val keepDefaultInstructions: Boolean = true,
)
