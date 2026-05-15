package ym.ymshop.command

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ym.ymshop.Ymshop

class YmShopCommand(private val plugin: Ymshop) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player
            if (player != null && plugin.shopService.findShop("main") != null) {
                val openResult = plugin.shopService.openShop(player, "main")
                if (openResult.success) {
                    val scheduled = plugin.platformExecutor.runForPlayer(player) {
                        plugin.shopGuiService.open(player, "main")
                    }
                    if (scheduled) {
                        return true
                    }
                }
            }
            plugin.messageService.sendRaw(sender, "&e/$label reload")
            plugin.messageService.sendRaw(sender, "&e/$label open <shop> [player]")
            plugin.messageService.sendRaw(sender, "&e/$label favorites")
            plugin.messageService.sendRaw(sender, "&e/$label itemmodel")
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("ymshop.reload")) {
                    plugin.messageService.send(sender, "no-permission")
                    return true
                }
                return handleReload(sender)
            }

            "open" -> return handleOpen(sender, args)
            "favorites", "fav" -> return handleFavorites(sender)
            "itemmodel" -> return handleItemModel(sender)
            else -> plugin.messageService.send(sender, "unknown-subcommand")
        }
        return true
    }

    private fun handleReload(sender: CommandSender): Boolean {
        plugin.platformExecutor.runGlobalAsync {
            plugin.shopService.reload()
        }.whenComplete { _, ex ->
            plugin.platformExecutor.runForSender(sender) {
                if (ex == null) {
                    plugin.messageService.send(sender, "reload-success")
                } else {
                    val cause = ex.cause ?: ex
                    plugin.logger.severe("Reload failed: ${cause.message}")
                    plugin.messageService.send(
                        sender,
                        "reload-failed",
                        mapOf("reason" to (cause.message ?: cause.javaClass.simpleName))
                    )
                }
            }
        }
        return true
    }

    private fun handleOpen(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            plugin.messageService.sendRaw(sender, "&cUsage: /ymshop open <shop> [player]")
            return true
        }

        val openingOther = args.size >= 3
        if (openingOther) {
            if (sender is Player && !sender.hasPermission("ymshop.open.others")) {
                plugin.messageService.send(sender, "no-permission")
                return true
            }
            if (sender !is Player && !sender.hasPermission("ymshop.open.others") && !sender.isOp) {
                plugin.messageService.send(sender, "no-permission")
                return true
            }
        } else if (!sender.hasPermission("ymshop.open")) {
            plugin.messageService.send(sender, "no-permission")
            return true
        }

        val target = when {
            args.size >= 3 -> plugin.server.getPlayerExact(args[2])
            sender is Player -> sender
            else -> null
        }

        if (target == null) {
            plugin.messageService.send(sender, "player-not-found")
            return true
        }

        val openResult = plugin.shopService.openShop(target, args[1])
        if (!openResult.success) {
            plugin.messageService.send(sender, openResult.messageKey, openResult.replacements)
            return true
        }

        val scheduled = plugin.platformExecutor.runForPlayer(target) {
            plugin.shopGuiService.open(target, args[1])
        }
        if (!scheduled) {
            plugin.messageService.send(sender, "player-schedule-failed", mapOf("player" to target.name))
        }
        return true
    }

    private fun handleFavorites(sender: CommandSender): Boolean {
        if (!sender.hasPermission("ymshop.open")) {
            plugin.messageService.send(sender, "no-permission")
            return true
        }
        val player = sender as? Player ?: run {
            plugin.messageService.send(sender, "player-only")
            return true
        }
        val scheduled = plugin.platformExecutor.runForPlayer(player) {
            plugin.shopGuiService.openFavorites(player)
        }
        if (!scheduled) {
            plugin.messageService.send(sender, "player-schedule-failed", mapOf("player" to player.name))
        }
        return true
    }

    private fun handleItemModel(sender: CommandSender): Boolean {
        if (!sender.hasPermission("ymshop.itemmodel")) {
            plugin.messageService.send(sender, "no-permission")
            return true
        }
        val player = sender as? Player ?: run {
            plugin.messageService.send(sender, "player-only")
            return true
        }
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            plugin.messageService.send(player, "itemmodel-empty-hand")
            return true
        }
        plugin.messageService.send(player, "itemmodel-header")
        plugin.itemService.createItemModelSnippet(item).forEach(player::sendMessage)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("reload", "open", "favorites", "itemmodel").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("open", ignoreCase = true)) plugin.shopService.shopIds().filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            3 -> if (args[0].equals("open", ignoreCase = true) && sender.hasPermission("ymshop.open.others")) {
                plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }
}
