package com.brunogiovani.cachetaburaco.presentation.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.presentation.match.MatchViewModel

private val ColorGreen = Color(0xFF2E7D32)
private val ColorGreenLight = Color(0xFF4CAF50)
private val ColorBlue = Color(0xFF1565C0)
private val ColorGold = Color(0xFFFFD54F)
private val ColorCard = Color(0xFF121820)
private val ColorPanel = Color(0xCC121820)

@Composable
fun MainMenuScreen(
    onLogout: () -> Unit,
    onHostRoom: () -> Unit,
    onJoinRoom: () -> Unit,
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

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC03070B), Color(0xE006100D))
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ProfilePanel(
                    playerName = player?.name ?: "Visitante",
                    onLogout = { showLogoutDialog = true }
                )
                RankingPanel(ranking = ranking, currentPlayerId = player?.id)
            }

            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .background(ColorPanel, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.game_logo),
                    contentDescription = "Logo",
                    modifier = Modifier.height(118.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = "Cacheta - Buraco - Tranca",
                    color = ColorGold.copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(24.dp))
                if (hasSavedGame) {
                    MenuButton(
                        text = "Continuar Partida Salva",
                        subtitle = "Recupere o progresso anterior",
                        color = Color(0xFFF57C00),
                        onClick = onResumeGame
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                MenuButton(
                    text = "Criar sala",
                    subtitle = "Configure regras, jogadores e pontuação",
                    color = ColorGreenLight,
                    onClick = onHostRoom
                )
                Spacer(modifier = Modifier.height(12.dp))
                MenuButton(
                    text = "Entrar em sala",
                    subtitle = "Procure partidas na mesma rede Wi-Fi",
                    color = ColorBlue,
                    onClick = onJoinRoom
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Partidas locais para testes práticos. Online fica preparado para evoluir depois.",
                    color = Color.White.copy(alpha = 0.48f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                containerColor = ColorCard,
                shape = RoundedCornerShape(20.dp),
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
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
}

@Composable
private fun ProfilePanel(playerName: String, onLogout: () -> Unit) {
    Surface(
        color = ColorCard.copy(alpha = 0.92f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.default_avatar),
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(54.dp).clip(CircleShape).background(ColorGreen)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(playerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Modo Local - Wi-Fi", color = ColorGreenLight, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onLogout) {
                Text("Sair", color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RankingPanel(
    ranking: List<FakeAuthRepository.LocalRankingEntry>,
    currentPlayerId: String?
) {
    Surface(
        color = ColorCard.copy(alpha = 0.88f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ranking local", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Vitórias", color = ColorGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (ranking.isEmpty()) {
                Text(
                    "Jogue uma partida para abrir a classificação deste aparelho.",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            } else {
                ranking.take(6).forEachIndexed { index, entry ->
                    RankingRow(
                        position = index + 1,
                        name = entry.playerName,
                        wins = entry.wins,
                        isCurrentPlayer = entry.playerId == currentPlayerId
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingRow(position: Int, name: String, wins: Int, isCurrentPlayer: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrentPlayer) ColorGreenLight.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(if (position == 1) ColorGold.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(position.toString(), color = if (position == 1) ColorGold else Color.White.copy(alpha = 0.78f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                name,
                color = if (isCurrentPlayer) ColorGreenLight else Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                fontWeight = if (isCurrentPlayer) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
        Text(wins.toString(), color = ColorGold, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun MenuButton(text: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.74f), fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun MainMenuScreenPreview() {
    MaterialTheme {
        MainMenuScreen(
            onLogout = {},
            onHostRoom = {},
            onJoinRoom = {}
        )
    }
}
