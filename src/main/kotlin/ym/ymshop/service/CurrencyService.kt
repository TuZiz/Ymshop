package ym.ymshop.service

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.CommandExecutionType
import ym.ymshop.model.CurrencyDefinition
import ym.ymshop.model.CurrencyType
import ym.ymshop.model.TradeResult
import ym.ymshop.util.replaceTokens
import ym.ymshop.util.resolvePlaceholderText
import java.util.UUID
import kotlin.math.round

class CurrencyService(
    private val plugin: JavaPlugin,
    private val platformExecutor: PlatformExecutor
) {

    private val vaultCacheLock = Any()
    private val vaultBalanceCache = linkedMapOf<UUID, CachedVaultBalance>()
    private var cachedVaultEconomy: Any? = null
    private var cachedVaultEconomyCheckedAt: Long = 0L

    fun logStartupDiagnostics() {
        val currenciesSection = plugin.config.getConfigurationSection("currencies") ?: return
        val usesVault = currenciesSection.getKeys(false).any { key ->
            currenciesSection.getString("$key.type", "")
                ?.equals("VAULT", ignoreCase = true) == true
        }
        if (!usesVault) {
            return
        }

        val pluginManager = Bukkit.getPluginManager()
        if (!pluginManager.isPluginEnabled("Vault")) {
            plugin.logger.warning("Detected VAULT currency, but Vault is not loaded in the server plugin list.")
            plugin.logger.warning("Project libs/*.jar are only build inputs. Put Vault and an economy plugin in the server plugins directory.")
            return
        }

        if (vaultEconomy() == null) {
            plugin.logger.warning("Vault is loaded, but no Economy provider is registered.")
            plugin.logger.warning("Install and enable a Vault-compatible economy plugin, such as EssentialsX Economy.")
        }
    }

    fun take(player: Player, currency: CurrencyDefinition, amount: Long, replacements: Map<String, String>): TradeResult {
        return when (currency.type) {
            CurrencyType.PLAYERPOINTS -> takePlayerPoints(player, currency, amount)
            CurrencyType.VAULT -> takeVault(player, currency, amount)
            CurrencyType.CUSTOM -> takeCustom(player, currency, amount, replacements)
        }
    }

    fun give(player: Player, currency: CurrencyDefinition, amount: Long, replacements: Map<String, String>): TradeResult {
        return when (currency.type) {
            CurrencyType.PLAYERPOINTS -> givePlayerPoints(player, currency, amount)
            CurrencyType.VAULT -> giveVault(player, currency, amount)
            CurrencyType.CUSTOM -> giveCustom(player, currency, amount, replacements)
        }
    }

    fun currentBalanceText(player: Player, currency: CurrencyDefinition): String? {
        return when (currency.type) {
            CurrencyType.PLAYERPOINTS -> currentPlayerPoints(player)?.toString()
            CurrencyType.VAULT -> currentVaultBalance(player)?.let(::formatVaultAmount)
            CurrencyType.CUSTOM -> readPlaceholderBalance(player, currency)?.toString()
        }
    }

    fun canAfford(player: Player, currency: CurrencyDefinition, amount: Long): Boolean? {
        return when (currency.type) {
            CurrencyType.PLAYERPOINTS -> currentPlayerPoints(player)?.let { it >= amount }
            CurrencyType.VAULT -> currentVaultBalance(player)?.let { it + 1.0E-6 >= amount.toDouble() }
            CurrencyType.CUSTOM -> readPlaceholderBalance(player, currency)?.let { it >= amount }
        }
    }

    fun dispatchCommands(player: Player, commands: List<String>, replacements: Map<String, String>) {
        dispatchCommands(player, commands, replacements, CommandExecutionType.CONSOLE)
    }

    fun dispatchCommands(player: Player, commands: List<String>, replacements: Map<String, String>, executionType: CommandExecutionType) {
        commands.forEach { raw ->
            val command = formatCommand(player, raw, replacements)
            when (executionType) {
                CommandExecutionType.CONSOLE -> platformExecutor.runGlobal {
                    runCatching {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    }.onFailure { ex ->
                        plugin.logger.warning("Failed to execute console command '$command': ${ex.message}")
                    }
                }

                CommandExecutionType.PLAYER -> {
                    platformExecutor.runForPlayer(player) {
                        runCatching {
                            player.performCommand(command)
                        }.onFailure { ex ->
                            plugin.logger.warning("Failed to execute player command '$command' for ${player.name}: ${ex.message}")
                        }
                    }
                }
            }
        }
    }

    private fun takePlayerPoints(player: Player, currency: CurrencyDefinition, amount: Long): TradeResult {
        val api = playerPointsApi() ?: return TradeResult(false, "currency-playerpoints-missing")
        if (amount > Int.MAX_VALUE) {
            return TradeResult(false, "currency-overflow")
        }
        val current = currentPlayerPoints(player)
            ?: return TradeResult(false, "currency-playerpoints-missing")
        if (current < amount.toInt()) {
            return TradeResult(false, "not-enough-currency", mapOf("currency" to currency.displayName, "balance" to current.toString()))
        }
        if (invokePlayerPointsBoolean(api, "take", player.uniqueId, amount.toInt()) != true) {
            return TradeResult(false, "currency-take-failed", mapOf("currency" to currency.displayName))
        }
        return TradeResult(true, "success")
    }

    private fun givePlayerPoints(player: Player, currency: CurrencyDefinition, amount: Long): TradeResult {
        val api = playerPointsApi() ?: return TradeResult(false, "currency-playerpoints-missing")
        if (amount > Int.MAX_VALUE) {
            return TradeResult(false, "currency-overflow")
        }
        if (invokePlayerPointsBoolean(api, "give", player.uniqueId, amount.toInt()) != true) {
            return TradeResult(false, "currency-give-failed", mapOf("currency" to currency.displayName))
        }
        return TradeResult(true, "success")
    }

    private fun takeCustom(player: Player, currency: CurrencyDefinition, amount: Long, replacements: Map<String, String>): TradeResult {
        val balance = readPlaceholderBalance(player, currency)
            ?: return TradeResult(false, "currency-balance-missing", mapOf("currency" to currency.displayName))
        if (balance < amount) {
            return TradeResult(false, "not-enough-currency", mapOf("currency" to currency.displayName, "balance" to balance.toString()))
        }
        if (currency.takeCommands.isEmpty()) {
            return TradeResult(false, "currency-take-command-missing", mapOf("currency" to currency.displayName))
        }
        dispatchCommands(player, currency.takeCommands, replacements + mapOf("amount" to amount.toString(), "currency" to currency.displayName))
        return TradeResult(true, "success")
    }

    private fun giveCustom(player: Player, currency: CurrencyDefinition, amount: Long, replacements: Map<String, String>): TradeResult {
        if (currency.giveCommands.isEmpty()) {
            return TradeResult(false, "currency-give-command-missing", mapOf("currency" to currency.displayName))
        }
        dispatchCommands(player, currency.giveCommands, replacements + mapOf("amount" to amount.toString(), "currency" to currency.displayName))
        return TradeResult(true, "success")
    }

    private fun takeVault(player: Player, currency: CurrencyDefinition, amount: Long): TradeResult {
        val economy = vaultEconomy() ?: return TradeResult(false, "currency-vault-missing")
        val balance = currentVaultBalance(player)
            ?: return TradeResult(false, "currency-vault-missing")
        if (balance + 1.0E-6 < amount.toDouble()) {
            return TradeResult(
                false,
                "not-enough-currency",
                mapOf("currency" to currency.displayName, "balance" to formatVaultAmount(balance))
            )
        }
        val response = invokeVaultTransaction(economy, "withdrawPlayer", player, amount.toDouble())
            ?: return TradeResult(false, "currency-take-failed", mapOf("currency" to currency.displayName))
        if (!vaultTransactionSuccess(response)) {
            invalidateVaultBalance(player.uniqueId)
            return TradeResult(false, "currency-take-failed", mapOf("currency" to currency.displayName))
        }
        cacheVaultBalance(player.uniqueId, (balance - amount.toDouble()).coerceAtLeast(0.0))
        return TradeResult(true, "success")
    }

    private fun giveVault(player: Player, currency: CurrencyDefinition, amount: Long): TradeResult {
        val economy = vaultEconomy() ?: return TradeResult(false, "currency-vault-missing")
        val response = invokeVaultTransaction(economy, "depositPlayer", player, amount.toDouble())
            ?: return TradeResult(false, "currency-give-failed", mapOf("currency" to currency.displayName))
        if (!vaultTransactionSuccess(response)) {
            invalidateVaultBalance(player.uniqueId)
            return TradeResult(false, "currency-give-failed", mapOf("currency" to currency.displayName))
        }
        cachedVaultBalance(player.uniqueId)?.let { cacheVaultBalance(player.uniqueId, it + amount.toDouble()) }
        return TradeResult(true, "success")
    }

    private fun currentPlayerPoints(player: Player): Int? {
        val api = playerPointsApi() ?: return null
        return invokePlayerPointsInt(api, "look", player.uniqueId)
    }

    private fun currentVaultBalance(player: OfflinePlayer): Double? {
        cachedVaultBalance(player.uniqueId)?.let { return it }
        val economy = vaultEconomy() ?: return null
        return invokeVaultBalance(economy, player)?.also { cacheVaultBalance(player.uniqueId, it) }
    }

    private fun readPlaceholderBalance(player: Player, currency: CurrencyDefinition): Long? {
        val placeholder = currency.balancePlaceholder ?: return null
        val resolved = resolvePlaceholderText(player, placeholder)
        val normalized = resolved.trim().replace(",", "")
        return normalized.toLongOrNull()
    }

    private fun formatCommand(player: Player, raw: String, replacements: Map<String, String>): String {
        val withTokens = replaceTokens(
            raw,
            replacements + mapOf(
                "player_name" to player.name,
                "player_uuid" to player.uniqueId.toString()
            )
        )
        return resolvePlaceholderText(player, withTokens).removePrefix("/")
    }

    private fun playerPointsApi() = runCatching {
        val clazz = Class.forName("org.black_ixx.playerpoints.PlayerPoints")
        val instance = clazz.getMethod("getInstance").invoke(null)
        instance?.javaClass?.getMethod("getAPI")?.invoke(instance)
    }.getOrNull()

    private fun invokePlayerPointsInt(api: Any, method: String, uuid: java.util.UUID): Int? {
        return runCatching { api.javaClass.getMethod(method, java.util.UUID::class.java).invoke(api, uuid) as Int }.getOrNull()
    }

    private fun invokePlayerPointsBoolean(api: Any, method: String, uuid: java.util.UUID, amount: Int): Boolean? {
        return runCatching {
            api.javaClass.getMethod(method, java.util.UUID::class.java, Int::class.javaPrimitiveType).invoke(api, uuid, amount) as Boolean
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun vaultEconomy(): Any? {
        val now = System.currentTimeMillis()
        synchronized(vaultCacheLock) {
            if (now - cachedVaultEconomyCheckedAt < VAULT_PROVIDER_CACHE_MILLIS) {
                return cachedVaultEconomy
            }
        }
        val provider = runCatching {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy") as Class<Any>
            Bukkit.getServicesManager().getRegistration(economyClass)?.provider
        }.getOrNull()
        synchronized(vaultCacheLock) {
            cachedVaultEconomy = provider
            cachedVaultEconomyCheckedAt = now
        }
        return provider
    }

    private fun invokeVaultBalance(economy: Any, player: OfflinePlayer): Double? {
        return runCatching {
            economy.javaClass.getMethod("getBalance", OfflinePlayer::class.java).invoke(economy, player) as Double
        }.getOrNull()
    }

    private fun invokeVaultTransaction(economy: Any, method: String, player: OfflinePlayer, amount: Double): Any? {
        return runCatching {
            economy.javaClass.getMethod(method, OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                .invoke(economy, player, amount)
        }.getOrNull()
    }

    private fun vaultTransactionSuccess(response: Any): Boolean {
        return runCatching {
            response.javaClass.getMethod("transactionSuccess").invoke(response) as Boolean
        }.getOrDefault(false)
    }

    private fun formatVaultAmount(value: Double): String {
        val rounded = round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun cachedVaultBalance(playerId: UUID): Double? {
        val now = System.currentTimeMillis()
        synchronized(vaultCacheLock) {
            val cached = vaultBalanceCache[playerId] ?: return null
            if (cached.expiresAt <= now) {
                vaultBalanceCache.remove(playerId)
                return null
            }
            return cached.value
        }
    }

    private fun cacheVaultBalance(playerId: UUID, value: Double) {
        val now = System.currentTimeMillis()
        synchronized(vaultCacheLock) {
            if (vaultBalanceCache.size >= MAX_BALANCE_CACHE_SIZE) {
                vaultBalanceCache.entries.removeIf { (_, cached) -> cached.expiresAt <= now }
                if (vaultBalanceCache.size >= MAX_BALANCE_CACHE_SIZE) {
                    vaultBalanceCache.entries.iterator().run {
                        if (hasNext()) {
                            next()
                            remove()
                        }
                    }
                }
            }
            vaultBalanceCache[playerId] = CachedVaultBalance(value, now + VAULT_BALANCE_CACHE_MILLIS)
        }
    }

    private fun invalidateVaultBalance(playerId: UUID) {
        synchronized(vaultCacheLock) {
            vaultBalanceCache.remove(playerId)
        }
    }

    private data class CachedVaultBalance(
        val value: Double,
        val expiresAt: Long
    )

    companion object {
        private const val VAULT_BALANCE_CACHE_MILLIS = 1000L
        private const val VAULT_PROVIDER_CACHE_MILLIS = 5000L
        private const val MAX_BALANCE_CACHE_SIZE = 512
    }
}
