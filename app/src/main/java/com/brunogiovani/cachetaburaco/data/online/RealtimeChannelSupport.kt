package com.brunogiovani.cachetaburaco.data.online

import io.github.jan.supabase.realtime.RealtimeChannel
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

internal const val REALTIME_SUBSCRIBE_TIMEOUT_MS = 10_000L

/** Evita reutilizar no SDK um canal que ja foi cancelado em uma tela anterior. */
internal fun uniqueRealtimeTopic(prefix: String): String = "$prefix-${UUID.randomUUID()}"

internal suspend fun RealtimeChannel.subscribeWithin(
    timeoutMillis: Long = REALTIME_SUBSCRIBE_TIMEOUT_MS
): Boolean = withTimeoutOrNull(timeoutMillis) {
    subscribe(blockUntilSubscribed = true)
    true
} == true
