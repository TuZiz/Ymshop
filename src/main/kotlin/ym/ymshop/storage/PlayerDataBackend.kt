package ym.ymshop.storage

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
}

interface PlayerDataBackend : FavoriteRepository, DailyTradeRepository, ShopStatsRepository {
    val settings: DatabaseSettings

    fun flush()

    fun close()
}
