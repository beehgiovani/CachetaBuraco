package com.brunogiovani.cachetaburaco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brunogiovani.cachetaburaco.testutil.AppStatePreparationRule
import com.brunogiovani.cachetaburaco.testutil.FontScaleRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Confere que o app abre e navega normalmente com a fonte do sistema
 * ampliada (2.0x), a maior escala comum em acessibilidade Android. A escala
 * precisa mudar ANTES da Activity ser lancada (MainActivity le a
 * configuracao na criacao), por isso FontScaleRule entra como regra externa
 * a AppStatePreparationRule e ao createAndroidComposeRule, na mesma ordem.
 *
 * Auditoria anterior (roadmap secao 3) ja confirmou por leitura de codigo que
 * os componentes reagem a fontScale; este teste verifica isso de verdade num
 * aparelho, navegando ate o lobby (tela com mais texto e opcoes) e voltando.
 */
@RunWith(AndroidJUnit4::class)
class LargeFontScaleNavigationTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(FontScaleRule(scale = 2.0f))
        .around(AppStatePreparationRule.withSavedProfile())
        .around(composeTestRule)

    @Test
    fun fonteAmpliada_menuPrincipalENavegacaoParaLobbyContinuamLegiveis() {
        // A 2x a tela de menu nao cabe inteira sem rolar (MainMenuScreen usa
        // verticalScroll de proposito pra isso) -- "nao exibido" sem rolagem
        // aqui significaria so que esta fora da viewport, nao que sumiu ou
        // ficou inacessivel. performScrollTo() garante que o teste so falha
        // se o item realmente nao existir/nao puder ser alcancado.
        composeTestRule.onNodeWithText("Criar sala local").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Entrar em sala local").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Jogar contra a máquina").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Jogar contra a máquina").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Voltar").fetchSemanticsNodes().isNotEmpty()
        }
        // "Voltar" fica na barra de topo (sempre visivel, fora da area
        // rolavel) -- so o conteudo de regras abaixo dela esta dentro da
        // LazyColumn e precisa de performScrollToNode pra materializar.
        composeTestRule.onNodeWithText("Voltar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hostPanelLazyColumn")
            .performScrollToNode(hasText("Modo contra a máquina usa 2 jogadores (você x máquina)."))
        composeTestRule
            .onNodeWithText("Modo contra a máquina usa 2 jogadores (você x máquina).")
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Voltar").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Criar sala local").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Criar sala local").performScrollTo().assertIsDisplayed()
    }
}
