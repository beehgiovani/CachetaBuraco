package com.brunogiovani.cachetaburaco.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.data.online.GoogleAccountLinker
import com.brunogiovani.cachetaburaco.data.online.GoogleLinkResult
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.EarnedMedal
import com.brunogiovani.cachetaburaco.domain.models.MedalCatalog
import com.brunogiovani.cachetaburaco.domain.models.MedalDefinition
import com.brunogiovani.cachetaburaco.domain.models.MedalTier
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
        val feedback: String? = null,
        val linkingGoogle: Boolean = false,
        val googleLinkFeedback: String? = null,
        val medals: List<EarnedMedal> = emptyList(),
        val pendingCropUri: Uri? = null,
        val uploadingPhoto: Boolean = false,
        val photoFeedback: String? = null,
        val showDeleteConfirm: Boolean = false,
        val deletingAccount: Boolean = false,
        val deleteFeedback: String? = null
    ) : OnlineProfileUiState

    data class Error(val message: String) : OnlineProfileUiState
}

@Composable
fun OnlineProfileScreen(
    playerName: String,
    repository: OnlineProfileRepository,
    googleLinker: GoogleAccountLinker = remember { GoogleAccountLinker() },
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit = {}
) {
    var state by remember { mutableStateOf<OnlineProfileUiState>(OnlineProfileUiState.Loading) }
    var reloadRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val ready = state as? OnlineProfileUiState.Ready ?: return@rememberLauncherForActivityResult
        if (uri != null) state = ready.copy(pendingCropUri = uri)
    }

    LaunchedEffect(playerName, reloadRequest) {
        state = OnlineProfileUiState.Loading
        state = runCatching { repository.loadProfile(playerName) }
            .fold(
                onSuccess = { profile ->
                    val medals = runCatching { repository.loadMedals(playerName) }.getOrDefault(emptyList())
                    OnlineProfileUiState.Ready(profile = profile, medals = medals)
                },
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
                                feedback = "Avatar sincronizado.",
                                medals = ready.medals
                            )
                        },
                        onFailure = {
                            ready.copy(saving = false, feedback = "Não foi possível salvar agora.")
                        }
                    )
            }
        },
        onLinkGoogle = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            if (ready.linkingGoogle) return@OnlineProfileContent
            state = ready.copy(linkingGoogle = true, googleLinkFeedback = null)
            scope.launch {
                val result = googleLinker.link(context)
                val current = state as? OnlineProfileUiState.Ready ?: return@launch
                state = current.copy(
                    linkingGoogle = false,
                    googleLinkFeedback = googleLinkFeedbackFor(result)
                )
            }
        },
        onPickPhoto = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onCropCancelled = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(pendingCropUri = null)
        },
        onCropConfirmed = { jpegBytes ->
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(pendingCropUri = null, uploadingPhoto = true, photoFeedback = null)
            scope.launch {
                state = runCatching { repository.uploadAvatarPhoto(playerName, jpegBytes) }
                    .fold(
                        onSuccess = {
                            ready.copy(
                                profile = it,
                                selectedAvatar = it.avatar,
                                pendingCropUri = null,
                                uploadingPhoto = false,
                                photoFeedback = "Foto atualizada."
                            )
                        },
                        onFailure = {
                            ready.copy(
                                pendingCropUri = null,
                                uploadingPhoto = false,
                                photoFeedback = "Não foi possível enviar a foto agora."
                            )
                        }
                    )
            }
        },
        onClearPhoto = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            if (ready.uploadingPhoto) return@OnlineProfileContent
            state = ready.copy(uploadingPhoto = true, photoFeedback = null)
            scope.launch {
                state = runCatching { repository.clearAvatarPhoto(playerName) }
                    .fold(
                        onSuccess = {
                            ready.copy(
                                profile = it,
                                selectedAvatar = it.avatar,
                                uploadingPhoto = false,
                                photoFeedback = "Foto removida."
                            )
                        },
                        onFailure = {
                            ready.copy(uploadingPhoto = false, photoFeedback = "Não foi possível remover a foto agora.")
                        }
                    )
            }
        },
        onDeleteAccountRequested = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(showDeleteConfirm = true)
        },
        onDeleteAccountDismissed = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(showDeleteConfirm = false)
        },
        onDeleteAccountConfirmed = {
            val ready = state as? OnlineProfileUiState.Ready ?: return@OnlineProfileContent
            state = ready.copy(showDeleteConfirm = false, deletingAccount = true)
            scope.launch {
                runCatching { repository.deleteAccount(playerName) }
                    .onSuccess {
                        FakeAuthRepository.logout()
                        onAccountDeleted()
                    }
                    .onFailure {
                        val current = state as? OnlineProfileUiState.Ready ?: return@launch
                        state = current.copy(
                            deletingAccount = false,
                            deleteFeedback = "Não foi possível excluir sua conta agora."
                        )
                    }
            }
        }
    )
}

/** Resumo da conta mostrado no card "Conta": convidado, ou e-mail + data de criacao. */
internal fun accountInfoLabel(profile: OnlineProfile): String {
    if (profile.isAnonymous) return "Você está jogando como convidado."
    val email = profile.email ?: "conta vinculada"
    val date = formatAccountDate(profile.accountCreatedAt)
    return if (date != null) "$email · desde $date" else email
}

private fun formatAccountDate(iso: String?): String? {
    if (iso == null) return null
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(zoned)
    }.getOrNull()
}

/** Mensagem exibida apos tentar vincular a conta Google, ou null pra ficar em silencio (cancelamento). */
internal fun googleLinkFeedbackFor(result: GoogleLinkResult): String? = when (result) {
    is GoogleLinkResult.Success ->
        "Conta Google vinculada! Seu perfil fica protegido mesmo se trocar de aparelho."
    is GoogleLinkResult.Cancelled -> null
    is GoogleLinkResult.NoGoogleAccountOnDevice ->
        "Nenhuma conta Google encontrada neste aparelho."
    is GoogleLinkResult.Failed ->
        "Não foi possível vincular a conta Google agora. Tente novamente."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineProfileContent(
    state: OnlineProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAvatarSelected: (OnlineAvatar) -> Unit,
    onSave: () -> Unit,
    onLinkGoogle: () -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onCropConfirmed: (ByteArray) -> Unit = {},
    onCropCancelled: () -> Unit = {},
    onClearPhoto: () -> Unit = {},
    onDeleteAccountRequested: () -> Unit = {},
    onDeleteAccountConfirmed: () -> Unit = {},
    onDeleteAccountDismissed: () -> Unit = {}
) {
    MenuBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp || maxHeight < 430.dp
            // Fonte grande (sistema) deixa 6 colunas apertadas demais e quebra
            // palavra no meio (ex.: "Inician-te") -- reduz a grade nesse caso,
            // mesmo em tela larga.
            val largeFont = LocalDensity.current.fontScale >= 1.25f
            val gridColumns = when {
                compact -> 3
                largeFont -> 3
                else -> 6
            }

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
                                        size = if (compact) 72.dp else 96.dp,
                                        photoUrl = state.profile.avatarPhotoUrl
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

                        MenuEntrance(delayMillis = 20) {
                            MenuSectionCard(title = "Foto de perfil") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
                                ) {
                                    Text(
                                        "Use uma foto real, se quiser. Fica visível pra outros jogadores no ranking e nas salas.",
                                        color = MenuColors.OnDarkMuted,
                                        textAlign = TextAlign.Center,
                                        fontSize = if (compact) 11.sp else 13.sp
                                    )
                                    MenuFilledButton(
                                        text = if (state.profile.avatarPhotoUrl != null) "Trocar foto" else "Escolher foto",
                                        onClick = onPickPhoto,
                                        enabled = !state.uploadingPhoto,
                                        loading = state.uploadingPhoto,
                                        containerColor = MenuColors.TableGreenLight
                                    )
                                    if (state.profile.avatarPhotoUrl != null) {
                                        MenuFilledButton(
                                            text = "Remover foto",
                                            onClick = onClearPhoto,
                                            enabled = !state.uploadingPhoto,
                                            containerColor = MenuColors.OnDarkMuted.copy(alpha = 0.24f)
                                        )
                                    }
                                    state.photoFeedback?.let {
                                        Text(
                                            it,
                                            color = if (it.startsWith("Não")) MenuColors.Gold else MenuColors.TableGreenLight,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        state.pendingCropUri?.let { uri ->
                            AvatarPhotoCropDialog(
                                imageUri = uri,
                                onConfirm = onCropConfirmed,
                                onDismiss = onCropCancelled
                            )
                        }

                        MenuEntrance(delayMillis = 40) {
                            MenuSectionCard(title = "Medalhas") {
                                val earnedCodes = remember(state.medals) { state.medals.map { it.code }.toSet() }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
                                ) {
                                    Text(
                                        "${earnedCodes.size} de ${MedalCatalog.all.size} conquistadas",
                                        color = MenuColors.OnDarkMuted,
                                        fontSize = if (compact) 11.sp else 13.sp
                                    )
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        maxItemsInEachRow = gridColumns,
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        MedalCatalog.all.forEach { medal ->
                                            MedalBadge(
                                                medal = medal,
                                                earned = medal.code in earnedCodes,
                                                compact = compact,
                                                largeFont = largeFont
                                            )
                                        }
                                    }
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
                                        maxItemsInEachRow = gridColumns,
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

                        MenuEntrance(delayMillis = 120) {
                            MenuSectionCard(title = "Conta") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        accountInfoLabel(state.profile),
                                        color = MenuColors.OnDark,
                                        textAlign = TextAlign.Center,
                                        fontSize = if (compact) 12.sp else 14.sp
                                    )
                                    if (state.profile.isAnonymous) {
                                        Text(
                                            "Vincule sua conta Google para não perder o perfil e o ranking se trocar de aparelho.",
                                            color = MenuColors.OnDarkMuted,
                                            textAlign = TextAlign.Center,
                                            fontSize = if (compact) 11.sp else 13.sp
                                        )
                                        MenuFilledButton(
                                            text = "Vincular conta Google",
                                            onClick = onLinkGoogle,
                                            enabled = !state.linkingGoogle,
                                            loading = state.linkingGoogle
                                        )
                                        state.googleLinkFeedback?.let {
                                            Text(
                                                it,
                                                color = if (it.startsWith("Conta Google vinculada")) MenuColors.TableGreenLight else MenuColors.Gold,
                                                textAlign = TextAlign.Center,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    MenuFilledButton(
                                        text = "Excluir conta",
                                        onClick = onDeleteAccountRequested,
                                        enabled = !state.deletingAccount,
                                        loading = state.deletingAccount,
                                        containerColor = MenuColors.RedDeep
                                    )
                                    state.deleteFeedback?.let {
                                        Text(it, color = MenuColors.Gold, textAlign = TextAlign.Center, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                if (state is OnlineProfileUiState.Ready && state.showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = onDeleteAccountDismissed,
                        containerColor = MenuColors.Ink,
                        shape = MenuShapes.Card,
                        title = { Text("Excluir conta?", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Isso apaga seu perfil, estatísticas, ranking e medalhas permanentemente. Essa ação não pode ser desfeita.",
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = onDeleteAccountConfirmed,
                                colors = ButtonDefaults.buttonColors(containerColor = MenuColors.RedDeep)
                            ) { Text("Excluir") }
                        },
                        dismissButton = {
                            TextButton(onClick = onDeleteAccountDismissed) {
                                Text("Cancelar", color = Color.White.copy(alpha = 0.65f))
                            }
                        }
                    )
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

private val MedalSilver = Color(0xFFC0C0C0)
private val MedalBronze = Color(0xFFCD7F32)

private fun MedalTier.color(): Color = when (this) {
    MedalTier.GOLD -> MenuColors.Gold
    MedalTier.SILVER -> MedalSilver
    MedalTier.BRONZE -> MedalBronze
}

@Composable
private fun MedalBadge(medal: MedalDefinition, earned: Boolean, compact: Boolean, largeFont: Boolean = false) {
    val tierColor = medal.tier.color()
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(max = if (compact) 76.dp else if (largeFont) 140.dp else 92.dp)
            .background(
                if (earned) tierColor.copy(alpha = 0.16f) else Color.Transparent,
                MenuShapes.Card
            )
            .border(
                width = if (earned) 2.dp else 1.dp,
                color = if (earned) tierColor else Color.White.copy(alpha = 0.12f),
                shape = MenuShapes.Card
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.achievement_medal),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (earned) tierColor else Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.size(if (compact) 32.dp else 40.dp)
        )
        Text(
            medal.title,
            color = if (earned) tierColor else MenuColors.OnDarkMuted,
            fontSize = if (compact) 8.sp else 10.sp,
            fontWeight = if (earned) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            medal.description,
            color = MenuColors.OnDarkMuted.copy(alpha = if (earned) 1f else 0.6f),
            fontSize = if (compact) 7.sp else 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
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
                selectedAvatar = OnlineAvatar.RUBY,
                medals = listOf(
                    EarnedMedal("WINS_10", "2026-01-01"),
                    EarnedMedal("STREAK_3", "2026-02-01"),
                    EarnedMedal("CACHETA_WINS_10", "2026-03-01")
                )
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
