package com.carfam.blemeshchat

import java.util.UUID

data class MeshMessage(
    val id: String,
    val type: String,
    val senderId: String,
    var ttl: Int,
    val timestamp: Long,
    val targetId: String,
    val text: String
) {
    fun toWire(): ByteArray =
        "$type|$id|$senderId|$ttl|$timestamp|$targetId|$text".toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_TTL = 6
        const val TYPE_MSG = "MSG"
        const val TYPE_EDIT = "EDIT"
        const val TYPE_DELETE = "DELETE"

        fun createMessage(senderId: String, text: String): MeshMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            type = TYPE_MSG,
            senderId = senderId,
            ttl = DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            targetId = "",
            text = text
        )

        fun createEdit(senderId: String, targetId: String, newText: String): MeshMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            type = TYPE_EDIT,
            senderId = senderId,
            ttl = DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            targetId = targetId,
            text = newText
        )

        fun createDelete(senderId: String, targetId: String): MeshMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            type = TYPE_DELETE,
            senderId = senderId,
            ttl = DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            targetId = targetId,
            text = ""
        )

        fun fromWire(bytes: ByteArray): MeshMessage? {
            return try {
                val str = String(bytes, Charsets.UTF_8)
                val parts = str.split("|", limit = 7)
                if (parts.size != 7) return null
                MeshMessage(
                    type = parts[0],
                    id = parts[1],
                    senderId = parts[2],
                    ttl = parts[3].toInt(),
                    timestamp = parts[4].toLong(),
                    targetId = parts[5],
                    text = parts[6]
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
