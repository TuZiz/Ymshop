package ym.ymshop.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ym.ymshop.model.TradeReservationStatus

class TradeReservationStatusPolicyTest {

    @Test
    fun `executing reservation cannot be auto committed and expires to admin review`() {
        assertTrue(TradeReservationStatusPolicy.canBeginExecution(TradeReservationStatus.PENDING))
        assertFalse(TradeReservationStatusPolicy.canAutoCommit(TradeReservationStatus.EXECUTING))
        assertEquals(
            ExpiredReservationAction.ADMIN_REVIEW,
            TradeReservationStatusPolicy.expiredAction(TradeReservationStatus.EXECUTING)
        )
    }

    @Test
    fun `commit retry is the only auto commit state and is never expired rollback`() {
        assertTrue(TradeReservationStatusPolicy.canMarkCommitRetry(TradeReservationStatus.EXECUTING))
        assertTrue(TradeReservationStatusPolicy.canAutoCommit(TradeReservationStatus.COMMIT_RETRY))
        assertFalse(TradeReservationStatusPolicy.canAutoCommit(TradeReservationStatus.PENDING))
        assertFalse(TradeReservationStatusPolicy.canAutoCommit(TradeReservationStatus.IN_DOUBT))
        assertEquals(
            ExpiredReservationAction.IGNORE,
            TradeReservationStatusPolicy.expiredAction(TradeReservationStatus.COMMIT_RETRY)
        )
    }

    @Test
    fun `pending expires to rollback while in doubt expires to admin review`() {
        assertEquals(
            ExpiredReservationAction.ROLLBACK,
            TradeReservationStatusPolicy.expiredAction(TradeReservationStatus.PENDING)
        )
        assertEquals(
            ExpiredReservationAction.ADMIN_REVIEW,
            TradeReservationStatusPolicy.expiredAction(TradeReservationStatus.IN_DOUBT)
        )
    }

    @Test
    fun `admin review cannot rollback or auto commit`() {
        assertFalse(TradeReservationStatusPolicy.canRollback(TradeReservationStatus.ADMIN_REVIEW))
        assertFalse(TradeReservationStatusPolicy.canAutoCommit(TradeReservationStatus.ADMIN_REVIEW))
        assertEquals(
            ExpiredReservationAction.IGNORE,
            TradeReservationStatusPolicy.expiredAction(TradeReservationStatus.ADMIN_REVIEW)
        )
    }
}
