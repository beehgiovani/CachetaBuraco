package com.brunogiovani.cachetaburaco.presentation.match

/**
 * Emojis de provocacao do chat de partida.
 *
 * Sao mensagens de chat comuns (mesmo `room_chat_messages`, mesma RLS, mesmo
 * bloqueio de banido da 0051) -- o atalho aqui so evita o jogador ter que
 * abrir o teclado no meio da mao pra mandar um "😏". O texto acompanha o
 * emoji porque quem esta com fonte grande ou leitor de tela nao enxerga so
 * o simbolo.
 *
 * Provocacao de mesa de carta, no tom de quem joga com amigo -- nada que
 * sirva pra ofender de verdade (isso e o que as diretrizes da comunidade
 * proibem, ver docs/termos-de-uso.md).
 */
data class TauntEmoji(val emoji: String, val label: String) {
    /** O que vai de fato pro chat -- emoji e texto juntos. */
    val message: String get() = "$emoji $label"
}

val TAUNT_EMOJIS = listOf(
    TauntEmoji("😏", "Tá suave"),
    TauntEmoji("😱", "Que isso!"),
    TauntEmoji("😂", "Rindo muito"),
    TauntEmoji("🔥", "Tô pegando fogo"),
    TauntEmoji("🐢", "Anda logo"),
    TauntEmoji("🍀", "Sorte sua"),
    TauntEmoji("🤝", "Boa partida"),
    TauntEmoji("👏", "Belo jogo")
)
