package com.brunogiovani.cachetaburaco.data.online

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * Codigo curto para sala online.
 *
 * Nao e segredo nem identificador de seguranca; e apenas um jeito facil de um
 * jogador encontrar a mesa criada por outro. A restricao UNIQUE do banco ainda
 * e a autoridade final caso dois aparelhos sorteiem o mesmo codigo.
 */
object OnlineRoomCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    fun create(
        playerName: String,
        nextIndex: (bound: Int) -> Int = { bound -> secureRandom.nextInt(bound) }
    ): String {
        val normalizedName = Normalizer.normalize(playerName, Normalizer.Form.NFD)
        val prefix = normalizedName
            .uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .take(3)
            .ifBlank { "CBR" }
        val suffix = buildString(capacity = 5) {
            repeat(5) {
                val index = Math.floorMod(nextIndex(ALPHABET.length), ALPHABET.length)
                append(ALPHABET[index])
            }
        }
        return "$prefix$suffix"
    }
}
