package com.brunogiovani.cachetaburaco.presentation.match

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchFeedbackTest {

    // Item pendente do roadmap: efeitos de jogada devem respeitar o modo
    // silencioso/vibrar do aparelho em vez de tocar som e vibrar sempre.

    @Test
    fun `normal ringer mode plays sound and vibrates`() {
        assertTrue(MatchFeedback.shouldPlaySound(AudioManager.RINGER_MODE_NORMAL))
        assertTrue(MatchFeedback.shouldVibrate(AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun `vibrate ringer mode mutes sound but keeps vibration`() {
        assertFalse(MatchFeedback.shouldPlaySound(AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(MatchFeedback.shouldVibrate(AudioManager.RINGER_MODE_VIBRATE))
    }

    @Test
    fun `silent ringer mode mutes both sound and vibration`() {
        assertFalse(MatchFeedback.shouldPlaySound(AudioManager.RINGER_MODE_SILENT))
        assertFalse(MatchFeedback.shouldVibrate(AudioManager.RINGER_MODE_SILENT))
    }
}
