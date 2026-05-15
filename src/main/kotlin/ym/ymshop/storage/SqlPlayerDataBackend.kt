package ym.ymshop.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.Date
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SqlPlayerDataBackend(
    private val plugin: JavaPlugin,
    override val settings: DatabaseSettings
) : PlayerDataBackend {

    private val dialect = SqlDialect.from(settings.type)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Ymshop-SQL-Worker").apply { isDaemon = true }
    }
    private val dataSource: HikariDataSource = createDataSource()

    init {
        initializeSchema()
    }

    override fun loadFavorites(): Map<UUID, List<FavoriteEntry>> {
        val favorites = linkedMapOf<UUID, MutableList<FavoriteEntry>>()
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT player_id, shop_id, entry_id FROM ymshop_favorites ORDER BY player_id"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val playerId = runCatching { UUID.fromString(result.getString("player_id")) }.getOrNull() ?: continue
                        favorites.getOrPut(playerId) { mutableListOf() } += FavoriteEntry(
                            shopId = result.getString("shop_id"),
                            entryId = result.getString("entry_id")
                        )
                    }
                }
            }
        }
        return favorites
    }

    override fun saveFavorites(playerId: UUID, entries: Collection<FavoriteEntry>) {
        val snapshot = entries.map { it.copy() }
        submitWrite("favorites:$playerId") { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM ymshop_favorites WHERE player_id = ?").use { delete ->
                    delete.setString(1, playerId.toString())
                    delete.executeUpdate()
                }
                if (snapshot.isNotEmpty()) {
                    connection.prepareStatement(dialect.replaceFavoritesSql).use { insert ->
                        snapshot.forEach { entry ->
                            insert.setString(1, playerId.toString())
                            insert.setString(2, entry.shopId)
                            insert.setString(3, entry.entryId)
                            insert.addBatch()
                        }
                        insert.executeBatch()
                    }
                }
                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun loadDailyTradeTotals(): Map<DailyTradeKey, DailyCurrencyTotals> {
        val totals = linkedMapOf<DailyTradeKey, DailyCurrencyTotals>()
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT trade_day, player_id, currency_id, buy_spent, sell_earned FROM ymshop_daily_trade_totals"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val playerId = runCatching { UUID.fromString(result.getString("player_id")) }.getOrNull() ?: continue
                        totals[DailyTradeKey(
                            playerId = playerId,
                            day = result.getDate("trade_day").toLocalDate(),
                            currencyId = result.getString("currency_id")
                        )] = DailyCurrencyTotals(
                            buySpent = result.getLong("buy_spent"),
                            sellEarned = result.getLong("sell_earned")
                        )
                    }
                }
            }
        }
        return totals
    }

    override fun saveDailyTradeTotal(key: DailyTradeKey, totals: DailyCurrencyTotals) {
        val copy = totals.copy()
        submitWrite("daily:${key.playerId}:${key.day}:${key.currencyId}") { connection ->
            connection.prepareStatement(dialect.upsertDailyTradeSql).use { statement ->
                statement.setDate(1, Date.valueOf(key.day))
                statement.setString(2, key.playerId.toString())
                statement.setString(3, key.currencyId)
                statement.setLong(4, copy.buySpent)
                statement.setLong(5, copy.sellEarned)
                statement.executeUpdate()
            }
        }
    }

    override fun loadShopSnapshot(shopId: String): ShopStatsSnapshot {
        val snapshot = ShopStatsSnapshot()
        withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT player_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker
                FROM ymshop_shop_player_stats
                WHERE shop_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, shopId)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val playerId = runCatching { UUID.fromString(result.getString("player_id")) }.getOrNull() ?: continue
                        snapshot.playerRows[ShopPlayerStatsKey(playerId, result.getString("entry_id"))] = ShopStatsRow(
                            total = result.getLong("total"),
                            buy = result.getLong("buy"),
                            sell = result.getLong("sell"),
                            buyResetMarker = result.getLong("buy_reset_marker"),
                            sellResetMarker = result.getLong("sell_reset_marker")
                        )
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker
                FROM ymshop_shop_global_stats
                WHERE shop_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, shopId)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        snapshot.globalRows[result.getString("entry_id")] = ShopStatsRow(
                            total = result.getLong("total"),
                            buy = result.getLong("buy"),
                            sell = result.getLong("sell"),
                            buyResetMarker = result.getLong("buy_reset_marker"),
                            sellResetMarker = result.getLong("sell_reset_marker")
                        )
                    }
                }
            }
        }
        return snapshot
    }

    override fun saveShopChanges(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ) {
        val playerRows = dirtyPlayerKeys.mapNotNull { key -> snapshot.playerRows[key]?.copy()?.let { key to it } }
        val globalRows = dirtyGlobalEntryIds.mapNotNull { entryId -> snapshot.globalRows[entryId]?.copy()?.let { entryId to it } }
        if (playerRows.isEmpty() && globalRows.isEmpty()) {
            return
        }
        submitWrite("shop:$shopId") { connection ->
            writeShopChanges(connection, shopId, playerRows, globalRows)
        }
    }

    override fun saveShopChangesStrict(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ): Boolean {
        val playerRows = dirtyPlayerKeys.mapNotNull { key -> snapshot.playerRows[key]?.copy()?.let { key to it } }
        val globalRows = dirtyGlobalEntryIds.mapNotNull { entryId -> snapshot.globalRows[entryId]?.copy()?.let { entryId to it } }
        if (playerRows.isEmpty() && globalRows.isEmpty()) {
            return true
        }
        val future = executor.submit<Boolean> {
            dataSource.connection.use { connection ->
                writeShopChanges(connection, shopId, playerRows, globalRows)
            }
            true
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.severe("Ymshop SQL strict stats write failed [shop:$shopId]: ${ex.message}")
                ex.printStackTrace()
            }
            .getOrDefault(false)
    }

    override fun flush() {
        val future = executor.submit<Unit> { }
        future.get(30, TimeUnit.SECONDS)
    }

    override fun close() {
        runCatching { flush() }
            .onFailure { plugin.logger.severe("Failed to flush SQL player data backend: ${it.message}") }
        executor.shutdown()
        runCatching { dataSource.close() }
            .onFailure { plugin.logger.severe("Failed to close SQL player data backend: ${it.message}") }
    }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                dialect.createTableStatements.forEach(statement::executeUpdate)
            }
        }
    }

    private fun createDataSource(): HikariDataSource {
        require(settings.isSql) { "SQL backend requires mysql or postgresql type" }
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildJdbcUrl(settings)
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.poolSize
            minimumIdle = 1
            connectionTimeout = 10000
            validationTimeout = 5000
            driverClassName = when (settings.type) {
                DatabaseType.MYSQL -> "com.mysql.cj.jdbc.Driver"
                DatabaseType.POSTGRESQL -> "org.postgresql.Driver"
                DatabaseType.YAML -> error("YAML backend does not use JDBC")
            }
            addDataSourceProperty("cachePrepStmts", true)
            addDataSourceProperty("prepStmtCacheSize", 250)
            addDataSourceProperty("prepStmtCacheSqlLimit", 2048)
        }
        return HikariDataSource(hikariConfig).also { dataSource ->
            dataSource.connection.use { }
        }
    }

    private fun submitWrite(label: String, block: (Connection) -> Unit) {
        executor.submit {
            runCatching {
                dataSource.connection.use(block)
            }.onFailure { ex ->
                plugin.logger.severe("Ymshop SQL write failed [$label]: ${ex.message}")
                ex.printStackTrace()
            }
        }
    }

    private fun writeShopChanges(
        connection: Connection,
        shopId: String,
        playerRows: List<Pair<ShopPlayerStatsKey, ShopStatsRow>>,
        globalRows: List<Pair<String, ShopStatsRow>>
    ) {
        connection.autoCommit = false
        try {
            if (playerRows.isNotEmpty()) {
                connection.prepareStatement(dialect.upsertPlayerStatsSql).use { statement ->
                    playerRows.forEach { (key, row) ->
                        statement.setString(1, shopId)
                        statement.setString(2, key.playerId.toString())
                        statement.setString(3, key.entryId)
                        statement.setLong(4, row.total)
                        statement.setLong(5, row.buy)
                        statement.setLong(6, row.sell)
                        statement.setLong(7, row.buyResetMarker)
                        statement.setLong(8, row.sellResetMarker)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            if (globalRows.isNotEmpty()) {
                connection.prepareStatement(dialect.upsertGlobalStatsSql).use { statement ->
                    globalRows.forEach { (entryId, row) ->
                        statement.setString(1, shopId)
                        statement.setString(2, entryId)
                        statement.setLong(3, row.total)
                        statement.setLong(4, row.buy)
                        statement.setLong(5, row.sell)
                        statement.setLong(6, row.buyResetMarker)
                        statement.setLong(7, row.sellResetMarker)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            connection.commit()
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    private fun withConnection(block: (Connection) -> Unit) {
        dataSource.connection.use(block)
    }

    private fun buildJdbcUrl(settings: DatabaseSettings): String {
        return when (settings.type) {
            DatabaseType.MYSQL -> {
                val ssl = if (settings.ssl) "true" else "false"
                "jdbc:mysql://${settings.host}:${settings.port}/${settings.database}?useSSL=$ssl&allowPublicKeyRetrieval=true&characterEncoding=utf8"
            }

            DatabaseType.POSTGRESQL -> {
                val sslMode = if (settings.ssl) "require" else "disable"
                "jdbc:postgresql://${settings.host}:${settings.port}/${settings.database}?sslmode=$sslMode"
            }

            DatabaseType.YAML -> error("YAML backend does not use JDBC")
        }
    }
}
