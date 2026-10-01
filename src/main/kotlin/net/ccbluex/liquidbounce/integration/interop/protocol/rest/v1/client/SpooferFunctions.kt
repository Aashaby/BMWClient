/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
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
 *
 *
 */

package net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client

import io.netty.handler.codec.http.FullHttpResponse
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.interopGson
import net.ccbluex.liquidbounce.features.spoofer.SpooferManager
import net.ccbluex.liquidbounce.integration.interop.onClientThreadAndWait
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.netty.http.model.RequestObject
import net.ccbluex.netty.http.util.httpInternalServerError
import net.ccbluex.netty.http.util.httpNoContent
import net.ccbluex.netty.http.util.httpOk

@Suppress("UNUSED_PARAMETER")
fun getSpooferConfigurable(request: RequestObject): FullHttpResponse = runCatching {
    // Serialize MultiplayerConfigurable to JSON
    onClientThreadAndWait { ConfigSystem.serializeConfigurable(SpooferManager, gson = interopGson) }
}.map { httpOk(it) }.getOrElse {
    logger.error("Failed to get spoofer settings", it)
    httpInternalServerError("Failed to get spoofer settings")
}

fun putSpooferConfigurable(request: RequestObject): FullHttpResponse = try {
    onClientThreadAndWait {
        ConfigSystem.deserializeConfigurable(SpooferManager, request.body.reader())
        ConfigSystem.store(SpooferManager)
    }

    httpNoContent()
} catch (throwable: Throwable) {
    logger.error("Failed to update spoofer settings", throwable)
    httpInternalServerError("Failed to update spoofer settings")
}
