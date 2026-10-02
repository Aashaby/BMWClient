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
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack
import net.ccbluex.liquidbounce.features.module.modules.bmw.grimvelocity.ModuleGrimVelocity
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleFreeze
import net.ccbluex.liquidbounce.utils.client.PacketQueueManager
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.input.InputTracker.wasPressedRecently
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket
import net.minecraft.util.hit.EntityHitResult

@Suppress("unused")
object CriticalsGrim : Choice("Grim") {

    override val parent: ChoiceConfigurable<*>
        get() = ModuleCriticals.modes

    /**
     * How many player ticks are frozen after a hit, 0 disables the freeze (and the interact packet
     * which covers it). The interact packet is sent right after the last frozen tick.
     */
    private val freezeTicks by int("FreezeTicks", 0, 0..10, "ticks")
    private val autoJump by boolean("AutoJump", true)

    private var prevFallDistance = 0f
    private var isFalling = false
    private var freezeTicksLeft = 0
    private var sprintRestoreTick = 0
    private var sprintShouldRestore = false
    private var hasTriggered = false
    private var autoJumpPending = false
    private var autoJumpUntil = 0L
    private var autoJumpWasAirborne = false

    override fun enable() {
        prevFallDistance = player.fallDistance
        isFalling = false
        freezeTicksLeft = 0
        sprintRestoreTick = 0
        sprintShouldRestore = false
        hasTriggered = false
        autoJumpPending = false
        autoJumpUntil = 0L
        autoJumpWasAirborne = false
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
        autoJumpPending = false
        autoJumpUntil = 0L
        autoJumpWasAirborne = false
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val currentFallDistance = player.fallDistance

        if (!player.isOnGround && player.velocity.y <= 0.0 &&
            currentFallDistance >= prevFallDistance && currentFallDistance > 0f
        ) {
            isFalling = true
        }

        if (currentFallDistance <= 0) {
            isFalling = false
            hasTriggered = false
        }

        prevFallDistance = currentFallDistance

        if (!player.isOnGround) {
            autoJumpWasAirborne = true
        } else if (autoJumpWasAirborne) {
            autoJumpWasAirborne = false
            autoJumpPending = false
            autoJumpUntil = 0L
        }

        if (freezeTicksLeft > 0) {
            freezeTicksLeft--
            if (freezeTicksLeft == 0 && freezeTicks > 0) {
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

    private fun hasAttackIntent(): Boolean {
        val hasTarget = mc.crosshairTarget is EntityHitResult ||
            (ModuleKillAura.running && ModuleKillAura.targetTracker.target != null)

        return hasTarget && (
            mc.options.attackKey.isPressedOnAny ||
                mc.options.attackKey.wasPressedRecently(150) ||
                (ModuleKillAura.running && ModuleKillAura.targetTracker.target != null) ||
                (ModuleAutoClicker.running && ModuleAutoClicker.attack)
            )
    }

    private fun shouldPrepareAutoJump(): Boolean =
        autoJump &&
            player.isOnGround &&
            player.hurtTime == 0 &&
            player.getAttackCooldownProgress(0.5f) > 0.9f &&
            !ModuleScaffold.running &&
            !ModuleScaffold.isTowering &&
            !autoJumpWasAirborne &&
            !autoJumpPending &&
            hasAttackIntent() &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !ModuleGrimVelocity.shouldStopBacktrack

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        if (player.isSprinting && player.lastSprinting && isFalling && !hasTriggered) {
            hasTriggered = true
            sendSprintPacket(false)
            player.lastSprinting = false
            sprintShouldRestore = true
            sprintRestoreTick = 1
            // Scaffold owns movement while bridging/void-saving; do not freeze its tick here.
            freezeTicksLeft = if (ModuleScaffold.running) 0 else freezeTicks
        }

    }

    @Suppress("unused")
    private val movementInputEventHandler = handler<MovementInputEvent> { event ->
        val now = System.currentTimeMillis()
        if (autoJumpPending && now > autoJumpUntil) {
            autoJumpPending = false
        }

        if (shouldPrepareAutoJump()) {
            autoJumpPending = true
            autoJumpUntil = now + 300L
        }

        if (autoJumpPending &&
            hasAttackIntent() &&
            player.isOnGround &&
            !ModuleScaffold.running &&
            !ModuleScaffold.isTowering &&
            player.hurtTime == 0 &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !ModuleGrimVelocity.shouldStopBacktrack
        ) {
            event.jump = true
            autoJumpPending = false
            autoJumpWasAirborne = false
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
        if (mc.player?.isSprinting == true && mc.networkHandler != null && !player.lastSprinting) {
            sendSprintPacket(true)
            player.lastSprinting = true
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
