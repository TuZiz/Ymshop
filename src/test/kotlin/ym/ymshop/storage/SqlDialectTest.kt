package ym.ymshop.storage

import kotlin.test.Test
import kotlin.test.assertTrue

class SqlDialectTest {

    @Test
    fun `mysql dialect uses duplicate key upsert`() {
        val dialect = SqlDialect.from(DatabaseType.MYSQL)

        val schema = dialect.createTableStatements.joinToString("\n")
        assertTrue(schema.contains("ymshop_trade_reservations"))
        assertTrue(schema.contains("reservation_id"))
        assertTrue(schema.contains("status"))
        assertTrue(schema.contains("expires_at"))
        assertTrue(schema.contains("idx_ymshop_trade_reservations_expired"))
        assertTrue(dialect.upsertDailyTradeSql.contains("ON DUPLICATE KEY UPDATE"))
        assertTrue(dialect.upsertPlayerStatsSql.contains("ON DUPLICATE KEY UPDATE"))
        assertTrue(dialect.upsertGlobalStatsSql.contains("ON DUPLICATE KEY UPDATE"))
        assertTrue(dialect.insertPlayerStatsIfAbsentSql.contains("INSERT IGNORE"))
        assertTrue(dialect.insertGlobalStatsIfAbsentSql.contains("INSERT IGNORE"))
    }

    @Test
    fun `postgres dialect uses conflict upsert`() {
        val dialect = SqlDialect.from(DatabaseType.POSTGRESQL)

        val schema = dialect.createTableStatements.joinToString("\n")
        assertTrue(schema.contains("ymshop_trade_reservations"))
        assertTrue(schema.contains("reservation_id"))
        assertTrue(schema.contains("status"))
        assertTrue(schema.contains("expires_at"))
        assertTrue(schema.contains("CREATE INDEX IF NOT EXISTS idx_ymshop_trade_reservations_expired"))
        assertTrue(dialect.upsertDailyTradeSql.contains("ON CONFLICT"))
        assertTrue(dialect.upsertPlayerStatsSql.contains("ON CONFLICT"))
        assertTrue(dialect.upsertGlobalStatsSql.contains("ON CONFLICT"))
        assertTrue(dialect.insertPlayerStatsIfAbsentSql.contains("DO NOTHING"))
        assertTrue(dialect.insertGlobalStatsIfAbsentSql.contains("DO NOTHING"))
    }
}
