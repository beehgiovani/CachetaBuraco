package com.brunogiovani.cachetaburaco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brunogiovani.cachetaburaco.testutil.AppStatePreparationRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Cobre a navegacao MAIN_MENU -> LOBBY_BOT -> MAIN_MENU (AppState em
 * MainActivity), que e o fluxo de "jogar contra a maquina" - o unico modo de
 * partida que nao depende de rede local nem do Supabase, entao pode ser
 * exercitado com seguranca num teste instrumentado.
 *
 * Um perfil e pre-semeado no SharedPreferences antes da Activity abrir, para
 * pular a tela de login (que ja tem cobertura propria em
 * LoginFlowNavigationTest) e comecar direto no menu principal.
 */
@RunWith(AndroidJUnit4::class)
class MainMenuNavigationTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(AppStatePreparationRule.withSavedProfile())
        .around(composeTestRule)

    @Test
    fun perfilJaSalvo_abreDiretoNoMenu_eNavegaParaLobbyDaMaquinaEVolta() {
        // Com perfil salvo, FakeAuthRepository.hasSavedProfile() e true e
        // MainActivity abre direto em AppState.MAIN_MENU (sem passar por LOGIN).
        composeTestRule.onNodeWithText("Criar sala local").assertIsDisplayed()

        composeTestRule.onNodeWithText("Jogar contra a máquina").performClick()

        // Texto exclusivo da LobbyScreen (nao existe no menu principal).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("Voltar")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Voltar").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Modo contra a máquina usa 2 jogadores (você x máquina).")
            .assertIsDisplayed()

        // Botao "Voltar" da LobbyScreen chama onBack, que em MainActivity volta
        // para AppState.MAIN_MENU.
        composeTestRule.onNodeWithText("Voltar").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("Criar sala local")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Criar sala local").assertIsDisplayed()
        composeTestRule.onNodeWithText("Entrar em sala local").assertIsDisplayed()
    }
}
