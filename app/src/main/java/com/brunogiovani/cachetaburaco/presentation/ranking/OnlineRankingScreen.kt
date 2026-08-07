package com.brunogiovani.cachetaburaco.presentation.ranking

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingEntry
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineRankingRepository
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.OnlineAvatarView
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot

private val RankingGold = Color(0xFFFFD54F)
private val RankingGreen = Color(0xFF4CAF50)
private val RankingBlue = Color(0xFF42A5F5)
private val RankingPanel = Color(0xED101820)

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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp || maxHeight < 430.dp
        val pagePadding = if (compact) 10.dp else 18.dp

        Image(
            painter = painterResource(R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE6070B10), Color(0xF0071510))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
        ) {
            RankingHeader(compact = compact, onBack = onBack)
            RankingPeriodSelector(
                selectedPeriod = selectedPeriod,
                onPeriodSelected = onPeriodSelected,
                compact = compact
            )

            when (state) {
                OnlineRankingUiState.Loading -> RankingLoading(Modifier.weight(1f))
                is OnlineRankingUiState.Error -> RankingError(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f)
                )
                is OnlineRankingUiState.Ready -> RankingReady(
                    snapshot = state.snapshot,
                    compact = compact,
                    modifier = Modifier.weight(1f)
                )
            }

            SafeAdBannerSlot(compact = true, placement = AdPlacement.RANKING)
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
            .heightIn(min = if (compact) 36.dp else 42.dp)
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

@Composable
private fun RankingHeader(compact: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onBack) {
            Text("Voltar", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Ranking online",
                color = RankingGold,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 18.sp else 24.sp,
                maxLines = 1
            )
            if (!compact) {
                Text(
                    "Classificação por partidas concluídas",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(64.dp))
    }
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
                color = Color.White.copy(alpha = 0.72f),
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
private fun CurrentPlayerSummary(entry: OnlineRankingEntry?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = RankingPanel,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            OnlineAvatarView(
                avatarId = entry?.avatarUrl,
                playerName = entry?.playerName ?: "Jogador",
                size = 68.dp
            )
            Text("Sua posição", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
            Text(
                entry?.position?.let { "#$it" } ?: "Fora do top 50",
                color = RankingGold,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            entry?.let {
                Text(
                    "${it.totalWins} vitórias em ${it.totalMatches} partidas",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
                Text(
                    "${it.winRatePercent}% de aproveitamento  |  ${it.xp} XP",
                    color = RankingGreen,
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
            .background(RankingGreen.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Você está em #${entry.position}", color = RankingGreen, fontWeight = FontWeight.Bold)
        Text("${entry.totalWins} vitórias  |  ${entry.xp} XP", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun RankingEntryRow(
    entry: OnlineRankingEntry,
    isCurrentPlayer: Boolean,
    compact: Boolean
) {
    val medalColor = when (entry.position) {
        1 -> RankingGold
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> RankingBlue
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrentPlayer) Modifier.border(1.dp, RankingGreen, RoundedCornerShape(8.dp))
                else Modifier
            ),
        color = if (isCurrentPlayer) Color(0xF0193326) else RankingPanel,
        shape = RoundedCornerShape(8.dp)
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
                    color = if (isCurrentPlayer) RankingGreen else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 13.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "C ${entry.cachetaWins}  |  B ${entry.buracoWins}  |  T ${entry.trancaWins}  |  Sequência ${entry.bestStreak}",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = if (compact) 10.sp else 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${entry.totalWins} vitórias",
                    color = RankingGold,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 14.sp,
                    maxLines = 1
                )
                Text(
                    "${entry.totalMatches} partidas  |  ${entry.winRatePercent}%  |  ${entry.xp} XP",
                    color = Color.White.copy(alpha = 0.62f),
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = RankingGold)
            Spacer(Modifier.height(10.dp))
            Text("Atualizando classificação...", color = Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun RankingError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = RankingGreen)
            ) {
                Text("Tentar novamente")
            }
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

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
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
