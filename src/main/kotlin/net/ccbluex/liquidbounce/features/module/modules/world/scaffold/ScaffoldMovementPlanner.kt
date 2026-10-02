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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold

import net.ccbluex.fastutil.objectHashSetOf
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.client.fastCos
import net.ccbluex.liquidbounce.utils.client.fastSin
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.toRadians
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.getMovementDirectionOfInput
import net.ccbluex.liquidbounce.utils.math.geometry.Line
import net.ccbluex.liquidbounce.utils.math.times
import net.ccbluex.liquidbounce.utils.math.toBlockPos
import net.ccbluex.liquidbounce.utils.math.toVec3d
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.*

object ScaffoldMovementPlanner {
    private const val MAX_LAST_PLACE_BLOCKS: Int = 4
    private const val DIRECTION_HYSTERESIS_DEGREES = 30.0F
    private val offsetsToTry = doubleArrayOf(0.301, 0.0, -0.301)

    private val lastPlacedBlocks = ArrayDeque<BlockPos>(MAX_LAST_PLACE_BLOCKS)
    private var lastPosition: BlockPos? = null
    private var lastDirectionAngle = Float.NaN

    /**
     * When using scaffold the player wants to follow the line and the scaffold should support them in doing so.
     * This function calculates this ideal line that the player should move on.
     */
    fun getOptimalMovementLine(directionalInput: DirectionalInput): Line? {
        val direction =
            chooseDirection(
                getMovementDirectionOfInput(
                    RotationManager.currentRotation?.yaw ?: player.yaw,
                    directionalInput,
                ),
            )

        // Is this a good way to find the block center?
        val blockUnderPlayer = findBlockPlayerStandsOn() ?: return null

        val lastBlocksLine = fitLinesThroughLastPlacedBlocks()

        // If it makes sense to follow the last placed blocks, we lay the movement line through them, otherwise, we
        // don't consider them because the user probably wants to do something new
        val lineBaseBlock = if (lastBlocksLine != null && !divergesTooMuchFromDirection(lastBlocksLine, direction)) {
            lastBlocksLine.position
        } else {
            blockUnderPlayer.toVec3d()
        }

        // We try to make the player run on this line
        val optimalLine = Line(Vec3d(lineBaseBlock.x + 0.5, player.pos.y, lineBaseBlock.z + 0.5), direction)

        // Debug optimal line
        ModuleScaffold.debugGeometry("optimalLine") {
            ModuleDebug.DebuggedLine(optimalLine, if (lastBlocksLine == null) Color4b.RED else Color4b.GREEN)
        }

        return optimalLine
    }

    private fun divergesTooMuchFromDirection(lastBlocksLine: Line, direction: Vec3d): Boolean {
        val dot = lastBlocksLine.direction.dotProduct(direction).coerceIn(-1.0, 1.0)
        return acos(dot).absoluteValue / Math.PI * 180 > 50.0
    }

    /**
     * Tries to fit a line that goes through the last placed blocks. Currently only considers the last two.
     */
    private fun fitLinesThroughLastPlacedBlocks(): Line? {
        // Take the last 2 blocks placed
        if (lastPlacedBlocks.size < 2) {
            return null
        }
        val last = lastPlacedBlocks.last()
        val secondToLast = lastPlacedBlocks[lastPlacedBlocks.size - 2]

        // Just debug stuff
        if (ModuleDebug.running) {
            debugLastPlacedBlocks(listOf(secondToLast, last))
        }

        val delta = last.subtract(secondToLast)

        // Duplicate placements can happen when a placement callback is retried.
        // Normalizing a zero vector produces an invalid direction and can poison
        // the movement line with NaN values. Fall back to the normal heuristic.
        if (delta == BlockPos.ORIGIN) {
            return null
        }

        val avgPos = secondToLast.add(last).toVec3d() * 0.5
        val dir = delta.toVec3d().normalize()

        // Calculate the average direction of the last placed blocks
        return Line(avgPos, dir)
    }

    private fun debugLastPlacedBlocks(lastPlacedBlocksToConsider: List<BlockPos>) {
        lastPlacedBlocksToConsider.forEachIndexed { idx, pos ->
            val alpha = ((1.0 - idx.toDouble() / lastPlacedBlocksToConsider.size.toDouble()) * 255.0).toInt()

            ModuleScaffold.debugGeometry("lastPlacedBlock$idx") {
                ModuleDebug.DebuggedBox(Box(pos), Color4b(alpha, alpha, 255, 127))
            }
        }
    }

    /**
     * Find the block the player stands on.
     * It considers all blocks which the player's hitbox collides with and chooses one. If the player stands on the last
     * block this function returned, this block is preferred.
     */
    private fun findBlockPlayerStandsOn(): BlockPos? {
        // Contains the blocks which the player is currently supported by
        val candidates = objectHashSetOf<BlockPos>()

        for (xOffset in offsetsToTry) {
            for (zOffset in offsetsToTry) {
                val playerPos = player.pos.toBlockPos(xOffset, -1.0, zOffset)

                val isEmpty = playerPos.getState()?.getCollisionShape(world, playerPos)?.isEmpty ?: true

                if (!isEmpty) {
                    candidates.add(playerPos)
                }
            }
        }

        // We want to keep the direction of the scaffold
        this.lastPlacedBlocks.lastOrNull()?.let { lastPlacedBlock ->
            if (lastPlacedBlock in candidates) {
                return lastPlacedBlock
            }
        }

        // Stabilize the heuristic
        if (lastPosition in candidates) {
            return lastPosition
        }

        // We have no reason to prefer a candidate so just pick any.
        // Do not keep a stale support block across a gap/world transition.
        return candidates.firstOrNull().also { lastPosition = it }
    }

    /**
     * The player can move in a lot of directions. But there are only 8 directions which make sense for scaffold to
     * follow (NORTH, NORTH_EAST, EAST, etc.). This function chooses such a direction based on the current angle.
     * i.e. if we were looking like 30° to the right, we would choose the direction NORTH_EAST (1.0, 0.0, 1.0).
     * And scaffold would move diagonally to the right.
     */
    private fun chooseDirection(currentAngle: Float): Vec3d {
        // Keep the previous snapped direction while the input remains close to it. This avoids
        // rapid east/southeast (etc.) oscillation when the mouse sits near an 8-way boundary.
        if (!lastDirectionAngle.isNaN() &&
            MathHelper.wrapDegrees(currentAngle - lastDirectionAngle).absoluteValue <= DIRECTION_HYSTERESIS_DEGREES
        ) {
            val angle = lastDirectionAngle.toRadians()
            return Vec3d(angle.fastCos().toDouble(), 0.0, angle.fastSin().toDouble())
        }

        // Transform the angle ([-180; 180]) to [0; 8]
        val currentDirection = currentAngle / 180.0F * 4 + 4

        // Round the angle to the nearest integer, which represents the direction.
        val newDirectionNumber = round(currentDirection)
        // Do this transformation backwards, and we have an angle that follows one of the 8 directions.
        val newDirectionAngle = MathHelper.wrapDegrees((newDirectionNumber - 4) / 4.0F * 180.0F + 90.0F)
        lastDirectionAngle = newDirectionAngle

        val angle = newDirectionAngle.toRadians()
        return Vec3d(angle.fastCos().toDouble(), 0.0, angle.fastSin().toDouble())
    }

    /**
     * Remembers the last placed blocks and removes old ones.
     */
    fun trackPlacedBlock(placedBlock: BlockPos) {

        // Keep retries from creating two identical consecutive samples. Apart
        // from avoiding a degenerate fitted line this also keeps the movement
        // planner stable during high-speed placement bursts.
        if (lastPlacedBlocks.lastOrNull() == placedBlock) {
            return
        }

        lastPlacedBlocks.add(placedBlock)

        while (lastPlacedBlocks.size > MAX_LAST_PLACE_BLOCKS)
            lastPlacedBlocks.removeFirst()
    }

    fun reset() {
        lastPosition = null
        lastDirectionAngle = Float.NaN
        this.lastPlacedBlocks.clear()
    }
}
