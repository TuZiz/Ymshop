package ym.ymshop.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YamlPlayerDataBackend(
    private val plugin: JavaPlugin,
    override val settings: DatabaseSettings
) : PlayerDataBackend {

    private val favoritesFile: File = plugin.dataFolder.resolve("data/favorites.yml")
    private val dailyTradesFile: File = plugin.dataFolder.resolve("data/player-daily-trades.yml")
    private val shopFiles = linkedMapOf<String, File>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Ymshop-YAML-Worker").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private val favoritesState = loadFavoritesFile().mapValuesTo(linkedMapOf()) { (_, entries) ->
        entries.mapTo(mutableListOf()) { it.copy() }
    }
    private val dailyTradeState = loadDailyTradeFile().toMutableMap()
    private val shopSnapshots = linkedMapOf<String, ShopStatsSnapshot>()
    private val pendingShopSnapshots = linkedMapOf<String, ShopStatsSnapshot>()
    private var pendingFavoritesSnapshot: Map<UUID, List<FavoriteEntry>>? = null
    private var pendingDailyTradeSnapshot: Map<DailyTradeKey, DailyCurrencyTotals>? = null
    private var drainScheduled = false

    override fun loadFavorites(): Map<UUID, List<FavoriteEntry>> {
        return synchronized(stateLock) {
            favoritesState.mapValuesTo(linkedMapOf()) { (_, entries) -> entries.map(FavoriteEntry::copy) }
        }
    }

    override fun saveFavorites(playerId: UUID, entries: Collection<FavoriteEntry>) {
        synchronized(stateLock) {
            favoritesState[playerId] = entries.mapTo(mutableListOf()) { it.copy() }
            pendingFavoritesSnapshot = favoritesState.mapValuesTo(linkedMapOf()) { (_, currentEntries) ->
                currentEntries.map(FavoriteEntry::copy)
            }
            scheduleDrainLocked()
        }
    }

    override fun loadDailyTradeTotals(): Map<DailyTradeKey, DailyCurrencyTotals> {
        return synchronized(stateLock) {
            dailyTradeState.mapValuesTo(linkedMapOf()) { (_, totals) -> totals.copy() }
        }
    }

    override fun saveDailyTradeTotal(key: DailyTradeKey, totals: DailyCurrencyTotals) {
        synchronized(stateLock) {
            dailyTradeState[key] = totals.copy()
            pendingDailyTradeSnapshot = dailyTradeState.mapValuesTo(linkedMapOf()) { (_, currentTotals) -> currentTotals.copy() }
            scheduleDrainLocked()
        }
    }

    override fun loadShopSnapshot(shopId: String): ShopStatsSnapshot {
        return synchronized(stateLock) {
            shopSnapshots.getOrPut(shopId.lowercase()) { loadShopSnapshotFile(shopId) }.deepCopy()
        }
    }

    override fun saveShopChanges(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ) {
        if (dirtyPlayerKeys.isEmpty() && dirtyGlobalEntryIds.isEmpty()) {
            return
        }
        synchronized(stateLock) {
            val state = shopSnapshots.getOrPut(shopId.lowercase()) { loadShopSnapshotFile(shopId) }
            dirtyPlayerKeys.forEach { key ->
                val row = snapshot.playerRows[key] ?: return@forEach
                state.playerRows[key] = row.copy()
            }
            dirtyGlobalEntryIds.forEach { entryId ->
                val row = snapshot.globalRows[entryId] ?: return@forEach
                state.globalRows[entryId] = row.copy()
            }
            pendingShopSnapshots[shopId.lowercase()] = state.deepCopy()
            scheduleDrainLocked()
        }
    }

    override fun flush() {
        val barrier = synchronized(stateLock) {
            if (!drainScheduled &&
                pendingFavoritesSnapshot == null &&
                pendingDailyTradeSnapshot == null &&
                pendingShopSnapshots.isEmpty()
            ) {
                null
            } else {
                worker.submit<Unit> { }
            }
        } ?: return
        barrier.get(30, TimeUnit.SECONDS)
    }

    override fun close() {
        runCatching { flush() }
            .onFailure { plugin.logger.severe("Failed to flush YAML player data backend: ${it.message}") }
        worker.shutdown()
    }

    private fun shopFile(shopId: String): File {
        return shopFiles.getOrPut(shopId.lowercase()) { plugin.dataFolder.resolve("data/$shopId.yml") }
    }

    private fun loadFavoritesFile(): Map<UUID, List<FavoriteEntry>> {
        val yaml = loadYaml(favoritesFile)
        val playersSection = yaml.getConfigurationSection("players") ?: return emptyMap()
        return playersSection.getKeys(false).mapNotNull { rawPlayerId ->
            val playerId = runCatching { UUID.fromString(rawPlayerId) }.getOrNull() ?: return@mapNotNull null
            val entries = yaml.getStringList("players.$rawPlayerId").mapNotNull(::decodeFavorite)
            playerId to entries
        }.toMap(linkedMapOf())
    }

    private fun loadDailyTradeFile(): Map<DailyTradeKey, DailyCurrencyTotals> {
        val yaml = loadYaml(dailyTradesFile)
        val daysSection = yaml.getConfigurationSection("days") ?: return emptyMap()
        val totals = linkedMapOf<DailyTradeKey, DailyCurrencyTotals>()
        daysSection.getKeys(false).forEach { rawDay ->
            val day = runCatching { LocalDate.parse(rawDay) }.getOrNull() ?: return@forEach
            val playersSection = yaml.getConfigurationSection("days.$rawDay.players") ?: return@forEach
            playersSection.getKeys(false).forEach { rawPlayerId ->
                val playerId = runCatching { UUID.fromString(rawPlayerId) }.getOrNull() ?: return@forEach
                val currencySection = yaml.getConfigurationSection("days.$rawDay.players.$rawPlayerId.currencies") ?: return@forEach
                currencySection.getKeys(false).forEach { rawCurrencyId ->
                    val base = "days.$rawDay.players.$rawPlayerId.currencies.$rawCurrencyId"
                    totals[DailyTradeKey(playerId, day, rawCurrencyId)] = DailyCurrencyTotals(
                        buySpent = yaml.getLong("$base.buy-spent"),
                        sellEarned = yaml.getLong("$base.sell-earned")
                    )
                }
            }
        }
        return totals
    }

    private fun loadShopSnapshotFile(shopId: String): ShopStatsSnapshot {
        val yaml = loadYaml(shopFile(shopId))
        val snapshot = ShopStatsSnapshot()

        val playersSection = yaml.getConfigurationSection("players")
        playersSection?.getKeys(false)?.forEach { rawPlayerId ->
            val playerId = runCatching { UUID.fromString(rawPlayerId) }.getOrNull() ?: return@forEach
            val playerSection = playersSection.getConfigurationSection(rawPlayerId) ?: return@forEach
            playerSection.getKeys(false).forEach { entryId ->
                val base = "players.$rawPlayerId.$entryId"
                snapshot.playerRows[ShopPlayerStatsKey(playerId, entryId)] = ShopStatsRow(
                    total = yaml.getLong("$base.total"),
                    buy = yaml.getLong("$base.buy"),
                    sell = yaml.getLong("$base.sell")
                )
            }
        }

        val globalSection = yaml.getConfigurationSection("entries")
        globalSection?.getKeys(false)?.forEach { entryId ->
            val base = "entries.$entryId"
            snapshot.globalRows[entryId] = ShopStatsRow(
                total = yaml.getLong("$base.total"),
                buy = yaml.getLong("$base.buy"),
                sell = yaml.getLong("$base.sell")
            )
        }

        val playerResetSection = yaml.getConfigurationSection("meta.resets.players")
        playerResetSection?.getKeys(false)?.forEach { rawPlayerId ->
            val playerId = runCatching { UUID.fromString(rawPlayerId) }.getOrNull() ?: return@forEach
            val playerSection = playerResetSection.getConfigurationSection(rawPlayerId) ?: return@forEach
            playerSection.getKeys(false).forEach { entryId ->
                val base = "meta.resets.players.$rawPlayerId.$entryId"
                val key = ShopPlayerStatsKey(playerId, entryId)
                val row = snapshot.playerRows.getOrPut(key) { ShopStatsRow() }
                row.buyResetMarker = yaml.getLong("$base.buy")
                row.sellResetMarker = yaml.getLong("$base.sell")
            }
        }

        val globalResetSection = yaml.getConfigurationSection("meta.resets.entries")
        globalResetSection?.getKeys(false)?.forEach { entryId ->
            val base = "meta.resets.entries.$entryId"
            val row = snapshot.globalRows.getOrPut(entryId) { ShopStatsRow() }
            row.buyResetMarker = yaml.getLong("$base.buy")
            row.sellResetMarker = yaml.getLong("$base.sell")
        }

        return snapshot
    }

    private fun scheduleDrainLocked() {
        if (drainScheduled) {
            return
        }
        drainScheduled = true
        worker.submit { drainPendingWrites() }
    }

    private fun drainPendingWrites() {
        while (true) {
            val favoritesSnapshot: Map<UUID, List<FavoriteEntry>>?
            val dailySnapshot: Map<DailyTradeKey, DailyCurrencyTotals>?
            val shopSnapshotBatch: Map<String, ShopStatsSnapshot>
            synchronized(stateLock) {
                if (pendingFavoritesSnapshot == null &&
                    pendingDailyTradeSnapshot == null &&
                    pendingShopSnapshots.isEmpty()
                ) {
                    drainScheduled = false
                    return
                }
                favoritesSnapshot = pendingFavoritesSnapshot
                dailySnapshot = pendingDailyTradeSnapshot
                shopSnapshotBatch = pendingShopSnapshots.toMap()
                pendingFavoritesSnapshot = null
                pendingDailyTradeSnapshot = null
                pendingShopSnapshots.clear()
            }

            favoritesSnapshot?.let { snapshot ->
                runCatching { writeFavoritesSnapshot(snapshot) }
                    .onFailure { plugin.logger.severe("Failed to save favorites.yml: ${it.message}") }
            }
            dailySnapshot?.let { snapshot ->
                runCatching { writeDailyTradeSnapshot(snapshot) }
                    .onFailure { plugin.logger.severe("Failed to save player-daily-trades.yml: ${it.message}") }
            }
            shopSnapshotBatch.forEach { (shopId, snapshot) ->
                runCatching { writeShopSnapshot(shopId, snapshot) }
                    .onFailure { plugin.logger.severe("Failed to save $shopId.yml: ${it.message}") }
            }
        }
    }

    private fun writeFavoritesSnapshot(snapshot: Map<UUID, List<FavoriteEntry>>) {
        val yaml = YamlConfiguration()
        snapshot.forEach { (playerId, entries) ->
            yaml.set("players.$playerId", entries.map(::encodeFavorite))
        }
        saveYaml(favoritesFile, yaml)
    }

    private fun writeDailyTradeSnapshot(snapshot: Map<DailyTradeKey, DailyCurrencyTotals>) {
        val yaml = YamlConfiguration()
        snapshot.forEach { (key, totals) ->
            val base = "days.${key.day}.players.${key.playerId}.currencies.${key.currencyId.lowercase()}"
            yaml.set("$base.buy-spent", totals.buySpent)
            yaml.set("$base.sell-earned", totals.sellEarned)
        }
        saveYaml(dailyTradesFile, yaml)
    }

    private fun writeShopSnapshot(shopId: String, snapshot: ShopStatsSnapshot) {
        val yaml = YamlConfiguration()
        snapshot.playerRows.forEach { (key, row) ->
            val base = "players.${key.playerId}.${key.entryId}"
            yaml.set("$base.total", row.total)
            yaml.set("$base.buy", row.buy)
            yaml.set("$base.sell", row.sell)
            val resetBase = "meta.resets.players.${key.playerId}.${key.entryId}"
            yaml.set("$resetBase.buy", row.buyResetMarker)
            yaml.set("$resetBase.sell", row.sellResetMarker)
        }
        snapshot.globalRows.forEach { (entryId, row) ->
            val base = "entries.$entryId"
            yaml.set("$base.total", row.total)
            yaml.set("$base.buy", row.buy)
            yaml.set("$base.sell", row.sell)
            val resetBase = "meta.resets.entries.$entryId"
            yaml.set("$resetBase.buy", row.buyResetMarker)
            yaml.set("$resetBase.sell", row.sellResetMarker)
        }
        saveYaml(shopFile(shopId), yaml)
    }

    private fun loadYaml(file: File): YamlConfiguration {
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    private fun saveYaml(file: File, yaml: YamlConfiguration) {
        file.parentFile.mkdirs()
        yaml.save(file)
    }

    private fun encodeFavorite(entry: FavoriteEntry): String = "${entry.shopId.lowercase()}:${entry.entryId.lowercase()}"

    private fun decodeFavorite(raw: String): FavoriteEntry? {
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }
        return FavoriteEntry(parts[0], parts[1])
    }

    private fun ShopStatsSnapshot.deepCopy(): ShopStatsSnapshot {
        return ShopStatsSnapshot(
            playerRows = playerRows.mapValuesTo(linkedMapOf()) { (_, row) -> row.copy() },
            globalRows = globalRows.mapValuesTo(linkedMapOf()) { (_, row) -> row.copy() }
        )
    }
}
