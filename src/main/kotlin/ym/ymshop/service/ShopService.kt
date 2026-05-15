package ym.ymshop.service

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.ConfiguredItem
import ym.ymshop.model.CurrencyDefinition
import ym.ymshop.model.EntryStats
import ym.ymshop.model.GlobalConfig
import ym.ymshop.model.PermissionLimit
import ym.ymshop.model.ResetMode
import ym.ymshop.model.ResetPolicy
import ym.ymshop.model.RewardType
import ym.ymshop.model.ShopDefinition
import ym.ymshop.model.ShopEntry
import ym.ymshop.model.TradeClickAmountDefinition
import ym.ymshop.model.TradeClickAmountMode
import ym.ymshop.model.TradeMode
import ym.ymshop.model.TradeResult
import ym.ymshop.model.TradeSide
import ym.ymshop.storage.PlayerDataBackend
import ym.ymshop.util.prettifyMaterialName
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max

class ShopService(
    private val plugin: JavaPlugin,
    private val platformExecutor: PlatformExecutor,
    private val configLoader: ConfigLoader,
    private val itemService: ItemService,
    private val currencyService: CurrencyService,
    private val messageService: MessageService,
    private val playerDataBackend: PlayerDataBackend,
    private val tradeLogService: TradeLogService
) {

    @Volatile
    private lateinit var globalConfig: GlobalConfig

    @Volatile
    private var shops: Map<String, ShopDefinition> = emptyMap()

    @Volatile
    private var dataStores: Map<String, ShopDataStore> = emptyMap()

    private val dailyTradeStore = PlayerDailyTradeStore(playerDataBackend)
    private val tradeLocks = TradeLockManager()
    private var zoneId: ZoneId = ZoneId.systemDefault()
    private var scheduledResetTask: PlatformExecutor.TaskHandle? = null
    private val resetListeners = mutableListOf<() -> Unit>()

    data class ShopPageRenderSnapshot(
        val shopId: String,
        val page: Int,
        val totalPages: Int,
        val itemSlots: List<Int>,
        val pageEntries: List<ShopEntry>,
        val layoutReplacements: Map<String, String>,
        val layoutSignature: Int,
        private val entrySnapshotsById: Map<String, ShopEntryRenderSnapshot>
    ) {
        fun entrySnapshot(entryId: String): ShopEntryRenderSnapshot? = entrySnapshotsById[entryId.lowercase()]
    }

    data class ShopEntryRenderSnapshot(
        val entryId: String,
        val icon: ConfiguredItem,
        val replacements: Map<String, String>,
        val renderSignature: Int
    )

    private data class RenderPreparation(
        val statsByEntryId: Map<String, EntryStats>,
        val balanceTextsByCurrencyId: Map<String, String?>
    )

    private data class EntryRenderContext(
        val player: Player,
        val shop: ShopDefinition,
        val entry: ShopEntry,
        val currency: CurrencyDefinition,
        val stats: EntryStats,
        val requestedSide: TradeSide?,
        val times: Int,
        val amount: Int,
        val totalPrice: Long,
        val preferredSide: TradeSide,
        val effectiveBuyLimit: Long?,
        val effectiveSellLimit: Long?,
        val dual: Boolean,
        val buyUnitPrice: Long?,
        val sellUnitPrice: Long?,
        val canAffordBuy: Boolean?,
        val sellItemCount: Int,
        val buyUnitAmount: Int,
        val sellUnitAmount: Int,
        val unitAmount: Int,
        val leftAmount: Int,
        val rightAmount: Int,
        val shiftAmount: Int,
        val materialName: String,
        val buyRefreshText: String,
        val sellRefreshText: String,
        val buyRefreshVisible: Boolean,
        val sellRefreshVisible: Boolean,
        val balanceText: String?
    )

    @Synchronized
    fun reload() {
        dataStores.values.forEach(ShopDataStore::save)
        val loadedGlobalConfig = configLoader.loadGlobalConfig()
        val loadedZoneId = loadedGlobalConfig.resetZoneId
        val layouts = configLoader.loadLayouts()
        layouts.values.forEach(::validateLayout)
        val loadedShops = linkedMapOf<String, ShopDefinition>()
        val loadedStores = linkedMapOf<String, ShopDataStore>()

        configLoader.loadShops(layouts).forEach { shop ->
            validateShop(shop, loadedGlobalConfig)
            loadedShops[shop.id.lowercase()] = shop
            loadedStores[shop.id.lowercase()] = ShopDataStore(shop.id, playerDataBackend)
        }

        cancelScheduledResetTask()
        globalConfig = loadedGlobalConfig
        zoneId = loadedZoneId
        shops = loadedShops
        dataStores = loadedStores
        val now = Instant.now()
        refreshAllResets(now, logSummary = true)
        logResetTimezone(now)
        scheduleNextReset(now)
    }

    fun close() {
        cancelScheduledResetTask()
        dataStores.values.forEach(ShopDataStore::save)
        dailyTradeStore.save()
    }

    fun addResetListener(listener: () -> Unit) {
        resetListeners += listener
    }

    fun shopIds(): List<String> = shops.keys.toList()

    fun findShop(id: String): ShopDefinition? = shops[id.lowercase()]

    fun findEntry(shopId: String, entryId: String): ShopEntry? {
        return findShop(shopId)?.entries?.firstOrNull { it.id.equals(entryId, ignoreCase = true) }
    }

    fun openShop(player: Player, id: String): TradeResult {
        val shop = findShop(id) ?: return TradeResult(false, "shop-not-found", mapOf("shop" to id))
        if (!shop.settings.permission.isNullOrBlank() && !player.hasPermission(shop.settings.permission)) {
            return TradeResult(false, "no-permission-shop", mapOf("shop" to shop.id))
        }
        return TradeResult(true, "success")
    }

    fun renderTitle(shop: ShopDefinition): String {
        return shop.layout.titleTemplate
            .replace("{shop-name}", shop.settings.shopName)
            .replace("{shop-id}", shop.id)
    }

    fun itemSlotIndices(shop: ShopDefinition): List<Int> {
        val buttonKeys = shop.layout.buttons.keys
        val indices = mutableListOf<Int>()
        var slot = 0
        shop.layout.pattern.forEach { row ->
            row.forEach { symbol ->
                if (symbol !in buttonKeys) {
                    indices += slot
                }
                slot++
            }
        }
        return indices
    }

    fun pageCount(shop: ShopDefinition): Int {
        val slotCount = max(1, itemSlotIndices(shop).size)
        return max(1, ceil(shop.entries.size / slotCount.toDouble()).toInt())
    }

    fun entriesForPage(shop: ShopDefinition, page: Int): List<ShopEntry> {
        val slotCount = max(1, itemSlotIndices(shop).size)
        val safePage = page.coerceIn(0, pageCount(shop) - 1)
        return shop.entries.drop(safePage * slotCount).take(slotCount)
    }

    fun createPageRenderSnapshot(player: Player, shop: ShopDefinition, page: Int): ShopPageRenderSnapshot {
        val totalPages = pageCount(shop)
        val safePage = page.coerceIn(0, totalPages - 1)
        val itemSlots = itemSlotIndices(shop)
        val pageEntries = entriesForPage(shop, safePage)
        val preparation = prepareRenderPreparation(player, shop, pageEntries)
        val layoutReplacements = renderLayoutReplacements(player, shop, safePage, totalPages)
        val entrySnapshots = pageEntries.associate { entry ->
            entry.id.lowercase() to createEntryRenderSnapshot(
                player = player,
                shop = shop,
                entry = entry,
                preparation = preparation,
                multiplier = 1,
                requestedSide = null
            )
        }
        return ShopPageRenderSnapshot(
            shopId = shop.id,
            page = safePage,
            totalPages = totalPages,
            itemSlots = itemSlots,
            pageEntries = pageEntries,
            layoutReplacements = layoutReplacements,
            layoutSignature = layoutReplacements.hashCode(),
            entrySnapshotsById = entrySnapshots
        )
    }

    fun buildIcon(player: Player, shop: ShopDefinition, entry: ShopEntry): org.bukkit.inventory.ItemStack {
        return buildIcon(player, createEntryRenderSnapshot(player, shop, entry))
    }

    fun buildIcon(player: Player, snapshot: ShopEntryRenderSnapshot): org.bukkit.inventory.ItemStack {
        return itemService.buildItem(snapshot.icon, player, snapshot.replacements)
    }

    fun renderLayoutReplacements(player: Player, shop: ShopDefinition, page: Int, totalPages: Int): Map<String, String> {
        val summary = todayMoneySummary(player)
        return mapOf(
            "shop_name" to shop.settings.shopName,
            "shop_id" to shop.id,
            "current_page" to (page + 1).toString(),
            "total_pages" to totalPages.toString(),
            "today_summary_currency" to summary.currencyDisplayName,
            "today_summary_day" to summary.day.toString(),
            "today_summary_refresh" to nextSummaryResetText(),
            "today_sell_remaining" to formatMoney(summary.sellRemaining),
            "today_sell_earned" to summary.sellEarned.toString(),
            "today_buy_spent" to summary.buySpent.toString()
        )
    }

    fun execute(player: Player, shopId: String, entryId: String, side: TradeSide, multiplier: Int = 1): TradeResult {
        val shop = findShop(shopId) ?: return TradeResult(false, "shop-not-found", mapOf("shop" to shopId))
        val entry = shop.entries.firstOrNull { it.id.equals(entryId, ignoreCase = true) }
            ?: return TradeResult(false, "entry-not-found", mapOf("entry" to entryId))

        if (side == TradeSide.BUY && !entry.supportsBuy) {
            return TradeResult(false, "entry-not-buyable", mapOf("entry" to entry.id))
        }
        if (side == TradeSide.SELL && !entry.supportsSell) {
            return TradeResult(false, "entry-not-sellable", mapOf("entry" to entry.id))
        }

        return tradeLocks.withEntryLock(shop.id, entry.id) {
            val currency = currency(entry.currencyId)
            val store = store(shop.id)
            refreshResets(shop, entry, player.uniqueId)
            val stats = store.stats(entry.id, player.uniqueId)
            val safeMultiplier = multiplier.coerceAtLeast(1)
            val configuredItem = tradeItem(entry, side)
            val quantity = configuredItem.amount * safeMultiplier
            val totalPrice = unitPrice(entry, side) * safeMultiplier
            val replacements = baseReplacements(
                createEntryRenderContext(
                    player = player,
                    shop = shop,
                    entry = entry,
                    preparation = RenderPreparation(
                        statsByEntryId = mapOf(entry.id.lowercase() to stats),
                        balanceTextsByCurrencyId = mapOf(currency.id.lowercase() to currencyService.currentBalanceText(player, currency))
                    ),
                    times = safeMultiplier,
                    requestedSide = side
                )
            )

            val resourceCheck = when (side) {
                TradeSide.BUY -> checkBuyResources(player, currency, totalPrice, replacements)
                TradeSide.SELL -> checkSellResources(player, entry, quantity, replacements)
            }
            if (resourceCheck != null) {
                return@withEntryLock resourceCheck
            }

            checkLimits(entry, stats, quantity, player, side)?.let { return@withEntryLock it }

            val result = when (side) {
                TradeSide.BUY -> executeBuy(player, shop, entry, currency, totalPrice, quantity, replacements)
                TradeSide.SELL -> executeSell(player, shop, entry, currency, totalPrice, quantity, replacements)
            }
            if (!result.success) {
                return@withEntryLock result
            }

            store.record(entry.id, player.uniqueId, if (side == TradeSide.BUY) TradeMode.BUY else TradeMode.SELL, quantity)
            dailyTradeStore.record(player.uniqueId, LocalDate.now(zoneId), currency.id, side, totalPrice)
            logTrade(player, shop, entry, side, quantity, unitPrice(entry, side), totalPrice, currency)
            currencyService.dispatchCommands(player, entry.successCommands, replacements)
            TradeResult(true, if (side == TradeSide.BUY) "buy-success" else "sell-success", replacements)
        }
    }

    fun renderReplacements(player: Player, shop: ShopDefinition, entry: ShopEntry): Map<String, String> {
        return createEntryRenderSnapshot(player, shop, entry).replacements
    }

    fun renderTradeReplacements(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        side: TradeSide,
        multiplier: Int
    ): Map<String, String> {
        val safeMultiplier = multiplier.coerceAtLeast(1)
        val snapshot = createEntryRenderSnapshot(player, shop, entry, multiplier = safeMultiplier, requestedSide = side)
        val amount = tradeItem(entry, side).amount * safeMultiplier
        val totalPrice = unitPrice(entry, side) * safeMultiplier
        return snapshot.replacements + mapOf(
            "confirm_amount" to amount.toString(),
            "confirm_price" to totalPrice.toString(),
            "confirm_unit_price" to unitPrice(entry, side).toString()
        )
    }

    fun maxSellAllMultiplier(player: Player, shopId: String, entryId: String): Int {
        val shop = findShop(shopId) ?: return 0
        val entry = shop.entries.firstOrNull { it.id.equals(entryId, ignoreCase = true) } ?: return 0
        if (!entry.supportsSell) {
            return 0
        }

        refreshResets(shop, entry, player.uniqueId)
        val unitItem = tradeItem(entry, TradeSide.SELL)
        val unitAmount = unitItem.amount.coerceAtLeast(1)
        val template = itemService.buildItem(unitItem, null)
        val totalOwned = itemService.countMatching(player.inventory, template)
        var maxMultiplier = totalOwned / unitAmount
        if (maxMultiplier <= 0) {
            return 0
        }

        val stats = store(shop.id).stats(entry.id, player.uniqueId)
        val effectiveSellLimit = effectiveLimit(entry.limits.sell, entry.sellPermissionLimits, player)
        val remainingPlayer = remainingAllowed(entry.limits.player, stats.playerTotal, unitAmount)
        val remainingGlobal = remainingAllowed(entry.limits.global, stats.globalTotal, unitAmount)
        val remainingSell = remainingAllowed(effectiveSellLimit, stats.playerSell, unitAmount)
        val remainingSellGlobal = remainingAllowed(entry.limits.sellGlobal, stats.globalSell, unitAmount)

        maxMultiplier = minOf(maxMultiplier, remainingPlayer, remainingGlobal, remainingSell, remainingSellGlobal)
        return maxMultiplier.coerceAtLeast(0)
    }

    fun messageService(): MessageService = messageService

    fun configuredText(path: String, fallback: String): String {
        return globalConfig.renderText.text(path, fallback)
    }

    private fun executeBuy(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        currency: CurrencyDefinition,
        totalPrice: Long,
        totalAmount: Int,
        replacements: Map<String, String>
    ): TradeResult {
        val charge = currencyService.take(player, currency, totalPrice, replacements)
        if (!charge.success) {
            return charge
        }

        when (entry.reward.type) {
            RewardType.ICON_ITEM,
            RewardType.CONFIG_ITEM -> {
                val gaveItem = itemService.tryGiveConfiguredItem(
                    player,
                    tradeItem(entry, TradeSide.BUY),
                    totalAmount,
                    replacements
                )
                if (!gaveItem) {
                    val refund = currencyService.give(player, currency, totalPrice, replacements)
                    if (!refund.success) {
                        logCompensation(
                            player = player,
                            shop = shop,
                            entry = entry,
                            side = TradeSide.BUY,
                            amount = totalAmount,
                            currency = currency,
                            totalPrice = totalPrice,
                            reason = "reward item delivery failed; refund failed: ${refund.messageKey}"
                        )
                    }
                    return TradeResult(false, "give-item-failed", replacements)
                }
            }

            // Command rewards and CUSTOM currency commands are weak-consistency integrations:
            // Bukkit command dispatch does not expose a reliable transaction result.
            RewardType.COMMANDS -> repeat(replacements["times"]?.toIntOrNull() ?: 1) {
                currencyService.dispatchCommands(player, entry.reward.commands, replacements)
            }

            RewardType.NONE -> Unit
        }
        return TradeResult(true, "success")
    }

    private fun executeSell(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        currency: CurrencyDefinition,
        totalPrice: Long,
        totalAmount: Int,
        replacements: Map<String, String>
    ): TradeResult {
        val sellTemplate = itemService.buildItem(tradeItem(entry, TradeSide.SELL), null)
        val count = itemService.countMatching(player.inventory, sellTemplate)
        if (count < totalAmount) {
            return TradeResult(
                false,
                "not-enough-items",
                replacements + mapOf("need" to totalAmount.toString(), "has" to count.toString())
            )
        }
        if (!itemService.removeMatching(player.inventory, sellTemplate, totalAmount)) {
            return TradeResult(false, "remove-item-failed", replacements)
        }
        val paid = currencyService.give(player, currency, totalPrice, replacements)
        if (!paid.success) {
            val returned = itemService.tryGiveConfiguredItem(player, tradeItem(entry, TradeSide.SELL), totalAmount, replacements)
            if (!returned) {
                logCompensation(
                    player = player,
                    shop = shop,
                    entry = entry,
                    side = TradeSide.SELL,
                    amount = totalAmount,
                    currency = currency,
                    totalPrice = totalPrice,
                    reason = "currency give failed: ${paid.messageKey}; item return failed"
                )
            }
            return paid
        }
        return paid
    }

    private fun checkBuyResources(
        player: Player,
        currency: CurrencyDefinition,
        totalPrice: Long,
        replacements: Map<String, String>
    ): TradeResult? {
        val canAfford = currencyService.canAfford(player, currency, totalPrice)
            ?: return null
        return if (canAfford) {
            null
        } else {
            TradeResult(false, "not-enough-currency", replacements + mapOf("currency" to currency.displayName))
        }
    }

    private fun checkSellResources(
        player: Player,
        entry: ShopEntry,
        totalAmount: Int,
        replacements: Map<String, String>
    ): TradeResult? {
        val sellTemplate = itemService.buildItem(tradeItem(entry, TradeSide.SELL), null)
        val count = itemService.countMatching(player.inventory, sellTemplate)
        return if (count >= totalAmount) {
            null
        } else {
            TradeResult(
                false,
                "not-enough-items",
                replacements + mapOf("need" to totalAmount.toString(), "has" to count.toString())
            )
        }
    }

    private fun checkLimits(entry: ShopEntry, stats: EntryStats, amount: Int, player: Player, side: TradeSide): TradeResult? {
        val nextTotal = stats.playerTotal + amount
        val nextGlobal = stats.globalTotal + amount
        val nextBuy = stats.playerBuy + amount
        val nextSell = stats.playerSell + amount
        val nextBuyGlobal = stats.globalBuy + amount
        val nextSellGlobal = stats.globalSell + amount
        val effectiveBuyLimit = effectiveLimit(entry.limits.buy, entry.buyPermissionLimits, player)
        val effectiveSellLimit = effectiveLimit(entry.limits.sell, entry.sellPermissionLimits, player)

        if (entry.limits.player != null && nextTotal > entry.limits.player) {
            return TradeResult(false, "limit-player", mapOf("limit" to entry.limits.player.toString()))
        }
        if (entry.limits.global != null && nextGlobal > entry.limits.global) {
            return TradeResult(false, "limit-global", mapOf("limit" to entry.limits.global.toString()))
        }
        if (side == TradeSide.BUY && effectiveBuyLimit != null && nextBuy > effectiveBuyLimit) {
            return TradeResult(false, "limit-buy", mapOf("limit" to effectiveBuyLimit.toString()))
        }
        if (side == TradeSide.BUY && entry.limits.buyGlobal != null && nextBuyGlobal > entry.limits.buyGlobal) {
            return TradeResult(false, "limit-buy", mapOf("limit" to entry.limits.buyGlobal.toString()))
        }
        if (side == TradeSide.SELL && effectiveSellLimit != null && nextSell > effectiveSellLimit) {
            return TradeResult(false, "limit-sell", mapOf("limit" to effectiveSellLimit.toString()))
        }
        if (side == TradeSide.SELL && entry.limits.sellGlobal != null && nextSellGlobal > entry.limits.sellGlobal) {
            return TradeResult(false, "limit-sell", mapOf("limit" to entry.limits.sellGlobal.toString()))
        }
        return null
    }

    fun createEntryRenderSnapshot(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        multiplier: Int = 1,
        requestedSide: TradeSide? = null
    ): ShopEntryRenderSnapshot {
        val preparation = prepareRenderPreparation(player, shop, listOf(entry))
        return createEntryRenderSnapshot(player, shop, entry, preparation, multiplier.coerceAtLeast(1), requestedSide)
    }

    private fun createEntryRenderSnapshot(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        preparation: RenderPreparation,
        multiplier: Int,
        requestedSide: TradeSide?
    ): ShopEntryRenderSnapshot {
        val context = createEntryRenderContext(
            player = player,
            shop = shop,
            entry = entry,
            preparation = preparation,
            times = multiplier.coerceAtLeast(1),
            requestedSide = requestedSide
        )
        val replacements = baseReplacements(context)
        val icon = templatedIcon(entry)
        return ShopEntryRenderSnapshot(
            entryId = entry.id,
            icon = icon,
            replacements = replacements,
            renderSignature = 31 * icon.hashCode() + replacements.hashCode()
        )
    }

    private fun prepareRenderPreparation(
        player: Player,
        shop: ShopDefinition,
        entries: Collection<ShopEntry>,
        now: Instant = Instant.now()
    ): RenderPreparation {
        if (entries.isEmpty()) {
            return RenderPreparation(emptyMap(), emptyMap())
        }

        val store = store(shop.id)
        var changed = false
        entries.forEach { entry ->
            changed = refreshResets(store, entry, player.uniqueId, now, save = false) || changed
        }
        if (changed) {
            store.saveAsync()
        }

        val statsByEntryId = entries.associate { entry ->
            entry.id.lowercase() to store.stats(entry.id, player.uniqueId)
        }
        val balanceTextsByCurrencyId = entries.asSequence()
            .map { currency(it.currencyId) }
            .distinctBy { it.id.lowercase() }
            .associate { currency ->
                currency.id.lowercase() to currencyService.currentBalanceText(player, currency)
            }

        return RenderPreparation(statsByEntryId, balanceTextsByCurrencyId)
    }

    private fun createEntryRenderContext(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        preparation: RenderPreparation,
        times: Int,
        requestedSide: TradeSide?
    ): EntryRenderContext {
        val currency = currency(entry.currencyId)
        val stats = preparation.statsByEntryId[entry.id.lowercase()] ?: store(shop.id).stats(entry.id, player.uniqueId)
        val preferredSide = requestedSide ?: if (entry.supportsBuy) TradeSide.BUY else TradeSide.SELL
        val effectiveBuyLimit = effectiveLimit(entry.limits.buy, entry.buyPermissionLimits, player)
        val effectiveSellLimit = effectiveLimit(entry.limits.sell, entry.sellPermissionLimits, player)
        val dual = entry.supportsBuy && entry.supportsSell
        val effectiveLore = effectiveIconLore(entry)
        val buyRefreshVisible = ShopLoreSupport.hasRefreshLine(effectiveLore, TradeSide.BUY, dual)
        val sellRefreshVisible = ShopLoreSupport.hasRefreshLine(effectiveLore, TradeSide.SELL, dual)
        val preferredTradeItem = tradeItem(entry, preferredSide)
        val leftTradeItem = tradeItem(entry, if (entry.supportsBuy) TradeSide.BUY else TradeSide.SELL)
        val rightTradeItem = tradeItem(entry, if (entry.supportsSell) TradeSide.SELL else preferredSide)
        val sellTradeItem = if (entry.supportsSell) tradeItem(entry, TradeSide.SELL) else null
        val buyTradeItem = if (entry.supportsBuy) tradeItem(entry, TradeSide.BUY) else null
        val buyUnitPrice = entry.buyPrice
        val sellUnitPrice = entry.sellPrice
        val canAffordBuy = buyUnitPrice?.let { currencyService.canAfford(player, currency, it) }
        val sellTemplate = sellTradeItem?.let { itemService.buildItem(it, null) }
        val sellItemCount = sellTemplate?.let { itemService.countMatching(player.inventory, it) } ?: 0
        return EntryRenderContext(
            player = player,
            shop = shop,
            entry = entry,
            currency = currency,
            stats = stats,
            requestedSide = requestedSide,
            times = times,
            amount = preferredTradeItem.amount * times,
            totalPrice = unitPrice(entry, preferredSide) * times,
            preferredSide = preferredSide,
            effectiveBuyLimit = effectiveBuyLimit,
            effectiveSellLimit = effectiveSellLimit,
            dual = dual,
            buyUnitPrice = buyUnitPrice,
            sellUnitPrice = sellUnitPrice,
            canAffordBuy = canAffordBuy,
            sellItemCount = sellItemCount,
            buyUnitAmount = buyTradeItem?.amount ?: 0,
            sellUnitAmount = sellTradeItem?.amount ?: 0,
            unitAmount = preferredTradeItem.amount,
            leftAmount = leftTradeItem.amount,
            rightAmount = rightTradeItem.amount,
            shiftAmount = preferredTradeItem.amount * if (!dual && shop.settings.buyMore) 64 else 1,
            materialName = prettifyMaterialName(entry.icon.material),
            buyRefreshText = nextResetText(entry.buyResetPolicy),
            sellRefreshText = nextResetText(entry.sellResetPolicy),
            buyRefreshVisible = buyRefreshVisible,
            sellRefreshVisible = sellRefreshVisible,
            balanceText = preparation.balanceTextsByCurrencyId[currency.id.lowercase()]
                ?: currencyService.currentBalanceText(player, currency)
        )
    }

    private fun baseReplacements(context: EntryRenderContext): Map<String, String> {
        val buyBlockedByPlayer = context.effectiveBuyLimit != null && context.stats.playerBuy >= context.effectiveBuyLimit
        val buyBlockedByServer = context.entry.limits.buyGlobal != null && context.stats.globalBuy >= context.entry.limits.buyGlobal
        val sellBlockedByPlayer = context.effectiveSellLimit != null && context.stats.playerSell >= context.effectiveSellLimit
        val sellBlockedByServer = context.entry.limits.sellGlobal != null && context.stats.globalSell >= context.entry.limits.sellGlobal
        return mapOf(
            "player_name" to context.player.name,
            "player_uuid" to context.player.uniqueId.toString(),
            "shop_id" to context.shop.id,
            "shop_name" to context.shop.settings.shopName,
            "entry_id" to context.entry.id,
            "entry_symbol" to context.entry.symbol.toString(),
            "currency" to context.currency.displayName,
            "price" to context.totalPrice.toString(),
            "unit_price" to unitPrice(context.entry, context.preferredSide).toString(),
            "buy-price" to (context.buyUnitPrice?.toString() ?: "-"),
            "sell-price" to (context.sellUnitPrice?.toString() ?: "-"),
            "amount" to context.amount.toString(),
            "unit_amount" to context.unitAmount.toString(),
            "material_name" to context.materialName,
            "material_key" to "minecraft:${context.entry.icon.material.lowercase()}",
            "times" to context.times.toString(),
            "trade_mode" to context.entry.mode.name,
            "trade_mode_display" to tradeModeDisplay(context.preferredSide),
            "left_times" to "1",
            "right_times" to if (context.dual) "1" else if (context.shop.settings.buyMore) "8" else "1",
            "shift_times" to if (context.dual) "1" else if (context.shop.settings.buyMore) "64" else "1",
            "left_amount" to context.leftAmount.toString(),
            "right_amount" to context.rightAmount.toString(),
            "shift_amount" to context.shiftAmount.toString(),
            "player_limit_remaining" to remaining(context.stats.playerTotal, context.entry.limits.player).toString(),
            "global_limit_remaining" to remaining(context.stats.globalTotal, context.entry.limits.global).toString(),
            "buy_limit_remaining" to remaining(context.stats.playerBuy, context.effectiveBuyLimit).toString(),
            "sell_limit_remaining" to remaining(context.stats.playerSell, context.effectiveSellLimit).toString(),
            "buy-times-player" to context.stats.playerBuy.toString(),
            "buy-times-server" to context.stats.globalBuy.toString(),
            "sell-times-player" to context.stats.playerSell.toString(),
            "sell-times-server" to context.stats.globalSell.toString(),
            "buy-limit-player" to formatLimit(context.effectiveBuyLimit),
            "buy-limit-server" to formatLimit(context.entry.limits.buyGlobal),
            "sell-limit-player" to formatLimit(context.effectiveSellLimit),
            "sell-limit-server" to formatLimit(context.entry.limits.sellGlobal),
            "buy-limit-player-line" to limitLoreLine(
                context.effectiveBuyLimit,
                configuredText("limits.buy-player-line", "&#FF7777玩家购买次数: {buy-times-player}/{buy-limit-player}")
            ),
            "buy-limit-server-line" to limitLoreLine(
                context.entry.limits.buyGlobal,
                configuredText("limits.buy-server-line", "&#FF7777服务器购买次数: {buy-times-server}/{buy-limit-server}")
            ),
            "sell-limit-player-line" to limitLoreLine(
                context.effectiveSellLimit,
                configuredText("limits.sell-player-line", "&#FF7777玩家出售次数: {sell-times-player}/{sell-limit-player}")
            ),
            "sell-limit-server-line" to limitLoreLine(
                context.entry.limits.sellGlobal,
                configuredText("limits.sell-server-line", "&#FF7777服务器出售次数: {sell-times-server}/{sell-limit-server}")
            ),
            "buy-blocked-player-line" to blockedLoreLine(
                blocked = buyBlockedByPlayer,
                blockedText = configuredText("blocked.buy-player-line", "&#ff3300无法再购买更多！"),
                policy = context.entry.buyResetPolicy,
                refreshAlreadyVisible = context.buyRefreshVisible,
                refreshToken = "{buy-refresh-player}"
            ),
            "buy-blocked-server-line" to blockedLoreLine(
                blocked = buyBlockedByServer,
                blockedText = configuredText("blocked.buy-server-line", "&#ff3300已售罄！"),
                policy = context.entry.buyResetPolicy,
                refreshAlreadyVisible = context.buyRefreshVisible,
                refreshToken = "{buy-refresh-server}"
            ),
            "buy-no-money-line" to if (context.canAffordBuy == false) configuredText("blocked.buy-no-money-line", "&#ff3300余额不足！") else ItemService.HIDDEN_LORE_SENTINEL,
            "sell-blocked-player-line" to blockedLoreLine(
                blocked = sellBlockedByPlayer,
                blockedText = configuredText("blocked.sell-player-line", "&#ff3300无法再出售更多！"),
                policy = context.entry.sellResetPolicy,
                refreshAlreadyVisible = context.sellRefreshVisible,
                refreshToken = "{sell-refresh-player}"
            ),
            "sell-blocked-server-line" to blockedLoreLine(
                blocked = sellBlockedByServer,
                blockedText = configuredText("blocked.sell-server-line", "&#ff3300服务器无法再出售更多！"),
                policy = context.entry.sellResetPolicy,
                refreshAlreadyVisible = context.sellRefreshVisible,
                refreshToken = "{sell-refresh-server}"
            ),
            "sell-no-items-line" to if (context.entry.supportsSell && context.sellItemCount < context.sellUnitAmount) configuredText("blocked.sell-no-items-line", "&#ff3300背包里没有可出售的物品！") else ItemService.HIDDEN_LORE_SENTINEL,
            "buy_left_hint_line" to clickHintLine(context.entry.supportsBuy, clickName("left"), TradeSide.BUY, context.shop.settings.tradeAmounts.buy.left, context.buyUnitAmount),
            "buy_right_hint_line" to clickHintLine(context.entry.supportsBuy, clickName("right"), TradeSide.BUY, context.shop.settings.tradeAmounts.buy.right, context.buyUnitAmount),
            "buy_shift_left_hint_line" to clickHintLine(context.entry.supportsBuy, clickName("shift-left"), TradeSide.BUY, context.shop.settings.tradeAmounts.buy.shiftLeft, context.buyUnitAmount),
            "buy_shift_right_hint_line" to clickHintLine(context.entry.supportsBuy, clickName("shift-right"), TradeSide.BUY, context.shop.settings.tradeAmounts.buy.shiftRight, context.buyUnitAmount),
            "sell_left_hint_line" to clickHintLine(context.entry.supportsSell, clickName("left"), TradeSide.SELL, context.shop.settings.tradeAmounts.sell.left, context.sellUnitAmount),
            "sell_right_hint_line" to clickHintLine(context.entry.supportsSell, clickName("right"), TradeSide.SELL, context.shop.settings.tradeAmounts.sell.right, context.sellUnitAmount),
            "sell_shift_left_hint_line" to clickHintLine(context.entry.supportsSell, clickName("shift-left"), TradeSide.SELL, context.shop.settings.tradeAmounts.sell.shiftLeft, context.sellUnitAmount),
            "sell_shift_right_hint_line" to clickHintLine(context.entry.supportsSell, clickName("shift-right"), TradeSide.SELL, context.shop.settings.tradeAmounts.sell.shiftRight, context.sellUnitAmount),
            "buy-refresh-player" to context.buyRefreshText,
            "buy-refresh-server" to context.buyRefreshText,
            "sell-refresh-player" to context.sellRefreshText,
            "sell-refresh-server" to context.sellRefreshText,
            "buy-refresh-player-line" to refreshLoreLine(context.entry.buyResetPolicy, configuredText("refresh.buy-player-line", "&8刷新时间: {buy-refresh-player}")),
            "sell-refresh-player-line" to refreshLoreLine(context.entry.sellResetPolicy, configuredText("refresh.sell-player-line", "&8刷新时间: {sell-refresh-player}")),
            "buy-refresh-dual-line" to refreshLoreLine(context.entry.buyResetPolicy, configuredText("refresh.buy-dual-line", "&8购买刷新: {buy-refresh-player}")),
            "sell-refresh-dual-line" to refreshLoreLine(context.entry.sellResetPolicy, configuredText("refresh.sell-dual-line", "&8出售刷新: {sell-refresh-player}")),
            "balance" to (context.balanceText ?: "-")
        )
    }

    private fun refreshResets(
        store: ShopDataStore,
        entry: ShopEntry,
        playerId: java.util.UUID,
        now: Instant,
        save: Boolean
    ): Boolean {
        val buyChanged = applyResetPolicy(store, entry, playerId, TradeSide.BUY, entry.buyResetPolicy, now, save)
        val sellChanged = applyResetPolicy(store, entry, playerId, TradeSide.SELL, entry.sellResetPolicy, now, save)
        return buyChanged || sellChanged
    }

    private fun applyResetPolicy(
        store: ShopDataStore,
        entry: ShopEntry,
        playerId: java.util.UUID,
        side: TradeSide,
        policy: ResetPolicy,
        now: Instant,
        save: Boolean
    ): Boolean {
        if (!policy.enabled) {
            return false
        }
        val marker = ShopResetSupport.currentMarker(policy, now, zoneId) ?: return false
        val playerChanged = applyResetScope(store, entry.id, playerId, side, ShopDataStore.ResetScope.PLAYER, marker, save)
        val globalChanged = applyResetScope(store, entry.id, GLOBAL_SCOPE_PLAYER_ID, side, ShopDataStore.ResetScope.GLOBAL, marker, save)
        return playerChanged || globalChanged
    }

    private fun refreshResets(shop: ShopDefinition, entry: ShopEntry, playerId: java.util.UUID) {
        val store = store(shop.id)
        val now = Instant.now()
        applyResetPolicy(store, entry, playerId, TradeSide.BUY, entry.buyResetPolicy, now)
        applyResetPolicy(store, entry, playerId, TradeSide.SELL, entry.sellResetPolicy, now)
    }

    private fun applyResetPolicy(
        store: ShopDataStore,
        entry: ShopEntry,
        playerId: java.util.UUID,
        side: TradeSide,
        policy: ResetPolicy,
        now: Instant
    ) {
        if (!policy.enabled) {
            return
        }
        val marker = ShopResetSupport.currentMarker(policy, now, zoneId) ?: return
        applyResetScope(store, entry.id, playerId, side, ShopDataStore.ResetScope.PLAYER, marker)
        applyResetScope(store, entry.id, GLOBAL_SCOPE_PLAYER_ID, side, ShopDataStore.ResetScope.GLOBAL, marker)
    }

    private fun applyResetScope(
        store: ShopDataStore,
        entryId: String,
        playerId: java.util.UUID,
        side: TradeSide,
        scope: ShopDataStore.ResetScope,
        marker: Long,
        save: Boolean = true
    ): Boolean {
        val previousMarker = store.sideResetMarker(entryId, playerId, side, scope)
        val currentCount = store.sideCount(entryId, playerId, side, scope)
        return when (ShopResetSupport.resolveScopeAction(previousMarker, marker, currentCount)) {
            ResetScopeAction.NONE -> false
            ResetScopeAction.MARK_ONLY -> {
                store.setSideResetMarker(entryId, playerId, side, scope, marker, save)
                true
            }

            ResetScopeAction.RESET_AND_MARK -> {
                store.resetSide(entryId, playerId, side, scope, save = false)
                store.setSideResetMarker(entryId, playerId, side, scope, marker, save)
                true
            }
        }
    }

    private fun refreshAllResets(now: Instant, logSummary: Boolean = false) {
        shops.values.forEach { shop ->
            val store = store(shop.id)
            var changed = false
            val summaries = mutableListOf<ResetSummary>()
            shop.entries.forEach { entry ->
                applyScheduledResetPolicy(store, entry, TradeSide.BUY, entry.buyResetPolicy, now)?.let { summary ->
                    changed = true
                    summaries += summary
                }
                applyScheduledResetPolicy(store, entry, TradeSide.SELL, entry.sellResetPolicy, now)?.let { summary ->
                    changed = true
                    summaries += summary
                }
            }
            if (changed) {
                store.save()
                if (logSummary) {
                    summaries.filter { it.totalResets > 0 }.forEach { summary ->
                        plugin.logger.info(
                            "Shop reset applied: shop=${shop.id}, entry=${summary.entryId}, side=${summary.side.name}, " +
                                "playerResets=${summary.playerResets}, globalReset=${summary.globalReset}, zone=$zoneId"
                        )
                    }
                }
            }
        }
    }

    private fun applyScheduledResetPolicy(
        store: ShopDataStore,
        entry: ShopEntry,
        side: TradeSide,
        policy: ResetPolicy,
        now: Instant
    ): ResetSummary? {
        if (!policy.enabled) {
            return null
        }

        val marker = ShopResetSupport.currentMarker(policy, now, zoneId) ?: return null
        var playerResets = 0
        var globalReset = false

        if (applyResetScope(
                store = store,
                entryId = entry.id,
                playerId = GLOBAL_SCOPE_PLAYER_ID,
                side = side,
                scope = ShopDataStore.ResetScope.GLOBAL,
                marker = marker,
                save = false
            )
        ) {
            globalReset = true
        }

        store.playerIds(entry.id).forEach { playerId ->
            if (applyResetScope(
                    store = store,
                    entryId = entry.id,
                    playerId = playerId,
                    side = side,
                    scope = ShopDataStore.ResetScope.PLAYER,
                    marker = marker,
                    save = false
                )
            ) {
                playerResets++
            }
        }
        if (!globalReset && playerResets == 0) {
            return null
        }
        return ResetSummary(entry.id, side, playerResets, globalReset)
    }

    private fun scheduleNextReset(now: Instant) {
        cancelScheduledResetTask()
        val nextReset = shops.values.asSequence()
            .flatMap { shop -> shop.entries.asSequence() }
            .flatMap { entry -> sequenceOf(entry.buyResetPolicy, entry.sellResetPolicy) }
            .filter { it.enabled }
            .mapNotNull { ShopResetSupport.nextResetInstant(it, now, zoneId) }
            .minOrNull()
            ?: return

        val delayMillis = Duration.between(now, nextReset).toMillis().coerceAtLeast(50L)
        val delayTicks = ((delayMillis + 49L) / 50L).coerceAtLeast(1L)

        scheduledResetTask = platformExecutor.runGlobalLater(delayTicks) {
            scheduledResetTask = null
            val executeAt = Instant.now()
            runCatching {
                refreshAllResets(executeAt, logSummary = true)
                notifyResetListeners()
            }.onFailure { ex ->
                plugin.logger.severe("Failed to execute scheduled shop reset: ${ex.message}")
            }
            scheduleNextReset(executeAt)
        }
    }

    private fun cancelScheduledResetTask() {
        scheduledResetTask?.cancel()
        scheduledResetTask = null
    }

    private fun notifyResetListeners() {
        resetListeners.forEach { listener ->
            runCatching(listener).onFailure { ex ->
                plugin.logger.warning("Failed to notify shop reset listener: ${ex.message}")
            }
        }
    }

    private fun nextResetText(policy: ResetPolicy): String {
        if (!policy.enabled) {
            return configuredText("reset.disabled", "未启用")
        }
        val now = Instant.now()
        val next = when (policy.mode) {
            ResetMode.NONE -> return configuredText("reset.disabled", "未启用")
            ResetMode.TIMED -> {
                val time = parseTime(policy.time) ?: return configuredText("reset.disabled", "未启用")
                val zonedNow = now.atZone(zoneId)
                var candidate = zonedNow.toLocalDate().atTime(time).atZone(zoneId)
                if (!candidate.toInstant().isAfter(now)) {
                    candidate = candidate.plusDays(1)
                }
                candidate.toInstant()
            }

            ResetMode.WEEKLY -> {
                val day = parseDay(policy.day) ?: DayOfWeek.MONDAY
                val time = parseTime(policy.time) ?: LocalTime.MIDNIGHT
                val zonedNow = now.atZone(zoneId)
                var candidate = zonedNow.with(day).toLocalDate().atTime(time).atZone(zoneId)
                if (!candidate.toInstant().isAfter(now)) {
                    candidate = candidate.plusWeeks(1)
                }
                candidate.toInstant()
            }

            ResetMode.INTERVAL -> {
                val intervalMillis = parseIntervalMillis(policy.interval) ?: return configuredText("reset.disabled", "未启用")
                Instant.ofEpochMilli(((now.toEpochMilli() / intervalMillis) + 1) * intervalMillis)
            }
        }
        return formatDuration(Duration.between(now, next))
    }

    private fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { LocalTime.parse(raw) }.getOrNull()
    }

    private fun parseDay(raw: String?): DayOfWeek? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { DayOfWeek.valueOf(raw.uppercase()) }.getOrNull()
    }

    private fun parseIntervalMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val value = raw.trim().lowercase()
        val match = Regex("""^(\d+)([smhdw])$""").matchEntire(value) ?: return value.toLongOrNull()
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "s" -> amount * 1000L
            "m" -> amount * 60_000L
            "h" -> amount * 3_600_000L
            "d" -> amount * 86_400_000L
            "w" -> amount * 604_800_000L
            else -> null
        }
    }

    private fun formatDuration(duration: Duration): String {
        var seconds = duration.seconds.coerceAtLeast(0)
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        seconds %= 60
        return buildString {
            if (days > 0) append(configuredText("duration.day", "{value}天").replace("{value}", days.toString()))
            if (hours > 0) append(configuredText("duration.hour", "{value}小时").replace("{value}", hours.toString()))
            if (minutes > 0) append(configuredText("duration.minute", "{value}分钟").replace("{value}", minutes.toString()))
            if (seconds > 0 || isEmpty()) append(configuredText("duration.second", "{value}秒").replace("{value}", seconds.toString()))
        }
    }

    private fun validateShop(shop: ShopDefinition, config: GlobalConfig = globalConfig) {
        require(shop.entries.map { it.id.lowercase() }.distinct().size == shop.entries.size) {
            "${shop.sourceFile} -> duplicate entry ids"
        }

        shop.entries.forEach { entry ->
            require(config.currencies.containsKey(entry.currencyId)) {
                "${shop.sourceFile} -> items.${entry.id} -> unknown currency ${entry.currencyId}"
            }
            require(entry.supportsBuy || entry.supportsSell) {
                "${shop.sourceFile} -> items.${entry.id} -> must define at least one of buy-price or sell-price"
            }
            if (entry.supportsBuy && entry.reward.type == RewardType.CONFIG_ITEM) {
                require(entry.reward.item != null) {
                    "${shop.sourceFile} -> items.${entry.id} -> reward.item required for CONFIG_ITEM"
                }
            }
            if (entry.supportsBuy && entry.reward.type == RewardType.COMMANDS) {
                require(entry.reward.commands.isNotEmpty()) {
                    "${shop.sourceFile} -> items.${entry.id} -> reward.commands required for COMMANDS"
                }
            }
            if (entry.supportsSell) {
                require(entry.tradeItem != null) {
                    "${shop.sourceFile} -> items.${entry.id} -> trade-item or products required for sell support"
                }
            }
        }
    }

    private fun validateLayout(layout: ym.ymshop.model.LayoutDefinition) {
        layout.buttons.forEach { (symbol, button) ->
            if (button.actions.isEmpty()) {
                return@forEach
            }
            button.actions.forEach { action ->
                when (action.type) {
                    ym.ymshop.model.ButtonActionType.OPEN_SHOP ->
                        require(!action.target.isNullOrBlank()) {
                            "${layout.sourceFile} -> buttons.$symbol -> OPEN_SHOP requires menu/shop target"
                        }

                    ym.ymshop.model.ButtonActionType.COMMAND ->
                        require(action.commands.isNotEmpty()) {
                            "${layout.sourceFile} -> buttons.$symbol -> COMMAND requires command(s)"
                        }

                    else -> Unit
                }
            }
        }
    }

    private fun todayMoneySummary(player: Player): DailyMoneySummary {
        val currency = preferredSummaryCurrency()
        val today = LocalDate.now(zoneId)
        if (currency == null) {
            return DailyMoneySummary(
                currencyDisplayName = configuredText("summary.default-currency", "金额"),
                buySpent = 0L,
                sellEarned = 0L,
                sellRemaining = 0L,
                day = today
            )
        }

        val totals = dailyTradeStore.totals(player.uniqueId, today, currency.id)
        return DailyMoneySummary(
            currencyDisplayName = currency.displayName,
            buySpent = totals.buySpent,
            sellEarned = totals.sellEarned,
            sellRemaining = remainingSellValue(player, currency.id),
            day = today
        )
    }

    private fun preferredSummaryCurrency(): CurrencyDefinition? {
        val activeCurrencies = shops.values.asSequence()
            .flatMap { shop -> shop.entries.asSequence() }
            .map { entry -> entry.currencyId }
            .toList()

        return activeCurrencies.firstNotNullOfOrNull { currencyId ->
            globalConfig.currencies.entries.firstOrNull { (configuredId, _) ->
                configuredId.equals(currencyId, ignoreCase = true) &&
                    configuredId.equals("vault", ignoreCase = true)
            }?.value
        } ?: activeCurrencies.firstNotNullOfOrNull { currencyId ->
            globalConfig.currencies.entries.firstOrNull { (configuredId, _) ->
                configuredId.equals(currencyId, ignoreCase = true)
            }?.value
        } ?: globalConfig.currencies.values.firstOrNull()
    }

    private fun remainingSellValue(player: Player, currencyId: String): Long? {
        var total = 0L

        shops.values.forEach { shop ->
            shop.entries.forEach { entry ->
                if (!entry.supportsSell || !entry.currencyId.equals(currencyId, ignoreCase = true)) {
                    return@forEach
                }

                refreshResets(shop, entry, player.uniqueId)
                val stats = store(shop.id).stats(entry.id, player.uniqueId)
                val remainingTrades = remainingSellTrades(entry, stats, player) ?: return null
                total += remainingTrades * requireNotNull(entry.sellPrice)
            }
        }

        return total
    }

    private fun remainingSellTrades(entry: ShopEntry, stats: EntryStats, player: Player): Long? {
        val unitAmount = tradeItem(entry, TradeSide.SELL).amount.coerceAtLeast(1)
        val effectiveSellLimit = effectiveLimit(entry.limits.sell, entry.sellPermissionLimits, player)
        return listOfNotNull(
            remainingTradeAllowance(entry.limits.player, stats.playerTotal, unitAmount),
            remainingTradeAllowance(entry.limits.global, stats.globalTotal, unitAmount),
            remainingTradeAllowance(effectiveSellLimit, stats.playerSell, unitAmount),
            remainingTradeAllowance(entry.limits.sellGlobal, stats.globalSell, unitAmount)
        ).minOrNull()
    }

    private fun remainingTradeAllowance(limit: Long?, current: Long, unitAmount: Int): Long? {
        if (limit == null || limit < 0) {
            return null
        }
        return (limit - current).coerceAtLeast(0) / unitAmount.coerceAtLeast(1)
    }

    private fun remaining(current: Long, max: Long?): Long {
        return if (max == null || max < 0) -1 else (max - current).coerceAtLeast(0)
    }

    private fun formatMoney(value: Long?): String {
        return value?.toString() ?: configuredText("format.unlimited", "无限")
    }

    private fun tradeModeDisplay(side: TradeSide): String {
        return when (side) {
            TradeSide.BUY -> configuredText("trade-mode.buy", "购买")
            TradeSide.SELL -> configuredText("trade-mode.sell", "出售")
        }
    }

    private fun clickName(path: String): String {
        return when (path) {
            "left" -> configuredText("click.left", "左键")
            "right" -> configuredText("click.right", "右键")
            "shift-left" -> configuredText("click.shift-left", "Shift+左键")
            "shift-right" -> configuredText("click.shift-right", "Shift+右键")
            else -> path
        }
    }

    private fun renderTextTemplate(template: String, replacements: Map<String, String>): String {
        var result = template
        replacements.forEach { (key, value) ->
            result = result
                .replace("{$key}", value)
                .replace("%$key%", value)
        }
        return result
    }

    private fun clickHintLine(
        supported: Boolean,
        clickName: String,
        side: TradeSide,
        configuredAmount: TradeClickAmountDefinition,
        unitAmount: Int
    ): String {
        if (!supported || configuredAmount.mode == TradeClickAmountMode.DISABLED) {
            return ItemService.HIDDEN_LORE_SENTINEL
        }

        val actionName = when (side) {
            TradeSide.BUY -> configuredText("click.buy-action", "购买")
            TradeSide.SELL -> configuredText("click.sell-action", "出售")
        }
        return when (configuredAmount.mode) {
            TradeClickAmountMode.ALL -> renderTextTemplate(
                configuredText("click.all-line", "&#FF5252{click}: &#90A4AE{action}全部"),
                mapOf("click" to clickName, "action" to actionName)
            )
            TradeClickAmountMode.FIXED -> {
                val totalAmount = unitAmount.coerceAtLeast(1) * configuredAmount.amount.coerceAtLeast(1)
                renderTextTemplate(
                    configuredText("click.fixed-line", "&#FF5252{click}: &#90A4AE{action} {amount} 个"),
                    mapOf("click" to clickName, "action" to actionName, "amount" to totalAmount.toString())
                )
            }

            TradeClickAmountMode.DISABLED -> ItemService.HIDDEN_LORE_SENTINEL
        }
    }

    private fun nextSummaryResetText(): String {
        val now = Instant.now()
        val nextReset = now.atZone(zoneId)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
        return formatDuration(Duration.between(now, nextReset))
    }

    private fun formatLimit(limit: Long?): String {
        return if (limit == null || limit < 0) configuredText("format.unlimited", "无限") else limit.toString()
    }

    private fun remainingAllowed(limit: Long?, current: Long, unitAmount: Int): Int {
        if (limit == null || limit < 0) {
            return Int.MAX_VALUE
        }
        val remaining = (limit - current).coerceAtLeast(0)
        return (remaining / unitAmount).toInt()
    }

    private fun limitLoreLine(limit: Long?, template: String): String {
        return if (limit == null || limit < 0) ItemService.HIDDEN_LORE_SENTINEL else template
    }

    private fun refreshLoreLine(policy: ResetPolicy, template: String): String {
        return if (policy.enabled) template else ItemService.HIDDEN_LORE_SENTINEL
    }

    private fun blockedLoreLine(
        blocked: Boolean,
        blockedText: String,
        policy: ResetPolicy,
        refreshAlreadyVisible: Boolean,
        refreshToken: String
    ): String {
        return ShopLoreSupport.blockedLine(
            blocked = blocked,
            blockedText = blockedText,
            policyEnabled = policy.enabled,
            refreshAlreadyVisible = refreshAlreadyVisible,
            refreshToken = refreshToken
        ) ?: ItemService.HIDDEN_LORE_SENTINEL
    }

    private fun templatedIcon(entry: ShopEntry): ConfiguredItem {
        val template = when {
            entry.supportsBuy && entry.supportsSell -> globalConfig.dualItemTemplate
            entry.supportsBuy -> globalConfig.buyItemTemplate
            else -> globalConfig.sellItemTemplate
        }
        return entry.icon.copy(
            name = entry.icon.name ?: template.defaultName,
            lore = effectiveIconLore(entry, template)
        )
    }

    private fun effectiveIconLore(
        entry: ShopEntry,
        template: ym.ymshop.model.ItemTemplateDefinition = when {
            entry.supportsBuy && entry.supportsSell -> globalConfig.dualItemTemplate
            entry.supportsBuy -> globalConfig.buyItemTemplate
            else -> globalConfig.sellItemTemplate
        }
    ): List<String> {
        return buildList {
            if (entry.icon.lore.isEmpty()) {
                addAll(template.defaultLore)
            } else {
                addAll(entry.icon.lore)
            }
            addAll(template.appendLore)
        }
    }

    private fun effectiveLimit(base: Long?, overrides: List<PermissionLimit>, player: Player): Long? {
        val matched = overrides
            .filter { player.hasPermission(it.permission) }
            .maxOfOrNull { it.amount }
        return matched ?: base
    }

    private fun tradeItem(entry: ShopEntry, side: TradeSide): ConfiguredItem {
        return when (side) {
            TradeSide.BUY -> when (entry.reward.type) {
                RewardType.ICON_ITEM -> entry.tradeItem ?: entry.icon
                RewardType.CONFIG_ITEM -> entry.reward.item ?: entry.tradeItem ?: entry.icon
                RewardType.COMMANDS, RewardType.NONE -> entry.tradeItem ?: entry.icon
            }

            TradeSide.SELL -> entry.tradeItem ?: entry.icon
        }
    }

    private fun unitPrice(entry: ShopEntry, side: TradeSide): Long {
        return when (side) {
            TradeSide.BUY -> requireNotNull(entry.buyPrice) { "Entry ${entry.id} does not support buy" }
            TradeSide.SELL -> requireNotNull(entry.sellPrice) { "Entry ${entry.id} does not support sell" }
        }
    }

    private fun currency(id: String): CurrencyDefinition {
        return globalConfig.currencies[id] ?: error("Unknown currency: $id")
    }

    private fun store(shopId: String): ShopDataStore {
        return dataStores[shopId.lowercase()] ?: error("Missing data store for shop $shopId")
    }

    private fun logResetTimezone(now: Instant) {
        val nextReset = shops.values.asSequence()
            .flatMap { shop -> shop.entries.asSequence() }
            .flatMap { entry -> sequenceOf(entry.buyResetPolicy, entry.sellResetPolicy) }
            .filter { it.enabled }
            .mapNotNull { ShopResetSupport.nextResetInstant(it, now, zoneId) }
            .minOrNull()
        val nextResetText = nextReset?.atZone(zoneId)?.toString() ?: "disabled"
        plugin.logger.info("Shop reset timezone active: zone=$zoneId, now=${now.atZone(zoneId)}, nextReset=$nextResetText")
    }

    private companion object {
        val GLOBAL_SCOPE_PLAYER_ID: java.util.UUID = java.util.UUID(0L, 0L)
    }

    private fun logTrade(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        side: TradeSide,
        amount: Int,
        unitPrice: Long,
        totalPrice: Long,
        currency: CurrencyDefinition
    ) {
        tradeLogService.logTrade(
            TradeLogService.TradeLogEntry(
                timestamp = Instant.now(),
                zoneId = zoneId,
                playerName = player.name,
                playerId = player.uniqueId,
                side = side,
                shopId = shop.id,
                entryId = entry.id,
                itemInfo = itemInfo(entry, side),
                amount = amount,
                unitPrice = unitPrice,
                totalPrice = totalPrice,
                currencyId = currency.id
            )
        )
    }

    private fun logCompensation(
        player: Player,
        shop: ShopDefinition,
        entry: ShopEntry,
        side: TradeSide,
        amount: Int,
        currency: CurrencyDefinition,
        totalPrice: Long,
        reason: String
    ) {
        tradeLogService.logCompensation(
            TradeLogService.CompensationLogEntry(
                timestamp = Instant.now(),
                zoneId = zoneId,
                playerName = player.name,
                playerId = player.uniqueId,
                side = side,
                shopId = shop.id,
                entryId = entry.id,
                itemInfo = itemInfo(entry, side),
                amount = amount,
                currencyId = currency.id,
                totalPrice = totalPrice,
                failureReason = reason
            )
        )
    }

    private fun itemInfo(entry: ShopEntry, side: TradeSide): String {
        val item = tradeItem(entry, side)
        return "${item.material}x${item.amount}"
    }

    private data class ResetSummary(
        val entryId: String,
        val side: TradeSide,
        val playerResets: Int,
        val globalReset: Boolean
    ) {
        val totalResets: Int
            get() = playerResets + if (globalReset) 1 else 0
    }

    private data class DailyMoneySummary(
        val currencyDisplayName: String,
        val buySpent: Long,
        val sellEarned: Long,
        val sellRemaining: Long?,
        val day: LocalDate
    )
}
