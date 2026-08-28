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
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.utils.entity.isCloseToEdge
import net.ccbluex.liquidbounce.utils.math.*
import net.ccbluex.liquidbounce.utils.math.geometry.Line
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.movement.findEdgeCollision
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2

object ScaffoldMovementPrediction : ToggleableConfigurable(ModuleScaffold, "Prediction", true) {

    private val lastPlacementOffsets = ArrayDeque<Vec3d>()

    private const val MAX_PLACEMENT_OFFSETS = 4

    /** Distance to stay behind the detected edge while prediction history warms up. */
    private val bootstrapBackoff by float("BootstrapBackoff", 0.2f, 0.0f..0.4f)

    /** Disable future-position prediction this close to the edge. */
    private val predictionCutoffDistance by float("PredictionCutoffDistance", 0.05f, 0.0f..0.3f)

    /** Number of successful placements used to warm up history-based prediction. */
    private val warmupPlacements by int("WarmupPlacements", 2, 0..MAX_PLACEMENT_OFFSETS)

    fun reset() {
        lastPlacementOffsets.clear()
    }

    override fun onDisabled() {
        reset()
        super.onDisabled()
    }

    fun onPlace(optimalLine: Line?, lastFallOffPosition: Vec3d?) {
        if (optimalLine == null || !this.enabled) {
            return
        }

        val fallOffPoint = lastFallOffPosition ?: return

        val direction = optimalLine.direction
        if (direction.lengthSquared() < 1.0E-8) {
            return
        }

        val lineDirAngle = atan2(direction.z, direction.x).toFloat()

        val unrotatedOffset = (player.pos - fallOffPoint).rotateY(lineDirAngle)

        lastPlacementOffsets.addLast(unrotatedOffset)

        if (lastPlacementOffsets.size > MAX_PLACEMENT_OFFSETS) {
            lastPlacementOffsets.removeFirst()
        }
    }

    fun getAvgPlacementPos(): Vec3d? {
        if (lastPlacementOffsets.isEmpty()) {
            return null
        }

        return lastPlacementOffsets.average()
    }

    /**
     * Calculates where the player will stand when he places the block. Useful for rotations
     *
     * @return the predicted pos or `null` if the prediction failed
     */
    fun getPredictedPlacementPos(optimalLine: Line?): Vec3d? {
        if (optimalLine == null || !this.enabled) {
            return null
        }

        // When we are close to the edge, we can place immediately. Do not
        // predict a future position in that case.
        if (player.isCloseToEdge(DirectionalInput(player.input), distance = predictionCutoffDistance.toDouble())) {
            return null
        }

        // If the next placement point is far in the future. Don't predict for now
        val fallOffPoint = getFallOffPositionOnLine(optimalLine) ?: return null

        val fallOffPointToPlayer = fallOffPoint - player.pos

        val bootstrapPos = if (bootstrapBackoff <= 0.0f) {
            fallOffPoint
        } else {
            fallOffPoint - fallOffPointToPlayer.normalize() * bootstrapBackoff.toDouble()
        }

        val last = getAvgPlacementPos() ?: return bootstrapPos

        val direction = optimalLine.direction
        if (direction.lengthSquared() < 1.0E-8) {
            return bootstrapPos
        }

        val lineDirAngle = atan2(direction.z, direction.x).toFloat()
        val predictedPos = fallOffPoint + last.rotateY(-lineDirAngle)

        // Avoid a large jump from the bootstrap estimate to the first history
        // sample. Blend in history over the first few placements.
        val blend = if (warmupPlacements <= 0) {
            1.0
        } else {
            (lastPlacementOffsets.size.toDouble() / warmupPlacements.toDouble()).coerceIn(0.0, 1.0)
        }

        return bootstrapPos.lerp(predictedPos, blend)
    }

    fun getFallOffPositionOnLine(optimalLine: Line): Vec3d? {
        // TODO Check if the player is moving away from the line and implement another prediction method for that case

        val nearestPosToPlayer = optimalLine.getNearestPointTo(player.pos)

        val fromLine = nearestPosToPlayer.add(0.0, -0.1, 0.0)
        val toLine = fromLine + optimalLine.direction.normalize().multiply(3.0)

        val edgeCollision = findEdgeCollision(fromLine, toLine) ?: return null

        val fallOffPoint = edgeCollision.copy(y = player.y)

        return fallOffPoint
    }

}
