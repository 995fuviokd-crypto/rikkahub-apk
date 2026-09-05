package me.rerere.rikkahub.ui.schema

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema 轨面板数据层单测（design.md D2.2 / 7.5）：
 * - panel.json 解析：合法结构、空 components 拒绝、非法 JSON 拒绝
 * - props 宽松读取：缺失/类型不符回退默认值（schema 来自插件包不可信任）
 * - 未知组件判定：KNOWN_TYPES 外的类型由渲染器降级占位
 * - key diff 数据基线：组件 key 稳定性约定
 */
class PluginPanelSchemaTest {

    @Test
    fun `parse valid schema with nested children`() {
        val json = """
            {
              "version": 1,
              "title": "统计面板",
              "components": [
                {
                  "key": "card1",
                  "type": "card",
                  "props": { "title": "统计", "subtitle": "实时" },
                  "children": [
                    { "key": "t1", "type": "text", "props": { "text": "共 3 条" } },
                    { "key": "b1", "type": "button", "props": { "label": "刷新", "action": "refresh" } }
                  ]
                }
              ]
            }
        """.trimIndent()
        val schema = PluginPanelSchemaParser.parse(json)
        assertNotNull(schema)
        assertEquals("统计面板", schema?.title)
        assertEquals(1, schema?.components?.size)
        val card = schema?.components?.first()
        assertEquals("card", card?.type)
        assertEquals(2, card?.children?.size)
        assertEquals("button", card?.children?.get(1)?.type)
        assertEquals("refresh", card?.children?.get(1)?.props?.propString("action"))
    }

    @Test
    fun `parse rejects empty components`() {
        val schema = PluginPanelSchemaParser.parse("""{"version":1,"title":"空","components":[]}""")
        assertNull(schema)
    }

    @Test
    fun `parse rejects malformed json`() {
        assertNull(PluginPanelSchemaParser.parse("not-json"))
        assertNull(PluginPanelSchemaParser.parse("{\"components\": 42}"))
    }

    @Test
    fun `component with missing type parses loosely and degrades at render layer`() {
        // 缺 type 的组件解析为空 type（合法），渲染层降级占位（见 unknown types 用例）
        val schema = PluginPanelSchemaParser.parse("""{"components": [{"key":"a"}]}""")
        assertNotNull(schema)
        assertEquals("", schema?.components?.first()?.type)
    }

    @Test
    fun `props readers fall back to defaults on missing or wrong types`() {
        val props = buildJsonObject {
            put("name", "rikkahub")
            put("count", 3)
            put("ratio", 0.5)
            put("enabled", true)
            put("empty", "")
        }
        assertEquals("rikkahub", props.propString("name"))
        assertEquals("fallback", props.propString("missing", default = "fallback"))
        // 空串视为缺省，命中级联 key
        assertEquals("fallback", props.propString("empty", "missing", default = "fallback"))
        assertEquals(3, props.propInt("count", default = 0))
        assertEquals(9, props.propInt("missing", default = 9))
        assertEquals(0, props.propInt("name", default = 0))
        assertEquals(0.5, props.propDouble("ratio", default = 1.0), 1e-9)
        assertEquals(1.0, props.propDouble("missing", default = 1.0), 1e-9)
        assertTrue(props.propBoolean("enabled", default = false))
        assertFalse(props.propBoolean("missing", default = false))
        assertFalse(props.propBoolean("name", default = false))
    }

    @Test
    fun `props tolerate null and object values without crash`() {
        val props = buildJsonObject {
            put("nullValue", kotlinx.serialization.json.JsonNull)
            put("objValue", buildJsonObject { put("a", 1) })
            put("arrValue", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(1)) })
        }
        assertEquals("d", props.propString("nullValue", default = "d"))
        assertEquals("d", props.propString("objValue", default = "d"))
        assertEquals(7, props.propInt("arrValue", default = 7))
        assertEquals(0.25, props.propDouble("objValue", default = 0.25), 1e-9)
        assertTrue(props.propBoolean("nullValue", default = true))
    }

    @Test
    fun `unknown component types are distinguishable for degradation`() {
        val json = """
            {"components": [
              {"key": "ok", "type": "text", "props": {"text": "正常"}},
              {"key": "bad", "type": " hologram ", "props": {}},
              {"key": "none", "type": "", "props": {}}
            ]}
        """.trimIndent()
        val schema = PluginPanelSchemaParser.parse(json)
        assertNotNull(schema)
        val types = schema!!.components.map { it.type }
        assertTrue(types[0] in PluginPanelComponent.KNOWN_TYPES)
        // 未知类型（含空白/空串）由渲染器降级占位，数据层可识别
        assertFalse(types[1].trim() in PluginPanelComponent.KNOWN_TYPES)
        assertFalse(types[2] in PluginPanelComponent.KNOWN_TYPES)
    }

    @Test
    fun `component keys drive incremental re-render identity`() {
        // key 稳定时 Compose LazyColumn item key 复用；key 缺失回退位置索引
        val json = """
            {"components": [
              {"key": "k1", "type": "text", "props": {"text": "v1"}},
              {"key": "k2", "type": "text", "props": {"text": "v2"}},
              {"type": "text", "props": {"text": "无 key"}}
            ]}
        """.trimIndent()
        val schema = PluginPanelSchemaParser.parse(json)!!
        val keys = schema.components.mapIndexed { index, c -> c.key.ifBlank { "component-$index" } }
        assertEquals(listOf("k1", "k2", "component-2"), keys)
    }
}
