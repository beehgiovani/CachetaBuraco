package com.brunogiovani.cachetaburaco.presentation.components

import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineAvatarViewTest {
    @Test
    fun `card suit avatars keep stable labels and symbols`() {
        assertEquals("Esmeralda", avatarLabel(OnlineAvatar.EMERALD))
        assertEquals("♣", avatarSymbol(OnlineAvatar.EMERALD, "Jogador"))
        assertEquals("♦", avatarSymbol(OnlineAvatar.GOLD, "Jogador"))
        assertEquals("♥", avatarSymbol(OnlineAvatar.RUBY, "Jogador"))
        assertEquals("♠", avatarSymbol(OnlineAvatar.SAPPHIRE, "Jogador"))
    }

    @Test
    fun `graphite avatar uses player initial with safe fallback`() {
        assertEquals("B", avatarSymbol(OnlineAvatar.GRAPHITE, " bruno"))
        assertEquals("J", avatarSymbol(OnlineAvatar.GRAPHITE, "  "))
    }
}
