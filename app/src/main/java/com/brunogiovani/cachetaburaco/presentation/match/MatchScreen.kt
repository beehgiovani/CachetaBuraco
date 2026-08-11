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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.brunogiovani.cachetaburaco.domain.models.displayLabel
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.domain.repositories.RoomChatMessage
import com.brunogiovani.cachetaburaco.presentation.components.CardView
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuMotion
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.rememberReducedMotionEnabled
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ─── Paleta ───────────────────────────────────────────────
internal val ColorGreenLight = Color(0xFF4CAF50)
internal val ColorBlueLight = Color(0xFF42A5F5)
internal val ColorGold = Color(0xFFFFD54F)
internal val ColorRed = Color(0xFFB71C1C)
internal val ColorRedLight = Color(0xFFEF5350)
internal val ColorSurface = Color(0xAA000000)
internal val ColorLockRed = Color(0xFFEF5350)

// MAX_DISPLAYED_CHAT_MESSAGES e LIVE_CHAT_BUBBLE_MILLIS moraram pra MatchChat.kt
// junto dos composables de chat que os usam.

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
internal fun rememberPulseAlpha(min: Float, max: Float = 1f, label: String = "pulse"): Float {
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

    // Chat de sala: so faz sentido no transporte online (Wi-Fi local e maquina
    // nao tem, ver LocalNetworkRepository.roomChatMessages).
    val roomChatEnabled = networkRepository.isOnlineTransport
    val roomChatMessages = remember { mutableStateListOf<RoomChatMessage>() }
    var showRoomChat by remember { mutableStateOf(false) }
    var unreadRoomChatCount by remember { mutableIntStateOf(0) }
    // Balao ao vivo: a ultima mensagem do adversario aparece sobre a mesa por
    // alguns segundos, pra quem esta no meio de uma jogada nao precisar abrir
    // o chat pra saber que foi provocado. So mensagem dos outros -- ver a
    // propria mensagem ecoando na tela nao ajuda em nada.
    var liveBubble by remember { mutableStateOf<RoomChatMessage?>(null) }
    LaunchedEffect(networkRepository, roomChatEnabled) {
        if (!roomChatEnabled) return@LaunchedEffect
        networkRepository.roomChatMessages.collect { message ->
            roomChatMessages.add(message)
            if (roomChatMessages.size > MAX_DISPLAYED_CHAT_MESSAGES) roomChatMessages.removeAt(0)
            if (!showRoomChat) {
                unreadRoomChatCount++
                if (!message.isSelf) liveBubble = message
            }
        }
    }
    // Some sozinho. A chave e a propria mensagem: se chegar outra antes de
    // vencer o tempo, o LaunchedEffect reinicia e o balao novo ganha os
    // segundos inteiros em vez de herdar o resto do anterior.
    LaunchedEffect(liveBubble) {
        if (liveBubble != null) {
            delay(LIVE_CHAT_BUBBLE_MILLIS)
            liveBubble = null
        }
    }

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
            TopBar(
                state = state,
                config = state.config,
                onLeave = {
                    viewModel.clearGameSnapshot()
                    networkRepository.stopHosting()
                    networkRepository.disconnect()
                    onLeaveMatch()
                },
                showChatButton = roomChatEnabled,
                unreadChatCount = unreadRoomChatCount,
                onOpenChat = {
                    showRoomChat = true
                    unreadRoomChatCount = 0
                }
            )

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

        // Logo abaixo da TopBar: perto do botao de chat (de onde a mensagem
        // "vem"), e fora da area da mesa/mao, que e onde o jogador toca.
        LiveChatBubble(
            message = liveBubble,
            onClick = {
                liveBubble = null
                showRoomChat = true
                unreadRoomChatCount = 0
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp)
        )

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

        // -- Chat da sala (so online) -----------------------------------------------------------------
        if (showRoomChat) {
            RoomChatDialog(
                messages = roomChatMessages,
                onSend = { body -> networkRepository.sendRoomChatMessage(body) },
                onDismiss = { showRoomChat = false }
            )
        }
    }
}

// ── Diálogo de Fim de Rodada ──────────────────────────────────────────────────────────────────────


// ── Top Bar ───────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(
    state: GameState,
    config: MatchConfig,
    onLeave: () -> Unit,
    showChatButton: Boolean = false,
    unreadChatCount: Int = 0,
    onOpenChat: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sair
            TextButton(onClick = onLeave) {
                Text("Sair", color = Color.White, fontWeight = FontWeight.Bold)
            }

            if (showChatButton) {
                Box {
                    TextButton(onClick = onOpenChat) {
                        Text("💬", fontSize = 18.sp)
                    }
                    if (unreadChatCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .background(ColorRedLight, CircleShape)
                        )
                    }
                }
            }
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

// --- Mão do Jogador ---------------------------------------
@Composable
internal fun MeldInspectorDialog(
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
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?, password: String?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int, password: String?) {}
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
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?, password: String?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int, password: String?) {}
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
                override fun startHosting(playerName: String, port: Int, config: MatchConfig?, password: String?) {}
                override fun stopHosting() {}
                override fun startDiscovery() {}
                override fun stopDiscovery() {}
                override fun connectToRoom(host: String, port: Int, password: String?) {}
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
