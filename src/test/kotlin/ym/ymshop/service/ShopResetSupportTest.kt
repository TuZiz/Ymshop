package ym.ymshop.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import ym.ymshop.model.ResetMode
import ym.ymshop.model.ResetPolicy
import java.time.Instant
import java.time.ZoneId

class ShopResetSupportTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `timed marker rolls over after the scheduled time`() {
        val policy = ResetPolicy(mode = ResetMode.TIMED, time = "00:00:00")
        val beforeReset = Instant.parse("2026-04-03T15:59:59Z")
        val afterReset = Instant.parse("2026-04-03T16:00:01Z")

        val previousMarker = assertNotNull(ShopResetSupport.currentMarker(policy, beforeReset, zoneId))
        val currentMarker = assertNotNull(ShopResetSupport.currentMarker(policy, afterReset, zoneId))

        assertEquals(
            ResetScopeAction.RESET_AND_MARK,
            ShopResetSupport.resolveScopeAction(previousMarker, currentMarker, currentCount = 64)
        )
    }

    @Test
    fun `timed next reset points to the next midnight in configured zone`() {
        val policy = ResetPolicy(mode = ResetMode.TIMED, time = "00:00:00")
        val now = Instant.parse("2026-04-03T16:00:01Z")

        assertEquals(
            Instant.parse("2026-04-04T16:00:00Z"),
            ShopResetSupport.nextResetInstant(policy, now, zoneId)
        )
    }

    @Test
    fun `timed next reset changes with zone`() {
        val policy = ResetPolicy(mode = ResetMode.TIMED, time = "00:00:00")
        val now = Instant.parse("2026-04-03T16:00:01Z")
        val shanghai = ZoneId.of("Asia/Shanghai")
        val utc = ZoneId.of("UTC")

        assertNotEquals(
            ShopResetSupport.nextResetInstant(policy, now, shanghai),
            ShopResetSupport.nextResetInstant(policy, now, utc)
        )
    }

    @Test
    fun `timed marker advances across midnight boundary`() {
        val policy = ResetPolicy(mode = ResetMode.TIMED, time = "00:00:00")
        val beforeMidnight = Instant.parse("2026-04-03T15:59:59Z")
        val afterMidnight = Instant.parse("2026-04-03T16:00:01Z")

        val beforeMarker = assertNotNull(ShopResetSupport.currentMarker(policy, beforeMidnight, zoneId))
        val afterMarker = assertNotNull(ShopResetSupport.currentMarker(policy, afterMidnight, zoneId))

        assertNotEquals(beforeMarker, afterMarker)
        assertEquals(
            ResetScopeAction.RESET_AND_MARK,
            ShopResetSupport.resolveScopeAction(beforeMarker, afterMarker, currentCount = 1L)
        )
    }

    @Test
    fun `legacy non-zero counter without marker is reset immediately`() {
        assertEquals(
            ResetScopeAction.RESET_AND_MARK,
            ShopResetSupport.resolveScopeAction(previousMarker = 0L, currentMarker = 1_000L, currentCount = 32L)
        )
    }

    @Test
    fun `empty counter without marker only initializes the marker`() {
        assertEquals(
            ResetScopeAction.MARK_ONLY,
            ShopResetSupport.resolveScopeAction(previousMarker = 0L, currentMarker = 1_000L, currentCount = 0L)
        )
    }
}
