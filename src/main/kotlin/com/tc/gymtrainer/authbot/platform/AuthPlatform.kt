package com.tc.gymtrainer.authbot.platform

enum class SignupConfirmation { PENDING_APPROVAL, ALREADY_LINKED, TELEGRAM_IN_USE }

/** A sign-in request to put in front of the trainer. [number] is the one the site shows. */
data class LoginDelivery(val id: String, val telegramId: Long, val username: String, val number: Int, val browser: String?, val ip: String?)

enum class LoginDecision { APPROVED, DENIED, EXPIRED }

/** [kind] is ACTIVATED or DEACTIVATED. */
data class TrainerNotice(val id: Long, val telegramId: Long, val username: String, val kind: String)

data class ChatStatus(val active: Boolean, val pending: Boolean = false, val deactivated: Boolean = false, val username: String? = null)

class AuthPlatform(url: String, key: String, val settings: PlatformSettings = PlatformSettings()) {
    private val http = PlatformHttp(url, key, settings)

    suspend fun register(username: String) = http.post<Unit>("/identity", mapOf("username" to username))

    suspend fun confirmSignup(token: String, sender: Long) =
        http.post<Map<String, SignupConfirmation>>("/signup", mapOf("token" to token, "sender" to sender)).getValue("status")

    suspend fun activateChat(sender: Long) =
        http.post<ChatStatus>("/chats/$sender/activate")

    /** [attempt] counts deliveries of the same update. */
    data class Lease(val status: String, val lease: String?, val attempt: Int = 1)
    suspend fun begin(sender: Long, update: Long) = http.post<Lease>("/updates/$sender/$update/begin")
    suspend fun release(sender: Long, update: Long, lease: String) =
        http.post<Unit>("/updates/$sender/$update/release", mapOf("lease" to lease))
    suspend fun finish(sender: Long, update: Long, lease: String) =
        http.post<Unit>("/updates/$sender/$update/finish", mapOf("lease" to lease))

    suspend fun nextDelivery() = http.post<LoginDelivery?>("/deliveries/next")
    suspend fun nextNotification() = http.post<TrainerNotice?>("/notifications/next")
    suspend fun acknowledgeNotification(id: Long) = http.post<Unit>("/notifications/$id/complete")

    suspend fun delivered(id: String) = http.post<Unit>("/deliveries/complete", mapOf("id" to id))

    suspend fun decide(id: String, sender: Long, approve: Boolean) =
        http.post<Map<String, LoginDecision>>("/logins/$id/${if (approve) "approve" else "deny"}", mapOf("sender" to sender)).getValue("decision")
}
