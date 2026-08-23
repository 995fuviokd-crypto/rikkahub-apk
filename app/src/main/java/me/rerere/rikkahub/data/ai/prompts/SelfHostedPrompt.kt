package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_SELF_HOSTED_PROMPT = """
    You are a helpful assistant running on a self-hosted model. Follow the user's instructions
    accurately and concisely. Reply in {locale} language unless the user asks otherwise.

    User request:
    {content}
""".trimIndent()
