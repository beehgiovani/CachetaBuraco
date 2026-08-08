package com.brunogiovani.cachetaburaco.testutil

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Muda a escala de fonte do sistema antes da Activity ser lancada (igual
 * AppStatePreparationRule, precisa rodar como regra externa em relacao ao
 * createAndroidComposeRule) e sempre devolve pro valor original depois,
 * mesmo se o teste falhar -- e uma configuracao do aparelho de teste, nao
 * algo isolado por processo, entao vazar essa mudanca afetaria outros testes
 * ou o uso normal do aparelho depois.
 */
class FontScaleRule(private val scale: Float) : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val original = device.executeShellCommand("settings get system font_scale").trim()
                device.executeShellCommand("settings put system font_scale $scale")
                try {
                    base.evaluate()
                } finally {
                    val restoreValue = original.takeIf { it.isNotEmpty() && it != "null" } ?: "1.0"
                    device.executeShellCommand("settings put system font_scale $restoreValue")
                }
            }
        }
    }
}
