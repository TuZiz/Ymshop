package ym.ymshop.service

import ym.ymshop.model.EntryStats
import ym.ymshop.model.TradeMode
import ym.ymshop.model.TradeSide
import ym.ymshop.storage.PlayerDataBackend
import ym.ymshop.storage.ShopPlayerStatsKey
import ym.ymshop.storage.ShopStatsRow
import java.util.UUID

class ShopDataStore(
    private val shopId: String,
    private val backend: PlayerDataBackend
) {

    private val snapshot = backend.loadShopSnapshot(shopId)
    private val lock = Any()
    private val dirtyPlayerKeys = linkedSetOf<ShopPlayerStatsKey>()
    private val dirtyGlobalEntryIds = linkedSetOf<String>()

    fun stats(entryId: String, playerId: UUID): EntryStats {
        return synchronized(lock) {
            val playerRow = snapshot.playerRows[ShopPlayerStatsKey(playerId, entryId)] ?: ShopStatsRow()
            val globalRow = snapshot.globalRows[entryId] ?: ShopStatsRow()
            EntryStats(
                playerTotal = playerRow.total,
                playerBuy = playerRow.buy,
                playerSell = playerRow.sell,
                globalTotal = globalRow.total,
                globalBuy = globalRow.buy,
                globalSell = globalRow.sell
            )
        }
    }

    fun record(entryId: String, playerId: UUID, mode: TradeMode, amount: Int, waitForPersistence: Boolean = false): Boolean {
        synchronized(lock) {
            val playerRow = playerRow(playerId, entryId)
            val globalRow = globalRow(entryId)
            playerRow.total += amount
            globalRow.total += amount

            when (mode) {
                TradeMode.BUY -> {
                    playerRow.buy += amount
                    globalRow.buy += amount
                }

                TradeMode.SELL -> {
                    playerRow.sell += amount
                    globalRow.sell += amount
                }

                TradeMode.BOTH -> Unit
            }
            markDirty(playerId, entryId)
            markDirty(entryId)
        }
        return flushDirty(waitForCompletion = waitForPersistence)
    }

    fun sideResetMarker(entryId: String, playerId: UUID, side: TradeSide, scope: ResetScope): Long {
        return synchronized(lock) {
            val row = if (scope == ResetScope.PLAYER) {
                snapshot.playerRows[ShopPlayerStatsKey(playerId, entryId)] ?: ShopStatsRow()
            } else {
                snapshot.globalRows[entryId] ?: ShopStatsRow()
            }
            if (side == TradeSide.BUY) row.buyResetMarker else row.sellResetMarker
        }
    }

    fun setSideResetMarker(
        entryId: String,
        playerId: UUID,
        side: TradeSide,
        scope: ResetScope,
        marker: Long,
        save: Boolean = true
    ) {
        synchronized(lock) {
            val row = if (scope == ResetScope.PLAYER) playerRow(playerId, entryId) else globalRow(entryId)
            if (side == TradeSide.BUY) {
                row.buyResetMarker = marker
            } else {
                row.sellResetMarker = marker
            }
            if (scope == ResetScope.PLAYER) {
                markDirty(playerId, entryId)
            } else {
                markDirty(entryId)
            }
        }
        if (save) {
            flushDirty(waitForCompletion = false)
        }
    }

    fun sideCount(entryId: String, playerId: UUID, side: TradeSide, scope: ResetScope): Long {
        return synchronized(lock) {
            when (scope) {
                ResetScope.PLAYER -> {
                    val row = snapshot.playerRows[ShopPlayerStatsKey(playerId, entryId)] ?: ShopStatsRow()
                    if (side == TradeSide.BUY) row.buy else row.sell
                }

                ResetScope.GLOBAL -> {
                    val row = snapshot.globalRows[entryId] ?: ShopStatsRow()
                    if (side == TradeSide.BUY) row.buy else row.sell
                }
            }
        }
    }

    fun resetSide(entryId: String, playerId: UUID, side: TradeSide, scope: ResetScope, save: Boolean = true) {
        synchronized(lock) {
            val row = if (scope == ResetScope.PLAYER) playerRow(playerId, entryId) else globalRow(entryId)
            if (side == TradeSide.BUY) {
                row.buy = 0L
            } else {
                row.sell = 0L
            }
            if (scope == ResetScope.PLAYER) {
                markDirty(playerId, entryId)
            } else {
                markDirty(entryId)
            }
        }
        if (save) {
            flushDirty(waitForCompletion = false)
        }
    }

    fun playerIds(entryId: String): Set<UUID> {
        return synchronized(lock) {
            snapshot.playerRows.keys.asSequence()
                .filter { it.entryId == entryId }
                .map { it.playerId }
                .toSet()
        }
    }

    fun save() {
        flushDirty(waitForCompletion = true)
    }

    fun saveAsync() {
        flushDirty(waitForCompletion = false)
    }

    private fun playerRow(playerId: UUID, entryId: String): ShopStatsRow {
        return snapshot.playerRows.getOrPut(ShopPlayerStatsKey(playerId, entryId)) { ShopStatsRow() }
    }

    private fun globalRow(entryId: String): ShopStatsRow {
        return snapshot.globalRows.getOrPut(entryId) { ShopStatsRow() }
    }

    private fun markDirty(playerId: UUID, entryId: String) {
        dirtyPlayerKeys += ShopPlayerStatsKey(playerId, entryId)
    }

    private fun markDirty(entryId: String) {
        dirtyGlobalEntryIds += entryId
    }

    private fun flushDirty(waitForCompletion: Boolean): Boolean {
        val playerKeys: Set<ShopPlayerStatsKey>
        val globalKeys: Set<String>
        val dirtySnapshot: ym.ymshop.storage.ShopStatsSnapshot
        synchronized(lock) {
            playerKeys = dirtyPlayerKeys.toSet()
            globalKeys = dirtyGlobalEntryIds.toSet()
            if (playerKeys.isEmpty() && globalKeys.isEmpty()) {
                return true
            }
            dirtySnapshot = ym.ymshop.storage.ShopStatsSnapshot(
                playerRows = playerKeys.mapNotNull { key ->
                    snapshot.playerRows[key]?.copy()?.let { key to it }
                }.toMap(linkedMapOf()),
                globalRows = globalKeys.mapNotNull { entryId ->
                    snapshot.globalRows[entryId]?.copy()?.let { entryId to it }
                }.toMap(linkedMapOf())
            )
            dirtyPlayerKeys.clear()
            dirtyGlobalEntryIds.clear()
        }
        return if (waitForCompletion) {
            val persisted = backend.saveShopChangesStrict(shopId, dirtySnapshot, playerKeys, globalKeys)
            if (!persisted) {
                synchronized(lock) {
                    dirtyPlayerKeys += playerKeys
                    dirtyGlobalEntryIds += globalKeys
                }
            }
            persisted
        } else {
            backend.saveShopChanges(shopId, dirtySnapshot, playerKeys, globalKeys)
            true
        }
    }

    enum class ResetScope {
        PLAYER,
        GLOBAL
    }
}
