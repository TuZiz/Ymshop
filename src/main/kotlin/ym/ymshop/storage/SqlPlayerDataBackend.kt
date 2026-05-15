package ym.ymshop.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.EntryStats
import ym.ymshop.model.TradeLimitRules
import ym.ymshop.model.TradeReservation
import ym.ymshop.model.TradeSide
import ym.ymshop.service.ResetScopeAction
import ym.ymshop.service.ShopResetSupport
import java.sql.Connection
import java.sql.Date
import java.time.Instant
import java.time.ZoneId
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
        // SQL shop stats are cross-server state. Trade and reset changes must go through
        // reserveShopTradeStats(), which checks limits and increments in one transaction.
    }

    override fun saveShopChangesStrict(
        shopId: String,
        snapshot: ShopStatsSnapshot,
        dirtyPlayerKeys: Set<ShopPlayerStatsKey>,
        dirtyGlobalEntryIds: Set<String>
    ): Boolean {
        // Strict SQL persistence is satisfied by the atomic reservation path.
        return true
    }

    override val supportsAtomicShopStats: Boolean
        get() = true

    override fun reserveShopTradeStats(
        shopId: String,
        entryId: String,
        playerId: UUID,
        side: TradeSide,
        amount: Int,
        limits: TradeLimitRules,
        nowMillis: Long,
        zoneId: ZoneId
    ): AtomicShopStatsResult {
        if (amount <= 0) {
            return AtomicShopStatsResult.Failed("Trade amount must be positive")
        }
        val future = executor.submit<AtomicShopStatsResult> {
            dataSource.connection.use { connection ->
                reserveShopTradeStatsInTransaction(
                    connection = connection,
                    shopId = shopId,
                    entryId = entryId,
                    playerId = playerId,
                    side = side,
                    amount = amount,
                    limits = limits,
                    nowMillis = nowMillis,
                    zoneId = zoneId
                )
            }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.severe("Ymshop SQL atomic stats reservation failed [shop:$shopId entry:$entryId]: ${ex.message}")
                ex.printStackTrace()
            }
            .getOrElse { AtomicShopStatsResult.Failed(it.message ?: "SQL atomic reservation failed") }
    }

    override fun rollbackShopTradeStats(reservation: TradeReservation): Boolean {
        val future = executor.submit<Boolean> {
            dataSource.connection.use { connection ->
                rollbackShopTradeStatsInTransaction(connection, reservation)
            }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.severe(
                    "Ymshop SQL atomic stats rollback failed [shop:${reservation.shopId} entry:${reservation.entryId}]: ${ex.message}"
                )
                ex.printStackTrace()
            }
            .getOrDefault(false)
    }

    override fun commitShopTradeStats(reservation: TradeReservation): Boolean {
        val future = executor.submit<Boolean> {
            dataSource.connection.use { connection ->
                commitShopTradeStatsInTransaction(connection, reservation)
            }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.severe(
                    "Ymshop SQL trade reservation commit failed [reservation:${reservation.reservationId} " +
                        "shop:${reservation.shopId} entry:${reservation.entryId}]: ${ex.message}"
                )
                ex.printStackTrace()
            }
            .getOrDefault(false)
    }

    override fun recoverExpiredShopTradeReservations(nowMillis: Long): Int {
        val future = executor.submit<Int> {
            dataSource.connection.use { connection ->
                recoverExpiredShopTradeReservationsInTransaction(connection, nowMillis)
            }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.severe("Ymshop SQL expired trade reservation recovery failed: ${ex.message}")
                ex.printStackTrace()
            }
            .getOrDefault(0)
    }

    override fun loadShopEntryStats(
        shopId: String,
        entryId: String,
        playerId: UUID,
        limits: TradeLimitRules,
        nowMillis: Long,
        zoneId: ZoneId
    ): EntryStats? {
        val future = executor.submit<EntryStats?> {
            dataSource.connection.use { connection ->
                loadShopEntryStatsInTransaction(
                    connection = connection,
                    shopId = shopId,
                    entryId = entryId,
                    playerId = playerId,
                    limits = limits,
                    nowMillis = nowMillis,
                    zoneId = zoneId
                )
            }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .onFailure { ex ->
                plugin.logger.warning("Ymshop SQL latest stats query failed [shop:$shopId entry:$entryId]: ${ex.message}")
            }
            .getOrNull()
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

    private fun reserveShopTradeStatsInTransaction(
        connection: Connection,
        shopId: String,
        entryId: String,
        playerId: UUID,
        side: TradeSide,
        amount: Int,
        limits: TradeLimitRules,
        nowMillis: Long,
        zoneId: ZoneId
    ): AtomicShopStatsResult {
        connection.autoCommit = false
        try {
            lockEntryForTransaction(connection, shopId, entryId)
            ensureStatsRows(connection, shopId, entryId, playerId)
            val playerRow = selectPlayerStatsForUpdate(connection, shopId, entryId, playerId)
            val globalRow = selectGlobalStatsForUpdate(connection, shopId, entryId)
            val now = Instant.ofEpochMilli(nowMillis)
            applyResetPolicies(playerRow, limits, now, zoneId)
            applyResetPolicies(globalRow, limits, now, zoneId)

            limitExceeded(playerRow.total + amount, limits.playerLimit, "limit-player")?.let {
                connection.rollback()
                return it
            }
            limitExceeded(globalRow.total + amount, limits.globalLimit, "limit-global")?.let {
                connection.rollback()
                return it
            }

            when (side) {
                TradeSide.BUY -> {
                    limitExceeded(playerRow.buy + amount, limits.buyLimit, "limit-buy")?.let {
                        connection.rollback()
                        return it
                    }
                    limitExceeded(globalRow.buy + amount, limits.buyGlobalLimit, "limit-buy")?.let {
                        connection.rollback()
                        return it
                    }
                    playerRow.buy += amount
                    globalRow.buy += amount
                }

                TradeSide.SELL -> {
                    limitExceeded(playerRow.sell + amount, limits.sellLimit, "limit-sell")?.let {
                        connection.rollback()
                        return it
                    }
                    limitExceeded(globalRow.sell + amount, limits.sellGlobalLimit, "limit-sell")?.let {
                        connection.rollback()
                        return it
                    }
                    playerRow.sell += amount
                    globalRow.sell += amount
                }
            }
            playerRow.total += amount
            globalRow.total += amount
            updatePlayerStats(connection, shopId, entryId, playerId, playerRow)
            updateGlobalStats(connection, shopId, entryId, globalRow)
            val reservationId = UUID.randomUUID()
            val expiresAtMillis = nowMillis + RESERVATION_TTL_MILLIS
            insertTradeReservation(
                connection = connection,
                reservationId = reservationId,
                shopId = shopId,
                entryId = entryId,
                playerId = playerId,
                side = side,
                amount = amount,
                playerBuyResetMarker = playerRow.buyResetMarker,
                playerSellResetMarker = playerRow.sellResetMarker,
                globalBuyResetMarker = globalRow.buyResetMarker,
                globalSellResetMarker = globalRow.sellResetMarker,
                createdAtMillis = nowMillis,
                expiresAtMillis = expiresAtMillis
            )
            connection.commit()
            return AtomicShopStatsResult.Reserved(
                reservation = TradeReservation(
                    reservationId = reservationId,
                    shopId = shopId,
                    entryId = entryId,
                    playerId = playerId,
                    side = side,
                    amount = amount,
                    playerBuyResetMarker = playerRow.buyResetMarker,
                    playerSellResetMarker = playerRow.sellResetMarker,
                    globalBuyResetMarker = globalRow.buyResetMarker,
                    globalSellResetMarker = globalRow.sellResetMarker,
                    reservedAt = now,
                    expiresAt = Instant.ofEpochMilli(expiresAtMillis)
                ),
                stats = EntryStats(
                    playerTotal = playerRow.total,
                    playerBuy = playerRow.buy,
                    playerSell = playerRow.sell,
                    globalTotal = globalRow.total,
                    globalBuy = globalRow.buy,
                    globalSell = globalRow.sell
                )
            )
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    private fun rollbackShopTradeStatsInTransaction(
        connection: Connection,
        reservation: TradeReservation
    ): Boolean {
        connection.autoCommit = false
        try {
            lockEntryForTransaction(connection, reservation.shopId, reservation.entryId)
            val row = selectReservationForUpdate(connection, reservation.reservationId)
            if (row == null) {
                connection.rollback()
                return false
            }
            if (row.status == RESERVATION_ROLLED_BACK) {
                connection.commit()
                return true
            }
            if (row.status == RESERVATION_COMMITTED) {
                connection.rollback()
                return false
            }
            rollbackPendingReservation(connection, row)
            updateReservationStatus(connection, row.reservationId, RESERVATION_ROLLED_BACK)
            connection.commit()
            return true
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    private fun commitShopTradeStatsInTransaction(
        connection: Connection,
        reservation: TradeReservation
    ): Boolean {
        connection.autoCommit = false
        try {
            lockEntryForTransaction(connection, reservation.shopId, reservation.entryId)
            val row = selectReservationForUpdate(connection, reservation.reservationId)
            if (row == null) {
                connection.rollback()
                return false
            }
            if (row.status == RESERVATION_ROLLED_BACK) {
                connection.rollback()
                return false
            }
            if (row.status == RESERVATION_PENDING) {
                updateReservationStatus(connection, row.reservationId, RESERVATION_COMMITTED)
            }
            connection.commit()
            return true
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    private fun recoverExpiredShopTradeReservationsInTransaction(connection: Connection, nowMillis: Long): Int {
        val expired = selectExpiredReservations(connection, nowMillis)
        if (expired.isEmpty()) {
            return 0
        }

        var recovered = 0
        expired.forEach { candidate ->
            connection.autoCommit = false
            try {
                lockEntryForTransaction(connection, candidate.shopId, candidate.entryId)
                val row = selectReservationForUpdate(connection, candidate.reservationId)
                if (row == null || row.status != RESERVATION_PENDING || row.expiresAtMillis > nowMillis) {
                    connection.commit()
                    return@forEach
                }
                rollbackPendingReservation(connection, row)
                updateReservationStatus(connection, row.reservationId, RESERVATION_ROLLED_BACK)
                connection.commit()
                recovered++
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                connection.autoCommit = true
            }
        }
        return recovered
    }

    private fun rollbackPendingReservation(connection: Connection, row: ReservationRow) {
        ensureStatsRows(connection, row.shopId, row.entryId, row.playerId)
        val playerRow = selectPlayerStatsForUpdate(connection, row.shopId, row.entryId, row.playerId)
        val globalRow = selectGlobalStatsForUpdate(connection, row.shopId, row.entryId)
        val amount = row.amount.toLong()
        playerRow.total = (playerRow.total - amount).coerceAtLeast(0L)
        globalRow.total = (globalRow.total - amount).coerceAtLeast(0L)
        when (row.side) {
            TradeSide.BUY -> {
                if (playerRow.buyResetMarker == row.playerBuyResetMarker) {
                    playerRow.buy = (playerRow.buy - amount).coerceAtLeast(0L)
                }
                if (globalRow.buyResetMarker == row.globalBuyResetMarker) {
                    globalRow.buy = (globalRow.buy - amount).coerceAtLeast(0L)
                }
            }

            TradeSide.SELL -> {
                if (playerRow.sellResetMarker == row.playerSellResetMarker) {
                    playerRow.sell = (playerRow.sell - amount).coerceAtLeast(0L)
                }
                if (globalRow.sellResetMarker == row.globalSellResetMarker) {
                    globalRow.sell = (globalRow.sell - amount).coerceAtLeast(0L)
                }
            }
        }
        updatePlayerStats(connection, row.shopId, row.entryId, row.playerId, playerRow)
        updateGlobalStats(connection, row.shopId, row.entryId, globalRow)
    }

    private fun lockEntryForTransaction(connection: Connection, shopId: String, entryId: String) {
        if (settings.type != DatabaseType.POSTGRESQL) {
            return
        }
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { statement ->
            statement.setInt(1, shopId.lowercase().hashCode())
            statement.setInt(2, entryId.lowercase().hashCode())
            statement.executeQuery().use { }
        }
    }

    private fun ensureStatsRows(connection: Connection, shopId: String, entryId: String, playerId: UUID) {
        connection.prepareStatement(dialect.insertPlayerStatsIfAbsentSql).use { statement ->
            statement.setString(1, shopId)
            statement.setString(2, playerId.toString())
            statement.setString(3, entryId)
            statement.executeUpdate()
        }
        connection.prepareStatement(dialect.insertGlobalStatsIfAbsentSql).use { statement ->
            statement.setString(1, shopId)
            statement.setString(2, entryId)
            statement.executeUpdate()
        }
    }

    private fun insertTradeReservation(
        connection: Connection,
        reservationId: UUID,
        shopId: String,
        entryId: String,
        playerId: UUID,
        side: TradeSide,
        amount: Int,
        playerBuyResetMarker: Long,
        playerSellResetMarker: Long,
        globalBuyResetMarker: Long,
        globalSellResetMarker: Long,
        createdAtMillis: Long,
        expiresAtMillis: Long
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ymshop_trade_reservations
                (reservation_id, shop_id, entry_id, player_id, side, amount, status,
                 player_buy_reset_marker, player_sell_reset_marker,
                 global_buy_reset_marker, global_sell_reset_marker,
                 created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, reservationId.toString())
            statement.setString(2, shopId)
            statement.setString(3, entryId)
            statement.setString(4, playerId.toString())
            statement.setString(5, side.name)
            statement.setInt(6, amount)
            statement.setString(7, RESERVATION_PENDING)
            statement.setLong(8, playerBuyResetMarker)
            statement.setLong(9, playerSellResetMarker)
            statement.setLong(10, globalBuyResetMarker)
            statement.setLong(11, globalSellResetMarker)
            statement.setLong(12, createdAtMillis)
            statement.setLong(13, expiresAtMillis)
            check(statement.executeUpdate() == 1) { "Failed to insert trade reservation" }
        }
    }

    private fun selectReservationForUpdate(connection: Connection, reservationId: UUID): ReservationRow? {
        connection.prepareStatement(
            """
            SELECT reservation_id, shop_id, entry_id, player_id, side, amount, status,
                   player_buy_reset_marker, player_sell_reset_marker,
                   global_buy_reset_marker, global_sell_reset_marker,
                   created_at, expires_at
            FROM ymshop_trade_reservations
            WHERE reservation_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, reservationId.toString())
            statement.executeQuery().use { result ->
                return if (result.next()) reservationRow(result) else null
            }
        }
    }

    private fun selectExpiredReservations(connection: Connection, nowMillis: Long): List<ReservationRow> {
        connection.prepareStatement(
            """
            SELECT reservation_id, shop_id, entry_id, player_id, side, amount, status,
                   player_buy_reset_marker, player_sell_reset_marker,
                   global_buy_reset_marker, global_sell_reset_marker,
                   created_at, expires_at
            FROM ymshop_trade_reservations
            WHERE status = ? AND expires_at <= ?
            ORDER BY expires_at ASC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, RESERVATION_PENDING)
            statement.setLong(2, nowMillis)
            statement.setInt(3, RECOVERY_BATCH_SIZE)
            statement.executeQuery().use { result ->
                val rows = mutableListOf<ReservationRow>()
                while (result.next()) {
                    rows += reservationRow(result)
                }
                return rows
            }
        }
    }

    private fun updateReservationStatus(connection: Connection, reservationId: UUID, status: String) {
        connection.prepareStatement(
            """
            UPDATE ymshop_trade_reservations
            SET status = ?
            WHERE reservation_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, reservationId.toString())
            check(statement.executeUpdate() == 1) { "Failed to update trade reservation status" }
        }
    }

    private fun selectPlayerStatsForUpdate(
        connection: Connection,
        shopId: String,
        entryId: String,
        playerId: UUID
    ): MutableStatsRow {
        connection.prepareStatement(
            """
            SELECT total, buy, sell, buy_reset_marker, sell_reset_marker
            FROM ymshop_shop_player_stats
            WHERE shop_id = ? AND player_id = ? AND entry_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, shopId)
            statement.setString(2, playerId.toString())
            statement.setString(3, entryId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing player stats row after insert" }
                return MutableStatsRow(
                    total = result.getLong("total"),
                    buy = result.getLong("buy"),
                    sell = result.getLong("sell"),
                    buyResetMarker = result.getLong("buy_reset_marker"),
                    sellResetMarker = result.getLong("sell_reset_marker")
                )
            }
        }
    }

    private fun selectGlobalStatsForUpdate(connection: Connection, shopId: String, entryId: String): MutableStatsRow {
        connection.prepareStatement(
            """
            SELECT total, buy, sell, buy_reset_marker, sell_reset_marker
            FROM ymshop_shop_global_stats
            WHERE shop_id = ? AND entry_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, shopId)
            statement.setString(2, entryId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing global stats row after insert" }
                return MutableStatsRow(
                    total = result.getLong("total"),
                    buy = result.getLong("buy"),
                    sell = result.getLong("sell"),
                    buyResetMarker = result.getLong("buy_reset_marker"),
                    sellResetMarker = result.getLong("sell_reset_marker")
                )
            }
        }
    }

    private fun loadShopEntryStatsInTransaction(
        connection: Connection,
        shopId: String,
        entryId: String,
        playerId: UUID,
        limits: TradeLimitRules,
        nowMillis: Long,
        zoneId: ZoneId
    ): EntryStats {
        connection.autoCommit = false
        try {
            lockEntryForTransaction(connection, shopId, entryId)
            ensureStatsRows(connection, shopId, entryId, playerId)
            val playerRow = selectPlayerStatsForUpdate(connection, shopId, entryId, playerId)
            val globalRow = selectGlobalStatsForUpdate(connection, shopId, entryId)
            val now = Instant.ofEpochMilli(nowMillis)
            applyResetPolicies(playerRow, limits, now, zoneId)
            applyResetPolicies(globalRow, limits, now, zoneId)
            updatePlayerStats(connection, shopId, entryId, playerId, playerRow)
            updateGlobalStats(connection, shopId, entryId, globalRow)
            connection.commit()
            return entryStats(playerRow, globalRow)
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    private fun updatePlayerStats(
        connection: Connection,
        shopId: String,
        entryId: String,
        playerId: UUID,
        row: MutableStatsRow
    ) {
        connection.prepareStatement(
            """
            UPDATE ymshop_shop_player_stats
            SET total = ?, buy = ?, sell = ?, buy_reset_marker = ?, sell_reset_marker = ?
            WHERE shop_id = ? AND player_id = ? AND entry_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, row.total)
            statement.setLong(2, row.buy)
            statement.setLong(3, row.sell)
            statement.setLong(4, row.buyResetMarker)
            statement.setLong(5, row.sellResetMarker)
            statement.setString(6, shopId)
            statement.setString(7, playerId.toString())
            statement.setString(8, entryId)
            check(statement.executeUpdate() == 1) { "Failed to update player stats row" }
        }
    }

    private fun updateGlobalStats(connection: Connection, shopId: String, entryId: String, row: MutableStatsRow) {
        connection.prepareStatement(
            """
            UPDATE ymshop_shop_global_stats
            SET total = ?, buy = ?, sell = ?, buy_reset_marker = ?, sell_reset_marker = ?
            WHERE shop_id = ? AND entry_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, row.total)
            statement.setLong(2, row.buy)
            statement.setLong(3, row.sell)
            statement.setLong(4, row.buyResetMarker)
            statement.setLong(5, row.sellResetMarker)
            statement.setString(6, shopId)
            statement.setString(7, entryId)
            check(statement.executeUpdate() == 1) { "Failed to update global stats row" }
        }
    }

    private fun applyResetPolicies(row: MutableStatsRow, limits: TradeLimitRules, now: Instant, zoneId: ZoneId) {
        ShopResetSupport.currentMarker(limits.buyResetPolicy, now, zoneId)?.let { marker ->
            when (ShopResetSupport.resolveScopeAction(row.buyResetMarker, marker, row.buy)) {
                ResetScopeAction.NONE -> Unit
                ResetScopeAction.MARK_ONLY -> row.buyResetMarker = marker
                ResetScopeAction.RESET_AND_MARK -> {
                    row.buy = 0L
                    row.buyResetMarker = marker
                }
            }
        }
        ShopResetSupport.currentMarker(limits.sellResetPolicy, now, zoneId)?.let { marker ->
            when (ShopResetSupport.resolveScopeAction(row.sellResetMarker, marker, row.sell)) {
                ResetScopeAction.NONE -> Unit
                ResetScopeAction.MARK_ONLY -> row.sellResetMarker = marker
                ResetScopeAction.RESET_AND_MARK -> {
                    row.sell = 0L
                    row.sellResetMarker = marker
                }
            }
        }
    }

    private fun reservationRow(result: java.sql.ResultSet): ReservationRow {
        return ReservationRow(
            reservationId = UUID.fromString(result.getString("reservation_id")),
            shopId = result.getString("shop_id"),
            entryId = result.getString("entry_id"),
            playerId = UUID.fromString(result.getString("player_id")),
            side = TradeSide.valueOf(result.getString("side")),
            amount = result.getInt("amount"),
            status = result.getString("status"),
            playerBuyResetMarker = result.getLong("player_buy_reset_marker"),
            playerSellResetMarker = result.getLong("player_sell_reset_marker"),
            globalBuyResetMarker = result.getLong("global_buy_reset_marker"),
            globalSellResetMarker = result.getLong("global_sell_reset_marker"),
            createdAtMillis = result.getLong("created_at"),
            expiresAtMillis = result.getLong("expires_at")
        )
    }

    private fun entryStats(playerRow: MutableStatsRow, globalRow: MutableStatsRow): EntryStats {
        return EntryStats(
            playerTotal = playerRow.total,
            playerBuy = playerRow.buy,
            playerSell = playerRow.sell,
            globalTotal = globalRow.total,
            globalBuy = globalRow.buy,
            globalSell = globalRow.sell
        )
    }

    private fun limitExceeded(next: Long, limit: Long?, messageKey: String): AtomicShopStatsResult.LimitExceeded? {
        if (limit == null || limit < 0 || next <= limit) {
            return null
        }
        return AtomicShopStatsResult.LimitExceeded(messageKey, limit)
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

    private data class MutableStatsRow(
        var total: Long = 0L,
        var buy: Long = 0L,
        var sell: Long = 0L,
        var buyResetMarker: Long = 0L,
        var sellResetMarker: Long = 0L
    )

    private data class ReservationRow(
        val reservationId: UUID,
        val shopId: String,
        val entryId: String,
        val playerId: UUID,
        val side: TradeSide,
        val amount: Int,
        val status: String,
        val playerBuyResetMarker: Long,
        val playerSellResetMarker: Long,
        val globalBuyResetMarker: Long,
        val globalSellResetMarker: Long,
        val createdAtMillis: Long,
        val expiresAtMillis: Long
    )

    private companion object {
        const val RESERVATION_PENDING = "PENDING"
        const val RESERVATION_COMMITTED = "COMMITTED"
        const val RESERVATION_ROLLED_BACK = "ROLLED_BACK"
        const val RESERVATION_TTL_MILLIS = 300_000L
        const val RECOVERY_BATCH_SIZE = 100
    }
}
