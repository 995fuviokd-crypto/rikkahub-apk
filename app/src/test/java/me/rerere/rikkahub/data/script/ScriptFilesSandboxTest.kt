package me.rerere.rikkahub.data.script

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptFilesSandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sandbox(): ScriptFilesSandbox = ScriptFilesSandbox(tmp.root)

    @Test
    fun `字符串路径 mkdir write read 全流程`() {
        val sb = sandbox()
        sb.handle("mkdir", listOf(jp("data")))
        val w = sb.handle("write", listOf(jp("data/a.txt"), jp("hello")))
        assertTrue(w["ok"]!!.jsonPrimitive.content == "true")
        val r = sb.handle("read", listOf(jp("data/a.txt")))
        assertEquals("hello", r["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `对象参数 read 与 mkdir 兼容对象参数形态`() {
        val sb = sandbox()
        sb.handle("mkdir", listOf(jp("""{"path":"data","environment":"android"}""")))
        sb.handle("write", listOf(jp("data/f.json"), jp("""{"a":1}""")))
        val r = sb.handle("read", listOf(jp("""{"path":"data/f.json","environment":"android"}""")))
        assertEquals("""{"a":1}""", r["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `对象参数带额外位置参数被忽略`() {
        val sb = sandbox()
        val r = sb.handle("mkdir", listOf(jp("""{"path":"sub/deep","environment":"android"}"""), JsonPrimitive(true), JsonPrimitive("android")))
        assertTrue(r["ok"]!!.jsonPrimitive.content == "true")
        assertTrue(File(tmp.root, "sub/deep").isDirectory)
    }

    @Test
    fun `makeDirectory 与 deleteFile 别名`() {
        val sb = sandbox()
        sb.handle("makeDirectory", listOf(jp("dir")))
        assertTrue(File(tmp.root, "dir").isDirectory)
        sb.handle("write", listOf(jp("dir/x.txt"), jp("v")))
        sb.handle("deleteFile", listOf(jp("dir/x.txt")))
        assertFalse(File(tmp.root, "dir/x.txt").exists())
    }

    @Test
    fun `write 忽略多余环境参数并且 append 生效`() {
        val sb = sandbox()
        sb.handle("write", listOf(jp("log.txt"), jp("l1"), JsonPrimitive(false), jp("linux")))
        sb.handle("write", listOf(jp("log.txt"), jp("l2"), JsonPrimitive(true), jp("linux")))
        val r = sb.handle("read", listOf(jp("log.txt")))
        assertEquals("l1l2", r["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `append 布尔参数接受 true 原始值`() {
        val sb = sandbox()
        jp("""{"path":"b.txt","environment":"android"}""")
        sb.handle("write", listOf(jp("b.txt"), jp("x")))
        sb.handle("write", listOf(jp("b.txt"), jp("y"), JsonPrimitive(true)))
        val r = sb.handle("read", listOf(jp("b.txt")))
        assertEquals("xy", r["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readBinary 同时返回 content 与 contentBase64`() {
        val sb = sandbox()
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        File(tmp.root, "bin.dat").writeBytes(bytes)
        val r = sb.handle("readBinary", listOf(jp("bin.dat")))
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals(b64, r["content"]!!.jsonPrimitive.content)
        assertEquals(b64, r["contentBase64"]!!.jsonPrimitive.content)
    }

    @Test
    fun `writeBinary 落盘为真实二进制`() {
        val sb = sandbox()
        val b64 = "AQIDBA=="
        sb.handle("writeBinary", listOf(jp("img.png"), jp(b64)))
        val bytes = File(tmp.root, "img.png").readBytes()
        assertEquals(4, bytes.size)
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `read 缺失文件返回提升消息`() {
        val sb = sandbox()
        val r = sb.handle("read", listOf(jp("""{"environment":"android"}""")))
        assertTrue(r["message"]!!.jsonPrimitive.content.contains("不存在"))
    }

    @Test
    fun `路径穿越被拦截`() {
        val sb = sandbox()
        sb.handle("write", listOf(jp("../esc.txt"), jp("pwn")))
        assertFalse(File(tmp.root.parentFile, "esc.txt").exists())
        val r = sb.handle("read", listOf(jp("../esc.txt")))
        assertTrue(r["message"]!!.jsonPrimitive.content.contains("不存在"))
    }

    @Test
    fun `exists 返回对象字段`() {
        val sb = sandbox()
        File(tmp.root, "e.txt").writeText("")
        val r = sb.handle("exists", listOf(jp("e.txt")))
        assertTrue(r["exists"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `list 递归返回文件列表`() {
        val sb = sandbox()
        sb.handle("mkdir", listOf(jp("tree")))
        sb.handle("write", listOf(jp("tree/a.js"), jp("")))
        val r = sb.handle("list", listOf(jp("tree")))
        assertTrue(r["ok"]!!.jsonPrimitive.content == "true")
    }

    private fun jp(s: String): kotlinx.serialization.json.JsonElement {
        return when {
            s.startsWith("{") || s.startsWith("[") -> kotlinx.serialization.json.Json.parseToJsonElement(s)
            else -> kotlinx.serialization.json.JsonPrimitive(s)
        }
    }
}