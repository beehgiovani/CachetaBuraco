package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.models.displayLabel
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine
import com.brunogiovani.cachetaburaco.presentation.components.CardView
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes

// Centro da mesa: monte, lixo, vira e os jogos ja baixados pelos dois lados.

// ── Centro da Mesa ────────────────────────────────────────────────────────────────────────────────
@Composable
internal fun TableCenter(
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
internal fun DrawPilesPanel(
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
        val isCacheta = config.gameType == GameType.CACHETA
        val compactCardByHeight = ((maxHeight - 8.dp).coerceAtLeast(88.dp)) / 1.5f
        // A Cacheta poe 3 cartas na linha (monte, vira, lixo); os outros modos,
        // 2 (monte, lixo). Desconto o espacamento antes de dividir.
        val compactCardByWidth = ((maxWidth - 18.dp) / if (isCacheta) 3f else 2f)
        val priorityCardWidth = minOf(compactCardByHeight, compactCardByWidth, if (ultraCompact) 74.dp else 88.dp)
            .coerceAtLeast(58.dp)
        val pileCardWidth = if (priorityCards) priorityCardWidth else 74.dp
        // O lixo so ganha largura extra quando o slot dele e maior de verdade
        // (Buraco/Tranca usam peso 1.18 contra 0.82 do monte). Na Cacheta os
        // tres slots tem peso igual: pedir mais largura ali fazia o Compose
        // cortar a largura e manter a altura (cardWidth * 1.5f), deixando a
        // carta do lixo permanentemente esmagada.
        val discardCardWidth = when {
            !priorityCards -> pileCardWidth
            isCacheta -> pileCardWidth
            else -> minOf(priorityCardWidth + 10.dp, compactCardByWidth, if (ultraCompact) 80.dp else 94.dp)
                .coerceAtLeast(priorityCardWidth)
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
internal fun MeldArea(
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
                    "Curinga: ${GameRulesEngine.getCachetaWildcardRank(turnCard).displayLabel}",
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
            // Padding fixo de proposito. Antes variava (3.dp ativo / 1.dp
            // inativo) e, como ele entra depois do .size(), a area util da
            // carta mudava junto -- a cada descarte o estado alternava e a
            // carta "pulava" de proporcao por um frame. O destaque de ativo ja
            // vem do brilho, da sombra e da borda, que nao mexem no layout.
            .padding(3.dp)
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
            // Tamanho fixo na Box de fora, de proposito -- antes so o CardView
            // interno tinha .size(), e a Box decorativa (sem tamanho proprio)
            // dependia do filho pra "borbulhar" a largura pra cima. Dentro do
            // Row de MONTE/VIRA/LIXO isso e fragil: a Column do LIXO carrega
            // texto mais largo que o card ("Toque para comprar"), e o Row com
            // 3 colunas nao-weighted espreme o card pra menos da metade do
            // cardWidth pedido (achado real: MONTE e LIXO com o mesmo
            // cardWidth=74dp, LIXO renderizando ~40% do tamanho). Fixando o
            // tamanho aqui, igual o CompactPileCard ja faz certo, o card nao
            // depende mais do quanto a Column ao redor decide reservar.
            modifier = Modifier
                .size(width = cardWidth, height = cardWidth * 1.5f)
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
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()
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
            // Mesmo motivo do DeckPile: tamanho fixo na Box de fora, nao so
            // no CardView interno. Era exatamente essa Box sem .size() proprio
            // que deixava o card do lixo espremido pelo Column ao redor
            // (largura do texto "Toque para comprar" competindo com as outras
            // duas colunas do Row sem weight).
            modifier = Modifier
                .size(width = cardWidth, height = cardWidth * 1.5f)
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
                    modifier = Modifier.fillMaxSize()
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
                Box(modifier = Modifier.fillMaxSize()
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

