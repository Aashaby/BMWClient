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

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.Choice
import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.allowsCriticalHit
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack
import net.ccbluex.liquidbounce.features.module.modules.bmw.grimvelocity.ModuleGrimVelocity
import net.ccbluex.liquidbounce.utils.client.PacketQueueManager
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.input.InputTracker.wasPressedRecently
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.modes
import net.ccbluex.liquidbounce.utils.client.MovePacketType
import net.minecraft.entity.LivingEntity
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.Direction

/**
 * Sneak criticals mode, also known as "平地刀爆" (flat ground crit).
 *
 * The trick is a chain of inputs:
 *
 *  1. You attack an entity, which is remembered as a click timestamp.
 *  2. If the jump key is pressed within [clickSpan] after that click, sneaking starts
 *     after [sneakDelay].
 *  3. While actually crouching and not on ground a tiny height difference is created, so that the
 *     server sees a fall distance greater than zero while you are off ground. By default this is
 *     done by reporting a [packetMomentum] lower position right before the attack
 *     ([MomentumSource.PACKET]), which a movement simulation cannot notice; [MomentumSource.CLIENT]
 *     forces the vertical velocity to [momentum] instead, which is visible in game.
 *  4. Releasing the jump key stops the sequence again, which also allows sprinting to resume.
 *     While the trick is active sprinting is suppressed, because a critical hit requires the
 *     attacker not to sprint. With [sneakTimeout] the crouch is also released once the attacks
 *     stopped.
 *
 * In short: hold your clicker and the jump key while approaching an enemy.
 */
object CriticalsSneak : Choice("Sneak") {

    override val parent: ChoiceConfigurable<*>
        get() = modes

    private val clickSpan by float("ClickSpan", 0.3f, 0.05f..1f, "s")
    private val sneakDelay by float("SneakDelay", 0.1f, 0f..1f, "s")
    /**
     * Where the tiny height difference comes from.
     *
     * [MomentumSource.PACKET] only tells the server that the player is a millionth of a block lower
     * right before the attack, while the client itself keeps moving vanilla. That is the same
     * method as the "Grim" packet mode of this module and survives a movement simulation.
     *
     * [MomentumSource.CLIENT] forces the vertical velocity instead, which is visible in game and
     * therefore only meant for servers without a movement simulation.
     */
    private val momentumSource by enumChoice("MomentumSource", MomentumSource.PACKET)

    private val momentum by float("Momentum", -0.1f, -1f..0f, "b/t")
    private val momentumMode by enumChoice("MomentumMode", MomentumMode.SET)

    /**
     * A critical hit requires the attacker not to be sprinting while sneaking does not cancel
     * sprinting, so sprinting is forced off while the trick is active. This makes the client send
     * the matching stop-sprint packet on its own.
     */
    private val stopSprinting by boolean("StopSprinting", true)

    /**
     * Releases the crouch again when no attack happened for that long, 0 keeps crouching until the
     * jump key is released.
     */
    private val sneakTimeout by float("SneakTimeout", 0f, 0f..2f, "s")

    /**
     * Request one normal client jump after the first eligible ground attack. The
     * request is deliberately not repeated while the same jump cycle is active.
     */
    private val autoJump by boolean("AutoJump", true)

    /**
     * Timestamp of the last attack on an entity, 0 if there was none yet.
     */
    private var lastAttackAt = 0L

    /**
     * Timestamp at which sneaking should begin, 0 while nothing is scheduled.
     */
    private var sneakAt = 0L

    /**
     * Whether the module currently forces the player to sneak.
     */
    private var sneaking = false
    private var autoJumpPending = false
    private var autoSequenceUntil = 0L
    private var autoJumpWasAirborne = false
    private var momentumSentThisAirborne = false

    /**
     * One millionth of a block below the current position: enough for the server to see a fall
     * distance greater than zero, but too small for a movement simulation to notice. The "Grim"
     * packet mode of this module uses the same offset.
     */
    private val packetMomentum = 0.000001

    override fun enable() = reset()

    override fun disable() = reset()

    private fun reset() {
        lastAttackAt = 0L
        sneakAt = 0L
        sneaking = false
        autoJumpPending = false
        autoSequenceUntil = 0L
        autoJumpWasAirborne = false
        momentumSentThisAirborne = false
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
            autoJumpPending.not() &&
            hasAttackIntent() &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !ModuleGrimVelocity.shouldStopBacktrack

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (event.entity !is LivingEntity) {
            return@handler
        }

        lastAttackAt = System.currentTimeMillis()

        // The jump key is already held, so the sneak can be scheduled right away. Otherwise the
        // tick handler takes care of it as soon as the jump key is pressed.
        if (sneakAt == 0L && !sneaking && (mc.options.jumpKey.isPressed || autoSequenceUntil > lastAttackAt)) {
            sneakAt = lastAttackAt + (sneakDelay * 1000f).toLong()
        }

        // Give the server the tiny fall distance it wants right before the attack packet leaves,
        // which happens right after this event was called.
        if (momentumSource == MomentumSource.PACKET &&
            sneaking &&
            !player.isOnGround &&
            player.fallDistance > 0f &&
            player.velocity.y <= 0.0 &&
            !momentumSentThisAirborne &&
            player.getAttackCooldownProgress(0.5f) > 0.9f &&
            allowsCriticalHit(true) &&
            !ModuleScaffold.running &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !ModuleGrimVelocity.shouldStopBacktrack
        ) {
            sendPacketMomentum()
            momentumSentThisAirborne = true
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val now = System.currentTimeMillis()

        // Releasing the jump key stops the trick.
        val automaticSequence = autoSequenceUntil > now
        if (!mc.options.jumpKey.isPressed && !automaticSequence) {
            sneakAt = 0L
            sneaking = false
            autoJumpPending = false
            return@handler
        }

        // Jump key held within ClickSpan after the click → start sneaking after SneakDelay.
        if (sneakAt == 0L && !sneaking && lastAttackAt != 0L &&
            now - lastAttackAt <= (clickSpan * 1000f).toLong()
        ) {
            sneakAt = now + (sneakDelay * 1000f).toLong()
        }

        if (sneakAt != 0L && now >= sneakAt) {
            sneaking = true
        }

        // Optional: release the crouch again when the attacks stopped, so the player does not keep
        // crouching forever just because the jump key is still held.
        if (sneaking && sneakTimeout > 0f && now - lastAttackAt > (sneakTimeout * 1000f).toLong()) {
            sneakAt = 0L
            sneaking = false
        }

        if (!player.isOnGround) {
            autoJumpWasAirborne = true
        } else if (autoJumpWasAirborne) {
            autoJumpWasAirborne = false
            autoJumpPending = false
            autoSequenceUntil = 0L
            sneakAt = 0L
            sneaking = false
            momentumSentThisAirborne = false
        }

        // Crouched and off ground → force the small downwards momentum. Only in states in which a
        // critical hit can happen at all, so flying, vehicles, water, ladders, ... are not affected.
        if (momentumSource == MomentumSource.CLIENT &&
            sneaking &&
            player.isSneaking &&
            !player.isOnGround &&
            player.fallDistance > 0f &&
            player.velocity.y <= 0.0 &&
            allowsCriticalHit(true) &&
            !ModuleScaffold.running &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !ModuleGrimVelocity.shouldStopBacktrack
        ) {
            val velocity = player.velocity

            player.velocity = when (momentumMode) {
                // Keep falling with a constant speed, which is the "tiny height difference"
                // the trick is based on.
                MomentumMode.SET -> velocity.withAxis(Direction.Axis.Y, momentum.toDouble())
                // Add up on top of the current velocity, resulting in an accelerating fall.
                MomentumMode.ADD -> velocity.add(0.0, momentum.toDouble(), 0.0)
            }
        }
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        if (shouldPrepareAutoJump()) {
            autoJumpPending = true
            autoSequenceUntil = System.currentTimeMillis() + (clickSpan * 1000f).toLong()
            if (sneakAt == 0L) {
                sneakAt = System.currentTimeMillis() + (sneakDelay * 1000f).toLong()
            }
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

        if (sneaking) {
            event.sneak = true
        }
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (stopSprinting && sneaking &&
            (event.source == SprintEvent.Source.MOVEMENT_TICK || event.source == SprintEvent.Source.INPUT)
        ) {
            event.sprint = false
        }
    }

    /**
     * Sends a movement packet which is [packetMomentum] below the current position. The client
     * position itself is not touched, so the next regular movement packet is back to vanilla.
     */
    private fun sendPacketMomentum() {
        network.sendPacket(MovePacketType.FULL.generatePacket().apply {
            this.y -= packetMomentum
            this.onGround = false
        })
    }

    enum class MomentumSource(override val choiceName: String) : NamedChoice {

        PACKET("Packet"),
        CLIENT("Client"),
    }

    enum class MomentumMode(override val choiceName: String) : NamedChoice {

        SET("Set"),
        ADD("Add"),
    }

}
