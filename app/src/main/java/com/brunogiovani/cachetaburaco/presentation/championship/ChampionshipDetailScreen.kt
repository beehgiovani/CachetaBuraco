package com.brunogiovani.cachetaburaco.presentation.championship

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.domain.models.Championship
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipMatchSummary
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStandingEntry
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStatus
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.repositories.ChampionshipRepository
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuBadge
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuGroupLabel
import com.brunogiovani.cachetaburaco.presentation.components.MenuMetrics
import com.brunogiovani.cachetaburaco.presentation.components.MenuSectionCard
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import com.brunogiovani.cachetaburaco.presentation.components.OnlineAvatarView
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot
import kotlinx.coroutines.launch

internal sealed interface ChampionshipDetailUiState {
    data object Loading : ChampionshipDetailUiState
    data class Ready(
        val standings: List<ChampionshipStandingEntry>,
        val matches: List<ChampionshipMatchSummary>
    ) : ChampionshipDetailUiState
    data class Error(val message: String) : ChampionshipDetailUiState
}

@Composable
fun ChampionshipDetailScreen(
    playerName: String,
    championship: Championship,
    repository: ChampionshipRepository,
    onBack: () -> Unit
) {
    var reloadRequest by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ChampionshipDetailUiState>(ChampionshipDetailUiState.Loading) }
    var isFinished by remember { mutableStateOf(championship.status == ChampionshipStatus.FINISHED) }
    var isFinishing by remember { mutableStateOf(false) }
    var finishError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(championship.id, reloadRequest) {
        state = ChampionshipDetailUiState.Loading
        state = runCatching {
            val standings = repository.listStandings(playerName, championship.id)
            val matches = repository.listMatches(playerName, championship.id)
            standings to matches
        }.fold(
            onSuccess = { (standings, matches) -> ChampionshipDetailUiState.Ready(standings, matches) },
            onFailure = { ChampionshipDetailUiState.Error("Não foi possível carregar o campeonato agora. Confira sua conexão.") }
        )
    }

    ChampionshipDetailContent(
        championship = championship,
        isFinished = isFinished,
        state = state,
        onBack = onBack,
        onRetry = { reloadRequest++ },
        isFinishing = isFinishing,
        finishError = finishError,
        onFinish = {
            finishError = null
            isFinishing = true
            scope.launch {
                runCatching { repository.finishChampionship(playerName, championship.id) }
                    .onSuccess { isFinished = true }
                    .onFailure { finishError = "Não foi possível encerrar o campeonato agora." }
                isFinishing = false
            }
        }
    )
}

@Composable
internal fun ChampionshipDetailContent(
    championship: Championship,
    isFinished: Boolean,
    state: ChampionshipDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    isFinishing: Boolean,
    finishError: String?,
    onFinish: () -> Unit
) {
    MenuBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .widthIn(max = MenuMetrics.MaxContentWidth)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MenuTopBar(
                title = championship.name,
                onBack = onBack,
                subtitle = "${championshipGameTypeLabel(championship.gameType)} · Código ${championship.code}"
            )
            Spacer(modifier = Modifier.height(16.dp))

            when (state) {
                ChampionshipDetailUiState.Loading -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    MenuStatusMessage(text = "Carregando campeonato...")
                }

                is ChampionshipDetailUiState.Error -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.message, color = MenuColors.OnDark, textAlign = TextAlign.Center, fontSize = 13.sp)
                        MenuFilledButton(text = "Tentar novamente", onClick = onRetry, containerColor = MenuColors.TableGreenLight)
                    }
                }

                is ChampionshipDetailUiState.Ready -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (isFinished) {
                        item {
                            MenuBadge(text = "CAMPEONATO ENCERRADO", color = MenuColors.OnDarkFaint)
                        }
                    }
                    item {
                        MenuSectionCard(title = "Classificação") {
                            if (state.standings.isEmpty()) {
                                Text(
                                    "Nenhuma partida vinculada ainda concluída.",
                                    color = MenuColors.OnDarkMuted,
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.standings.forEach { entry -> StandingRow(entry) }
                                }
                            }
                        }
                    }
                    item {
                        MenuSectionCard(title = "Histórico de partidas") {
                            if (state.matches.isEmpty()) {
                                Text(
                                    "Nenhuma partida concluída neste campeonato ainda.",
                                    color = MenuColors.OnDarkMuted,
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.matches.forEach { match -> MatchRow(match) }
                                }
                            }
                        }
                    }
                    if (championship.isHost && !isFinished) {
                        item {
                            MenuSectionCard {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MenuGroupLabel("Zona do host")
                                    Text(
                                        "Encerrar o campeonato impede novas partidas e inscrições. A classificação continua disponível.",
                                        color = MenuColors.OnDarkMuted,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                    finishError?.let {
                                        Text(it, color = MenuColors.Red, fontSize = 12.sp, textAlign = TextAlign.Center)
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                    MenuFilledButton(
                                        text = "Encerrar campeonato",
                                        onClick = onFinish,
                                        loading = isFinishing,
                                        containerColor = MenuColors.Red
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SafeAdBannerSlot(compact = true, placement = AdPlacement.LOBBY, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingRow(entry: ChampionshipStandingEntry) {
    val medalColor = when (entry.position) {
        1 -> MenuColors.Gold
        2 -> MenuColors.OnDark.copy(alpha = 0.55f)
        3 -> Color(0xFFCD7F32)
        else -> MenuColors.TableGreenLight
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MenuColors.InkPanelSoft,
        shape = MenuShapes.Card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(medalColor.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${entry.position}", color = medalColor, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
            OnlineAvatarView(
                avatarId = entry.avatarUrl,
                playerName = entry.playerName,
                size = 32.dp,
                photoUrl = entry.avatarPhotoUrl
            )
            Text(
                entry.playerName,
                color = MenuColors.OnDark,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text("${entry.totalWins} vitórias", color = MenuColors.Gold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                Text("${entry.totalMatches} partidas", color = MenuColors.OnDarkMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MatchRow(match: ChampionshipMatchSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MenuColors.InkPanelSoft,
        shape = MenuShapes.Card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    match.winnerNickname?.let { "🏆 $it" } ?: "Time ${match.winnerTeam}",
                    color = MenuColors.OnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text("Vitória do time ${match.winnerTeam}", color = MenuColors.OnDarkFaint, fontSize = 11.sp)
            }
            Text(
                formatMatchDate(match.finishedAt) ?: "-",
                color = MenuColors.OnDarkMuted,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatMatchDate(iso: String?): String? {
    if (iso == null) return null
    return runCatching {
        val zone = java.time.ZoneId.systemDefault()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
        formatter.format(java.time.Instant.parse(iso).atZone(zone))
    }.getOrNull()
}

private fun championshipGameTypeLabel(type: GameType): String = when (type) {
    GameType.CACHETA -> "Cacheta"
    GameType.BURACO -> "Buraco"
    GameType.TRANCA -> "Tranca"
}

// ─── Previews ─────────────────────────────────────────────────────────────

private val previewChampionship = Championship(
    id = "c1",
    code = "AB12CD",
    name = "Liga da Sexta",
    gameType = GameType.BURACO,
    status = ChampionshipStatus.ACTIVE,
    isHost = true,
    participantCount = 4
)

private val previewStandings = listOf(
    ChampionshipStandingEntry(1, "p1", "MesaReal", null, null, 8, 10),
    ChampionshipStandingEntry(2, "p2", "Canastra Limpa", null, null, 5, 9),
    ChampionshipStandingEntry(3, "p3", "Jogador atual", null, null, 3, 8)
)

private val previewMatches = listOf(
    ChampionshipMatchSummary("m1", 1, "MesaReal", "2026-08-01T20:15:00Z"),
    ChampionshipMatchSummary("m2", 2, "Canastra Limpa", "2026-07-28T19:00:00Z")
)

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Campeonato - detalhe (host)")
@Composable
private fun ChampionshipDetailPreview() {
    MaterialTheme {
        ChampionshipDetailContent(
            championship = previewChampionship,
            isFinished = false,
            state = ChampionshipDetailUiState.Ready(previewStandings, previewMatches),
            onBack = {},
            onRetry = {},
            isFinishing = false,
            finishError = null,
            onFinish = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Campeonato - detalhe (participante)")
@Composable
private fun ChampionshipDetailParticipantPreview() {
    MaterialTheme {
        ChampionshipDetailContent(
            championship = previewChampionship.copy(isHost = false),
            isFinished = false,
            state = ChampionshipDetailUiState.Ready(previewStandings, previewMatches),
            onBack = {},
            onRetry = {},
            isFinishing = false,
            finishError = null,
            onFinish = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Campeonato - vazio")
@Composable
private fun ChampionshipDetailEmptyPreview() {
    MaterialTheme {
        ChampionshipDetailContent(
            championship = previewChampionship,
            isFinished = false,
            state = ChampionshipDetailUiState.Ready(emptyList(), emptyList()),
            onBack = {},
            onRetry = {},
            isFinishing = false,
            finishError = null,
            onFinish = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Campeonato - encerrado")
@Composable
private fun ChampionshipDetailFinishedPreview() {
    MaterialTheme {
        ChampionshipDetailContent(
            championship = previewChampionship.copy(status = ChampionshipStatus.FINISHED),
            isFinished = true,
            state = ChampionshipDetailUiState.Ready(previewStandings, previewMatches),
            onBack = {},
            onRetry = {},
            isFinishing = false,
            finishError = null,
            onFinish = {}
        )
    }
}
