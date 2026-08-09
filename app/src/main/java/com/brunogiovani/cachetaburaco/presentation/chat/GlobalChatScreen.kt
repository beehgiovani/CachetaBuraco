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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

// So em memoria, nunca persistido -- e Realtime Broadcast puro (ver
// SupabaseGlobalChatRepository), entao esse limite so evita a lista crescer
// sem fim numa sessao muito longa com o chat aberto.
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
                subtitle = "Sem histórico -- mensagens somem quando você sai",
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
                        items(messages) { entry -> GlobalChatBubble(entry) }
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

@Composable
private fun GlobalChatBubble(entry: GlobalChatEntry) {
    Column(
        horizontalAlignment = if (entry.isSelf) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (entry.isSelf) "Você" else entry.senderName,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .background(
                    if (entry.isSelf) MenuColors.TableGreenLight.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = entry.body, color = Color.White, fontSize = 13.sp)
        }
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
                    GlobalChatEntry(senderName = "Carlos", body = "Alguém pra jogar Buraco?", isSelf = false),
                    GlobalChatEntry(senderName = "Bruno", body = "Eu topo!", isSelf = true),
                    GlobalChatEntry(senderName = "Ana", body = "Cria a sala aí", isSelf = false)
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
