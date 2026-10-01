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
 */

package net.ccbluex.liquidbounce.utils.input

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.util.InputUtil
import net.minecraft.util.ActionResult

/**
 * Translates a key name to an InputUtil.Key using GLFW key codes.
 * If the name is unrecognized, defaults to NONE.
 *
 * The input can be provided in the following formats:
 * - Full key name: "key.mouse.left", "key.keyboard.a", "key.keyboard.keypad.decimal"
 * - Abbreviated: "a" -> "key.keyboard.a", "left_shift" -> "key.keyboard.left.shift"
 *
 * @param name The key name as a string.
 * @return The corresponding InputUtil.Key object, or [InputUtil.UNKNOWN_KEY] if the name is unknown.
 */
fun inputByName(name: String): InputUtil.Key {
    if (name.equals("NONE", true)) {
        return InputUtil.UNKNOWN_KEY
    }

    val formattedName = name.replace('_', '.')
    val translationKey =
        when {
            formattedName.startsWith("key.mouse.", ignoreCase = true) ||
                formattedName.startsWith("key.keyboard.", ignoreCase = true) -> formattedName.lowercase()

            formattedName.startsWith("mouse.", ignoreCase = true) ||
                formattedName.startsWith("keyboard.", ignoreCase = true) -> "key.${formattedName.lowercase()}"

            else -> "key.keyboard.${formattedName.lowercase()}"
        }

    return try {
        InputUtil.fromTranslationKey(translationKey)
    } catch (_: IllegalArgumentException) {
        // Unnamed keys are looked up by their number, so a name that is neither known nor numeric
        // leaves the lookup throwing instead of reporting an unknown key.
        InputUtil.UNKNOWN_KEY
    }
}

/**
 * Checks whether this key is currently pressed.
 *
 * This extension property uses the current window handle to determine if
 * the key represented by this [InputUtil.Key] is being pressed.
 *
 * @return `true` if the key is pressed; otherwise, `false`.
 */
val InputUtil.Key.isPressed get() =
    InputUtil.isKeyPressed(mc.window.handle, this.code)

/**
 * Reduces a full key name (e.g., "key.keyboard.a") to its minimal form (e.g., "a").
 * This is useful for simplifying key names for easier recognition.
 *
 * @param translationKey The full key name as a string.
 * @return The reduced key name as a string.
 */
fun reduceInputName(translationKey: String): String =
    translationKey
        .removePrefix("key.")
        .removePrefix("keyboard.")

/**
 * Retrieves a set of reduced keyboard input names available in InputUtil.
 *
 * @return A set of simplified keyboard input names.
 */
val availableKeyboardKeys: Set<String>
    get() = InputUtil.Type.KEYSYM.map.values
        .map { key -> reduceInputName(key.translationKey) }
        .toSet()

/**
 * Retrieves a set of reduced keyboard input names available in InputUtil.
 *
 * @return A set of simplified keyboard input names.
 */
val availableMouseKeys: Set<String>
    get() = InputUtil.Type.KEYSYM.map.values
        .map { key -> reduceInputName(key.translationKey) }
        .toSet()

val availableInputKeys: Set<String> = availableKeyboardKeys + availableMouseKeys + "none"

fun ActionResult.shouldSwingHand() = this is ActionResult.Success && this.swingSource == ActionResult.SwingSource.CLIENT
