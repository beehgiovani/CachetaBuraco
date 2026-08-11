package com.brunogiovani.cachetaburaco.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Reune o consentimento (UMP) antes de inicializar o SDK do AdMob.
 *
 * Sem isso o app carregava anuncio no primeiro frame, sem checar se o
 * usuario esta na EEA/UK/Suica e exige o formulario de consentimento --
 * violacao direta da politica do AdMob (e do Google Play, que audita isso).
 * Nenhum outro lugar do app deve chamar MobileAds.initialize diretamente.
 */
object AdsConsentManager {

    private val _canRequestAds = mutableStateOf(false)
    val canRequestAds: State<Boolean> get() = _canRequestAds

    fun gatherConsent(activity: ComponentActivity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // Erro aqui so significa que o formulario nao pode ser mostrado agora;
                    // canRequestAds() abaixo continua sendo a fonte da verdade.
                    initializeIfAllowed(activity, consentInformation)
                }
            },
            {
                // Sem rede ou falha ao buscar o status de consentimento. Nao inicializo
                // anuncio agora; o app tenta de novo na proxima abertura.
            }
        )

        // Sala de espera: se o consentimento ja tinha sido dado numa sessao anterior,
        // requestConsentInfoUpdate as vezes resolve rapido demais pro callback acima
        // ainda nao ter rodado quando a tela principal aparece.
        initializeIfAllowed(activity, consentInformation)
    }

    private fun initializeIfAllowed(activity: ComponentActivity, info: ConsentInformation) {
        if (info.canRequestAds() && !_canRequestAds.value) {
            _canRequestAds.value = true
            MobileAds.initialize(activity.applicationContext) {}
        }
    }
}
