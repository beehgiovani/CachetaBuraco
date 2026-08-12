package com.brunogiovani.cachetaburaco.presentation.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.online.GoogleAccountLinker
import com.brunogiovani.cachetaburaco.data.online.GoogleLinkResult
import com.brunogiovani.cachetaburaco.data.online.SupabaseClientProvider
import com.brunogiovani.cachetaburaco.data.online.SupabaseIdentity
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.Player
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuEntrance
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    identity: SupabaseIdentity = remember { SupabaseIdentity(SupabaseClientProvider.client) },
    googleLinker: GoogleAccountLinker = remember { GoogleAccountLinker() }
) {
    val hasSaved = FakeAuthRepository.hasSavedProfile()
    val savedPlayer = FakeAuthRepository.getCurrentPlayer()
    var showNewProfile by remember { mutableStateOf(!hasSaved) }
    var nickname by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingGoogle by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

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
                .background(MenuColors.backgroundScrim())
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            MenuEntrance {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
                    colors = CardDefaults.cardColors(containerColor = MenuColors.InkPanel),
                    shape = MenuShapes.Card,
                    elevation = CardDefaults.cardElevation(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.game_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.height(92.dp),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            text = "Carteado BR - Cacheta, Buraco e Tranca",
                            color = MenuColors.Gold.copy(alpha = 0.88f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(22.dp))
                        HorizontalDivider(color = MenuColors.Border)
                        Spacer(modifier = Modifier.height(18.dp))

                        AnimatedContent(
                            targetState = showNewProfile,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "login_state"
                        ) { creatingNew ->
                            if (!creatingNew && savedPlayer != null) {
                                SavedProfileContent(
                                    playerName = savedPlayer.name,
                                    onContinue = onLoginSuccess,
                                    onChangeName = { showNewProfile = true }
                                )
                            } else {
                                NewProfileContent(
                                    nickname = nickname,
                                    error = error,
                                    isLoading = isLoading,
                                    isLoadingGoogle = isLoadingGoogle,
                                    hasSaved = hasSaved,
                                    onNicknameChange = {
                                        if (it.length <= 20) {
                                            nickname = it
                                            error = ""
                                        }
                                    },
                                    onDone = { focusManager.clearFocus() },
                                    onCreateGuest = {
                                        val trimmed = nickname.trim()
                                        if (trimmed.length < 2) {
                                            error = "Apelido deve ter ao menos 2 caracteres"
                                            return@NewProfileContent
                                        }
                                        isLoading = true
                                        error = ""
                                        focusManager.clearFocus()
                                        coroutineScope.launch {
                                            // ensure() cria (ou reaproveita) a mesma identidade anonima
                                            // do Supabase -- o ID local passa a ser o ID de verdade que
                                            // ja vale pro ranking/campeonato online, nao mais um UUID
                                            // aleatorio so deste aparelho.
                                            val playerId = runCatching { identity.ensure(trimmed) }.getOrNull()
                                            isLoading = false
                                            if (playerId == null) {
                                                error = "Sem conexão. Tente novamente."
                                                return@launch
                                            }
                                            FakeAuthRepository.loginWithId(playerId, trimmed)
                                            onLoginSuccess()
                                        }
                                    },
                                    onContinueWithGoogle = {
                                        isLoadingGoogle = true
                                        error = ""
                                        focusManager.clearFocus()
                                        coroutineScope.launch {
                                            // linkIdentityWithIdToken exige uma sessao anonima ja aberta --
                                            // o nome aqui e so um rotulo temporario, substituido pelo nome
                                            // real do Google assim que o vinculo funcionar.
                                            val fallbackName = nickname.trim().ifBlank { "Jogador" }
                                            val playerId = runCatching { identity.ensure(fallbackName) }.getOrNull()
                                            if (playerId == null) {
                                                isLoadingGoogle = false
                                                error = "Sem conexão. Tente novamente."
                                                return@launch
                                            }
                                            when (val result = googleLinker.link(context)) {
                                                is GoogleLinkResult.Success -> {
                                                    val googleName = result.displayName?.trim()?.take(20)
                                                        ?.ifBlank { null } ?: fallbackName
                                                    val finalId = runCatching { identity.ensure(googleName) }
                                                        .getOrDefault(playerId)
                                                    isLoadingGoogle = false
                                                    FakeAuthRepository.loginWithId(finalId, googleName)
                                                    onLoginSuccess()
                                                }
                                                is GoogleLinkResult.Cancelled -> {
                                                    isLoadingGoogle = false
                                                }
                                                is GoogleLinkResult.NoGoogleAccountOnDevice -> {
                                                    isLoadingGoogle = false
                                                    error = "Nenhuma conta Google encontrada neste aparelho."
                                                }
                                                is GoogleLinkResult.Failed -> {
                                                    isLoadingGoogle = false
                                                    error = result.message
                                                }
                                            }
                                        }
                                    },
                                    onBackToSaved = { showNewProfile = false }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MenuColors.Border)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Modo Local - Rede Wi-Fi",
                            color = Color.White.copy(alpha = 0.38f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedProfileContent(
    playerName: String,
    onContinue: () -> Unit,
    onChangeName: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bem-vindo de volta!", color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(playerName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("Modo Local", color = MenuColors.TableGreenLight, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(24.dp))

        MenuFilledButton(
            text = "CONTINUAR JOGANDO",
            onClick = onContinue,
            containerColor = MenuColors.TableGreenLight
        )

        Spacer(modifier = Modifier.height(10.dp))
        TextButton(onClick = onChangeName) {
            Text("Trocar apelido", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProfileContent(
    nickname: String,
    error: String,
    isLoading: Boolean,
    isLoadingGoogle: Boolean,
    hasSaved: Boolean,
    onNicknameChange: (String) -> Unit,
    onDone: () -> Unit,
    onCreateGuest: () -> Unit,
    onContinueWithGoogle: () -> Unit,
    onBackToSaved: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Entrar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Escolha como quer entrar", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            label = { Text("Apelido", color = Color.White.copy(alpha = 0.55f)) },
            singleLine = true,
            isError = error.isNotBlank(),
            supportingText = {
                Text(
                    text = if (error.isNotBlank()) error else "${nickname.length}/20",
                    color = if (error.isNotBlank()) MenuColors.Red else Color.White.copy(alpha = 0.38f)
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MenuColors.TableGreenLight,
                unfocusedBorderColor = MenuColors.BorderStrong,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = MenuColors.TableGreenLight
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))
        MenuFilledButton(
            text = "ENTRAR COMO CONVIDADO",
            onClick = onCreateGuest,
            enabled = !isLoading && !isLoadingGoogle && nickname.isNotBlank(),
            loading = isLoading,
            containerColor = MenuColors.TableGreenLight
        )
        Text(
            text = "Rápido, sem conta. Seu progresso fica salvo só neste aparelho.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = MenuColors.Border)
            Text(
                "ou",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = MenuColors.Border)
        }
        Spacer(modifier = Modifier.height(10.dp))

        MenuFilledButton(
            text = "CONTINUAR COM GOOGLE",
            onClick = onContinueWithGoogle,
            enabled = !isLoading && !isLoadingGoogle,
            loading = isLoadingGoogle,
            containerColor = MenuColors.InkPanelSoft
        )
        Text(
            text = "Mantém ranking, medalhas e XP se você trocar de aparelho ou reinstalar o app.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
        )

        if (hasSaved) {
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onBackToSaved) {
                Text("Voltar ao perfil salvo", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Login - tablet/paisagem")
@Composable
fun LoginScreenPreview() {
    FakeAuthRepository.logout()
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Login - celular compacto")
@Composable
fun LoginScreenCompactPreview() {
    FakeAuthRepository.logout()
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
    fontScale = 1.5f,
    name = "Login - fonte grande (1.5x)"
)
@Composable
fun LoginScreenLargeFontPreview() {
    FakeAuthRepository.logout()
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
    fontScale = 2.0f,
    name = "Login - fonte extra grande (2x)"
)
@Composable
fun LoginScreenExtraLargeFontPreview() {
    FakeAuthRepository.logout()
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 400, name = "Login - paisagem baixa")
@Composable
fun LoginScreenLandscapePreview() {
    FakeAuthRepository.logout()
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Login - perfil salvo")
@Composable
fun LoginScreenSavedProfilePreview() {
    FakeAuthRepository.forceSetForPreview(Player("preview_saved", "Bruno"))
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}
