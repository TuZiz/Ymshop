package ym.ymshop.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ym.ymshop.model.TradeMode
import ym.ymshop.model.TradeSide
import ym.ymshop.storage.AtomicShopStatsResult
import ym.ymshop.storage.DailyCurrencyTotals
import ym.ymshop.storage.DailyTradeKey
import ym.ymshop.storage.DatabaseSettings
import ym.ymshop.storage.DatabaseType
import ym.ymshop.storage.FavoriteEntry
import ym.ymshop.storage.PlayerDataBackend
import ym.ymshop.storage.ShopPlayerStatsKey
import ym.ymshop.storage.ShopStatsSnapshot
import java.time.LocalDate
import java.util.UUID

class StorageBackedDataServicesTest {

    @Test
    fun `favorite service normalizes favorites and persists toggles`() {
        val backend = FakePlayerDataBackend()
        val service = FavoriteService(backend)
        val playerId = UUID.randomUUID()

        assertFalse(service.contains(playerId, "Main", "Diamond"))

        assertTrue(service.toggle(playerId, "Main", "Diamond"))
        assertTrue(service.contains(playerId, "main", "diamond"))
        assertEquals(listOf(FavoriteEntry("main", "diamond")), service.entries(playerId))

        assertFalse(service.toggle(playerId, "MAIN", "DIAMOND"))
        assertEquals(emptyList(), service.entries(playerId))
        assertEquals(emptyList(), backend.favoriteRows[playerId].orEmpty())
    }

    @Test
    fun `daily trade store keeps in memory totals and persists updates`() {
        val backend = FakePlayerDataBackend()
        val store = PlayerDailyTradeStore(backend)
        val playerId = UUID.randomUUID()
        val day = LocalDate.of(2026, 4, 13)

        store.record(playerId, day, "Vault", TradeSide.BUY, 125L)
        store.record(playerId, day, "VAULT", TradeSide.SELL, 40L)

        assertEquals(
            DailyCurrencyTotals(buySpent = 125L, sellEarned = 40L),
            store.totals(playerId, day, "vault")
        )
        assertEquals(
            DailyCurrencyTotals(buySpent = 125L, sellEarned = 40L),
            backend.dailyRows[DailyTradeKey(playerId, day, "vault")]
        )
    }

    @Test
    fun `shop data store flushes immediate writes and delayed save writes`() {
        val backend = FakePlayerDataBackend()
        val playerId = UUID.randomUUID()
        val store = ShopDataStore("main", backend)

        store.record("diamond", playerId, TradeMode.BUY, 5)
        assertEquals(setOf(playerId), store.playerIds("diamond"))
        assertEquals(5L, backend.snapshots.getValue("main").playerRows.getValue(ShopPlayerStatsKey(playerId, "diamond")).buy)
        assertEquals(5L, backend.snapshots.getValue("main").globalRows.getValue("diamond").buy)

        store.setSideResetMarker("diamond", playerId, TradeSide.BUY, ShopDataStore.ResetScope.PLAYER, 77L, save = false)
        store.resetSide("diamond", playerId, TradeSide.BUY, ShopDataStore.ResetScope.PLAYER, save = false)

        assertEquals(0L, store.sideCount("diamond", playerId, TradeSide.BUY, ShopDataStore.ResetScope.PLAYER))
        assertEquals(77L, store.sideResetMarker("diamond", playerId, TradeSide.BUY, ShopDataStore.ResetScope.PLAYER))
        assertEquals(5L, backend.snapshots.getValue("main").playerRows.getValue(ShopPlayerStatsKey(playerId, "diamond")).buy)

        store.save()

        val persisted = backend.snapshots.getValue("main").playerRows.getValue(ShopPlayerStatsKey(playerId, "diamond"))
        assertEquals(0L, persisted.buy)
        assertEquals(77L, persisted.buyResetMarker)
        assertTrue(backend.flushCount > 0)
    }

    @Test
    fun `yaml backend does not advertise atomic cross-server shop stats`() {
        val backend = FakePlayerDataBackend()

        assertFalse(backend.supportsAtomicShopStats)
        assertEquals(
            AtomicShopStatsResult.Unavailable,
            backend.reserveShopTradeStats(
                shopId = "main",
                entryId = "diamond",
                playerId = UUID.randomUUID(),
                side = TradeSide.BUY,
                amount = 1,
                limits = ym.ymshop.model.TradeLimitRules(),
                nowMillis = 0L,
                zoneId = java.time.ZoneId.of("UTC")
            )
        )
        assertFalse(backend.commitShopTradeStats(sampleReservation()))
        assertFalse(backend.prepareShopTradeStatsCommit(sampleReservation()))
        assertFalse(backend.rollbackShopTradeStats(sampleReservation()))
        assertEquals(0, backend.recoverExpiredShopTradeReservations(1_000L))
        assertEquals(
            null,
            backend.loadShopEntryStats(
                shopId = "main",
                entryId = "diamond",
                playerId = UUID.randomUUID(),
                limits = ym.ymshop.model.TradeLimitRules(),
                nowMillis = 0L,
                zoneId = java.time.ZoneId.of("UTC")
            )
        )
    }

    private fun sampleReservation(): ym.ymshop.model.TradeReservation {
        val now = java.time.Instant.EPOCH
        return ym.ymshop.model.TradeReservation(
            reservationId = UUID.randomUUID(),
            shopId = "main",
            entryId = "diamond",
            playerId = UUID.randomUUID(),
            side = TradeSide.BUY,
            amount = 1,
            playerBuyResetMarker = 0L,
            playerSellResetMarker = 0L,
            globalBuyResetMarker = 0L,
            globalSellResetMarker = 0L,
            reservedAt = now,
            expiresAt = now.plusSeconds(300)
        )
    }

    private class FakePlayerDataBackend : PlayerDataBackend {
        override val settings: DatabaseSettings = DatabaseSettings(
            type = DatabaseType.YAML,
            host = "",
            port = 0,
            database = "",
            username = "",
            password = "",
            poolSize = 1,
            ssl = false
        )

        val favoriteRows = linkedMapOf<UUID, MutableList<FavoriteEntry>>()
        val dailyRows = linkedMapOf<DailyTradeKey, DailyCurrencyTotals>()
        val snapshots = linkedMapOf<String, ShopStatsSnapshot>()
        var flushCount = 0

        override fun loadFavorites(): Map<UUID, List<FavoriteEntry>> {
            return favoriteRows.mapValues { (_, entries) -> entries.toList() }
        }

        override fun saveFavorites(playerId: UUID, entries: Collection<FavoriteEntry>) {
            favoriteRows[playerId] = entries.toMutableList()
        }

        override fun loadDailyTradeTotals(): Map<DailyTradeKey, DailyCurrencyTotals> {
            return dailyRows.toMap()
        }

        override fun saveDailyTradeTotal(key: DailyTradeKey, totals: DailyCurrencyTotals) {
            dailyRows[key] = totals
        }

        override fun loadShopSnapshot(shopId: String): ShopStatsSnapshot {
            return snapshots.getOrPut(shopId) { ShopStatsSnapshot() }.deepCopy()
        }

        override fun saveShopChanges(
            shopId: String,
            snapshot: ShopStatsSnapshot,
            dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
            dirtyGlobalEntryIds: Set<String>
        ) {
            val target = snapshots.getOrPut(shopId) { ShopStatsSnapshot() }
            dirtyPlayerKeys.forEach { key ->
                val row = snapshot.playerRows[key] ?: return@forEach
                target.playerRows[key] = row.copy()
            }
            dirtyGlobalEntryIds.forEach { entryId ->
                val row = snapshot.globalRows[entryId] ?: return@forEach
                target.globalRows[entryId] = row.copy()
            }
        }

        override fun flush() {
            flushCount++
        }

        override fun close() = Unit

        private fun ShopStatsSnapshot.deepCopy(): ShopStatsSnapshot {
            val playerRowsCopy = playerRows.mapValuesTo(linkedMapOf()) { (_, row) -> row.copy() }.toMutableMap()
            val globalRowsCopy = globalRows.mapValuesTo(linkedMapOf()) { (_, row) -> row.copy() }.toMutableMap()
            return ShopStatsSnapshot(playerRowsCopy, globalRowsCopy)
        }
    }
}
