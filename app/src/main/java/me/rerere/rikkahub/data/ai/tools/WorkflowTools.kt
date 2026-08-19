package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.workflow.WorkflowRunner
import me.rerere.rikkahub.data.model.AiStepConfig
import me.rerere.rikkahub.data.model.DelayStepConfig
import me.rerere.rikkahub.data.model.HttpStepConfig
import me.rerere.rikkahub.data.model.ShellStepConfig
import me.rerere.rikkahub.data.model.StepConfig
import me.rerere.rikkahub.data.model.StepType
import me.rerere.rikkahub.data.model.TextStepConfig
import me.rerere.rikkahub.data.model.WorkflowGraph
import me.rerere.rikkahub.data.model.WorkflowStep
import me.rerere.rikkahub.data.model.validate
import me.rerere.rikkahub.data.repository.WorkflowRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * 工作流相关 AI 工具：允许 AI 查看、运行与创建工作流。
 */
fun createWorkflowTools(
    workflowRepository: WorkflowRepository,
    workflowRunner: WorkflowRunner,
): List<Tool> = listOf(
    Tool(
        name = "workflow_list",
        description = """
            List all saved workflows. Returns each workflow's id, name, description and a summary
            of its steps (types only). Use this to discover which workflows exist before running one.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = { false },
        execute = {
            val workflows = workflowRepository.loadAll()
            val payload = buildJsonArray {
                workflows.forEach { workflow ->
                    add(buildJsonObject {
                        put("id", workflow.id)
                        put("name", workflow.name)
                        put("description", workflow.description)
                        put("step_count", workflow.stepCount)
                        put("step_types", buildJsonArray {
                            workflow.effectiveGraph.nodes.forEach { node -> add(JsonPrimitive(node.type.name.lowercase())) }
                        })
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "workflow_run",
        description = """
            Run a saved workflow by its id. Optionally pass input parameters which are injected into
            step templates as {{input.NAME}}. Returns each step's name, status and output so you can
            report the result to the user.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("workflowId", buildJsonObject {
                        put("type", "string")
                        put("description", "The id of the workflow to run")
                    })
                    put("input", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional key-value parameters injected as {{input.NAME}} in step templates")
                    })
                },
                required = listOf("workflowId")
            )
        },
        needsApproval = { true },
        execute = {
            val workflowId = it.jsonObject["workflowId"]?.jsonPrimitive?.contentOrNull
                ?: error("workflowId is required")
            val input = it.jsonObject["input"]?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: v.toString() }
                ?: emptyMap()
            val workflow = workflowRepository.loadWorkflow(workflowId)
                ?: error("Workflow not found: $workflowId")
            val result = workflowRunner.run(workflow = workflow, input = input)
            val payload = buildJsonObject {
                put("workflow_id", result.workflowId)
                put("succeeded", result.succeeded)
                put("nodes", buildJsonArray {
                    result.nodes.forEach { node ->
                        add(buildJsonObject {
                            put("node_id", node.nodeId)
                            put("name", node.nodeName)
                            put("status", node.status.name.lowercase())
                            put("output", node.output.take(2000))
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "workflow_create",
        description = """
            Create or update a workflow. The workflow can later be run manually in the app or via
            workflow_run. Provide a name, optional description, and an ordered list of steps.
            Each step: {"name": string, "type": "text"|"ai"|"shell"|"http"|"delay", "config": {...}}.
            Config shapes:
            - text: {"content": string}
            - ai: {"assistantId": string, "prompt": string} (assistantId optional, defaults to current assistant)
            - shell: {"command": string, "timeoutMillis": number}
            - http: {"method": "GET", "url": string, "headers": object, "body": string, "timeoutMillis": number}
            - delay: {"seconds": number}
            Steps may reference previous step outputs via {{step.N.output}} (N is 1-based step index)
            and input parameters via {{input.NAME}}.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional id to update an existing workflow; omit to create a new one")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Workflow name")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional workflow description")
                    })
                    put("steps", buildJsonObject {
                        put("type", "array")
                        put("description", "Ordered list of workflow steps")
                    })
                },
                required = listOf("name")
            )
        },
        needsApproval = { true },
        execute = {
            val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                ?: error("name is required")
            val description = it.jsonObject["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val stepsJson = it.jsonObject["steps"]?.jsonArray ?: buildJsonArray { }
            val steps = stepsJson.mapIndexed { index, element ->
                parseWorkflowStep(element, index)
            }
            val workflow = if (it.jsonObject["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                workflowRepository.create(name = name, steps = steps)
            } else {
                val id = it.jsonObject["id"]!!.jsonPrimitive.content
                val existing = workflowRepository.loadWorkflow(id)
                    ?: error("Workflow not found: $id")
                workflowRepository.save(existing.copy(name = name, description = description, graph = null, steps = steps))
            }
            val payload = buildJsonObject {
                put("id", workflow.id)
                put("name", workflow.name)
                put("step_count", workflow.stepCount)
                put("message", "Workflow saved")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "workflow_generate",
        description = """
            Generate a workflow as a graph (DAG) from a description. Provide a graph object:
            {"nodes": [{"id": "n1", "type": "start"|"end"|"text"|"ai"|"shell"|"http"|"delay"|"if"|"for"|"merge"|"output", "name": string, "config": {...}, "x": number, "y": number}],
             "edges": [{"fromNodeId": string, "fromPort": "out"|"true"|"false", "toNodeId": string}]}.
            Config shapes:
            - text: {"content": string} (supports {{node.<id>.output}} and {{input.NAME}} variables)
            - ai: {"assistantId": string, "prompt": string}
            - shell: {"command": string, "timeoutMillis": number}
            - http: {"method": "GET", "url": string, "headers": object, "body": string, "timeoutMillis": number}
            - delay: {"seconds": number}
            - if: {"condition": string, e.g. "{{node.n1.output}} > 10"} (outgoing edges use fromPort "true"/"false")
            - for: {"itemsSource": string, "prompt": string, "assistantId": string} (prompt uses {{item}} and {{index}})
            - merge: {} / output: {"template": string} / start|end: {}
            The graph must be acyclic. node ids must be unique strings.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Workflow name")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional workflow description")
                    })
                    put("graph", buildJsonObject {
                        put("type", "object")
                        put("description", "Workflow graph with nodes and edges")
                    })
                },
                required = listOf("name", "graph")
            )
        },
        needsApproval = { true },
        execute = {
            val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                ?: error("name is required")
            val description = it.jsonObject["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val graphJson = it.jsonObject["graph"]
                ?: error("graph is required")
            val graph = JsonInstantPretty.decodeFromString<WorkflowGraph>(graphJson.toString())
            val issues = graph.validate()
            if (issues.isNotEmpty()) {
                error("图校验失败：${issues.joinToString("；")}")
            }
            if (graph.nodes.none { n -> n.type.name == "start" }) {
                error("图中缺少 start 节点")
            }
            if (graph.nodes.none { n -> n.type.name == "end" }) {
                error("图中缺少 end 节点")
            }
            val workflow = workflowRepository.create(name = name, description = description, graph = graph)
            val payload = buildJsonObject {
                put("id", workflow.id)
                put("name", workflow.name)
                put("node_count", workflow.effectiveGraph.nodes.size)
                put("message", "Workflow saved")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

private fun parseWorkflowStep(element: JsonElement, index: Int): WorkflowStep {
    val obj = element.jsonObject
    val typeName = obj["type"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: error("step $index: missing type")
    val type = runCatching { StepType.valueOf(typeName) }
        .getOrElse { error("step $index: unknown step type '$typeName'") }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: "步骤 ${index + 1}"
    val configJson = obj["config"]?.jsonObject
    val config = parseStepConfig(type, configJson, index)
    return WorkflowStep(
        id = Uuid.random().toString(),
        name = name,
        type = type,
        config = config,
    )
}

private fun parseStepConfig(type: StepType, config: JsonObject?, index: Int): StepConfig {
    fun field(name: String, fallback: String): String =
        config?.get(name)?.jsonPrimitive?.contentOrNull ?: fallback

    return when (type) {
        StepType.TEXT -> TextStepConfig(content = field("content", ""))
        StepType.AI -> AiStepConfig(
            assistantId = field("assistantId", ""),
            prompt = field("prompt", ""),
        )
        StepType.SHELL -> ShellStepConfig(
            command = field("command", ""),
            timeoutMillis = config?.get("timeoutMillis")?.jsonPrimitive?.longOrNull ?: 60_000,
        )
        StepType.HTTP -> HttpStepConfig(
            method = field("method", "GET").uppercase(),
            url = field("url", ""),
            headers = config?.get("headers")?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap(),
            body = field("body", ""),
            timeoutMillis = config?.get("timeoutMillis")?.jsonPrimitive?.longOrNull ?: 30_000,
        )
        StepType.DELAY -> DelayStepConfig(
            seconds = config?.get("seconds")?.jsonPrimitive?.intOrNull ?: 1,
        )
    }.also { config ?: error("step $index: missing config") }
}
