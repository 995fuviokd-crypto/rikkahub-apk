package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工作流图：节点 + 连线的有向无环图（DAG），可视化工作流的存储与执行结构。
 * version=3 起支持边条件（condition）与提取（EXTRACT）节点。
 */
@Serializable
data class WorkflowGraph(
    val version: Int = 3,
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
) {
    val nodeCount: Int get() = nodes.size
    val edgeCount: Int get() = edges.size
}

/**
 * 图中的一个节点，x/y 为画布世界坐标。
 */
@Serializable
data class WorkflowNode(
    val id: String,
    val type: NodeType,
    val name: String = "",
    val config: StepConfig = TextStepConfig(),
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * 连接两个节点的有向边。IF 节点使用 "true"/"false" 端口区分分支。
 *
 * [condition] 为可选的边执行条件（边条件模型）：
 *  - 空/null：源节点成功即沿边传播（默认）
 *  - "success"/"ok"/"on_success"：仅源节点成功时沿边传播
 *  - "error"/"failed"/"on_error"：仅源节点失败时沿边传播（错误处理分支）
 *  - "true"/"false"：按源节点输出的布尔值匹配
 *  - 其它：作为正则表达式匹配源节点输出
 */
@Serializable
data class WorkflowEdge(
    val id: String,
    val fromNodeId: String,
    val fromPort: String = "out",
    val toNodeId: String,
    val toPort: String = "in",
    val condition: String? = null,
)

@Serializable
enum class NodeType {
    @SerialName("start")
    START,

    @SerialName("end")
    END,

    @SerialName("text")
    TEXT,

    @SerialName("ai")
    AI,

    @SerialName("shell")
    SHELL,

    @SerialName("http")
    HTTP,

    @SerialName("delay")
    DELAY,

    @SerialName("if")
    IF,

    @SerialName("for")
    FOR,

    @SerialName("merge")
    MERGE,

    @SerialName("extract")
    EXTRACT,

    @SerialName("output")
    OUTPUT,
}

/**
 * 提取节点的处理模式（数据提取节点）：
 *  - REGEX：从输入中匹配首个正则组（无组则整段匹配）
 *  - JSON：从输入中按 JSONPath 取值
 *  - SUB：截取输入的子串
 *  - CONCAT：拼接固定值/变量得到输出
 */
@Serializable
enum class ExtractMode {
    @SerialName("regex")
    REGEX,

    @SerialName("json")
    JSON,

    @SerialName("sub")
    SUB,

    @SerialName("concat")
    CONCAT,
}

/**
 * 图校验结果。返回非空列表表示存在需要修复的问题。
 */
fun WorkflowGraph.validate(): List<String> {
    val issues = mutableListOf<String>()
    val ids = nodes.map { it.id }
    if (ids.size != ids.distinct().size) {
        issues += "存在重复的节点 id"
    }
    val idSet = ids.toSet()
    for (edge in edges) {
        if (edge.fromNodeId !in idSet) issues += "连线 ${edge.id} 的源节点不存在"
        if (edge.toNodeId !in idSet) issues += "连线 ${edge.id} 的目标节点不存在"
        if (edge.fromNodeId == edge.toNodeId) issues += "连线 ${edge.id} 指向自身"
    }
    if (topologicalOrder() == null) {
        issues += "图中存在循环，无法执行"
    }
    val startCount = nodes.count { it.type == NodeType.START }
    if (startCount > 1) {
        issues += "存在多个开始节点（应为 0 或 1 个）"
    }
    return issues
}

/**
 * Kahn 拓扑排序。返回节点执行顺序；存在环时返回 null。
 */
fun WorkflowGraph.topologicalOrder(): List<String>? {
    val indegree = nodes.associate { it.id to 0 }.toMutableMap()
    val adj = nodes.associate { it.id to mutableListOf<String>() }
    for (edge in edges) {
        if (edge.fromNodeId in indegree && edge.toNodeId in indegree) {
            adj[edge.fromNodeId]!!.add(edge.toNodeId)
            indegree[edge.toNodeId] = indegree[edge.toNodeId]!! + 1
        }
    }
    val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
    val order = mutableListOf<String>()
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        order.add(id)
        for (next in adj[id]!!) {
            indegree[next] = indegree[next]!! - 1
            if (indegree[next] == 0) queue.addLast(next)
        }
    }
    return if (order.size == nodes.size) order else null
}

/**
 * 将旧线性 steps 转换为等价图：START → 逐个步骤串行 → END。
 */
fun legacyStepsToGraph(steps: List<WorkflowStep>): WorkflowGraph {
    if (steps.isEmpty()) return WorkflowGraph()
    val nodes = mutableListOf<WorkflowNode>()
    val edges = mutableListOf<WorkflowEdge>()
    nodes += WorkflowNode(
        id = "start",
        type = NodeType.START,
        name = "开始",
        config = StartStepConfig(),
    )
    var prevId = "start"
    steps.forEachIndexed { index, step ->
        val type = when (step.type) {
            StepType.TEXT -> NodeType.TEXT
            StepType.AI -> NodeType.AI
            StepType.SHELL -> NodeType.SHELL
            StepType.HTTP -> NodeType.HTTP
            StepType.DELAY -> NodeType.DELAY
        }
        val nodeId = "step$index"
        nodes += WorkflowNode(
            id = nodeId,
            type = type,
            name = step.name.ifBlank { step.type.name },
            config = step.config,
            x = 260f * (index + 1),
            y = 0f,
        )
        edges += WorkflowEdge(
            id = "e$index",
            fromNodeId = prevId,
            fromPort = "out",
            toNodeId = nodeId,
            toPort = "in",
        )
        prevId = nodeId
    }
    nodes += WorkflowNode(
        id = "end",
        type = NodeType.END,
        name = "结束",
        config = EndStepConfig(),
        x = 260f * (steps.size + 1),
        y = 0f,
    )
    edges += WorkflowEdge(
        id = "eEnd",
        fromNodeId = prevId,
        fromPort = "out",
        toNodeId = "end",
        toPort = "in",
    )
    return WorkflowGraph(nodes = nodes, edges = edges)
}
