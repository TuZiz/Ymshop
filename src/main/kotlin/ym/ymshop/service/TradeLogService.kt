package ym.ymshop.service

import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.TradeSide
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TradeLogService(
    private val plugin: JavaPlugin
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Ymshop-TradeLog-Worker").apply { isDaemon = true }
    }

    fun logTrade(entry: TradeLogEntry) {
        submit("trade") {
            val now = entry.timestamp.atZone(entry.zoneId)
            val file = plugin.dataFolder.resolve("logs/trade-${now.toLocalDate()}.log")
            appendLine(file, entry.toLine(now.toLocalDate().toString(), now.toLocalTime().withNano(0).toString()))
        }
    }

    fun logCompensation(entry: CompensationLogEntry) {
        plugin.logger.severe(
            "Ymshop compensation required: player=${entry.playerName} uuid=${entry.playerId} " +
                "shop=${entry.shopId} entry=${entry.entryId} reason=${entry.failureReason}"
        )
        submit("compensation") {
            val now = entry.timestamp.atZone(entry.zoneId)
            val file = plugin.dataFolder.resolve("logs/compensation-${now.toLocalDate()}.log")
            appendLine(file, entry.toLine(now.toLocalDate().toString(), now.toLocalTime().withNano(0).toString()))
        }
    }

    fun close() {
        worker.shutdown()
        runCatching {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.logger.severe("Timed out while flushing Ymshop trade logs.")
            }
        }.onFailure { ex ->
            plugin.logger.severe("Failed to flush Ymshop trade logs: ${ex.message}")
            Thread.currentThread().interrupt()
        }
    }

    private fun submit(label: String, task: () -> Unit) {
        runCatching {
            worker.submit {
                runCatching(task).onFailure { ex ->
                    plugin.logger.severe("Failed to write Ymshop $label log: ${ex.message}")
                }
            }
        }.onFailure { ex ->
            plugin.logger.severe("Failed to enqueue Ymshop $label log: ${ex.message}")
        }
    }

    private fun appendLine(file: java.io.File, line: String) {
        file.parentFile.mkdirs()
        file.appendText(line + System.lineSeparator())
    }

    private fun clean(value: String): String {
        return value.replace('\n', ' ').replace('\r', ' ')
    }

    data class TradeLogEntry(
        val timestamp: Instant,
        val zoneId: ZoneId,
        val playerName: String,
        val playerId: UUID,
        val side: TradeSide,
        val shopId: String,
        val entryId: String,
        val itemInfo: String,
        val amount: Int,
        val unitPrice: Long,
        val totalPrice: Long,
        val currencyId: String
    ) {
        fun toLine(date: String, time: String): String {
            return "[${date} ${time}] " +
                "player=${clean(playerName)} uuid=$playerId side=${side.name} shop=${clean(shopId)} " +
                "entry=${clean(entryId)} item=${clean(itemInfo)} amount=$amount unit_price=$unitPrice " +
                "total_price=$totalPrice currency=${clean(currencyId)}"
        }
    }

    data class CompensationLogEntry(
        val timestamp: Instant,
        val zoneId: ZoneId,
        val playerName: String,
        val playerId: UUID,
        val side: TradeSide,
        val shopId: String,
        val entryId: String,
        val itemInfo: String,
        val amount: Int,
        val currencyId: String,
        val totalPrice: Long,
        val failureReason: String
    ) {
        fun toLine(date: String, time: String): String {
            return "[${date} ${time}] " +
                "player=${clean(playerName)} uuid=$playerId side=${side.name} shop=${clean(shopId)} " +
                "entry=${clean(entryId)} item=${clean(itemInfo)} amount=$amount currency=${clean(currencyId)} " +
                "total_price=$totalPrice reason=${clean(failureReason)}"
        }
    }

    private companion object {
        fun clean(value: String): String = value.replace('\n', ' ').replace('\r', ' ')
    }
}
