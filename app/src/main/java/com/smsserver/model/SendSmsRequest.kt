package com.smsserver.model

data class SendSmsRequest(
    val address: String,
    val body: String
)
