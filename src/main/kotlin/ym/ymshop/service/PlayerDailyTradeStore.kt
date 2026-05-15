package ym.ymshop.service

import ym.ymshop.model.TradeSide
import ym.ymshop.storage.DailyCurrencyTotals
import ym.ymshop.storage.DailyTradeKey
import ym.ymshop.storage.PlayerDataBackend
import java.time.LocalDate
import java.util.UUID

class PlayerDailyTradeStore(private val backend: PlayerDataBackend) {

    private val totals = backend.loadDailyTradeTotals().toMutableMap()

    @Synchronized
    fun record(playerId: UUID, day: LocalDate, currencyId: String, side: TradeSide, amount: Long) {
        val key = DailyTradeKey(playerId, day, currencyId.lowercase())
        val current = totals[key] ?: DailyCurrencyTotals(buySpent = 0L, sellEarned = 0L)
        val updated = when (side) {
            TradeSide.BUY -> current.copy(buySpent = current.buySpent + amount)
            TradeSide.SELL -> current.copy(sellEarned = current.sellEarned + amount)
        }
        totals[key] = updated
        backend.saveDailyTradeTotal(key, updated)
    }

    @Synchronized
    fun totals(playerId: UUID, day: LocalDate, currencyId: String): DailyCurrencyTotals {
        return totals[DailyTradeKey(playerId, day, currencyId.lowercase())]
            ?: DailyCurrencyTotals(buySpent = 0L, sellEarned = 0L)
    }

    @Synchronized
    fun save() {
        backend.flush()
    }
}
