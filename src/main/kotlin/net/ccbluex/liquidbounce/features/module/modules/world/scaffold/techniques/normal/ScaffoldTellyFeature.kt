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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.normal

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerAfterJumpEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.ScaffoldNormalTechnique
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.moving

/**
 * Telly feature
 *
 * This is based on the telly technique and means that the player will jump when moving.
 * That allows for a faster scaffold.
 * Depending on the SameY setting, we might scaffold upwards.
 *
 * @see ModuleScaffold
 */
object ScaffoldTellyFeature : ToggleableConfigurable(ScaffoldNormalTechnique, "Telly", false) {

    /**
     * Keeps the normal scaffold rotation out of the way during the straight
     * part of a telly jump. The old implementation also required
     * `ticksUntilJump >= jumpTicks`; that counter is reset immediately by
     * PlayerAfterJumpEvent, so the condition was effectively false during
     * the first air ticks where it mattered most.
     */
    val doNotAim: Boolean
        get() = enabled &&
                player.airTicks in 1..straightTicks &&
                !(ModuleScaffold.isTowering && aimOnTower)

    /**
     * True while the Telly controller is in its pre-jump window.
     *
     * This deliberately remains a grounded-state signal: the jump is injected
     * from MovementInputEvent, and PlayerAfterJumpEvent resets the counter as
     * soon as the jump is accepted.
     */
    val isTellyBridging: Boolean
        get() = enabled && player.moving && player.isOnGround && ticksUntilJump >= jumpTicks

    private var ticksUntilJump = 0

    val resetMode by enumChoice("ResetMode", Mode.RESET)
    private val straightTicks by int("Straight", 0, 0..5, "ticks")
    private val jumpTicksOpt by intRange("Jump", 0..0, 0..10, "ticks")
    private val aimOnTower by boolean("AimOnTower", true)
    private var jumpTicks = jumpTicksOpt.random()

    @Suppress("unused")
    private val gameHandler = handler<GameTickEvent> {
        if (!enabled || !player.moving || ModuleScaffold.blockCount <= 0) {
            // A stop/start of movement must begin a fresh jump window. Without
            // this reset a partially completed delay can carry into the next
            // sprint and make Telly feel randomly slow.
            ticksUntilJump = 0
            return@handler
        }

        if (player.isOnGround) {
            ticksUntilJump = (ticksUntilJump + 1).coerceAtMost(jumpTicks.coerceAtLeast(1))
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (!enabled || !player.moving || ModuleScaffold.blockCount <= 0 || !player.isOnGround) {
            return@handler
        }

        // Jump scheduling happens while grounded. The rotation suppression window
        // starts only after the jump (airTicks >= 1), so it must not be reused here.
        when (resetMode) {
            Mode.REVERSE -> event.jump = true
            // A zero delay means jump on the first eligible input tick. Keep
            // that path explicit so the default fast Telly profile does not
            // depend on GameTick/MovementInput event ordering.
            Mode.RESET -> if (jumpTicks <= 0 || ticksUntilJump >= jumpTicks) event.jump = true
        }
    }

    @Suppress("unused")
    private val afterJumpHandler = handler<PlayerAfterJumpEvent> {
        ticksUntilJump = 0
        jumpTicks = jumpTicksOpt.random()
    }

    enum class Mode(override val choiceName: String) : NamedChoice {
        REVERSE("Reverse"),
        RESET("Reset")
    }

}
