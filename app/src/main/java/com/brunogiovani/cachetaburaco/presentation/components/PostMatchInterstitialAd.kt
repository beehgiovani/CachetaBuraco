package com.brunogiovani.cachetaburaco.presentation.components

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

private const val PROD_INTERSTITIAL_POST_MATCH = "ca-app-pub-9473501958357317/2339884534"
private const val TEST_INTERSTITIAL_ANDROID = "ca-app-pub-3940256099942544/1033173712"

/**
 * Intersticial de pos-partida. So deve ser chamado quando uma partida inteira
 * termina (isMatchOver) e o jogador esta saindo da mesa — nunca no meio de uma
 * rodada ou decisao de jogo, para nao violar o plano de monetizacao.
 */
object PostMatchInterstitialAd {
    @Volatile
    private var cachedAd: InterstitialAd? = null

    @Volatile
    private var isLoading = false

    fun preload(context: Context) {
        if (cachedAd != null || isLoading) return
        if (!AdsConsentManager.canRequestAds.value) return
        isLoading = true
        InterstitialAd.load(
            context.applicationContext,
            adUnitIdFor(context),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    cachedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    cachedAd = null
                }
            }
        )
    }

    /** Mostra o intersticial se ja estiver carregado; senao apenas recarrega para a proxima vez. */
    fun showIfReady(activity: Activity) {
        val ad = cachedAd
        if (ad == null) {
            preload(activity)
            return
        }
        cachedAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preload(activity)
            }
        }
        ad.show(activity)
    }

    private fun adUnitIdFor(context: Context): String {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (isDebuggable) TEST_INTERSTITIAL_ANDROID else PROD_INTERSTITIAL_POST_MATCH
    }
}
