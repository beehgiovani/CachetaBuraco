package com.brunogiovani.cachetaburaco.domain.models

enum class DeckColor { RED, BLACK }

enum class Suit {
    HEARTS, DIAMONDS, CLUBS, SPADES
}

enum class Rank(val value: Int) {
    ACE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    SIX(6),
    SEVEN(7),
    EIGHT(8),
    NINE(9),
    TEN(10),
    JACK(11),
    QUEEN(12),
    KING(13)
}

data class Card(
    val suit: Suit,
    val rank: Rank,
    val isJoker: Boolean = false,
    val deckColor: DeckColor = DeckColor.BLACK
) {
    // Para ordenação e cálculos de jogo
    val id: String get() = if (isJoker) "JOKER_${deckColor.name}_${suit.name}" else "${rank.name}_${suit.name}_${deckColor.name}"
}
