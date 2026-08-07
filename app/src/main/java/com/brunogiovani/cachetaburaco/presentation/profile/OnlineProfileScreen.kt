package com.brunogiovani.cachetaburaco.presentation.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import com.brunogiovani.cachetaburaco.domain.models.OnlineProfile
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineProfileRepository
import com.brunogiovani.cachetaburaco.presentation.components.OnlineAvatarView
import com.brunogiovani.cachetaburaco.presentation.components.avatarLabel
import kotlinx.coroutines.launch

private val ProfilePanel = Color(0xED101820)
private val ProfileGreen = Color(0xFF4CAF50)
private val ProfileGold = Color(0xFFFFD54F)

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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp || maxHeight < 430.dp
        Image(
            painter = painterResource(R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xE6070B10), Color(0xF0071510))))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)
        ) {
            ProfileHeader(compact = compact, onBack = onBack)
            when (state) {
                OnlineProfileUiState.Loading -> {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator(color = ProfileGold)
                }

                is OnlineProfileUiState.Error -> {
                    Text(state.message, color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = onRetry) { Text("Tentar novamente") }
                }

                is OnlineProfileUiState.Ready -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = ProfilePanel,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(if (compact) 12.dp else 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                        ) {
                            OnlineAvatarView(
                                avatar = state.selectedAvatar,
                                playerName = state.profile.playerName,
                                size = if (compact) 68.dp else 92.dp
                            )
                            Text(
                                state.profile.playerName,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 18.sp else 24.sp
                            )
                            Text(
                                "Escolha como você aparece no ranking e nas salas online.",
                                color = Color.White.copy(alpha = 0.64f),
                                textAlign = TextAlign.Center,
                                fontSize = if (compact) 11.sp else 13.sp
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                maxItemsInEachRow = if (compact) 6 else 6,
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
                            Button(
                                onClick = onSave,
                                enabled = !state.saving && state.selectedAvatar != state.profile.avatar,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ProfileGreen)
                            ) {
                                if (state.saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Salvar avatar", fontWeight = FontWeight.Bold)
                                }
                            }
                            state.feedback?.let {
                                Text(
                                    it,
                                    color = if (it.startsWith("Avatar")) ProfileGreen else Color(0xFFFFB74D),
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

@Composable
private fun ProfileHeader(compact: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onBack) { Text("Voltar", color = Color.White) }
        Text(
            "Perfil online",
            color = ProfileGold,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 18.sp else 24.sp
        )
        Spacer(Modifier.size(64.dp))
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
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, ProfileGold, RoundedCornerShape(8.dp))
                else Modifier
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
            color = if (selected) ProfileGold else Color.White.copy(alpha = 0.7f),
            fontSize = if (compact) 8.sp else 10.sp,
            maxLines = 1
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
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
