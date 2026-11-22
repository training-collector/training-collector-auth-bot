package com.tc.gymtrainer.authbot.platform

import kotlinx.coroutines.CoroutineScope

enum class SignupConfirmation { LINKED, ALREADY_LINKED, TELEGRAM_IN_USE }
data class CodeDelivery(val challenge: String, val telegramId: Long, val headingSent: Boolean)

class AuthPlatform(url: String, key: String) {
    private val http = PlatformHttp(url, key)

    suspend fun keepRegistered(scope: CoroutineScope, username: String) = http.keepRegistered(scope, username)

    suspend fun confirmSignup(token: String, sender: Long) =
        http.call<Map<String, SignupConfirmation>>("POST", "/signup", mapOf("token" to token, "sender" to sender)).getValue("status")

    suspend fun activateChat(sender: Long) =
        http.call<Map<String, Boolean>>("POST", "/chats/$sender/activate").getValue("active")

    suspend fun nextDelivery() = http.call<CodeDelivery?>("POST", "/deliveries/next")

    suspend fun prepareDelivery(challenge: String, code: String) =
        http.call<Map<String, Boolean>>("POST", "/deliveries/prepare", mapOf("challenge" to challenge, "code" to code)).getValue("ready")

    suspend fun markHeadingSent(challenge: String) =
        http.call<Unit>("POST", "/deliveries/heading", mapOf("challenge" to challenge))

    suspend fun delivered(challenge: String) =
        http.call<Unit>("POST", "/deliveries/complete", mapOf("challenge" to challenge))
}
