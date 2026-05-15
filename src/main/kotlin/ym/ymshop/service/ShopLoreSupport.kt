package ym.ymshop.service

import ym.ymshop.model.TradeSide

internal object ShopLoreSupport {

    fun hasRefreshLine(lore: List<String>, side: TradeSide, dual: Boolean): Boolean {
        val token = when {
            dual && side == TradeSide.BUY -> "{buy-refresh-dual-line}"
            dual && side == TradeSide.SELL -> "{sell-refresh-dual-line}"
            side == TradeSide.BUY -> "{buy-refresh-player-line}"
            else -> "{sell-refresh-player-line}"
        }
        return lore.any { line -> line.contains(token, ignoreCase = true) }
    }

    fun blockedLine(
        blocked: Boolean,
        blockedText: String,
        policyEnabled: Boolean,
        refreshAlreadyVisible: Boolean,
        refreshToken: String
    ): String? {
        if (!blocked) {
            return null
        }
        if (!policyEnabled || refreshAlreadyVisible) {
            return blockedText
        }
        return "$blockedText &8\u5237\u65b0: $refreshToken"
    }
}
