package ym.ymshop.service

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ym.ymshop.model.ButtonActionType
import ym.ymshop.model.ConfiguredItem
import ym.ymshop.model.LayoutButtonDefinition
import ym.ymshop.model.OpenActionType
import ym.ymshop.model.ShopDefinition
import ym.ymshop.model.ShopEntry
import ym.ymshop.model.TradeClickAmountDefinition
import ym.ymshop.model.TradeClickAmountMode
import ym.ymshop.model.TradeSide
import ym.ymshop.util.applyText
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ShopGuiService(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val platformExecutor: PlatformExecutor,
    private val shopService: ShopService,
    private val itemService: ItemService,
    private val currencyService: CurrencyService,
    private val favoriteService: FavoriteService
) : Listener {

    private val navigationStacks = ConcurrentHashMap<UUID, ArrayDeque<PageState>>()
    private val transitioningPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastTradeActionAt = ConcurrentHashMap<UUID, Long>()
    private val liveRefreshTask = platformExecutor.runGlobalTimer(LIVE_REFRESH_PERIOD_TICKS, LIVE_REFRESH_PERIOD_TICKS) {
        refreshOpenInventories()
    }

    fun close() {
        liveRefreshTask.cancel()
    }

    fun open(player: Player, shopId: String, mode: OpenMode = OpenMode.OPEN, page: Int = 0, playOpenEffects: Boolean = true) {
        val shop = shopService.findShop(shopId) ?: run {
            shopService.messageService().send(player, "shop-not-found", mapOf("shop" to shopId))
            return
        }
        val snapshot = shopService.createPageRenderSnapshot(player, shop, page)
        val holder = ShopInventoryHolder(shop.id, snapshot.page, mutableMapOf(), mutableMapOf())
        val inventory = createInventory(
            holder,
            shop.layout.size,
            applyText(player, shopService.renderTitle(shop), snapshot.layoutReplacements)
        )
        applyShopRenderSnapshot(player, shop, holder, inventory, snapshot, force = true)

        updateNavigation(player.uniqueId, PageState.ShopPage(shop.id, snapshot.page), mode)
        if (playOpenEffects) {
            playOpenActions(player, shop)
        }
        openInventory(player, inventory)
    }

    fun openFavorites(player: Player, mode: OpenMode = OpenMode.OPEN, page: Int = 0) {
        val validEntries = favoriteService.entries(player.uniqueId).mapNotNull { favorite ->
            val shop = shopService.findShop(favorite.shopId) ?: run {
                favoriteService.remove(player.uniqueId, favorite.shopId, favorite.entryId)
                return@mapNotNull null
            }
            val entry = shop.entries.firstOrNull { it.id.equals(favorite.entryId, ignoreCase = true) } ?: run {
                favoriteService.remove(player.uniqueId, favorite.shopId, favorite.entryId)
                return@mapNotNull null
            }
            FavoriteDisplay(shop, entry)
        }

        val itemSlots = favoriteItemSlots()
        val pageSize = itemSlots.size.coerceAtLeast(1)
        val totalPages = (validEntries.size + pageSize - 1).coerceAtLeast(pageSize) / pageSize
        val safeTotalPages = totalPages.coerceAtLeast(1)
        val safePage = page.coerceIn(0, safeTotalPages - 1)
        val pageEntries = validEntries.drop(safePage * pageSize).take(pageSize)
        val slotToFavorite = buildMap {
            pageEntries.forEachIndexed { index, favorite ->
                val slotIndex = itemSlots.getOrNull(index) ?: return@forEachIndexed
                put(slotIndex, favorite)
            }
        }

        val holder = FavoritesInventoryHolder(safePage, slotToFavorite)
        val inventory = createInventory(holder, 54, applyText(player, configText("favorites-menu.title", "&6我的收藏")))
        fillFavoritesBackground(player, inventory)

        pageEntries.forEachIndexed { index, favorite ->
            val slotIndex = itemSlots.getOrNull(index) ?: return@forEachIndexed
            inventory.setItem(slotIndex, buildShopDisplayItem(player, favorite.shop, favorite.entry))
        }

        if (pageEntries.isEmpty()) {
            inventory.setItem(
                22,
                itemService.buildItem(
                    configItem(
                        "favorites-menu.empty-item",
                        ConfiguredItem(
                            material = "NETHER_STAR",
                            name = "&e我的收藏",
                            lore = listOf("&7当前还没有收藏商品", "&7在商品页按 &fQ &7即可收藏")
                        )
                    ),
                    player
                )
            )
        }

        inventory.setItem(
            45,
            pageButtonItem(player, page = safePage, totalPages = safeTotalPages, previous = true, enabled = safePage > 0)
        )
        inventory.setItem(
            49,
            backOrCloseItem(player, hasPrevious = hasPreviousNavigation(player.uniqueId))
        )
        inventory.setItem(
            53,
            pageButtonItem(player, page = safePage, totalPages = safeTotalPages, previous = false, enabled = safePage + 1 < safeTotalPages)
        )

        updateNavigation(player.uniqueId, PageState.FavoritesPage(safePage), mode)
        openInventory(player, inventory)
    }

    fun refreshOpenInventories() {
        plugin.server.onlinePlayers.forEach { player ->
            if (!player.isOnline) {
                return@forEach
            }
            platformExecutor.runForPlayer(player) {
                refreshCurrentView(player)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? YmShopInventoryHolder ?: return
        event.isCancelled = true

        when (holder) {
            is ShopInventoryHolder -> handleShopInventoryClick(player, holder, event)
            is FavoritesInventoryHolder -> handleFavoritesInventoryClick(player, holder, event)
            is ConfirmInventoryHolder -> handleConfirmInventoryClick(player, holder, event)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is YmShopInventoryHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (event.inventory.holder !is YmShopInventoryHolder) {
            return
        }
        if (transitioningPlayers.contains(player.uniqueId)) {
            return
        }
        navigationStacks.remove(player.uniqueId)
    }

    private fun handleShopInventoryClick(player: Player, holder: ShopInventoryHolder, event: InventoryClickEvent) {
        val rawSlot = event.rawSlot
        val topSize = event.view.topInventory.size
        if (rawSlot !in 0 until topSize) {
            return
        }

        val entryId = holder.slotToEntryId[rawSlot]
        if (entryId != null) {
            val shop = shopService.findShop(holder.shopId) ?: return
            val entry = shop.entries.firstOrNull { it.id.equals(entryId, ignoreCase = true) } ?: return
            if (handleFavoriteToggleClick(player, event.click, shop, entry)) {
                open(player, holder.shopId, OpenMode.REPLACE, holder.page)
                return
            }

            val tradeAction = resolveTradeAction(shop, entry, event.click, player) ?: return
            if (!tryAcquireTradeCooldown(player.uniqueId)) {
                return
            }
            if (tradeAction.multiplier == 1) {
                val result = shopService.execute(player, holder.shopId, entry.id, tradeAction.side, tradeAction.multiplier)
                shopService.messageService().send(player, result.messageKey, result.replacements, player)
                if (result.success) {
                    // 只刷新当前商品槽位，避免整页菜单重开导致卡顿
                    event.view.topInventory.setItem(rawSlot, buildShopDisplayItem(player, shop, entry))
                }
                return
            }
            openConfirm(
                player = player,
                returnState = PageState.ShopPage(holder.shopId, holder.page),
                shopId = holder.shopId,
                entry = entry,
                side = tradeAction.side,
                multiplier = tradeAction.multiplier
            )
            return
        }

        val button = holder.slotToButton[rawSlot] ?: return
        val shop = shopService.findShop(holder.shopId) ?: return
        handleButtonClick(player, shop, button, holder.page, shopService.pageCount(shop))
    }

    private fun handleFavoritesInventoryClick(player: Player, holder: FavoritesInventoryHolder, event: InventoryClickEvent) {
        val rawSlot = event.rawSlot
        val topSize = event.view.topInventory.size
        if (rawSlot !in 0 until topSize) {
            return
        }

        when (rawSlot) {
            45 -> {
                if (holder.page > 0) {
                    openFavorites(player, OpenMode.REPLACE, holder.page - 1)
                }
                return
            }

            49 -> {
                val previous = popNavigationBack(player.uniqueId)
                if (previous == null) {
                    navigationStacks.remove(player.uniqueId)
                    closeInventory(player)
                    return
                }
                reopenState(player, previous, OpenMode.REPLACE)
                return
            }

            53 -> {
                openFavorites(player, OpenMode.REPLACE, holder.page + 1)
                return
            }
        }

        val favorite = holder.slotToFavorite[rawSlot] ?: return
        if (handleFavoriteToggleClick(player, event.click, favorite.shop, favorite.entry)) {
            openFavorites(player, OpenMode.REPLACE, holder.page)
            return
        }

        val tradeAction = resolveTradeAction(favorite.shop, favorite.entry, event.click, player) ?: return
        if (!tryAcquireTradeCooldown(player.uniqueId)) {
            return
        }
        if (tradeAction.multiplier == 1) {
            val result = shopService.execute(player, favorite.shop.id, favorite.entry.id, tradeAction.side, tradeAction.multiplier)
            shopService.messageService().send(player, result.messageKey, result.replacements, player)
            if (result.success) {
                // 只刷新当前收藏条目的槽位
                event.view.topInventory.setItem(rawSlot, buildShopDisplayItem(player, favorite.shop, favorite.entry))
            }
            return
        }
        openConfirm(
            player = player,
            returnState = PageState.FavoritesPage(holder.page),
            shopId = favorite.shop.id,
            entry = favorite.entry,
            side = tradeAction.side,
            multiplier = tradeAction.multiplier
        )
    }

    private fun handleConfirmInventoryClick(player: Player, holder: ConfirmInventoryHolder, event: InventoryClickEvent) {
        val rawSlot = event.rawSlot
        val topSize = event.view.topInventory.size
        if (rawSlot !in 0 until topSize) {
            return
        }

        when (rawSlot) {
            holder.confirmSlot -> {
                if (!tryAcquireTradeCooldown(player.uniqueId)) {
                    return
                }
                val result = shopService.execute(player, holder.shopId, holder.entryId, holder.side, holder.multiplier)
                shopService.messageService().send(player, result.messageKey, result.replacements, player)
                reopenState(player, holder.returnState, OpenMode.REPLACE)
            }

            holder.cancelSlot -> reopenState(player, holder.returnState, OpenMode.REPLACE)
        }
    }

    private fun handleButtonClick(player: Player, shop: ShopDefinition, button: LayoutButtonDefinition, page: Int, totalPages: Int) {
        button.actions.forEach { action ->
            when (action.type) {
                ButtonActionType.OPEN_SHOP -> {
                    val target = action.target ?: return@forEach
                    if (target.equals("favorites", ignoreCase = true)) {
                        openFavorites(player, OpenMode.PUSH, 0)
                        return
                    }

                    val openCheck = shopService.openShop(player, target)
                    if (!openCheck.success) {
                        if (!shop.settings.hideMessage) {
                            shopService.messageService().send(player, openCheck.messageKey, openCheck.replacements, player)
                        }
                        return
                    }
                    open(player, target, OpenMode.PUSH, 0)
                    return
                }

                ButtonActionType.CLOSE -> {
                    navigationStacks.remove(player.uniqueId)
                    closeInventory(player)
                    return
                }

                ButtonActionType.RELOAD -> {
                    if (!player.hasPermission("ymshop.reload")) {
                        shopService.messageService().send(player, "no-permission", player = player)
                        return
                    }
                    platformExecutor.runGlobalAsync {
                        shopService.reload()
                    }.whenComplete { _, ex ->
                        platformExecutor.runForPlayer(player) {
                            if (ex != null) {
                                val cause = ex.cause ?: ex
                                shopService.messageService().send(
                                    player,
                                    "reload-failed",
                                    mapOf("reason" to (cause.message ?: cause.javaClass.simpleName)),
                                    player
                                )
                                return@runForPlayer
                            }
                            if (!shop.settings.hideMessage) {
                                shopService.messageService().send(player, "reload-success", player = player)
                            }
                            open(player, shop.id, OpenMode.REPLACE, page)
                        }
                    }
                    return
                }

                ButtonActionType.COMMAND -> {
                    if (action.commands.isNotEmpty()) {
                        currencyService.dispatchCommands(
                            player,
                            action.commands,
                            mapOf("shop_id" to shop.id, "shop_name" to shop.settings.shopName),
                            action.executeAs
                        )
                    }
                }

                ButtonActionType.BACK -> {
                    val previous = popNavigationBack(player.uniqueId)
                    if (previous == null) {
                        navigationStacks.remove(player.uniqueId)
                        closeInventory(player)
                        return
                    }
                    reopenState(player, previous, OpenMode.REPLACE)
                    return
                }

                ButtonActionType.NEXT_PAGE -> {
                    if (page + 1 < totalPages) {
                        open(player, shop.id, OpenMode.REPLACE, page + 1)
                    }
                    return
                }

                ButtonActionType.PREVIOUS_PAGE -> {
                    if (page > 0) {
                        open(player, shop.id, OpenMode.REPLACE, page - 1)
                    }
                    return
                }

                ButtonActionType.NONE -> Unit
            }
        }
    }

    private fun openConfirm(
        player: Player,
        returnState: PageState,
        shopId: String,
        entry: ShopEntry,
        side: TradeSide,
        multiplier: Int
    ) {
        if (multiplier <= 0) {
            return
        }
        val shop = shopService.findShop(shopId) ?: return
        val preview = shopService.buildIcon(player, shop, entry)
        val section = plugin.config.getConfigurationSection("confirm-menu")
        val title = applyText(player, section?.getString("title") ?: "&6确认交易")
        val confirmSlot = section?.getInt("confirm-slot", 11) ?: 11
        val previewSlot = section?.getInt("preview-slot", 13) ?: 13
        val cancelSlot = section?.getInt("cancel-slot", 15) ?: 15
        val fillerItem = loadConfiguredItem(section?.getConfigurationSection("filler-item"))
        val confirmItem = loadConfiguredItem(section?.getConfigurationSection("confirm-item"))
            ?: ConfiguredItem(material = "LIME_STAINED_GLASS_PANE", name = "&a确认")
        val cancelItem = loadConfiguredItem(section?.getConfigurationSection("cancel-item"))
            ?: ConfiguredItem(material = "RED_STAINED_GLASS_PANE", name = "&c取消")

        val replacements = shopService.renderTradeReplacements(player, shop, entry, side, multiplier) + mapOf(
            "confirm_side" to confirmSideText(side),
            "confirm_times" to multiplier.toString()
        )

        val holder = ConfirmInventoryHolder(
            shopId = shopId,
            entryId = entry.id,
            side = side,
            multiplier = multiplier,
            confirmSlot = confirmSlot,
            cancelSlot = cancelSlot,
            returnState = returnState
        )
        val inventory = createInventory(holder, 27, title)

        if (fillerItem != null) {
            (0 until inventory.size).forEach { slotIndex ->
                if (slotIndex != confirmSlot && slotIndex != previewSlot && slotIndex != cancelSlot) {
                    inventory.setItem(slotIndex, itemService.buildItem(fillerItem, player, replacements))
                }
            }
        }
        inventory.setItem(previewSlot, buildConfirmPreviewItem(player, preview, shop, entry, side, multiplier, replacements))
        inventory.setItem(confirmSlot, itemService.buildItem(confirmItem, player, replacements))
        inventory.setItem(cancelSlot, itemService.buildItem(cancelItem, player, replacements))

        openInventory(player, inventory)
    }

    private fun buildShopDisplayItem(player: Player, shop: ShopDefinition, entry: ShopEntry): ItemStack {
        return buildShopDisplayItem(
            player = player,
            entrySnapshot = shopService.createEntryRenderSnapshot(player, shop, entry),
            favorite = favoriteService.contains(player.uniqueId, shop.id, entry.id)
        )
    }

    private fun buildShopDisplayItem(
        player: Player,
        entrySnapshot: ShopService.ShopEntryRenderSnapshot,
        favorite: Boolean
    ): ItemStack {
        val item = shopService.buildIcon(player, entrySnapshot).clone()
        val meta = item.itemMeta ?: return item
        val lore = meta.lore.orEmpty().toMutableList()
        if (lore.isNotEmpty()) {
            lore += ""
        }
        lore += applyText(
            player,
            if (favorite) configText("favorites-menu.unfavorite-line", "&6Q键: 取消收藏")
            else configText("favorites-menu.favorite-line", "&eQ键: 加入收藏")
        )
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun applyShopRenderSnapshot(
        player: Player,
        shop: ShopDefinition,
        holder: ShopInventoryHolder,
        inventory: Inventory,
        snapshot: ShopService.ShopPageRenderSnapshot,
        force: Boolean
    ) {
        holder.page = snapshot.page
        val favoriteKeys = favoriteKeys(player.uniqueId)
        val usedItemSlots = mutableSetOf<Int>()

        holder.slotToEntryId.clear()
        snapshot.pageEntries.forEachIndexed { index, entry ->
            val slotIndex = snapshot.itemSlots.getOrNull(index) ?: return@forEachIndexed
            if (slotIndex !in 0 until inventory.size) {
                return@forEachIndexed
            }
            val entrySnapshot = snapshot.entrySnapshot(entry.id) ?: return@forEachIndexed
            val favorite = favoriteKey(shop.id, entry.id) in favoriteKeys
            val signature = entryRenderSignature(entrySnapshot, favorite)
            holder.slotToEntryId[slotIndex] = entry.id
            usedItemSlots += slotIndex
            if (force || holder.itemRenderSignatures[slotIndex] != signature || inventory.getItem(slotIndex) == null) {
                inventory.setItem(slotIndex, buildShopDisplayItem(player, entrySnapshot, favorite))
                holder.itemRenderSignatures[slotIndex] = signature
            }
        }

        snapshot.itemSlots.forEach { slotIndex ->
            if (slotIndex !in usedItemSlots && slotIndex in 0 until inventory.size) {
                if (force || holder.itemRenderSignatures.remove(slotIndex) != null || inventory.getItem(slotIndex) != null) {
                    inventory.setItem(slotIndex, null)
                }
                holder.slotToEntryId.remove(slotIndex)
            }
        }

        holder.slotToButton.clear()
        var slotIndex = 0
        shop.layout.pattern.forEach { row ->
            row.forEach { symbol ->
                val button = shop.layout.buttons[symbol]
                if (button != null && slotIndex in 0 until inventory.size) {
                    holder.slotToButton[slotIndex] = button
                    val signature = buttonRenderSignature(button, snapshot.layoutSignature)
                    if (force || holder.buttonRenderSignatures[slotIndex] != signature || inventory.getItem(slotIndex) == null) {
                        inventory.setItem(slotIndex, itemService.buildItem(button.displayItem, player, snapshot.layoutReplacements))
                        holder.buttonRenderSignatures[slotIndex] = signature
                    }
                } else {
                    holder.buttonRenderSignatures.remove(slotIndex)
                }
                slotIndex++
            }
        }
    }

    private fun favoriteKeys(playerId: UUID): Set<String> {
        return favoriteService.entries(playerId)
            .mapTo(linkedSetOf()) { favoriteKey(it.shopId, it.entryId) }
    }

    private fun favoriteKey(shopId: String, entryId: String): String {
        return "${shopId.lowercase()}:${entryId.lowercase()}"
    }

    private fun entryRenderSignature(snapshot: ShopService.ShopEntryRenderSnapshot, favorite: Boolean): Int {
        return 31 * snapshot.renderSignature + favorite.hashCode()
    }

    private fun buttonRenderSignature(button: LayoutButtonDefinition, layoutSignature: Int): Int {
        return 31 * button.key.hashCode() + layoutSignature
    }

    private fun handleFavoriteToggleClick(player: Player, click: ClickType, shop: ShopDefinition, entry: ShopEntry): Boolean {
        if (click != ClickType.DROP && click != ClickType.CONTROL_DROP && click != ClickType.MIDDLE) {
            return false
        }
        val added = favoriteService.toggle(player.uniqueId, shop.id, entry.id)
        val itemName = shopService.buildIcon(player, shop, entry).itemMeta?.displayName ?: entry.id
        val message = configText(
            if (added) "favorites-menu.added-message" else "favorites-menu.removed-message",
            if (added) "&a已收藏: &f{item}" else "&e已取消收藏: &f{item}"
        ).replace("{item}", itemName)
        shopService.messageService().sendRaw(player, message, player = player)
        return true
    }

    private fun updateNavigation(playerId: UUID, state: PageState, mode: OpenMode) {
        val stack = navigationStacks.computeIfAbsent(playerId) { ArrayDeque() }
        synchronized(stack) {
            when (mode) {
                OpenMode.OPEN -> {
                    stack.clear()
                    stack.addLast(state)
                }

                OpenMode.PUSH -> stack.addLast(state)
                OpenMode.REPLACE -> {
                    if (stack.isEmpty()) {
                        stack.addLast(state)
                    } else {
                        stack.removeLast()
                        stack.addLast(state)
                    }
                }
            }
        }
    }

    private fun hasPreviousNavigation(playerId: UUID): Boolean {
        val stack = navigationStacks[playerId] ?: return false
        return synchronized(stack) {
            stack.size > 1
        }
    }

    private fun popNavigationBack(playerId: UUID): PageState? {
        val stack = navigationStacks[playerId] ?: return null
        return synchronized(stack) {
            if (stack.size <= 1) {
                null
            } else {
                stack.removeLast()
                stack.last()
            }
        }
    }

    private fun reopenState(player: Player, state: PageState, mode: OpenMode) {
        when (state) {
            is PageState.ShopPage -> open(player, state.shopId, mode, state.page)
            is PageState.FavoritesPage -> openFavorites(player, mode, state.page)
        }
    }

    private fun refreshCurrentView(player: Player) {
        val holder = player.openInventory.topInventory.holder as? YmShopInventoryHolder ?: return
        when (holder) {
            is ShopInventoryHolder -> refreshShopInventory(player, holder)
            is FavoritesInventoryHolder -> Unit
            is ConfirmInventoryHolder -> refreshConfirmInventory(player, holder)
        }
    }

    private fun refreshShopInventory(player: Player, holder: ShopInventoryHolder) {
        val inventory = holder.getInventory()
        val shop = shopService.findShop(holder.shopId) ?: return
        val snapshot = shopService.createPageRenderSnapshot(player, shop, holder.page)
        applyShopRenderSnapshot(player, shop, holder, inventory, snapshot, force = false)
    }

    private fun refreshConfirmInventory(player: Player, holder: ConfirmInventoryHolder) {
        val inventory = holder.getInventory()
        val shop = shopService.findShop(holder.shopId) ?: return
        val entry = shop.entries.firstOrNull { it.id.equals(holder.entryId, ignoreCase = true) } ?: return
        val preview = shopService.buildIcon(player, shop, entry)
        val replacements = shopService.renderTradeReplacements(player, shop, entry, holder.side, holder.multiplier) + mapOf(
            "confirm_side" to confirmSideText(holder.side),
            "confirm_times" to holder.multiplier.toString()
        )
        val section = plugin.config.getConfigurationSection("confirm-menu")
        val confirmItem = loadConfiguredItem(section?.getConfigurationSection("confirm-item"))
            ?: ConfiguredItem(material = "LIME_STAINED_GLASS_PANE", name = "&a\u786e\u8ba4")
        val cancelItem = loadConfiguredItem(section?.getConfigurationSection("cancel-item"))
            ?: ConfiguredItem(material = "RED_STAINED_GLASS_PANE", name = "&c\u53d6\u6d88")

        inventory.setItem(
            holder.confirmSlot,
            itemService.buildItem(confirmItem, player, replacements)
        )
        inventory.setItem(
            holder.cancelSlot,
            itemService.buildItem(cancelItem, player, replacements)
        )
        inventory.setItem(
            section?.getInt("preview-slot", 13) ?: 13,
            buildConfirmPreviewItem(player, preview, shop, entry, holder.side, holder.multiplier, replacements)
        )
    }

    private fun playOpenActions(player: Player, shop: ShopDefinition) {
        shop.layout.openActions.forEach { action ->
            when (action.type) {
                OpenActionType.SOUND -> resolveSound(action.sound)?.let { sound ->
                    player.playSound(player.location, sound, 1f, 1f)
                }
            }
        }
    }

    private fun fillFavoritesBackground(player: Player, inventory: Inventory) {
        val filler = itemService.buildItem(
            configItem("favorites-menu.background-item", ConfiguredItem(material = "BLACK_STAINED_GLASS_PANE", name = "&7 ")),
            player
        )
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, filler)
        }
    }

    private fun favoriteItemSlots(): List<Int> {
        return listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        )
    }

    private fun pageButtonItem(player: Player, page: Int, totalPages: Int, previous: Boolean, enabled: Boolean): ItemStack {
        val material = if (enabled) {
            if (previous) "SPECTRAL_ARROW" else "ARROW"
        } else {
            "GRAY_DYE"
        }
        val sectionPath = when {
            previous && enabled -> "favorites-menu.previous-enabled-item"
            previous -> "favorites-menu.previous-disabled-item"
            enabled -> "favorites-menu.next-enabled-item"
            else -> "favorites-menu.next-disabled-item"
        }
        val fallbackName = when {
            previous && enabled -> "&e上一页"
            previous -> "&7已经是第一页"
            enabled -> "&e下一页"
            else -> "&7已经是最后一页"
        }
        val replacements = mapOf(
            "current_page" to (page + 1).toString(),
            "total_pages" to totalPages.toString()
        )
        return itemService.buildItem(
            configItem(
                sectionPath,
                ConfiguredItem(
                    material = material,
                    name = fallbackName,
                    lore = listOf("&7当前页: &f{current_page}/{total_pages}")
                )
            ),
            player,
            replacements
        )
    }

    private fun backOrCloseItem(player: Player, hasPrevious: Boolean): ItemStack {
        return itemService.buildItem(
            configItem(
                if (hasPrevious) "favorites-menu.back-item" else "favorites-menu.close-item",
                ConfiguredItem(
                    material = if (hasPrevious) "ARROW" else "BARRIER",
                    name = if (hasPrevious) "&c返回上一页" else "&c关闭菜单"
                )
            ),
            player
        )
    }

    private fun openInventory(player: Player, inventory: Inventory) {
        markTransition(player)
        player.openInventory(inventory)
    }

    private fun closeInventory(player: Player) {
        markTransition(player)
        player.closeInventory()
    }

    private fun createInventory(holder: YmShopInventoryHolder, size: Int, title: String): Inventory {
        val inventory = Bukkit.createInventory(holder, size, title)
        holder.bind(inventory)
        return inventory
    }

    private fun resolveTradeAction(shop: ShopDefinition, entry: ShopEntry, click: ClickType, player: Player): TradeAction? {
        val buyAmounts = shop.settings.tradeAmounts.buy
        val sellAmounts = shop.settings.tradeAmounts.sell
        return when (click) {
            ClickType.LEFT -> when {
                entry.supportsBuy -> resolveTradeAction(buyAmounts.left, TradeSide.BUY, shop, entry, player)
                entry.supportsSell -> resolveTradeAction(sellAmounts.left, TradeSide.SELL, shop, entry, player)
                else -> null
            }

            ClickType.SHIFT_LEFT -> when {
                entry.supportsBuy -> resolveTradeAction(buyAmounts.shiftLeft, TradeSide.BUY, shop, entry, player)
                entry.supportsSell -> resolveTradeAction(sellAmounts.shiftLeft, TradeSide.SELL, shop, entry, player)
                else -> null
            }

            ClickType.RIGHT -> when {
                entry.supportsSell -> resolveTradeAction(sellAmounts.right, TradeSide.SELL, shop, entry, player)
                entry.supportsBuy -> resolveTradeAction(buyAmounts.right, TradeSide.BUY, shop, entry, player)
                else -> null
            }

            ClickType.SHIFT_RIGHT -> when {
                entry.supportsSell -> resolveTradeAction(sellAmounts.shiftRight, TradeSide.SELL, shop, entry, player)
                entry.supportsBuy -> resolveTradeAction(buyAmounts.shiftRight, TradeSide.BUY, shop, entry, player)
                else -> null
            }

            else -> null
        }
    }

    private fun resolveTradeAction(
        configuredAmount: TradeClickAmountDefinition,
        side: TradeSide,
        shop: ShopDefinition,
        entry: ShopEntry,
        player: Player
    ): TradeAction? {
        return when (configuredAmount.mode) {
            TradeClickAmountMode.DISABLED -> null
            TradeClickAmountMode.FIXED -> TradeAction(side, configuredAmount.amount.coerceAtLeast(1))
            TradeClickAmountMode.ALL -> {
                if (side != TradeSide.SELL) {
                    null
                } else {
                    val multiplier = shopService.maxSellAllMultiplier(player, shop.id, entry.id)
                    if (multiplier > 0) TradeAction(side, multiplier) else null
                }
            }
        }
    }

    private fun loadConfiguredItem(section: ConfigurationSection?): ConfiguredItem? {
        if (section == null) {
            return null
        }
        return ConfiguredItem(
            material = section.getString("material") ?: return null,
            amount = section.getInt("amount", 1),
            name = section.getString("name"),
            lore = section.getStringList("lore"),
            customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else null,
            itemModel = section.getString("item-model"),
            glow = section.getBoolean("glow", false),
            unbreakable = section.getBoolean("unbreakable", false),
            enchants = section.getConfigurationSection("enchants")
                ?.getKeys(false)
                ?.associateWith { key -> section.getInt("enchants.$key") }
                .orEmpty(),
            flags = section.getStringList("flags")
        )
    }

    private fun configText(path: String, fallback: String): String {
        return plugin.config.getString(path)?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun configTextList(path: String, fallback: List<String>): List<String> {
        return if (plugin.config.isList(path)) plugin.config.getStringList(path) else fallback
    }

    private fun configItem(path: String, fallback: ConfiguredItem): ConfiguredItem {
        return loadConfiguredItem(plugin.config.getConfigurationSection(path)) ?: fallback
    }

    private fun confirmSideText(side: TradeSide): String {
        return when (side) {
            TradeSide.BUY -> configText("confirm-menu.side-buy", "购买")
            TradeSide.SELL -> configText("confirm-menu.side-sell", "出售")
        }
    }

    private fun resolveSound(raw: String): Sound? {
        val normalized = raw.trim().lowercase()
        return sequenceOf(
            NamespacedKey.fromString(normalized),
            NamespacedKey.minecraft(normalized)
        )
            .filterNotNull()
            .mapNotNull { key -> Registry.SOUNDS.get(key) }
            .firstOrNull()
    }

    private fun buildConfirmPreviewItem(
        player: Player,
        baseItem: ItemStack,
        shop: ShopDefinition,
        entry: ShopEntry,
        side: TradeSide,
        multiplier: Int,
        replacements: Map<String, String>
    ): ItemStack {
        val preview = baseItem.clone()
        val meta = preview.itemMeta ?: return preview
        val lore = meta.lore.orEmpty().toMutableList()
        val totalAmount = replacements["confirm_amount"]?.toIntOrNull() ?: 0
        val totalPrice = replacements["confirm_price"].orEmpty()
        val currency = replacements["currency"].orEmpty()
        val priceLabel = if (side == TradeSide.BUY) {
            configText("confirm-menu.estimate-buy-label", "消费")
        } else {
            configText("confirm-menu.estimate-sell-label", "收益")
        }
        val unitAmount = if (side == TradeSide.SELL && multiplier > 1) {
            (totalAmount / multiplier).coerceAtLeast(1)
        } else {
            0
        }
        val sellableAmount = if (side == TradeSide.SELL && multiplier > 1) {
            shopService.maxSellAllMultiplier(player, shop.id, entry.id) * unitAmount
        } else {
            0
        }
        val previewReplacements = replacements + mapOf(
            "confirm_price_label" to priceLabel,
            "confirm_amount" to totalAmount.toString(),
            "confirm_price" to totalPrice,
            "currency" to currency,
            "sellable_amount" to sellableAmount.toString()
        )

        if (lore.isNotEmpty()) {
            lore += ""
        }
        configTextList(
            "confirm-menu.preview-lore",
            listOf(
                "&8确认预览",
                "&7本次操作: &f{confirm_side}",
                "&7本次数量: &f{confirm_amount}",
                "&7预计{confirm_price_label}: &6{confirm_price} {currency}"
            )
        ).forEach { line ->
            lore += applyText(player, line, previewReplacements)
        }

        if (side == TradeSide.SELL && multiplier > 1) {
            lore += applyText(
                player,
                configText("confirm-menu.preview-sellable-line", "&7当前可售: &f{sellable_amount}"),
                previewReplacements
            )
        }

        meta.lore = lore
        preview.itemMeta = meta
        return preview
    }

    private fun tryAcquireTradeCooldown(playerId: UUID): Boolean {
        val cooldownMillis = plugin.config.getLong("trade-click-cooldown-ticks", 4L).coerceAtLeast(0L) * 50L
        if (cooldownMillis <= 0L) {
            lastTradeActionAt[playerId] = System.currentTimeMillis()
            return true
        }

        val now = System.currentTimeMillis()
        var acquired = false
        lastTradeActionAt.compute(playerId) { _, previous ->
            if (previous != null && now - previous < cooldownMillis) {
                previous
            } else {
                acquired = true
                now
            }
        }
        return acquired
    }

    private fun markTransition(player: Player) {
        val playerId = player.uniqueId
        transitioningPlayers += playerId
        val scheduled = platformExecutor.runForPlayerLater(player, 1L) {
            transitioningPlayers.remove(playerId)
        }
        if (!scheduled) {
            transitioningPlayers.remove(playerId)
        }
    }

    private data class FavoriteDisplay(
        val shop: ShopDefinition,
        val entry: ShopEntry
    )

    private data class TradeAction(
        val side: TradeSide,
        val multiplier: Int
    )

    private sealed class PageState {
        data class ShopPage(val shopId: String, val page: Int) : PageState()
        data class FavoritesPage(val page: Int) : PageState()
    }

    private sealed class YmShopInventoryHolder : InventoryHolder {
        private lateinit var backingInventory: Inventory

        fun bind(inventory: Inventory) {
            backingInventory = inventory
        }

        override fun getInventory(): Inventory = backingInventory
    }

    private class ShopInventoryHolder(
        val shopId: String,
        var page: Int,
        val slotToEntryId: MutableMap<Int, String>,
        val slotToButton: MutableMap<Int, LayoutButtonDefinition>,
        val itemRenderSignatures: MutableMap<Int, Int> = mutableMapOf(),
        val buttonRenderSignatures: MutableMap<Int, Int> = mutableMapOf()
    ) : YmShopInventoryHolder()

    private class FavoritesInventoryHolder(
        val page: Int,
        val slotToFavorite: Map<Int, FavoriteDisplay>
    ) : YmShopInventoryHolder()

    private class ConfirmInventoryHolder(
        val shopId: String,
        val entryId: String,
        val side: TradeSide,
        val multiplier: Int,
        val confirmSlot: Int,
        val cancelSlot: Int,
        val returnState: PageState
    ) : YmShopInventoryHolder()

    enum class OpenMode {
        OPEN,
        PUSH,
        REPLACE
    }

    companion object {
        private const val LIVE_REFRESH_PERIOD_TICKS = 20L
    }
}
