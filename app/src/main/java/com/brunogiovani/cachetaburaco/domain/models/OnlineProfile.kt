package com.brunogiovani.cachetaburaco.domain.models

enum class OnlineAvatar(val storageId: String) {
    EMERALD("builtin:emerald"),
    GOLD("builtin:gold"),
    RUBY("builtin:ruby"),
    SAPPHIRE("builtin:sapphire"),
    VIOLET("builtin:violet"),
    GRAPHITE("builtin:graphite");

    companion object {
        fun fromStorageId(value: String?): OnlineAvatar {
            return entries.firstOrNull { it.storageId == value } ?: EMERALD
        }
    }
}

data class OnlineProfile(
    val playerId: String,
    val playerName: String,
    val avatar: OnlineAvatar,
    val avatarPhotoUrl: String? = null,
    val isAnonymous: Boolean = true,
    val email: String? = null,
    val accountCreatedAt: String? = null
)
