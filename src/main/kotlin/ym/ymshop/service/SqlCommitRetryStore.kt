package ym.ymshop.service

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.TradeReservation
import ym.ymshop.model.TradeSide
import java.io.File
import java.time.Instant
import java.util.UUID

class SqlCommitRetryStore(private val plugin: JavaPlugin) {
    private val file: File = plugin.dataFolder.resolve("data/sql-commit-retry.yml")
    private val lock = Any()

    fun load(): List<TradeReservation> = synchronized(lock) {
        val yaml = loadYaml()
        val section = yaml.getConfigurationSection(RESERVATIONS_PATH) ?: return@synchronized emptyList()
        section.getKeys(false).mapNotNull { id -> readReservation(yaml, "$RESERVATIONS_PATH.$id") }
    }

    fun add(reservation: TradeReservation) = synchronized(lock) {
        val yaml = loadYaml()
        writeReservation(yaml, "$RESERVATIONS_PATH.${reservation.reservationId}", reservation)
        saveYaml(yaml)
    }

    fun remove(reservationId: UUID) = synchronized(lock) {
        val yaml = loadYaml()
        yaml.set("$RESERVATIONS_PATH.$reservationId", null)
        saveYaml(yaml)
    }

    fun hasEntry(shopId: String, entryId: String): Boolean = synchronized(lock) {
        load().any { it.shopId.equals(shopId, ignoreCase = true) && it.entryId.equals(entryId, ignoreCase = true) }
    }

    private fun loadYaml(): YamlConfiguration {
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    private fun saveYaml(yaml: YamlConfiguration) {
        file.parentFile.mkdirs()
        yaml.save(file)
    }

    private fun readReservation(yaml: YamlConfiguration, path: String): TradeReservation? {
        val reservationId = yaml.getString("$path.reservation-id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val playerId = yaml.getString("$path.player-id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val side = yaml.getString("$path.side")?.let { runCatching { TradeSide.valueOf(it) }.getOrNull() } ?: return null
        return TradeReservation(
            reservationId = reservationId,
            shopId = yaml.getString("$path.shop-id") ?: return null,
            entryId = yaml.getString("$path.entry-id") ?: return null,
            playerId = playerId,
            side = side,
            amount = yaml.getInt("$path.amount"),
            playerBuyResetMarker = yaml.getLong("$path.player-buy-reset-marker"),
            playerSellResetMarker = yaml.getLong("$path.player-sell-reset-marker"),
            globalBuyResetMarker = yaml.getLong("$path.global-buy-reset-marker"),
            globalSellResetMarker = yaml.getLong("$path.global-sell-reset-marker"),
            reservedAt = Instant.ofEpochMilli(yaml.getLong("$path.reserved-at")),
            expiresAt = Instant.ofEpochMilli(yaml.getLong("$path.expires-at"))
        )
    }

    private fun writeReservation(yaml: YamlConfiguration, path: String, reservation: TradeReservation) {
        yaml.set("$path.reservation-id", reservation.reservationId.toString())
        yaml.set("$path.shop-id", reservation.shopId)
        yaml.set("$path.entry-id", reservation.entryId)
        yaml.set("$path.player-id", reservation.playerId.toString())
        yaml.set("$path.side", reservation.side.name)
        yaml.set("$path.amount", reservation.amount)
        yaml.set("$path.player-buy-reset-marker", reservation.playerBuyResetMarker)
        yaml.set("$path.player-sell-reset-marker", reservation.playerSellResetMarker)
        yaml.set("$path.global-buy-reset-marker", reservation.globalBuyResetMarker)
        yaml.set("$path.global-sell-reset-marker", reservation.globalSellResetMarker)
        yaml.set("$path.reserved-at", reservation.reservedAt.toEpochMilli())
        yaml.set("$path.expires-at", reservation.expiresAt.toEpochMilli())
    }

    private companion object {
        const val RESERVATIONS_PATH = "reservations"
    }
}
