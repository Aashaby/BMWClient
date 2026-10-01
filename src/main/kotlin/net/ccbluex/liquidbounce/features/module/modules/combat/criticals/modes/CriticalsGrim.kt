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

package net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes

import net.ccbluex.liquidbounce.config.types.nesting.Choice
import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleFreeze
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket

@Suppress("unused")
object CriticalsGrim : Choice("Grim") {

    override val parent: ChoiceConfigurable<*>
        get() = ModuleCriticals.modes

    /**
     * How many player ticks are frozen after a hit, 0 disables the freeze (and the interact packet
     * which covers it). The interact packet is sent right after the last frozen tick.
     */
    private val freezeTicks by int("FreezeTicks", 2, 0..10, "ticks")

    private var prevFallDistance = 0f
    private var isFalling = false
    private var freezeTicksLeft = 0
    private var sprintRestoreTick = 0
    private var sprintShouldRestore = false
    private var hasTriggered = false

    override fun enable() {
        prevFallDistance = player.fallDistance
        isFalling = false
        freezeTicksLeft = 0
        sprintRestoreTick = 0
        sprintShouldRestore = false
        hasTriggered = false
    }

    /**
     * Undo a sprint state that was not restored yet, otherwise the server would keep the player
     * marked as not sprinting while the client sprints again.
     */
    override fun disable() {
        if (sprintShouldRestore) {
            restoreSprint()
        }

        sprintShouldRestore = false
        sprintRestoreTick = 0
        freezeTicksLeft = 0
        hasTriggered = false
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val currentFallDistance = player.fallDistance

        if (currentFallDistance > prevFallDistance && currentFallDistance > 0) {
            isFalling = true
        }

        if (currentFallDistance <= 0) {
            isFalling = false
            hasTriggered = false
        }

        prevFallDistance = currentFallDistance

        if (freezeTicksLeft > 0) {
            freezeTicksLeft--
            if (freezeTicksLeft == 0) {
                ModuleFreeze.interact()
            }
        }

        if (sprintRestoreTick > 0) {
            sprintRestoreTick--
            if (sprintRestoreTick == 0 && sprintShouldRestore) {
                restoreSprint()
                sprintShouldRestore = false
            }
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        if (player.isSprinting && isFalling && !hasTriggered) {
            hasTriggered = true
            sendSprintPacket(false)
            sprintShouldRestore = true
            sprintRestoreTick = 1
            freezeTicksLeft = freezeTicks
        }
    }

    @Suppress("unused")
    private val playerTickEventHandler = handler<PlayerTickEvent> { event ->
        if (freezeTicksLeft > 0) {
            event.cancelEvent()
        }
    }

    /**
     * Tells the server to sprint again, but only while the client itself is sprinting. Otherwise
     * the server would simulate a sprint state the client is not in.
     */
    private fun restoreSprint() {
        if (mc.player?.isSprinting == true && mc.networkHandler != null) {
            sendSprintPacket(true)
        }
    }

    private fun sendSprintPacket(start: Boolean) {
        val mode = if (start) {
            ClientCommandC2SPacket.Mode.START_SPRINTING
        } else {
            ClientCommandC2SPacket.Mode.STOP_SPRINTING
        }
        network.sendPacket(ClientCommandC2SPacket(player, mode))
    }

}
