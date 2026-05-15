package ym.ymshop.service

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.ymshop.util.applyText

class MessageService(private val plugin: JavaPlugin) {

    fun get(key: String, replacements: Map<String, String> = emptyMap(), player: Player? = null): String {
        val prefix = plugin.config.getString("messages.prefix", "&6[YmShop] &r").orEmpty()
        val body = plugin.config.getString("messages.$key", key).orEmpty()
        return applyText(player, prefix + body, replacements)
    }

    fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap(), player: Player? = sender as? Player) {
        sender.sendMessage(get(key, replacements, player))
    }

    fun sendRaw(sender: CommandSender, message: String, replacements: Map<String, String> = emptyMap(), player: Player? = sender as? Player) {
        sender.sendMessage(applyText(player, message, replacements))
    }
}
