package com.brunogiovani.cachetaburaco.presentation.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Paleta e tokens visuais dos menus (tudo que fica fora da mesa de jogo).
 *
 * Antes cada tela inventava sua propria cor para cada botao (azul, roxo, teal...),
 * o que deixava o app parecido com paineis de app diferentes. Aqui a base fica
 * sempre em verde de mesa, preto/branco de carta e dourado de destaque - vermelho
 * e reservado só para acoes de risco (sair, erro), como pedido no brief de design.
 */
object MenuColors {
    val TableGreenDeep = Color(0xFF07231A)
    val TableGreen = Color(0xFF1E6B45)
    val TableGreenLight = Color(0xFF4CAF50)
    val Ink = Color(0xFF10161C)
    val InkPanel = Color(0xE6121A22)
    val InkPanelSoft = Color(0xCC121A22)
    val Gold = Color(0xFFFFD54F)
    val GoldDeep = Color(0xFFC9A227)
    val Red = Color(0xFFE0483F)
    val RedDeep = Color(0xFFB71C1C)
    val OnDark = Color.White
    val OnDarkMuted = Color.White.copy(alpha = 0.66f)
    val OnDarkFaint = Color.White.copy(alpha = 0.42f)
    val Border = Color.White.copy(alpha = 0.10f)
    val BorderStrong = Color.White.copy(alpha = 0.22f)

    fun backgroundScrim(): Brush = Brush.verticalGradient(
        colors = listOf(Color(0xE6060B08), Color(0xF2071309))
    )
}

object MenuShapes {
    // Regra do brief: nenhum "card" passa de 8dp de raio.
    val Card = RoundedCornerShape(8.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Button = RoundedCornerShape(12.dp)
}

object MenuMotion {
    const val DURATION_SHORT = 180
    const val DURATION_MEDIUM = 240
    const val DURATION_LONG = 300

    fun <T> standard() = tween<T>(DURATION_MEDIUM)
    fun <T> quick() = tween<T>(DURATION_SHORT)
}

object MenuMetrics {
    val MinTouchTarget = 48.dp
    val ScreenPaddingCompact = 12.dp
    val ScreenPaddingRegular = 20.dp
    val SectionSpacing = 14.dp
    val MaxContentWidth = 1040.dp
}
