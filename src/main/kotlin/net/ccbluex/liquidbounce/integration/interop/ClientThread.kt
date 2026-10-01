/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.integration.interop

import net.ccbluex.liquidbounce.utils.client.mc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * How long a request waits for the client thread before giving up. The client thread executes the
 * task within the next frame, so this is only reached while the game is stuck.
 */
private const val CLIENT_THREAD_TIMEOUT_MS = 3_000L

/**
 * Executes [block] on the client thread and waits until it has been executed.
 *
 * The interop REST API is served on Netty threads, while configuration is meant to be read and
 * written from the client thread only. Answering a request before the change was applied makes the
 * client state inconsistent with what the web theme sees: the theme re-reads the settings right
 * after every change, so it would show the old value again - and the config might not even be
 * saved yet. Waiting here also gives a proper happens-before relationship between a write and the
 * follow-up read of the same client.
 *
 * @throws IllegalStateException if the client thread did not execute [block] in time.
 */
internal fun <T> onClientThreadAndWait(block: () -> T): T {
    if (mc.isOnThread) {
        return block()
    }

    val future = CompletableFuture<T>()

    mc.execute {
        try {
            future.complete(block())
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }
    }

    return try {
        future.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (exception: TimeoutException) {
        throw IllegalStateException("Timed out while waiting for the client thread", exception)
    } catch (exception: ExecutionException) {
        throw exception.cause ?: exception
    }
}
