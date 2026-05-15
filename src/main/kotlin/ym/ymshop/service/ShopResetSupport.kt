package ym.ymshop.service

import ym.ymshop.model.ResetMode
import ym.ymshop.model.ResetPolicy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal object ShopResetSupport {

    fun currentMarker(policy: ResetPolicy, now: Instant, zoneId: ZoneId): Long? {
        return when (policy.mode) {
            ResetMode.NONE -> null
            ResetMode.TIMED -> {
                val time = parseTime(policy.time) ?: return null
                val zonedNow = now.atZone(zoneId)
                val candidate = zonedNow.toLocalDate().atTime(time).atZone(zoneId)
                if (candidate.toInstant().isAfter(now)) {
                    candidate.minusDays(1).toInstant().toEpochMilli()
                } else {
                    candidate.toInstant().toEpochMilli()
                }
            }

            ResetMode.WEEKLY -> {
                val day = parseDay(policy.day) ?: DayOfWeek.MONDAY
                val time = parseTime(policy.time) ?: LocalTime.MIDNIGHT
                val zonedNow = now.atZone(zoneId)
                var candidate = zonedNow.with(day).toLocalDate().atTime(time).atZone(zoneId)
                if (candidate.toInstant().isAfter(now)) {
                    candidate = candidate.minusWeeks(1)
                }
                candidate.toInstant().toEpochMilli()
            }

            ResetMode.INTERVAL -> {
                val intervalMillis = parseIntervalMillis(policy.interval) ?: return null
                (now.toEpochMilli() / intervalMillis) * intervalMillis
            }
        }
    }

    fun nextResetInstant(policy: ResetPolicy, now: Instant, zoneId: ZoneId): Instant? {
        return when (policy.mode) {
            ResetMode.NONE -> null
            ResetMode.TIMED -> {
                val time = parseTime(policy.time) ?: return null
                val zonedNow = now.atZone(zoneId)
                var candidate = zonedNow.toLocalDate().atTime(time).atZone(zoneId)
                if (!candidate.toInstant().isAfter(now)) {
                    candidate = candidate.plusDays(1)
                }
                candidate.toInstant()
            }

            ResetMode.WEEKLY -> {
                val day = parseDay(policy.day) ?: DayOfWeek.MONDAY
                val time = parseTime(policy.time) ?: LocalTime.MIDNIGHT
                val zonedNow = now.atZone(zoneId)
                var candidate = zonedNow.with(day).toLocalDate().atTime(time).atZone(zoneId)
                if (!candidate.toInstant().isAfter(now)) {
                    candidate = candidate.plusWeeks(1)
                }
                candidate.toInstant()
            }

            ResetMode.INTERVAL -> {
                val intervalMillis = parseIntervalMillis(policy.interval) ?: return null
                Instant.ofEpochMilli(((now.toEpochMilli() / intervalMillis) + 1) * intervalMillis)
            }
        }
    }

    fun resolveScopeAction(previousMarker: Long, currentMarker: Long, currentCount: Long): ResetScopeAction {
        return when {
            previousMarker == 0L && currentCount > 0L -> ResetScopeAction.RESET_AND_MARK
            previousMarker == 0L -> ResetScopeAction.MARK_ONLY
            currentMarker > previousMarker -> ResetScopeAction.RESET_AND_MARK
            else -> ResetScopeAction.NONE
        }
    }

    private fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { LocalTime.parse(raw) }.getOrNull()
    }

    private fun parseDay(raw: String?): DayOfWeek? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { DayOfWeek.valueOf(raw.uppercase()) }.getOrNull()
    }

    private fun parseIntervalMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val value = raw.trim().lowercase()
        val match = Regex("""^(\d+)([smhdw])$""").matchEntire(value) ?: return value.toLongOrNull()
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "s" -> amount * 1000L
            "m" -> amount * 60_000L
            "h" -> amount * 3_600_000L
            "d" -> amount * 86_400_000L
            "w" -> amount * 604_800_000L
            else -> null
        }
    }
}

internal enum class ResetScopeAction {
    NONE,
    MARK_ONLY,
    RESET_AND_MARK
}
