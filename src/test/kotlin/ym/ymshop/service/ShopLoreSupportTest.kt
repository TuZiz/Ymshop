package ym.ymshop.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ym.ymshop.model.TradeSide

class ShopLoreSupportTest {

    @Test
    fun `single-side lore detects refresh placeholder`() {
        assertTrue(
            ShopLoreSupport.hasRefreshLine(
                lore = listOf("{sell-refresh-player-line}"),
                side = TradeSide.SELL,
                dual = false
            )
        )
    }

    @Test
    fun `dual lore detects refresh placeholder`() {
        assertTrue(
            ShopLoreSupport.hasRefreshLine(
                lore = listOf("{buy-refresh-dual-line}"),
                side = TradeSide.BUY,
                dual = true
            )
        )
    }

    @Test
    fun `missing refresh placeholder is reported`() {
        assertFalse(
            ShopLoreSupport.hasRefreshLine(
                lore = listOf("{sell-limit-player-line}"),
                side = TradeSide.SELL,
                dual = false
            )
        )
    }

    @Test
    fun `blocked line embeds refresh when template lacks refresh line`() {
        assertEquals(
            "blocked &8\u5237\u65b0: {sell-refresh-player}",
            ShopLoreSupport.blockedLine(
                blocked = true,
                blockedText = "blocked",
                policyEnabled = true,
                refreshAlreadyVisible = false,
                refreshToken = "{sell-refresh-player}"
            )
        )
    }

    @Test
    fun `blocked line stays unchanged when refresh is already visible`() {
        assertEquals(
            "blocked",
            ShopLoreSupport.blockedLine(
                blocked = true,
                blockedText = "blocked",
                policyEnabled = true,
                refreshAlreadyVisible = true,
                refreshToken = "{sell-refresh-player}"
            )
        )
    }

    @Test
    fun `blocked line stays hidden when not blocked`() {
        assertEquals(
            null,
            ShopLoreSupport.blockedLine(
                blocked = false,
                blockedText = "blocked",
                policyEnabled = true,
                refreshAlreadyVisible = false,
                refreshToken = "{sell-refresh-player}"
            )
        )
    }
}
