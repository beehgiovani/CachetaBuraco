package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.EarnedMedal
import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import com.brunogiovani.cachetaburaco.domain.models.OnlineProfile

interface OnlineProfileRepository {
    suspend fun loadProfile(playerName: String): OnlineProfile
    suspend fun updateAvatar(playerName: String, avatar: OnlineAvatar): OnlineProfile
    suspend fun loadMedals(playerName: String): List<EarnedMedal>
}
