package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.DeckColor
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.presentation.components.CardView
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuMotion
import com.brunogiovani.cachetaburaco.presentation.components.rememberReducedMotionEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Efeitos visuais da mesa: fundo com movimento sutil, distribuicao de cartas,
// brilho ao baixar jogo, aviso do morto e confete de vitoria. Nenhum deles
// muda estado de jogo -- sao so feedback.

@Composable
internal fun FeltAmbientMotion(modifier: Modifier = Modifier) {
    // Puramente decorativo (nao indica estado nenhum), entao com "reduzir
    // movimento" ligado ele so fica parado no meio em vez de varrer a mesa.
    val reducedMotion = rememberReducedMotionEnabled()
    val drift = if (reducedMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "felt_ambient_motion")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "felt_ambient_drift"
        )
        value
    }

    Canvas(modifier = modifier) {
        val x = size.width * drift
        val glowCenter = androidx.compose.ui.geometry.Offset(x, size.height * 0.42f)
        drawCircle(
            color = ColorGreenLight.copy(alpha = 0.045f),
            radius = size.minDimension * 0.45f,
            center = glowCenter
        )
        drawLine(
            color = Color.White.copy(alpha = 0.035f),
            start = androidx.compose.ui.geometry.Offset(x - size.width * 0.42f, 0f),
            end = androidx.compose.ui.geometry.Offset(x + size.width * 0.08f, size.height),
            strokeWidth = size.width * 0.055f
        )
    }
}

@Composable
internal fun DealingAnimation(
    visible: Boolean,
    cardCount: Int,
    modifier: Modifier = Modifier
) {
    // Some inteira com "reduzir movimento": o LaunchedEffect que a aciona (em
    // MatchScreen) tambem zera o delay, entao ninguem fica esperando um efeito
    // que nunca vai aparecer.
    if (rememberReducedMotionEnabled()) return
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = LinearEasing),
        label = "deal_progress"
    )
    if (progress <= 0.01f) return

    val backCard = remember {
        Card(Suit.SPADES, Rank.ACE, deckColor = DeckColor.BLACK)
    }
    Box(modifier = modifier.alpha((1f - progress).coerceIn(0.2f, 0.9f))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alpha = (1f - progress).coerceIn(0f, 0.55f)
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.38f)
            drawCircle(
                color = ColorGold.copy(alpha = alpha * 0.18f),
                radius = size.minDimension * (0.12f + progress * 0.18f),
                center = center
            )
            repeat(5) { index ->
                val lineProgress = (progress + index * 0.08f).coerceIn(0f, 1f)
                drawLine(
                    color = Color.White.copy(alpha = alpha * 0.16f),
                    start = androidx.compose.ui.geometry.Offset(center.x, center.y),
                    end = androidx.compose.ui.geometry.Offset(
                        center.x + (index - 2) * size.width * 0.09f,
                        center.y + size.height * (0.2f + lineProgress * 0.28f)
                    ),
                    strokeWidth = 3f
                )
            }
        }
        Text(
            text = "Distribuindo cartas...",
            color = ColorGold.copy(alpha = (1f - progress).coerceIn(0.2f, 0.9f)),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-138).dp)
                .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp)
        )
        repeat(cardCount) { index ->
            val delay = index * 0.055f
            val localProgress = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
            val easedProgress = localProgress * localProgress * (3f - 2f * localProgress)
            val spread = (index - (cardCount - 1) / 2f) * 34f
            val startX = 0f
            val startY = (-80f)
            val endX = spread
            val endY = 260f + (index % 2) * 16f
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            x = (startX + (endX - startX) * easedProgress).toInt(),
                            y = (startY + (endY - startY) * easedProgress).toInt()
                        )
                    }
                    .graphicsLayer {
                        rotationZ = -20f + index * 4.7f + easedProgress * 13f
                        rotationX = 8f * (1f - easedProgress)
                        scaleX = 0.68f + easedProgress * 0.2f
                        scaleY = 0.68f + easedProgress * 0.2f
                        alpha = localProgress.coerceIn(0.15f, 1f)
                    }
            ) {
                CardView(
                    card = backCard,
                    isFaceUp = false,
                    modifier = Modifier.size(width = 54.dp, height = 82.dp)
                )
            }
        }
    }
}

private data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val sway: Float,
    val phase: Float
)

@Composable
internal fun MeldSparkleBurst(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // O texto de "lastMeldResult" ja avisa o resultado por escrito; a explosao de
    // particulas e so reforco visual, entao fica de fora quando reduzir movimento.
    val reducedMotion = rememberReducedMotionEnabled()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible, reducedMotion) {
        if (visible && !reducedMotion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 760, easing = LinearEasing))
        }
    }
    if (!visible || reducedMotion || progress.value <= 0.01f) return

    val colors = remember {
        listOf(ColorGold, ColorGreenLight, ColorBlueLight, Color.White)
    }
    Canvas(modifier = modifier.size(250.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        repeat(28) { index ->
            val angle = (PI.toFloat() * 2f / 28f) * index
            val distance = (28f + (index % 5) * 9f) * progress.value
            val x = center.x + cos(angle) * distance
            val y = center.y + sin(angle) * distance
            drawCircle(
                color = colors[index % colors.size],
                radius = (5f - progress.value * 2.5f).coerceAtLeast(1.5f),
                center = androidx.compose.ui.geometry.Offset(x, y),
                alpha = (1f - progress.value).coerceIn(0f, 0.92f)
            )
        }
    }
}

@Composable
internal fun MortoNoticeOverlay(
    text: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(animationSpec = MenuMotion.quick()) + scaleIn(
            initialScale = 0.86f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = fadeOut(animationSpec = MenuMotion.standard()) + scaleOut(
            targetScale = 0.92f,
            animationSpec = MenuMotion.standard()
        ),
        modifier = modifier.zIndex(4f)
    ) {
        val pulse = rememberPulseAlpha(min = 0.62f, label = "morto_notice_pulse")
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.78f),
                            Color(0xFF143B2C).copy(alpha = 0.90f),
                            Color.Black.copy(alpha = 0.78f)
                        )
                    ),
                    RoundedCornerShape(18.dp)
                )
                .border(2.dp, ColorGold.copy(alpha = pulse), RoundedCornerShape(18.dp))
                .shadow(18.dp, RoundedCornerShape(18.dp), clip = false)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MORTO",
                color = ColorGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                text = text.orEmpty(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun VictoryConfetti(visible: Boolean) {
    if (!visible) return
    // Confete e comemoracao pura, sem informacao nenhuma alem do dialogo de fim
    // de rodada que ja aparece junto - primeiro a sair quando reduzir movimento.
    if (rememberReducedMotionEnabled()) return

    val colors = remember {
        listOf(
            Color(0xFFFFD54F),
            Color(0xFF4CAF50),
            Color(0xFF42A5F5),
            Color(0xFFEF5350),
            Color.White
        )
    }
    val particles = remember {
        List(90) {
            ConfettiParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 4f + 3f,
                speed = Random.nextFloat() * 0.45f + 0.55f,
                sway = Random.nextFloat() * 0.08f + 0.02f,
                phase = Random.nextFloat() * 6.28f
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "victory_confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "victory_confetti_progress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEachIndexed { index, particle ->
            val fall = (particle.y + progress * particle.speed) % 1.15f
            val wave = sin((progress * 6.28f + particle.phase).toDouble()).toFloat()
            val x = (particle.x + wave * particle.sway).coerceIn(0f, 1f) * size.width
            val y = (fall - 0.1f) * size.height
            drawCircle(
                color = colors[index % colors.size],
                radius = particle.radius,
                center = androidx.compose.ui.geometry.Offset(x, y),
                alpha = 0.92f
            )
        }
    }
}
