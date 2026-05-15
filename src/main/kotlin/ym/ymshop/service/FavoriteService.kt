package ym.ymshop.service

import ym.ymshop.storage.FavoriteEntry
import ym.ymshop.storage.PlayerDataBackend
import java.util.UUID

class FavoriteService(private val backend: PlayerDataBackend) {

    private val favorites = backend.loadFavorites()
        .mapValuesTo(linkedMapOf()) { (_, entries) -> entries.map(::normalize).toMutableList() }

    @Synchronized
    fun entries(playerId: UUID): List<FavoriteEntry> {
        return favorites[playerId].orEmpty().toList()
    }

    @Synchronized
    fun contains(playerId: UUID, shopId: String, entryId: String): Boolean {
        val normalized = normalize(FavoriteEntry(shopId, entryId))
        return favorites[playerId].orEmpty().any { it == normalized }
    }

    @Synchronized
    fun toggle(playerId: UUID, shopId: String, entryId: String): Boolean {
        val values = favorites.getOrPut(playerId) { mutableListOf() }
        val normalized = normalize(FavoriteEntry(shopId, entryId))
        val existingIndex = values.indexOfFirst { it == normalized }
        val added = existingIndex < 0
        if (added) {
            values += normalized
        } else {
            values.removeAt(existingIndex)
        }
        backend.saveFavorites(playerId, values)
        return added
    }

    @Synchronized
    fun remove(playerId: UUID, shopId: String, entryId: String) {
        val values = favorites[playerId] ?: return
        val normalized = normalize(FavoriteEntry(shopId, entryId))
        if (values.removeIf { it == normalized }) {
            backend.saveFavorites(playerId, values)
        }
    }

    @Synchronized
    fun close() {
        backend.flush()
    }

    private fun normalize(entry: FavoriteEntry): FavoriteEntry {
        return FavoriteEntry(entry.shopId.lowercase(), entry.entryId.lowercase())
    }
}
