package ym.ymshop.util

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

private val hexColorPattern = Regex("(?i)&#([0-9a-f]{6})")
private val ampHexColorPattern = Regex("(?i)&x(&[0-9a-f]){6}")

fun color(text: String): String {
    val expandedHex = hexColorPattern.replace(text) { match ->
        buildString {
            append("&x")
            match.groupValues[1].forEach { char ->
                append('&')
                append(char.uppercaseChar())
            }
        }
    }
    val normalized = ampHexColorPattern.replace(expandedHex) { it.value.uppercase() }
    return ChatColor.translateAlternateColorCodes('&', normalized)
}

fun applyPlaceholders(player: Player?, text: String): String {
    if (player == null) {
        return text
    }
    return resolvePlaceholderText(player, text)
}

fun replaceTokens(text: String, replacements: Map<String, String>): String {
    var result = text
    repeat(4) {
        var changed = false
        replacements.forEach { (key, value) ->
            val next = result
                .replace("%$key%", value, ignoreCase = false)
                .replace("{$key}", value, ignoreCase = false)
            if (next != result) {
                changed = true
                result = next
            }
        }
        if (!changed) {
            return result
        }
    }
    return result
}

fun applyText(player: Player?, text: String, replacements: Map<String, String> = emptyMap()): String {
    val tokenApplied = replaceTokens(text, replacements)
    val placeholderApplied = applyPlaceholders(player, tokenApplied)
    return color(placeholderApplied)
}

fun normalizeLoreControl(text: String): String {
    if (text == "@") {
        return ""
    }
    if (!text.startsWith("@")) {
        return text
    }
    if (text.length >= 3 && text[1].isLetter()) {
        return text.substring(2)
    }
    return text.substring(1)
}

fun legacyToAmpersand(text: String): String = text.replace('\u00A7', '&')

fun quoteYaml(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun prettifyMaterialName(materialName: String): String {
    return materialName.lowercase()
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }
}

private val placeholderMethod by lazy {
    runCatching {
        val clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        clazz.getMethod("setPlaceholders", OfflinePlayer::class.java, String::class.java)
    }.getOrNull()
}

fun resolvePlaceholderText(player: OfflinePlayer?, text: String): String {
    if (player == null) {
        return text
    }
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
        return text
    }
    val method = placeholderMethod ?: return text
    return runCatching { method.invoke(null, player, text) as String }.getOrDefault(text)
}
