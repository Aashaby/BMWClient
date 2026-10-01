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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.integration.interop.onClientThreadAndWait
import net.ccbluex.liquidbounce.integration.interop.persistant.PersistentLocalStorage
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.netty.http.model.RequestObject
import net.ccbluex.netty.http.util.httpForbidden
import net.ccbluex.netty.http.util.httpInternalServerError
import net.ccbluex.netty.http.util.httpNoContent
import net.ccbluex.netty.http.util.httpOk

/**
 * LocalStorage RestAPI
 *
 * Allows to persist data across different browser.
 *
 * Since we cannot rely on the browser's localStorage
 * we have to implement our own. This is a simple key-value store.
 *
 * Especially because we have not enabled the CEF local storage
 */

// GET /api/v1/client/localStorage
fun getLocalStorage(requestObject: RequestObject) = with(requestObject) {
    val key = queryParams["key"] ?: return@with httpForbidden("No key")

    val value = runCatching { onClientThreadAndWait { PersistentLocalStorage[key] } }
        .getOrElse {
            logger.error("Failed to read $key", it)
            return@with httpInternalServerError("Failed to read $key")
        } ?: return@with httpForbidden("No value for key $key")

    httpOk(JsonObject().apply {
        addProperty("value", value)
    })
}

// PUT /api/v1/client/localStorage
fun putLocalStorage(requestObject: RequestObject) = with(requestObject) {
    val body = asJson<JsonObject>()
    val key = body["key"]?.asString ?: return@with httpForbidden("No key")
    val value = body["value"]?.asString ?: return@with httpForbidden("No value")

    runCatching {
        onClientThreadAndWait {
            PersistentLocalStorage[key] = value
            // The storage is otherwise only written on a clean shutdown
            ConfigSystem.store(PersistentLocalStorage)
        }
    }.map { httpNoContent() }.getOrElse {
        logger.error("Failed to store $key", it)
        httpInternalServerError("Failed to store $key")
    }
}

// DELETE /api/v1/client/localStorage
fun deleteLocalStorage(requestObject: RequestObject) = with(requestObject) {
    val key = queryParams["key"] ?: return@with httpForbidden("No key")

    runCatching {
        onClientThreadAndWait {
            PersistentLocalStorage.remove(key)
            ConfigSystem.store(PersistentLocalStorage)
        }
    }.map { httpNoContent() }.getOrElse {
        logger.error("Failed to remove $key", it)
        httpInternalServerError("Failed to remove $key")
    }
}

// GET /api/v1/client/localStorage/all
fun getAllLocalStorage(requestObject: RequestObject) = with(requestObject) {
    runCatching {
        onClientThreadAndWait {
            JsonObject().apply {
                val jsonArray = JsonArray()

                PersistentLocalStorage.forEach { (key, value) ->
                    jsonArray.add(JsonObject().apply {
                        addProperty("key", key)
                        addProperty("value", value)
                    })
                }

                add("items", jsonArray)
            }
        }
    }.map { httpOk(it) }.getOrElse {
        logger.error("Failed to read the persistent storage", it)
        httpInternalServerError("Failed to read the persistent storage")
    }
}

// PUT /api/v1/client/localStorage/all
fun putAllLocalStorage(requestObject: RequestObject) = with(requestObject) {
    data class Item(val key: String, val value: String)
    data class StoragePutRequest(val items: List<Item>)

    val body = asJson<StoragePutRequest>()

    runCatching {
        onClientThreadAndWait {
            PersistentLocalStorage.clear()
            body.items.forEach { item ->
                PersistentLocalStorage[item.key] = item.value
            }

            // The storage is otherwise only written on a clean shutdown
            ConfigSystem.store(PersistentLocalStorage)
        }
    }.map { httpNoContent() }.getOrElse {
        logger.error("Failed to store the persistent storage", it)
        httpInternalServerError("Failed to store the persistent storage")
    }
}
