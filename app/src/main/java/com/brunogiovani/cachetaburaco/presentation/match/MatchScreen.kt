package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.animation.AnimatedVisibility
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
import com.brunogiovani.cachetaburaco.R
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ─── Paleta ───────────────────────────────────────────────
private val ColorGreen = Color(0xFF2E7D32)
private val ColorGreenLight = Color(0xFF4CAF50)
private val ColorBlue = Color(0xFF1565C0)
private val ColorBlueLight = Color(0xFF42A5F5)
private val ColorGold = Color(0xFFFFD54F)
private val ColorRed = Color(0xFFB71C1C)
private val ColorRedLight = Color(0xFFEF5350)
private val ColorSurface = Color(0xAA000000)
private val ColorLockRed = Color(0xFFEF5350)
private val ColorCard = Color(0xFF161B22)

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
    val viewModel = remember {
        MatchViewModel(networkRepository, currentPlayer.id, isHost, config, context)
    }

    val state by viewModel.gameState.collectAsState()
    val connectionStatus by networkRepository.connectionStatus.collectAsState()
    val feedback = rememberMatchFeedback()
    val roundEndDetails = state.roundEndDetails
    var recordedMatchWinKey by remember { mutableStateOf<String?>(null) }
    var showDealingAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (isHost && !viewModel.isRestored) viewModel.startGame() }

    // Quando a conexão volta, o cliente pede a mesa atual para o host.
    var wasDisconnected by remember { mutableStateOf(false) }
    LaunchedEffect(connectionStatus) {
        when (connectionStatus) {
            ConnectionStatus.OPPONENT_DISCONNECTED,
            ConnectionStatus.HOST_DISCONNECTED -> wasDisconnected = true
            ConnectionStatus.CONNECTED -> {
                if (wasDisconnected && !isHost) {
                    wasDisconnected = false
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

    LaunchedEffect(state.playerSeat, state.myHand.size, state.turnCard?.id, state.config.gameType) {
        if (state.myHand.isNotEmpty()) {
            showDealingAnimation = true
            kotlinx.coroutines.delay(1250)
            showDealingAnimation = false
        }
    }

    LaunchedEffect(
        roundEndDetails?.isMatchOver,
        roundEndDetails?.winnerName,
        roundEndDetails?.myNewTotal,
        roundEndDetails?.opponentNewTotal
    ) {
        val details = roundEndDetails ?: return@LaunchedEffect
        val isLocalWinner = details.isMatchOver &&
            (details.winnerName.startsWith("Voc") || details.winnerName == "Sua equipe")
        val winKey = "${details.winnerName}:${details.myNewTotal}:${details.opponentNewTotal}"
        if (isLocalWinner && recordedMatchWinKey != winKey) {
            feedback.play(FeedbackCue.Victory)
            FakeAuthRepository.recordCurrentPlayerVictory()
            recordedMatchWinKey = winKey
        } else if (details.isMatchOver || state.showRoundEndDialog) {
            feedback.play(FeedbackCue.RoundEnd)
        }
    }

    // Som quando adversário pega o morto
    LaunchedEffect(state.opponentPickedMorto) {
        if (state.opponentPickedMorto) {
            feedback.play(FeedbackCue.OpponentMorto)
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

        // ── Overlay de Meld feedback ───────────────────────
        AnimatedVisibility(
            visible = state.lastMeldResult.isNotBlank(),
            enter = fadeIn(animationSpec = tween(140)) + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            exit = fadeOut(animationSpec = tween(180)) + scaleOut(
                targetScale = 0.92f,
                animationSpec = tween(180)
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
        if (connectionStatus == ConnectionStatus.OPPONENT_DISCONNECTED ||
            connectionStatus == ConnectionStatus.HOST_DISCONNECTED) {
            DisconnectDialog(
                message = if (connectionStatus == ConnectionStatus.HOST_DISCONNECTED)
                    "O host perdeu a conexão." else "Um oponente saiu da partida.",
                isClient = !isHost,
                onBack = {
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

        // ── Diálogo de Fim de Rodada / Partida ──────────────────────────────────────────────────────
        state.roundEndDetails?.takeIf { state.showRoundEndDialog }?.let { details ->
            RoundEndDialog(
                details = details,
                config = state.config,
                onNextRound = { viewModel.nextRound() },
                onLeave = {
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
    val transition = rememberInfiniteTransition(label = "felt_ambient_motion")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "felt_ambient_drift"
    )

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
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 1150, easing = LinearEasing),
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
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 760, easing = LinearEasing),
        label = "meld_sparkle_progress"
    )
    if (progress <= 0.01f) return

    val colors = remember {
        listOf(ColorGold, ColorGreenLight, ColorBlueLight, Color.White)
    }
    Canvas(modifier = modifier.size(250.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        repeat(28) { index ->
            val angle = (PI.toFloat() * 2f / 28f) * index
            val distance = (28f + (index % 5) * 9f) * progress
            val x = center.x + cos(angle) * distance
            val y = center.y + sin(angle) * distance
            drawCircle(
                color = colors[index % colors.size],
                radius = (5f - progress * 2.5f).coerceAtLeast(1.5f),
                center = androidx.compose.ui.geometry.Offset(x, y),
                alpha = (1f - progress).coerceIn(0f, 0.92f)
            )
        }
    }
}

@Composable
private fun VictoryConfetti(visible: Boolean) {
    if (!visible) return

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
    onNextRound: () -> Unit,
    onLeave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = ColorCard,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (details.isMatchOver) "🏆 PARTIDA ENCERRADA!" else "🎴 FIM DE RODADA",
                    color = if (details.isMatchOver) ColorGold else Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Vencedor: ${details.winnerName}",
                    color = ColorGreenLight,
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
                // Breakdown
                if (details.breakdown.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = details.breakdown,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Placar acumulado — orientado pela perspectiva local (localTeam)
                val isTeamMode = config.maxPlayers == 4
                val opponentSideLabel = if (details.opponentLabel == "Máquina") "Lado da Máquina" else "Lado do Oponente"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
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
                        ScoreColumn(
                            label = details.opponentLabel,
                            score = details.opponentNewTotal,
                            limit = config.pointLimit,
                            gameType = config.gameType,
                            isWinner = details.winnerTeam != null && details.winnerTeam != details.localTeam
                        )
                    }
                }


                if (details.isMatchOver) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ColorGold.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🎉 ${details.winnerName} venceu a partida!",
                            color = ColorGold,
                            fontWeight = FontWeight.Bold,
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
                    colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▶  Próxima Rodada", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
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
            }
        }
    )
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
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Text(
            text = if (gameType == GameType.CACHETA) "$score ❤️" else "$score pts",
            color = if (isWinner) ColorGreenLight else Color.White,
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

        // Fase / Status central
        val (phaseColor, phaseText) = when (state.turnPhase) {
            TurnPhase.DRAW -> ColorGreenLight to "Compre uma carta"
            TurnPhase.ACTION -> ColorGold to "Baixe ou descarte"
            TurnPhase.WAITING_OPPONENT -> Color.LightGray to "Turno do oponente"
        }

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
@Composable
private fun MatchScreenStaticPreview(config: MatchConfig) {
    val sampleHand = remember {
        listOf(
            Card(Suit.HEARTS, Rank.ACE), Card(Suit.SPADES, Rank.KING),
            Card(Suit.DIAMONDS, Rank.QUEEN), Card(Suit.CLUBS, Rank.JACK),
            Card(Suit.HEARTS, Rank.TEN), Card(Suit.SPADES, Rank.NINE),
            Card(Suit.DIAMONDS, Rank.EIGHT), Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.SIX), Card(Suit.SPADES, Rank.FIVE),
            Card(Suit.DIAMONDS, Rank.FOUR)
        )
    }
    val sampleMyMelds = remember {
        listOf(
            listOf(Card(Suit.HEARTS, Rank.THREE), Card(Suit.HEARTS, Rank.FOUR), Card(Suit.HEARTS, Rank.FIVE), Card(Suit.HEARTS, Rank.SIX), Card(Suit.HEARTS, Rank.SEVEN)),
            listOf(Card(Suit.SPADES, Rank.JACK), Card(Suit.CLUBS, Rank.JACK), Card(Suit.HEARTS, Rank.JACK))
        )
    }
    val sampleOppMelds = remember {
        listOf(
            listOf(Card(Suit.DIAMONDS, Rank.TWO), Card(Suit.DIAMONDS, Rank.THREE), Card(Suit.DIAMONDS, Rank.FOUR))
        )
    }
    val fakeState = GameState(
        myHand = sampleHand,
        myTableMelds = sampleMyMelds,
        opponentTableMelds = sampleOppMelds,
        discardPile = listOf(Card(Suit.CLUBS, Rank.KING)),
        deckSize = 42,
        mortosLeft = if (config.gameType != GameType.CACHETA) 2 else 0,
        playerSeat = 0,
        activeSeat = 0,
        teamScores = listOf(450, 200),
        turnPhase = TurnPhase.ACTION,
        feedbackMessage = "\uD83C\uDFB4 Preview \u2014 ${config.gameType.name} | Sua vez!",
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
                    Text("Baixe ou descarte", color = ColorGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 300.dp)) {

                    Text("A 450 x B 200", color = ColorGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (config.gameType != GameType.CACHETA)
                        Text("Mortos: 2", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
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
                        prominent = false
                    )
                    MeldArea(
                        title = "Minha Mesa",
                        melds = fakeState.myTableMelds,
                        accentColor = ColorGreenLight,
                        emptyText = "Baixe jogos aqui",
                        modifier = Modifier.weight(1.18f),
                        prominent = true
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
private fun MortosPile(mortosLeft: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Mortos",
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .height(58.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (mortosLeft > 0) ColorGold.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.08f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (mortosLeft > 0) {
                repeat(mortosLeft.coerceAtMost(2)) { index ->
                    CardView(
                        card = Card(Suit.SPADES, Rank.ACE),
                        isFaceUp = false,
                        modifier = Modifier
                            .size(width = 36.dp, height = 54.dp)
                            .graphicsLayer {
                                rotationZ = if (index == 0) -18f else 18f
                                translationX = if (index == 0) -14f else 14f
                            }
                    )
                }
            } else {
                Text("0", color = Color.White.copy(alpha = 0.45f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text("$mortosLeft", color = ColorGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
            val longestMeld = melds.maxOfOrNull { it.size } ?: 3
            val groupCardWidth = when {
                maxWidth < 250.dp -> 34.dp
                melds.size >= 5 -> 34.dp
                melds.size >= 4 -> 38.dp
                melds.size >= 3 && longestMeld >= 5 -> 40.dp
                melds.size >= 3 -> 46.dp
                longestMeld >= 7 -> 42.dp
                prominent -> 54.dp
                else -> 48.dp
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(if (groupCardWidth <= 38.dp) 4.dp else 6.dp),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (melds.isEmpty()) {
                    item {
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
                    itemsIndexed(
                        items = melds,
                        key = { index, meld -> "meld_${index}_${meld.joinToString("_") { it.id }}" }
                    ) { _, meld ->
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
        }
    }
}

@Composable
private fun TurnCardPile(turnCard: Card?, cardWidth: Dp = 58.dp) {
    val pulse by rememberInfiniteTransition(label = "turn_card_pulse").animateFloat(
        initialValue = 0.52f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "turn_card_pulse_alpha"
    )
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
    val pulse by rememberInfiniteTransition(label = "compact_pile_pulse_$label").animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compact_pile_alpha_$label"
    )
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
    val pulse by rememberInfiniteTransition(label = "deck_pulse").animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "deck_pulse_alpha"
    )
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
    val pulse by rememberInfiniteTransition(label = "discard_pulse").animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "discard_pulse_alpha"
    )

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
    val pulse by rememberInfiniteTransition(label = "meld_group_pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meld_group_alpha"
    )

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
    val glow by rememberInfiniteTransition(label = "meld_inspector_glow").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meld_inspector_glow_alpha"
    )
    val centerIndex = (cards.lastIndex.coerceAtLeast(0)) / 2f

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xEE101820),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ColorGold.copy(alpha = 0.45f * glow), RoundedCornerShape(20.dp))
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
                        Text("Fechar", color = ColorGold)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    ColorGold.copy(alpha = 0.14f * glow),
                                    ColorGreenLight.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            ),
                            RoundedCornerShape(16.dp)
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
    val glow by rememberInfiniteTransition(label = "target_select_glow").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "target_select_glow_alpha"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF121D24),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ColorGold.copy(alpha = 0.45f * glow), RoundedCornerShape(20.dp))
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
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
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
                                    .background(ColorGreenLight.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    .border(1.dp, ColorGreenLight, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Encaixar",
                                    color = ColorGreenLight,
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
                            colors = ButtonDefaults.buttonColors(containerColor = ColorGold),
                            shape = RoundedCornerShape(8.dp),
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
    // Exposição visível: ~42% da carta aparece. O resto fica abaixo da tela.
    val visibleFraction = 1f
    val hiddenDp = 0.dp
    // Quando selecionada, a carta sobe o valor inteiro do que estava escondido
    // mais um extra para destacar
    val selectRise = 14.dp
    val overlap = when {
        handCount >= 24 -> -(cardW * 0.58f)
        handCount >= 18 -> -(cardW * 0.50f)
        handCount >= 13 -> -(cardW * 0.38f)
        handCount >= 9 -> -(cardW * 0.24f)
        else -> 8.dp
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

        Box(
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
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(overlap, Alignment.CenterHorizontally),
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
                    val shine by rememberInfiniteTransition(label = "shine_${card.id}").animateFloat(
                        initialValue = -0.6f,
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "shine_x_${card.id}"
                    )
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
                        if (isSelected) {
                            Canvas(modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))) {
                                val x = size.width * shine
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
        containerColor = Color(0xFF1A1A2E),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Novo Jogo", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = { Text("O host deseja reiniciar a partida inteira (zerando o placar). Você aceita?", color = Color.LightGray) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight)
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
        containerColor = Color(0xFF1A1A2E),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Conexão interrompida", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = { Text(message, color = Color.LightGray) },
        confirmButton = {
            if (isClient) {
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorBlue)
                ) { Text("Tentar Reconectar") }
            } else {
                Button(
                    onClick = onWait,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight)
                ) { Text("Aguardar") }
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("Sair e Salvar", color = Color(0xFFE53935)) }
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
