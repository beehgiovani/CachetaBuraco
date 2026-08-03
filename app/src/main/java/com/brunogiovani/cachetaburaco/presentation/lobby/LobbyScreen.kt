package com.brunogiovani.cachetaburaco.presentation.lobby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.PointsMode
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository

private val ColorGreenLight = Color(0xFF4CAF50)
private val ColorGold = Color(0xFFFFD54F)
private val ColorSurface = Color(0xCC0D0D1E)
private val ColorCard = Color(0x99111133)

@Composable
fun LobbyScreen(
    isHosting: Boolean,
    singlePlayerMode: Boolean = false,
    networkRepository: LocalNetworkRepository,
    onBack: () -> Unit,
    onGameStarted: (MatchConfig) -> Unit
) {
    val player = FakeAuthRepository.getCurrentPlayer() ?: return

    // Tudo que o jogador escolhe antes da partida vira MatchConfig.
    // O lobby não valida jogada; quem manda nisso é o motor de regras.
    var selectedGameType by remember { mutableStateOf(GameType.CACHETA) }
    var selectedPlayers by remember { mutableIntStateOf(2) }
    var cachetaCardsPerPlayer by remember { mutableIntStateOf(9) }
    var allowWildcards by remember { mutableStateOf(true) }
    var allowDrawFromDiscard by remember { mutableStateOf(true) }
    var allowCharutos by remember { mutableStateOf(true) }
    var cachetaStartsWithDiscard by remember { mutableStateOf(false) }
    var requireCleanCanastraToWin by remember { mutableStateOf(true) }
    var autoMeldTrancaRedThrees by remember { mutableStateOf(true) }
    var penalizeBlackThreesInHand by remember { mutableStateOf(true) }
    var uniformCardPoints by remember { mutableStateOf(false) }
    var autoSortHand by remember { mutableStateOf(true) }
    var botDifficulty by remember { mutableStateOf(BotDifficulty.NORMAL) }
    var pointsMode by remember { mutableStateOf(PointsMode.FREE) }
    var selectedPointLimit by remember { mutableIntStateOf(5) }

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
        cachetaCardsPerPlayer = cachetaCardsPerPlayer,
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
    // Por isso a mesa fica fixa em 2 jogadores: Bruno x máquina.
    val discoveredRooms by networkRepository.discoveredRooms.collectAsState()
    val connectedClients by networkRepository.connectedClientsCount.collectAsState()

    var gameStarted by remember { mutableStateOf(false) }

    LaunchedEffect(isHosting, currentConfig, connectedClients) {
        // Antes de começar, qualquer mudança de regra atualiza a sala anunciada.
        // Com cliente real conectado eu não reinicio o host para não derrubar a conexão.
        if (isHosting) {
            if (connectedClients == 0) {
                networkRepository.startHosting(player.name, config = currentConfig)
            }
        } else {
            networkRepository.startDiscovery()
        }
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Fundo
        Image(
            painter = painterResource(id = R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)))

        // Conteudo principal
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .widthIn(max = 1040.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cabecalho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Voltar", color = Color.White.copy(alpha = 0.8f))
                }
                Text(
                    text = when {
                        singlePlayerMode -> "Jogar contra a máquina"
                        isHosting -> "Criar sala"
                        else -> "Procurar sala"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(72.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isHosting) {
                HostPanel(
                    config = currentConfig,
                    connectedClients = connectedClients,
                    singlePlayerMode = singlePlayerMode,
                    selectedGameType = selectedGameType,
                    onGameTypeChange = { selectedGameType = it },
                    selectedPlayers = selectedPlayers,
                    onPlayersChange = { selectedPlayers = it },
                    cachetaCardsPerPlayer = cachetaCardsPerPlayer,
                    onCachetaCardsPerPlayerChange = { cachetaCardsPerPlayer = it },
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
                    onStart = {
                        gameStarted = true
                        onGameStarted(currentConfig)
                    },

                    modifier = Modifier.weight(1f).fillMaxWidth()
                    
                )
            } else {
                ClientPanel(
                    discoveredRooms = discoveredRooms,
                    onJoin = { room ->
                        networkRepository.connectToRoom(room.host, room.port)
                        gameStarted = true
                        onGameStarted(room.config ?: currentConfig)
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
    singlePlayerMode: Boolean = false,
    selectedGameType: GameType,
    onGameTypeChange: (GameType) -> Unit,
    selectedPlayers: Int,
    onPlayersChange: (Int) -> Unit,
    cachetaCardsPerPlayer: Int,
    onCachetaCardsPerPlayerChange: (Int) -> Unit,
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
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requiredClients = config.maxPlayers - 1
    val canStart = singlePlayerMode || connectedClients >= requiredClients

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = modifier
    ) {
        item {
            SectionCard(title = "Modo de Jogo") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameType.entries.forEach { type ->
                        val isSelected = type == selectedGameType
                        val (label, desc) = when (type) {
                            GameType.CACHETA -> "Cacheta" to "7, 9 ou 10 cartas - Solo"
                            GameType.BURACO -> "Buraco" to "11 cartas - Solo/Duplas"
                            GameType.TRANCA -> "Tranca" to "11 cartas - Solo/Duplas"
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    2.dp,
                                    if (isSelected) ColorGreenLight else Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .background(
                                    if (isSelected) Color(0x334CAF50) else ColorCard,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onGameTypeChange(type) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) ColorGreenLight else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = desc,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Jogadores") {
                val options = if (selectedGameType == GameType.CACHETA || singlePlayerMode) listOf(2) else listOf(2, 4)
                if (options.size == 1) {
                    Text(
                        if (singlePlayerMode) "Modo contra a máquina usa 2 jogadores (você x máquina)." else "Cacheta é sempre 2 jogadores (solo).",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { count ->
                            FilterChipOption(
                                label = if (count == 2) "2 - Solo" else "4 - Duplas",
                                isSelected = count == selectedPlayers,
                                onClick = { onPlayersChange(count) }
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Opções da Partida") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedGameType == GameType.CACHETA) {
                        Text(
                            text = "A carta vira define o curinga da rodada. Escolha a quantidade de cartas conforme a regra da mesa.",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7, 9, 10).forEach { count ->
                                FilterChipOption(
                                    label = "$count cartas",
                                    isSelected = cachetaCardsPerPlayer == count,
                                    onClick = { onCachetaCardsPerPlayerChange(count) }
                                )
                            }
                        }
                        ToggleRow(
                            label = "Curinga pela Vira",
                            description = "Carta acima da vira, no mesmo naipe",
                            checked = allowWildcards,
                            onCheckedChange = onWildcardsChange
                        )
                        ToggleRow(
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
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 12.sp
                        )
                    }

                    ToggleRow(
                        label = "Compra do Lixo",
                        description = if (selectedGameType == GameType.TRANCA)
                            "3 preto tranca automaticamente"
                        else
                            "Permitir comprar do lixo",
                        checked = allowDrawFromDiscard,
                        onCheckedChange = onDrawDiscardChange
                    )

                    if (selectedGameType != GameType.CACHETA) {
                        ToggleRow(
                            label = "Charutos / Trincas",
                            description = if (allowCharutos)
                                "Permite jogos de cartas do mesmo valor"
                            else
                                "Somente sequências do mesmo naipe",
                            checked = allowCharutos,
                            onCheckedChange = onCharutosChange
                        )
                    }

                    if (selectedGameType == GameType.BURACO) {
                        ToggleRow(
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
                        ToggleRow(
                            label = "3 Vermelho",
                            description = if (autoMeldTrancaRedThrees)
                                "Baixa automaticamente e vale 100"
                            else
                                "Fica na mão para controle manual",
                            checked = autoMeldTrancaRedThrees,
                            onCheckedChange = onAutoMeldTrancaRedThreesChange
                        )
                    }

                    if (selectedGameType != GameType.CACHETA) {
                        ToggleRow(
                            label = "Cartas Uniformes",
                            description = if (uniformCardPoints)
                                "Todas as cartas valem 10 pts"
                            else
                                "Valores variáveis (As=15, 2-7=5, etc.)",
                            checked = uniformCardPoints,
                            onCheckedChange = onUniformCardPointsChange
                        )
                    }

                    ToggleRow(
                        label = "Ordenar Mão Auto",
                        description = "Organiza cartas por naipe e valor",
                        checked = autoSortHand,
                        onCheckedChange = onAutoSortChange
                    )

                    if (singlePlayerMode) {
                        Text(
                            text = "Nível da máquina",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BotDifficulty.entries.forEach { difficulty ->
                                val profile = when (difficulty) {
                                    BotDifficulty.EASY -> BotDifficultyProfile(
                                        label = "Fácil",
                                        description = "Compra pouco e protege o básico",
                                        color = Color(0xFF66BB6A)
                                    )
                                    BotDifficulty.NORMAL -> BotDifficultyProfile(
                                        label = "Normal",
                                        description = "Compra lixo útil e monta jogos",
                                        color = ColorGold
                                    )
                                    BotDifficulty.HARD -> BotDifficultyProfile(
                                        label = "Difícil",
                                        description = "Lê a mesa e evita entregar carta",
                                        color = Color(0xFFEF5350)
                                    )
                                }
                                BotDifficultyOption(
                                    profile = profile,
                                    isSelected = botDifficulty == difficulty,
                                    onClick = { onBotDifficultyChange(difficulty) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            RuleSummaryCard(config = config)
        }

        item {
            SectionCard(title = "Pontuação") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipOption(
                        label = "Gratuito",
                        isSelected = pointsMode == PointsMode.FREE,
                        onClick = { onPointsModeChange(PointsMode.FREE) }
                    )
                    FilterChipOption(
                        label = "Fichas",
                        isSelected = pointsMode == PointsMode.CHIPS,
                        onClick = { onPointsModeChange(PointsMode.CHIPS) }
                    )
                }
            }
        }

        item {
            val title = if (selectedGameType == GameType.CACHETA) "Vidas (Limite)" else "Pontuação limite"
            SectionCard(title = title) {
                val options = if (selectedGameType == GameType.CACHETA) listOf(5, 10, 15) else listOf(1500, 3000, 5000)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { limit ->
                        FilterChipOption(
                            label = if (selectedGameType == GameType.CACHETA) "$limit vidas" else "$limit pts",
                            isSelected = limit == selectedPointLimit,
                            onClick = { onPointLimitChange(limit) }
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ColorCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Aguardando Jogadores",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(requiredClients) { index ->
                            val filled = index < connectedClients
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        if (filled) ColorGreenLight else Color.White.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(50)
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "$connectedClients / $requiredClients conectado(s)",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorGreenLight,
                            disabledContainerColor = Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Text(
                            if (canStart) "Iniciar Partida" else "Aguardando jogadores...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

//Painel do Cliente (Discovery)
@Composable
private fun ClientPanel(
    discoveredRooms: List<com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom>,
    onJoin: (com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (discoveredRooms.isEmpty()) {
            item {
                Spacer(modifier = Modifier.height(48.dp))
                CircularProgressIndicator(color = ColorGreenLight, strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Procurando salas na rede local...",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Certifique-se de estar na mesma rede Wi-Fi",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            item {
                Text(
                    "Salas disponiveis",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            items(discoveredRooms) { room ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = ColorCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                room.serviceName.removePrefix("Room_"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                room.host,
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 11.sp
                            )
                            room.config?.let { config ->
                                Spacer(modifier = Modifier.height(6.dp))
                                RuleSummaryText(config = config)
                            }
                        }
                        Button(
                            onClick = { onJoin(room) },
                            colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Entrar >", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}


// Componentes de UI Auxiliares
@Composable
private fun RuleSummaryCard(config: MatchConfig) {
    SectionCard(title = "Regras da sala") {
        RuleSummaryText(config = config)
    }
}

@Composable
private fun RuleSummaryText(config: MatchConfig) {
    val gameName = when (config.gameType) {
        GameType.CACHETA -> "Cacheta"
        GameType.BURACO -> "Buraco"
        GameType.TRANCA -> "Tranca"
    }
    val lines = buildList {
        add("$gameName - ${config.maxPlayers} jogadores - ${config.cardsPerPlayer} cartas")
        if (config.gameType == GameType.CACHETA) {
            add("Curinga: carta acima da vira no mesmo naipe")
            add(if (config.cachetaStartsWithDiscard) "Lixo inicial com a vira" else "Lixo inicial vazio")
        } else {
            add("2 e curinga fixo; topo do lixo deve baixar ou encaixar")
            add(if (config.allowCharutos) "Charutos/trincas permitidos" else "Somente sequências do mesmo naipe")
            add(if (config.requireCleanCanastraToWin) "Precisa canastra limpa para bater" else "Pode bater com canastra suja")
            if (config.gameType == GameType.TRANCA) {
                add("3 preto tranca o lixo; 3 vermelho baixa separado")
            }
        }
        add(if (config.allowDrawFromDiscard) "Compra do lixo ligada" else "Compra do lixo desligada")
        add("Limite: ${if (config.gameType == GameType.CACHETA) "${config.pointLimit} vidas" else "${config.pointLimit} pts"}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xDD121820)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.88f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            content()
        }
    }
}

@Composable
private fun FilterChipOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(
                1.5.dp,
                if (isSelected) ColorGreenLight else Color.White.copy(alpha = 0.25f),
                RoundedCornerShape(10.dp)
            )
            .background(
                if (isSelected) Color(0x334CAF50) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (isSelected) ColorGreenLight else Color.White.copy(alpha = 0.7f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

private data class BotDifficultyProfile(
    val label: String,
    val description: String,
    val color: Color
)

@Composable
private fun BotDifficultyOption(
    profile: BotDifficultyProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 86.dp)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) profile.color else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = if (isSelected) profile.color.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = profile.label,
            color = if (isSelected) profile.color else Color.White.copy(alpha = 0.82f),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = profile.description,
            color = Color.White.copy(alpha = if (isSelected) 0.82f else 0.58f),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ColorGreenLight,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Host"
)
@Composable
fun LobbyScreenHostPreview() {
    FakeAuthRepository.forceSetForPreview(
        com.brunogiovani.cachetaburaco.domain.models.Player("preview_host", "Bruno")
    )
    val dummyRepo = object : LocalNetworkRepository {
        override val discoveredRooms = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom>())
        override val connectedClientsCount = kotlinx.coroutines.flow.MutableStateFlow(1)
        override val incomingMessages = kotlinx.coroutines.flow.MutableSharedFlow<com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage>()
        override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus.CONNECTED)
        override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {}
        override fun stopHosting() {}
        override fun startDiscovery() {}
        override fun stopDiscovery() {}
        override fun connectToRoom(host: String, port: Int) {}
        override fun reconnect(): Boolean = false
        override fun disconnect() {}
        override fun sendMessage(message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) {}
        override fun sendMessageToClient(clientIndex: Int, message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) = true
        override fun sendMessageToPlayer(playerId: String, message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) = true
        override fun resetConnectionStatus() {}
    }
    androidx.compose.material3.MaterialTheme {
        LobbyScreen(
            isHosting = true,
            networkRepository = dummyRepo,
            onBack = {},
            onGameStarted = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,dpi=240",
    name = "LobbyScreen - Cliente"
)
@Composable
fun LobbyScreenClientPreview() {
    FakeAuthRepository.forceSetForPreview(
        com.brunogiovani.cachetaburaco.domain.models.Player("preview_client", "Joao")
    )
    val sampleRooms = listOf(
        com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom(
            serviceName = "Room_Bruno",
            host = "192.168.1.10",
            port = 9090,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        ),
        com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom(
            serviceName = "Room_Carlos",
            host = "192.168.1.11",
            port = 9090,
            config = MatchConfig(gameType = GameType.CACHETA)
        )
    )
    val dummyRepo = object : LocalNetworkRepository {
        override val discoveredRooms = kotlinx.coroutines.flow.MutableStateFlow(sampleRooms)
        override val connectedClientsCount = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val incomingMessages = kotlinx.coroutines.flow.MutableSharedFlow<com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage>()
        override val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow(com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus.CONNECTED)
        override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {}
        override fun stopHosting() {}
        override fun startDiscovery() {}
        override fun stopDiscovery() {}
        override fun connectToRoom(host: String, port: Int) {}
        override fun reconnect(): Boolean = false
        override fun disconnect() {}
        override fun sendMessage(message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) {}
        override fun sendMessageToClient(clientIndex: Int, message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) = true
        override fun sendMessageToPlayer(playerId: String, message: com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage) = true
        override fun resetConnectionStatus() {}
    }
    androidx.compose.material3.MaterialTheme {
        LobbyScreen(
            isHosting = false,
            networkRepository = dummyRepo,
            onBack = {},
            onGameStarted = {}
        )
    }
}
