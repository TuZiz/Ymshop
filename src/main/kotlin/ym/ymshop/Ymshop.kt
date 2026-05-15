package ym.ymshop

import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.jar.JarFile
import ym.ymshop.command.YmShopCommand
import ym.ymshop.service.ConfigLoader
import ym.ymshop.service.CurrencyService
import ym.ymshop.service.FavoriteService
import ym.ymshop.service.ItemService
import ym.ymshop.service.MessageService
import ym.ymshop.service.PlatformExecutor
import ym.ymshop.service.ShopGuiService
import ym.ymshop.service.ShopService
import ym.ymshop.service.TradeLogService
import ym.ymshop.storage.PlayerDataBackend
import ym.ymshop.storage.PlayerDataBackendFactory

class Ymshop : JavaPlugin() {

    lateinit var platformExecutor: PlatformExecutor
        private set

    lateinit var messageService: MessageService
        private set

    lateinit var itemService: ItemService
        private set

    lateinit var currencyService: CurrencyService
        private set

    lateinit var favoriteService: FavoriteService
        private set

    lateinit var tradeLogService: TradeLogService
        private set

    lateinit var playerDataBackend: PlayerDataBackend
        private set

    lateinit var shopService: ShopService
        private set

    lateinit var shopGuiService: ShopGuiService
        private set

    override fun onEnable() {
        saveBundledResources()

        platformExecutor = PlatformExecutor(this)
        messageService = MessageService(this)
        itemService = ItemService(this)
        currencyService = CurrencyService(this, platformExecutor)
        tradeLogService = TradeLogService(this)
        playerDataBackend = runCatching { PlayerDataBackendFactory.create(this) }
            .getOrElse { ex ->
                logger.severe("Failed to initialize player data backend: ${ex.message}")
                ex.printStackTrace()
                server.pluginManager.disablePlugin(this)
                return
            }

        runCatching {
            favoriteService = FavoriteService(playerDataBackend)
            shopService = ShopService(
                this,
                platformExecutor,
                ConfigLoader(this),
                itemService,
                currencyService,
                messageService,
                playerDataBackend,
                tradeLogService
            )
            shopGuiService = ShopGuiService(this, platformExecutor, shopService, itemService, currencyService, favoriteService)
            shopService.addResetListener(shopGuiService::refreshOpenInventories)
            server.pluginManager.registerEvents(shopGuiService, this)

            shopService.reload()
            currencyService.logStartupDiagnostics()
            registerCommands()
        }.onFailure { ex ->
            logger.severe("Failed to enable Ymshop: ${ex.message}")
            ex.printStackTrace()
            runCatching { playerDataBackend.close() }
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        if (::shopGuiService.isInitialized) {
            shopGuiService.close()
        }
        if (::shopService.isInitialized) {
            shopService.close()
        }
        if (::tradeLogService.isInitialized) {
            tradeLogService.close()
        }
        if (::favoriteService.isInitialized) {
            favoriteService.close()
        }
        if (::playerDataBackend.isInitialized) {
            playerDataBackend.close()
        }
        if (::platformExecutor.isInitialized) {
            platformExecutor.close()
        }
    }

    private fun registerCommands() {
        val command = YmShopCommand(this)
        val pluginCommand: PluginCommand = requireNotNull(getCommand("ymshop")) { "ymshop command not defined in plugin.yml" }
        pluginCommand.setExecutor(command)
        pluginCommand.tabCompleter = command
    }

    private fun saveBundledResources() {
        saveDefaultConfig()
        saveBundledYamlDirectory("templates")
        saveBundledYamlDirectory("layouts")
        saveBundledYamlDirectory("shops")
    }

    private fun saveResourceIfAbsent(path: String) {
        val target = dataFolder.resolve(path)
        if (!target.exists()) {
            if (getResource(path) == null) {
                logger.warning("Bundled resource missing, skipped copy: $path")
                return
            }
            target.parentFile.mkdirs()
            saveResource(path, false)
        }
    }

    private fun saveBundledYamlDirectory(directory: String) {
        bundledYamlPaths(directory).forEach(::saveResourceIfAbsent)
    }

    private fun bundledYamlPaths(directory: String): List<String> {
        val codeSource = runCatching { File(javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            ?: return emptyList()
        val normalizedDirectory = directory.trim('/').trimEnd('/') + "/"

        if (codeSource.isFile) {
            return JarFile(codeSource).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(normalizedDirectory) && it.name.endsWith(".yml", ignoreCase = true) }
                    .map { it.name }
                    .sorted()
                    .toList()
            }
        }

        val resourceDirectory = codeSource.resolve(directory)
        if (!resourceDirectory.exists()) {
            return emptyList()
        }

        return resourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
            .map { it.relativeTo(codeSource).invariantSeparatorsPath }
            .sorted()
            .toList()
    }
}
