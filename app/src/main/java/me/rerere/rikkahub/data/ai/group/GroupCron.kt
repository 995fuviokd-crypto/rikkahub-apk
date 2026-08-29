package me.rerere.rikkahub.data.ai.group

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 轻量 cron 表达式解析与下一次触发时间计算。
 *
 * 支持 5 段：`分 时 日 月 周`
 * - 每 n 单位（step）、区间（a-b）、枚举（a,b,c）均可组合，如每 30 分钟
 * - 日/周同时限制时按"或"处理（与标准 cron 一致：任一满足即触发）
 *
 * 不支持的复杂语法（如秒、年、L/W/#）直接返回 null，UI 侧给出提示。
 */
object GroupCron {
    data class ParsedCron(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>, // 1=周日 ... 7=周六
        val raw: String,
    ) {
        fun matches(time: LocalDateTime): Boolean {
            val monthOk = months.contains(time.monthValue)
            val domOk = daysOfMonth.contains(time.dayOfMonth)
            val dowOk = daysOfWeek.contains(time.dayOfWeek.value) // 1=周一..7=周日
            val domOrDow = daysOfMonth.contains(0) && daysOfWeek.contains(0) ||
                domOk || dowOk
            return minutes.contains(time.minute) &&
                hours.contains(time.hour) &&
                domOrDow &&
                monthOk
        }
    }

    /**
     * 解析 5 段 cron。非法输入返回 null。
     * 周段支持 1-7（1=周日，与 Quartz 一致）与 0（=7 周日）。
     */
    fun parse(expression: String): ParsedCron? {
        val expr = expression.trim().lowercase()
        if (expr.isBlank()) return null
        val parts = expr.split(Regex("\\s+"))
        if (parts.size != 5) return null

        val minutes = parseField(parts[0], 0, 59) ?: return null
        val hours = parseField(parts[1], 0, 23) ?: return null
        val daysOfMonth = parseField(parts[2], 1, 31) ?: return null
        val months = parseField(parts[3], 1, 12) ?: return null
        val dow = parseField(parts[4], 0, 7) ?: return null
        // 0 == 7 均为周日；转成 1..7（1=周日）
        val daysOfWeek = dow.map { if (it == 0) 7 else it }.toSet()

        return ParsedCron(
            minutes = minutes,
            hours = hours,
            daysOfMonth = daysOfMonth,
            months = months,
            daysOfWeek = daysOfWeek,
            raw = expression.trim(),
        )
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
        if (field == "*") return (min..max).toSet()
        val result = mutableSetOf<Int>()
        for (part in field.split(",")) {
            val stepMatch = Regex("^(\\*|\\d+-\\d+|\\d+)/(\\d+)$").matchEntire(part)
            if (stepMatch != null) {
                val base = stepMatch.groupValues[1]
                val step = stepMatch.groupValues[2].toIntOrNull() ?: return null
                if (step <= 0) return null
                val range = if (base == "*") {
                    min..max
                } else {
                    val dash = base.split("-")
                    val start = dash[0].toIntOrNull() ?: return null
                    val end = dash.getOrNull(1)?.toIntOrNull() ?: return null
                    if (start < min || end > max || start > end) return null
                    start..end
                }
                for (v in range) {
                    if ((v - range.first) % step == 0) {
                        if (v in min..max) result.add(v)
                    }
                }
                continue
            }

            val rangeMatch = Regex("^(\\d+)-(\\d+)$").matchEntire(part)
            if (rangeMatch != null) {
                val start = rangeMatch.groupValues[1].toIntOrNull() ?: return null
                val end = rangeMatch.groupValues[2].toIntOrNull() ?: return null
                if (start < min || end > max || start > end) return null
                for (v in start..end) result.add(v)
                continue
            }

            val v = part.toIntOrNull() ?: return null
            if (v !in min..max) return null
            result.add(v)
        }
        return result
    }

    /**
     * 计算自 [from] 起（不含 [from] 本身）下一次匹配的时间；没有则返回 null。
     */
    fun nextRun(cron: ParsedCron, from: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        // 只扫未来 2 年，避免极端表达式死循环
        var candidate = from.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
        val limit = from.plusYears(2)
        var guard = 0
        while (guard < 200_000 && !candidate.isAfter(limit)) {
            if (cron.matches(candidate)) return candidate
            candidate = candidate.plusMinutes(1)
            guard++
        }
        return null
    }

    /** 是否配置了有效的定时任务 */
    fun isScheduled(raw: String?): Boolean = raw?.let { parse(it) != null } ?: false

    /** 给 UI 的友好描述，如 "每 30 分钟" / "每天 09:00" */
    fun describe(raw: String?): String? {
        val cron = raw?.let { parse(it) } ?: return null
        val minuteSet = cron.minutes
        val hourSet = cron.hours
        // 每日整点
        if (minuteSet.size == 1 && minuteSet.first() == 0) {
            if (hourSet.size == 1) {
                val h = hourSet.first()
                return "每天 ${h.toString().padStart(2, '0')}:00"
            }
            if (hourSet.size == 24) return "每小时整点"
        }
        if (minuteSet.size == 2 && minuteSet == setOf(0, 30) && hourSet.size == 24) {
            return "每 30 分钟"
        }
        val step = detectMinuteStep(cron)
        if (step != null && hourSet.size == 24) {
            return if (step % 60 == 0) "每 ${step / 60} 小时" else "每 $step 分钟"
        }
        return raw
    }

    private fun detectMinuteStep(cron: ParsedCron): Int? {
        val minutes = cron.minutes.sorted()
        if (minutes.size < 2) return null
        val step = minutes[1] - minutes[0]
        if (step <= 0) return null
        if (minutes.zipWithNext().all { (a, b) -> b - a == step } &&
            (60 - minutes.last() + minutes.first()) == step
        ) {
            return step
        }
        return null
    }
}
