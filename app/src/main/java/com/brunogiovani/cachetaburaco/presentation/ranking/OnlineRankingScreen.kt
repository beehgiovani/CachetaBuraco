package com.brunogiovani.cachetaburaco.presentation.ranking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingEntry
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineRankingRepository
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuEntrance
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import com.brunogiovani.cachetaburaco.presentation.components.OnlineAvatarView
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot

internal sealed interface OnlineRankingUiState {
    data object Loading : OnlineRankingUiState
    data class Ready(val snapshot: OnlineRankingSnapshot) : OnlineRankingUiState
    data class Error(val message: String) : OnlineRankingUiState
}

@Composable
fun OnlineRankingScreen(
    playerName: String,
    repository: OnlineRankingRepository,
    onBack: () -> Unit
) {
    var reloadRequest by remember { mutableIntStateOf(0) }
    var selectedPeriod by remember { mutableStateOf(OnlineRankingPeriod.OVERALL) }
    var state by remember { mutableStateOf<OnlineRankingUiState>(OnlineRankingUiState.Loading) }

    LaunchedEffect(playerName, selectedPeriod, reloadRequest) {
        state = OnlineRankingUiState.Loading
        state = runCatching { repository.loadRanking(playerName, selectedPeriod) }
            .fold(
                onSuccess = { OnlineRankingUiState.Ready(it) },
                onFailure = {
                    OnlineRankingUiState.Error(
                        "Não foi possível carregar o ranking agora. Confira sua conexão."
                    )
                }
            )
    }

    OnlineRankingContent(
        state = state,
        selectedPeriod = selectedPeriod,
        onPeriodSelected = { selectedPeriod = it },
        onBack = onBack,
        onRetry = { reloadRequest++ }
    )
}

@Composable
internal fun OnlineRankingContent(
    state: OnlineRankingUiState,
    selectedPeriod: OnlineRankingPeriod,
    onPeriodSelected: (OnlineRankingPeriod) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    MenuBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp || maxHeight < 430.dp
            val pagePadding = if (compact) 10.dp else 18.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(pagePadding),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
            ) {
                MenuTopBar(
                    title = "Ranking online",
                    onBack = onBack,
                    subtitle = if (!compact) "Classificação por partidas concluídas" else null
                )
                MenuEntrance {
                    RankingPeriodSelector(
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = onPeriodSelected,
                        compact = compact
                    )
                }

                when (state) {
                    OnlineRankingUiState.Loading -> RankingLoading(Modifier.weight(1f))
                    is OnlineRankingUiState.Error -> RankingError(
                        message = state.message,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f)
                    )
                    is OnlineRankingUiState.Ready -> MenuEntrance(modifier = Modifier.weight(1f)) {
                        RankingReady(
                            snapshot = state.snapshot,
                            compact = compact,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                SafeAdBannerSlot(compact = true, placement = AdPlacement.RANKING)
            }
        }
    }
}

@Composable
private fun RankingPeriodSelector(
    selectedPeriod: OnlineRankingPeriod,
    onPeriodSelected: (OnlineRankingPeriod) -> Unit,
    compact: Boolean
) {
    val periods = OnlineRankingPeriod.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 44.dp else 48.dp)
    ) {
        periods.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selectedPeriod,
                onClick = { onPeriodSelected(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                label = {
                    Text(
                        text = rankingPeriodLabel(period),
                        maxLines = 1,
                        fontSize = if (compact) 11.sp else 13.sp
                    )
                }
            )
        }
    }
}

internal fun rankingPeriodLabel(period: OnlineRankingPeriod): String = when (period) {
    OnlineRankingPeriod.OVERALL -> "Geral"
    OnlineRankingPeriod.WEEKLY -> "Semana"
    OnlineRankingPeriod.MONTHLY -> "Mês"
}

internal fun rankingEmptyMessage(period: OnlineRankingPeriod): String = when (period) {
    OnlineRankingPeriod.OVERALL -> "O ranking abre assim que a primeira partida online terminar."
    OnlineRankingPeriod.WEEKLY -> "Nenhuma partida online foi concluída nesta semana."
    OnlineRankingPeriod.MONTHLY -> "Nenhuma partida online foi concluída neste mês."
}

@Composable
private fun RankingReady(
    snapshot: OnlineRankingSnapshot,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val localPlayer = snapshot.localPlayer
    if (snapshot.entries.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                rankingEmptyMessage(snapshot.period),
                color = MenuColors.OnDark.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)
    ) {
        if (!compact) {
            CurrentPlayerSummary(
                entry = localPlayer,
                modifier = Modifier
                    .width(230.dp)
                    .fillMaxHeight()
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)
        ) {
            if (compact && localPlayer != null) {
                item(key = "current-player-summary") {
                    CompactCurrentPlayerSummary(localPlayer)
                }
            }
            items(snapshot.entries, key = { it.playerId }) { entry ->
                RankingEntryRow(
                    entry = entry,
                    isCurrentPlayer = entry.playerId == snapshot.localPlayerId,
                    compact = compact
                )
            }
        }
    }
}

@Composable
private fun CurrentPlayerSummary(entry: OnlineRankingEntry?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MenuColors.InkPanel,
        shape = MenuShapes.Card,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MenuColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Text(
                "SUA POSIÇÃO",
                color = MenuColors.OnDarkFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MenuColors.Gold.copy(alpha = 0.14f), CircleShape)
                    .border(2.dp, MenuColors.Gold.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    entry?.position?.let { "#$it" } ?: "-",
                    color = MenuColors.Gold,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            OnlineAvatarView(
                avatarId = entry?.avatarUrl,
                playerName = entry?.playerName ?: "Jogador",
                size = 56.dp
            )
            if (entry == null) {
                Text(
                    "Fora do top 50",
                    color = MenuColors.OnDarkMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            entry?.let {
                Text(
                    "${it.totalWins} vitórias em ${it.totalMatches} partidas",
                    color = MenuColors.OnDark,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
                Text(
                    "${it.winRatePercent}% de aproveitamento  |  ${it.xp} XP",
                    color = MenuColors.TableGreenLight,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun CompactCurrentPlayerSummary(entry: OnlineRankingEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MenuColors.TableGreenLight.copy(alpha = 0.16f), MenuShapes.Card)
            .border(1.dp, MenuColors.TableGreenLight.copy(alpha = 0.35f), MenuShapes.Card)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MenuColors.TableGreenLight.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("#${entry.position}", color = MenuColors.TableGreenLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Você", color = MenuColors.TableGreenLight, fontWeight = FontWeight.Bold)
        }
        Text("${entry.totalWins} vitórias  |  ${entry.xp} XP", color = MenuColors.OnDark, fontSize = 12.sp)
    }
}

@Composable
private fun RankingEntryRow(
    entry: OnlineRankingEntry,
    isCurrentPlayer: Boolean,
    compact: Boolean
) {
    val medalColor = when (entry.position) {
        1 -> MenuColors.Gold
        2 -> MenuColors.OnDark.copy(alpha = 0.55f)
        3 -> Color(0xFFCD7F32)
        else -> MenuColors.TableGreenLight
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrentPlayer) Modifier.border(1.dp, MenuColors.TableGreenLight, MenuShapes.Card)
                else Modifier
            ),
        color = if (isCurrentPlayer) Color(0xF0193326) else MenuColors.InkPanel,
        shape = MenuShapes.Card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 52.dp else 64.dp)
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 32.dp else 38.dp)
                    .background(medalColor.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${entry.position}",
                    color = medalColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }
            OnlineAvatarView(
                avatarId = entry.avatarUrl,
                playerName = entry.playerName,
                size = if (compact) 32.dp else 38.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.playerName,
                    color = if (isCurrentPlayer) MenuColors.TableGreenLight else MenuColors.OnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 13.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "C ${entry.cachetaWins}  |  B ${entry.buracoWins}  |  T ${entry.trancaWins}  |  Sequência ${entry.bestStreak}",
                    color = MenuColors.OnDarkMuted,
                    fontSize = if (compact) 10.sp else 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${entry.totalWins} vitórias",
                    color = MenuColors.Gold,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 14.sp,
                    maxLines = 1
                )
                Text(
                    "${entry.totalMatches} partidas  |  ${entry.winRatePercent}%  |  ${entry.xp} XP",
                    color = MenuColors.OnDarkMuted,
                    fontSize = if (compact) 9.sp else 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RankingLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MenuStatusMessage(text = "Atualizando classificação...")
    }
}

@Composable
private fun RankingError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(message, color = MenuColors.OnDark, textAlign = TextAlign.Center)
            MenuFilledButton(
                text = "Tentar novamente",
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 44.dp),
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

private val rankingPreview = OnlineRankingSnapshot(
    localPlayerId = "player-2",
    entries = listOf(
        OnlineRankingEntry(1, "player-1", "MesaReal", "builtin:gold", 18, 27, 8, 5, 5, 6, 3, 2125, null),
        OnlineRankingEntry(2, "player-2", "Jogador atual", "builtin:sapphire", 14, 23, 4, 7, 3, 4, 2, 1675, null),
        OnlineRankingEntry(3, "player-3", "Canastra Limpa", "builtin:ruby", 11, 22, 2, 3, 6, 3, 0, 1275, null)
    )
)

// ─── Previews ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Ranking - tablet/paisagem")
@Preview(
    showBackground = true,
    device = "spec:width=800dp,height=360dp,dpi=320",
    fontScale = 1.5f,
    name = "Ranking compacto - fonte grande"
)
@Composable
private fun OnlineRankingPreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Ready(rankingPreview),
            selectedPeriod = OnlineRankingPeriod.OVERALL,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Ranking - celular compacto")
@Composable
private fun OnlineRankingCompactPreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Ready(rankingPreview),
            selectedPeriod = OnlineRankingPeriod.WEEKLY,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Ranking - vazio")
@Composable
private fun OnlineRankingEmptyPreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Ready(
                OnlineRankingSnapshot(localPlayerId = "player-1", entries = emptyList())
            ),
            selectedPeriod = OnlineRankingPeriod.MONTHLY,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Ranking - carregando")
@Composable
private fun OnlineRankingLoadingPreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Loading,
            selectedPeriod = OnlineRankingPeriod.OVERALL,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Ranking - erro")
@Composable
private fun OnlineRankingErrorPreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Error("Não foi possível carregar o ranking agora. Confira sua conexão."),
            selectedPeriod = OnlineRankingPeriod.OVERALL,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 812, heightDp = 375, name = "Ranking - celular paisagem")
@Composable
private fun OnlineRankingLandscapePreview() {
    MaterialTheme {
        OnlineRankingContent(
            state = OnlineRankingUiState.Ready(rankingPreview),
            selectedPeriod = OnlineRankingPeriod.OVERALL,
            onPeriodSelected = {},
            onBack = {},
            onRetry = {}
        )
    }
}
