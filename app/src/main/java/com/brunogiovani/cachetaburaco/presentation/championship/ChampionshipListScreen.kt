package com.brunogiovani.cachetaburaco.presentation.championship

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.domain.models.Championship
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipCadence
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStatus
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.PlayerLevel
import com.brunogiovani.cachetaburaco.domain.repositories.ChampionshipRepository
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuBadge
import com.brunogiovani.cachetaburaco.presentation.components.MenuChipOption
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuMetrics
import com.brunogiovani.cachetaburaco.presentation.components.MenuSectionCard
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot
import kotlinx.coroutines.launch

// create_championship exige nome entre 2 e 40 chars; join_championship recebe
// sempre um codigo de 6 chars (md5 truncado, gerado so no servidor -- ver
// migration 0034/0049).
private const val MIN_CHAMPIONSHIP_NAME_LENGTH = 2
private const val MAX_CHAMPIONSHIP_NAME_LENGTH = 40
private const val CHAMPIONSHIP_CODE_LENGTH = 6

internal sealed interface ChampionshipListUiState {
    data object Loading : ChampionshipListUiState
    data class Ready(val championships: List<Championship>) : ChampionshipListUiState
    data class Error(val message: String) : ChampionshipListUiState
}

// Campeonatos (Fase 6): igual sala privada, nao existe lista publica pra
// descobrir -- so cria (vira host) ou entra com o codigo que alguem te deu.
@Composable
fun ChampionshipListScreen(
    playerName: String,
    repository: ChampionshipRepository,
    onBack: () -> Unit,
    onOpenChampionship: (Championship) -> Unit
) {
    var reloadRequest by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ChampionshipListUiState>(ChampionshipListUiState.Loading) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playerName, reloadRequest) {
        state = ChampionshipListUiState.Loading
        state = runCatching { repository.listMyChampionships(playerName) }
            .fold(
                onSuccess = { ChampionshipListUiState.Ready(it) },
                onFailure = {
                    ChampionshipListUiState.Error("Não foi possível carregar seus campeonatos agora. Confira sua conexão.")
                }
            )
    }

    var isCreating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var isJoining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }

    ChampionshipListContent(
        state = state,
        onBack = onBack,
        onRetry = { reloadRequest++ },
        onOpenChampionship = onOpenChampionship,
        isCreating = isCreating,
        createError = createError,
        onCreate = { name, gameType, cadence, level ->
            createError = null
            isCreating = true
            scope.launch {
                runCatching { repository.createChampionship(playerName, name, gameType, cadence, level) }
                    .onSuccess { reloadRequest++ }
                    .onFailure { createError = createChampionshipFeedbackFor(it) }
                isCreating = false
            }
        },
        isJoining = isJoining,
        joinError = joinError,
        onJoin = { code ->
            joinError = null
            isJoining = true
            scope.launch {
                runCatching { repository.joinChampionship(playerName, code) }
                    .onSuccess { reloadRequest++ }
                    .onFailure { joinError = joinChampionshipFeedbackFor(it) }
                isJoining = false
            }
        }
    )
}

internal fun createChampionshipFeedbackFor(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        "ADMIN_REQUIRED" in message -> "Só o administrador do jogo pode criar campeonatos."
        else -> "Não foi possível criar o campeonato agora."
    }
}

internal fun joinChampionshipFeedbackFor(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        "LEVEL_MISMATCH" in message -> "Esse campeonato é só pra um nível de jogador diferente do seu."
        "CHAMPIONSHIP_FINISHED" in message -> "Esse campeonato já foi encerrado."
        else -> "Não foi possível entrar no campeonato. Confira o código."
    }
}

@Composable
internal fun ChampionshipListContent(
    state: ChampionshipListUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenChampionship: (Championship) -> Unit,
    isCreating: Boolean,
    createError: String?,
    onCreate: (name: String, gameType: GameType, cadence: ChampionshipCadence, level: PlayerLevel?) -> Unit,
    isJoining: Boolean,
    joinError: String?,
    onJoin: (code: String) -> Unit
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
            MenuTopBar(title = "Campeonatos", onBack = onBack, subtitle = "Por pontos, entre quem tem o código")
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    CreateChampionshipCard(isCreating = isCreating, errorMessage = createError, onCreate = onCreate)
                }
                item {
                    JoinChampionshipCard(isJoining = isJoining, errorMessage = joinError, onJoin = onJoin)
                }
                when (state) {
                    ChampionshipListUiState.Loading -> item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            MenuStatusMessage(text = "Carregando seus campeonatos...")
                        }
                    }
                    is ChampionshipListUiState.Error -> item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(state.message, color = MenuColors.OnDark, fontSize = 13.sp)
                            MenuFilledButton(text = "Tentar novamente", onClick = onRetry, containerColor = MenuColors.TableGreenLight)
                        }
                    }
                    is ChampionshipListUiState.Ready -> {
                        if (state.championships.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                    MenuStatusMessage(
                                        text = "Você ainda não está em nenhum campeonato",
                                        caption = "Crie um novo ou entre com o código de quem já tem um"
                                    )
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "Meus campeonatos",
                                    color = MenuColors.OnDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items(state.championships, key = { it.id }) { championship ->
                                ChampionshipRow(championship = championship, onClick = { onOpenChampionship(championship) })
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

// Visivel pra qualquer jogador, mas so funciona de verdade pro admin do jogo
// -- create_championship rejeita no servidor (ADMIN_REQUIRED) e a mensagem
// de erro explica isso, em vez de esconder o card so pra quem e admin (o app
// nao tem hoje um jeito confiavel de saber isso sem chamar o servidor).
@Composable
private fun CreateChampionshipCard(
    isCreating: Boolean,
    errorMessage: String?,
    onCreate: (name: String, gameType: GameType, cadence: ChampionshipCadence, level: PlayerLevel?) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var gameType by rememberSaveable { mutableStateOf(GameType.CACHETA) }
    var cadence by rememberSaveable { mutableStateOf(ChampionshipCadence.MANUAL) }
    var level by rememberSaveable { mutableStateOf<PlayerLevel?>(null) }
    val canCreate = name.trim().length >= MIN_CHAMPIONSHIP_NAME_LENGTH && !isCreating

    MenuSectionCard(title = "Criar campeonato") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_CHAMPIONSHIP_NAME_LENGTH) },
                label = { Text("Nome do campeonato", color = Color.White.copy(alpha = 0.55f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MenuColors.TableGreenLight,
                    unfocusedBorderColor = MenuColors.BorderStrong,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MenuColors.TableGreenLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameType.entries.forEach { type ->
                    MenuChipOption(
                        label = championshipGameTypeLabel(type),
                        isSelected = type == gameType,
                        onClick = { gameType = type }
                    )
                }
            }
            Text("Cadência", color = MenuColors.OnDarkFaint, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampionshipCadence.entries.forEach { option ->
                    MenuChipOption(
                        label = championshipCadenceLabel(option),
                        isSelected = option == cadence,
                        onClick = { cadence = option }
                    )
                }
            }
            Text("Nível (opcional -- livre aceita todo mundo)", color = MenuColors.OnDarkFaint, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuChipOption(label = "Livre", isSelected = level == null, onClick = { level = null })
                PlayerLevel.entries.forEach { option ->
                    MenuChipOption(
                        label = playerLevelLabel(option),
                        isSelected = option == level,
                        onClick = { level = option }
                    )
                }
            }
            errorMessage?.let { Text(it, color = MenuColors.Red, fontSize = 12.sp) }
            MenuFilledButton(
                text = "Criar",
                onClick = { onCreate(name.trim(), gameType, cadence, level) },
                enabled = canCreate,
                loading = isCreating,
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

@Composable
private fun JoinChampionshipCard(
    isJoining: Boolean,
    errorMessage: String?,
    onJoin: (code: String) -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    val canJoin = code.trim().length == CHAMPIONSHIP_CODE_LENGTH && !isJoining

    MenuSectionCard(title = "Entrar com código") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(CHAMPIONSHIP_CODE_LENGTH) },
                label = { Text("Código do campeonato", color = Color.White.copy(alpha = 0.55f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MenuColors.TableGreenLight,
                    unfocusedBorderColor = MenuColors.BorderStrong,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MenuColors.TableGreenLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
            errorMessage?.let { Text(it, color = MenuColors.Red, fontSize = 12.sp) }
            MenuFilledButton(
                text = "Entrar",
                onClick = { onJoin(code.trim()) },
                enabled = canJoin,
                loading = isJoining,
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

@Composable
private fun ChampionshipRow(championship: Championship, onClick: () -> Unit) {
    val levelText = championship.level?.let { "Nível ${playerLevelLabel(it)}" } ?: "Nível livre"

    Card(
        colors = CardDefaults.cardColors(containerColor = MenuColors.InkPanel),
        shape = MenuShapes.Card,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MenuColors.Border, MenuShapes.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(championship.name, color = MenuColors.OnDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (championship.isHost) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MenuBadge(text = "HOST", color = MenuColors.Gold)
                    }
                    if (championship.status == ChampionshipStatus.FINISHED) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MenuBadge(text = "ENCERRADO", color = MenuColors.OnDarkFaint)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${championshipGameTypeLabel(championship.gameType)} · ${championshipCadenceLabel(championship.cadence)} · $levelText",
                    color = MenuColors.OnDarkFaint,
                    fontSize = 11.sp
                )
                Text(
                    "Código ${championship.code} · ${championship.participantCount} participante(s)",
                    color = MenuColors.OnDarkFaint,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // MenuFilledButton sempre pede fillMaxWidth por dentro -- sem uma
            // largura fixa aqui ele disputa espaco com o Column(weight=1f) ao
            // lado e vence a medicao (é medido antes, por nao ter weight),
            // espremendo o texto do campeonato numa coluna de poucos pixels
            // (cada letra quebra numa linha). Mesmo ajuste ja feito em
            // LobbyScreen.kt pro botao "Entrar" da lista de salas.
            MenuFilledButton(
                text = "Ver",
                onClick = onClick,
                containerColor = MenuColors.TableGreenLight,
                modifier = Modifier.width(96.dp)
            )
        }
    }
}

private fun championshipGameTypeLabel(type: GameType): String = when (type) {
    GameType.CACHETA -> "Cacheta"
    GameType.BURACO -> "Buraco"
    GameType.TRANCA -> "Tranca"
}

private fun championshipCadenceLabel(cadence: ChampionshipCadence): String = when (cadence) {
    ChampionshipCadence.WEEKLY -> "Semanal"
    ChampionshipCadence.MONTHLY -> "Mensal"
    ChampionshipCadence.MANUAL -> "Livre"
}

// internal (nao private): reaproveitado em LobbyScreen.kt pro seletor de
// nivel da sala usar o mesmo rotulo do seletor de nivel de campeonato.
internal fun playerLevelLabel(level: PlayerLevel): String = when (level) {
    PlayerLevel.NOOB -> "Iniciante"
    PlayerLevel.MID -> "Intermediário"
    PlayerLevel.HARD -> "Avançado"
    PlayerLevel.EXPERT -> "Expert"
}

// ─── Previews ─────────────────────────────────────────────────────────────

private val previewChampionships = listOf(
    Championship(
        id = "c1",
        code = "AB12CD",
        name = "Liga da Sexta",
        gameType = GameType.BURACO,
        status = ChampionshipStatus.ACTIVE,
        cadence = ChampionshipCadence.WEEKLY,
        level = PlayerLevel.NOOB,
        isHost = true,
        participantCount = 5
    ),
    Championship(
        id = "c2",
        code = "9F3E7A",
        name = "Torneio da Firma",
        gameType = GameType.CACHETA,
        status = ChampionshipStatus.FINISHED,
        cadence = ChampionshipCadence.MONTHLY,
        level = PlayerLevel.EXPERT,
        isHost = false,
        participantCount = 8
    )
)

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Campeonatos - lista")
@Composable
private fun ChampionshipListPreview() {
    MaterialTheme {
        ChampionshipListContent(
            state = ChampionshipListUiState.Ready(previewChampionships),
            onBack = {},
            onRetry = {},
            onOpenChampionship = {},
            isCreating = false,
            createError = null,
            onCreate = { _, _, _, _ -> },
            isJoining = false,
            joinError = null,
            onJoin = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Campeonatos - vazio")
@Composable
private fun ChampionshipListEmptyPreview() {
    MaterialTheme {
        ChampionshipListContent(
            state = ChampionshipListUiState.Ready(emptyList()),
            onBack = {},
            onRetry = {},
            onOpenChampionship = {},
            isCreating = false,
            createError = null,
            onCreate = { _, _, _, _ -> },
            isJoining = false,
            joinError = null,
            onJoin = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Campeonatos - carregando")
@Composable
private fun ChampionshipListLoadingPreview() {
    MaterialTheme {
        ChampionshipListContent(
            state = ChampionshipListUiState.Loading,
            onBack = {},
            onRetry = {},
            onOpenChampionship = {},
            isCreating = false,
            createError = null,
            onCreate = { _, _, _, _ -> },
            isJoining = false,
            joinError = null,
            onJoin = {}
        )
    }
}
