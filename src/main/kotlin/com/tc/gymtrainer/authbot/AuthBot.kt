package com.tc.gymtrainer.authbot

import com.tc.gymtrainer.authbot.platform.*
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import com.typesafe.config.ConfigFactory
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.telegramBotWithBehaviourAndLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.textsources.CodeTextSource
import dev.inmo.tgbotapi.types.toChatId
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuthBot(private val platform: AuthPlatform, private val token: String) {
    suspend fun install(context: BehaviourContext) {
        platform.keepRegistered(context, requireNotNull(context.getMe().username).withoutAt)
        context.onCommand("start", requireOnlyCommandInMessage = false) { message ->
            val sender = (message as? FromUserMessage)?.from?.id ?: return@onCommand
            if (message.chat !is PrivateChat || message.chat.id != sender) return@onCommand
            val payload = message.content.text.substringAfter(' ', "").trim()
            start(this, sender.chatId.long, payload)
        }
    }

    private suspend fun start(bot: TelegramBot, sender: Long, payload: String) {
        if (payload.isNotEmpty() && payload != "login") {
            try {
                when (platform.confirmSignup(payload, sender)) {
                    SignupConfirmation.PENDING_APPROVAL -> bot.sendTextMessage(sender.toChatId(), statusMessage(platform.activateChat(sender)))
                    SignupConfirmation.LINKED -> Unit // The worker delivers the requested code.
                    SignupConfirmation.ALREADY_LINKED -> bot.sendTextMessage(sender.toChatId(), "Telegram уже привязан. Введите полученный код на сайте или нажмите «Запросить код повторно».")
                    SignupConfirmation.TELEGRAM_IN_USE -> bot.sendTextMessage(sender.toChatId(), "Этот аккаунт Telegram уже зарегистрирован. На сайте нажмите «Перейти ко входу» и введите Username существующего аккаунта.")
                }
            } catch (e: ServiceException) {
                bot.sendTextMessage(sender.toChatId(), e.message.orEmpty())
            }
            return
        }
        bot.sendTextMessage(sender.toChatId(), statusMessage(platform.activateChat(sender)))
    }

    suspend fun webhook(bot: TelegramBot, update: Update, username: String) {
        val message = (update as? MessageUpdate)?.data as? CommonMessage<*> ?: return
        val sender = (message as? FromUserMessage)?.from?.id ?: return
        if (message.chat !is PrivateChat || message.chat.id != sender) return
        val text = (message.content as? TextContent)?.text ?: return
        val command = text.substringBefore(' ')
        if (command != "/start" && !command.equals("/start@$username", true)) return
        val id = update.updateId.long
        val claim = platform.begin(sender.chatId.long, id)
        if (claim.status == "done") return
        check(claim.status == "claimed")
        start(bot, sender.chatId.long, text.substringAfter(' ', "").trim())
        platform.finish(sender.chatId.long, id, requireNotNull(claim.lease))
    }

    suspend fun drain(bot: TelegramBot): Long? {
        repeat(12) {
            val notified = deliverNextNotification(bot)
            val sent = deliverNext(bot)
            if (!sent && !notified) return platform.nextDelay()
        }
        return platform.nextDelay()
    }

    private fun statusMessage(status: ChatStatus): String = when {
        status.deactivated -> "Аккаунт деактивирован. Доступ к кабинету закрыт."
        status.pending -> listOfNotNull(
            "Telegram привязан. Заявка ожидает подтверждения администратора.",
            status.username?.let { "Логин для входа: $it" },
            "После одобрения сюда придёт код для первого входа."
        ).joinToString("\n\n")
        status.active -> "Бот для входа подключён. Запрошенный на сайте код придёт сюда."
        else -> "Откройте сайт и нажмите «Открыть бота авторизации» на странице регистрации."
    }

    suspend fun deliverNext(bot: TelegramBot): Boolean {
        val job = platform.nextDelivery() ?: return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(("training-collector/login/" + job.challenge).toByteArray(Charsets.UTF_8))
        val code = ((ByteBuffer.wrap(digest).long ushr 1) % 1_000_000).toString().padStart(6, '0')
        if (platform.prepareDelivery(job.challenge, code)) {
            val chat = job.telegramId.toChatId()
            if (!job.headingSent) {
                bot.sendTextMessage(chat, "Код для авторизации")
                platform.markHeadingSent(job.challenge)
            }
            bot.sendTextMessage(chat, entities = listOf(CodeTextSource(code)))
            platform.delivered(job.challenge)
        }
        return true
    }

    suspend fun deliverNextNotification(bot: TelegramBot): Boolean {
        val notice = platform.nextNotification() ?: return false
        val text = if (notice.active) "Аккаунт ${notice.username} активирован. Доступ к кабинету открыт."
            else "Аккаунт ${notice.username} деактивирован. Доступ к кабинету закрыт."
        try {
            bot.sendTextMessage(notice.telegramId.toChatId(), text)
        } catch (e: RequestException) {
            // A blocked bot cannot reach this chat. Do not schedule this notice forever.
            if (e.response.errorCode != 403) throw e
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

suspend fun pollAuthBot(token: String, platform: AuthPlatform): Unit = coroutineScope {
    val handler = AuthBot(platform, token)
    val (bot, polling) = telegramBotWithBehaviourAndLongPolling(token, scope = this) { handler.install(this) }
    val worker = launch {
        val log = LoggerFactory.getLogger(AuthBot::class.java)
        suspend fun attempt(label: String, deliver: suspend () -> Boolean): Boolean {
            try {
                return deliver()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Ошибка доставки {} ({}). Повтор будет выполнен позже.", label, e.javaClass.simpleName)
                return false
            }
        }
        while (isActive) {
            val notified = attempt("уведомления") { handler.deliverNextNotification(bot) }
            val sent = attempt("кода") { handler.deliverNext(bot) }
            if (!notified && !sent) delay(1000)
        }
    }
    try { polling.join() } finally { worker.cancelAndJoin() }
}

fun main() {
    val config = ConfigFactory.load("auth-bot").getConfig("app")
    val token = config.getString("authBot.token").trim()
    require(token.isNotEmpty()) { "Задайте AUTH_BOT_TOKEN." }
    val platform = AuthPlatform(config.getString("platform.url"), config.getString("platform.key"))
    KSLog.default = telegramSdkLogger(KSLog.default)
    when ((System.getenv("BOT_MODE") ?: "POLLING").uppercase()) {
        "POLLING" -> runBlocking { pollAuthBot(token, platform) }
        "WEBHOOK" -> {
            val bot = dev.inmo.tgbotapi.bot.ktor.telegramBot(token)
            val handler = AuthBot(platform, token)
            val username = requireNotNull(System.getenv("BOT_USERNAME")) { "Set BOT_USERNAME" }.removePrefix("@")
            webhookServer((System.getenv("PORT") ?: "8080").toInt(), System.getenv("TELEGRAM_WEBHOOK_SECRET").orEmpty(),
                { handler.webhook(bot, it, username) }, config.getString("platform.key"),
                { """{"delaySeconds":${handler.drain(bot)}}""" }).start()
        }
        else -> error("BOT_MODE must be POLLING or WEBHOOK")
    }
}
