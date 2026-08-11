package com.brunogiovani.cachetaburaco.presentation.components

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.brunogiovani.cachetaburaco.R
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlin.math.roundToInt

enum class AdPlacement {
    MAIN_MENU,
    LOBBY,
    RANKING,
    RULES,
    ROUND_SUMMARY
}

@Composable
fun SafeAdBannerSlot(
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    placement: AdPlacement = AdPlacement.MAIN_MENU
) {
    if (LocalInspectionMode.current) {
        AdPlaceholder(modifier = modifier, compact = compact)
        return
    }

    // Sem consentimento (UMP) resolvido ainda nao pede anuncio nenhum -- so mostra
    // o placeholder ate AdsConsentManager liberar (ver MainActivity.onCreate).
    val canRequestAds by AdsConsentManager.canRequestAds
    if (!canRequestAds) {
        AdPlaceholder(modifier = modifier, compact = compact)
        return
    }

    val context = LocalContext.current
    // stringResource (nao context.getString) pra continuar reagindo a troca
    // de configuracao -- lint (LocalContextGetResourceValueCall) exige isso
    // dentro de Composable.
    val bannerTestId = stringResource(R.string.admob_banner_test)
    val bannerMenuId = stringResource(R.string.admob_banner_menu)
    val bannerLobbyId = stringResource(R.string.admob_banner_lobby)
    val bannerRankingId = stringResource(R.string.admob_banner_ranking)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 50.dp else 60.dp)
            .background(Color.Black.copy(alpha = 0.18f), MenuShapes.Card)
            .border(1.dp, Color.White.copy(alpha = 0.06f), MenuShapes.Card)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        val widthDp = maxWidth.value.roundToInt().coerceIn(320, 1200)
        val adUnitId = remember(context, placement, bannerTestId, bannerMenuId, bannerLobbyId, bannerRankingId) {
            val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) {
                bannerTestId
            } else {
                productionAdUnitFor(
                    placement,
                    bannerMenu = bannerMenuId,
                    bannerLobby = bannerLobbyId,
                    bannerRanking = bannerRankingId
                )
            }
        }
        val adView = remember(context, widthDp, adUnitId) {
            AdView(context).apply {
                setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp))
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        }

        DisposableEffect(adView) {
            onDispose { adView.destroy() }
        }

        AndroidView(
            factory = { adView },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// IDs recebidos por parametro (nao lidos direto de R.string aqui) pra essa
// funcao continuar testavel num JUnit puro, sem precisar de um Context
// Android de verdade -- ver SafeAdBannerSlotTest.
internal fun productionAdUnitFor(
    placement: AdPlacement,
    bannerMenu: String,
    bannerLobby: String,
    bannerRanking: String
): String {
    // Cada tela usa um bloco proprio para medir receita e estabilidade sem
    // misturar dados de menu, lobby e ranking no mesmo relatorio.
    return when (placement) {
        AdPlacement.MAIN_MENU -> bannerMenu
        AdPlacement.LOBBY -> bannerLobby
        AdPlacement.RANKING -> bannerRanking
        AdPlacement.RULES -> bannerLobby
        AdPlacement.ROUND_SUMMARY -> bannerRanking
    }
}

@Composable
private fun AdPlaceholder(
    modifier: Modifier,
    compact: Boolean
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 34.dp else 44.dp)
            .background(Color.Black.copy(alpha = 0.28f), MenuShapes.Card)
            .border(1.dp, Color.White.copy(alpha = 0.08f), MenuShapes.Card)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "PUBLICIDADE",
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = "Espaço reservado fora da mesa",
            color = Color.White.copy(alpha = 0.46f),
            fontSize = if (compact) 10.sp else 11.sp,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}
