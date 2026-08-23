package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_MEMORY_PROMPT = """
    Summarize the following conversation content into a compact memory entry.

    Rules:
    1. Keep key facts, decisions, preferences and user instructions.
    2. Write in {locale} language.
    3. Output only the summary text, no extra explanation.

    Content:
    {content}
""".trimIndent()
