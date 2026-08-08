package com.brunogiovani.cachetaburaco.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Prepara o estado persistido do app (SharedPreferences) antes da Activity ser
 * lancada pelo ComposeTestRule.
 *
 * MainActivity decide a tela inicial (LOGIN vs MAIN_MENU) dentro de onCreate,
 * lendo o perfil salvo via FakeAuthRepository.init(). Se so limpassemos ou
 * preenchessemos esse estado num metodo @Before comum, a ordem de execucao
 * das JUnit rules faria a Activity ja ter lido o estado antigo antes do
 * @Before rodar. Por isso essa preparacao precisa acontecer dentro do
 * TestRule#apply, para poder ser encadeada como regra externa (RuleChain) em
 * relacao ao createAndroidComposeRule e garantir que ela roda ANTES do
 * lancamento da Activity.
 *
 * As chaves usadas aqui espelham as constantes privadas de
 * FakeAuthRepository (PREFS_NAME, KEY_PLAYER_ID, KEY_PLAYER_NAME). Se aquele
 * arquivo mudar os nomes das chaves, este arquivo precisa acompanhar.
 */
class AppStatePreparationRule(
    private val prepare: (Context) -> Unit
) : TestRule {

    companion object {
        const val PREFS_NAME = "cachetaburaco_prefs"
        const val KEY_PLAYER_ID = "player_id"
        const val KEY_PLAYER_NAME = "player_name"

        /** Nenhum perfil salvo -> app deve abrir na tela de login. */
        fun clearSavedProfile(): AppStatePreparationRule = AppStatePreparationRule { context ->
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        /** Perfil ja salvo -> app deve pular login e abrir direto no menu principal. */
        fun withSavedProfile(
            playerId: String = "test-player-id",
            playerName: String = "Jogador Teste"
        ): AppStatePreparationRule = AppStatePreparationRule { context ->
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putString(KEY_PLAYER_ID, playerId)
                .putString(KEY_PLAYER_NAME, playerName)
                .commit()
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                prepare(context)
                base.evaluate()
            }
        }
    }
}
