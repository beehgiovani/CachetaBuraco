package com.brunogiovani.cachetaburaco.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatEntry
import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatRepository
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuMetrics
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import kotlinx.coroutines.launch

// O servidor ja retem so as ultimas 200 linhas (migration 0036); este
// limite e so um teto extra pra lista em memoria nao crescer sem fim numa
// sessao muito longa com o chat aberto.
private const val MAX_DISPLAYED_GLOBAL_CHAT_MESSAGES = 200

@Composable
fun GlobalChatScreen(
    playerName: String,
    repository: GlobalChatRepository,
    onBack: () -> Unit
) {
    val messages = remember { mutableStateListOf<GlobalChatEntry>() }
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(playerName) {
        repository.observeMessages(playerName).collect { entry ->
            messages.add(entry)
            if (messages.size > MAX_DISPLAYED_GLOBAL_CHAT_MESSAGES) messages.removeAt(0)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty()) return
        draft = ""
        sendError = null
        scope.launch {
            if (!repository.sendMessage(playerName, body)) {
                sendError = "Não foi possível enviar. Tente novamente."
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
                .align(Alignment.Center)
        ) {
            MenuTopBar(
                title = "Chat geral",
                subtitle = "Veja as últimas mensagens e continue a conversa",
                onBack = onBack
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    MenuStatusMessage(
                        text = "Nenhuma mensagem ainda",
                        caption = "Seja o primeiro a dizer oi!",
                        showSpinner = false,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages, key = { it.id }) { entry -> GlobalChatBubble(entry) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            sendError?.let { message ->
                Text(message, color = MenuColors.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 300) draft = it },
                    placeholder = { Text("Mensagem...", color = Color.White.copy(alpha = 0.4f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MenuColors.TableGreenLight,
                        unfocusedBorderColor = MenuColors.BorderStrong,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MenuColors.TableGreenLight
                    ),
                    modifier = Modifier.weight(1f)
                )
                MenuFilledButton(
                    text = "Enviar",
                    onClick = ::send,
                    enabled = draft.isNotBlank(),
                    containerColor = MenuColors.TableGreenLight,
                    // width fixo: MenuFilledButton sempre pede fillMaxWidth por dentro,
                    // e aqui ele divide a linha com o campo de texto (weight = 1f).
                    modifier = Modifier.width(96.dp)
                )
            }
        }
    }
}

// Paleta pra distinguir remetentes num chat sem avatar de verdade (broadcast
// puro, sem tabela -- ver GlobalChatRepository.kt). Cor derivada de um hash
// do nome, nao de quem enviou primeiro, entao a mesma pessoa sempre aparece
// com a mesma cor durante a sessao inteira.
private val SenderPalette = listOf(
    MenuColors.TableGreenLight,
    MenuColors.Gold,
    Color(0xFF7EA6FF),
    Color(0xFFFF8A65),
    Color(0xFFBA68C8),
    Color(0xFF4DD0E1)
)

private fun senderColor(name: String): Color =
    SenderPalette[(name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % SenderPalette.size]

private fun senderInitial(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

@Composable
private fun GlobalChatBubble(entry: GlobalChatEntry) {
    val accent = if (entry.isSelf) MenuColors.TableGreenLight else senderColor(entry.senderName)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (entry.isSelf) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!entry.isSelf) SenderAvatar(name = entry.senderName, color = accent)
        Column(horizontalAlignment = if (entry.isSelf) Alignment.End else Alignment.Start) {
            Text(
                text = if (entry.isSelf) "Você" else entry.senderName,
                color = accent.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .background(
                        if (entry.isSelf) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = if (entry.isSelf) 12.dp else 2.dp,
                            bottomEnd = if (entry.isSelf) 2.dp else 12.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = entry.body, color = Color.White, fontSize = 13.sp)
            }
        }
        if (entry.isSelf) SenderAvatar(name = "Você", color = accent)
    }
}

@Composable
private fun SenderAvatar(name: String, color: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(color.copy(alpha = 0.24f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(senderInitial(name), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private class PreviewGlobalChatRepository(
    private val seed: List<GlobalChatEntry>
) : GlobalChatRepository {
    override suspend fun sendMessage(playerName: String, body: String): Boolean = true
    override fun observeMessages(playerName: String) = kotlinx.coroutines.flow.flow {
        seed.forEach { emit(it) }
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "GlobalChatScreen - com mensagens")
@Composable
private fun GlobalChatScreenPreview() {
    MaterialTheme {
        GlobalChatScreen(
            playerName = "Bruno",
            repository = PreviewGlobalChatRepository(
                listOf(
                    GlobalChatEntry(id = 1, senderName = "Carlos", body = "Alguém pra jogar Buraco?", isSelf = false),
                    GlobalChatEntry(id = 2, senderName = "Bruno", body = "Eu topo!", isSelf = true),
                    GlobalChatEntry(id = 3, senderName = "Ana", body = "Cria a sala aí", isSelf = false)
                )
            ),
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "GlobalChatScreen - vazio")
@Composable
private fun GlobalChatScreenEmptyPreview() {
    MaterialTheme {
        GlobalChatScreen(
            playerName = "Bruno",
            repository = PreviewGlobalChatRepository(emptyList()),
            onBack = {}
        )
    }
}
