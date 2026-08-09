package com.brunogiovani.cachetaburaco.presentation.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Player
import com.brunogiovani.cachetaburaco.domain.models.PointsMode
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuBadge
import com.brunogiovani.cachetaburaco.presentation.components.MenuChipOption
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuExpandableSection
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuGroupLabel
import com.brunogiovani.cachetaburaco.presentation.components.MenuMetrics
import com.brunogiovani.cachetaburaco.presentation.components.MenuOptionTile
import com.brunogiovani.cachetaburaco.presentation.components.MenuSectionCard
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuToggleRow
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

// AppState em MainActivity troca de tela com um "when" manual, entao sair do
// lobby e voltar recria o composable do zero -- sem rememberSaveable, um
// "Voltar" sem querer jogava fora toda regra que o host tinha acabado de
// escolher com cuidado.
private inline fun <reified T : Enum<T>> enumSaver(): Saver<T, String> =
    Saver(save = { it.name }, restore = { enumValueOf<T>(it) })

// Mesmo minimo exigido pelo banco (length > 0) seria fraco demais pra uma
// senha de verdade -- 4 caracteres bate com o tamanho minimo do proprio
// codigo da sala.
private const val MIN_ROOM_PASSWORD_LENGTH = 4

// create_match_room valida ^[A-Z0-9]{4,8}$ pro codigo -- limitar a digitacao
// aqui evita mandar pro servidor um codigo que ja sei de antemao que vai
// voltar INVALID_ROOM_CODE.
private const val MIN_ROOM_CODE_LENGTH = 4
private const val MAX_ROOM_CODE_LENGTH = 8

@Composable
fun LobbyScreen(
    isHosting: Boolean,
    singlePlayerMode: Boolean = false,
    networkRepository: LocalNetworkRepository,
    onBack: () -> Unit,
    onGameStarted: (MatchConfig) -> Unit
) {
    val player = FakeAuthRepository.getCurrentPlayer() ?: return

    // Tudo que o jogador escolhe antes da partida vira MatchConfig. Uso
    // rememberSaveable (nao so remember) porque sair do lobby e voltar recria
    // o composable do zero -- sem isso, um "Voltar" sem querer jogava fora
    // toda regra que o host tinha acabado de escolher com cuidado.
    // O lobby não valida jogada; quem manda nisso é o motor de regras.
    var selectedGameType by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(GameType.CACHETA) }
    var selectedPlayers by rememberSaveable { mutableIntStateOf(2) }
    var allowWildcards by rememberSaveable { mutableStateOf(true) }
    var allowDrawFromDiscard by rememberSaveable { mutableStateOf(true) }
    var allowCharutos by rememberSaveable { mutableStateOf(true) }
    var cachetaStartsWithDiscard by rememberSaveable { mutableStateOf(false) }
    var requireCleanCanastraToWin by rememberSaveable { mutableStateOf(true) }
    var autoMeldTrancaRedThrees by rememberSaveable { mutableStateOf(true) }
    var penalizeBlackThreesInHand by rememberSaveable { mutableStateOf(true) }
    var uniformCardPoints by rememberSaveable { mutableStateOf(false) }
    var autoSortHand by rememberSaveable { mutableStateOf(true) }
    var botDifficulty by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(BotDifficulty.NORMAL) }
    var pointsMode by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(PointsMode.FREE) }
    var selectedPointLimit by rememberSaveable { mutableIntStateOf(5) }
    var isPrivateRoom by rememberSaveable { mutableStateOf(false) }
    var roomPassword by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(selectedGameType) {
        selectedPointLimit = if (selectedGameType == GameType.CACHETA) 5 else 1500
        if (selectedGameType == GameType.CACHETA || singlePlayerMode) selectedPlayers = 2
        allowCharutos = selectedGameType != GameType.BURACO
        requireCleanCanastraToWin = selectedGameType == GameType.BURACO
    }

    val currentConfig = MatchConfig(
        gameType = selectedGameType,
        maxPlayers = if (singlePlayerMode) 2 else selectedPlayers,
        allowWildcards = allowWildcards,
        allowDrawFromDiscard = allowDrawFromDiscard,
        allowCharutos = allowCharutos,
        cachetaStartsWithDiscard = cachetaStartsWithDiscard,
        requireCleanCanastraToWin = requireCleanCanastraToWin,
        autoMeldTrancaRedThrees = autoMeldTrancaRedThrees,
        penalizeBlackThreesInHand = penalizeBlackThreesInHand,
        uniformCardPoints = uniformCardPoints,
        autoSortHand = autoSortHand,
        botDifficulty = botDifficulty,
        pointsMode = pointsMode,
        pointLimit = selectedPointLimit
    )

    // No modo contra a máquina, reaproveito a criação de sala e simulo um cliente.
    // Por isso a mesa fica fixa em 2 jogadores: jogador local x máquina.
    val discoveredRooms by networkRepository.discoveredRooms.collectAsState()
    val connectedClients by networkRepository.connectedClientsCount.collectAsState()
    val connectionStatus by networkRepository.connectionStatus.collectAsState()

    var gameStarted by remember { mutableStateOf(false) }
    var publishedConfig by remember { mutableStateOf<MatchConfig?>(null) }
    var isPublishing by remember { mutableStateOf(false) }
    var publicationError by remember { mutableStateOf<String?>(null) }
    var pendingPublishConfig by remember { mutableStateOf<MatchConfig?>(null) }
    var pendingJoinConfig by remember { mutableStateOf<MatchConfig?>(null) }
    var joinError by remember { mutableStateOf<String?>(null) }
    // Sala privada nao aparece na lista de descoberta, entao "entrar por
    // codigo" nao tem o MatchConfig de antemao como o fluxo normal (que usa o
    // config ja publicado na sala listada) -- so sei o config depois que
    // join_match_room responder, via joinedRoomConfig.
    var isJoiningByCode by remember { mutableStateOf(false) }
    val joinedRoomConfig by networkRepository.joinedRoomConfig.collectAsState()

    LaunchedEffect(connectionStatus, pendingPublishConfig) {
        val readyConfig = pendingPublishConfig ?: return@LaunchedEffect
        when (connectionStatus) {
            ConnectionStatus.ROOM_READY,
            ConnectionStatus.CONNECTED -> {
                pendingPublishConfig = null
                isPublishing = false
                if (singlePlayerMode) {
                    gameStarted = true
                    onGameStarted(readyConfig)
                } else {
                    publishedConfig = readyConfig
                }
            }
            ConnectionStatus.ERROR -> {
                pendingPublishConfig = null
                isPublishing = false
                networkRepository.stopHosting()
                publishedConfig = null
                publicationError = "Não foi possível publicar a sala. Tente novamente."
            }
            else -> Unit
        }
    }

    LaunchedEffect(connectionStatus, pendingJoinConfig) {
        val joinedConfig = pendingJoinConfig ?: return@LaunchedEffect
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> {
                pendingJoinConfig = null
                gameStarted = true
                onGameStarted(joinedConfig)
            }
            ConnectionStatus.ERROR,
            ConnectionStatus.HOST_DISCONNECTED -> {
                pendingJoinConfig = null
                joinError = "Não foi possível entrar na sala. Verifique a rede e tente novamente."
            }
            else -> Unit
        }
    }

    LaunchedEffect(connectionStatus, isJoiningByCode, joinedRoomConfig) {
        if (!isJoiningByCode) return@LaunchedEffect
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> {
                val config = joinedRoomConfig ?: return@LaunchedEffect
                isJoiningByCode = false
                gameStarted = true
                onGameStarted(config)
            }
            ConnectionStatus.ERROR,
            ConnectionStatus.HOST_DISCONNECTED -> {
                isJoiningByCode = false
                joinError = "Não foi possível entrar na sala. Confira o código e a senha."
            }
            else -> Unit
        }
    }

    LaunchedEffect(isHosting) {
        if (!isHosting) {
            networkRepository.startDiscovery()
        }
    }

    val publishOrStart = {
        val frozenConfig = currentConfig
        publicationError = null
        isPublishing = true
        pendingPublishConfig = frozenConfig

        val effectivePassword = if (isPrivateRoom && networkRepository.isOnlineTransport) {
            roomPassword.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        runCatching {
            // Contra a máquina esta chamada apenas entrega as regras ao adaptador; nenhuma sala Wi-Fi é anunciada.
            networkRepository.startHosting(player.name, config = frozenConfig, password = effectivePassword)
        }.onFailure { error ->
            pendingPublishConfig = null
            networkRepository.stopHosting()
            publishedConfig = null
            publicationError = error.message ?: "Não foi possível publicar a sala. Tente novamente."
            isPublishing = false
        }
        Unit
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!gameStarted) {
                if (isHosting) networkRepository.stopHosting()
                else {
                    networkRepository.stopDiscovery()
                    networkRepository.disconnect()
                }
            } else {
                if (!isHosting) networkRepository.stopDiscovery()
            }
        }
    }

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
                title = when {
                    singlePlayerMode -> "Jogar contra a máquina"
                    isHosting -> "Criar sala"
                    else -> "Procurar sala"
                },
                onBack = onBack
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isHosting) {
                val visibleConfig = publishedConfig ?: currentConfig
                HostPanel(
                    config = visibleConfig,
                    connectedClients = connectedClients,
                    singlePlayerMode = singlePlayerMode,
                    isPublished = publishedConfig != null,
                    isPublishing = isPublishing,
                    publicationError = publicationError,
                    selectedGameType = selectedGameType,
                    onGameTypeChange = { selectedGameType = it },
                    selectedPlayers = selectedPlayers,
                    onPlayersChange = { selectedPlayers = it },
                    allowWildcards = allowWildcards,
                    onWildcardsChange = { allowWildcards = it },
                    allowDrawFromDiscard = allowDrawFromDiscard,
                    onDrawDiscardChange = { allowDrawFromDiscard = it },
                    allowCharutos = allowCharutos,
                    onCharutosChange = { allowCharutos = it },
                    cachetaStartsWithDiscard = cachetaStartsWithDiscard,
                    onCachetaStartsWithDiscardChange = { cachetaStartsWithDiscard = it },
                    requireCleanCanastraToWin = requireCleanCanastraToWin,
                    onRequireCleanCanastraToWinChange = { requireCleanCanastraToWin = it },
                    autoMeldTrancaRedThrees = autoMeldTrancaRedThrees,
                    onAutoMeldTrancaRedThreesChange = { autoMeldTrancaRedThrees = it },
                    penalizeBlackThreesInHand = penalizeBlackThreesInHand,
                    onPenalizeBlackThreesInHandChange = { penalizeBlackThreesInHand = it },
                    uniformCardPoints = uniformCardPoints,
                    onUniformCardPointsChange = { uniformCardPoints = it },
                    autoSortHand = autoSortHand,
                    onAutoSortChange = { autoSortHand = it },
                    botDifficulty = botDifficulty,
                    onBotDifficultyChange = { botDifficulty = it },
                    pointsMode = pointsMode,
                    onPointsModeChange = { pointsMode = it },
                    selectedPointLimit = selectedPointLimit,
                    onPointLimitChange = { selectedPointLimit = it },
                    isOnlineTransport = networkRepository.isOnlineTransport,
                    isPrivateRoom = isPrivateRoom,
                    onPrivateRoomChange = { isPrivateRoom = it },
                    roomPassword = roomPassword,
                    onRoomPasswordChange = { roomPassword = it },
                    onPublish = publishOrStart,
                    onStart = {
                        val configToStart = publishedConfig
                        val requiredClients = (configToStart?.maxPlayers ?: 0) - 1
                        if (configToStart != null && connectedClients >= requiredClients) {
                            gameStarted = true
                            onGameStarted(configToStart)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                ClientPanel(
                    discoveredRooms = discoveredRooms,
                    isJoining = pendingJoinConfig != null || isJoiningByCode,
                    joinError = joinError,
                    isOnlineTransport = networkRepository.isOnlineTransport,
                    onJoin = { room ->
                        val roomConfig = room.config
                        if (roomConfig == null) {
                            joinError = "Esta sala não publicou as regras corretamente. Aguarde o host republicar."
                        } else {
                            joinError = null
                            pendingJoinConfig = roomConfig
                            networkRepository.connectToRoom(room.host, room.port)
                        }
                    },
                    onJoinByCode = { roomCode, password ->
                        joinError = null
                        isJoiningByCode = true
                        networkRepository.connectToRoom(roomCode, port = 0, password = password)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

// Painel do Host
@Composable
private fun HostPanel(
    config: MatchConfig,
    connectedClients: Int,
    modifier: Modifier = Modifier,
    singlePlayerMode: Boolean = false,
    isPublished: Boolean,
    isPublishing: Boolean,
    publicationError: String?,
    selectedGameType: GameType,
    onGameTypeChange: (GameType) -> Unit,
    selectedPlayers: Int,
    onPlayersChange: (Int) -> Unit,
    allowWildcards: Boolean,
    onWildcardsChange: (Boolean) -> Unit,
    allowDrawFromDiscard: Boolean,
    onDrawDiscardChange: (Boolean) -> Unit,
    allowCharutos: Boolean,
    onCharutosChange: (Boolean) -> Unit,
    cachetaStartsWithDiscard: Boolean,
    onCachetaStartsWithDiscardChange: (Boolean) -> Unit,
    requireCleanCanastraToWin: Boolean,
    onRequireCleanCanastraToWinChange: (Boolean) -> Unit,
    autoMeldTrancaRedThrees: Boolean,
    onAutoMeldTrancaRedThreesChange: (Boolean) -> Unit,
    penalizeBlackThreesInHand: Boolean,
    onPenalizeBlackThreesInHandChange: (Boolean) -> Unit,
    uniformCardPoints: Boolean,
    onUniformCardPointsChange: (Boolean) -> Unit,
    autoSortHand: Boolean,
    onAutoSortChange: (Boolean) -> Unit,
    botDifficulty: BotDifficulty,
    onBotDifficultyChange: (BotDifficulty) -> Unit,
    pointsMode: PointsMode,
    onPointsModeChange: (PointsMode) -> Unit,
    selectedPointLimit: Int,
    onPointLimitChange: (Int) -> Unit,
    isOnlineTransport: Boolean = false,
    isPrivateRoom: Boolean = false,
    onPrivateRoomChange: (Boolean) -> Unit = {},
    roomPassword: String = "",
    onRoomPasswordChange: (String) -> Unit = {},
    onPublish: () -> Unit,
    onStart: () -> Unit
) {
    val requiredClients = config.maxPlayers - 1
    val canStart = isPublished && connectedClients >= requiredClients
    val privatePasswordValid = roomPassword.trim().length >= MIN_ROOM_PASSWORD_LENGTH
    val canPublish = !isPrivateRoom || privatePasswordValid

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        // So pra testes instrumentados conseguirem rolar ate itens que ainda
        // nao foram compostos (LazyColumn so materializa o que esta visivel);
        // performScrollToNode() precisa de uma referencia ao container.
        modifier = modifier.testTag("hostPanelLazyColumn")
    ) {
        if (!isPublished) item {
            MenuSectionCard(title = "Modo de jogo", stepNumber = 1) {
                GameTypeOptions(
                    selectedGameType = selectedGameType,
                    onGameTypeChange = onGameTypeChange
                )
            }
        }

        if (!isPublished) item {
            MenuSectionCard(title = "Jogadores", stepNumber = 2) {
                val options = if (selectedGameType == GameType.CACHETA || singlePlayerMode) listOf(2) else listOf(2, 4)
                if (options.size == 1) {
                    Text(
                        if (singlePlayerMode) "Modo contra a máquina usa 2 jogadores (você x máquina)." else "Cacheta é sempre 2 jogadores (solo).",
                        color = MenuColors.OnDarkMuted,
                        fontSize = 12.sp
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { count ->
                            MenuChipOption(
                                label = if (count == 2) "2 - Solo" else "4 - Duplas",
                                isSelected = count == selectedPlayers,
                                onClick = { onPlayersChange(count) }
                            )
                        }
                    }
                }
            }
        }

        if (!isPublished) item {
            MenuExpandableSection(title = "Regras da partida", stepNumber = 3) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuGroupLabel("Curinga e baralho")
                    if (selectedGameType == GameType.CACHETA) {
                        Text(
                            text = "Cada jogador recebe 9 cartas. A décima só aparece durante a compra.",
                            color = MenuColors.OnDarkMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        MenuToggleRow(
                            label = "Curinga pela Vira",
                            description = "Carta acima da vira, no mesmo naipe",
                            checked = allowWildcards,
                            onCheckedChange = onWildcardsChange
                        )
                        MenuToggleRow(
                            label = "Lixo Inicial",
                            description = if (cachetaStartsWithDiscard)
                                "A vira também começa no lixo"
                            else
                                "Lixo começa vazio; vira fica separada",
                            checked = cachetaStartsWithDiscard,
                            onCheckedChange = onCachetaStartsWithDiscardChange
                        )
                    } else {
                        Text(
                            text = "No ${selectedGameType.name.lowercase().replaceFirstChar { it.uppercase() }}, o 2 é curinga fixo e sempre suja a canastra.",
                            color = MenuColors.OnDarkMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    MenuGroupLabel("Compra e jogadas")
                    MenuToggleRow(
                        label = "Compra do Lixo",
                        description = if (selectedGameType == GameType.TRANCA)
                            "3 preto tranca automaticamente"
                        else
                            "Permitir comprar do lixo",
                        checked = allowDrawFromDiscard,
                        onCheckedChange = onDrawDiscardChange
                    )
                    if (selectedGameType != GameType.CACHETA) {
                        MenuToggleRow(
                            label = "Charutos / Trincas",
                            description = if (allowCharutos)
                                "Permite jogos de cartas do mesmo valor"
                            else
                                "Somente sequências do mesmo naipe",
                            checked = allowCharutos,
                            onCheckedChange = onCharutosChange
                        )
                    }

                    if (selectedGameType != GameType.CACHETA) {
                        MenuGroupLabel("Batida e pontuação das cartas")
                    }
                    if (selectedGameType == GameType.BURACO) {
                        MenuToggleRow(
                            label = "Canastra Limpa",
                            description = if (requireCleanCanastraToWin)
                                "Obrigatória para bater"
                            else
                                "Qualquer canastra permite bater",
                            checked = requireCleanCanastraToWin,
                            onCheckedChange = onRequireCleanCanastraToWinChange
                        )
                    }
                    if (selectedGameType == GameType.TRANCA) {
                        MenuToggleRow(
                            label = "3 Vermelho",
                            description = if (autoMeldTrancaRedThrees)
                                "Baixa automaticamente e vale 100"
                            else
                                "Fica na mão para controle manual",
                            checked = autoMeldTrancaRedThrees,
                            onCheckedChange = onAutoMeldTrancaRedThreesChange
                        )
                        MenuToggleRow(
                            label = "3 Preto Pesado",
                            description = if (penalizeBlackThreesInHand)
                                "3 preto na mão desconta 100 pontos"
                            else
                                "3 preto na mão usa valor normal da carta",
                            checked = penalizeBlackThreesInHand,
                            onCheckedChange = onPenalizeBlackThreesInHandChange
                        )
                    }
                    if (selectedGameType != GameType.CACHETA) {
                        MenuToggleRow(
                            label = "Cartas Uniformes",
                            description = if (uniformCardPoints)
                                "Todas as cartas valem 10 pts"
                            else
                                "Valores variáveis (As=15, 2-7=5, etc.)",
                            checked = uniformCardPoints,
                            onCheckedChange = onUniformCardPointsChange
                        )
                    }

                    MenuGroupLabel("Preferências")
                    MenuToggleRow(
                        label = "Ordenar Mão Auto",
                        description = "Organiza cartas por naipe e valor",
                        checked = autoSortHand,
                        onCheckedChange = onAutoSortChange
                    )
                    if (singlePlayerMode) {
                        Text(
                            text = "Nível da máquina",
                            color = MenuColors.OnDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                        BotDifficultyOptions(
                            selectedDifficulty = botDifficulty,
                            onDifficultyChange = onBotDifficultyChange
                        )
                    }
                }
            }
        }

        if (!isPublished) item {
            MenuSectionCard(title = "Pontuação", stepNumber = 4) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MenuGroupLabel("Formato")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MenuChipOption(
                                label = "Gratuito",
                                isSelected = pointsMode == PointsMode.FREE,
                                onClick = { onPointsModeChange(PointsMode.FREE) }
                            )
                            MenuChipOption(
                                label = "Fichas",
                                isSelected = pointsMode == PointsMode.CHIPS,
                                onClick = { onPointsModeChange(PointsMode.CHIPS) }
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MenuGroupLabel(if (selectedGameType == GameType.CACHETA) "Vidas (limite)" else "Pontuação limite")
                        val options = if (selectedGameType == GameType.CACHETA) listOf(5, 10, 15) else listOf(1500, 3000, 5000)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            options.forEach { limit ->
                                MenuChipOption(
                                    label = if (selectedGameType == GameType.CACHETA) "$limit vidas" else "$limit pts",
                                    isSelected = limit == selectedPointLimit,
                                    onClick = { onPointLimitChange(limit) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isPublished && isOnlineTransport) item {
            MenuSectionCard(title = "Privacidade") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MenuToggleRow(
                        label = "Sala privada",
                        description = "Só quem tiver o código e a senha consegue entrar",
                        checked = isPrivateRoom,
                        onCheckedChange = onPrivateRoomChange
                    )
                    if (isPrivateRoom) {
                        RoomPasswordField(
                            password = roomPassword,
                            onPasswordChange = onRoomPasswordChange,
                            isError = roomPassword.isNotEmpty() && !privatePasswordValid
                        )
                    }
                }
            }
        }

        item {
            RuleSummaryCard(config = config)
        }

        item {
            when {
                singlePlayerMode -> PublishActionCard(
                    title = "Configuração pronta?",
                    description = "As regras serão fechadas e a partida começa em seguida.",
                    buttonLabel = "Concluir e jogar",
                    isPublishing = isPublishing,
                    errorMessage = publicationError,
                    onClick = onPublish
                )

                !isPublished -> PublishActionCard(
                    title = "Concluir criação",
                    description = if (isPrivateRoom)
                        "A sala só aparece para os outros jogadores depois desta confirmação. Compartilhe o código e a senha com quem vai jogar."
                    else
                        "A sala só aparece para os outros jogadores depois desta confirmação.",
                    buttonLabel = "Publicar sala",
                    isPublishing = isPublishing,
                    errorMessage = publicationError ?: if (isPrivateRoom && !privatePasswordValid && roomPassword.isNotEmpty())
                        "A senha precisa ter pelo menos $MIN_ROOM_PASSWORD_LENGTH caracteres."
                    else null,
                    enabled = canPublish,
                    onClick = onPublish
                )

                else -> PublishedRoomCard(
                    connectedClients = connectedClients,
                    requiredClients = requiredClients,
                    canStart = canStart,
                    onStart = onStart
                )
            }
        }
        item {
            SafeAdBannerSlot(
                compact = true,
                placement = AdPlacement.LOBBY,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun GameTypeOptions(
    selectedGameType: GameType,
    onGameTypeChange: (GameType) -> Unit
) {
    val largeFont = LocalDensity.current.fontScale >= 1.25f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackOptions = maxWidth < 600.dp || largeFont
        if (stackOptions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameType.entries.forEach { type ->
                    val (label, description) = gameTypeCopy(type)
                    MenuOptionTile(
                        title = label,
                        description = description,
                        isSelected = type == selectedGameType,
                        onClick = { onGameTypeChange(type) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameType.entries.forEach { type ->
                    val (label, description) = gameTypeCopy(type)
                    MenuOptionTile(
                        title = label,
                        description = description,
                        isSelected = type == selectedGameType,
                        onClick = { onGameTypeChange(type) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun gameTypeCopy(type: GameType): Pair<String, String> = when (type) {
    GameType.CACHETA -> "Cacheta" to "9 cartas - Individual"
    GameType.BURACO -> "Buraco" to "11 cartas - Solo/Duplas"
    GameType.TRANCA -> "Tranca" to "11 cartas - Solo/Duplas"
}

@Composable
private fun BotDifficultyOptions(
    selectedDifficulty: BotDifficulty,
    onDifficultyChange: (BotDifficulty) -> Unit
) {
    val largeFont = LocalDensity.current.fontScale >= 1.25f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackOptions = maxWidth < 600.dp || largeFont
        if (stackOptions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BotDifficulty.entries.forEach { difficulty ->
                    val profile = botDifficultyProfile(difficulty)
                    MenuOptionTile(
                        title = profile.label,
                        description = profile.description,
                        isSelected = selectedDifficulty == difficulty,
                        onClick = { onDifficultyChange(difficulty) },
                        accentColor = profile.color,
                        minHeight = 86.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotDifficulty.entries.forEach { difficulty ->
                    val profile = botDifficultyProfile(difficulty)
                    MenuOptionTile(
                        title = profile.label,
                        description = profile.description,
                        isSelected = selectedDifficulty == difficulty,
                        onClick = { onDifficultyChange(difficulty) },
                        accentColor = profile.color,
                        minHeight = 86.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private data class BotDifficultyProfile(val label: String, val description: String, val color: Color)

private fun botDifficultyProfile(difficulty: BotDifficulty): BotDifficultyProfile {
    return when (difficulty) {
        BotDifficulty.EASY -> BotDifficultyProfile(
            label = "Fácil",
            description = "Compra pouco e protege o básico",
            color = MenuColors.TableGreenLight
        )
        BotDifficulty.NORMAL -> BotDifficultyProfile(
            label = "Normal",
            description = "Compra lixo útil e monta jogos",
            color = MenuColors.Gold
        )
        BotDifficulty.HARD -> BotDifficultyProfile(
            label = "Difícil",
            description = "Lê a mesa e evita entregar carta",
            color = MenuColors.Red
        )
    }
}

@Composable
private fun PublishActionCard(
    title: String,
    description: String,
    buttonLabel: String,
    isPublishing: Boolean,
    errorMessage: String?,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    MenuSectionCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = MenuColors.OnDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                color = MenuColors.OnDarkMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    color = MenuColors.Red,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            MenuFilledButton(
                text = if (isPublishing) "Preparando..." else buttonLabel,
                onClick = onClick,
                enabled = enabled && !isPublishing,
                loading = isPublishing,
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

@Composable
private fun PublishedRoomCard(
    connectedClients: Int,
    requiredClients: Int,
    canStart: Boolean,
    onStart: () -> Unit
) {
    MenuSectionCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Sala publicada", color = MenuColors.OnDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                repeat(requiredClients) { index ->
                    val filled = index < connectedClients
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (filled) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
                Text(
                    "$connectedClients / $requiredClients conectado(s)",
                    color = MenuColors.OnDarkMuted,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            MenuFilledButton(
                text = if (canStart) "Iniciar partida" else "Aguardando jogadores...",
                onClick = onStart,
                enabled = canStart,
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

@Composable
private fun RoomPasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Senha da sala",
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {}
) {
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.55f)) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = if (isError) {
            { Text("Mínimo de $MIN_ROOM_PASSWORD_LENGTH caracteres.", color = MenuColors.Red) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MenuColors.TableGreenLight,
            unfocusedBorderColor = MenuColors.BorderStrong,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = MenuColors.TableGreenLight
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// Sala privada nunca aparece na lista de descoberta (RLS/RPC filtram por
// design) -- este e o unico jeito de entrar nela: quem recebeu o codigo e a
// senha de quem criou a sala digita aqui. Funciona tambem pra sala publica,
// que so ignora a senha se vier preenchida.
@Composable
private fun JoinByCodeCard(
    isJoining: Boolean,
    onJoin: (roomCode: String, password: String?) -> Unit
) {
    var roomCode by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canJoin = roomCode.trim().length >= MIN_ROOM_CODE_LENGTH && !isJoining

    MenuSectionCard(title = "Entrar com código") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it.uppercase().filter(Char::isLetterOrDigit).take(MAX_ROOM_CODE_LENGTH) },
                label = { Text("Código da sala", color = Color.White.copy(alpha = 0.55f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MenuColors.TableGreenLight,
                    unfocusedBorderColor = MenuColors.BorderStrong,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MenuColors.TableGreenLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
            RoomPasswordField(
                password = password,
                onPasswordChange = { password = it },
                isError = false,
                label = "Senha (se a sala tiver)",
                onImeAction = { if (canJoin) onJoin(roomCode.trim(), password.trim().takeIf { it.isNotEmpty() }) }
            )
            MenuFilledButton(
                text = "Entrar",
                onClick = { onJoin(roomCode.trim(), password.trim().takeIf { it.isNotEmpty() }) },
                enabled = canJoin,
                loading = isJoining,
                containerColor = MenuColors.TableGreenLight
            )
        }
    }
}

//Painel do Cliente (Discovery)
@Composable
private fun ClientPanel(
    discoveredRooms: List<DiscoveredRoom>,
    isJoining: Boolean,
    joinError: String?,
    onJoin: (DiscoveredRoom) -> Unit,
    modifier: Modifier = Modifier,
    isOnlineTransport: Boolean = false,
    onJoinByCode: (roomCode: String, password: String?) -> Unit = { _, _ -> }
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isOnlineTransport) {
            item {
                JoinByCodeCard(
                    isJoining = isJoining,
                    onJoin = onJoinByCode
                )
            }
        }
        if (joinError != null) {
            item {
                Text(
                    text = joinError,
                    color = MenuColors.Red,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
        }
        if (discoveredRooms.isEmpty()) {
            item {
                Spacer(modifier = Modifier.height(48.dp))
                MenuStatusMessage(
                    text = if (isOnlineTransport) "Procurando salas online..." else "Procurando salas na rede local...",
                    caption = if (isOnlineTransport) "Isso pode levar alguns segundos" else "Certifique-se de estar na mesma rede Wi-Fi"
                )
            }
        } else {
            item {
                Text(
                    "Salas disponíveis",
                    color = MenuColors.OnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            items(discoveredRooms) { room ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MenuColors.InkPanel),
                    shape = MenuShapes.Card,
                    modifier = Modifier.fillMaxWidth().border(1.dp, MenuColors.Border, MenuShapes.Card)
                ) {
                    val largeFont = LocalDensity.current.fontScale >= 1.25f
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val stackContent = maxWidth < 560.dp || largeFont
                        if (stackContent) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RoomDetails(
                                    roomName = room.serviceName.removePrefix("Room_"),
                                    host = room.host,
                                    config = room.config
                                )
                                MenuFilledButton(
                                    text = if (room.config == null) "Regras indisponíveis" else "Entrar",
                                    onClick = { onJoin(room) },
                                    enabled = room.config != null && !isJoining,
                                    loading = isJoining,
                                    containerColor = MenuColors.TableGreenLight
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RoomDetails(
                                    roomName = room.serviceName.removePrefix("Room_"),
                                    host = room.host,
                                    config = room.config,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                MenuFilledButton(
                                    text = if (room.config == null) "Regras indisponíveis" else "Entrar",
                                    onClick = { onJoin(room) },
                                    enabled = room.config != null && !isJoining,
                                    loading = isJoining,
                                    containerColor = MenuColors.TableGreenLight,
                                    // width fixo: MenuFilledButton sempre pede fillMaxWidth por dentro,
                                    // e aqui ele divide a linha com RoomDetails(weight = 1f).
                                    modifier = Modifier.width(148.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SafeAdBannerSlot(
                compact = true,
                placement = AdPlacement.LOBBY,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}


// Componentes de UI Auxiliares
@Composable
private fun RoomDetails(
    roomName: String,
    host: String,
    config: MatchConfig?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            roomName,
            color = MenuColors.OnDark,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Text(
            host,
            color = MenuColors.OnDarkFaint,
            fontSize = 11.sp
        )
        config?.let {
            Spacer(modifier = Modifier.height(6.dp))
            RuleSummaryText(config = it)
        } ?: run {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Regras não recebidas do host",
                color = MenuColors.Red,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun RuleSummaryCard(config: MatchConfig) {
    MenuSectionCard(title = "Regras da sala") {
        RuleSummaryText(config = config)
    }
}

// null = regra fixa do modo (informativa, não é liga/desliga) — vira badge dourado
// em vez de verde/cinza para não parecer uma opção que o host escolheu.
private data class RuleChip(val label: String, val enabled: Boolean?)

@Composable
private fun RuleSummaryText(config: MatchConfig) {
    val gameName = when (config.gameType) {
        GameType.CACHETA -> "Cacheta"
        GameType.BURACO -> "Buraco"
        GameType.TRANCA -> "Tranca"
    }
    // Cor de identidade propria por jogo na "capa da sala" -- antes os tres
    // modos usavam a mesma cor dourada e ficavam indistinguiveis de relance.
    val gameAccent = when (config.gameType) {
        GameType.CACHETA -> MenuColors.Gold
        GameType.BURACO -> MenuColors.TableGreenLight
        GameType.TRANCA -> MenuColors.Red
    }

    val chips = buildList {
        if (config.gameType == GameType.CACHETA) {
            add(RuleChip(if (config.allowWildcards) "Curinga: acima da vira" else "Sem curinga", config.allowWildcards))
            add(RuleChip(if (config.cachetaStartsWithDiscard) "Lixo abre com a vira" else "Lixo abre vazio", null))
        } else {
            add(RuleChip("2 é curinga fixo", null))
            add(RuleChip(if (config.allowCharutos) "Charutos permitidos" else "Só sequência, mesmo naipe", config.allowCharutos))
            add(RuleChip(if (config.requireCleanCanastraToWin) "Canastra limpa p/ bater" else "Pode bater suja", config.requireCleanCanastraToWin))
            if (config.gameType == GameType.TRANCA) {
                add(RuleChip("3 preto tranca o lixo", null))
                add(RuleChip(if (config.autoMeldTrancaRedThrees) "3 vermelho baixa sozinho" else "3 vermelho fica na mão", config.autoMeldTrancaRedThrees))
                add(RuleChip(if (config.penalizeBlackThreesInHand) "3 preto na mão: -100" else "3 preto na mão: normal", config.penalizeBlackThreesInHand))
            }
            add(RuleChip(if (config.uniformCardPoints) "Toda carta vale 10" else "Pontos tradicionais", config.uniformCardPoints))
        }
        add(RuleChip(if (config.allowDrawFromDiscard) "Compra do lixo ligada" else "Compra do lixo desligada", config.allowDrawFromDiscard))
        add(RuleChip(if (config.autoSortHand) "Mão organiza sozinha" else "Ordem manual da mão", config.autoSortHand))
        add(
            RuleChip(
                if (config.gameType == GameType.CACHETA) "Limite: ${config.pointLimit} vidas" else "Limite: ${config.pointLimit} pts",
                null
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(gameName, color = gameAccent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(
                "${config.maxPlayers} jogadores · ${config.cardsPerPlayer} cartas",
                color = MenuColors.OnDarkFaint,
                fontSize = 11.sp
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chips.forEach { chip ->
                MenuBadge(
                    text = chip.label,
                    color = when (chip.enabled) {
                        true -> MenuColors.TableGreenLight
                        false -> MenuColors.OnDarkFaint
                        null -> MenuColors.Gold
                    }
                )
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private fun previewNetworkRepository(
    rooms: List<DiscoveredRoom> = emptyList(),
    clients: Int = 0,
    status: ConnectionStatus = ConnectionStatus.CONNECTED,
    onlineTransport: Boolean = false
): LocalNetworkRepository = object : LocalNetworkRepository {
    override val discoveredRooms = MutableStateFlow(rooms)
    override val connectedClientsCount = MutableStateFlow(clients)
    override val incomingMessages = MutableSharedFlow<NetworkMessage>()
    override val connectionStatus = MutableStateFlow(status)
    override val isOnlineTransport = onlineTransport
    override fun startHosting(playerName: String, port: Int, config: MatchConfig?, password: String?) {}
    override fun stopHosting() {}
    override fun startDiscovery() {}
    override fun stopDiscovery() {}
    override fun connectToRoom(host: String, port: Int, password: String?) {}
    override fun reconnect(): Boolean = false
    override fun disconnect() {}
    override fun sendMessage(message: NetworkMessage) {}
    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage) = true
    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage) = true
    override fun resetConnectionStatus() {}
}

@Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Host"
)
@Composable
fun LobbyScreenHostPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_host", "Jogador"))
    MaterialTheme {
        LobbyScreen(
            isHosting = true,
            networkRepository = previewNetworkRepository(clients = 1),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "LobbyScreen - Host celular compacto")
@Composable
fun LobbyScreenHostCompactPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_host_compact", "Jogador"))
    MaterialTheme {
        LobbyScreen(
            isHosting = true,
            networkRepository = previewNetworkRepository(),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 640,
    heightDp = 360,
    fontScale = 1.5f,
    name = "LobbyScreen - Host compacto e fonte grande"
)
@Composable
fun LobbyScreenHostCompactLargeFontPreview() {
    LobbyScreenHostPreview()
}

@Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Cliente procurando"
)
@Composable
fun LobbyScreenClientSearchingPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_client_empty", "Joao"))
    MaterialTheme {
        LobbyScreen(
            isHosting = false,
            networkRepository = previewNetworkRepository(),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Cliente com salas"
)
@Composable
fun LobbyScreenClientPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_client", "Joao"))
    val sampleRooms = listOf(
        DiscoveredRoom(
            serviceName = "Room_Local",
            host = "192.168.1.10",
            port = 9090,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        ),
        DiscoveredRoom(
            serviceName = "Room_Carlos",
            host = "192.168.1.11",
            port = 9090,
            config = MatchConfig(gameType = GameType.CACHETA)
        )
    )
    MaterialTheme {
        LobbyScreen(
            isHosting = false,
            networkRepository = previewNetworkRepository(rooms = sampleRooms),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "LobbyScreen - sala publicada")
@Composable
private fun LobbyScreenPublishedRoomPreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PublishedRoomCard(connectedClients = 1, requiredClients = 3, canStart = false, onStart = {})
        }
    }
}

@Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Host sala privada (online)"
)
@Composable
fun LobbyScreenHostPrivateRoomPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_host_private", "Jogador"))
    MaterialTheme {
        LobbyScreen(
            isHosting = true,
            networkRepository = previewNetworkRepository(onlineTransport = true),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Cliente online (entrar com código)"
)
@Composable
fun LobbyScreenClientOnlineJoinByCodePreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_client_online", "Joao"))
    MaterialTheme {
        LobbyScreen(
            isHosting = false,
            networkRepository = previewNetworkRepository(onlineTransport = true),
            onBack = {},
            onGameStarted = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "LobbyScreen - erro ao entrar")
@Composable
private fun LobbyScreenJoinErrorPreview() {
    MaterialTheme {
        ClientPanel(
            discoveredRooms = emptyList(),
            isJoining = false,
            joinError = "Não foi possível entrar na sala. Verifique a rede e tente novamente.",
            onJoin = {}
        )
    }
}
