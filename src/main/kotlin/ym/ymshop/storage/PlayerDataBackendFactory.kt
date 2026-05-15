package ym.ymshop.storage

import org.bukkit.plugin.java.JavaPlugin

object PlayerDataBackendFactory {

    fun create(plugin: JavaPlugin): PlayerDataBackend {
        val settings = DatabaseSettings.from(plugin.config.getConfigurationSection("database"))
        val backend = when (settings.type) {
            DatabaseType.YAML -> YamlPlayerDataBackend(plugin, settings)
            DatabaseType.MYSQL,
            DatabaseType.POSTGRESQL -> SqlPlayerDataBackend(plugin, settings)
        }
        plugin.logger.info("Ymshop player data backend: ${settings.type.name.lowercase()}")
        return backend
    }
}
