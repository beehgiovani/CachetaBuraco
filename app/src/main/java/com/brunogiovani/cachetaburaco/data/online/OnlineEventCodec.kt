package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import org.json.JSONObject

/**
 * Conversao entre mensagem de mesa e payload persistido no online.
 *
 * Mantendo esse codec isolado, Wi-Fi local, bot e online continuam falando a
 * mesma lingua. O banco guarda envelope + payload, mas nao interpreta regra.
 */
object OnlineEventCodec {
    fun encode(message: NetworkMessage): String {
        return JSONObject()
            .put("senderId", message.senderId)
            .put("type", message.type)
            .put("payload", message.payload)
            .put("messageId", message.messageId)
            .apply { message.roundId?.let { put("roundId", it) } }
            .toString()
    }

    fun decode(raw: String): NetworkMessage? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val senderId = json.optString("senderId").takeIf { it.isNotBlank() } ?: return null
        val type = json.optString("type").takeIf { it.isNotBlank() } ?: return null
        val messageId = json.optString("messageId").takeIf { it.isNotBlank() } ?: return null
        return NetworkMessage(
            senderId = senderId,
            type = type,
            payload = json.optString("payload"),
            messageId = messageId,
            roundId = json.optString("roundId").takeIf { it.isNotBlank() }
        )
    }
}
