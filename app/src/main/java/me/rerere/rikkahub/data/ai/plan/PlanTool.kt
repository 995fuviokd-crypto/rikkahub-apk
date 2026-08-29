package me.rerere.rikkahub.data.ai.plan

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 内置 plan 工具（RikkaHub 原生计划管理，契约对标 TodoWrite / ACP plan）：
 *
 * - action=create: 创建计划条目，返回 `{ task_id }`
 * - action=update: 更新条目状态（pending / in_progress / completed）或内容
 * - action=list: 返回当前全部条目（含状态）
 *
 * 模型在长任务中用它维护结构化任务清单；UI 侧通过 [PlanTracker.state]
 * 在输入框上方渲染计划胶囊。
 */
internal fun buildPlanTool(planTracker: PlanTracker): Tool = Tool(
    name = "plan",
    description = """
        Maintain a structured task plan for complex multi-step requests.
        Use this to break a request into tasks, track progress, and keep the user informed of what remains.
        Actions:
        - "create": create a plan entry. Input: { action: "create", content: "task description" }. Returns task_id.
        - "update": mark progress. Input: { action: "update", task_id: "...", status: "pending" | "in_progress" | "completed" }. Optionally content to rewrite the task text.
        - "list": return all current plan entries with their status.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("create")
                        add("update")
                        add("list")
                    })
                    put("description", "The action to perform")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Task description (required for create, optional for update)")
                })
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The id of a plan entry, as returned by create (required for update)")
                })
                put("status", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("pending")
                        add("in_progress")
                        add("completed")
                    })
                    put("description", "New status for the task (optional for update)")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val action = (args as? JsonObject)?.get("action")?.let { (it as? JsonPrimitive)?.content }.orEmpty()

        when (action) {
            "create" -> {
                val content = (args as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.content }
                    ?.takeIf { it.isNotBlank() }
                if (content == null) {
                    return@Tool listOf(UIMessagePart.Text("Error: content is required for create"))
                }
                val id = planTracker.create(content)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("task_id", id)
                        }.toString()
                    )
                )
            }

            "update" -> {
                val obj = args as? JsonObject
                val id = obj?.get("task_id")?.let { (it as? JsonPrimitive)?.content }.orEmpty()
                if (id.isBlank()) {
                    return@Tool listOf(UIMessagePart.Text("Error: task_id is required for update"))
                }
                val status = obj?.get("status")?.let { (it as? JsonPrimitive)?.content }
                val content = obj?.get("content")?.let { (it as? JsonPrimitive)?.content }
                val old = planTracker.state.value.firstOrNull { it.id == id }
                if (old == null) {
                    return@Tool listOf(UIMessagePart.Text("Error: unknown task_id: $id"))
                }
                planTracker.update(
                    id = id,
                    status = status?.takeUnless { it.isBlank() },
                    content = content?.takeUnless { it.isBlank() },
                )
                listOf(UIMessagePart.Text("Ok"))
            }

            "list" -> {
                val entries = planTracker.state.value
                val json = buildJsonObject {
                    put(
                        "tasks",
                        buildJsonArray {
                            entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("task_id", entry.id)
                                        put("content", entry.content)
                                        put("status", entry.status)
                                    }
                                )
                            }
                        }
                    )
                }
                listOf(UIMessagePart.Text(json.toString()))
            }

            else -> listOf(UIMessagePart.Text("Error: unknown action '$action'"))
        }
    }
)