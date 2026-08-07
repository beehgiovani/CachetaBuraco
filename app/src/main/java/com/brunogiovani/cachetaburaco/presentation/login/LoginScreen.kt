package com.brunogiovani.cachetaburaco.presentation.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.Player
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuEntrance
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val hasSaved = FakeAuthRepository.hasSavedProfile()
    val savedPlayer = FakeAuthRepository.getCurrentPlayer()
    var showNewProfile by remember { mutableStateOf(!hasSaved) }
    var nickname by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

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
                                    hasSaved = hasSaved,
                                    onNicknameChange = {
                                        if (it.length <= 20) {
                                            nickname = it
                                            error = ""
                                        }
                                    },
                                    onDone = { focusManager.clearFocus() },
                                    onCreate = {
                                        val trimmed = nickname.trim()
                                        if (trimmed.length < 2) {
                                            error = "Apelido deve ter ao menos 2 caracteres"
                                            return@NewProfileContent
                                        }
                                        isLoading = true
                                        focusManager.clearFocus()
                                        coroutineScope.launch {
                                            FakeAuthRepository.login(trimmed)
                                            isLoading = false
                                            onLoginSuccess()
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
    hasSaved: Boolean,
    onNicknameChange: (String) -> Unit,
    onDone: () -> Unit,
    onCreate: () -> Unit,
    onBackToSaved: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Criar perfil", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Escolha um apelido para jogar", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
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
            text = "CRIAR PERFIL",
            onClick = onCreate,
            enabled = !isLoading && nickname.isNotBlank(),
            loading = isLoading,
            containerColor = MenuColors.TableGreenLight
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
