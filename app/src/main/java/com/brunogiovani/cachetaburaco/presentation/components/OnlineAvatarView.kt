package com.brunogiovani.cachetaburaco.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar

@Composable
fun OnlineAvatarView(
    avatar: OnlineAvatar,
    playerName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    photoUrl: String? = null
) {
    if (photoUrl != null) {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape)
        )
        return
    }

    val colors = avatarColors(avatar)
    val textSize = when {
        size <= 34.dp -> 14.sp
        size <= 60.dp -> 22.sp
        else -> 34.sp
    }
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(colors), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = avatarSymbol(avatar, playerName),
            color = Color.White,
            fontSize = textSize,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun OnlineAvatarView(
    avatarId: String?,
    playerName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    photoUrl: String? = null
) {
    OnlineAvatarView(
        avatar = OnlineAvatar.fromStorageId(avatarId),
        playerName = playerName,
        size = size,
        modifier = modifier,
        photoUrl = photoUrl
    )
}

internal fun avatarLabel(avatar: OnlineAvatar): String = when (avatar) {
    OnlineAvatar.EMERALD -> "Esmeralda"
    OnlineAvatar.GOLD -> "Ouro"
    OnlineAvatar.RUBY -> "Rubi"
    OnlineAvatar.SAPPHIRE -> "Safira"
    OnlineAvatar.VIOLET -> "Violeta"
    OnlineAvatar.GRAPHITE -> "Grafite"
}

internal fun avatarSymbol(avatar: OnlineAvatar, playerName: String): String = when (avatar) {
    OnlineAvatar.EMERALD -> "♣"
    OnlineAvatar.GOLD -> "♦"
    OnlineAvatar.RUBY -> "♥"
    OnlineAvatar.SAPPHIRE -> "♠"
    OnlineAvatar.VIOLET -> "★"
    OnlineAvatar.GRAPHITE -> playerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "J"
}

private fun avatarColors(avatar: OnlineAvatar): List<Color> = when (avatar) {
    OnlineAvatar.EMERALD -> listOf(Color(0xFF087F5B), Color(0xFF174E3B))
    OnlineAvatar.GOLD -> listOf(Color(0xFFD99B16), Color(0xFF76500A))
    OnlineAvatar.RUBY -> listOf(Color(0xFFC63D4F), Color(0xFF6F1F2B))
    OnlineAvatar.SAPPHIRE -> listOf(Color(0xFF2775C7), Color(0xFF163F72))
    OnlineAvatar.VIOLET -> listOf(Color(0xFF8E4EC6), Color(0xFF4B286D))
    OnlineAvatar.GRAPHITE -> listOf(Color(0xFF56616C), Color(0xFF252B31))
}

@Preview(showBackground = true)
@Composable
private fun OnlineAvatarPreview() {
    MaterialTheme {
        OnlineAvatarView(avatar = OnlineAvatar.RUBY, playerName = "Jogador", size = 72.dp)
    }
}
