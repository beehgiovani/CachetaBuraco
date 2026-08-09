package com.brunogiovani.cachetaburaco.presentation.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.Player
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
    onResumeGame: () -> Unit = {}
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
        onJoinOnlineRoom = onJoinOnlineRoom
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
    onJoinOnlineRoom: () -> Unit
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
            val stacked = maxWidth < 700.dp

            if (stacked) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MenuHeader()
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
                            onOpenChampionships = onOpenChampionships
                        )
                    }
                    MenuEntrance(delayMillis = 60) {
                        ProfilePanel(
                            playerName = playerName,
                            onOpenProfile = onOpenOnlineProfile,
                            onLogout = onRequestLogout
                        )
                    }
                    MenuEntrance(delayMillis = 100) {
                        RankingPanel(
                            ranking = ranking,
                            currentPlayerId = currentPlayerId,
                            onOpenOnlineRanking = onOpenOnlineRanking
                        )
                    }
                    SafeAdBannerSlot(compact = true, placement = AdPlacement.MAIN_MENU)
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
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        MenuEntrance {
                            ProfilePanel(
                                playerName = playerName,
                                onOpenProfile = onOpenOnlineProfile,
                                onLogout = onRequestLogout
                            )
                        }
                        MenuEntrance(delayMillis = 60) {
                            RankingPanel(
                                ranking = ranking,
                                currentPlayerId = currentPlayerId,
                                onOpenOnlineRanking = onOpenOnlineRanking
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .background(MenuColors.InkPanelSoft, MenuShapes.Card)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                    ) {
                        MenuHeader()
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
                                onOpenChampionships = onOpenChampionships
                            )
                        }
                        SafeAdBannerSlot(compact = true, placement = AdPlacement.MAIN_MENU)
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
private fun MenuHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.game_logo),
            contentDescription = "Logo",
            modifier = Modifier.heightIn(min = 82.dp, max = 118.dp),
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
    onOpenChampionships: () -> Unit = {}
) {
    Column(
        modifier = Modifier.widthIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (hasSavedGame) {
            MenuActionRow(
                title = "Continuar Partida Salva",
                subtitle = "Recupere o progresso anterior",
                glyph = "▶",
                accentColor = MenuColors.Gold,
                onClick = onResumeGame,
                highlighted = true
            )
        }
        MenuGroupLabel("Rede local")
        MenuActionRow(
            title = "Criar sala local",
            subtitle = "Configure regras para jogar na mesma rede Wi-Fi",
            glyph = "♠",
            accentColor = MenuColors.TableGreenLight,
            onClick = onHostRoom,
            highlighted = !hasSavedGame
        )
        MenuActionRow(
            title = "Jogar contra a máquina",
            subtitle = "Treine e teste regras sem outro celular",
            glyph = "♟",
            accentColor = MenuColors.TableGreen,
            onClick = onPlayBot
        )
        MenuActionRow(
            title = "Entrar em sala local",
            subtitle = "Procure partidas na mesma rede Wi-Fi",
            glyph = "♣",
            accentColor = MenuColors.TableGreenDeep,
            onClick = onJoinRoom
        )
        MenuGroupLabel("Online")
        MenuActionRow(
            title = "Criar sala online",
            subtitle = "Publique as regras e jogue pela internet",
            glyph = "♥",
            accentColor = MenuColors.Gold,
            onClick = onHostOnlineRoom,
            badge = "BETA"
        )
        MenuActionRow(
            title = "Encontrar sala online",
            subtitle = "Veja as regras antes de escolher uma mesa",
            glyph = "★",
            accentColor = MenuColors.GoldDeep,
            onClick = onJoinOnlineRoom,
            badge = "BETA"
        )
        MenuActionRow(
            title = "Campeonatos",
            subtitle = "Crie ou entre com código, veja a classificação",
            glyph = "🏆",
            accentColor = MenuColors.Gold,
            onClick = onOpenChampionships,
            badge = "BETA"
        )
        MenuGroupLabel("Comunidade")
        MenuActionRow(
            title = "Chat geral",
            subtitle = "Converse com quem estiver online agora",
            glyph = "💬",
            accentColor = MenuColors.TableGreenLight,
            onClick = onOpenGlobalChat,
            badge = "BETA"
        )
    }
}

@Composable
private fun ProfilePanel(
    playerName: String,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit
) {
    MenuSectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.default_avatar),
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(54.dp).clip(CircleShape).background(MenuColors.TableGreen)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(playerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Modo Local - Wi-Fi", color = MenuColors.TableGreenLight, fontSize = 12.sp)
                }
            }
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
