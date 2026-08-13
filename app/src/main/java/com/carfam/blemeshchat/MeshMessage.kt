package com.carfam.blemeshchat

import java.util.UUID

data class MeshMessage(
    val id: String,
    val senderId: String,
    var ttl: Int,
    val timestamp: Long,
    val text: String
) {
    fun toWire(): ByteArray = "$id|$senderId|$ttl|$timestamp|$text".toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_TTL = 6

        fun create(senderId: String, text: String): MeshMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            ttl = DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            text = text
        )

        fun fromWire(bytes: ByteArray): MeshMessage? {
            return try {
                val str = String(bytes, Charsets.UTF_8)
                val parts = str.split("|", limit = 5)
                if (parts.size != 5) return null
                MeshMessage(
                    id = parts[0],
                    senderId = parts[1],
                    ttl = parts[2].toInt(),
                    timestamp = parts[3].toLong(),
                    text = parts[4]
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
