package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCardShowLogicTest {

    private val publishedAt = "2026-08-16T20:00:00Z"
    private val publishedAtMillis = java.time.Instant.parse(publishedAt).toEpochMilli()

    @Test
    fun `higher version always shows update`() {
        // 远端版本更高，无论安装时间都推送
        assertTrue(
            shouldShowUpdate(
                latestVersion = "2.4.18",
                currentVersion = "2.4.17",
                latestPublishedAt = publishedAt,
                localInstallTimeMillis = 0L,
            )
        )
    }

    @Test
    fun `same version with later publish than install shows update`() {
        // 同版本号重新构建（修复后重发）：发布时间晚于本地安装时间 -> 覆盖推送
        assertTrue(
            shouldShowUpdate(
                latestVersion = "2.4.17",
                currentVersion = "2.4.17",
                latestPublishedAt = publishedAt,
                localInstallTimeMillis = publishedAtMillis - 60_000L,
            )
        )
    }

    @Test
    fun `same version with earlier publish than install does not show update`() {
        // 已是最新且远端发布时间早于安装时间：正常最新状态，不打扰
        assertFalse(
            shouldShowUpdate(
                latestVersion = "2.4.17",
                currentVersion = "2.4.17",
                latestPublishedAt = publishedAt,
                localInstallTimeMillis = publishedAtMillis + 60_000L,
            )
        )
    }

    @Test
    fun `lower version with later publish still shows update`() {
        // 远端版本号更低但发布时间更新（覆盖推送场景）-> 仍提示更新
        assertTrue(
            shouldShowUpdate(
                latestVersion = "2.4.11",
                currentVersion = "2.4.18",
                latestPublishedAt = publishedAt,
                localInstallTimeMillis = publishedAtMillis - 60_000L,
            )
        )
    }

    @Test
    fun `invalid publish date falls back to version comparison`() {
        // 发布时间解析失败：退化为纯版本号比较
        assertTrue(
            shouldShowUpdate(
                latestVersion = "2.4.18",
                currentVersion = "2.4.17",
                latestPublishedAt = "not-a-date",
                localInstallTimeMillis = 0L,
            )
        )
        assertFalse(
            shouldShowUpdate(
                latestVersion = "2.4.17",
                currentVersion = "2.4.18",
                latestPublishedAt = "not-a-date",
                localInstallTimeMillis = 0L,
            )
        )
    }
}
