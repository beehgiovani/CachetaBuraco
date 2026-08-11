package com.brunogiovani.cachetaburaco.presentation.components

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.brunogiovani.cachetaburaco.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

private const val PROD_NATIVE_ROOM_LIST = "ca-app-pub-9473501958357317/5456307931"
private const val TEST_NATIVE_ADVANCED = "ca-app-pub-3940256099942544/2247696110"

/**
 * Card de anuncio nativo avancado misturado na lista de "Encontrar sala
 * online" (ClientPanel, LobbyScreen.kt). So pede o anuncio depois que o
 * consentimento (UMP) resolve -- ver AdsConsentManager -- e some sozinho se
 * o carregamento falhar, sem deixar buraco na lista.
 */
@Composable
fun NativeAdRoomCard(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) return

    val canRequestAds by AdsConsentManager.canRequestAds
    if (!canRequestAds) return

    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(context) {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val adUnitId = if (isDebuggable) TEST_NATIVE_ADVANCED else PROD_NATIVE_ROOM_LIST

        val adLoader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                nativeAd?.destroy()
                nativeAd = ad
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    nativeAd = null
                }
            })
            .build()
        adLoader.loadAd(AdRequest.Builder().build())

        onDispose {
            nativeAd?.destroy()
            nativeAd = null
        }
    }

    val ad = nativeAd ?: return

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.native_ad_room_card, null) as NativeAdView
        },
        update = { adView ->
            val headlineView = adView.findViewById<TextView>(R.id.native_ad_headline)
            val bodyView = adView.findViewById<TextView>(R.id.native_ad_body)
            val iconView = adView.findViewById<ImageView>(R.id.native_ad_icon)
            val ctaView = adView.findViewById<Button>(R.id.native_ad_cta)
            val mediaView = adView.findViewById<MediaView>(R.id.native_ad_media)

            headlineView.text = ad.headline
            adView.headlineView = headlineView

            bodyView.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
            bodyView.text = ad.body
            adView.bodyView = bodyView

            val icon = ad.icon
            iconView.visibility = if (icon == null) View.GONE else View.VISIBLE
            icon?.let { iconView.setImageDrawable(it.drawable) }
            adView.iconView = iconView

            ctaView.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
            ctaView.text = ad.callToAction
            adView.callToActionView = ctaView

            val media = ad.mediaContent
            if (media != null) {
                mediaView.visibility = View.VISIBLE
                mediaView.mediaContent = media
                adView.mediaView = mediaView
            } else {
                mediaView.visibility = View.GONE
            }

            adView.setNativeAd(ad)
        }
    )
}
