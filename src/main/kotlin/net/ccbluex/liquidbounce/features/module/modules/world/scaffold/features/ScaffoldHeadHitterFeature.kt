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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.ScaffoldNormalTechnique
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.entity.moving

object ScaffoldHeadHitterFeature : ToggleableConfigurable(ScaffoldNormalTechnique, "HeadHitter", false) {
    private val jumpDelay by intRange("JumpDelay", 0..0, 0..20, "ticks")
    private var jumpCooldown = 0

    fun canHeadHit() =
        run {
            val pos = player.blockPos.add(0, 2, 0)
            val state = pos.getState() ?: return@run false
            !state.getCollisionShape(world, pos).isEmpty!!
        } && player.isOnGround

    val repeatable = tickHandler {
        if (jumpCooldown > 0) {
            jumpCooldown--
            return@tickHandler
        }

        if (canHeadHit() && player.moving) {
            jumpCooldown = jumpDelay.random()
            player.jump()
        }
    }
}
