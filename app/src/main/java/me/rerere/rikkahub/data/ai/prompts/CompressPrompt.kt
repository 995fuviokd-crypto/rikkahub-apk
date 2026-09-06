package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a structured summary that another AI assistant will use as its only memory of this history.

    Requirements:
    1. Organize the summary into these sections, each starting with a bold heading (skip a section only if there is truly nothing to report):
       - **Current Task**: what the user is trying to accomplish and the current goal
       - **Completed & Key Results**: steps already taken and their important outcomes
       - **User Preferences & Constraints**: explicit instructions, preferences, hard requirements the user stated (this section is critical, preserve every item verbatim)
       - **Key Entities & Data**: file paths, names, numbers, commands, links and other facts needed later
       - **Open Questions & Next Steps**: unresolved issues and what should happen next
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Use {locale} language
    6. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
