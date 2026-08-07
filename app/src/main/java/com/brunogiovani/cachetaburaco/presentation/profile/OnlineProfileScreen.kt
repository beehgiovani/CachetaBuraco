package com.brunogiovani.cachetaburaco.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import com.brunogiovani.cachetaburaco.domain.models.OnlineProfile
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineProfileRepository
import com.brunogiovani.cachetaburaco.presentation.components.MenuBackdrop
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuEntrance
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuPressScale
import com.brunogiovani.cachetaburaco.presentation.components.MenuSectionCard
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import com.brunogiovani.cachetaburaco.presentation.components.MenuStatusMessage
import com.brunogiovani.cachetaburaco.presentation.components.MenuTopBar
import com.brunogiovani.cachetaburaco.presentation.components.OnlineAvatarView
import com.brunogiovani.cachetaburaco.presentation.components.avatarLabel
import kotlinx.coroutines.launch

internal sealed interface OnlineProfileUiState {
    data object Loading : OnlineProfileUiState
    data class Ready(
        val profile: OnlineProfile,
        val selectedAvatar: OnlineAvatar = profile.avatar,
        val saving: Boolean = false,
        val feedback: String? = null
    ) : OnlineProfileUiState

    data class Error(val message: String) : OnlineProfileUiState
}

@Composable
fun OnlineProfileScreen(
    playerName: String,
    repository: OnlineProfileRepository,
    onBack: () -> Unit
) {
    var state by remember { mutableStateOf<OnlineProfileUiState>(OnlineProfileUiState.Loading) }
    var reloadRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playerName, reloadRequest) {
        state = OnlineProfileUiState.Loading
        state = runCatching { repository.loadProfile(playerName) }
            .fold(
                onSuccess = { OnlineProfileUiState.Ready(it) },
                onFailure = { OnlineProfileUiState.Error("Não foi possível carregar seu perfil online.") }
            )
    }

    OnlineProfileContent(
        state = state,
        onBack = onBack,
        onRetry = { reloadRequest++ },
        onAvatarSelected = { avatar ->
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(selectedAvatar = avatar, feedback = null)
        },
        onSave = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            if (ready.saving || ready.selectedAvatar == ready.profile.avatar) return@OnlineProfileContent
            state = ready.copy(saving = true, feedback = null)
            scope.launch {
                state = runCatching { repository.updateAvatar(playerName, ready.selectedAvatar) }
                    .fold(
                        onSuccess = {
                            OnlineProfileUiState.Ready(
                                profile = it,
                                selectedAvatar = it.avatar,
                                feedback = "Avatar sincronizado."
                            )
                        },
                        onFailure = {
                            ready.copy(saving = false, feedback = "Não foi possível salvar agora.")
                        }
                    )
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineProfileContent(
    state: OnlineProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAvatarSelected: (OnlineAvatar) -> Unit,
    onSave: () -> Unit
) {
    MenuBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp || maxHeight < 430.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(if (compact) 10.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)
            ) {
                MenuTopBar(title = "Perfil online", onBack = onBack)
                when (state) {
                    OnlineProfileUiState.Loading -> {
                        Spacer(Modifier.height(24.dp))
                        MenuStatusMessage(text = "Carregando seu perfil online...")
                    }

                    is OnlineProfileUiState.Error -> {
                        Spacer(Modifier.height(24.dp))
                        MenuStatusMessage(text = state.message, showSpinner = false)
                        Spacer(Modifier.height(4.dp))
                        MenuFilledButton(
                            text = "Tentar novamente",
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = 44.dp)
                        )
                    }

                    is OnlineProfileUiState.Ready -> {
                        val hasPendingChange = state.selectedAvatar != state.profile.avatar

                        MenuEntrance {
                            MenuSectionCard {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OnlineAvatarView(
                                        avatar = state.selectedAvatar,
                                        playerName = state.profile.playerName,
                                        size = if (compact) 72.dp else 96.dp
                                    )
                                    Text(
                                        state.profile.playerName,
                                        color = MenuColors.OnDark,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (compact) 20.sp else 26.sp
                                    )
                                    Text(
                                        "É assim que você aparece no ranking e nas salas online.",
                                        color = MenuColors.OnDarkMuted,
                                        textAlign = TextAlign.Center,
                                        fontSize = if (compact) 11.sp else 13.sp
                                    )
                                }
                            }
                        }

                        MenuEntrance(delayMillis = 60) {
                            MenuSectionCard(title = "Escolha seu avatar") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
                                ) {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        maxItemsInEachRow = 6,
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OnlineAvatar.entries.forEach { avatar ->
                                            AvatarOption(
                                                avatar = avatar,
                                                playerName = state.profile.playerName,
                                                selected = avatar == state.selectedAvatar,
                                                compact = compact,
                                                onClick = { onAvatarSelected(avatar) }
                                            )
                                        }
                                    }
                                    MenuFilledButton(
                                        text = if (hasPendingChange) "Salvar avatar" else "Avatar salvo",
                                        onClick = onSave,
                                        enabled = !state.saving && hasPendingChange,
                                        loading = state.saving,
                                        containerColor = MenuColors.TableGreenLight
                                    )
                                    state.feedback?.let {
                                        Text(
                                            it,
                                            color = if (it.startsWith("Avatar")) MenuColors.TableGreenLight else MenuColors.Gold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarOption(
    avatar: OnlineAvatar,
    playerName: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    MenuPressScale(onClick = onClick) {
        Column(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .background(
                    if (selected) MenuColors.Gold.copy(alpha = 0.16f) else Color.Transparent,
                    MenuShapes.Card
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MenuColors.Gold else Color.White.copy(alpha = 0.12f),
                    shape = MenuShapes.Card
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            OnlineAvatarView(
                avatar = avatar,
                playerName = playerName,
                size = if (compact) 40.dp else 52.dp
            )
            Text(
                avatarLabel(avatar),
                color = if (selected) MenuColors.Gold else MenuColors.OnDarkMuted,
                fontSize = if (compact) 8.sp else 10.sp,
                maxLines = 1
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240", name = "Perfil - tablet/paisagem")
@Preview(
    showBackground = true,
    device = "spec:width=800dp,height=360dp,dpi=320",
    fontScale = 1.5f,
    name = "Perfil compacto - fonte grande"
)
@Composable
private fun OnlineProfilePreview() {
    MaterialTheme {
        OnlineProfileContent(
            state = OnlineProfileUiState.Ready(
                profile = OnlineProfile("preview", "Jogador", OnlineAvatar.SAPPHIRE),
                selectedAvatar = OnlineAvatar.RUBY
            ),
            onBack = {},
            onRetry = {},
            onAvatarSelected = {},
            onSave = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Perfil - celular compacto")
@Composable
private fun OnlineProfileCompactPreview() {
    MaterialTheme {
        OnlineProfileContent(
            state = OnlineProfileUiState.Ready(
                profile = OnlineProfile("preview", "Bruno", OnlineAvatar.GOLD),
                selectedAvatar = OnlineAvatar.GOLD
            ),
            onBack = {},
            onRetry = {},
            onAvatarSelected = {},
            onSave = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Perfil - carregando")
@Composable
private fun OnlineProfileLoadingPreview() {
    MaterialTheme {
        OnlineProfileContent(
            state = OnlineProfileUiState.Loading,
            onBack = {},
            onRetry = {},
            onAvatarSelected = {},
            onSave = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812, name = "Perfil - erro")
@Composable
private fun OnlineProfileErrorPreview() {
    MaterialTheme {
        OnlineProfileContent(
            state = OnlineProfileUiState.Error("Não foi possível carregar seu perfil online."),
            onBack = {},
            onRetry = {},
            onAvatarSelected = {},
            onSave = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 812, heightDp = 375, name = "Perfil - celular paisagem")
@Composable
private fun OnlineProfileLandscapePreview() {
    MaterialTheme {
        OnlineProfileContent(
            state = OnlineProfileUiState.Ready(
                profile = OnlineProfile("preview", "Bruno", OnlineAvatar.VIOLET),
                selectedAvatar = OnlineAvatar.VIOLET,
                saving = true
            ),
            onBack = {},
            onRetry = {},
            onAvatarSelected = {},
            onSave = {}
        )
    }
}
