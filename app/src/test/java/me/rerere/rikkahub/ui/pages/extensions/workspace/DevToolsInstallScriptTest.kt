package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开发工具安装脚本的结构断言: 保证多源下载函数嵌入、国内镜像兜底与 platform 文件名映射不被误改。
 */
class DevToolsInstallScriptTest {

    private fun render(script: String, version: String = "34"): String =
        script.replace("{{VERSION}}", version)

    @Test
    fun `all scripts embed multi source download helper`() {
        for (script in listOf(
            ANDROID_BUILD_TOOLS_INSTALL_SCRIPT,
            ANDROID_PLATFORM_INSTALL_SCRIPT,
            R8_INSTALL_SCRIPT,
        )) {
            val rendered = render(script)
            assertTrue("missing dl() helper", rendered.contains("dl() {"))
            assertTrue("missing fallback return", rendered.contains("return 1"))
            assertTrue("missing bootstrap for curl/wget", rendered.contains("apt-get install -y -qq curl"))
        }
    }

    @Test
    fun `build tools script falls back to tencent mirror`() {
        val rendered = render(ANDROID_BUILD_TOOLS_INSTALL_SCRIPT)
        assertTrue(
            rendered.contains("https://dl.google.com/android/repository/build-tools_r${'$'}BT_VERSION-linux.zip"),
        )
        assertTrue(
            rendered.contains("https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r${'$'}BT_VERSION-linux.zip"),
        )
    }

    @Test
    fun `platform script maps version to official ext7 file name`() {
        val rendered = render(ANDROID_PLATFORM_INSTALL_SCRIPT, version = "34")
        assertTrue(rendered.contains("34) NAME=platform-34-ext7_r02.zip"))
        assertTrue(rendered.contains("35) NAME=platform-35_r02.zip"))
        // 源码内 URL 以变量拼接, 校验镜像前缀与 dl 候选调用存在
        assertTrue(rendered.contains("https://mirrors.cloud.tencent.com/AndroidSDK/"))
    }

    @Test
    fun `r8 script falls back to aliyun google mirror`() {
        val rendered = render(R8_INSTALL_SCRIPT, version = "8.2.33")
        assertTrue(rendered.contains("https://maven.google.com/com/android/tools/r8/${'$'}V/r8-${'$'}V.jar"))
        assertTrue(
            rendered.contains("https://maven.aliyun.com/repository/google/com/android/tools/r8/${'$'}V/r8-${'$'}V.jar"),
        )
    }

    @Test
    fun `scripts fail with explicit message when all sources exhausted`() {
        for (script in listOf(
            ANDROID_BUILD_TOOLS_INSTALL_SCRIPT,
            ANDROID_PLATFORM_INSTALL_SCRIPT,
            R8_INSTALL_SCRIPT,
        )) {
            val rendered = render(script)
            assertTrue("missing all-sources-failed error", rendered.contains("all download sources failed"))
        }
    }
}
