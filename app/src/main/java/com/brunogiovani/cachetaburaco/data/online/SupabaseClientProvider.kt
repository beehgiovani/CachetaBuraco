package com.brunogiovani.cachetaburaco.data.online

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Cliente Supabase unico do app.
 *
 * Ele instala Auth, Postgrest e Realtime, mas ainda nao faz chamadas sozinho.
 * Os repositorios decidem quando usar cada modulo, mantendo regra de jogo fora
 * da camada de infraestrutura.
 */
object SupabaseClientProvider {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseProjectConfig.URL,
            supabaseKey = SupabaseProjectConfig.PUBLISHABLE_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
