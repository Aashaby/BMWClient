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

package net.ccbluex.liquidbounce.features.module.modules.bmw.newscaffold

import net.ccbluex.liquidbounce.bmw.notifyAsMessage
import net.ccbluex.liquidbounce.bmw.simulatePlayerMovement
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ScaffoldBlockItemSelection
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.toRadians
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.onGroundTicks
import net.ccbluex.liquidbounce.utils.item.isFullBlock
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.block.*
import net.minecraft.item.BlockItem
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 抄ssng的，抄不好，总是坠机。谁修好了找我获取10个软妹币
 */
@Suppress("unused")
object ModuleNewScaffold : ClientModule("NewScaffold", Category.BMW) {

    private data class BlockData(val pos: BlockPos, val facing: Direction)

    private enum class Mode(override val choiceName: String) : NamedChoice {
        TELLY("Telly"),
        SNAP("Snap"),
        NORMAL("Normal")
    }

    private enum class BlockSlotMode(override val choiceName: String) : NamedChoice {
        FARTHEST("Farthest"),
        MOST_BLOCKS("MostBlocks")
    }

    private enum class JumpMode(override val choiceName: String) : NamedChoice {
        NORMAL("Normal"),
        PARKOUR("Parkour"),
        NONE("None")
    }

    private val mode by enumChoice("Mode", Mode.TELLY)
    private val alwaysUpdateRot by boolean("AlwaysUpdateRotation", true)
    private val placeTick by int("PlaceTick", 1, 1..5, "ticks")
    private val rotTick by int("RotationTick", 1, 1..5, "ticks")
    private val noSwing by boolean("NoSwing", false)
    private val eagle by boolean("Eagle", false)
    private val snap by boolean("Snap", false)
    private val noUpTelly by boolean("NoUpTelly", false)
    private val smoothed by boolean("HeypixelUpTelly", true)
    private val safeMode by boolean("SafeMode", false)
    private val testOnGround by boolean("TestOnGround", false)
    private val randomSlow by boolean("SlowUpTelly", false)
    private val blockSlotMode by enumChoice("BlockSlotMode", BlockSlotMode.FARTHEST)
    private val jumpMode by enumChoice("JumpMode", JumpMode.NORMAL)
    private val safeDistance by float("ClutchSafeDistance", 4.5f, 1.0f..5.0f)
    private val tellyEagleTick by int("EagleTick", 1, 1..5)
    private val keepEagleSneakTick by int("KeepEagleTick", 1, 1..5)
    private val debug by boolean("Debug", false)
    private val duplicateRotPlace by boolean("DuplicateRotPlace", true)
    private val interactItem by boolean("InteractItemBeforePlace", false)
    private val silentSwitch by boolean("SilentSwitch", true)

    private var slot: SlotData? = null
    private var blockSlot: SlotData? = null
    private var canPlace = false
    private var blockData: BlockData? = null
    private var lastBlockData: BlockData? = null
    private var rotateCount = 0
    private var posY = 0.0
    private var lastPlacePosition: BlockPos? = null
    private var tellyJumpTicks = 0
    private var waitingForEagleSneak = false
    private var lastRotation: Rotation? = null
    private var rot: Rotation? = null
    private var boxExpand = 0.15
    private var oldSlot = 0
    private var placeCount = 0
    private var ups = 0
    private var lastPlacePitchDiff = 0.0

    // Velocity-aware candidate scoring
    private data class PlacementCandidate(
        val pos: BlockPos,
        val facing: Direction,
        val score: Double
    )

    override fun onEnabled() {
        placeCount = 0
        ups = 0
        boxExpand = (0.1..0.2).random()
        lastRotation = Rotation(player.yaw, player.pitch)
        slot = SlotData(player.inventory.selectedSlot, Hand.MAIN_HAND)
        oldSlot = player.inventory.selectedSlot
        blockSlot = null
        blockData = null
        canPlace = true
        lastPlacePosition = null
        tellyJumpTicks = 0
        waitingForEagleSneak = false
        rot = null
        // skipTick removed - dangerous mechanism eliminated
        lastPlacePitchDiff = 0.0
    }

    override fun onDisabled() {
        SilentHotbar.resetSlot(this)
        player.inventory.selectedSlot = oldSlot
        mc.options.sneakKey.isPressed = false
        blockSlot = null
        blockData = null
        lastBlockData = null
        // skipTick removed - dangerous mechanism eliminated
    }

    @Suppress("unused")
    private val playerTickHandler = handler<PlayerTickEvent> {
        // Removed dangerous skipTick mechanism that cancelled player ticks
        // This was causing unpredictable player behavior
    }

    @Suppress("unused")
    private val strafeHandler = handler<PlayerVelocityStrafe> {
        if (this.blockSlot == null || blockSlot!!.check()) {
            return@handler
        }
        if (player.onGroundTicks > (if (smoothed && safeMode && !testOnGround) 1 else 0)
            && !mc.options.jumpKey.isPressed
            && player.moving
            && mode == Mode.TELLY
        ) {
            when (jumpMode) {
                JumpMode.NONE -> {}
                JumpMode.NORMAL -> player.jump()
                JumpMode.PARKOUR -> {
                    val yaw = player.yaw.toRadians()
                    val forwardX = -sin(yaw)
                    val forwardZ = cos(yaw)

                    val frontPos1 = BlockPos(
                        (player.x + forwardX).toInt(),
                        (player.y - 0.1).toInt(),
                        (player.z + forwardZ).toInt()
                    )
                    val frontPos2 = BlockPos(
                        (player.x + forwardX * 2).toInt(),
                        (player.y - 0.1).toInt(),
                        (player.z + forwardZ * 2).toInt()
                    )

                    if (world.getBlockState(frontPos1).block is AirBlock ||
                        world.getBlockState(frontPos2).block is AirBlock
                    ) {
                        player.jump()
                    }
                }
            }


            if (eagle && mode == Mode.TELLY) {
                waitingForEagleSneak = true
                tellyJumpTicks = 0
            }
        }
    }

    private fun getBRot(forceRotation: Boolean): Rotation {
        var rotation: Rotation? = if (blockData != null) {
            RotationUtils.getClosestToBlockFace(
                blockData!!.pos,
                blockData!!.facing,
                RotationManager.serverRotation.yaw,
                RotationManager.serverRotation.pitch
            )
        } else null
        if (rotation == null) {
            rotation = if (RotationUtils.normalizeYawDiff(
                    player.yaw + 100f,
                    RotationManager.serverRotation.yaw
                ) < RotationUtils.normalizeYawDiff(
                    player.yaw - 100f, RotationManager.serverRotation.yaw
                )
            ) {
                Rotation(player.yaw + 100f, RotationManager.serverRotation.pitch)
            } else {
                Rotation(player.yaw - 100f, RotationManager.serverRotation.pitch)
            }
        }
        // Removed skipTick check - dangerous tick cancellation is no longer used
        val diff: Double = RotationUtils.yawDiffDirectly(rotation.yaw, RotationManager.serverRotation.yaw)
        if (mode == Mode.TELLY) {
            if (mc.options.jumpKey.isPressed && noUpTelly) {
                return rotation
            }
            if (mc.options.jumpKey.isPressed && randomSlow) {
                ups++
                if (ups % 2 == 0) {
                    return rotation
                }
            }
            if (smoothed && (player.airTicks < rotTick || safeMode)
            ) {
                if (player.onGroundTicks > 0) {
                    if (safeMode && (!testOnGround || mc.options.jumpKey.isPressed)) {
                        when (player.onGroundTicks) {
                            1 -> {
                                if (!forceRotation) {
                                    rotation.yaw = RotationManager.serverRotation.yaw + RotationUtils.smooth(
                                        diff.toFloat(),
                                        (diff / 2f).toFloat()
                                    )
                                    rotation.pitch = 75.5f
                                } else {
                                    rotation = RotationUtils.getClosestToBlockFace(
                                        blockData?.pos,
                                        blockData?.facing,
                                        player.yaw,
                                        RotationManager.serverRotation.pitch
                                    )
                                }
                                player.jumpingCooldown = 2
                            }

                            2 -> {
                                return Rotation(player.yaw, 75.5f)
                            }
                        }
                    } else {
                        return Rotation(player.yaw, 75.5f)
                    }
                } else {
                    var smooth = when (player.airTicks) {
                        1 -> 80f
                        else -> 50.0f
                    }
                    smooth -= (0.001f..0.005f).random()
                    rotation.yaw =
                        RotationManager.serverRotation.yaw + RotationUtils.smooth(diff.toFloat(), smooth)
                }
            } else {
                if (snap && mc.options.jumpKey.isPressed) {
                    if (lastBlockData == null || player.airTicks < rotTick) {
                        return Rotation(player.yaw, 85.0f + Math.random().toFloat())
                    }
                } else {
                    if (player.airTicks < rotTick) {
                        return Rotation(player.yaw, 85.0f + Math.random().toFloat())
                    }
                }
            }
        }
        if (lastRotation != null && blockData != null && ClientRayTraceUtil.didHitBlockFace(
                player,
                lastRotation!!.yaw,
                lastRotation!!.pitch,
                blockData!!.pos,
                blockData!!.facing,
                true
            )
        ) {
            return lastRotation!!
        }
        if (lastRotation != null && blockData != null && !alwaysUpdateRot && player.airTicks >= rotTick) {
            if (!ClientRayTraceUtil.didHitBlockFace(
                    player,
                    rotation!!.yaw,
                    rotation.pitch,
                    blockData!!.pos,
                    blockData!!.facing,
                    true
                ) && player.airTicks >= rotTick
            ) {
                lastRotation!!.yaw += Math.random().toFloat()
                return lastRotation!!
            }
        }
        lastRotation = rotation
        return rotation!!
    }

    private fun place() {
        if (blockData != null) {
            if (!canPlace) {
                return
            }
            val block: BlockHitResult? =
                ClientRayTraceUtil.getFacedBlock(
                    RotationManager.currentRotation?.yaw ?: player.yaw,
                    RotationManager.currentRotation?.pitch ?: player.pitch
                )
            if (!ClientRayTraceUtil.didHitBlockFace(
                    player,
                    RotationManager.currentRotation?.yaw ?: player.yaw,
                    RotationManager.currentRotation?.pitch ?: player.pitch,
                    blockData!!.pos,
                    blockData!!.facing,
                    true
                )
            ) {
                return
            }
            if (this.blockSlot!!.hand == Hand.MAIN_HAND) {
                player.inventory.selectedSlot = this.blockSlot!!.slot
                interaction.syncSelectedSlot()
            }
            if (duplicateRotPlace && abs(player.pitch - player.lastPitch) > 2.0) {
                val xDiff: Double = abs(abs(player.pitch - player.lastPitch) - lastPlacePitchDiff)
                if (xDiff < 0.0001) {
                    return
                }
            }
            if (interactItem) {
                interaction.interactItem(player, Hand.MAIN_HAND)
            }
            val result = interaction.interactBlock(player, this.blockSlot!!.hand, block)
            if (result == ActionResult.SUCCESS) {
                placeCount++
                lastPlacePosition = blockData!!.pos.offset(blockData!!.facing)
                if (abs(player.pitch - player.lastPitch) > 0.0) {
                    lastPlacePitchDiff = abs(player.pitch - player.lastPitch).toDouble()
                }
                if (noSwing) {
                    network.sendPacket(HandSwingC2SPacket(this.blockSlot!!.hand))
                } else {
                    player.swingHand(this.blockSlot!!.hand)
                }
            }
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        this.blockSlot = null

        if (player.offHandStack.isFullBlock() && ScaffoldBlockItemSelection.isValidBlock(
                player.offHandStack
            )
        ) {
            this.blockSlot = SlotData(99, Hand.OFF_HAND)
        }

        if (blockSlot == null && blockSlotMode != BlockSlotMode.MOST_BLOCKS) {
            if (player.mainHandStack.isFullBlock() && ScaffoldBlockItemSelection.isValidBlock(
                    player.mainHandStack
                )
            ) {
                this.blockSlot = SlotData(player.inventory.selectedSlot, Hand.MAIN_HAND)
            }
        }

        if (blockSlot == null) {
            val hotbarSlot = getHotbarBlockSlot()
            if (hotbarSlot != -1) {
                this.blockSlot = SlotData(hotbarSlot, Hand.MAIN_HAND)
            }
        }

        if (this.blockSlot == null || blockSlot!!.check()) {
            return@handler
        }
        if (player.isOnGround) {
            posY = floor(player.y - 1)
        }

        if (mc.options.jumpKey.isPressed) {
            posY = player.blockY - 1.0
        }
        
        // Removed old position-based search that was conflicting with prediction logic
        // This was causing placement to revert to current position after some time


        if (mode == Mode.NORMAL) {
            canPlace = true
        } else if (mode == Mode.SNAP) {
            canPlace = doesNotContainBlock()
        } else {
            canPlace = player.airTicks >= placeTick
            if (safeMode && testOnGround && !canPlace && mc.options.jumpKey.isPressed) {
                canPlace = player.onGroundTicks == 1
            }
        }

        if (this.blockSlot!!.hand == Hand.MAIN_HAND) {
            if (silentSwitch) {
                SilentHotbar.selectSlotSilently(this, this.blockSlot!!.slot, 2)
            } else {
                player.inventory.selectedSlot = this.blockSlot!!.slot
            }
        }
        // One simulation pass gives us the two-tick prediction and avoids duplicate physics work.
        val predictedState = simulatePlayerMovement(ticks = 2)
        val predictedPos = predictedState.position
        val predictedY = predictedPos.y
        var reachable = true
        val nextEyePos = predictedPos.add(0.0, player.standingEyeHeight.toDouble(), 0.0)
        
        // Use fully predicted position (X, Y, Z) for placement search
        val predictedBlockX = floor(predictedPos.x).toInt()
        val predictedBlockZ = floor(predictedPos.z).toInt()
        
        // Primary: predicted position, Secondary: velocity-aware fallback only
        // Removed current position fallback to prevent reverting to old behavior
        val placement: BlockData? = getBlockData(
            BlockPos(predictedBlockX, player.blockY - 1, predictedBlockZ),
            predictedBlockX,
            predictedBlockZ
        ) ?: getVelocityAwareFallback(predictedPos, player.blockY - 1)
        var forceRotation = false
        if (placement != null) {
            if (safeMode && testOnGround && player.onGroundTicks == 1 && mc.options.jumpKey.isPressed) {
                forceRotation = true
            }
            
            // Improved safeDistance check: use proper reach calculation instead of eye-to-center distance
            val reachCheck = isPlacementReachable(placement, nextEyePos, predictedPos)
            if (!reachCheck || placement.pos.y > predictedY) { // 这是大kb自救逻辑
                canPlace = true
                reachable = false
                lastBlockData = placement
                blockData = lastBlockData
            }
        }
        if (blockData != null) {
            val box = Box(this.blockData!!.pos)
                .withMinY(this.blockData!!.pos.y - 1.0)
                .withMaxY(this.blockData!!.pos.y + 1.0)
            if (blockData!!.pos.y > predictedY && !box.contains(player.pos)) { // 这是防止碰撞箱冲突
                canPlace = true
                reachable = false
                posY = player.blockY - 1.0 // 普通下落自救
                // Use predicted position for emergency placement instead of current position
                lastBlockData = getBlockData(
                    BlockPos(predictedBlockX, floor(posY).toInt(), predictedBlockZ),
                    predictedBlockX,
                    predictedBlockZ
                ) ?: getVelocityAwareFallback(predictedPos, floor(posY).toInt())
                blockData = lastBlockData
            }
        }
        if (!reachable && rotateCount < 8) {
            if (debug && rotateCount == 1) {
                notifyAsMessage(ModuleNewScaffold, "Clutching...")
            }
            // Removed skipTick = true - dangerous tick cancellation removed
            rotateCount++
        } else {
            rotateCount = 0
        }
        rot = getBRot(forceRotation)
        if (duplicateRotPlace && rot != null) {
            rot!!.pitch -= (0.001f..0.003f).random()
            rot!!.yaw -= (0.0001f..0.0003f).random()
            do {
                rot!!.pitch -= (0.001f..0.003f).random()
            } while (rot!!.pitch > 90f)
            if (rot!!.pitch < -90f) {
                rot!!.pitch = -90f
            }
        }
        if (didHitBlockFace(blockData, rot!!)) {
            // skipTick removal - no longer needed
            rotateCount = 0
        }
        rot = rot?.normalize()
        if (rot != null) {
            RotationManager.setRotationTarget(
                RotationTarget(
                    rotation = rot!!,
                    ticksUntilReset = 1,
                    resetThreshold = 1f,
                    considerInventory = true,
                    movementCorrection = MovementCorrection.SILENT,
                    whenReached = RestrictedSingleUseAction({ true }) {
                        place()
                    }
                ),
                Priority.IMPORTANT_FOR_PLAYER_LIFE,
                ModuleNewScaffold
            )
        }
        if (blockData == null) return@handler
        if (mode == Mode.TELLY) {
            return@handler
        }
        if (waitingForEagleSneak) {
            tellyJumpTicks++
            if (tellyJumpTicks == tellyEagleTick && !mc.options.sneakKey.isPressed) {
                mc.options.sneakKey.isPressed = true
            }
            if (tellyJumpTicks == tellyEagleTick + keepEagleSneakTick) {
                mc.options.sneakKey.isPressed = false
                waitingForEagleSneak = false
                tellyJumpTicks = 0
            }
        }
    }

    private fun didHitBlockFace(blockData: BlockData?, rot: Rotation): Boolean {
        return blockData == null || !ClientRayTraceUtil.didHitBlockFace(rot, blockData.pos, blockData.facing, true)
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (mode == Mode.TELLY && eagle) {
            event.sneak = placeCount % 4 == 0
        }
    }

    @Suppress("unused")
    private val slowHandler = handler<PlayerUseMultiplier> {
        if (player.onGroundTicks == 1 && testOnGround && smoothed && !noUpTelly && safeMode && mc.options.jumpKey.isPressed) {
            player.input.movementForward *= 0.2f
            player.input.movementSideways *= 0.2f
        }
    }

    private fun doesNotContainBlock(): Boolean {
        return blockRelativeToPlayer().defaultState.isTransparent
    }

    private fun getHotbarBlockSlot(): Int {
        if (blockSlotMode == BlockSlotMode.MOST_BLOCKS) {
            return getMostBlocksHotbarSlot()
        }

        var slot = -1
        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.isFullBlock() && ScaffoldBlockItemSelection.isValidBlock(stack)) {
                slot = i
            }
        }
        return slot
    }

    private fun getMostBlocksHotbarSlot(): Int {
        val selectedSlot = player.inventory.selectedSlot
        var bestSlot = -1
        var bestCount = -1

        val selectedStack = player.inventory.getStack(selectedSlot)
        if (selectedStack.isFullBlock() && ScaffoldBlockItemSelection.isValidBlock(selectedStack)) {
            bestSlot = selectedSlot
            bestCount = selectedStack.count
        }

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.isFullBlock() && ScaffoldBlockItemSelection.isValidBlock(stack) && stack.count > bestCount) {
                bestSlot = i
                bestCount = stack.count
            }
        }
        return bestSlot
    }

    private fun getBlockData(pos: BlockPos, predictedX: Int = player.blockX, predictedZ: Int = player.blockZ): BlockData? {
        val data: BlockData

        if (getPos(pos) == null) {
            val blockPos = getBlockPos()
            if (blockPos == null) return null

            val direction = getPlaceSide(blockPos, predictedX, predictedZ)
            if (direction == null) return null

            data = BlockData(blockPos, direction)
        } else {
            data = getPos(pos)!!
        }

        if (ClientRayTraceUtil.isIgnoredBlock(world.getBlockState(data.pos.offset(data.facing)))) {
            return data
        }

        return null
    }

    private fun getPlaceSide(blockPos: BlockPos, predictedX: Int = player.blockX, predictedZ: Int = player.blockZ): Direction? {
        // Use predicted position for placement side selection instead of current position
        val playerPos = BlockPos(predictedX, player.blockY, predictedZ)
        
        var best: Direction? = null
        var bestDistance = Double.POSITIVE_INFINITY

        for (face in arrayOf(Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST)) {
            val placePos = blockPos.offset(face)
            if (placePos == playerPos || !isAirBlock(placePos)) continue
            val supportPos = placePos.offset(face)
            if (!ClientRayTraceUtil.isIgnoredBlock(world.getBlockState(supportPos))) continue

            val distance = placePos.getSquaredDistance(playerPos)
            if (distance < bestDistance) {
                bestDistance = distance
                best = face
            }
        }
        return best
    }

    private fun getBlockPos(): BlockPos? {
        val px = player.blockX
        val py = player.blockY
        val pz = player.blockZ

        // Get velocity direction for velocity-aware search
        val velX = player.velocity.x
        val velZ = player.velocity.z
        val speed = kotlin.math.sqrt(velX * velX + velZ * velZ)
        
        // Normalize velocity for direction
        val dirX = if (speed > 0.001) velX / speed else 0.0
        val dirZ = if (speed > 0.001) velZ / speed else 0.0
        
        // Get predicted position for better scoring (reuse prediction to avoid duplication)
        val predictedState = simulatePlayerMovement(ticks = 1)
        val predictedPos = predictedState.position
        val predX = floor(predictedPos.x).toInt()
        val predZ = floor(predictedPos.z).toInt()

        var best: BlockPos? = null
        var bestScore = Double.NEGATIVE_INFINITY

        // Search range - same envelope but velocity-aware scoring
        for (x in 5 downTo -4) {
            for (y in 5 downTo -4) {
                if (py + y >= py) continue
                for (z in 5 downTo -4) {
                    val pos = BlockPos(px + x, py + y, pz + z)
                    if (!isPosSolid(pos)) continue
                    
                    // Multi-factor scoring instead of simple distance
                    val score = scorePlacementCandidate(pos, px, py, pz, predX, predZ, dirX, dirZ, speed)
                    if (score > bestScore) {
                        bestScore = score
                        best = pos
                    }
                }
            }
        }
        return best
    }

    private fun scorePlacementCandidate(
        pos: BlockPos, 
        px: Int, py: Int, pz: Int,
        predX: Int, predZ: Int,
        dirX: Double, dirZ: Double, 
        speed: Double
    ): Double {
        // Distance from predicted player position (more accurate for reach)
        val predictedDist = pos.getSquaredDistance(predX.toDouble(), py.toDouble(), predZ.toDouble())
        
        // Direction alignment: how well this position aligns with movement direction
        val deltaX = (pos.x - predX).toDouble()
        val deltaZ = (pos.z - predZ).toDouble()
        val directionScore = if (speed > 0.1) {
            // Dot product for direction alignment
            (deltaX * dirX + deltaZ * dirZ) / (kotlin.math.sqrt(deltaX * deltaX + deltaZ * deltaZ) + 0.001)
        } else 0.0
        
        // Distance penalty: closer is better, but direction alignment matters more
        val distancePenalty = predictedDist * 0.5
        
        // Height preference: prefer same level or slightly below
        val deltaY = (pos.y - py).toDouble()
        val heightScore = if (deltaY <= 0) 0.0 else deltaY * 2.0
        
        // Combined score: higher is better
        // Direction alignment (positive for forward positions) - distance penalty - height penalty
        return directionScore * 10.0 - distancePenalty - heightScore
    }

    private fun isAirBlock(blockPos: BlockPos?): Boolean {
        return blockPos != null && ClientRayTraceUtil.isIgnoredBlock(world.getBlockState(blockPos))
    }

    private fun getPos(pos: BlockPos): BlockData? {
        if (isPosSolid(pos.add(-1, 0, 0))) return BlockData(pos.add(-1, 0, 0), Direction.EAST)
        if (isPosSolid(pos.add(1, 0, 0))) return BlockData(pos.add(1, 0, 0), Direction.WEST)
        if (isPosSolid(pos.add(0, 0, 1))) return BlockData(pos.add(0, 0, 1), Direction.NORTH)
        if (isPosSolid(pos.add(0, 0, -1))) return BlockData(pos.add(0, 0, -1), Direction.SOUTH)
        if (isPosSolid(pos.add(0, -1, 0))) return BlockData(pos.add(0, -1, 0), Direction.UP)
        return null
    }

    private fun isPosSolid(pos: BlockPos?): Boolean {
        if (pos == null) return false
        val state = world.getBlockState(pos)
        val block = state.block
        if (block is TrapdoorBlock || block is DoorBlock || block is FenceGateBlock) return false
        return block !in NON_SUPPORT_BLOCKS && !ClientRayTraceUtil.isIgnoredBlock(state)
    }

    private val NON_SUPPORT_BLOCKS: Set<Block> by lazy {
        setOf(
            Blocks.ANVIL, Blocks.AIR, Blocks.WATER, Blocks.FIRE, Blocks.LAVA,
            Blocks.SKELETON_SKULL, Blocks.OAK_SIGN, Blocks.TRAPPED_CHEST, Blocks.CHEST,
            Blocks.ENCHANTING_TABLE, Blocks.ENDER_CHEST, Blocks.CRAFTING_TABLE,
            Blocks.DAYLIGHT_DETECTOR, Blocks.COBWEB, Blocks.SHORT_GRASS, Blocks.FLOWER_POT,
            Blocks.CHORUS_FLOWER, Blocks.SUNFLOWER, Blocks.CORNFLOWER, Blocks.TORCHFLOWER,
            Blocks.OAK_BUTTON, Blocks.ACACIA_BUTTON, Blocks.BIRCH_BUTTON, Blocks.CRIMSON_BUTTON,
            Blocks.CHERRY_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.JUNGLE_BUTTON,
            Blocks.STONE_BUTTON, Blocks.WARPED_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.NOTE_BLOCK,
            Blocks.PLAYER_HEAD
        )
    }

    private data class SlotData(val slot: Int, val hand: Hand) {
        fun check(): Boolean {
            if (hand == Hand.OFF_HAND) {
                val stack = player.offHandStack
                return stack.isEmpty || stack.item !is BlockItem
            }

            return player.inventory.getStack(slot).isEmpty
                    || player.inventory.getStack(slot).item !is BlockItem
        }
    }

    private fun blockRelativeToPlayer(): Block {
        val pos = BlockPos(
            floor(player.x).toInt(),
            floor(player.y).toInt() - 1,
            floor(player.z).toInt()
        )
        return player.world.getBlockState(pos).block
    }

    private fun getVelocityAwareFallback(predictedPos: Vec3d, targetY: Int): BlockData? {
        val velX = player.velocity.x
        val velZ = player.velocity.z
        val speed = kotlin.math.sqrt(velX * velX + velZ * velZ)
        
        if (speed < 0.1) return null // No significant movement
        
        // Normalize velocity direction
        val dirX = velX / speed
        val dirZ = velZ / speed
        
        // Search along velocity direction first (forward cone)
        val predictedBlockX = floor(predictedPos.x).toInt()
        val predictedBlockZ = floor(predictedPos.z).toInt()
        
        // Priority order: along movement direction, then expanding
        val searchOffsets = listOf(
            // Forward direction (2 blocks ahead)
            Pair(2, 0), Pair(0, 2), Pair(1, 1), Pair(1, -1), Pair(-1, 1),
            // Forward direction (1 block ahead)  
            Pair(1, 0), Pair(0, 1), Pair(-1, 0), Pair(0, -1),
            // Diagonal and nearby
            Pair(2, 1), Pair(1, 2), Pair(-2, 1), Pair(-1, 2),
            Pair(2, -1), Pair(1, -2), Pair(-2, -1), Pair(-1, -2),
            // Further ahead for high speed
            Pair(3, 0), Pair(0, 3), Pair(2, 2), Pair(-2, 2), Pair(2, -2), Pair(-2, -2)
        )
        
        // Sort offsets by alignment with velocity direction
        val sortedOffsets = searchOffsets.sortedByDescending { (dx, dz) ->
            val dotProduct = dx * dirX + dz * dirZ
            dotProduct
        }
        
        for ((dx, dz) in sortedOffsets) {
            val candidatePos = BlockPos(predictedBlockX + dx, targetY, predictedBlockZ + dz)
            val blockData = getBlockData(candidatePos, predictedBlockX, predictedBlockZ)
            if (blockData != null) {
                return blockData
            }
        }
        
        return null
    }

    private fun isPlacementReachable(placement: BlockData, eyePos: Vec3d, predictedPos: Vec3d): Boolean {
        // Calculate actual reach distance based on ray trace and placement face
        val placementFaceCenter = placement.pos.toCenterPos().add(
            placement.facing.vector.x * 0.5,
            placement.facing.vector.y * 0.5, 
            placement.facing.vector.z * 0.5
        )
        
        val actualDistance = eyePos.distanceTo(placementFaceCenter)
        
        // Also check if the predicted player position can reach this placement
        val predictedDistance = predictedPos.distanceTo(placementFaceCenter)
        
        // Use the more conservative distance check
        val maxDistance = kotlin.math.max(actualDistance, predictedDistance)
        
        return maxDistance < safeDistance
    }

}
