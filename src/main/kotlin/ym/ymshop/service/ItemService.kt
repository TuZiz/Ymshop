package ym.ymshop.service

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.model.ConfiguredItem
import ym.ymshop.util.applyText
import ym.ymshop.util.legacyToAmpersand
import ym.ymshop.util.normalizeLoreControl
import ym.ymshop.util.quoteYaml

class ItemService(private val plugin: JavaPlugin) {

    companion object {
        const val HIDDEN_LORE_SENTINEL = "__YMSHOP_HIDE_LINE__"
    }

    fun buildItem(
        configuredItem: ConfiguredItem,
        player: Player? = null,
        replacements: Map<String, String> = emptyMap(),
        amountOverride: Int? = null
    ): ItemStack {
        val material = Material.matchMaterial(configuredItem.material)
            ?: error("Unknown material: ${configuredItem.material}")
        val stack = ItemStack(material, amountOverride ?: configuredItem.amount)
        val meta = requireNotNull(stack.itemMeta) { "Item meta unavailable for ${configuredItem.material}" }

        if (!configuredItem.name.isNullOrBlank()) {
            meta.setDisplayName(applyText(player, configuredItem.name, replacements))
        }
        if (configuredItem.lore.isNotEmpty()) {
            meta.lore = configuredItem.lore.mapNotNull { rawLine ->
                val rendered = applyText(player, normalizeLoreControl(rawLine), replacements)
                if (rendered.contains(HIDDEN_LORE_SENTINEL)) null else rendered
            }
        }
        configuredItem.customModelData?.let(meta::setCustomModelData)
        configuredItem.itemModel?.takeIf { it.isNotBlank() }?.let { raw ->
            NamespacedKey.fromString(raw)?.let(meta::setItemModel)
        }
        meta.isUnbreakable = configuredItem.unbreakable

        configuredItem.flags.forEach { raw ->
            runCatching { ItemFlag.valueOf(raw.uppercase()) }.getOrNull()?.let { meta.addItemFlags(it) }
        }

        configuredItem.enchants.forEach { (rawKey, level) ->
            resolveEnchantment(rawKey)?.let { enchantment ->
                when (meta) {
                    is EnchantmentStorageMeta -> meta.addStoredEnchant(enchantment, level, true)
                    else -> meta.addEnchant(enchantment, level, true)
                }
            }
        }

        if (configuredItem.glow && configuredItem.enchants.isEmpty()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        stack.itemMeta = meta
        return stack
    }

    fun giveItem(player: Player, item: ItemStack) {
        val overflow = player.inventory.addItem(item).values
        overflow.forEach { remainder -> player.world.dropItemNaturally(player.location, remainder) }
    }

    fun giveConfiguredItem(player: Player, configuredItem: ConfiguredItem, totalAmount: Int, replacements: Map<String, String> = emptyMap()) {
        val preview = buildItem(configuredItem, player, replacements, 1)
        val maxStack = preview.maxStackSize.coerceAtLeast(1)
        var remaining = totalAmount
        while (remaining > 0) {
            val give = minOf(maxStack, remaining)
            giveItem(player, buildItem(configuredItem, player, replacements, give))
            remaining -= give
        }
    }

    fun countMatching(inventory: PlayerInventory, template: ItemStack): Int {
        val compare = template.clone().apply { amount = 1 }
        return inventory.contents
            .filterNotNull()
            .filter { it.isSimilar(compare) }
            .sumOf { it.amount }
    }

    fun removeMatching(inventory: PlayerInventory, template: ItemStack, amount: Int): Boolean {
        var remaining = amount
        val compare = template.clone().apply { this.amount = 1 }
        for (slot in inventory.storageContents.indices) {
            val current = inventory.getItem(slot) ?: continue
            if (!current.isSimilar(compare)) {
                continue
            }
            val take = minOf(current.amount, remaining)
            current.amount -= take
            if (current.amount <= 0) {
                inventory.setItem(slot, null)
            } else {
                inventory.setItem(slot, current)
            }
            remaining -= take
            if (remaining <= 0) {
                return true
            }
        }
        return false
    }

    fun createItemModelSnippet(item: ItemStack): List<String> {
        val meta = item.itemMeta
        val lines = mutableListOf<String>()
        lines += "icon:"
        lines += "  material: ${item.type}"
        lines += "  amount: ${item.amount}"
        if (meta != null) {
            if (meta.hasDisplayName()) {
                lines += "  name: ${quoteYaml(legacyToAmpersand(meta.displayName))}"
            }
            if (meta.hasLore()) {
                lines += "  lore:"
                meta.lore.orEmpty().forEach { line ->
                    lines += "    - ${quoteYaml(legacyToAmpersand(line))}"
                }
            }
            if (meta.hasCustomModelData()) {
                lines += "  custom-model-data: ${meta.customModelData}"
            }
            if (meta.hasItemModel()) {
                lines += "  item-model: ${quoteYaml(meta.itemModel.toString())}"
            } else {
                lines += "  # item-model: \"namespace:key\""
            }
        }
        return lines
    }

    private fun resolveEnchantment(raw: String): Enchantment? {
        val key = if (raw.contains(":")) NamespacedKey.fromString(raw) else NamespacedKey.minecraft(raw.lowercase())
        return key?.let { Registry.ENCHANTMENT[it] }
    }
}
