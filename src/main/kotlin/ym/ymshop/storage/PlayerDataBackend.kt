package ym.ymshop.storage

import ym.ymshop.model.TradeLimitRules
import ym.ymshop.model.TradeReservation
import ym.ymshop.model.TradeSide
import java.util.UUID

interface FavoriteRepository {
    fun loadFavorites(): Map<UUID, List<FavoriteEntry>>

    fun saveFavorites(playerId: UUID, entries: Collection<FavoriteEntry>)
}

interface DailyTradeRepository {
    fun loadDailyTradeTotals(): Map<DailyTradeKey, DailyCurrencyTotals>

    fun saveDailyTradeTotal(key: DailyTradeKey, totals: DailyCurrencyTotals)
}

interface ShopStatsRepository {
    fun loadShopSnapshot(shopId: String): ShopStatsSnapshot

    fun saveShopChanges(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    )

    fun saveShopChangesStrict(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ): Boolean

    val supportsAtomicShopStats: Boolean
        get() = false

    fun reserveShopTradeStats(
        shopId: String,
        entryId: String,
        playerId: UUID,
        side: TradeSide,
        amount: Int,
        limits: TradeLimitRules,
        nowMillis: Long,
        zoneId: java.time.ZoneId
    ): AtomicShopStatsResult {
        return AtomicShopStatsResult.Unavailable
    }

    fun rollbackShopTradeStats(reservation: TradeReservation): Boolean {
        return false
    }
}

interface PlayerDataBackend : FavoriteRepository, DailyTradeRepository, ShopStatsRepository {
    val settings: DatabaseSettings

    fun flush()

    fun close()

    override fun saveShopChangesStrict(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ): Boolean {
        saveShopChanges(shopId, snapshot, dirtyPlayerKeys, dirtyGlobalEntryIds)
        flush()
        return true
    }
}

sealed class AtomicShopStatsResult {
    data class Reserved(
        val reservation: TradeReservation,
        val stats: ym.ymshop.model.EntryStats
    ) : AtomicShopStatsResult()

    data class LimitExceeded(
        val messageKey: String,
        val limit: Long
    ) : AtomicShopStatsResult()

    data class Failed(
        val message: String
    ) : AtomicShopStatsResult()

    data object Unavailable : AtomicShopStatsResult()
}
