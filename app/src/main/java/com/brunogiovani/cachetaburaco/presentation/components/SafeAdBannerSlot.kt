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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlin.math.roundToInt

private const val PROD_BANNER_MENU = "ca-app-pub-9473501958357317/7461912378"
private const val PROD_BANNER_LOBBY = "ca-app-pub-9473501958357317/8583422353"
private const val PROD_BANNER_RANKING = "ca-app-pub-9473501958357317/2018014003"
private const val TEST_BANNER_ANDROID = "ca-app-pub-3940256099942544/9214589741"

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
        val adUnitId = remember(context, placement) {
            val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) TEST_BANNER_ANDROID else productionAdUnitFor(placement)
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

internal fun productionAdUnitFor(placement: AdPlacement): String {
    // Cada tela usa um bloco proprio para medir receita e estabilidade sem
    // misturar dados de menu, lobby e ranking no mesmo relatorio.
    return when (placement) {
        AdPlacement.MAIN_MENU -> PROD_BANNER_MENU
        AdPlacement.LOBBY -> PROD_BANNER_LOBBY
        AdPlacement.RANKING -> PROD_BANNER_RANKING
        AdPlacement.RULES -> PROD_BANNER_LOBBY
        AdPlacement.ROUND_SUMMARY -> PROD_BANNER_RANKING
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
