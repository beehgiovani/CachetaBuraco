package com.brunogiovani.cachetaburaco.presentation.match

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.brunogiovani.cachetaburaco.domain.repositories.RoomChatMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes

// Chat de sala nao tem historico no banco alem da propria partida (migration
// 0032 apaga tudo quando ela encerra) -- este limite e so pra nao deixar a
// lista em memoria crescer sem fim numa partida muito longa.
internal const val MAX_DISPLAYED_CHAT_MESSAGES = 200

// Tempo que o balao da ultima mensagem do adversario fica sobre a mesa.
// Curto o bastante pra nao atrapalhar a jogada, longo o bastante pra dar
// tempo de ler uma provocacao inteira.
internal const val LIVE_CHAT_BUBBLE_MILLIS = 4000L

/**
 * Balao com a ultima mensagem recebida, sobreposto a mesa. Existe pra quem
 * esta no meio de uma jogada perceber a provocacao sem abrir o chat -- some
 * sozinho depois de LIVE_CHAT_BUBBLE_MILLIS.
 */
@Composable
internal fun LiveChatBubble(
    message: RoomChatMessage?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        // Guardo a ultima mensagem nao-nula pro texto nao sumir no meio da
        // animacao de saida (quando message ja voltou a ser null).
        val shown = remember(message) { message } ?: return@AnimatedVisibility
        val senderLabel = shown.senderSeat?.let { "J${it + 1}" } ?: "Jogador"

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 320.dp)
                .background(Color(0xF21B2733), RoundedCornerShape(14.dp))
                .border(1.dp, MenuColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = senderLabel,
                color = MenuColors.Gold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = shown.body,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun RoomChatDialog(
    messages: List<RoomChatMessage>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xEE101820),
            shape = MenuShapes.Card,
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .border(1.dp, MenuColors.Gold.copy(alpha = 0.3f), MenuShapes.Card)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chat da sala", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) {
                        Text("Fechar", color = MenuColors.Gold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Nenhuma mensagem ainda. Mande um oi!",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages) { message -> RoomChatBubble(message) }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Atalho de provocacao: manda direto, sem passar pelo campo de
                // texto -- no meio de uma mao ninguem quer abrir o teclado.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(TAUNT_EMOJIS) { taunt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                                .border(1.dp, MenuColors.Border, RoundedCornerShape(16.dp))
                                .clickable { onSend(taunt.message) }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(taunt.emoji, fontSize = 15.sp)
                            Text(taunt.label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 500) draft = it },
                        placeholder = { Text("Mensagem...", color = Color.White.copy(alpha = 0.4f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                onSend(body)
                                draft = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MenuColors.TableGreenLight,
                            unfocusedBorderColor = MenuColors.BorderStrong,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = MenuColors.TableGreenLight
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                onSend(body)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MenuColors.TableGreenLight)
                    ) {
                        Text("Enviar")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomChatBubble(message: RoomChatMessage) {
    val senderLabel = if (message.isSelf) "Você" else message.senderSeat?.let { "J${it + 1}" } ?: "Jogador"
    Column(
        horizontalAlignment = if (message.isSelf) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = senderLabel,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp
        )
        Box(
            modifier = Modifier
                .background(
                    if (message.isSelf) MenuColors.TableGreenLight.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 260.dp)
        ) {
            Text(text = message.body, color = Color.White, fontSize = 13.sp)
        }
    }
}
