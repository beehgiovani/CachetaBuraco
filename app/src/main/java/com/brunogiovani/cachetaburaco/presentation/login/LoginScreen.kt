package com.brunogiovani.cachetaburaco.presentation.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import kotlinx.coroutines.launch

private val ColorGreenLight = Color(0xFF4CAF50)
private val ColorGold = Color(0xFFFFD54F)
private val ColorCard = Color(0xFF121820)
private val ColorBorder = Color(0xFF30363D)

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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xDD040A14), Color(0xDD06120F))
                    )
                )
        )

        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(containerColor = ColorCard.copy(alpha = 0.94f)),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                    text = "Cacheta - Buraco - Tranca",
                    color = ColorGold.copy(alpha = 0.86f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(22.dp))
                HorizontalDivider(color = ColorBorder)
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
                HorizontalDivider(color = ColorBorder)
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
        Text("Modo Local", color = ColorGreenLight, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("CONTINUAR JOGANDO", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

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
                    color = if (error.isNotBlank()) Color(0xFFEF5350) else Color.White.copy(alpha = 0.38f)
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ColorGreenLight,
                unfocusedBorderColor = ColorBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = ColorGreenLight
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorGreenLight),
            shape = RoundedCornerShape(14.dp),
            enabled = !isLoading && nickname.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                Text("CRIAR PERFIL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (hasSaved) {
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onBackToSaved) {
                Text("Voltar ao perfil salvo", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun LoginScreenPreview() {
    MaterialTheme {
        LoginScreen(onLoginSuccess = {})
    }
}
