package com.brunogiovani.cachetaburaco.presentation.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.Player
import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatRepository
import com.brunogiovani.cachetaburaco.presentation.chat.GlobalChatPanel
import com.brunogiovani.cachetaburaco.presentation.components.AdPlacement
import com.brunogiovani.cachetaburaco.presentation.components.MenuActionRow
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuEntrance
import com.brunogiovani.cachetaburaco.presentation.components.MenuGroupLabel
import com.brunogiovani.cachetaburaco.presentation.components.MenuSectionCard
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.SafeAdBannerSlot
import com.brunogiovani.cachetaburaco.presentation.match.MatchViewModel

@Composable
fun MainMenuScreen(
    onLogout: () -> Unit,
    onHostRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onHostOnlineRoom: () -> Unit,
    onJoinOnlineRoom: () -> Unit,
    onOpenOnlineProfile: () -> Unit,
    onOpenOnlineRanking: () -> Unit,
    onOpenGlobalChat: () -> Unit = {},
    onOpenChampionships: () -> Unit = {},
    onPlayBot: () -> Unit,
    onResumeGame: () -> Unit = {},
    globalChatRepository: GlobalChatRepository? = null
) {
    val context = LocalContext.current
    val player = FakeAuthRepository.getCurrentPlayer()
    val ranking = remember { FakeAuthRepository.getLocalRanking() }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var hasSavedGame by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasSavedGame = MatchViewModel.hasSavedGame(context)
    }

    MainMenuContent(
        playerName = player?.name ?: "Visitante",
        ranking = ranking,
        currentPlayerId = player?.id,
        hasSavedGame = hasSavedGame,
        onOpenOnlineProfile = onOpenOnlineProfile,
        onRequestLogout = { showLogoutDialog = true },
        onOpenOnlineRanking = onOpenOnlineRanking,
        onOpenGlobalChat = onOpenGlobalChat,
        onOpenChampionships = onOpenChampionships,
        onResumeGame = onResumeGame,
        onHostRoom = onHostRoom,
        onPlayBot = onPlayBot,
        onJoinRoom = onJoinRoom,
        onHostOnlineRoom = onHostOnlineRoom,
        onJoinOnlineRoom = onJoinOnlineRoom,
        globalChatRepository = globalChatRepository
    )

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = MenuColors.Ink,
            shape = MenuShapes.Card,
            title = { Text("Trocar de conta?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Isso apaga o perfil salvo neste aparelho. O ranking local permanece separado por usuário salvo.",
                    color = Color.White.copy(alpha = 0.72f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        FakeAuthRepository.logout()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.RedDeep)
                ) { Text("Sair") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.65f))
                }
            }
        )
    }
}

@Composable
private fun MainMenuContent(
    playerName: String,
    ranking: List<FakeAuthRepository.LocalRankingEntry>,
    currentPlayerId: String?,
    hasSavedGame: Boolean,
    onOpenOnlineProfile: () -> Unit,
    onRequestLogout: () -> Unit,
    onOpenOnlineRanking: () -> Unit,
    onOpenGlobalChat: () -> Unit = {},
    onOpenChampionships: () -> Unit = {},
    onResumeGame: () -> Unit,
    onHostRoom: () -> Unit,
    onPlayBot: () -> Unit,
    onJoinRoom: () -> Unit,
    onHostOnlineRoom: () -> Unit,
    onJoinOnlineRoom: () -> Unit,
    globalChatRepository: GlobalChatRepository? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(MenuColors.backgroundScrim()))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
            val fontScale = LocalDensity.current.fontScale
            val stacked = maxWidth < 700.dp
            val compactLandscape = !stacked && (maxHeight < 680.dp || fontScale >= 1.2f)
            val twoColumnActions = !stacked && maxWidth >= 900.dp && fontScale < 1.2f
            val stackedScroll = rememberScrollState()
            val profileScroll = rememberScrollState()
            val actionsScroll = rememberScrollState()

            // Ao mudar orientacao ou tamanho da fonte, recomeco no topo para
            // nenhum bloco reaparecer cortado por uma posicao antiga de rolagem.
            LaunchedEffect(maxWidth, maxHeight, fontScale) {
                stackedScroll.scrollTo(0)
                profileScroll.scrollTo(0)
                actionsScroll.scrollTo(0)
            }

            if (stacked) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(stackedScroll),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MenuHeader(compact = fontScale >= 1.2f)
                    MenuEntrance {
                        ActionsPanel(
                            hasSavedGame = hasSavedGame,
                            onResumeGame = onResumeGame,
                            onHostRoom = onHostRoom,
                            onPlayBot = onPlayBot,
                            onJoinRoom = onJoinRoom,
                            onHostOnlineRoom = onHostOnlineRoom,
                            onJoinOnlineRoom = onJoinOnlineRoom,
                            onOpenGlobalChat = onOpenGlobalChat,
                            onOpenChampionships = onOpenChampionships,
                            compact = fontScale >= 1.2f
                        )
                    }
                    MenuEntrance(delayMillis = 60) {
                        ProfilePanel(
                            playerName = playerName,
                            onOpenProfile = onOpenOnlineProfile,
                            onLogout = onRequestLogout,
                            stackActions = fontScale >= 1.2f
                        )
                    }
                    MenuEntrance(delayMillis = 100) {
                        RankingPanel(
                            ranking = ranking,
                            currentPlayerId = currentPlayerId,
                            onOpenOnlineRanking = onOpenOnlineRanking
                        )
                    }
                    if (globalChatRepository != null) {
                        MenuEntrance(delayMillis = 130) {
                            InlineGlobalChatPanel(
                                playerName = playerName,
                                repository = globalChatRepository,
                                onExpand = onOpenGlobalChat
                            )
                        }
                    }
                    SafeAdBannerSlot(compact = true, placement = AdPlacement.MAIN_MENU)
                    // Espaco reservado pro FAB do chat nao tampar o fim da lista
                    // em telas curtas (ele fica fixo por cima do conteudo rolado).
                    Spacer(modifier = Modifier.height(72.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .verticalScroll(profileScroll),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        MenuEntrance {
                            ProfilePanel(
                                playerName = playerName,
                                onOpenProfile = onOpenOnlineProfile,
                                onLogout = onRequestLogout,
                                stackActions = fontScale >= 1.2f
                            )
                        }
                        MenuEntrance(delayMillis = 60) {
                            RankingPanel(
                                ranking = ranking,
                                currentPlayerId = currentPlayerId,
                                onOpenOnlineRanking = onOpenOnlineRanking
                            )
                        }
                        if (globalChatRepository != null) {
                            MenuEntrance(delayMillis = 90) {
                                InlineGlobalChatPanel(
                                    playerName = playerName,
                                    repository = globalChatRepository,
                                    onExpand = onOpenGlobalChat
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .background(MenuColors.InkPanelSoft, MenuShapes.Card)
                            .verticalScroll(actionsScroll)
                            .padding(if (compactLandscape) 12.dp else 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(
                            if (compactLandscape) 8.dp else 12.dp,
                            if (compactLandscape) Alignment.Top else Alignment.CenterVertically
                        )
                    ) {
                        MenuHeader(compact = compactLandscape)
                        MenuEntrance {
                            ActionsPanel(
                                hasSavedGame = hasSavedGame,
                                onResumeGame = onResumeGame,
                                onHostRoom = onHostRoom,
                                onPlayBot = onPlayBot,
                                onJoinRoom = onJoinRoom,
                                onHostOnlineRoom = onHostOnlineRoom,
                                onJoinOnlineRoom = onJoinOnlineRoom,
                                onOpenGlobalChat = onOpenGlobalChat,
                                onOpenChampionships = onOpenChampionships,
                                compact = compactLandscape,
                                twoColumns = twoColumnActions
                            )
                        }
                        SafeAdBannerSlot(compact = true, placement = AdPlacement.MAIN_MENU)
                        // Mesmo motivo do ramo empilhado: o FAB do chat fica fixo
                        // no canto e nao pode tampar o fim desta coluna.
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }

        // Atalho pro chat geral sem precisar rolar ate o fim da lista de
        // acoes -- ela ja tem 8 itens agrupados em 3 secoes, e o chat e a
        // unica acao sem estado (nunca tem "regra pra configurar antes"),
        // entao vale ficar sempre a um toque de distancia.
        FloatingActionButton(
            onClick = onOpenGlobalChat,
            containerColor = MenuColors.TableGreenLight,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp)
        ) {
            Text("💬", fontSize = 22.sp)
        }
    }
}

@Composable
private fun MenuHeader(compact: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.game_logo),
            contentDescription = "Logo",
            modifier = Modifier.heightIn(
                min = if (compact) 56.dp else 82.dp,
                max = if (compact) 72.dp else 118.dp
            ),
            contentScale = ContentScale.Fit
        )
        Text(
            text = "Carteado BR - Cacheta, Buraco e Tranca",
            color = MenuColors.Gold.copy(alpha = 0.88f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

// Cada botao entra num transporte diferente, mas a partida em si
// continua usando MatchScreen + MatchViewModel.
@Composable
private fun ActionsPanel(
    hasSavedGame: Boolean,
    onResumeGame: () -> Unit,
    onHostRoom: () -> Unit,
    onPlayBot: () -> Unit,
    onJoinRoom: () -> Unit,
    onHostOnlineRoom: () -> Unit,
    onJoinOnlineRoom: () -> Unit,
    onOpenGlobalChat: () -> Unit = {},
    onOpenChampionships: () -> Unit = {},
    compact: Boolean = false,
    twoColumns: Boolean = false
) {
    val localActions = listOf(
        MainMenuAction(
            title = "Criar sala local",
            subtitle = "Configure regras para jogar na mesma rede Wi-Fi",
            glyph = "♠",
            accentColor = MenuColors.TableGreenLight,
            onClick = onHostRoom,
            highlighted = !hasSavedGame
        ),
        MainMenuAction(
            title = "Jogar contra a máquina",
            subtitle = "Treine e teste regras sem outro celular",
            glyph = "♟",
            accentColor = MenuColors.TableGreen,
            onClick = onPlayBot
        ),
        MainMenuAction(
            title = "Entrar em sala local",
            subtitle = "Procure partidas na mesma rede Wi-Fi",
            glyph = "♣",
            accentColor = MenuColors.TableGreenDeep,
            onClick = onJoinRoom
        )
    )
    val onlineActions = listOf(
        MainMenuAction(
            title = "Criar sala online",
            subtitle = "Publique as regras e jogue pela internet",
            glyph = "♥",
            accentColor = MenuColors.Gold,
            onClick = onHostOnlineRoom,
            badge = "BETA"
        ),
        MainMenuAction(
            title = "Encontrar sala online",
            subtitle = "Veja as regras antes de escolher uma mesa",
            glyph = "★",
            accentColor = MenuColors.GoldDeep,
            onClick = onJoinOnlineRoom,
            badge = "BETA"
        ),
        MainMenuAction(
            title = "Campeonatos",
            subtitle = "Crie ou entre com código, veja a classificação",
            glyph = "🏆",
            accentColor = MenuColors.Gold,
            onClick = onOpenChampionships,
            badge = "BETA"
        )
    )
    val communityActions = listOf(
        MainMenuAction(
            title = "Chat geral",
            subtitle = "Converse com quem estiver online agora",
            glyph = "💬",
            accentColor = MenuColors.TableGreenLight,
            onClick = onOpenGlobalChat,
            badge = "BETA"
        )
    )

    Column(
        modifier = Modifier.widthIn(max = if (twoColumns) 680.dp else 460.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
    ) {
        if (hasSavedGame) {
            MenuActionRow(
                title = "Continuar Partida Salva",
                subtitle = "Recupere o progresso anterior",
                glyph = "▶",
                accentColor = MenuColors.Gold,
                onClick = onResumeGame,
                highlighted = true,
                compact = compact
            )
        }
        if (twoColumns) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
                ) {
                    MenuActionGroup("Rede local", localActions, compact)
                    MenuActionGroup("Comunidade", communityActions, compact)
                }
                MenuActionGroup(
                    label = "Online",
                    actions = onlineActions,
                    compact = compact,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            MenuActionGroup("Rede local", localActions, compact)
            MenuActionGroup("Online", onlineActions, compact)
            MenuActionGroup("Comunidade", communityActions, compact)
        }
    }
}

private data class MainMenuAction(
    val title: String,
    val subtitle: String,
    val glyph: String,
    val accentColor: Color,
    val onClick: () -> Unit,
    val badge: String? = null,
    val highlighted: Boolean = false
)

@Composable
private fun MenuActionGroup(
    label: String,
    actions: List<MainMenuAction>,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
    ) {
        MenuGroupLabel(label)
        actions.forEach { action ->
            MenuActionRow(
                title = action.title,
                subtitle = action.subtitle,
                glyph = action.glyph,
                accentColor = action.accentColor,
                onClick = action.onClick,
                badge = action.badge,
                highlighted = action.highlighted,
                compact = compact
            )
        }
    }
}

@Composable
private fun ProfilePanel(
    playerName: String,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit,
    stackActions: Boolean = false
) {
    MenuSectionCard {
        if (stackActions) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ProfileIdentity(playerName)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onOpenProfile) {
                        Text("Perfil online", color = MenuColors.TableGreenLight, fontSize = 13.sp)
                    }
                    TextButton(onClick = onLogout) {
                        Text("Sair", color = MenuColors.Red, fontSize = 13.sp)
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                ProfileIdentity(playerName, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onOpenProfile) {
                        Text("Perfil online", color = MenuColors.TableGreenLight, fontSize = 13.sp)
                    }
                    TextButton(onClick = onLogout) {
                        Text("Sair", color = MenuColors.Red, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileIdentity(playerName: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Image(
            painter = painterResource(id = R.drawable.default_avatar),
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(CircleShape).background(MenuColors.TableGreen)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playerName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Modo Local - Wi-Fi", color = MenuColors.TableGreenLight, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RankingPanel(
    ranking: List<FakeAuthRepository.LocalRankingEntry>,
    currentPlayerId: String?,
    onOpenOnlineRanking: () -> Unit
) {
    MenuSectionCard(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ranking local", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Vitórias", color = MenuColors.Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (ranking.isEmpty()) {
            Text(
                "Jogue uma partida para abrir a classificação deste aparelho.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ranking.take(6).forEachIndexed { index, entry ->
                    RankingRow(
                        position = index + 1,
                        name = entry.playerName,
                        wins = entry.wins,
                        isCurrentPlayer = entry.playerId == currentPlayerId
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = onOpenOnlineRanking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreen),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Ver ranking global", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// Logo abaixo do ranking global, do jeito que o Bruno pediu: um chat ao vivo
// pra combinar sala/partida sem precisar navegar pra uma tela separada.
// Altura fixa porque GlobalChatPanel usa weight(1f) internamente pra lista
// de mensagens, e aqui ele mora dentro de uma coluna com verticalScroll
// (altura infinita) -- sem essa altura fixa o weight(1f) não tem o que medir.
@Composable
private fun InlineGlobalChatPanel(
    playerName: String,
    repository: GlobalChatRepository,
    onExpand: () -> Unit
) {
    MenuSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chat geral", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Expandir",
                color = MenuColors.TableGreenLight,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onExpand)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        GlobalChatPanel(
            playerName = playerName,
            repository = repository,
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )
    }
}

@Composable
private fun RankingRow(position: Int, name: String, wins: Int, isCurrentPlayer: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrentPlayer) MenuColors.TableGreenLight.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (position == 1) MenuColors.Gold.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    position.toString(),
                    color = if (position == 1) MenuColors.Gold else Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                name,
                color = if (isCurrentPlayer) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                fontWeight = if (isCurrentPlayer) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
        Text(wins.toString(), color = MenuColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Menu - celular compacto")
@Composable
private fun MainMenuScreenCompactPreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_main", "Bruno"))
    MaterialTheme {
        MainMenuContent(
            playerName = "Bruno",
            ranking = emptyList(),
            currentPlayerId = "preview_main",
            hasSavedGame = false,
            onOpenOnlineProfile = {},
            onRequestLogout = {},
            onOpenOnlineRanking = {},
            onResumeGame = {},
            onHostRoom = {},
            onPlayBot = {},
            onJoinRoom = {},
            onHostOnlineRoom = {},
            onJoinOnlineRoom = {}
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Menu - tablet/paisagem")
@Composable
private fun MainMenuScreenWidePreview() {
    val sampleRanking = listOf(
        FakeAuthRepository.LocalRankingEntry("p1", "Bruno", 18),
        FakeAuthRepository.LocalRankingEntry("p2", "Carlos", 12),
        FakeAuthRepository.LocalRankingEntry("p3", "Ana", 9)
    )
    MaterialTheme {
        MainMenuContent(
            playerName = "Bruno",
            ranking = sampleRanking,
            currentPlayerId = "p1",
            hasSavedGame = true,
            onOpenOnlineProfile = {},
            onRequestLogout = {},
            onOpenOnlineRanking = {},
            onResumeGame = {},
            onHostRoom = {},
            onPlayBot = {},
            onJoinRoom = {},
            onHostOnlineRoom = {},
            onJoinOnlineRoom = {}
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
    fontScale = 1.5f,
    name = "Menu - fonte grande (1.5x)"
)
@Composable
private fun MainMenuScreenLargeFontPreview() {
    MaterialTheme {
        MainMenuContent(
            playerName = "Bruno",
            ranking = emptyList(),
            currentPlayerId = null,
            hasSavedGame = true,
            onOpenOnlineProfile = {},
            onRequestLogout = {},
            onOpenOnlineRanking = {},
            onResumeGame = {},
            onHostRoom = {},
            onPlayBot = {},
            onJoinRoom = {},
            onHostOnlineRoom = {},
            onJoinOnlineRoom = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 812, heightDp = 375, name = "Menu - celular paisagem")
@Composable
private fun MainMenuScreenLandscapePreview() {
    MaterialTheme {
        MainMenuContent(
            playerName = "Bruno",
            ranking = emptyList(),
            currentPlayerId = null,
            hasSavedGame = false,
            onOpenOnlineProfile = {},
            onRequestLogout = {},
            onOpenOnlineRanking = {},
            onResumeGame = {},
            onHostRoom = {},
            onPlayBot = {},
            onJoinRoom = {},
            onHostOnlineRoom = {},
            onJoinOnlineRoom = {}
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 1067,
    heightDp = 600,
    fontScale = 1.3f,
    name = "Menu - paisagem com fonte grande"
)
@Composable
private fun MainMenuScreenLandscapeLargeFontPreview() {
    MainMenuScreenLandscapePreview()
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Menu - ranking preenchido")
@Composable
fun MainMenuScreenPreview() {
    val sampleRanking = listOf(
        FakeAuthRepository.LocalRankingEntry("p1", "Bruno", 18),
        FakeAuthRepository.LocalRankingEntry("p2", "Carlos", 12)
    )
    MaterialTheme {
        MainMenuContent(
            playerName = "Bruno",
            ranking = sampleRanking,
            currentPlayerId = "p1",
            hasSavedGame = false,
            onOpenOnlineProfile = {},
            onRequestLogout = {},
            onOpenOnlineRanking = {},
            onResumeGame = {},
            onHostRoom = {},
            onPlayBot = {},
            onJoinRoom = {},
            onHostOnlineRoom = {},
            onJoinOnlineRoom = {}
        )
    }
}
