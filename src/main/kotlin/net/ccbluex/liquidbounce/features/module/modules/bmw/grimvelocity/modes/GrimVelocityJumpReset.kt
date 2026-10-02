package net.ccbluex.liquidbounce.features.module.modules.bmw.grimvelocity.modes

import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.bmw.grimvelocity.GrimVelocityMode
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.utils.client.PacketQueueManager
import net.ccbluex.liquidbounce.features.module.modules.player.nofall.modes.NoFallGrim
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket

object GrimVelocityJumpReset : GrimVelocityMode("JumpReset") {

    private val chance by int("Chance", 100, 0..100, "%")
    private val requireKillAura by boolean("RequireKillAura", true)

    private var jump = false
    private var damage = false
    private var damageWindowUntil = 0L

    // JumpReset owns the next movement-input tick while this request is pending.
    // Prevent Backtrack from changing network timing in the same transition.
    override val shouldStopBacktrack: Boolean
        get() = jump

    @Suppress("unused")
    private val movementInputEventHandler = handler<MovementInputEvent> { event ->
        if (!jump) {
            return@handler
        }

        if (!ModuleScaffold.running &&
            !ModuleScaffold.isTowering &&
            !PacketQueueManager.isLagging &&
            !ModuleBacktrack.isLagging() &&
            !InventoryManager.isInventoryOpen &&
            mc.currentScreen !is GenericContainerScreen &&
            player.isOnGround &&
            !(NoFallGrim.running && NoFallGrim.jumping)
        ) {
            event.jump = true
        }

        // A jump request is one-shot. If Scaffold/another queue owns movement this tick,
        // deliberately give control back to it instead of carrying the request into a later state.
        jump = false
    }

    @Suppress("unused")
    private val packetEventHandler = handler<PacketEvent> { event ->
        val packet = event.packet

        if (pause) return@handler

        if (packet is EntityDamageS2CPacket && packet.entityId == player.id) {
            damage = true
            damageWindowUntil = System.currentTimeMillis() + 750L
            return@handler
        }

        if (damage &&
            packet is EntityVelocityUpdateS2CPacket &&
            packet.entityId == player.id &&
            System.currentTimeMillis() <= damageWindowUntil
        ) {
            if (!requireKillAura || (ModuleKillAura.running && ModuleKillAura.targetTracker.target != null)) {
                jump = (1..100).random() <= chance
            }
            damage = false
            damageWindowUntil = 0L
        } else if (damage && System.currentTimeMillis() > damageWindowUntil) {
            // Do not let a stale damage flag arm a later unrelated velocity packet.
            damage = false
            damageWindowUntil = 0L
        }
    }

    override fun enable() {
        jump = false
        damage = false
        damageWindowUntil = 0L
    }

    override fun disable() {
        jump = false
        damage = false
        damageWindowUntil = 0L
    }

}
