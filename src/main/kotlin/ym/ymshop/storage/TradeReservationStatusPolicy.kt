package ym.ymshop.storage

import ym.ymshop.model.TradeReservationStatus

object TradeReservationStatusPolicy {
    fun canBeginExecution(status: TradeReservationStatus): Boolean {
        return status == TradeReservationStatus.PENDING ||
            status == TradeReservationStatus.EXECUTING ||
            status == TradeReservationStatus.IN_DOUBT
    }

    fun canMarkCommitRetry(status: TradeReservationStatus): Boolean {
        return status == TradeReservationStatus.EXECUTING ||
            status == TradeReservationStatus.IN_DOUBT ||
            status == TradeReservationStatus.COMMIT_RETRY ||
            status == TradeReservationStatus.COMMITTED
    }

    fun canAutoCommit(status: TradeReservationStatus): Boolean {
        return status == TradeReservationStatus.COMMIT_RETRY
    }

    fun canRollback(status: TradeReservationStatus): Boolean {
        return status == TradeReservationStatus.PENDING ||
            status == TradeReservationStatus.EXECUTING ||
            status == TradeReservationStatus.IN_DOUBT
    }

    fun expiredAction(status: TradeReservationStatus): ExpiredReservationAction {
        return when (status) {
            TradeReservationStatus.PENDING -> ExpiredReservationAction.ROLLBACK
            TradeReservationStatus.EXECUTING,
            TradeReservationStatus.IN_DOUBT -> ExpiredReservationAction.ADMIN_REVIEW
            TradeReservationStatus.COMMIT_RETRY,
            TradeReservationStatus.COMMITTED,
            TradeReservationStatus.ROLLED_BACK,
            TradeReservationStatus.ADMIN_REVIEW -> ExpiredReservationAction.IGNORE
        }
    }
}

enum class ExpiredReservationAction {
    ROLLBACK,
    ADMIN_REVIEW,
    IGNORE
}
