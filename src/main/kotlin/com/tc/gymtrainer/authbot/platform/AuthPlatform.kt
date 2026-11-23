package com.tc.gymtrainer.authbot.platform

import kotlinx.coroutines.CoroutineScope

enum class SignupConfirmation { LINKED, PENDING_APPROVAL, ALREADY_LINKED, TELEGRAM_IN_USE }
data class CodeDelivery(val challenge: String, val telegramId: Long, val headingSent: Boolean)
data class AccessNotification(val id: Long, val telegramId: Long, val username: String, val active: Boolean)

data class ChatStatus(val active: Boolean, val pending: Boolean = false, val deactivated: Boolean = false, val username: String? = null)

class AuthPlatform(url: String, key: String) {
    private val http = PlatformHttp(url, key)

    suspend fun keepRegistered(scope: CoroutineScope, username: String) = http.keepRegistered(scope, username)

    suspend fun confirmSignup(token: String, sender: Long) =
        http.post<Map<String, SignupConfirmation>>("/signup", mapOf("token" to token, "sender" to sender)).getValue("status")

    suspend fun activateChat(sender: Long) =
        http.post<ChatStatus>("/chats/$sender/activate")

    suspend fun nextDelivery() = http.post<CodeDelivery?>("/deliveries/next")
    suspend fun nextNotification() = http.post<AccessNotification?>("/notifications/next")
    suspend fun acknowledgeNotification(id: Long) = http.post<Unit>("/notifications/$id/complete")

    suspend fun prepareDelivery(challenge: String, code: String) =
        http.post<Map<String, Boolean>>("/deliveries/prepare", mapOf("challenge" to challenge, "code" to code)).getValue("ready")

    suspend fun markHeadingSent(challenge: String) =
        http.post<Unit>("/deliveries/heading", mapOf("challenge" to challenge))

    suspend fun delivered(challenge: String) =
        http.post<Unit>("/deliveries/complete", mapOf("challenge" to challenge))
}
