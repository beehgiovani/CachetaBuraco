package com.brunogiovani.cachetaburaco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brunogiovani.cachetaburaco.testutil.AppStatePreparationRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Cobre o primeiro trecho do grafo de navegacao real (MainActivity.AppState):
 * LOGIN -> MAIN_MENU, criando um perfil novo pela LoginScreen.
 *
 * Nao depende de rede/Supabase: FakeAuthRepository e 100% local (SharedPreferences).
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowNavigationTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    // A limpeza do perfil salvo precisa acontecer ANTES da Activity ser
    // lancada (MainActivity le o estado salvo em onCreate), por isso essa
    // regra fica como a mais externa da RuleChain.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(AppStatePreparationRule.clearSavedProfile())
        .around(composeTestRule)

    @Test
    fun semPerfilSalvo_abreNaTelaDeLogin_eCriarPerfilLevaAoMenuPrincipal() {
        // Sem perfil salvo, o app deve abrir direto no formulario "Criar perfil".
        composeTestRule.onNodeWithText("Criar perfil").assertIsDisplayed()

        composeTestRule.onNodeWithText("Apelido").performTextInput("Testador")
        composeTestRule.onNodeWithText("CRIAR PERFIL").performClick()

        // FakeAuthRepository.login tem um pequeno delay artificial (400ms) antes
        // de completar; esperamos ate a tela de menu aparecer de verdade.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule
                .onAllNodesWithText("Jogar contra a máquina")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Texto exclusivo do menu principal (nao existe na tela de login).
        composeTestRule.onNodeWithText("Criar sala local").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jogar contra a máquina").assertIsDisplayed()
    }
}
