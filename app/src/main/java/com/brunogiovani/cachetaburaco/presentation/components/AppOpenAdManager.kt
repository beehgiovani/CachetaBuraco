package com.brunogiovani.cachetaburaco.presentation.components

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.brunogiovani.cachetaburaco.R
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.lang.ref.WeakReference
import java.util.Date

/**
 * Anuncio de abertura do app (App Open Ads). So mostra quando o app volta
 * pro primeiro plano depois de ter ficado em segundo plano -- nunca na
 * primeira abertura fria (splash/login) nem com uma partida em andamento na
 * tela (MainActivity avisa via isMatchInProgress).
 */
object AppOpenAdManager : DefaultLifecycleObserver {
    // Recomendacao do proprio guia do Google: nao reusar um anuncio carregado
    // ha mais de 4h, a probabilidade de servir invalido sobe muito depois disso.
    private const val AD_MAX_CACHE_AGE_MS = 4 * 60 * 60 * 1000L

    @Volatile private var appOpenAd: AppOpenAd? = null
    @Volatile private var isLoadingAd = false
    @Volatile private var isShowingAd = false
    @Volatile private var loadTime: Long = 0L
    @Volatile private var currentActivity: WeakReference<Activity>? = null
    @Volatile private var isFirstForeground = true
    @Volatile var isMatchInProgress: Boolean = false

    private var observing = false

    /** Chamar uma vez em MainActivity.onCreate. */
    fun start(activity: Activity) {
        currentActivity = WeakReference(activity)
        if (!observing) {
            observing = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (isFirstForeground) {
            // A abertura fria ja tem a splash + tela de login/menu como
            // primeira impressao -- nao empilha anuncio em cima disso.
            isFirstForeground = false
            return
        }
        showAdIfAvailable()
    }

    fun loadAd(activity: Activity) {
        if (isLoadingAd || isAdAvailable() || !AdsConsentManager.canRequestAds.value) return
        isLoadingAd = true
        AppOpenAd.load(
            activity.applicationContext,
            adUnitIdFor(activity),
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                    loadTime = Date().time
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                }
            }
        )
    }

    private fun isAdAvailable(): Boolean {
        val ad = appOpenAd ?: return false
        return Date().time - loadTime < AD_MAX_CACHE_AGE_MS
    }

    private fun showAdIfAvailable() {
        if (isShowingAd || isMatchInProgress || !AdsConsentManager.canRequestAds.value) return
        val activity = currentActivity?.get() ?: return
        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            loadAd(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                isShowingAd = false
                loadAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
            }
        }
        ad.show(activity)
    }

    private fun adUnitIdFor(activity: Activity): String {
        val isDebuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return activity.getString(
            if (isDebuggable) R.string.admob_app_open_test else R.string.admob_app_open
        )
    }
}
