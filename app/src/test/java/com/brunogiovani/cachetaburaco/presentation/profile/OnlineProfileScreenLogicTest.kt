package com.brunogiovani.cachetaburaco.presentation.profile

import com.brunogiovani.cachetaburaco.data.online.GoogleLinkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineProfileScreenLogicTest {

    @Test
    fun `successful google link shows confirmation mentioning protected profile`() {
        val message = googleLinkFeedbackFor(GoogleLinkResult.Success(displayName = "Bruno"))
        assertEquals(
            "Conta Google vinculada! Seu perfil fica protegido mesmo se trocar de aparelho.",
            message
        )
    }

    @Test
    fun `cancelled google link stays silent instead of showing an error`() {
        // Cancelar o seletor de conta e uma acao normal do usuario, nao uma
        // falha -- nao deve assustar com uma mensagem de erro.
        assertNull(googleLinkFeedbackFor(GoogleLinkResult.Cancelled))
    }

    @Test
    fun `no google account on device shows a specific actionable message`() {
        val message = googleLinkFeedbackFor(GoogleLinkResult.NoGoogleAccountOnDevice)
        assertEquals("Nenhuma conta Google encontrada neste aparelho.", message)
    }

    @Test
    fun `real failure shows a generic retry message without leaking exception details`() {
        val message = googleLinkFeedbackFor(GoogleLinkResult.Failed("SocketTimeoutException: timeout"))
        assertEquals("Não foi possível vincular a conta Google agora. Tente novamente.", message)
    }
}
