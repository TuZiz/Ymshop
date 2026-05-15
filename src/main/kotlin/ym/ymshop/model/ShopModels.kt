package ym.ymshop.model

import java.time.ZoneId
import java.time.Instant
import java.util.UUID

enum class TradeMode {
    BUY,
    SELL,
    BOTH
}

enum class TradeSide {
    BUY,
    SELL
}

enum class RewardType {
    ICON_ITEM,
    CONFIG_ITEM,
    COMMANDS,
    NONE
}

enum class CurrencyType {
    PLAYERPOINTS,
    VAULT,
    CUSTOM
}

enum class ResetMode {
    NONE,
    TIMED,
    WEEKLY,
    INTERVAL
}

data class ResetPolicy(
    val mode: ResetMode = ResetMode.NONE,
    val time: String? = null,
    val day: String? = null,
    val interval: String? = null
) {
    val enabled: Boolean
        get() = mode != ResetMode.NONE
}

data class ConfiguredItem(
    val material: String,
    val amount: Int = 1,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val customModelData: Int? = null,
    val itemModel: String? = null,
    val glow: Boolean = false,
    val unbreakable: Boolean = false,
    val enchants: Map<String, Int> = emptyMap(),
    val flags: List<String> = emptyList()
)

data class EntryLimits(
    val player: Long? = null,
    val global: Long? = null,
    val buy: Long? = null,
    val sell: Long? = null,
    val buyGlobal: Long? = null,
    val sellGlobal: Long? = null
)

data class TradeLimitRules(
    val playerLimit: Long? = null,
    val globalLimit: Long? = null,
    val buyLimit: Long? = null,
    val sellLimit: Long? = null,
    val buyGlobalLimit: Long? = null,
    val sellGlobalLimit: Long? = null,
    val buyResetPolicy: ResetPolicy = ResetPolicy(),
    val sellResetPolicy: ResetPolicy = ResetPolicy()
)

data class TradeReservation(
    val shopId: String,
    val entryId: String,
    val playerId: UUID,
    val side: TradeSide,
    val amount: Int,
    val playerBuyResetMarker: Long,
    val playerSellResetMarker: Long,
    val globalBuyResetMarker: Long,
    val globalSellResetMarker: Long,
    val reservedAt: Instant
)

data class PermissionLimit(
    val permission: String,
    val amount: Long
)

data class RewardDefinition(
    val type: RewardType,
    val item: ConfiguredItem? = null,
    val commands: List<String> = emptyList()
)

enum class ButtonActionType {
    OPEN_SHOP,
    CLOSE,
    RELOAD,
    COMMAND,
    BACK,
    NEXT_PAGE,
    PREVIOUS_PAGE,
    NONE
}

enum class OpenActionType {
    SOUND
}

enum class CommandExecutionType {
    CONSOLE,
    PLAYER
}

data class ButtonActionDefinition(
    val type: ButtonActionType,
    val target: String? = null,
    val sound: String? = null,
    val commands: List<String> = emptyList(),
    val executeAs: CommandExecutionType = CommandExecutionType.CONSOLE
)

data class OpenActionDefinition(
    val type: OpenActionType,
    val sound: String
)

data class LayoutButtonDefinition(
    val key: Char,
    val displayItem: ConfiguredItem,
    val actions: List<ButtonActionDefinition>
)

data class LayoutDefinition(
    val id: String,
    val titleTemplate: String,
    val size: Int,
    val rows: Int,
    val pattern: List<String>,
    val buttons: Map<Char, LayoutButtonDefinition>,
    val openActions: List<OpenActionDefinition>,
    val sourceFile: String
)

enum class TradeClickAmountMode {
    FIXED,
    ALL,
    DISABLED
}

data class TradeClickAmountDefinition(
    val mode: TradeClickAmountMode = TradeClickAmountMode.DISABLED,
    val amount: Int = 1
)

data class TradeClickAmounts(
    val left: TradeClickAmountDefinition = TradeClickAmountDefinition(),
    val right: TradeClickAmountDefinition = TradeClickAmountDefinition(),
    val shiftLeft: TradeClickAmountDefinition = TradeClickAmountDefinition(),
    val shiftRight: TradeClickAmountDefinition = TradeClickAmountDefinition()
)

data class ShopTradeAmountSettings(
    val buy: TradeClickAmounts,
    val sell: TradeClickAmounts
)

data class ShopSettings(
    val menuId: String,
    val shopName: String,
    val buyMore: Boolean,
    val hideMessage: Boolean,
    val permission: String?,
    val strictPersistence: Boolean,
    val tradeAmounts: ShopTradeAmountSettings
)

data class ShopEntry(
    val id: String,
    val symbol: Char,
    val mode: TradeMode,
    val currencyId: String,
    val buyPrice: Long?,
    val sellPrice: Long?,
    val icon: ConfiguredItem,
    val tradeItem: ConfiguredItem?,
    val reward: RewardDefinition,
    val limits: EntryLimits,
    val buyResetPolicy: ResetPolicy = ResetPolicy(),
    val sellResetPolicy: ResetPolicy = ResetPolicy(),
    val buyPermissionLimits: List<PermissionLimit> = emptyList(),
    val sellPermissionLimits: List<PermissionLimit> = emptyList(),
    val successCommands: List<String>
) {
    val supportsBuy: Boolean
        get() = buyPrice != null

    val supportsSell: Boolean
        get() = sellPrice != null
}

data class ShopDefinition(
    val id: String,
    val settings: ShopSettings,
    val layout: LayoutDefinition,
    val entries: List<ShopEntry>,
    val sourceFile: String
)

data class CurrencyDefinition(
    val id: String,
    val type: CurrencyType,
    val displayName: String,
    val balancePlaceholder: String?,
    val takeCommands: List<String>,
    val giveCommands: List<String>
)

data class ItemTemplateDefinition(
    val defaultName: String,
    val defaultLore: List<String>,
    val appendLore: List<String>
)

data class RenderTextConfig(
    val values: Map<String, String>
) {
    fun text(path: String, fallback: String): String {
        return values[path] ?: fallback
    }
}

data class GlobalConfig(
    val currencies: Map<String, CurrencyDefinition>,
    val messages: Map<String, String>,
    val buyItemTemplate: ItemTemplateDefinition,
    val sellItemTemplate: ItemTemplateDefinition,
    val dualItemTemplate: ItemTemplateDefinition,
    val renderText: RenderTextConfig,
    val resetZoneId: ZoneId
)

data class EntryStats(
    val playerTotal: Long,
    val playerBuy: Long,
    val playerSell: Long,
    val globalTotal: Long,
    val globalBuy: Long,
    val globalSell: Long
)

data class TradeResult(
    val success: Boolean,
    val messageKey: String,
    val replacements: Map<String, String> = emptyMap()
)
