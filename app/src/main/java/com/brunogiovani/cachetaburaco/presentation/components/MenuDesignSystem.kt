package com.brunogiovani.cachetaburaco.presentation.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

    // Respiracao de indicadores de estado (monte/lixo/jogo ativo). Antes cada
    // componente da partida inventava seu proprio ciclo (900/1100/1200ms) - agora
    // todo "pulso" de destaque usa o mesmo ritmo, aqui e nos dialogos de mesa.
    const val DURATION_PULSE = 1100

    fun <T> standard() = tween<T>(DURATION_MEDIUM)
    fun <T> quick() = tween<T>(DURATION_SHORT)
    fun <T> pulse() = tween<T>(DURATION_PULSE, easing = LinearEasing)
}

object MenuMetrics {
    val MinTouchTarget = 48.dp
    val ScreenPaddingCompact = 12.dp
    val ScreenPaddingRegular = 20.dp
    val SectionSpacing = 14.dp
    val MaxContentWidth = 1040.dp
}

/**
 * Preferencia de "reduzir movimento" do sistema (Config. > Acessibilidade > Remover
 * animacoes, ou a escala de duracao de animador nas opcoes de desenvolvedor). Nao existe
 * hoje nenhum ponto do app que respeite isso - o Compose ja escala tweens/springs
 * individuais via MotionDurationScale, mas um `infiniteRepeatable` com duracao zerada
 * pisca sem parar em vez de parar, entao os loops decorativos (brilho ambiente, pulso de
 * destaque, leque brilhante da mao, confete) chamam isto para congelar num valor fixo.
 *
 * Le `Settings.Global.ANIMATOR_DURATION_SCALE` direto (em vez de
 * `ValueAnimator.getDurationScale()`, que só existe a partir da API 33) porque o
 * app tem `minSdk = 26`.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
        }.getOrDefault(false)
    }
}
