package com.brunogiovani.cachetaburaco.domain.models

object DeckFactory {
    fun createDecks(numberOfDecks: Int = 2, includeJokers: Boolean = true): List<Card> {
        val cards = mutableListOf<Card>()
        val colors = listOf(DeckColor.RED, DeckColor.BLACK)
        
        for (i in 0 until numberOfDecks) {
            val color = colors[i % colors.size]
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    cards.add(Card(suit, rank, isJoker = false, deckColor = color))
                }
            }
            if (includeJokers) {
                cards.add(Card(Suit.SPADES, Rank.ACE, isJoker = true, deckColor = color)) // Coringa Preto
                cards.add(Card(Suit.HEARTS, Rank.ACE, isJoker = true, deckColor = color)) // Coringa Vermelho
            }
        }
        return cards.shuffled()
    }
}
