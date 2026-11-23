package com.tc.gymtrainer.authbot

import dev.inmo.kslog.common.KSLog
import dev.inmo.micro_utils.coroutines.launchLogging
import dev.inmo.micro_utils.coroutines.runCatchingLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class TelegramSdkLoggerTest {
    @Test
    fun `normal SDK job cancellation is quiet and failures stay visible`() = runBlocking {
        val logged = mutableListOf<Throwable?>()
        val logger = telegramSdkLogger(KSLog { _, _, _, cause -> logged.add(cause) })
        val started = CompletableDeferred<Unit>()
        val subscription = launchLogging(logger = logger) {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        subscription.cancelAndJoin()
        assertEquals(emptyList<Throwable?>(), logged)

        val timeout = requireNotNull(runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull())
        val failure = IOException("Telegram unavailable")
        val failures = listOf(timeout, failure, CancellationException("Parent failed", failure))
        for (cause in failures) {
            Unit.runCatchingLogging(logger = logger) { throw cause }
        }
        assertEquals(failures, logged)
    }
}
