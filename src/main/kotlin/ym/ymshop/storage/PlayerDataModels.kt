package ym.ymshop.storage

import org.bukkit.configuration.ConfigurationSection
import java.time.LocalDate
import java.util.UUID

enum class DatabaseType {
    YAML,
    MYSQL,
    POSTGRESQL
}

data class DatabaseSettings(
    val type: DatabaseType,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val poolSize: Int,
    val ssl: Boolean
) {
    val isSql: Boolean
        get() = type == DatabaseType.MYSQL || type == DatabaseType.POSTGRESQL

    companion object {
        fun from(section: ConfigurationSection?): DatabaseSettings {
            val type = parseType(section?.getString("type"))
            val defaultPort = when (type) {
                DatabaseType.MYSQL -> 3306
                DatabaseType.POSTGRESQL -> 5432
                DatabaseType.YAML -> 0
            }
            return DatabaseSettings(
                type = type,
                host = section?.getString("host", "127.0.0.1").orEmpty(),
                port = section?.getInt("port", defaultPort) ?: defaultPort,
                database = section?.getString("database", "ymshop").orEmpty(),
                username = section?.getString("username", "root").orEmpty(),
                password = section?.getString("password", "").orEmpty(),
                poolSize = (section?.getInt("pool-size", 10) ?: 10).coerceAtLeast(1),
                ssl = section?.getBoolean("ssl", false) ?: false
            )
        }

        private fun parseType(raw: String?): DatabaseType {
            return when (raw?.trim()?.lowercase()) {
                null, "", "yaml" -> DatabaseType.YAML
                "mysql" -> DatabaseType.MYSQL
                "postgres", "postgresql", "pg" -> DatabaseType.POSTGRESQL
                else -> throw IllegalArgumentException("Unsupported database.type: $raw")
            }
        }
    }
}

data class FavoriteEntry(
    val shopId: String,
    val entryId: String
)

data class DailyTradeKey(
    val playerId: UUID,
    val day: LocalDate,
    val currencyId: String
)

data class DailyCurrencyTotals(
    val buySpent: Long,
    val sellEarned: Long
)

data class ShopPlayerStatsKey(
    val playerId: UUID,
    val entryId: String
)

data class ShopStatsRow(
    var total: Long = 0L,
    var buy: Long = 0L,
    var sell: Long = 0L,
    var buyResetMarker: Long = 0L,
    var sellResetMarker: Long = 0L
)

data class ShopStatsSnapshot(
    val playerRows: MutableMap<ShopPlayerStatsKey, ShopStatsRow> = linkedMapOf(),
    val globalRows: MutableMap<String, ShopStatsRow> = linkedMapOf()
)
