package me.rerere.rikkahub.data.ai.prompts

/**
 * 图片生成提示词模板。`{prompt}` 会被替换为用户输入的图片描述。
 * 默认仅透传用户输入；用户可改写为带风格/构图约束的模板。
 */
internal val DEFAULT_IMAGE_GENERATION_PROMPT = "{prompt}"
