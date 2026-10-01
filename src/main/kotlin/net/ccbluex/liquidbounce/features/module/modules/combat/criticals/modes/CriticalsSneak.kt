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
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.allowsCriticalHit
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.modes
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Direction

/**
 * Sneak criticals mode, also known as "平地刀爆" (flat ground crit).
 *
 * The trick is a chain of inputs:
 *
 *  1. You attack an entity, which is remembered as a click timestamp.
 *  2. If the jump key is pressed within [clickSpan] after that click, sneaking starts
 *     after [sneakDelay].
 *  3. While sneaking and not on ground the vertical velocity is set to [momentum]. This
 *     creates the tiny height difference vanilla needs for a critical hit: the server sees
 *     a fall distance greater than zero while you are off ground.
 *  4. Releasing the jump key stops the sequence again, which also allows sprinting to resume.
 *     While the trick is active sprinting is suppressed, because a critical hit requires the
 *     attacker not to sprint.
 *
 * In short: hold your clicker and the jump key while approaching an enemy.
 */
object CriticalsSneak : Choice("Sneak") {

    override val parent: ChoiceConfigurable<*>
        get() = modes

    private val clickSpan by float("ClickSpan", 0.3f, 0.05f..1f, "s")
    private val sneakDelay by float("SneakDelay", 0.1f, 0f..1f, "s")
    private val momentum by float("Momentum", -0.1f, -1f..0f)
    private val momentumMode by enumChoice("MomentumMode", MomentumMode.SET)

    /**
     * A critical hit requires the attacker not to be sprinting while sneaking does not cancel
     * sprinting, so sprinting is forced off while the trick is active. This makes the client send
     * the matching stop-sprint packet on its own.
     */
    private val stopSprinting by boolean("StopSprinting", true)

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

    override fun enable() = reset()

    override fun disable() = reset()

    private fun reset() {
        lastAttackAt = 0L
        sneakAt = 0L
        sneaking = false
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (event.entity !is LivingEntity) {
            return@handler
        }

        lastAttackAt = System.currentTimeMillis()

        // The jump key is already held, so the sneak can be scheduled right away. Otherwise the
        // tick handler takes care of it as soon as the jump key is pressed.
        if (sneakAt == 0L && !sneaking && mc.options.jumpKey.isPressed) {
            sneakAt = lastAttackAt + (sneakDelay * 1000f).toLong()
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val now = System.currentTimeMillis()

        // Releasing the jump key stops the trick.
        if (!mc.options.jumpKey.isPressed) {
            sneakAt = 0L
            sneaking = false
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

        // Crouched and off ground → force the small downwards momentum. Only in states in which a
        // critical hit can happen at all, so flying, vehicles, water, ladders, ... are not affected.
        if (sneaking && !player.isOnGround && allowsCriticalHit(true)) {
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

    enum class MomentumMode(override val choiceName: String) : NamedChoice {

        SET("Set"),
        ADD("Add"),
    }

}
