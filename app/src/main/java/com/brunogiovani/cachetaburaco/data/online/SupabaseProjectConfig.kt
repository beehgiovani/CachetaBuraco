package com.brunogiovani.cachetaburaco.data.online

/**
 * Configuracao publica do projeto Supabase.
 *
 * Esta publishable key pode ir no app Android. Secret key, senha do Postgres
 * e connection string ficam sempre fora do APK, em ambiente seguro ou no painel.
 */
object SupabaseProjectConfig {
    const val URL = "https://yvpbegrdepevppglbcbm.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_lZ5SgQZwpB5dHr_mNd2Ykw_9nLLJOz9"
    const val PROJECT_REF = "yvpbegrdepevppglbcbm"

    // Client ID do OAuth Client tipo "Web" no Google Cloud Console (projeto
    // cacheta-504906) -- e o mesmo cadastrado no provedor Google do Supabase
    // Auth. Nao e segredo (serve so pra identificar o app pro Google, o
    // client secret de verdade fica so no painel do Supabase); vai como
    // `serverClientId` no pedido do Credential Manager mesmo sendo um login
    // nativo Android, porque e esse ID que o Supabase valida como audiencia
    // do id_token.
    const val GOOGLE_WEB_CLIENT_ID = "762498671443-amqpakurdrmcil17b3iaq8r20otd19rc.apps.googleusercontent.com"
}
