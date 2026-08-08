package com.brunogiovani.cachetaburaco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brunogiovani.cachetaburaco.testutil.AppStatePreparationRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Cobre uma partida basica de verdade contra a maquina: entrar no lobby,
 * concluir a configuracao, ver a mesa carregada e sair de volta pro menu.
 * E o unico modo que nao depende de rede local nem do Supabase, entao pode
 * ser exercitado de ponta a ponta com seguranca num teste instrumentado.
 */
@RunWith(AndroidJUnit4::class)
class BotMatchNavigationTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(AppStatePreparationRule.withSavedProfile())
        .around(composeTestRule)

    @Test
    fun jogoContraMaquina_iniciaPartidaMostraMesaEVoltaAoMenu() {
        composeTestRule.onNodeWithText("Jogar contra a máquina").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("hostPanelLazyColumn").fetchSemanticsNodes().isNotEmpty()
        }
        // "Concluir e jogar" fica depois de varias secoes de regra dentro de
        // uma LazyColumn, que so compoe o que esta visivel -- precisa rolar
        // pelo proprio container (nao pelo no do botao, que ainda nem existe
        // na arvore de semantica antes de ficar visivel).
        composeTestRule.onNodeWithTag("hostPanelLazyColumn")
            .performScrollToNode(hasText("Concluir e jogar"))
        composeTestRule.onNodeWithText("Concluir e jogar").performClick()

        // MatchScreen carregou de verdade: o botao "Sair" da barra superior
        // sempre aparece, independente de vez de quem esta jogando. Aparelho
        // fisico com SDK de anuncios de verdade pode levar mais tempo que o
        // emulador pra terminar a transicao.
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithText("Sair").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Sair").assertIsDisplayed()

        composeTestRule.onNodeWithText("Sair").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Criar sala local").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Criar sala local").assertIsDisplayed()
    }
}
