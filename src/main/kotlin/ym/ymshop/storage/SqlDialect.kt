package ym.ymshop.storage

interface SqlDialect {
    val createTableStatements: List<String>
    val replaceFavoritesSql: String
    val upsertDailyTradeSql: String
    val upsertPlayerStatsSql: String
    val upsertGlobalStatsSql: String
    val insertPlayerStatsIfAbsentSql: String
    val insertGlobalStatsIfAbsentSql: String

    companion object {
        fun from(type: DatabaseType): SqlDialect {
            return when (type) {
                DatabaseType.MYSQL -> MysqlDialect
                DatabaseType.POSTGRESQL -> PostgresqlDialect
                DatabaseType.YAML -> error("YAML backend does not use SQL dialect")
            }
        }
    }
}

private object MysqlDialect : SqlDialect {
    override val createTableStatements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ymshop_favorites (
            player_id VARCHAR(36) NOT NULL,
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            PRIMARY KEY (player_id, shop_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_daily_trade_totals (
            trade_day DATE NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            currency_id VARCHAR(64) NOT NULL,
            buy_spent BIGINT NOT NULL,
            sell_earned BIGINT NOT NULL,
            PRIMARY KEY (trade_day, player_id, currency_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_shop_player_stats (
            shop_id VARCHAR(128) NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            total BIGINT NOT NULL,
            buy BIGINT NOT NULL,
            sell BIGINT NOT NULL,
            buy_reset_marker BIGINT NOT NULL,
            sell_reset_marker BIGINT NOT NULL,
            PRIMARY KEY (shop_id, player_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_shop_global_stats (
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            total BIGINT NOT NULL,
            buy BIGINT NOT NULL,
            sell BIGINT NOT NULL,
            buy_reset_marker BIGINT NOT NULL,
            sell_reset_marker BIGINT NOT NULL,
            PRIMARY KEY (shop_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_trade_reservations (
            reservation_id VARCHAR(36) NOT NULL,
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            side VARCHAR(16) NOT NULL,
            amount INT NOT NULL,
            status VARCHAR(16) NOT NULL,
            player_buy_reset_marker BIGINT NOT NULL,
            player_sell_reset_marker BIGINT NOT NULL,
            global_buy_reset_marker BIGINT NOT NULL,
            global_sell_reset_marker BIGINT NOT NULL,
            created_at BIGINT NOT NULL,
            expires_at BIGINT NOT NULL,
            PRIMARY KEY (reservation_id),
            INDEX idx_ymshop_trade_reservations_expired (status, expires_at),
            INDEX idx_ymshop_trade_reservations_entry (shop_id, entry_id, player_id)
        )
        """.trimIndent()
    )

    override val replaceFavoritesSql: String =
        "INSERT INTO ymshop_favorites (player_id, shop_id, entry_id) VALUES (?, ?, ?)"

    override val upsertDailyTradeSql: String =
        """
        INSERT INTO ymshop_daily_trade_totals (trade_day, player_id, currency_id, buy_spent, sell_earned)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            buy_spent = VALUES(buy_spent),
            sell_earned = VALUES(sell_earned)
        """.trimIndent()

    override val upsertPlayerStatsSql: String =
        """
        INSERT INTO ymshop_shop_player_stats
            (shop_id, player_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            total = VALUES(total),
            buy = VALUES(buy),
            sell = VALUES(sell),
            buy_reset_marker = VALUES(buy_reset_marker),
            sell_reset_marker = VALUES(sell_reset_marker)
        """.trimIndent()

    override val upsertGlobalStatsSql: String =
        """
        INSERT INTO ymshop_shop_global_stats
            (shop_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            total = VALUES(total),
            buy = VALUES(buy),
            sell = VALUES(sell),
            buy_reset_marker = VALUES(buy_reset_marker),
            sell_reset_marker = VALUES(sell_reset_marker)
        """.trimIndent()

    override val insertPlayerStatsIfAbsentSql: String =
        """
        INSERT IGNORE INTO ymshop_shop_player_stats
            (shop_id, player_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, 0, 0, 0, 0, 0)
        """.trimIndent()

    override val insertGlobalStatsIfAbsentSql: String =
        """
        INSERT IGNORE INTO ymshop_shop_global_stats
            (shop_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, 0, 0, 0, 0, 0)
        """.trimIndent()
}

private object PostgresqlDialect : SqlDialect {
    override val createTableStatements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ymshop_favorites (
            player_id VARCHAR(36) NOT NULL,
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            PRIMARY KEY (player_id, shop_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_daily_trade_totals (
            trade_day DATE NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            currency_id VARCHAR(64) NOT NULL,
            buy_spent BIGINT NOT NULL,
            sell_earned BIGINT NOT NULL,
            PRIMARY KEY (trade_day, player_id, currency_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_shop_player_stats (
            shop_id VARCHAR(128) NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            total BIGINT NOT NULL,
            buy BIGINT NOT NULL,
            sell BIGINT NOT NULL,
            buy_reset_marker BIGINT NOT NULL,
            sell_reset_marker BIGINT NOT NULL,
            PRIMARY KEY (shop_id, player_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_shop_global_stats (
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            total BIGINT NOT NULL,
            buy BIGINT NOT NULL,
            sell BIGINT NOT NULL,
            buy_reset_marker BIGINT NOT NULL,
            sell_reset_marker BIGINT NOT NULL,
            PRIMARY KEY (shop_id, entry_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ymshop_trade_reservations (
            reservation_id VARCHAR(36) NOT NULL,
            shop_id VARCHAR(128) NOT NULL,
            entry_id VARCHAR(128) NOT NULL,
            player_id VARCHAR(36) NOT NULL,
            side VARCHAR(16) NOT NULL,
            amount INTEGER NOT NULL,
            status VARCHAR(16) NOT NULL,
            player_buy_reset_marker BIGINT NOT NULL,
            player_sell_reset_marker BIGINT NOT NULL,
            global_buy_reset_marker BIGINT NOT NULL,
            global_sell_reset_marker BIGINT NOT NULL,
            created_at BIGINT NOT NULL,
            expires_at BIGINT NOT NULL,
            PRIMARY KEY (reservation_id)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_ymshop_trade_reservations_expired
        ON ymshop_trade_reservations (status, expires_at)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_ymshop_trade_reservations_entry
        ON ymshop_trade_reservations (shop_id, entry_id, player_id)
        """.trimIndent()
    )

    override val replaceFavoritesSql: String =
        "INSERT INTO ymshop_favorites (player_id, shop_id, entry_id) VALUES (?, ?, ?)"

    override val upsertDailyTradeSql: String =
        """
        INSERT INTO ymshop_daily_trade_totals (trade_day, player_id, currency_id, buy_spent, sell_earned)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (trade_day, player_id, currency_id) DO UPDATE SET
            buy_spent = EXCLUDED.buy_spent,
            sell_earned = EXCLUDED.sell_earned
        """.trimIndent()

    override val upsertPlayerStatsSql: String =
        """
        INSERT INTO ymshop_shop_player_stats
            (shop_id, player_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (shop_id, player_id, entry_id) DO UPDATE SET
            total = EXCLUDED.total,
            buy = EXCLUDED.buy,
            sell = EXCLUDED.sell,
            buy_reset_marker = EXCLUDED.buy_reset_marker,
            sell_reset_marker = EXCLUDED.sell_reset_marker
        """.trimIndent()

    override val upsertGlobalStatsSql: String =
        """
        INSERT INTO ymshop_shop_global_stats
            (shop_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (shop_id, entry_id) DO UPDATE SET
            total = EXCLUDED.total,
            buy = EXCLUDED.buy,
            sell = EXCLUDED.sell,
            buy_reset_marker = EXCLUDED.buy_reset_marker,
            sell_reset_marker = EXCLUDED.sell_reset_marker
        """.trimIndent()

    override val insertPlayerStatsIfAbsentSql: String =
        """
        INSERT INTO ymshop_shop_player_stats
            (shop_id, player_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, ?, 0, 0, 0, 0, 0)
        ON CONFLICT (shop_id, player_id, entry_id) DO NOTHING
        """.trimIndent()

    override val insertGlobalStatsIfAbsentSql: String =
        """
        INSERT INTO ymshop_shop_global_stats
            (shop_id, entry_id, total, buy, sell, buy_reset_marker, sell_reset_marker)
        VALUES (?, ?, 0, 0, 0, 0, 0)
        ON CONFLICT (shop_id, entry_id) DO NOTHING
        """.trimIndent()
}
