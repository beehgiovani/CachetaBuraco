package com.brunogiovani.cachetaburaco.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.brunogiovani.cachetaburaco.R
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.models.DeckColor

@Composable
fun CardView(
    card: Card,
    modifier: Modifier = Modifier,
    isFaceUp: Boolean = true
) {
    val cardShape = RoundedCornerShape(12.dp)

    CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(density = LocalDensity.current.density, fontScale = 1f)
    ) {
        BoxWithConstraints(
            modifier = modifier
                .defaultMinSize(minWidth = 80.dp, minHeight = 120.dp)
                .shadow(6.dp, cardShape, clip = false)
                .clip(cardShape)
                .background(
                    if (isFaceUp) {
                        Brush.linearGradient(
                            colors = listOf(Color.White, Color(0xFFFAF9F6), Color(0xFFE6DFD0)),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF2D3748), Color(0xFF1A202C), Color(0xFF0D1117)),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    }
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White, Color.Black.copy(alpha = 0.2f)),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    ),
                    shape = cardShape
                )
                .border(2.dp, Color.Black.copy(alpha = 0.08f), cardShape),
            contentAlignment = Alignment.Center
        ) {
            val scaleFactor = maxWidth.value / 80f
            
            if (isFaceUp) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f),
                        radius = size.minDimension * 0.48f,
                        center = Offset(size.width * 0.25f, size.height * 0.2f)
                    )
                }
                if (card.isJoker) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "★",
                            fontSize = (34 * scaleFactor).sp,
                            color = Color(0xFFD6A928),
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "JOKER",
                            fontSize = (14 * scaleFactor).sp,
                            color = Color(0xFF212121),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    val color = if (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS) Color(0xFFD32F2F) else Color(0xFF212121)
                    val mutedColor = color.copy(alpha = 0.12f)
                    val displaySymbol = when (card.suit) {
                        Suit.HEARTS -> "♥"
                        Suit.DIAMONDS -> "♦"
                        Suit.CLUBS -> "♣"
                        Suit.SPADES -> "♠"
                    }
                    val rankText = when (card.rank.value) {
                        1 -> "A"
                        11 -> "J"
                        12 -> "Q"
                        13 -> "K"
                        else -> card.rank.value.toString()
                    }
    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding((6 * scaleFactor).dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Left
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = rankText, color = color, fontWeight = FontWeight.Bold, fontSize = (16 * scaleFactor).sp)
                            Text(text = displaySymbol, color = color, fontSize = (14 * scaleFactor).sp)
                        }
    
                        // Center Symbol
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size((48 * scaleFactor).dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displaySymbol,
                                color = mutedColor,
                                fontSize = (48 * scaleFactor).sp
                            )
                            Text(
                                text = displaySymbol,
                                color = color,
                                fontSize = (30 * scaleFactor).sp
                            )
                        }
    
                        // Bottom Right
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(text = rankText, color = color, fontWeight = FontWeight.Bold, fontSize = (16 * scaleFactor).sp)
                            Text(text = displaySymbol, color = color, fontSize = (14 * scaleFactor).sp)
                        }
                    }
                }
            } else {
                Image(
                    painter = painterResource(
                        id = if (card.deckColor == DeckColor.RED) {
                            R.drawable.card_back_red
                        } else {
                            R.drawable.card_back
                        }
                    ),
                    contentDescription = "Verso da carta",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
