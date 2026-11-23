package com.tc.gymtrainer.authbot

import com.tc.gymtrainer.authbot.platform.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.types.update.CallbackQueryUpdate
import dev.inmo.tgbotapi.utils.row
import dev.inmo.tgbotapi.requests.GetUpdates
import dev.inmo.tgbotapi.requests.webhook.DeleteWebhook
import dev.inmo.tgbotapi.types.UpdateId
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("AuthBot")

/** The Telegram SDK aborts any request after 25 s. A long poll must end before that. */
internal val MAX_POLL_TIMEOUT: Duration = Duration.ofSeconds(24)

/** A long poll that the HTTP client cut short. It is not an error: nothing arrived meanwhile. */
internal fun Throwable.isRequestTimeout() =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.any { it.javaClass.simpleName == "HttpRequestTimeoutException" }

/** Where an update failed. The message carries identifiers only. */
class UpdateFailure(message: String, cause: Throwable) : RuntimeException(message, cause)

private val LOGIN_BUTTON = Regex("login:(ok|no):([A-Za-z0-9_-]{22})")

/**
 * Links a trainer's Telegram account on /start, delivers what the platform has queued
 * (sign-in requests and account notices) and passes the trainer's answer to a request back.
 */
class AuthBot(private val platform: AuthPlatform, private val maxUpdateAttempts: Int = 3) {

    private suspend fun start(bot: TelegramBot, sender: Long, payload: String) {
        if (payload.isNotEmpty() && payload != "login") {
            try {
                when (platform.confirmSignup(payload, sender)) {
                    SignupConfirmation.PENDING_APPROVAL -> bot.sendTextMessage(sender.toChatId(), statusMessage(platform.activateChat(sender)))
                    SignupConfirmation.ALREADY_LINKED -> bot.sendTextMessage(sender.toChatId(), "Telegram уже привязан. Войдите на сайте и подтвердите вход в этом чате.")
                    SignupConfirmation.TELEGRAM_IN_USE -> bot.sendTextMessage(sender.toChatId(), "Этот аккаунт Telegram уже зарегистрирован. На сайте нажмите «Перейти ко входу» и введите Username существующего аккаунта.")
                }
            } catch (e: ServiceException) {
                bot.sendTextMessage(sender.toChatId(), e.message.orEmpty())
            }
            return
        }
        bot.sendTextMessage(sender.toChatId(), statusMessage(platform.activateChat(sender)))
    }

    /** Handles one update. The polling loop calls it for each update in order; tests call it directly. */
    suspend fun handle(bot: TelegramBot, update: Update, username: String) {
        when (update) {
            is MessageUpdate -> {
                val message = update.data as? CommonMessage<*> ?: return
                val sender = (message as? FromUserMessage)?.from?.id ?: return
                if (message.chat !is PrivateChat || message.chat.id != sender) return
                val text = (message.content as? TextContent)?.text ?: return
                val command = text.substringBefore(' ')
                if (command != "/start" && !command.equals("/start@$username", true)) return
                claimed(sender.chatId.long, update.updateId.long) { start(bot, sender.chatId.long, text.substringAfter(' ', "").trim()) }
            }
            is CallbackQueryUpdate -> {
                val query = update.data as? MessageDataCallbackQuery ?: return
                val button = LOGIN_BUTTON.matchEntire(query.data) ?: return
                val sender = query.from.id.chatId.long
                if (query.message.chat.id.chatId.long != sender) return
                claimed(sender, update.updateId.long) { answer(bot, query, button.groupValues[2], sender, button.groupValues[1] == "ok") }
            }
            else -> return
        }
    }

    /** Runs [action] once per update, even when Telegram delivers it again. */
    private suspend fun claimed(chat: Long, id: Long, action: suspend () -> Unit) {
        val claim = platform.begin(chat, id)
        if (claim.status == "done") return
        check(claim.status == "claimed")
        val lease = requireNotNull(claim.lease)
        try {
            // An update that keeps failing is acknowledged, so it cannot block the ones behind it.
            if (claim.attempt > maxUpdateAttempts) log.error("Update {} of chat {} failed {} times and is dropped", id, chat, claim.attempt - 1)
            else action()
            platform.finish(chat, id, lease)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { platform.release(chat, id, lease) }
            throw UpdateFailure("update=$id chat=$chat attempt=${claim.attempt}", e)
        }
    }

    /** Records the trainer's answer and replaces the buttons with the outcome. */
    private suspend fun answer(bot: TelegramBot, query: MessageDataCallbackQuery, id: String, sender: Long, approve: Boolean) {
        val decision = platform.decide(id, sender, approve)
        val outcome = when (decision) {
            LoginDecision.APPROVED -> "✅ Вход разрешён. Вернитесь на сайт: кабинет откроется сам."
            LoginDecision.DENIED -> "Вход отклонён. Без вашего подтверждения в кабинет войти нельзя."
            LoginDecision.EXPIRED -> "Запрос входа устарел. Запросите вход на сайте ещё раз."
        }
        ignoringStale { bot.answerCallbackQuery(query) }
        ignoringStale { bot.editMessageText(query.message.chat.id, query.message.messageId, outcome) }
    }

    /** A press on an old message: its spinner has expired or the text is already the outcome. */
    private suspend fun ignoringStale(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: RequestException) {
            val detail = e.response.description.orEmpty()
            val stale = listOf("query is too old", "query ID is invalid", "message is not modified").any { detail.contains(it, true) }
            if (e.response.errorCode != 400 || !stale) throw e
        }
    }

    private fun statusMessage(status: ChatStatus): String = when {
        status.deactivated -> "Аккаунт деактивирован. Доступ к кабинету закрыт."
        status.pending -> listOfNotNull(
            "Telegram привязан. Заявка ожидает подтверждения администратора.",
            status.username?.let { "Логин для входа: $it" },
            "После одобрения войдите на сайте и подтвердите вход в этом чате."
        ).joinToString("\n\n")
        status.active -> "Бот для входа подключён. Сюда придут запросы входа с сайта."
        else -> "Откройте сайт и нажмите «Открыть бота авторизации» на странице регистрации."
    }

    suspend fun deliverNext(bot: TelegramBot): Boolean {
        val job = platform.nextDelivery() ?: return false
        val details = listOfNotNull(job.browser?.let { "Браузер: $it" }, job.ip?.let { "IP: $it" }).joinToString("\n")
        val text = buildString {
            append("Вход в кабинет ${job.username}\n\nНа сайте должно быть число ${job.number}.")
            if (details.isNotEmpty()) append("\n$details")
            append("\n\nЕсли вы не входили или число другое, нажмите «Отклонить вход».")
        }
        bot.sendTextMessage(job.telegramId.toChatId(), text, replyMarkup = inlineKeyboard {
            row {
                dataButton("Разрешить вход", "login:ok:${job.id}")
                dataButton("Отклонить вход", "login:no:${job.id}")
            }
        })
        platform.delivered(job.id)
        return true
    }

    suspend fun deliverNextNotification(bot: TelegramBot): Boolean {
        val notice = platform.nextNotification() ?: return false
        val text = when (notice.kind) {
            "ACTIVATED" -> "Аккаунт ${notice.username} активирован. Войдите на сайте и подтвердите вход в этом чате."
            "DEACTIVATED" -> "Аккаунт ${notice.username} деактивирован. Доступ к кабинету закрыт."
            else -> null
        }
        try {
            if (text != null) bot.sendTextMessage(notice.telegramId.toChatId(), text)
        } catch (e: RequestException) {
            // A blocked bot or a deleted chat cannot be reached. Do not schedule this notice again.
            val unreachable = e.response.errorCode == 403 || (e.response.errorCode == 400 && e.response.description.orEmpty().contains("chat not found", true))
            if (!unreachable) throw e
        }
        platform.acknowledgeNotification(notice.id)
        return true
    }
}

internal fun telegramSdkLogger(delegate: KSLog): KSLog = KSLog { level, tag, message, cause ->
    // The SDK logs normal cancellation of update subscriptions as an error.
    val cancelledSubscription = level == LogLevel.ERROR && message == "Something web wrong" &&
        cause is CancellationException && generateSequence<Throwable>(cause) { it.cause }.all {
            it is CancellationException && it !is TimeoutCancellationException
        }
    if (!cancelledSubscription) delegate.performLog(level, tag, message, cause)
}

/**
 * Reads updates by long polling and, alongside, sends what the platform has queued:
 * sign-in requests and account notices, checked every [pollInterval].
 */
suspend fun pollAuthBot(
    telegram: TelegramBot, token: String, platform: AuthPlatform, handler: AuthBot = AuthBot(platform),
    pollInterval: Duration = Duration.ofSeconds(1), pollTimeout: Duration = Duration.ofSeconds(20),
): Unit = coroutineScope {
    suspend fun <T> untilDone(what: String, action: suspend () -> T): T {
        while (true) {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{} failed, retrying", what, safeFailure(e, token))
                delay(platform.settings.retryDelay.toMillis())
            }
        }
    }
    val username = untilDone("Reading the bot profile") { requireNotNull(telegram.getMe().username).withoutAt }
    untilDone("Registering the bot on the platform") { platform.register(username) }
    untilDone("Switching the bot to polling") { telegram.execute(DeleteWebhook()) }
    val worker = launch {
        while (isActive) {
            val worked = try {
                handler.deliverNextNotification(telegram) or handler.deliverNext(telegram)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Delivery failed and will be tried again", safeFailure(e, token))
                false
            }
            if (!worked) delay(pollInterval.toMillis())
        }
    }
    try {
        var offset: UpdateId? = null
        while (isActive) {
            val updates = untilDone("Reading updates") {
                try {
                    telegram.execute(GetUpdates(offset, timeout = pollTimeout.seconds.toInt(), allowed_updates = listOf("message", "callback_query")))
                } catch (e: Exception) {
                    if (e is CancellationException || !e.isRequestTimeout()) throw e
                    emptyList()
                }
            }
            for (update in updates) {
                untilDone("Handling an update") { handler.handle(telegram, update, username) }
                offset = UpdateId(update.updateId.long + 1)
            }
        }
    } finally {
        worker.cancelAndJoin()
    }
}

private fun positive(config: Config, path: String) = config.getDuration(path).also {
    require(!it.isNegative && !it.isZero) { "app.$path must be positive" }
}

fun main() {
    val config = ConfigFactory.load("auth-bot").getConfig("app")
    val token = config.getString("authBot.token").trim()
    require(token.isNotEmpty()) { "Задайте AUTH_BOT_TOKEN." }
    val platform = AuthPlatform(config.getString("platform.url"), config.getString("platform.key"), PlatformSettings(
        allowInsecure = config.getBoolean("platform.allowInsecure"),
        connectTimeout = positive(config, "platform.connectTimeout"),
        requestTimeout = positive(config, "platform.requestTimeout"),
        retryDelay = positive(config, "platform.retryDelay"),
    ))
    fun count(path: String) = config.getInt(path).also { require(it > 0) { "app.$path must be positive" } }
    val handler = AuthBot(platform, count("authBot.maxUpdateAttempts"))
    KSLog.default = telegramSdkLogger(KSLog.default)
    val pollTimeout = positive(config, "authBot.pollTimeout")
    require(pollTimeout <= MAX_POLL_TIMEOUT) { "app.authBot.pollTimeout must not exceed ${MAX_POLL_TIMEOUT.seconds}s" }
    runBlocking { pollAuthBot(telegramBot(token), token, platform, handler, positive(config, "authBot.pollInterval"), pollTimeout) }
}
