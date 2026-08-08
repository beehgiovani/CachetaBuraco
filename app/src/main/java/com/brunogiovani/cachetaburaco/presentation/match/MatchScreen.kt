package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import android.app.Activity
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.presentation.components.PostMatchInterstitialAd
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.DeckColor
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.presentation.components.CardView
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuMotion
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.rememberReducedMotionEnabled
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ─── Paleta ───────────────────────────────────────────────
private val ColorGreenLight = Color(0xFF4CAF50)
private val ColorBlueLight = Color(0xFF42A5F5)
private val ColorGold = Color(0xFFFFD54F)
private val ColorRed = Color(0xFFB71C1C)
private val ColorRedLight = Color(0xFFEF5350)
private val ColorSurface = Color(0xAA000000)
private val ColorLockRed = Color(0xFFEF5350)

private fun String.isErrorFeedback(): Boolean {
    return contains("nao", ignoreCase = true) ||
        contains("não", ignoreCase = true) ||
        contains("inval", ignoreCase = true) ||
        contains("bloque", ignoreCase = true) ||
        contains("insuficiente", ignoreCase = true) ||
        contains("Selecione", ignoreCase = true) ||
        contains("precisa", ignoreCase = true) ||
        startsWith("❌")
}

/**
 * Alpha de "respiracao" dos indicadores de estado (monte, lixo, jogos na mesa,
 * dialogos de selecao). Antes cada componente tinha seu proprio ciclo de pulso
 * (900/1100/1200ms) com curva propria - agora todos usam a mesma duracao do
 * design system (`MenuMotion.pulse`). Com "reduzir movimento" ligado o indicador
 * fica parado no valor mais aceso: continua sinalizando estado, so sem o loop
 * infinito, que a essa preferencia poderia virar uma cintilacao constante.
 */
@Composable
private fun rememberPulseAlpha(min: Float, max: Float = 1f, label: String = "pulse"): Float {
    if (rememberReducedMotionEnabled()) return max
    val pulse by rememberInfiniteTransition(label = label).animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = MenuMotion.pulse(),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}_alpha"
    )
    return pulse
}

internal fun RoundEndDetails.isLocalMatchWinner(): Boolean {
    return isMatchOver && winnerTeam != null && winnerTeam == localTeam
}

@Composable
fun MatchScreen(
    networkRepository: LocalNetworkRepository,
    isHost: Boolean,
    config: MatchConfig = MatchConfig(),
    onLeaveMatch: () -> Unit
) {
    // Preview do Android Studio não cria ViewModel real. Uso uma mesa estática
    // para conferir tamanho, fonte e espaçamento sem depender de rede/coroutine.
    // A tela só desenha o GameState e repassa cliques; regra fica no ViewModel.
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        MatchScreenStaticPreview(config = config)
        return
    }

    val currentPlayer = FakeAuthRepository.getCurrentPlayer() ?: return

    val context = androidx.compose.ui.platform.LocalContext.current
    val matchPlayerId = remember(networkRepository, currentPlayer.id) {
        networkRepository.authenticatedPlayerId ?: currentPlayer.id
    }
    val viewModel = remember(networkRepository, matchPlayerId, isHost, config) {
        MatchViewModel(networkRepository, matchPlayerId, isHost, config, context)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    val state by viewModel.gameState.collectAsState()
    val connectionStatus by networkRepository.connectionStatus.collectAsState()
    val feedback = rememberMatchFeedback()
    // Acessibilidade: usuario com "reduzir movimento" ligado ve a distribuicao
    // instantanea em vez de esperar a animacao cosmetica que ninguem vai ver.
    val reducedMotion = rememberReducedMotionEnabled()
    val roundEndDetails = state.roundEndDetails
    var recordedMatchWinKey by remember { mutableStateOf<String?>(null) }
    var showDealingAnimation by remember { mutableStateOf(false) }
    var mortoNoticeText by remember { mutableStateOf<String?>(null) }
    var lastMortoNoticeKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (isHost && !viewModel.isRestored) viewModel.startGame() }

    // Quando a conexão volta, o cliente pede a mesa atual para o host. Um cliente
    // restaurado de snapshot (app fechado e reaberto) nunca passa por
    // OPPONENT_DISCONNECTED/HOST_DISCONNECTED antes do primeiro CONNECTED -- por
    // isso ele também dispara esse pedido na primeira conexão, pra corrigir
    // qualquer coisa que tenha mudado na mesa enquanto o app estava fechado.
    var wasDisconnected by remember { mutableStateOf(false) }
    var didRequestResumeSync by remember { mutableStateOf(false) }
    LaunchedEffect(connectionStatus) {
        when (connectionStatus) {
            ConnectionStatus.OPPONENT_DISCONNECTED,
            ConnectionStatus.HOST_DISCONNECTED,
            ConnectionStatus.ERROR -> wasDisconnected = true
            ConnectionStatus.CONNECTED -> {
                val shouldResync = wasDisconnected || (viewModel.isRestored && !didRequestResumeSync)
                if (shouldResync && !isHost) {
                    wasDisconnected = false
                    didRequestResumeSync = true
                    viewModel.requestReconnect()
                }
            }
            else -> {}
        }
    }

    LaunchedEffect(state.turnPhase, state.activeSeat, state.playerSeat) {
        if (state.turnPhase == TurnPhase.DRAW || state.turnPhase == TurnPhase.ACTION) {
            feedback.play(FeedbackCue.Turn)
        }
    }

    LaunchedEffect(
        state.turnPhase,
        state.activeSeat,
        state.myHand.size,
        state.selectedCards.size,
        state.discardPile.size
    ) {
        if (state.turnPhase == TurnPhase.DRAW || state.turnPhase == TurnPhase.ACTION) {
            kotlinx.coroutines.delay(5000)
            feedback.play(FeedbackCue.Nudge)
        }
    }

    LaunchedEffect(state.dealEventId, reducedMotion) {
        if (state.dealEventId > 0 && state.myHand.isNotEmpty()) {
            showDealingAnimation = true
            kotlinx.coroutines.delay(if (reducedMotion) 0L else 1200)
            showDealingAnimation = false
        }
    }

    LaunchedEffect(
        roundEndDetails?.isMatchOver,
        roundEndDetails?.winnerName,
        roundEndDetails?.myNewTotal,
        roundEndDetails?.opponentNewTotal,
        state.dealEventId
    ) {
        val details = roundEndDetails ?: return@LaunchedEffect
        val isLocalWinner = details.isLocalMatchWinner()
        val winKey = "${state.dealEventId}:${details.winnerTeam}:${details.myNewTotal}:${details.opponentNewTotal}"
        if (isLocalWinner && recordedMatchWinKey != winKey) {
            feedback.play(FeedbackCue.Victory)
            FakeAuthRepository.recordCurrentPlayerVictory()
            recordedMatchWinKey = winKey
        } else if (details.isMatchOver || state.showRoundEndDialog) {
            feedback.play(FeedbackCue.RoundEnd)
        }
        // Carrega o intersticial de pos-partida com antecedencia para estar pronto
        // quando o jogador tocar em "Voltar ao Menu"/"Jogar Novamente".
        if (details.isMatchOver) {
            PostMatchInterstitialAd.preload(context)
        }
    }

    LaunchedEffect(state.mortoNoticeId) {
        val pickedSeat = state.mortoNoticeSeat ?: return@LaunchedEffect
        val localTeam = ((state.playerSeat % 2) + 2) % 2
        val pickedTeam = ((pickedSeat % 2) + 2) % 2
        val pickedByLocalSeat = pickedSeat == state.playerSeat
        val pickedByLocalTeam = pickedTeam == localTeam
        val notice = when {
            pickedByLocalSeat -> "Você pegou o morto"
            pickedByLocalTeam -> "Sua equipe pegou o morto"
            state.opponentLabel == "Máquina" -> "Máquina pegou o morto"
            state.config.maxPlayers == 4 -> "Equipe adversária pegou o morto"
            else -> "Oponente pegou o morto"
        }
        val key = state.mortoNoticeId.toString()
        if (key != lastMortoNoticeKey) {
            lastMortoNoticeKey = key
            mortoNoticeText = notice
            feedback.play(if (pickedByLocalTeam) FeedbackCue.Draw else FeedbackCue.OpponentMorto)
            kotlinx.coroutines.delay(2600)
            if (mortoNoticeText == notice) mortoNoticeText = null
        }
    }

    // Limpa feedback de meld após 2s
    LaunchedEffect(state.lastMeldResult) {
        if (state.lastMeldResult.isNotBlank()) {
            feedback.play(if (state.lastMeldResult.isErrorFeedback()) FeedbackCue.Error else FeedbackCue.Place)
            kotlinx.coroutines.delay(2000)
            viewModel.clearMeldFeedback()
        }
    }

    LaunchedEffect(state.feedbackMessage) {
        if (state.feedbackMessage.isErrorFeedback()) {
            feedback.play(FeedbackCue.Error)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {

        // ── Fundo ──────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        FeltAmbientMotion(modifier = Modifier.fillMaxSize())

        // ── Layout Principal ───────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            TopBar(state = state, config = state.config, onLeave = {
                viewModel.clearGameSnapshot()
                networkRepository.stopHosting()
                networkRepository.disconnect()
                onLeaveMatch()
            })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TableCenter(state = state, config = state.config, viewModel = viewModel, feedback = feedback)
            }

            HandSection(state = state, viewModel = viewModel, config = state.config, feedback = feedback)
        }

        VictoryConfetti(
            visible = state.showRoundEndDialog &&
                roundEndDetails != null &&
                (roundEndDetails.winnerName.startsWith("Voc") || roundEndDetails.winnerName == "Sua equipe")
        )

        DealingAnimation(
            visible = showDealingAnimation,
            cardCount = state.myHand.size.coerceIn(6, 11),
            modifier = Modifier.fillMaxSize()
        )

        MeldSparkleBurst(
            visible = state.lastMeldResult.isNotBlank(),
            modifier = Modifier.align(Alignment.Center)
        )

        MortoNoticeOverlay(
            text = mortoNoticeText,
            modifier = Modifier.align(Alignment.Center)
        )

        // ── Overlay de Meld feedback ───────────────────────
        // Entrada/saida usam a mesma duracao curta do design system (antes eram
        // 140/180ms "a olho", cada tela com seu proprio numero).
        AnimatedVisibility(
            visible = state.lastMeldResult.isNotBlank(),
            enter = fadeIn(animationSpec = MenuMotion.quick()) + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            exit = fadeOut(animationSpec = MenuMotion.quick()) + scaleOut(
                targetScale = 0.92f,
                animationSpec = MenuMotion.quick()
            ),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = state.lastMeldResult,
                color = ColorGold,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }

        // -- Diálogo de Desconexão --------------------------------------------------------------------
        // ConnectionStatus.ERROR (falha de registro Wi-Fi/socket ou de sessão
        // online) ficava sem nenhum tratamento aqui -- a mesa congelava sem
        // diálogo, mensagem ou botão de saída, tanto pro host quanto pro
        // cliente. Agora ele reaproveita o mesmo diálogo de desconexão, só com
        // uma mensagem própria que não sugere "o outro jogador saiu".
        if (connectionStatus == ConnectionStatus.OPPONENT_DISCONNECTED ||
            connectionStatus == ConnectionStatus.HOST_DISCONNECTED ||
            connectionStatus == ConnectionStatus.ERROR) {
            DisconnectDialog(
                message = when (connectionStatus) {
                    ConnectionStatus.HOST_DISCONNECTED -> "O host perdeu a conexão."
                    ConnectionStatus.ERROR -> "A conexão com a sala falhou."
                    else -> "Um oponente saiu da partida."
                },
                isClient = !isHost,
                onBack = {
                    // O jogador escolheu desistir em vez de tentar reconectar --
                    // mesma limpeza das outras saidas, senao esse jogo abandonado
                    // continuaria oferecendo "Continuar Partida Salva" depois.
                    viewModel.clearGameSnapshot()
                    networkRepository.stopHosting()
                    networkRepository.disconnect()
                    onLeaveMatch()
                },
                onWait = { networkRepository.resetConnectionStatus() },
                onReconnect = {
                    networkRepository.reconnect()
                    networkRepository.resetConnectionStatus()
                }
            )
        }

        // ── Diálogo de Reinício de Partida (consentimento de todos) ─────────────────────────────────
        // Tem prioridade sobre o resumo: quem recebe o pedido vê o convite no lugar do resumo antigo,
        // e volta a ver o resumo automaticamente se recusar ou se o host cancelar.
        if (state.showRestartMatchDialog) {
            RestartMatchDialog(
                onConfirm = { viewModel.requestRestartMatch() },
                onDecline = { viewModel.declineRestartMatch() }
            )
        } else state.roundEndDetails?.takeIf { state.showRoundEndDialog }?.let { details ->
            // ── Diálogo de Fim de Rodada / Partida ──────────────────────────────────────────────────
            RoundEndDialog(
                details = details,
                config = state.config,
                isHost = isHost,
                onNextRound = { viewModel.nextRound() },
                onRequestRestart = { viewModel.requestRestartMatch() },
                onLeave = {
                    if (details.isMatchOver) {
                        (context as? Activity)?.let { PostMatchInterstitialAd.showIfReady(it) }
                    }
                    // A mesma limpeza que o botao "Sair" da barra superior ja fazia --
                    // sem isso, uma partida que terminou de verdade (ou foi abandonada
                    // aqui) continuava oferecendo "Continuar Partida Salva" no menu com
                    // um retrato incompleto (o ultimo salvo antes do dialogo de fim de
                    // rodada aparecer, nao o resultado final).
                    viewModel.clearGameSnapshot()
                    networkRepository.stopHosting()
                    networkRepository.disconnect()
                    onLeaveMatch()
                }
            )
        }

        // -- Diálogo de Seleção de Jogo Destino ------------------------------------------------------
        state.pendingMeldTargets?.let { targets ->
            val canMeldNew = if (state.selectedCards.size >= 3) {
                GameRulesEngine.validateMeld(state.selectedCards.toList(), state.config, state.turnCard).isValid
            } else {
                false
            }
            MeldTargetSelectionDialog(
                selectedCards = state.selectedCards.toList(),
                eligibleMeldIndices = targets,
                myTableMelds = state.myTableMelds,
                config = state.config,
                turnCard = state.turnCard,
                onMeldSelected = { index -> viewModel.meldSelectedCards(chosenTargetIndex = index) },
                onMeldNew = { viewModel.meldSelectedCards(chosenTargetIndex = -1) },
                canMeldNew = canMeldNew,
                onDismiss = { viewModel.cancelMeldTargetSelection() }
            )
        }
    }
}

// ── Diálogo de Fim de Rodada ──────────────────────────────────────────────────────────────────────
@Composable
private fun FeltAmbientMotion(modifier: Modifier = Modifier) {
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
private fun DealingAnimation(
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
private fun MeldSparkleBurst(
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
private fun MortoNoticeOverlay(
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
private fun VictoryConfetti(visible: Boolean) {
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

@Composable
private fun RoundEndDialog(
    details: RoundEndDetails,
    config: MatchConfig,
    isHost: Boolean,
    onNextRound: () -> Unit,
    onRequestRestart: () -> Unit,
    onLeave: () -> Unit
) {
    var restartRequested by remember(details) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        containerColor = MenuColors.Ink,
        shape = MenuShapes.Card,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (details.isMatchOver) "🏆 PARTIDA ENCERRADA!" else "🎴 FIM DE RODADA",
                    color = if (details.isMatchOver) MenuColors.Gold else Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (details.winnerName == "Contagem") "Contagem" else "Vencedor: ${details.winnerName}",
                    color = MenuColors.TableGreenLight,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Detalhamento da rodada em formato de tabela para ficar fácil conferir.
                if (details.breakdown.isNotBlank()) {
                    RoundBreakdownTable(breakdown = details.breakdown)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Placar acumulado — orientado pela perspectiva local (localTeam).
                // Botei num cartão próprio com um "×" central pra ficar claro que é um
                // confronto entre dois lados, em vez de uma linha solta perdida no meio.
                val isTeamMode = config.maxPlayers == 4
                val opponentSideLabel = if (details.opponentLabel == "Máquina") "Lado da Máquina" else "Lado do Oponente"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), MenuShapes.Card)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Placar acumulado",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTeamMode) {
                            // Modo 4 jogadores: exibe Equipe A / Equipe B pelos índices absolutos
                            val teamScores = details.teamScores
                            if (teamScores.size >= 2) {
                                val leftIsLocal = details.localTeam == 0
                                ScoreColumn(
                                    label = if (leftIsLocal) "Seu lado (Equipe A)" else "$opponentSideLabel (Equipe A)",
                                    score = teamScores[0],
                                    limit = config.pointLimit,
                                    gameType = config.gameType,
                                    isWinner = details.winnerTeam == 0
                                )
                                Text("×", color = Color.White.copy(alpha = 0.28f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                ScoreColumn(
                                    label = if (!leftIsLocal) "Seu lado (Equipe B)" else "$opponentSideLabel (Equipe B)",
                                    score = teamScores[1],
                                    limit = config.pointLimit,
                                    gameType = config.gameType,
                                    isWinner = details.winnerTeam == 1
                                )
                            }
                        } else {
                            // Modo 2 jogadores: usa myNewTotal/opponentNewTotal já orientados localmente
                            ScoreColumn(
                                label = details.myLabel,
                                score = details.myNewTotal,
                                limit = config.pointLimit,
                                gameType = config.gameType,
                                isWinner = details.winnerTeam != null && details.winnerTeam == details.localTeam
                            )
                            Text("×", color = Color.White.copy(alpha = 0.28f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            ScoreColumn(
                                label = details.opponentLabel,
                                score = details.opponentNewTotal,
                                limit = config.pointLimit,
                                gameType = config.gameType,
                                isWinner = details.winnerTeam != null && details.winnerTeam != details.localTeam
                            )
                        }
                    }
                }

                if (details.isMatchOver) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(MenuColors.Gold.copy(alpha = 0.22f), MenuColors.Gold.copy(alpha = 0.10f))
                                ),
                                MenuShapes.Card
                            )
                            .border(1.dp, MenuColors.Gold.copy(alpha = 0.4f), MenuShapes.Card)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🎉 ${details.winnerName} venceu a partida!",
                            color = MenuColors.Gold,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!details.isMatchOver) {
                Button(
                    onClick = onNextRound,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▶  Próxima Rodada", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Voltar ao Menu", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!details.isMatchOver) {
                TextButton(onClick = onLeave) {
                    Text("Sair", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (isHost) {
                // So o host propoe a revanche; os demais confirmam pelo RestartMatchDialog
                // quando o pedido chegar (fluxo de consentimento ja existente no ViewModel).
                TextButton(
                    onClick = {
                        restartRequested = true
                        onRequestRestart()
                    },
                    enabled = !restartRequested
                ) {
                    Text(
                        if (restartRequested) "Aguardando jogadores..." else "🔁  Jogar Novamente",
                        color = if (restartRequested) Color.White.copy(alpha = 0.5f) else MenuColors.Gold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}

private data class BreakdownRow(
    val owner: String,
    val item: String,
    val quantity: String,
    val points: String
)

@Composable
private fun RoundBreakdownTable(breakdown: String) {
    val rows = remember(breakdown) { parseBreakdownRows(breakdown) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), MenuShapes.Card)
            .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Detalhes da contagem",
            color = MenuColors.Gold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        if (rows.isEmpty()) {
            Text(
                text = breakdown,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            return@Column
        }

        BreakdownHeaderRow()
        val ownerGroups = rows.groupBy { it.owner }
        ownerGroups.entries.forEachIndexed { groupIndex, (owner, ownerRows) ->
            // Divisor entre jogador/equipe pra separar visualmente cada grupo da tabela.
            if (groupIndex > 0) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
            Text(
                text = owner,
                color = MenuColors.TableGreenLight,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            ownerRows.forEachIndexed { index, row ->
                BreakdownDataRow(
                    row = row,
                    background = if (index % 2 == 0) Color.White.copy(alpha = 0.045f) else Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun BreakdownHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.18f), MenuShapes.Card)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreakdownCell("Item", weight = 1.45f, isHeader = true)
        BreakdownCell("Qtd.", weight = 0.55f, isHeader = true, alignEnd = true)
        BreakdownCell("Pontos", weight = 0.8f, isHeader = true, alignEnd = true)
    }
}

@Composable
private fun BreakdownDataRow(row: BreakdownRow, background: Color) {
    // A linha de "Total da rodada" fecha cada grupo, entao destaco ela em dourado
    // pra dar pra ver de relance quanto cada lado somou sem ler a tabela inteira.
    val isTotal = row.item.equals("Total da rodada", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isTotal) MenuColors.Gold.copy(alpha = 0.12f) else background, MenuShapes.Card)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreakdownCell(row.item, weight = 1.45f, emphasize = isTotal)
        BreakdownCell(row.quantity.ifBlank { "-" }, weight = 0.55f, alignEnd = true, emphasize = isTotal)
        BreakdownCell(row.points.ifBlank { "-" }, weight = 0.8f, alignEnd = true, emphasize = isTotal)
    }
}

@Composable
private fun RowScope.BreakdownCell(
    text: String,
    weight: Float,
    isHeader: Boolean = false,
    alignEnd: Boolean = false,
    emphasize: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        color = when {
            isHeader -> Color.White.copy(alpha = 0.72f)
            emphasize -> MenuColors.Gold
            else -> Color.White.copy(alpha = 0.9f)
        },
        fontSize = if (isHeader) 11.sp else 12.sp,
        fontWeight = if (isHeader || emphasize) FontWeight.Bold else FontWeight.Medium,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 2,
        lineHeight = 15.sp
    )
}

private fun parseBreakdownRows(breakdown: String): List<BreakdownRow> {
    return breakdown
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line -> parseBreakdownLine(line) }
        .toList()
}

private fun parseBreakdownLine(line: String): BreakdownRow {
    val owner = line.substringBefore(":", missingDelimiterValue = "Rodada").trim()
    val body = line.substringAfter(":", line).trim()
    val item = when {
        body.startsWith("Pontuação das cartas") -> "Regra das cartas"
        body.startsWith("mesa", ignoreCase = true) -> "Cartas na mesa"
        body.startsWith("canastras", ignoreCase = true) -> "Canastras"
        body.startsWith("3 vermelhos", ignoreCase = true) -> "3 vermelhos"
        body.startsWith("mão", ignoreCase = true) -> "Cartas na mão"
        body.startsWith("3 pretos", ignoreCase = true) -> "3 pretos na mão"
        body.startsWith("morto", ignoreCase = true) -> "Morto"
        body.startsWith("bonus", ignoreCase = true) -> "Bônus de bate"
        body.startsWith("total", ignoreCase = true) -> "Total da rodada"
        else -> body.substringBefore("=").trim().ifBlank { body }
    }
    return BreakdownRow(
        owner = owner,
        item = item,
        quantity = extractBreakdownQuantity(body),
        points = extractBreakdownPoints(body)
    )
}

private fun extractBreakdownQuantity(body: String): String {
    val cardCount = Regex("""(\d+)\s+carta\(s\)""").find(body)?.groupValues?.getOrNull(1)
    if (cardCount != null) return cardCount
    val cleanDirty = Regex("""limpas\s+(\d+),\s+sujas\s+(\d+)""").find(body)?.groupValues
    if (cleanDirty != null && cleanDirty.size >= 3) return "L ${cleanDirty[1]} / S ${cleanDirty[2]}"
    val threeCount = Regex("""3\s+\w+\s+(\d+)""").find(body)?.groupValues?.getOrNull(1)
    if (threeCount != null) return threeCount
    return ""
}

private fun extractBreakdownPoints(body: String): String {
    val afterEquals = body.substringAfter("=", missingDelimiterValue = "").trim()
    if (afterEquals.isNotBlank()) return afterEquals
    val explicitPoints = Regex("""([+-]?\d+\s*pts?)""").find(body)?.groupValues?.getOrNull(1)
    if (explicitPoints != null) return explicitPoints
    val penalty = Regex("""([+-]\d+)""").find(body)?.groupValues?.getOrNull(1)
    return penalty ?: ""
}

@Composable
private fun ScoreColumn(
    label: String,
    score: Int,
    limit: Int,
    gameType: GameType,
    isWinner: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isWinner) "👑 $label" else label,
            color = if (isWinner) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.6f),
            fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            text = if (gameType == GameType.CACHETA) "$score ❤️" else "$score pts",
            color = if (isWinner) MenuColors.TableGreenLight else Color.White,
            fontWeight = if (isWinner) FontWeight.ExtraBold else FontWeight.Medium,
            fontSize = 22.sp
        )
        if (gameType != GameType.CACHETA) {
            Text(
                text = "de $limit pts",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp
            )
        }
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(state: GameState, config: MatchConfig, onLeave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sair
        TextButton(onClick = onLeave) {
            Text("Sair", color = Color.White, fontWeight = FontWeight.Bold)
        }

        // Fase / Status central. A cor troca com uma transicao curta (em vez de
        // recompor direto) pra virada de turno ficar visualmente clara sem
        // chamar atencao demais nem atrasar a jogada.
        val (targetPhaseColor, phaseText) = when (state.turnPhase) {
            TurnPhase.DRAW -> ColorGreenLight to "Compre uma carta"
            TurnPhase.ACTION -> ColorGold to "Baixe ou descarte"
            TurnPhase.WAITING_OPPONENT -> Color.LightGray to "Turno do oponente"
        }
        val phaseColor by animateColorAsState(
            targetValue = targetPhaseColor,
            animationSpec = MenuMotion.quick(),
            label = "turn_phase_color"
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = config.gameType.name,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Text(
                text = phaseText,
                color = phaseColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            if (config.maxPlayers > 2) {
                Text(
                    text = "Você: J${state.playerSeat + 1} - Vez: J${state.activeSeat + 1}",
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 10.sp
                )
            }
        }

        // Placar + Mortos
        Column(horizontalAlignment = Alignment.End) {
            // Placar
            val teamScores = state.teamScores
            val scoreLabel = if (teamScores.size >= 2) {
                if (config.gameType == GameType.CACHETA) {
                    "${teamScores[0]} ❤️ vs ${teamScores[1]} ❤️"
                } else if (config.maxPlayers == 4) {
                    "A ${teamScores[0]} x B ${teamScores[1]}"
                } else {
                    "${teamScores[0]} x ${teamScores[1]}"
                }
            } else {
                val myScore = state.myScore
                val oppScore = state.opponentScore
                if (config.gameType == GameType.CACHETA)
                    "$myScore ❤️ vs $oppScore ❤️"
                else
                    "$myScore / ${config.pointLimit}"
            }
            Text(
                text = scoreLabel,
                color = ColorGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            // Mortos (Buraco/Tranca)
            if (config.gameType != GameType.CACHETA) {
                Text(
                    text = "Mortos: ${state.mortosLeft}",
                    color = if (state.mortosLeft > 0) Color.White.copy(alpha = 0.7f) else Color.Gray,
                    fontSize = 11.sp
                )
            }
            OpponentTopCounter(
                count = state.opponentHandCount,
                opponentPickedMorto = state.opponentPickedMorto,
                label = state.opponentLabel
            )
        }
    }
}

@Composable
private fun OpponentTopCounter(
    count: Int,
    opponentPickedMorto: Boolean,
    label: String
) {
    val shortLabel = when (label) {
        "Máquina" -> "Máquina"
        else -> "Oponente"
    }
    Row(
        modifier = Modifier
            .padding(top = 3.dp)
            .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = shortLabel,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = "$count cartas",
            color = ColorGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        if (opponentPickedMorto) {
            Text(
                text = "+M",
                color = ColorBlueLight,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

// -- Preview Estático (LocalInspectionMode) ------------------------------------------------------
// Cenários usados só nos @Preview no fim do arquivo, pra exercitar mesa vazia,
// mesa cheia (grade/leque sob pressão) e feedback de erro sem ViewModel real.
private enum class MatchPreviewScenario { STANDARD, EMPTY, FULL_TABLE, ERROR }

@Composable
private fun MatchScreenStaticPreview(
    config: MatchConfig,
    scenario: MatchPreviewScenario = MatchPreviewScenario.STANDARD
) {
    val sampleHand = remember(scenario) {
        when (scenario) {
            MatchPreviewScenario.FULL_TABLE -> listOf(
                Card(Suit.HEARTS, Rank.ACE), Card(Suit.SPADES, Rank.KING), Card(Suit.DIAMONDS, Rank.QUEEN),
                Card(Suit.CLUBS, Rank.JACK), Card(Suit.HEARTS, Rank.TEN), Card(Suit.SPADES, Rank.NINE),
                Card(Suit.DIAMONDS, Rank.EIGHT), Card(Suit.CLUBS, Rank.SEVEN), Card(Suit.HEARTS, Rank.SIX),
                Card(Suit.SPADES, Rank.FIVE), Card(Suit.DIAMONDS, Rank.FOUR), Card(Suit.CLUBS, Rank.THREE),
                Card(Suit.HEARTS, Rank.TWO)
            )
            MatchPreviewScenario.EMPTY -> listOf(
                Card(Suit.HEARTS, Rank.ACE), Card(Suit.SPADES, Rank.KING), Card(Suit.DIAMONDS, Rank.QUEEN)
            )
            MatchPreviewScenario.STANDARD, MatchPreviewScenario.ERROR -> listOf(
                Card(Suit.HEARTS, Rank.ACE), Card(Suit.SPADES, Rank.KING),
                Card(Suit.DIAMONDS, Rank.QUEEN), Card(Suit.CLUBS, Rank.JACK),
                Card(Suit.HEARTS, Rank.TEN), Card(Suit.SPADES, Rank.NINE),
                Card(Suit.DIAMONDS, Rank.EIGHT), Card(Suit.CLUBS, Rank.SEVEN),
                Card(Suit.HEARTS, Rank.SIX), Card(Suit.SPADES, Rank.FIVE),
                Card(Suit.DIAMONDS, Rank.FOUR)
            )
        }
    }
    val sampleMyMelds = remember(scenario) {
        when (scenario) {
            MatchPreviewScenario.EMPTY -> emptyList()
            MatchPreviewScenario.FULL_TABLE -> listOf(
                listOf(Card(Suit.HEARTS, Rank.THREE), Card(Suit.HEARTS, Rank.FOUR), Card(Suit.HEARTS, Rank.FIVE), Card(Suit.HEARTS, Rank.SIX), Card(Suit.HEARTS, Rank.SEVEN), Card(Suit.HEARTS, Rank.EIGHT), Card(Suit.HEARTS, Rank.NINE)),
                listOf(Card(Suit.SPADES, Rank.JACK), Card(Suit.CLUBS, Rank.JACK), Card(Suit.HEARTS, Rank.JACK)),
                listOf(Card(Suit.DIAMONDS, Rank.SEVEN), Card(Suit.DIAMONDS, Rank.EIGHT), Card(Suit.DIAMONDS, Rank.NINE), Card(Suit.DIAMONDS, Rank.TEN)),
                listOf(Card(Suit.CLUBS, Rank.FOUR), Card(Suit.CLUBS, Rank.FIVE), Card(Suit.CLUBS, Rank.SIX)),
                listOf(Card(Suit.SPADES, Rank.TWO), Card(Suit.SPADES, Rank.THREE), Card(Suit.SPADES, Rank.FOUR)),
                listOf(Card(Suit.HEARTS, Rank.QUEEN), Card(Suit.CLUBS, Rank.QUEEN), Card(Suit.DIAMONDS, Rank.QUEEN))
            )
            MatchPreviewScenario.STANDARD, MatchPreviewScenario.ERROR -> listOf(
                listOf(Card(Suit.HEARTS, Rank.THREE), Card(Suit.HEARTS, Rank.FOUR), Card(Suit.HEARTS, Rank.FIVE), Card(Suit.HEARTS, Rank.SIX), Card(Suit.HEARTS, Rank.SEVEN)),
                listOf(Card(Suit.SPADES, Rank.JACK), Card(Suit.CLUBS, Rank.JACK), Card(Suit.HEARTS, Rank.JACK))
            )
        }
    }
    val sampleOppMelds = remember(scenario) {
        when (scenario) {
            MatchPreviewScenario.EMPTY -> emptyList()
            MatchPreviewScenario.FULL_TABLE -> listOf(
                listOf(Card(Suit.DIAMONDS, Rank.TWO), Card(Suit.DIAMONDS, Rank.THREE), Card(Suit.DIAMONDS, Rank.FOUR)),
                listOf(Card(Suit.CLUBS, Rank.EIGHT), Card(Suit.CLUBS, Rank.NINE), Card(Suit.CLUBS, Rank.TEN)),
                listOf(Card(Suit.SPADES, Rank.SIX), Card(Suit.SPADES, Rank.SEVEN), Card(Suit.SPADES, Rank.EIGHT), Card(Suit.SPADES, Rank.NINE)),
                listOf(Card(Suit.HEARTS, Rank.KING), Card(Suit.CLUBS, Rank.KING), Card(Suit.DIAMONDS, Rank.KING))
            )
            MatchPreviewScenario.STANDARD, MatchPreviewScenario.ERROR -> listOf(
                listOf(Card(Suit.DIAMONDS, Rank.TWO), Card(Suit.DIAMONDS, Rank.THREE), Card(Suit.DIAMONDS, Rank.FOUR))
            )
        }
    }
    val sampleDiscardPile: List<Card> = if (scenario == MatchPreviewScenario.EMPTY) {
        emptyList()
    } else {
        listOf(Card(Suit.CLUBS, Rank.KING))
    }
    val previewFeedback = when (scenario) {
        MatchPreviewScenario.EMPTY -> "\uD83C\uDFB4 Preview \u2014 ${config.gameType.name} | Aguardando a primeira jogada"
        MatchPreviewScenario.FULL_TABLE -> "\uD83D\uDD25 Preview \u2014 mesa cheia, confira a rolagem da grade"
        MatchPreviewScenario.ERROR -> "\u274C Jogada inv\u00E1lida \u2014 selecione cartas do mesmo valor"
        MatchPreviewScenario.STANDARD -> "\uD83C\uDFB4 Preview \u2014 ${config.gameType.name} | Sua vez!"
    }
    val statusText = when (scenario) {
        MatchPreviewScenario.ERROR -> "Jogada inv\u00E1lida"
        MatchPreviewScenario.EMPTY -> "Aguarde a 1\u00AA jogada"
        MatchPreviewScenario.FULL_TABLE, MatchPreviewScenario.STANDARD -> "Baixe ou descarte"
    }
    val statusColor = if (scenario == MatchPreviewScenario.ERROR) ColorLockRed else ColorGold
    val fakeState = GameState(
        myHand = sampleHand,
        myTableMelds = sampleMyMelds,
        opponentTableMelds = sampleOppMelds,
        discardPile = sampleDiscardPile,
        deckSize = when (scenario) {
            MatchPreviewScenario.EMPTY -> 78
            MatchPreviewScenario.FULL_TABLE -> 4
            else -> 42
        },
        mortosLeft = if (config.gameType != GameType.CACHETA) {
            if (scenario == MatchPreviewScenario.FULL_TABLE) 0 else 2
        } else 0,
        playerSeat = 0,
        activeSeat = 0,
        teamScores = listOf(450, 200),
        turnPhase = TurnPhase.ACTION,
        feedbackMessage = previewFeedback,
        isDiscardLocked = scenario == MatchPreviewScenario.ERROR,
        canDrawFromDiscard = scenario != MatchPreviewScenario.ERROR,
        drawDiscardBlockedReason = if (scenario == MatchPreviewScenario.ERROR) "Lixo bloqueado neste turno" else "",
        config = config
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B4A1E)))

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            // TopBar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorSurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sair", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(config.gameType.name, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 300.dp)) {

                    Text("A 450 x B 200", color = ColorGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (config.gameType != GameType.CACHETA)
                        Text("Mortos: ${fakeState.mortosLeft}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }

            // Mesa central
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1.18f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MeldArea(
                        title = "Mesa do Oponente",
                        melds = fakeState.opponentTableMelds,
                        accentColor = ColorRedLight,
                        emptyText = "Nenhum jogo baixado pelo oponente",
                        modifier = Modifier.weight(0.92f),
                        prominent = false,
                        gameType = config.gameType
                    )
                    MeldArea(
                        title = "Minha Mesa",
                        melds = fakeState.myTableMelds,
                        accentColor = ColorGreenLight,
                        emptyText = "Baixe jogos aqui",
                        modifier = Modifier.weight(1.18f),
                        prominent = true,
                        gameType = config.gameType
                    )
                }
                DrawPilesPanel(
                    state = fakeState,
                    config = config,
                    modifier = Modifier.weight(0.82f).fillMaxHeight(),
                    onDeckClick = {},
                    onDiscardClick = {},
                    onBlockedDiscardClick = {}
                )
            }

            // Mão do jogador no preview.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.56f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Mão - ${sampleHand.size} cartas",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = ColorRed),
                            modifier = Modifier.height(42.dp).padding(end = 20.dp)
                        ) { Text("Descartar", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
                            modifier = Modifier.height(42.dp).padding(end = 300.dp)
                        ) { Text("Baixar Jogo", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
                // Cartas centralizadas na tela — Row com scroll horizontal centrado
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    sampleHand.forEachIndexed { index, card ->
                        val offset = if (index > 0) (-30).dp else 0.dp
                        Box(modifier = Modifier.offset(x = offset * index)) {
                            CardView(
                                card = card,
                                isFaceUp = true,
                                modifier = Modifier.size(width = 60.dp, height = 92.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Centro da Mesa ────────────────────────────────────────────────────────────────────────────────
@Composable
private fun TableCenter(
    state: GameState,
    config: MatchConfig,
    viewModel: MatchViewModel,
    feedback: MatchFeedback
) {
    // Centro da mesa: jogos baixados, monte, lixo e mortos.
    // Bloco responsivo para fonte grande/tela pequena, sem regra de jogo misturada.
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        val landscapeTable = maxWidth >= 680.dp && maxWidth > maxHeight
        val compactTable = !landscapeTable && (maxWidth < 560.dp || maxHeight < 360.dp)
        val shortLandscapeTable = maxHeight < 380.dp
        val pilePanelHeight = if (maxHeight < 330.dp) 146.dp else 176.dp
        val centerPanelWidth = when {
            maxWidth < 760.dp -> 214.dp
            maxWidth < 980.dp -> 232.dp
            else -> 252.dp
        }
        val opponentSideTitle = if (state.opponentLabel == "Máquina") "Lado da Máquina" else "Lado do Oponente"
        val mySideTitle = if (config.maxPlayers == 4) "Seu lado (equipe)" else "Seu lado"
        val opponentEmptyText = if (state.opponentLabel == "Máquina") {
            "A máquina ainda não baixou jogo"
        } else {
            "Nenhum jogo baixado pelo lado adversário"
        }
        val myEmptyText = "Baixe jogos para eles aparecerem no seu lado"

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = state.feedbackMessage,
                color = Color.White,
                fontSize = if (compactTable) 11.sp else 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp)
                    .padding(horizontal = 10.dp, vertical = 1.dp)
            )

            if (landscapeTable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MeldArea(
                        title = opponentSideTitle,
                        melds = state.opponentTableMelds,
                        accentColor = ColorRedLight,
                        emptyText = opponentEmptyText,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        prominent = true,
                        gameType = config.gameType
                    )

                    DrawPilesPanel(
                        state = state,
                        config = config,
                        modifier = Modifier
                            .width(centerPanelWidth)
                            .fillMaxHeight()
                            .heightIn(min = 148.dp),
                        compact = shortLandscapeTable,
                        onDeckClick = {
                            feedback.play(FeedbackCue.Draw)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.drawFromDeck()
                        },
                        onDiscardClick = {
                            feedback.play(FeedbackCue.Draw)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.drawFromDiscard()
                        },
                        onBlockedDiscardClick = {
                            feedback.play(FeedbackCue.Error)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )

                    MeldArea(
                        title = mySideTitle,
                        melds = state.myTableMelds,
                        accentColor = ColorGreenLight,
                        emptyText = myEmptyText,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        prominent = true,
                        gameType = config.gameType
                    )
                }
            } else if (compactTable) {
                MeldArea(
                    title = opponentSideTitle,
                    melds = state.opponentTableMelds,
                    accentColor = ColorRedLight,
                    emptyText = opponentEmptyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    prominent = false,
                    gameType = config.gameType
                )

                DrawPilesPanel(
                    state = state,
                    config = config,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pilePanelHeight),
                    compact = true,
                    onDeckClick = {
                        feedback.play(FeedbackCue.Draw)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.drawFromDeck()
                    },
                    onDiscardClick = {
                        feedback.play(FeedbackCue.Draw)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.drawFromDiscard()
                    },
                    onBlockedDiscardClick = {
                        feedback.play(FeedbackCue.Error)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )

                MeldArea(
                    title = mySideTitle,
                    melds = state.myTableMelds,
                    accentColor = ColorGreenLight,
                    emptyText = myEmptyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    prominent = true,
                    gameType = config.gameType
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.18f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MeldArea(
                            title = opponentSideTitle,
                            melds = state.opponentTableMelds,
                            accentColor = ColorRedLight,
                            emptyText = opponentEmptyText,
                            modifier = Modifier.weight(0.92f),
                            prominent = false,
                            gameType = config.gameType
                        )

                        MeldArea(
                            title = mySideTitle,
                            melds = state.myTableMelds,
                            accentColor = ColorGreenLight,
                            emptyText = myEmptyText,
                            modifier = Modifier.weight(1.18f),
                            prominent = true,
                            gameType = config.gameType
                        )
                    }

                    DrawPilesPanel(
                        state = state,
                        config = config,
                        modifier = Modifier
                            .weight(0.82f)
                            .fillMaxHeight(),
                        onDeckClick = {
                            feedback.play(FeedbackCue.Draw)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.drawFromDeck()
                        },
                        onDiscardClick = {
                            feedback.play(FeedbackCue.Draw)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.drawFromDiscard()
                        },
                        onBlockedDiscardClick = {
                            feedback.play(FeedbackCue.Error)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawPilesPanel(
    state: GameState,
    config: MatchConfig,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onDeckClick: () -> Unit,
    onDiscardClick: () -> Unit,
    onBlockedDiscardClick: () -> Unit
) {
    // Deck/lixo/mortos são pontos de compra. A UI mostra se pode clicar, mas a
    // decisão oficial ainda passa pelo ViewModel e pelo GameRulesEngine.
    BoxWithConstraints(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    listOf(
                        ColorGold.copy(alpha = 0.16f),
                        ColorGreenLight.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.18f)
                    )
                ),
                RoundedCornerShape(18.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val fontScale = LocalDensity.current.fontScale
        val priorityCards = compact || maxHeight < 250.dp || fontScale >= 1.18f
        val ultraCompact = maxHeight < 190.dp || fontScale >= 1.35f
        val tightPanel = priorityCards || ultraCompact
        val compactCardByHeight = ((maxHeight - 8.dp).coerceAtLeast(88.dp)) / 1.5f
        val compactCardByWidth = ((maxWidth - 18.dp) / if (config.gameType == GameType.CACHETA) 3f else 2f)
        val priorityCardWidth = minOf(compactCardByHeight, compactCardByWidth, if (ultraCompact) 74.dp else 88.dp)
            .coerceAtLeast(58.dp)
        val pileCardWidth = if (priorityCards) priorityCardWidth else 74.dp
        val discardCardWidth = if (priorityCards) {
            minOf(priorityCardWidth + 10.dp, compactCardByWidth, if (ultraCompact) 80.dp else 94.dp)
                .coerceAtLeast(priorityCardWidth)
        } else {
            pileCardWidth
        }
        val contentArrangement = Arrangement.spacedBy(if (tightPanel) 6.dp else 8.dp, Alignment.CenterVertically)
        if (priorityCards) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .zIndex(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(if (config.gameType == GameType.CACHETA) 1f else 0.82f),
                        contentAlignment = Alignment.Center
                    ) {
                        CompactPileCard(
                            label = "MONTE",
                            countText = state.deckSize.toString(),
                            card = Card(Suit.SPADES, Rank.ACE),
                            faceUp = false,
                            enabled = state.turnPhase == TurnPhase.DRAW && canAttemptDeckDraw(state, config),
                            active = state.turnPhase == TurnPhase.DRAW,
                            accentColor = ColorGreenLight,
                            cardWidth = pileCardWidth,
                            onClick = onDeckClick
                        )
                    }

                    if (config.gameType == GameType.CACHETA) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            TurnCardPile(turnCard = state.turnCard, cardWidth = pileCardWidth)
                        }
                    }

                    Box(
                        modifier = Modifier.weight(if (config.gameType == GameType.CACHETA) 1f else 1.18f),
                        contentAlignment = Alignment.Center
                    ) {
                        val topDiscard = state.discardPile.lastOrNull()
                        CompactPileCard(
                            label = "LIXO",
                            countText = state.discardPile.size.toString(),
                            card = topDiscard,
                            faceUp = true,
                            enabled = topDiscard != null,
                            active = state.canDrawFromDiscard && state.turnPhase == TurnPhase.DRAW,
                            blocked = topDiscard != null && !state.canDrawFromDiscard &&
                                (state.isDiscardLocked || state.drawDiscardBlockedReason.isNotBlank()),
                            accentColor = if (state.canDrawFromDiscard && state.turnPhase == TurnPhase.DRAW) ColorBlueLight else ColorLockRed,
                            cardWidth = discardCardWidth,
                            onClick = onDiscardClick,
                            onBlockedClick = onBlockedDiscardClick
                        )
                    }
                }

            }
            return@BoxWithConstraints
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = contentArrangement
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = if (ultraCompact) 76.dp else if (tightPanel) 88.dp else 118.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeckPile(
                    deckSize = state.deckSize,
                    isMyTurn = state.turnPhase == TurnPhase.DRAW,
                    canDraw = state.turnPhase == TurnPhase.DRAW && canAttemptDeckDraw(state, config),
                    cardWidth = pileCardWidth,
                    showHint = !priorityCards,
                    priorityCard = priorityCards,
                    onClick = onDeckClick
                )

                if (config.gameType == GameType.CACHETA) {
                    TurnCardPile(turnCard = state.turnCard, cardWidth = pileCardWidth)
                }

                DiscardPile(
                    discardPile = state.discardPile,
                    isLocked = state.isDiscardLocked,
                    canDraw = state.canDrawFromDiscard && state.turnPhase == TurnPhase.DRAW,
                    blockedReason = state.drawDiscardBlockedReason,
                    cardWidth = pileCardWidth,
                    showHint = !priorityCards,
                    priorityCard = priorityCards,
                    onClick = onDiscardClick,
                    onBlockedClick = onBlockedDiscardClick
                )
            }
        }
    }
}

@Composable
private fun MeldArea(
    title: String,
    melds: List<List<Card>>,
    accentColor: Color,
    emptyText: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    gameType: GameType = GameType.TRANCA
) {
    // Jogos na mesa são públicos. O clique abre o leque completo do jogo sem
    // expor cartas privadas de mao, o que continua correto no online.
    var inspectedCards by remember { mutableStateOf<List<Card>?>(null) }

    inspectedCards?.let { cards ->
        MeldInspectorDialog(
            title = title,
            cards = cards,
            onDismiss = { inspectedCards = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = accentColor.copy(alpha = 0.85f),
                fontSize = if (prominent) 14.sp else 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${melds.size} jogo(s)",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = if (prominent) 11.sp else 10.sp
            )
        }
        BoxWithConstraints(
            modifier = Modifier 
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = if (prominent) 104.dp else 88.dp)
                .background(accentColor.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                .padding(5.dp)
        ) {
            if (melds.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 180.dp)
                            .height(42.dp)
                            .border(1.dp, accentColor.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            emptyText,
                            color = Color.White.copy(alpha = 0.42f),
                            fontSize = if (prominent) 11.sp else 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val gap = if (maxWidth < 360.dp) 4.dp else 6.dp
                val longestMeld = melds.maxOfOrNull { it.size } ?: 3
                val columns = tableGridColumnCount(
                    maxWidth = maxWidth,
                    meldCount = melds.size,
                    longestMeld = longestMeld,
                    prominent = prominent,
                    gap = gap
                )
                val cellWidth = ((maxWidth - gap * (columns - 1)) / columns).coerceAtLeast(72.dp)
                val groupCardWidth = tableGridCardWidth(
                    cellWidth = cellWidth,
                    longestMeld = longestMeld,
                    meldCount = melds.size,
                    prominent = prominent
                )
                val rowHeight = (groupCardWidth * 1.5f) + if (prominent) 24.dp else 20.dp
                val rows = melds.chunked(columns)
                val maxVisibleRows = (maxHeight.value / rowHeight.value).toInt().coerceAtLeast(1)
                val useScroll = rows.size > maxVisibleRows
                val gridModifier = if (useScroll) {
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxSize()
                }

                Column(
                    modifier = gridModifier,
                    verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    rows.forEachIndexed { rowIndex, rowMelds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowMelds.forEachIndexed { columnIndex, meld ->
                                val meldIndex = rowIndex * columns + columnIndex
                                key("meld_grid_${meldIndex}_${meld.joinToString("_") { it.id }}") {
                                    Box(
                                        modifier = Modifier
                                            .width(cellWidth)
                                            .heightIn(min = rowHeight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        MeldGroupView(
                                            meld = meld,
                                            prominent = prominent,
                                            gameType = gameType,
                                            cardWidth = groupCardWidth,
                                            onClick = { inspectedCards = meld }
                                        )
                                    }
                                }
                            }
                            repeat(columns - rowMelds.size) {
                                Spacer(
                                    modifier = Modifier
                                        .width(cellWidth)
                                        .height(1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -- Previews de componente: grade de jogos na mesa (cheia, vazia, tela pequena) -------------------
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF123018,
    widthDp = 380,
    heightDp = 240,
    name = "MeldArea - cheia (com canastra)"
)
@Composable
private fun MeldAreaFullPreview() {
    val melds = remember {
        listOf(
            listOf(Card(Suit.HEARTS, Rank.THREE), Card(Suit.HEARTS, Rank.FOUR), Card(Suit.HEARTS, Rank.FIVE), Card(Suit.HEARTS, Rank.SIX), Card(Suit.HEARTS, Rank.SEVEN), Card(Suit.HEARTS, Rank.EIGHT), Card(Suit.HEARTS, Rank.NINE)),
            listOf(Card(Suit.SPADES, Rank.JACK), Card(Suit.CLUBS, Rank.JACK), Card(Suit.HEARTS, Rank.JACK)),
            listOf(Card(Suit.DIAMONDS, Rank.SEVEN), Card(Suit.DIAMONDS, Rank.EIGHT), Card(Suit.DIAMONDS, Rank.NINE), Card(Suit.DIAMONDS, Rank.TEN)),
            listOf(Card(Suit.CLUBS, Rank.FOUR), Card(Suit.CLUBS, Rank.FIVE), Card(Suit.CLUBS, Rank.SIX))
        )
    }
    MaterialTheme {
        MeldArea(
            title = "Minha Mesa",
            melds = melds,
            accentColor = ColorGreenLight,
            emptyText = "Baixe jogos aqui",
            modifier = Modifier.fillMaxWidth().height(220.dp),
            prominent = true,
            gameType = GameType.BURACO
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF123018,
    widthDp = 200,
    heightDp = 160,
    name = "MeldArea - vazia (tela pequena)"
)
@Composable
private fun MeldAreaEmptyCompactPreview() {
    MaterialTheme {
        MeldArea(
            title = "Mesa do Oponente",
            melds = emptyList(),
            accentColor = ColorRedLight,
            emptyText = "Nenhum jogo baixado pelo oponente",
            modifier = Modifier.fillMaxWidth().height(140.dp),
            prominent = false,
            gameType = GameType.TRANCA
        )
    }
}

private fun tableGridColumnCount(
    maxWidth: Dp,
    meldCount: Int,
    longestMeld: Int,
    prominent: Boolean,
    gap: Dp
): Int {
    val preferred = when {
        maxWidth < 220.dp -> 1
        maxWidth < 390.dp -> 2
        maxWidth < 620.dp -> if (prominent) 2 else 3
        maxWidth < 920.dp -> 3
        prominent -> 3
        meldCount <= 6 -> 4
        else -> 3
    }
    var columns = preferred.coerceIn(1, meldCount.coerceAtLeast(1))
    val minimumCardWidth = if (maxWidth < 300.dp) 24.dp else 28.dp
    val minimumOverlap = 6.dp
    val groupPadding = if (prominent) 14.dp else 10.dp
    val requiredCellWidth = minimumCardWidth * longestMeld.toFloat() -
        minimumOverlap * (longestMeld - 1).coerceAtLeast(0).toFloat() + groupPadding

    while (columns > 1) {
        val candidateCellWidth = (maxWidth - gap * (columns - 1)) / columns
        if (candidateCellWidth >= requiredCellWidth) break
        columns--
    }
    return columns
}

private fun tableGridCardWidth(
    cellWidth: Dp,
    longestMeld: Int,
    meldCount: Int,
    prominent: Boolean
): Dp {
    val maxCard = when {
        meldCount >= 8 -> 44.dp
        meldCount >= 5 -> 46.dp
        prominent -> 54.dp
        else -> 48.dp
    }
    val minimumCard = if (cellWidth < 180.dp) 24.dp else 28.dp
    val estimatedNegativeSpacing = 6.dp * (longestMeld - 1).coerceAtLeast(0)
    val horizontalPadding = if (prominent) 14.dp else 10.dp
    val availableForCards = cellWidth - horizontalPadding + estimatedNegativeSpacing
    return (availableForCards / longestMeld.coerceAtLeast(3))
        .coerceIn(minimumCard, maxCard)
}

@Composable
private fun TurnCardPile(turnCard: Card?, cardWidth: Dp = 58.dp) {
    val pulse = rememberPulseAlpha(min = 0.52f, label = "turn_card_pulse")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (turnCard != null) {
            Box(
                modifier = Modifier
                    .shadow(14.dp, RoundedCornerShape(10.dp), clip = false)
                    .background(ColorGold.copy(alpha = 0.16f * pulse), RoundedCornerShape(10.dp))
                    .border(2.dp, ColorGold.copy(alpha = pulse), RoundedCornerShape(9.dp))
                    .padding(3.dp)
            ) {
                CardView(
                    card = turnCard,
                    isFaceUp = true,
                    modifier = Modifier.size(width = cardWidth, height = cardWidth * 1.5f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardWidth * 1.5f)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("Vira", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("VIRA", color = ColorGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (turnCard != null) {
            Text(
                "Curinga: ${GameRulesEngine.getCachetaWildcardRank(turnCard).name}",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CompactPileCard(
    label: String,
    countText: String,
    card: Card?,
    faceUp: Boolean,
    enabled: Boolean,
    active: Boolean,
    accentColor: Color,
    cardWidth: Dp,
    blocked: Boolean = false,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit = {}
) {
    val pulse = rememberPulseAlpha(min = 0.62f, label = "compact_pile_pulse_$label")
    val glowColor = when {
        active -> accentColor
        blocked -> ColorLockRed
        else -> Color.White.copy(alpha = 0.16f)
    }
    Box(
        modifier = Modifier
            .size(width = cardWidth, height = cardWidth * 1.5f)
            .shadow(if (active || blocked) 16.dp else 5.dp, RoundedCornerShape(9.dp), clip = false)
            .background(
                if (active || blocked) glowColor.copy(alpha = 0.14f * pulse) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .border(
                width = if (active || blocked) 2.dp else 1.dp,
                color = if (active || blocked) glowColor.copy(alpha = pulse) else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(9.dp)
            )
            .padding(if (active || blocked) 3.dp else 1.dp)
            .clickable(enabled = enabled) {
                if (blocked) onBlockedClick() else onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (card != null) {
            CardView(
                card = card,
                isFaceUp = faceUp,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = countText,
                color = ColorGold,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }

        if (blocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.34f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "X",
                    color = ColorLockRed,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

// ─── Monte (Deck) ─────────────────────────────────────────
@Composable
private fun DeckPile(
    deckSize: Int,
    isMyTurn: Boolean,
    canDraw: Boolean,
    cardWidth: Dp = 54.dp,
    showHint: Boolean = true,
    priorityCard: Boolean = false,
    onClick: () -> Unit
) {
    val pulse = rememberPulseAlpha(min = 0.65f, label = "deck_pulse")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "MONTE",
            color = ColorGreenLight,
            fontSize = if (priorityCard) 8.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(if (priorityCard) 1.dp else 3.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .shadow(if (isMyTurn) 16.dp else 5.dp, RoundedCornerShape(8.dp), clip = false)
                .background(
                    if (isMyTurn) ColorGreenLight.copy(alpha = 0.18f * pulse) else Color.Transparent,
                    RoundedCornerShape(10.dp)
                )
                .padding(if (isMyTurn) 3.dp else 0.dp)
                .border(
                    width = if (isMyTurn) 2.dp else 0.dp,
                    color = if (isMyTurn) ColorGreenLight.copy(alpha = pulse) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .alpha(if (deckSize > 0 || canDraw) 1f else 0.4f)
                .clickable(enabled = canDraw) { onClick() }
        ) {
            if (deckSize > 0) {
                CardView(
                    card = com.brunogiovani.cachetaburaco.domain.models.Card(
                        com.brunogiovani.cachetaburaco.domain.models.Suit.SPADES,
                        com.brunogiovani.cachetaburaco.domain.models.Rank.ACE
                    ),
                    isFaceUp = false,
                    modifier = Modifier.size(width = cardWidth, height = cardWidth * 1.5f)
                )
            } else {
                Box(modifier = Modifier.width(cardWidth).height(cardWidth * 1.5f)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)))
            }
            if (priorityCard) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(999.dp))
                        .border(1.dp, ColorGreenLight.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (deckSize > 0) deckSize.toString() else "0",
                        color = ColorGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }
        if (!priorityCard) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (deckSize > 0) "$deckSize cartas" else "Vazio",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        if (canDraw && showHint) {
            Text(
                if (deckSize > 0) "Toque para comprar" else "Toque para resolver",
                color = ColorGreenLight,
                fontSize = 10.sp,
                maxLines = 1
            )
        } else if (isMyTurn && showHint) {
            Text("Indisponível", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

private fun canAttemptDeckDraw(state: GameState, config: MatchConfig): Boolean {
    if (state.deckSize > 0) return true
    return when (config.gameType) {
        GameType.CACHETA -> state.discardPile.size > 1
        GameType.BURACO -> state.mortosLeft > 0
        GameType.TRANCA -> true
    }
}

// ─── Lixo (Discard) ───────────────────────────────────────
@Composable
private fun DiscardPile(
    discardPile: List<Card>,
    isLocked: Boolean,
    canDraw: Boolean,
    blockedReason: String,
    cardWidth: Dp = 54.dp,
    showHint: Boolean = true,
    priorityCard: Boolean = false,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit
) {
    val topCard = discardPile.lastOrNull()
    val isBlocked = topCard != null && !canDraw && (isLocked || blockedReason.isNotBlank())
    val statusColor = when {
        canDraw -> ColorBlueLight
        isBlocked -> ColorLockRed
        else -> Color.Transparent
    }
    val pulse = rememberPulseAlpha(min = 0.65f, label = "discard_pulse")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "LIXO",
            color = if (canDraw) ColorBlueLight else Color.White.copy(alpha = 0.72f),
            fontSize = if (priorityCard) 8.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(if (priorityCard) 1.dp else 3.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .shadow(if (canDraw || isBlocked) 18.dp else 5.dp, RoundedCornerShape(8.dp), clip = false)
                .background(
                    if (canDraw || isBlocked) {
                        Brush.radialGradient(
                            listOf(
                                statusColor.copy(alpha = 0.24f * pulse),
                                statusColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    } else {
                        Brush.radialGradient(listOf(Color.Transparent, Color.Transparent))
                    },
                    RoundedCornerShape(10.dp)
                )
                .padding(if (canDraw || isBlocked) 4.dp else 0.dp)
                .border(
                    width = if (canDraw || isBlocked) 2.dp else 0.dp,
                    color = if (canDraw || isBlocked) statusColor.copy(alpha = pulse) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(enabled = topCard != null) {
                    if (canDraw) onClick() else onBlockedClick()
                }
        ) {
            if (topCard != null) {
                CardView(
                    card = topCard,
                    isFaceUp = true,
                    modifier = Modifier.size(width = cardWidth, height = cardWidth * 1.5f)
                )
                if (isBlocked) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = if (isLocked) "LOCK" else "X",
                            color = ColorLockRed,
                            fontSize = if (isLocked) 16.sp else 30.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.width(cardWidth).height(cardWidth * 1.5f)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Lixo", color = Color.White.copy(alpha = 0.4f), fontSize = if (priorityCard) 8.sp else 12.sp, maxLines = 1)
                }
            }
            if (priorityCard) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(999.dp))
                        .border(1.dp, statusColor.copy(alpha = if (topCard != null) 0.55f else 0.22f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = discardPile.size.toString(),
                        color = if (discardPile.isNotEmpty()) ColorGold else Color.White.copy(alpha = 0.65f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }
        val pileCount = discardPile.size
        if (!priorityCard) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (pileCount > 0) "$pileCount carta(s)" else "Vazio",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        when {
            isLocked && showHint -> Text(blockedReason, color = ColorLockRed, fontSize = 10.sp, maxLines = 1)
            canDraw && showHint -> Text("Toque para comprar", color = ColorBlueLight, fontSize = 10.sp, maxLines = 1)
        }
    }
}

// ─── Grupo de Melds na Mesa ───────────────────────────────
@Composable
private fun MeldGroupView(
    meld: List<Card>,
    prominent: Boolean = false,
    gameType: GameType = GameType.TRANCA,
    cardWidth: Dp = if (prominent) 58.dp else 50.dp,
    onClick: () -> Unit
) {
    val isCanastra = meld.size >= 7
    val isCleanCanastra = isCanastra && meld.none { GameRulesEngine.isWildcard(it, gameType) }
    val canastraColor = if (isCleanCanastra) ColorGold else Color(0xFFB0B0B0)
    val borderColor = if (isCanastra) canastraColor else ColorGreenLight
    val pulse = rememberPulseAlpha(min = 0.55f, label = "meld_group_pulse")

    Box(
        modifier = Modifier
            .shadow(if (isCanastra) 14.dp else 8.dp, RoundedCornerShape(10.dp), clip = false)
            .clickable { onClick() }
            .background(
                if (isCanastra) {
                    Brush.radialGradient(
                        listOf(
                            canastraColor.copy(alpha = 0.22f * pulse),
                            canastraColor.copy(alpha = 0.07f),
                            Color.Transparent
                        )
                    )
                } else {
                    Brush.radialGradient(
                        listOf(
                            ColorGreenLight.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                },
                RoundedCornerShape(10.dp)
            )
            .border(if (isCanastra) 2.dp else 1.dp, borderColor.copy(alpha = if (isCanastra) pulse else 0.85f), RoundedCornerShape(8.dp))
            .padding(if (prominent) 7.dp else 5.dp)
    ) {
        val cardHeight = cardWidth * 1.5f
        val spacing = when {
            cardWidth <= 36.dp -> (-6).dp
            cardWidth <= 42.dp -> (-7).dp
            prominent -> (-10).dp
            else -> (-8).dp
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(cardHeight + 4.dp)
        ) {
            meld.forEach { card ->
                CardView(
                    card = card,
                    isFaceUp = true,
                    modifier = Modifier
                        .size(width = cardWidth, height = cardHeight)
                        .shadow(4.dp, RoundedCornerShape(9.dp), clip = false)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "${meld.size}",
                color = Color.White,
                fontSize = if (prominent) 13.sp else 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (isCanastra) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                    .border(1.dp, canastraColor.copy(alpha = pulse), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isCleanCanastra) "Limpa" else "Suja",
                    color = canastraColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// --- Mão do Jogador ---------------------------------------
@Composable
private fun MeldInspectorDialog(
    title: String,
    cards: List<Card>,
    onDismiss: () -> Unit
) {
    val openProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "meld_inspector_open"
    )
    val glow = rememberPulseAlpha(min = 0.45f, label = "meld_inspector_glow")
    val centerIndex = (cards.lastIndex.coerceAtLeast(0)) / 2f

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xEE101820),
            shape = MenuShapes.Card,
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MenuColors.Gold.copy(alpha = 0.45f * glow), MenuShapes.Card)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${cards.size} cartas",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Fechar", color = MenuColors.Gold)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MenuColors.Gold.copy(alpha = 0.14f * glow),
                                    MenuColors.TableGreenLight.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            ),
                            MenuShapes.Card
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    cards.forEachIndexed { index, card ->
                        val distance = index - centerIndex
                        val cappedDistance = distance.coerceIn(-4.5f, 4.5f)
                        val xOffset = cappedDistance * 24
                        val yOffset = abs(cappedDistance) * 5
                        CardView(
                            card = card,
                            isFaceUp = true,
                            modifier = Modifier
                                .size(width = 66.dp, height = 99.dp)
                                .offset(x = xOffset.dp, y = yOffset.dp)
                                .graphicsLayer {
                                    rotationZ = cappedDistance * 6f
                                    scaleX = 0.86f + openProgress * 0.14f
                                    scaleY = 0.86f + openProgress * 0.14f
                                }
                        )
                }
            }
        }
    }
}
}

@Composable
private fun MeldTargetSelectionDialog(
    selectedCards: List<Card>,
    eligibleMeldIndices: List<Int>,
    myTableMelds: List<List<Card>>,
    config: MatchConfig,
    turnCard: Card?,
    onMeldSelected: (Int) -> Unit,
    onMeldNew: () -> Unit,
    canMeldNew: Boolean,
    onDismiss: () -> Unit
) {
    val glow = rememberPulseAlpha(min = 0.5f, label = "target_select_glow")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF121D24),
            shape = MenuShapes.Card,
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MenuColors.Gold.copy(alpha = 0.45f * glow), MenuShapes.Card)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Onde deseja encaixar?",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Selecione o jogo de destino para as cartas selecionadas",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Cards preview
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-16).dp),
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    selectedCards.forEach { card ->
                        CardView(
                            card = card,
                            isFaceUp = true,
                            modifier = Modifier
                                .size(width = 46.dp, height = 69.dp)
                                .shadow(4.dp, RoundedCornerShape(4.dp))
                        )
                    }
                }

                // Options list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    eligibleMeldIndices.forEach { index ->
                        val targetMeld = myTableMelds.getOrNull(index) ?: emptyList()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), MenuShapes.Card)
                                .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
                                .clickable { onMeldSelected(index) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((-18).dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                targetMeld.forEach { card ->
                                    CardView(
                                        card = card,
                                        isFaceUp = true,
                                        modifier = Modifier.size(width = 34.dp, height = 51.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Box(
                                modifier = Modifier
                                    .background(MenuColors.TableGreenLight.copy(alpha = 0.15f), MenuShapes.Card)
                                    .border(1.dp, MenuColors.TableGreenLight, MenuShapes.Card)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Encaixar",
                                    color = MenuColors.TableGreenLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                    }

                    if (canMeldNew) {
                        Button(
                            onClick = onMeldNew,
                            colors = ButtonDefaults.buttonColors(containerColor = MenuColors.Gold),
                            shape = MenuShapes.Card,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Novo Jogo", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandSection(
    state: GameState,
    viewModel: MatchViewModel,
    config: MatchConfig,
    feedback: MatchFeedback
) {
    // Mao do jogador local: esta lista e privada. Em modo online, somente o dono
    // da mao deve receber estes ids de carta.
    val canInteract = state.turnPhase == TurnPhase.ACTION
    val selectedCount = state.selectedCards.size
    val haptic = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    // Reset debounce flag whenever selection changes (new card tapped)
    androidx.compose.runtime.LaunchedEffect(selectedCount) { isProcessing = false }

    // Reset debounce on error so the player can retry without re-selecting
    androidx.compose.runtime.LaunchedEffect(state.lastMeldResult) {
        if (state.lastMeldResult.isNotBlank()) isProcessing = false
    }

    // Altura da carta inteira e quanto fica visível por padrão (efeito dominó)
    val handCount = state.myHand.size.coerceAtLeast(1)
    val compactHand = state.myHand.size >= 13
    val cardW = if (compactHand) 52.dp else 64.dp
    val cardH = cardW * 1.5f
    val selectRise = 14.dp
    // Brilho puramente decorativo sobre a carta selecionada; a borda dourada ja
    // sinaliza a selecao, entao o sweep some com "reduzir movimento" ligado.
    val reducedMotion = rememberReducedMotionEnabled()
    val handShine = if (reducedMotion) {
        0f
    } else {
        val shine by rememberInfiniteTransition(label = "hand_shine").animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "hand_shine_x"
        )
        shine
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Barra de ações — fora do Box das cartas, nunca sobreposta ────────
        // Renderiza num bloco Row isolado ACIMA do Box das cartas,
        // evitando qualquer sobreposição de toque ou visual.
        if (canInteract || selectedCount > 0) {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedCount == 0) "Mão - ${state.myHand.size} cartas"
                       else "${selectedCount} selecionada(s)",
                color = if (selectedCount > 0) ColorGold else Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = if (selectedCount > 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            if (canInteract) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedCount == 1) {
                        Button(
                            onClick = {
                                if (isProcessing) return@Button
                                isProcessing = true
                                feedback.play(FeedbackCue.Place)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.discardCard(state.selectedCards.first())
                            },
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ColorRed,
                                disabledContainerColor = ColorRed.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .defaultMinSize(minWidth = 96.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text("Descartar", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                    }
                    if (selectedCount >= 1) {
                        Button(
                            onClick = {
                                if (isProcessing) return@Button
                                isProcessing = true
                                feedback.play(FeedbackCue.Place)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.meldSelectedCards()
                            },
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ColorGreenLight,
                                disabledContainerColor = ColorGreenLight.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .defaultMinSize(minWidth = 96.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text(
                                text = if (selectedCount < 3) "Encaixar" else "Baixar Jogo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
        }

        // -- Cartas da mão - estilo dominó ------------------------------------
        // O Box tem altura = parte visível da carta, e as cartas transbordam para baixo
        // ficando escondidas pelo clip. Carta selecionada sobe com offset negativo.

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardH + selectRise + 8.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.0f),
                            Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
        ) {
            val horizontalPadding = 24.dp
            val availableWidth = (maxWidth - horizontalPadding).coerceAtLeast(cardW)
            val minimumStep = 19.dp
            val fittedStep = if (handCount <= 1) {
                cardW
            } else {
                ((availableWidth - cardW) / (handCount - 1)).coerceAtMost(cardW + 8.dp)
            }
            val needsScrolling = fittedStep < minimumStep
            val visibleStep = if (needsScrolling) minimumStep else fittedStep
            val overlap = visibleStep - cardW

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(overlap, Alignment.CenterHorizontally),
                userScrollEnabled = needsScrolling,
                modifier = Modifier
                    .fillMaxWidth()
                    // Mantém o LazyRow sem clip para cartas selecionadas subirem acima da Box.
                    .wrapContentHeight(unbounded = true)
                    .align(Alignment.BottomCenter)
            ) {
                itemsIndexed(
                    items = state.myHand,
                    key = { index, card -> "${index}_${card.id}" }
                ) { index, card ->
                    val isSelected = card in state.selectedCards
                    val isLastDrawn = card.id == state.lastDrawnCardId
                    val centerIdx = state.myHand.lastIndex / 2f
                    val dist = index - centerIdx
                    
                    // Limita o ângulo e drop máximo para mãos gigantes (ex: 30 cartas)
                    val maxDist = maxOf(1f, centerIdx)
                    val factorZ = minOf(3.0f, 18f / maxDist)
                    val factorY = minOf(1.2f, 10f / maxDist)
                    val factorDrop = minOf(1.2f, 8f / maxDist)

                    val fanZ = dist * factorZ
                    val curveY = dist * -factorY
                    val dropY = kotlin.math.abs(dist) * factorDrop
                    // Carta não selecionada = 0 (topo colado no topo do Box)
                    // Carta selecionada = sobe selectRise (fica completamente visível + extra)
                    val yOffset by animateDpAsState(
                        targetValue = if (isSelected) -selectRise else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "card_y_${card.id}"
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, (yOffset.roundToPx() + (dropY * density).toInt())) }
                            .graphicsLayer {
                                rotationZ = if (isSelected) 0f else fanZ
                                rotationY = if (isSelected) 0f else curveY
                                cameraDistance = 12f * density
                            }
                            .animateItem()
                            .shadow(
                                elevation = if (isSelected) 24.dp else 8.dp,
                                shape = RoundedCornerShape(12.dp),
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.4f),
                                spotColor = Color.Black.copy(alpha = 0.7f)
                            )
                            .background(
                                if (isSelected) Brush.radialGradient(
                                    colors = listOf(
                                        ColorGold.copy(alpha = 0.45f),
                                        ColorGold.copy(alpha = 0.10f),
                                        Color.Transparent
                                    )
                                ) else Brush.radialGradient(
                                    colors = listOf(Color.Transparent, Color.Transparent)
                                ),
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                width = if (isSelected) 2.dp else if (isLastDrawn) 2.dp else 0.dp,
                                color = if (isSelected) ColorGold else if (isLastDrawn) Color(0xFFFFD700).copy(alpha = 0.8f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .then(
                                if (isLastDrawn && !isSelected) Modifier.background(
                                    Brush.radialGradient(
                                        listOf(ColorGold.copy(alpha = 0.4f), Color.Transparent)
                                    ),
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                            .padding(if (isSelected || isLastDrawn) 2.dp else 0.dp)
                            .clickable {
                                if (canInteract) {
                                    feedback.play(FeedbackCue.Select)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.toggleCardSelection(card)
                                } else {
                                    feedback.play(FeedbackCue.Error)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                    ) {
                        CardView(
                            card = card,
                            isFaceUp = true,
                            modifier = Modifier.size(width = cardW, height = cardH)
                        )
                        if (isSelected && !reducedMotion) {
                            Canvas(modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))) {
                                val x = size.width * handShine
                                drawLine(
                                    color = Color.White.copy(alpha = 0.55f),
                                    start = androidx.compose.ui.geometry.Offset(x - size.width * 0.35f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(x + size.width * 0.15f, size.height),
                                    strokeWidth = size.width * 0.16f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Diálogo de Reinício de Partida ──────────────────────
@Composable
private fun RestartMatchDialog(onConfirm: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = MenuColors.Ink,
        shape = MenuShapes.Card,
        title = {
            Text("Novo Jogo", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = { Text("O host deseja reiniciar a partida inteira (zerando o placar). Você aceita?", color = Color.LightGray) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight)
            ) { Text("Aceitar") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Recusar", color = Color.LightGray) }
        }
    )
}

// --- Diálogo de Desconexão --------------------------------
@Composable
private fun DisconnectDialog(message: String, isClient: Boolean, onBack: () -> Unit, onWait: () -> Unit, onReconnect: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        containerColor = MenuColors.Ink,
        shape = MenuShapes.Card,
        title = {
            Text("Conexão interrompida", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = { Text(message, color = Color.LightGray) },
        confirmButton = {
            if (isClient) {
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.Gold)
                ) { Text("Tentar Reconectar", color = Color.Black) }
            } else {
                Button(
                    onClick = onWait,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight)
                ) { Text("Aguardar") }
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("Sair e Salvar", color = MenuColors.Red) }
        }
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "MatchScreen - Buraco")
@Composable
fun MatchScreenPreview() {
    MaterialTheme {
        // LocalInspectionMode será true aqui — MatchScreen renderiza MatchScreenStaticPreview automaticamente
        MatchScreen(
            networkRepository = object : LocalNetworkRepository {
                override val discoveredRooms = kotlinx.coroutines.flow.MutableStateFlow(emptyList<DiscoveredRoom>())
                override val connectedClientsCount = kotlinx.coroutines.flow.MutableStateFlow(1)
                override val incomingMessages = kotlinx.coroutines.flow.MutableSharedFlow<NetworkMessage>()
                override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.CONNECTED)
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int) {}
                override fun disconnect() {}
                override fun sendMessage(message: NetworkMessage) {}
                override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage) = true
                override fun sendMessageToPlayer(playerId: String, message: NetworkMessage) = true
                override fun resetConnectionStatus() {}
                override fun reconnect(): Boolean = false
            },
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO),
            onLeaveMatch = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "MatchScreen - Cacheta")
@Composable
fun MatchScreenCachetaPreview() {
    MaterialTheme {
        MatchScreen(
            networkRepository = object : LocalNetworkRepository {
                override val discoveredRooms = kotlinx.coroutines.flow.MutableStateFlow(emptyList<DiscoveredRoom>())
                override val connectedClientsCount = kotlinx.coroutines.flow.MutableStateFlow(1)
                override val incomingMessages = kotlinx.coroutines.flow.MutableSharedFlow<NetworkMessage>()
                override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.CONNECTED)
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int) {}
                override fun disconnect() {}
                override fun sendMessage(message: NetworkMessage) {}
                override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage) = true
                override fun sendMessageToPlayer(playerId: String, message: NetworkMessage) = true
                override fun resetConnectionStatus() {}
                override fun reconnect(): Boolean = false
            },
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA),
            onLeaveMatch = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "MatchScreen - Tranca")
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=800dp,height=360dp,dpi=320",
    fontScale = 1.5f,
    name = "Partida compacta - fonte grande"
)
@Composable
fun MatchScreenTrancaPreview() {
    MaterialTheme {
        MatchScreen(
            networkRepository = object : LocalNetworkRepository {
                override val discoveredRooms = kotlinx.coroutines.flow.MutableStateFlow(emptyList<DiscoveredRoom>())
                override val connectedClientsCount = kotlinx.coroutines.flow.MutableStateFlow(1)
                override val incomingMessages = kotlinx.coroutines.flow.MutableSharedFlow<NetworkMessage>()
                override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(ConnectionStatus.CONNECTED)
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int) {}
                override fun disconnect() {}
                override fun sendMessage(message: NetworkMessage) {}
                override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage) = true
                override fun sendMessageToPlayer(playerId: String, message: NetworkMessage) = true
                override fun resetConnectionStatus() {}
                override fun reconnect(): Boolean = false
            },
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA),
            onLeaveMatch = {}
        )
    }
}

// -- Previews de estado da mesa (vazio, cheio, erro) ----------------------------------------------
// Chamam o preview estatico direto (sem passar por MatchScreen/ViewModel) pra
// exercitar os estados que o roadmap pede: mesa vazia, mesa cheia (grade e leque
// sob pressao) e feedback de jogada invalida/lixo bloqueado.
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "MatchScreen - mesa vazia"
)
@Composable
private fun MatchScreenEmptyTablePreview() {
    MaterialTheme {
        MatchScreenStaticPreview(config = MatchConfig(gameType = GameType.BURACO), scenario = MatchPreviewScenario.EMPTY)
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "MatchScreen - mesa cheia"
)
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=760dp,height=420dp,dpi=280",
    name = "MatchScreen - mesa cheia (compacta)"
)
@Composable
private fun MatchScreenFullTablePreview() {
    MaterialTheme {
        MatchScreenStaticPreview(config = MatchConfig(gameType = GameType.TRANCA), scenario = MatchPreviewScenario.FULL_TABLE)
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "MatchScreen - jogada invalida (erro)"
)
@Composable
private fun MatchScreenErrorStatePreview() {
    MaterialTheme {
        MatchScreenStaticPreview(config = MatchConfig(gameType = GameType.BURACO), scenario = MatchPreviewScenario.ERROR)
    }
}

// -- Previews de tela pequena e fonte ampliada (acessibilidade) -----------------------------------
@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=360dp,height=780dp,dpi=420",
    name = "MatchScreen - celular retrato (tela pequena)"
)
@Composable
private fun MatchScreenCompactPortraitPreview() {
    MaterialTheme {
        MatchScreenStaticPreview(config = MatchConfig(gameType = GameType.CACHETA), scenario = MatchPreviewScenario.STANDARD)
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=411dp,height=891dp,dpi=420",
    fontScale = 2.0f,
    name = "MatchScreen - fonte grande (acessibilidade)"
)
@Composable
private fun MatchScreenLargeFontPreview() {
    MaterialTheme {
        MatchScreenStaticPreview(config = MatchConfig(gameType = GameType.BURACO), scenario = MatchPreviewScenario.STANDARD)
    }
}
