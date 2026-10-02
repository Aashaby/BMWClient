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

package net.ccbluex.liquidbounce.features.module.modules.combat.velocity.mode

import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.QueuePacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.utils.raycast
import net.ccbluex.liquidbounce.utils.client.PacketQueueManager
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult

internal object VelocityGrim2371 : VelocityMode("Grim2371") {

    private var cancelNextVelocity = false
    private var delay = false
    private var needClick = false

    private var waitForPing = false
    private var waitForUpdate = false
    private var damageWindowUntil = 0L

    private var hitResult: BlockHitResult? = null
    private var shouldSkip = false

    private var freezeTicks = 0
    private const val MAX_FREEZE_TICKS = 20 // To prevent freezing

    override fun enable() {
        cancelNextVelocity = false
        delay = false
        needClick = false
        waitForUpdate = false
        hitResult = null
        shouldSkip = false
        freezeTicks = 0
    }

    override fun disable() {
        cancelNextVelocity = false
        delay = false
        waitForPing = false
        waitForUpdate = false
        damageWindowUntil = 0L
        PacketQueueManager.flush(TransferOrigin.INCOMING)
    }

    private val Packet<*>.isSelfDamage
        get() = this is EntityDamageS2CPacket && this.entityId == player.id

    private val Packet<*>.isSelfVelocity
        get() = this is EntityVelocityUpdateS2CPacket && this.entityId == player.id
            || this is ExplosionS2CPacket

    @Suppress("unused")
    private val packetHandler = sequenceHandler<PacketEvent> { event ->
        val packet = event.packet

        when (packet) {
            is PlayerInteractEntityC2SPacket, is PlayerInteractBlockC2SPacket ->
                shouldSkip = true

            is PlayerMoveC2SPacket if packet.changesPosition() && waitForUpdate &&
                !ModuleScaffold.running && !ModuleScaffold.isTowering ->
                event.cancelEvent()

            is CommonPongC2SPacket if waitForPing -> {
                waitTicks(1)
                waitForUpdate = false
                waitForPing = false
            }
        }

        if (event.isCancelled) {
            return@sequenceHandler
        }

        if (packet is BlockUpdateS2CPacket && waitForUpdate && packet.pos == player.blockPos) {
            waitTicks(1)
            waitForPing = true
            needClick = false
            return@sequenceHandler
        }

        if (waitForUpdate || delay) {
            return@sequenceHandler
        }

        // Check for damage to make sure it will only cancel damage velocity (that all we need),
        // and not affect other types of velocity
        val now = System.currentTimeMillis()
        if (cancelNextVelocity && now > damageWindowUntil) {
            cancelNextVelocity = false
            damageWindowUntil = 0L
        }

        if (packet.isSelfDamage) {
            cancelNextVelocity = true
            damageWindowUntil = now + 750L
        } else if (cancelNextVelocity && event.packet.isSelfVelocity && now <= damageWindowUntil) {
            event.cancelEvent()
            delay = true
            cancelNextVelocity = false
            damageWindowUntil = 0L
            needClick = true
        }
    }

    @Suppress("unused")
    private val queuePacketHandler = handler<QueuePacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || waitForUpdate || !delay) {
            return@handler
        }

        // Keep unrelated world/entity packets live. Only hold the packets that can carry
        // knockback for this player; delaying everything else is a major source of replay bursts.
        if (event.packet == null || event.packet !is EntityVelocityUpdateS2CPacket && event.packet !is ExplosionS2CPacket) {
            return@handler
        }

        if (event.packet is EntityVelocityUpdateS2CPacket && event.packet.entityId != player.id) {
            return@handler
        }

        if (event.packet is ExplosionS2CPacket && !event.packet.playerKnockback.isPresent) {
            return@handler
        }

        event.action = PacketQueueManager.Action.QUEUE
    }

    @Suppress("unused")
    private val playerTickHandler = handler<PlayerTickEvent> { event ->
        if (needClick && !shouldSkip && !player.isUsingItem) {
            hitResult = raycast(
                rotation = RotationManager.serverRotation.copy(pitch = 90F)
            ).takeIf {
                it.blockPos.offset(it.side) == player.blockPos
            }
        }

        if (hitResult != null) {
            delay = false

            PacketQueueManager.flush(TransferOrigin.INCOMING)

            if (interaction.interactBlock(player, Hand.MAIN_HAND, hitResult).isAccepted) {
                player.swingHand(Hand.MAIN_HAND)
            }

            if (RotationManager.serverRotation.pitch != 90f) {
                network.sendPacket(
                    PlayerMoveC2SPacket.LookAndOnGround(
                        player.yaw,
                        90f,
                        player.isOnGround,
                        player.horizontalCollision
                    )
                )
            } else {
                network.sendPacket(
                    PlayerMoveC2SPacket.OnGroundOnly(
                        player.isOnGround,
                        player.horizontalCollision
                    )
                )
            }

            freezeTicks = 0
            waitForUpdate = !ModuleScaffold.running && !ModuleScaffold.isTowering
            hitResult = null
            needClick = false
        }

        if (waitForUpdate) {
            if (!ModuleScaffold.running && !ModuleScaffold.isTowering) {
                event.cancelEvent()
            }
            freezeTicks++
            if (freezeTicks > MAX_FREEZE_TICKS) {
                // Never leave the velocity packet stranded in the global queue.
                PacketQueueManager.flush(TransferOrigin.INCOMING)
                waitForUpdate = false
                waitForPing = false
                delay = false
                needClick = false
                freezeTicks = 0
            }
        }

        shouldSkip = false
    }
}
