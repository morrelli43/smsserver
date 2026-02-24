package com.smsserver.model

data class Conversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val timestamp: Long,
    val messageCount: Int,
    val unreadCount: Int,
    val hasMms: Boolean
)
