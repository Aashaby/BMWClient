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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold.tower

import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold.isBlockBelow
import net.ccbluex.liquidbounce.utils.block.getCenterDistanceSquared
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.minecraft.util.math.BlockPos
import kotlin.math.hypot

object ScaffoldTowerHypixel : ScaffoldTower("Hypixel") {

    @Suppress("unused")
    private val tickHandler = tickHandler {
        if (!mc.options.jumpKey.isPressed || ModuleScaffold.blockCount <= 0) {
            return@tickHandler
        }

        // Hypixel's prediction tower is intended to be stationary. The upstream
        // implementation uses a fixed launch velocity and a deterministic
        // downward correction instead of the old air-tick pattern.
        if (hypot(player.velocity.x, player.velocity.z) > 0.01) {
            return@tickHandler
        }

        if (player.isOnGround) {
            player.velocity.y = 0.42
        }

        if (player.velocity.y <= 0.0 && player.velocity.y >= -0.09) {
            player.velocity.y = -0.38
        }
    }

    override fun getTargetedPosition(blockPos: BlockPos): BlockPos {
        if (!player.moving) {
            // Find the block closest to the player
            val blocks = arrayOf(
                blockPos.add(0, 0, 1),
                blockPos.add(0, 0, -1),
                blockPos.add(1, 0, 0),
                blockPos.add(-1, 0, 0)
            )

            val blockOffset = blocks.minByOrNull { blockPos ->
                blockPos.getCenterDistanceSquared()
            }?.add(0, -1, 0) ?: blockPos

            // Check if block next to the player is solid
            if (blockOffset.getState()?.isSolidBlock(world, blockOffset) != true) {
                return blockOffset
            }
        }

        return super.getTargetedPosition(blockPos)
    }


}
