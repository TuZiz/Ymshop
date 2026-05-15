package ym.ymshop.service

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.ButtonActionDefinition
import ym.ymshop.model.ButtonActionType
import ym.ymshop.model.CommandExecutionType
import ym.ymshop.model.ConfiguredItem
import ym.ymshop.model.CurrencyDefinition
import ym.ymshop.model.CurrencyType
import ym.ymshop.model.EntryLimits
import ym.ymshop.model.GlobalConfig
import ym.ymshop.model.ItemTemplateDefinition
import ym.ymshop.model.LayoutButtonDefinition
import ym.ymshop.model.LayoutDefinition
import ym.ymshop.model.OpenActionDefinition
import ym.ymshop.model.OpenActionType
import ym.ymshop.model.PermissionLimit
import ym.ymshop.model.RenderTextConfig
import ym.ymshop.model.ResetMode
import ym.ymshop.model.ResetPolicy
import ym.ymshop.model.RewardDefinition
import ym.ymshop.model.RewardType
import ym.ymshop.model.ShopDefinition
import ym.ymshop.model.ShopEntry
import ym.ymshop.model.ShopSettings
import ym.ymshop.model.ShopTradeAmountSettings
import ym.ymshop.model.TradeClickAmountDefinition
import ym.ymshop.model.TradeClickAmountMode
import ym.ymshop.model.TradeClickAmounts
import ym.ymshop.model.TradeMode
import java.io.File
import java.time.ZoneId

class ConfigLoader(private val plugin: JavaPlugin) {

    fun loadGlobalConfig(): GlobalConfig {
        plugin.reloadConfig()
        val currenciesSection = plugin.config.getConfigurationSection("currencies")
        val currencies = currenciesSection?.getKeys(false)?.associateWith { id ->
            validateId(id, "config.yml -> currencies.$id")
            val section = requireNotNull(currenciesSection.getConfigurationSection(id)) {
                "config.yml -> currencies.$id -> missing section"
            }
            loadCurrency(id, section, "config.yml -> currencies.$id")
        }.orEmpty()

        val messages = plugin.config.getConfigurationSection("messages")
            ?.getKeys(false)
            ?.associateWith { plugin.config.getString("messages.$it", "").orEmpty() }
            .orEmpty()

        val templateSection = plugin.config.getConfigurationSection("item-template")
        val buyTemplate = loadItemTemplate(templateSection, "buy")
        val sellTemplate = loadItemTemplate(templateSection, "sell")
        val dualTemplate = loadItemTemplate(
            root = templateSection,
            key = "dual",
            fallbackName = buyTemplate.defaultName,
            fallbackDefaultLore = buyTemplate.defaultLore,
            fallbackAppendLore = buyTemplate.appendLore + sellTemplate.appendLore
        )

        val resetZoneId = loadResetZoneId()
        val renderText = loadRenderText(plugin.config.getConfigurationSection("render-text"))

        return GlobalConfig(currencies, messages, buyTemplate, sellTemplate, dualTemplate, renderText, resetZoneId)
    }

    fun loadLayouts(): Map<String, LayoutDefinition> {
        val folder = plugin.dataFolder.resolve("layouts")
        if (!folder.exists()) {
            folder.mkdirs()
        }

        return folder.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.associate { file ->
                val layout = runCatching { loadLayout(file) }
                    .getOrElse { ex -> throw IllegalArgumentException("layouts/${file.name} -> ${ex.message}", ex) }
                layout.id.lowercase() to layout
            }
            .orEmpty()
    }

    fun loadShops(layouts: Map<String, LayoutDefinition>): List<ShopDefinition> {
        val folder = plugin.dataFolder.resolve("shops")
        if (!folder.exists()) {
            folder.mkdirs()
        }

        return folder.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { file ->
                runCatching { loadShop(file, layouts) }
                    .getOrElse { ex -> throw IllegalArgumentException("shops/${file.name} -> ${ex.message}", ex) }
            }
            .orEmpty()
    }

    private fun loadLayout(file: File): LayoutDefinition {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val layoutId = file.nameWithoutExtension
        val source = "layouts/${file.name}"
        validateId(layoutId, "$source -> layout id")
        val size = yaml.getInt("size", 54)
        require(size in 9..54 && size % 9 == 0) { "size must be a multiple of 9 between 9 and 54" }
        val rows = size / 9
        val pattern = yaml.getStringList("layout")
        require(pattern.size == rows) { "layout must contain exactly $rows rows" }
        pattern.forEachIndexed { index, line ->
            require(line.length == 9) { "layout row ${index + 1} must be exactly 9 chars" }
        }

        val buttons = yaml.getConfigurationSection("buttons")
            ?.getKeys(false)
            ?.associate { key ->
                require(key.length == 1) { "buttons.$key key must be a single char" }
                val charKey = key[0]
                val buttonPath = "$source -> buttons.$key"
                val section = requireNotNull(yaml.getConfigurationSection("buttons.$key")) { "$buttonPath missing section" }
                charKey to LayoutButtonDefinition(
                    key = charKey,
                    displayItem = loadItem(
                        requireNotNull(section.getConfigurationSection("display-item")) { "$buttonPath -> display-item missing" },
                        "$buttonPath -> display-item"
                    ),
                    actions = loadButtonActions(section.getConfigurationSection("actions"), "$buttonPath -> actions")
                )
            }
            .orEmpty()

        return LayoutDefinition(
            id = layoutId,
            titleTemplate = yaml.getString("title", "{shop-name}").orEmpty(),
            size = size,
            rows = rows,
            pattern = pattern,
            buttons = buttons,
            openActions = loadOpenActions(yaml.getConfigurationSection("open-actions"), "$source -> open-actions"),
            sourceFile = source
        )
    }

    private fun loadShop(file: File, layouts: Map<String, LayoutDefinition>): ShopDefinition {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val shopId = file.nameWithoutExtension
        val source = "shops/${file.name}"
        validateId(shopId, "$source -> shop id")
        val settingsSection = yaml.getConfigurationSection("settings")
            ?: error("$source -> settings missing")
        val menuId = settingsSection.getString("menu")
            ?: error("$source -> settings.menu missing")
        validateId(menuId, "$source -> settings.menu")
        val layout = layouts[menuId.lowercase()]
            ?: error("$source -> settings.menu references missing layout $menuId")

        val itemsSection = yaml.getConfigurationSection("items")
            ?: error("$source -> items missing")
        val entries = itemsSection.getKeys(false).sorted().mapIndexed { index, key ->
            val itemPath = "$source -> items.$key"
            val section = requireNotNull(itemsSection.getConfigurationSection(key)) { "$itemPath missing section" }
            loadEntry(defaultEntrySymbol(index), key, section, itemPath)
        }

        return ShopDefinition(
            id = shopId,
            settings = ShopSettings(
                menuId = menuId,
                shopName = settingsSection.getString("shop-name", shopId).orEmpty(),
                buyMore = settingsSection.getBoolean("buy-more", false),
                hideMessage = settingsSection.getBoolean("hide-message", false),
                permission = settingsSection.getString("permission")?.takeIf { it.isNotBlank() },
                tradeAmounts = loadTradeAmounts(settingsSection)
            ),
            layout = layout,
            entries = entries,
            sourceFile = source
        )
    }

    private fun loadEntry(symbol: Char, rawKey: String, section: ConfigurationSection, path: String): ShopEntry {
        val id = section.getString("id")?.takeIf { it.isNotBlank() } ?: rawKey
        validateId(id, "$path -> id")
        val explicitMode = section.getString("mode")?.takeIf { it.isNotBlank() }?.let {
            runCatching { TradeMode.valueOf(it.uppercase()) }
                .getOrElse { throw IllegalArgumentException("$path -> mode invalid: $it") }
        }
        val buyPrice = resolvePrice(section, "buy", explicitMode)
        val sellPrice = resolvePrice(section, "sell", explicitMode)
        val mode = resolveMode(explicitMode, buyPrice, sellPrice)

        val rewardSection = section.getConfigurationSection("reward")
        val firstProduct = loadCompatProduct(section.getConfigurationSection("products"), "$path -> products")
        val iconSection = section.getConfigurationSection("icon")
        val icon = if (iconSection != null) loadItem(iconSection, "$path -> icon") else compatIcon(section, firstProduct, path)
        val tradeItem = section.getConfigurationSection("trade-item")?.let { loadItem(it, "$path -> trade-item") } ?: firstProduct

        val rewardTypeName = rewardSection?.getString("type")
            ?: if (buyPrice != null) "ICON_ITEM" else "NONE"
        val rewardType = runCatching { RewardType.valueOf(rewardTypeName.uppercase()) }
            .getOrElse { throw IllegalArgumentException("$path -> reward.type invalid: $rewardTypeName") }

        val limitsSection = section.getConfigurationSection("limits")
        val buyCompatLimits = compatDirectionalLimits(section, "buy")
        val sellCompatLimits = compatDirectionalLimits(section, "sell")
        val currencyId = section.getString("currency")
            ?: section.getString("currency-id")
            ?: error("$path -> missing currency")
        validateId(currencyId, "$path -> currency")

        return ShopEntry(
            id = id,
            symbol = symbol,
            mode = mode,
            currencyId = currencyId,
            buyPrice = buyPrice,
            sellPrice = sellPrice,
            icon = icon,
            tradeItem = tradeItem,
            reward = RewardDefinition(
                type = rewardType,
                item = rewardSection?.getConfigurationSection("item")?.let { loadItem(it, "$path -> reward.item") },
                commands = rewardSection?.getStringList("commands").orEmpty()
            ),
            limits = EntryLimits(
                player = readOptionalLong(limitsSection, "player"),
                global = readOptionalLong(limitsSection, "global"),
                buy = readOptionalLong(limitsSection, "buy") ?: buyCompatLimits.player,
                sell = readOptionalLong(limitsSection, "sell") ?: sellCompatLimits.player,
                buyGlobal = readOptionalLong(limitsSection, "buy-global") ?: buyCompatLimits.global,
                sellGlobal = readOptionalLong(limitsSection, "sell-global") ?: sellCompatLimits.global
            ),
            buyResetPolicy = loadResetPolicy(section, "buy", path),
            sellResetPolicy = loadResetPolicy(section, "sell", path),
            buyPermissionLimits = compatPermissionLimits(section, "buy"),
            sellPermissionLimits = compatPermissionLimits(section, "sell"),
            successCommands = section.getStringList("success-commands")
        )
    }

    private fun loadCurrency(id: String, section: ConfigurationSection, path: String): CurrencyDefinition {
        validateId(id, "$path -> id")
        val typeName = section.getString("type", "PLAYERPOINTS") ?: "PLAYERPOINTS"
        val type = runCatching { CurrencyType.valueOf(typeName.uppercase()) }
            .getOrElse { throw IllegalArgumentException("$path -> type invalid: $typeName") }
        return CurrencyDefinition(
            id = id,
            type = type,
            displayName = section.getString("display-name", id).orEmpty(),
            balancePlaceholder = section.getString("balance-placeholder"),
            takeCommands = section.getStringList("take-commands"),
            giveCommands = section.getStringList("give-commands")
        )
    }

    private fun loadItem(section: ConfigurationSection, path: String): ConfiguredItem {
        val material = section.getString("material") ?: error("$path -> material missing")
        require(Material.matchMaterial(material) != null) { "$path -> material invalid: $material" }
        val amount = section.getInt("amount", 1)
        require(amount > 0) { "$path -> amount must be > 0" }
        val customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else null
        customModelData?.let { require(it >= 0) { "$path -> custom-model-data must be >= 0" } }
        val enchants = section.getConfigurationSection("enchants")
            ?.getKeys(false)
            ?.associateWith { key ->
                val level = section.getInt("enchants.$key")
                require(level > 0) { "$path -> enchants.$key must be > 0" }
                level
            }
            .orEmpty()
        return ConfiguredItem(
            material = material,
            amount = amount,
            name = section.getString("name") ?: section.getString("display-name"),
            lore = section.getStringList("lore"),
            customModelData = customModelData,
            itemModel = section.getString("item-model"),
            glow = section.getBoolean("glow", false),
            unbreakable = section.getBoolean("unbreakable", false),
            enchants = enchants,
            flags = section.getStringList("flags")
        )
    }

    private fun compatIcon(section: ConfigurationSection, firstProduct: ConfiguredItem?, path: String): ConfiguredItem {
        val base = firstProduct ?: error("$path -> missing icon/products")
        val customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else base.customModelData
        customModelData?.let { require(it >= 0) { "$path -> custom-model-data must be >= 0" } }
        return base.copy(
            name = section.getString("display-name") ?: base.name,
            lore = section.getStringList("lore").ifEmpty { base.lore },
            customModelData = customModelData,
            itemModel = section.getString("item-model") ?: base.itemModel
        )
    }

    private fun loadCompatProduct(section: ConfigurationSection?, path: String): ConfiguredItem? {
        if (section == null) {
            return null
        }
        val firstKey = section.getKeys(false).sorted().firstOrNull() ?: return null
        val child = section.getConfigurationSection(firstKey) ?: return null
        return loadItem(child, "$path.$firstKey")
    }

    private fun loadResetPolicy(section: ConfigurationSection, sideKey: String, path: String): ResetPolicy {
        val modeName = section.getString("${sideKey}-times-reset-mode") ?: return ResetPolicy()
        val mode = runCatching { ResetMode.valueOf(modeName.uppercase()) }
            .getOrElse { throw IllegalArgumentException("$path -> ${sideKey}-times-reset-mode invalid: $modeName") }
        return ResetPolicy(
            mode = mode,
            time = section.getString("${sideKey}-times-reset-time"),
            day = section.getString("${sideKey}-times-reset-day"),
            interval = section.getString("${sideKey}-times-reset-interval")
        )
    }

    private fun resolvePrice(section: ConfigurationSection, sideKey: String, explicitMode: TradeMode?): Long? {
        val direct = readPriceValue(section, "$sideKey-price")
        val nested = compatNestedPrice(section, "$sideKey-prices")
        val plain = readPriceValue(section, "price")
        return direct
            ?: nested
            ?: when (explicitMode) {
                TradeMode.BUY -> plain.takeIf { sideKey == "buy" }
                TradeMode.SELL -> plain.takeIf { sideKey == "sell" }
                TradeMode.BOTH -> plain
                null -> null
            }
    }

    private fun resolveMode(explicitMode: TradeMode?, buyPrice: Long?, sellPrice: Long?): TradeMode {
        return explicitMode ?: when {
            buyPrice != null && sellPrice != null -> TradeMode.BOTH
            buyPrice != null -> TradeMode.BUY
            sellPrice != null -> TradeMode.SELL
            else -> TradeMode.SELL
        }
    }

    private fun readPriceValue(section: ConfigurationSection, path: String): Long? {
        if (!section.contains(path)) {
            return null
        }
        val raw = section.get(path) ?: return null
        val price = when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().toDoubleOrNull()?.toLong()
        }
        require(price != null) { "$path invalid price: $raw" }
        require(price >= 0) { "$path must be >= 0" }
        return price
    }

    private fun compatNestedPrice(section: ConfigurationSection, root: String): Long? {
        val container = section.getConfigurationSection(root) ?: return null
        val firstKey = container.getKeys(false).sorted().firstOrNull() ?: return null
        return readPriceValue(section, "$root.$firstKey.amount")
    }

    private fun loadButtonActions(section: ConfigurationSection?, path: String): List<ButtonActionDefinition> {
        if (section == null) {
            return emptyList()
        }
        return section.getKeys(false)
            .sorted()
            .mapNotNull { key ->
                val child = section.getConfigurationSection(key) ?: return@mapNotNull null
                val typeRaw = child.getString("type", "NONE") ?: "NONE"
                val type = when (typeRaw.uppercase()) {
                    "OPEN_MENU", "OPEN_SHOP" -> ButtonActionType.OPEN_SHOP
                    "CLOSE" -> ButtonActionType.CLOSE
                    "RELOAD" -> ButtonActionType.RELOAD
                    "COMMAND" -> ButtonActionType.COMMAND
                    "BACK" -> ButtonActionType.BACK
                    "NEXT_PAGE" -> ButtonActionType.NEXT_PAGE
                    "PREVIOUS_PAGE", "PREV_PAGE" -> ButtonActionType.PREVIOUS_PAGE
                    else -> ButtonActionType.NONE
                }
                ButtonActionDefinition(
                    type = type,
                    target = child.getString("menu") ?: child.getString("shop"),
                    sound = child.getString("sound"),
                    commands = child.getStringList("commands").ifEmpty {
                        child.getString("command")?.let(::listOf).orEmpty()
                    },
                    executeAs = runCatching {
                        CommandExecutionType.valueOf((child.getString("execute-as", "CONSOLE") ?: "CONSOLE").uppercase())
                    }.getOrDefault(CommandExecutionType.CONSOLE)
                )
            }
    }

    private fun loadOpenActions(section: ConfigurationSection?, path: String): List<OpenActionDefinition> {
        if (section == null) {
            return emptyList()
        }
        return section.getKeys(false)
            .sorted()
            .mapNotNull { key ->
                val child = section.getConfigurationSection(key) ?: return@mapNotNull null
                when ((child.getString("type", "") ?: "").uppercase()) {
                    "SOUND" -> {
                        val sound = child.getString("sound")
                            ?: throw IllegalArgumentException("$path.$key -> sound missing")
                        OpenActionDefinition(OpenActionType.SOUND, sound)
                    }

                    else -> null
                }
            }
    }

    private fun readOptionalLong(section: ConfigurationSection?, path: String): Long? {
        if (section == null || !section.contains(path)) {
            return null
        }
        val value = section.getLong(path)
        require(value >= -1L) { "${section.currentPath}.$path must be >= -1" }
        return value
    }

    private fun compatDirectionalLimits(section: ConfigurationSection, modeKey: String): CompatDirectionalLimits {
        val compat = section.getConfigurationSection("${modeKey}-limits") ?: return CompatDirectionalLimits()
        return CompatDirectionalLimits(
            global = readOptionalLong(compat, "global"),
            player = readOptionalLong(compat, "default")
        )
    }

    private fun compatPermissionLimits(section: ConfigurationSection, modeKey: String): List<PermissionLimit> {
        val limitsSection = section.getConfigurationSection("${modeKey}-limits") ?: return emptyList()
        val conditionsSection = section.getConfigurationSection("${modeKey}-limits-conditions")

        return limitsSection.getKeys(false)
            .filterNot { it.equals("global", true) || it.equals("default", true) }
            .mapNotNull { key ->
                val amount = readOptionalLong(limitsSection, key) ?: return@mapNotNull null
                val conditionSection = conditionsSection?.getConfigurationSection(key) ?: return@mapNotNull null
                val permission = conditionSection.getKeys(false)
                    .sorted()
                    .firstNotNullOfOrNull { conditionKey ->
                        val child = conditionSection.getConfigurationSection(conditionKey) ?: return@firstNotNullOfOrNull null
                        if (!child.getString("type", "").equals("permission", true)) {
                            return@firstNotNullOfOrNull null
                        }
                        child.getString("permission")
                    }
                    ?: return@mapNotNull null
                PermissionLimit(permission, amount)
            }
    }

    private fun loadItemTemplate(
        root: ConfigurationSection?,
        key: String,
        fallbackName: String = root?.getString("default-name", "&f{material_name}").orEmpty(),
        fallbackDefaultLore: List<String> = root?.getStringList("default-lore").orEmpty(),
        fallbackAppendLore: List<String> = root?.getStringList("append-lore").orEmpty()
    ): ItemTemplateDefinition {
        val section = root?.getConfigurationSection(key)
        return ItemTemplateDefinition(
            defaultName = section?.getString("default-name", fallbackName).orEmpty(),
            defaultLore = section?.getStringList("default-lore") ?: fallbackDefaultLore,
            appendLore = section?.getStringList("append-lore") ?: fallbackAppendLore
        )
    }

    private fun validateId(id: String, path: String) {
        require(id.isNotBlank()) { "$path must not be empty" }
        require(ID_PATTERN.matches(id)) { "$path must match ${ID_PATTERN.pattern}" }
    }

    private fun loadRenderText(section: ConfigurationSection?): RenderTextConfig {
        val values = linkedMapOf(
            "trade-mode.buy" to "购买",
            "trade-mode.sell" to "出售",
            "limits.buy-player-line" to "&#FF7777玩家购买次数: {buy-times-player}/{buy-limit-player}",
            "limits.buy-server-line" to "&#FF7777服务器购买次数: {buy-times-server}/{buy-limit-server}",
            "limits.sell-player-line" to "&#FF7777玩家出售次数: {sell-times-player}/{sell-limit-player}",
            "limits.sell-server-line" to "&#FF7777服务器出售次数: {sell-times-server}/{sell-limit-server}",
            "blocked.buy-player-line" to "&#ff3300无法再购买更多！",
            "blocked.buy-server-line" to "&#ff3300已售罄！",
            "blocked.buy-no-money-line" to "&#ff3300余额不足！",
            "blocked.sell-player-line" to "&#ff3300无法再出售更多！",
            "blocked.sell-server-line" to "&#ff3300服务器无法再出售更多！",
            "blocked.sell-no-items-line" to "&#ff3300背包里没有可出售的物品！",
            "refresh.buy-player-line" to "&8刷新时间: {buy-refresh-player}",
            "refresh.sell-player-line" to "&8刷新时间: {sell-refresh-player}",
            "refresh.buy-dual-line" to "&8购买刷新: {buy-refresh-player}",
            "refresh.sell-dual-line" to "&8出售刷新: {sell-refresh-player}",
            "click.left" to "左键",
            "click.right" to "右键",
            "click.shift-left" to "Shift+左键",
            "click.shift-right" to "Shift+右键",
            "click.buy-action" to "购买",
            "click.sell-action" to "出售",
            "click.all-line" to "&#FF5252{click}: &#90A4AE{action}全部",
            "click.fixed-line" to "&#FF5252{click}: &#90A4AE{action} {amount} 个",
            "reset.disabled" to "未启用",
            "duration.day" to "{value}天",
            "duration.hour" to "{value}小时",
            "duration.minute" to "{value}分钟",
            "duration.second" to "{value}秒",
            "format.unlimited" to "无限",
            "summary.default-currency" to "金额"
        )
        if (section != null) {
            section.getKeys(true).forEach { path ->
                if (!section.isConfigurationSection(path)) {
                    section.getString(path)?.let { values[path] = it }
                }
            }
        }
        return RenderTextConfig(values)
    }

    private fun defaultEntrySymbol(index: Int): Char {
        val base = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return base.getOrElse(index % base.length) { '?' }
    }

    private fun loadResetZoneId(): ZoneId {
        val raw = plugin.config.getString("reset-timezone")?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ZoneId.systemDefault()
        }
        return runCatching { ZoneId.of(raw) }
            .getOrElse { throw IllegalArgumentException("config.yml -> reset-timezone invalid: $raw") }
    }

    private fun loadTradeAmounts(settingsSection: ConfigurationSection): ShopTradeAmountSettings {
        return ShopTradeAmountSettings(
            buy = TradeClickAmounts(
                left = loadTradeAmount(settingsSection, "trade-amounts.buy.left", fixedAmount(1), allowAll = false),
                right = loadTradeAmount(settingsSection, "trade-amounts.buy.right", fixedAmount(1), allowAll = false),
                shiftLeft = loadTradeAmount(settingsSection, "trade-amounts.buy.shift-left", fixedAmount(64), allowAll = false),
                shiftRight = loadTradeAmount(settingsSection, "trade-amounts.buy.shift-right", disabledAmount(), allowAll = false)
            ),
            sell = TradeClickAmounts(
                left = loadTradeAmount(settingsSection, "trade-amounts.sell.left", fixedAmount(1)),
                right = loadTradeAmount(settingsSection, "trade-amounts.sell.right", fixedAmount(1)),
                shiftLeft = loadTradeAmount(settingsSection, "trade-amounts.sell.shift-left", fixedAmount(64)),
                shiftRight = loadTradeAmount(settingsSection, "trade-amounts.sell.shift-right", allAmount())
            )
        )
    }

    private fun loadTradeAmount(
        settingsSection: ConfigurationSection,
        path: String,
        fallback: TradeClickAmountDefinition,
        allowAll: Boolean = true
    ): TradeClickAmountDefinition {
        if (!settingsSection.contains(path)) {
            return fallback
        }

        val raw = settingsSection.get(path)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            return fallback
        }

        return when (raw.lowercase()) {
            "all", "max" -> {
                require(allowAll) { "settings.$path does not support 'all'" }
                allAmount()
            }

            "none", "disabled", "off" -> disabledAmount()
            else -> {
                val amount = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("settings.$path invalid amount: $raw")
                require(amount > 0) { "settings.$path must be > 0" }
                fixedAmount(amount)
            }
        }
    }

    private fun fixedAmount(amount: Int) = TradeClickAmountDefinition(TradeClickAmountMode.FIXED, amount)

    private fun allAmount() = TradeClickAmountDefinition(TradeClickAmountMode.ALL, 1)

    private fun disabledAmount() = TradeClickAmountDefinition(TradeClickAmountMode.DISABLED, 1)

    private data class CompatDirectionalLimits(
        val global: Long? = null,
        val player: Long? = null
    )

    private companion object {
        val ID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
    }
}
