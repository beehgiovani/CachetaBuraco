package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes

// Fim de rodada: o dialogo de resultado e a tabela que traduz o texto do
// breakdown (gerado pelo MatchViewModel) em linhas legiveis.

@Composable
internal fun RoundEndDialog(
    details: RoundEndDetails,
    config: MatchConfig,
    isHost: Boolean,
    onNextRound: () -> Unit,
    onRequestRestart: () -> Unit,
    onLeave: () -> Unit
) {
    var restartRequested by remember(details) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        containerColor = MenuColors.Ink,
        shape = MenuShapes.Card,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (details.isMatchOver) "🏆 PARTIDA ENCERRADA!" else "🎴 FIM DE RODADA",
                    color = if (details.isMatchOver) MenuColors.Gold else Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (details.winnerName == "Contagem") "Contagem" else "Vencedor: ${details.winnerName}",
                    color = MenuColors.TableGreenLight,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Detalhamento da rodada em formato de tabela para ficar fácil conferir.
                if (details.breakdown.isNotBlank()) {
                    RoundBreakdownTable(breakdown = details.breakdown)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Placar acumulado — orientado pela perspectiva local (localTeam).
                // Botei num cartão próprio com um "×" central pra ficar claro que é um
                // confronto entre dois lados, em vez de uma linha solta perdida no meio.
                val isTeamMode = config.maxPlayers == 4
                val opponentSideLabel = if (details.opponentLabel == "Máquina") "Lado da Máquina" else "Lado do Oponente"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), MenuShapes.Card)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Placar acumulado",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTeamMode) {
                            // Modo 4 jogadores: exibe Equipe A / Equipe B pelos índices absolutos
                            val teamScores = details.teamScores
                            if (teamScores.size >= 2) {
                                val leftIsLocal = details.localTeam == 0
                                ScoreColumn(
                                    label = if (leftIsLocal) "Seu lado (Equipe A)" else "$opponentSideLabel (Equipe A)",
                                    score = teamScores[0],
                                    limit = config.pointLimit,
                                    gameType = config.gameType,
                                    isWinner = details.winnerTeam == 0
                                )
                                Text("×", color = Color.White.copy(alpha = 0.28f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                ScoreColumn(
                                    label = if (!leftIsLocal) "Seu lado (Equipe B)" else "$opponentSideLabel (Equipe B)",
                                    score = teamScores[1],
                                    limit = config.pointLimit,
                                    gameType = config.gameType,
                                    isWinner = details.winnerTeam == 1
                                )
                            }
                        } else {
                            // Modo 2 jogadores: usa myNewTotal/opponentNewTotal já orientados localmente
                            ScoreColumn(
                                label = details.myLabel,
                                score = details.myNewTotal,
                                limit = config.pointLimit,
                                gameType = config.gameType,
                                isWinner = details.winnerTeam != null && details.winnerTeam == details.localTeam
                            )
                            Text("×", color = Color.White.copy(alpha = 0.28f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            ScoreColumn(
                                label = details.opponentLabel,
                                score = details.opponentNewTotal,
                                limit = config.pointLimit,
                                gameType = config.gameType,
                                isWinner = details.winnerTeam != null && details.winnerTeam != details.localTeam
                            )
                        }
                    }
                }

                if (details.isMatchOver) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(MenuColors.Gold.copy(alpha = 0.22f), MenuColors.Gold.copy(alpha = 0.10f))
                                ),
                                MenuShapes.Card
                            )
                            .border(1.dp, MenuColors.Gold.copy(alpha = 0.4f), MenuShapes.Card)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🎉 ${details.winnerName} venceu a partida!",
                            color = MenuColors.Gold,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!details.isMatchOver) {
                Button(
                    onClick = onNextRound,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▶  Próxima Rodada", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Voltar ao Menu", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!details.isMatchOver) {
                TextButton(onClick = onLeave) {
                    Text("Sair", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (isHost) {
                // So o host propoe a revanche; os demais confirmam pelo RestartMatchDialog
                // quando o pedido chegar (fluxo de consentimento ja existente no ViewModel).
                TextButton(
                    onClick = {
                        restartRequested = true
                        onRequestRestart()
                    },
                    enabled = !restartRequested
                ) {
                    Text(
                        if (restartRequested) "Aguardando jogadores..." else "🔁  Jogar Novamente",
                        color = if (restartRequested) Color.White.copy(alpha = 0.5f) else MenuColors.Gold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}

private data class BreakdownRow(
    val owner: String,
    val item: String,
    val quantity: String,
    val points: String
)

@Composable
private fun RoundBreakdownTable(breakdown: String) {
    val rows = remember(breakdown) { parseBreakdownRows(breakdown) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), MenuShapes.Card)
            .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Detalhes da contagem",
            color = MenuColors.Gold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        if (rows.isEmpty()) {
            Text(
                text = breakdown,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            return@Column
        }

        BreakdownHeaderRow()
        val ownerGroups = rows.groupBy { it.owner }
        ownerGroups.entries.forEachIndexed { groupIndex, (owner, ownerRows) ->
            // Divisor entre jogador/equipe pra separar visualmente cada grupo da tabela.
            if (groupIndex > 0) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
            Text(
                text = owner,
                color = MenuColors.TableGreenLight,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            ownerRows.forEachIndexed { index, row ->
                BreakdownDataRow(
                    row = row,
                    background = if (index % 2 == 0) Color.White.copy(alpha = 0.045f) else Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun BreakdownHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.18f), MenuShapes.Card)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreakdownCell("Item", weight = 1.45f, isHeader = true)
        BreakdownCell("Qtd.", weight = 0.55f, isHeader = true, alignEnd = true)
        BreakdownCell("Pontos", weight = 0.8f, isHeader = true, alignEnd = true)
    }
}

@Composable
private fun BreakdownDataRow(row: BreakdownRow, background: Color) {
    // A linha de "Total da rodada" fecha cada grupo, entao destaco ela em dourado
    // pra dar pra ver de relance quanto cada lado somou sem ler a tabela inteira.
    val isTotal = row.item.equals("Total da rodada", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isTotal) MenuColors.Gold.copy(alpha = 0.12f) else background, MenuShapes.Card)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreakdownCell(row.item, weight = 1.45f, emphasize = isTotal)
        BreakdownCell(row.quantity.ifBlank { "-" }, weight = 0.55f, alignEnd = true, emphasize = isTotal)
        BreakdownCell(row.points.ifBlank { "-" }, weight = 0.8f, alignEnd = true, emphasize = isTotal)
    }
}

@Composable
private fun RowScope.BreakdownCell(
    text: String,
    weight: Float,
    isHeader: Boolean = false,
    alignEnd: Boolean = false,
    emphasize: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        color = when {
            isHeader -> Color.White.copy(alpha = 0.72f)
            emphasize -> MenuColors.Gold
            else -> Color.White.copy(alpha = 0.9f)
        },
        fontSize = if (isHeader) 11.sp else 12.sp,
        fontWeight = if (isHeader || emphasize) FontWeight.Bold else FontWeight.Medium,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 2,
        lineHeight = 15.sp
    )
}

private fun parseBreakdownRows(breakdown: String): List<BreakdownRow> {
    return breakdown
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line -> parseBreakdownLine(line) }
        .toList()
}

private fun parseBreakdownLine(line: String): BreakdownRow {
    val owner = line.substringBefore(":", missingDelimiterValue = "Rodada").trim()
    val body = line.substringAfter(":", line).trim()
    val item = when {
        body.startsWith("Pontuação das cartas") -> "Regra das cartas"
        body.startsWith("mesa", ignoreCase = true) -> "Cartas na mesa"
        body.startsWith("canastras", ignoreCase = true) -> "Canastras"
        body.startsWith("3 vermelhos", ignoreCase = true) -> "3 vermelhos"
        body.startsWith("mão", ignoreCase = true) -> "Cartas na mão"
        body.startsWith("3 pretos", ignoreCase = true) -> "3 pretos na mão"
        body.startsWith("morto", ignoreCase = true) -> "Morto"
        body.startsWith("bonus", ignoreCase = true) -> "Bônus de bate"
        body.startsWith("total", ignoreCase = true) -> "Total da rodada"
        else -> body.substringBefore("=").trim().ifBlank { body }
    }
    return BreakdownRow(
        owner = owner,
        item = item,
        quantity = extractBreakdownQuantity(body),
        points = extractBreakdownPoints(body)
    )
}

private fun extractBreakdownQuantity(body: String): String {
    val cardCount = Regex("""(\d+)\s+carta\(s\)""").find(body)?.groupValues?.getOrNull(1)
    if (cardCount != null) return cardCount
    val cleanDirty = Regex("""limpas\s+(\d+),\s+sujas\s+(\d+)""").find(body)?.groupValues
    if (cleanDirty != null && cleanDirty.size >= 3) return "L ${cleanDirty[1]} / S ${cleanDirty[2]}"
    val threeCount = Regex("""3\s+\w+\s+(\d+)""").find(body)?.groupValues?.getOrNull(1)
    if (threeCount != null) return threeCount
    return ""
}

private fun extractBreakdownPoints(body: String): String {
    val afterEquals = body.substringAfter("=", missingDelimiterValue = "").trim()
    if (afterEquals.isNotBlank()) return afterEquals
    val explicitPoints = Regex("""([+-]?\d+\s*pts?)""").find(body)?.groupValues?.getOrNull(1)
    if (explicitPoints != null) return explicitPoints
    val penalty = Regex("""([+-]\d+)""").find(body)?.groupValues?.getOrNull(1)
    return penalty ?: ""
}

@Composable
private fun ScoreColumn(
    label: String,
    score: Int,
    limit: Int,
    gameType: GameType,
    isWinner: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isWinner) "👑 $label" else label,
            color = if (isWinner) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.6f),
            fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            text = if (gameType == GameType.CACHETA) "$score ❤️" else "$score pts",
            color = if (isWinner) MenuColors.TableGreenLight else Color.White,
            fontWeight = if (isWinner) FontWeight.ExtraBold else FontWeight.Medium,
            fontSize = 22.sp
        )
        if (gameType != GameType.CACHETA) {
            Text(
                text = "de $limit pts",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp
            )
        }
    }
}
