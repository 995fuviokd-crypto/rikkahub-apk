package me.rerere.rikkahub.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 双轨面板声明解析测试（design.md D2.2 / 7.1）：
 * - plugin.json 的 panel 块反序列化为 [PluginPanelSpec]
 * - 缺省值向后兼容：无 panel 块 → null；type 缺省 web；entry 缺省由探测层补齐
 */
class PluginPanelSpecTest {

    private val json = PluginJson.json

    @Test
    fun `plugin json without panel block yields null`() {
        val info = json.decodeFromString(
            PluginInfo.serializer(),
            """{"id":"p1","name":"P1","version":"1.0.0"}""",
        )
        assertNull(info.panel)
    }

    @Test
    fun `schema panel block is parsed with script`() {
        val info = json.decodeFromString(
            PluginInfo.serializer(),
            """
            {"id":"p2","name":"P2","version":"1.0.0",
             "panel": {"type": "schema", "entry": "ui/panel.json", "script": "script/handler.js"}}
            """.trimIndent(),
        )
        val panel = info.panel
        assertEquals(PluginPanelSpec.TYPE_SCHEMA, panel?.type)
        assertEquals("ui/panel.json", panel?.entry)
        assertEquals("script/handler.js", panel?.script)
    }

    @Test
    fun `web panel defaults keep backward compatibility`() {
        val info = json.decodeFromString(
            PluginInfo.serializer(),
            """{"id":"p3","name":"P3","version":"1.0.0","panel": {}}""",
        )
        val panel = info.panel
        assertEquals(PluginPanelSpec.TYPE_WEB, panel?.type)
        assertEquals("", panel?.entry)
        assertEquals("", panel?.script)
        assertEquals("index.html", PluginPanelSpec.defaultEntryFor(panel?.type ?: ""))
    }

    @Test
    fun `unknown panel type is preserved for runtime degradation`() {
        val info = json.decodeFromString(
            PluginInfo.serializer(),
            """{"id":"p4","name":"P4","version":"1.0.0","panel": {"type": "hologram"}}""",
        )
        assertEquals("hologram", info.panel?.type)
        // 未知 type 探测层按 web 轨缺省入口处理
        assertEquals("index.html", PluginPanelSpec.defaultEntryFor("hologram"))
    }
}
