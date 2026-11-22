package com.tc.gymtrainer.authbot

import com.tc.gymtrainer.authbot.platform.*
import com.typesafe.config.ConfigFactory
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
            if (payload.isNotEmpty() && payload != "login") {
                try {
                    when (platform.confirmSignup(payload, sender.chatId.long)) {
                        SignupConfirmation.LINKED -> Unit // The worker delivers the requested code.
                        SignupConfirmation.ALREADY_LINKED -> sendTextMessage(message.chat, "Telegram уже привязан. Введите полученный код на сайте или нажмите «Запросить код повторно».")
                        SignupConfirmation.TELEGRAM_IN_USE -> sendTextMessage(message.chat, "Этот аккаунт Telegram уже зарегистрирован. На сайте нажмите «Перейти ко входу» и введите Username существующего аккаунта.")
                    }
                } catch (e: ServiceException) {
                    sendTextMessage(message.chat, e.message.orEmpty())
                }
                return@onCommand
            }
            val ready = platform.activateChat(sender.chatId.long)
            sendTextMessage(message.chat, if (ready) "Бот для входа подключён. Запрошенный на сайте код придёт сюда."
                else "Откройте сайт и нажмите «Открыть бота авторизации» на странице регистрации.")
        }
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
}

suspend fun pollAuthBot(token: String, platform: AuthPlatform): Unit = coroutineScope {
    val handler = AuthBot(platform, token)
    val (bot, polling) = telegramBotWithBehaviourAndLongPolling(token, scope = this) { handler.install(this) }
    val worker = launch {
        val log = LoggerFactory.getLogger(AuthBot::class.java)
        while (isActive) {
            try {
                if (!handler.deliverNext(bot)) delay(1000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Ошибка доставки кода ({}). Повтор будет выполнен позже.", e.javaClass.simpleName)
                delay(1500)
            }
        }
    }
    try { polling.join() } finally { worker.cancelAndJoin() }
}

fun main() {
    val config = ConfigFactory.load("auth-bot").getConfig("app")
    val token = config.getString("authBot.token").trim()
    require(token.isNotEmpty()) { "Задайте AUTH_BOT_TOKEN." }
    val platform = AuthPlatform(config.getString("platform.url"), config.getString("platform.key"))
    runBlocking { pollAuthBot(token, platform) }
}
