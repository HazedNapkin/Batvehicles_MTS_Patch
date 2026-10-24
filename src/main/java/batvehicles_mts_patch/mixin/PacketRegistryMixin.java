package batvehicles_mts_patch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import batvehicles_mts_patch.phone.PacketPhoneAction;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.components.APacketBase;

/**
 * Registers our packets with MTS's network channel, right after MTS registers its own.
 * This runs on both client and server, so both sides agree on the packet index.
 */
@Mixin(value = APacketBase.class, remap = false)
public abstract class PacketRegistryMixin {

    @Inject(method = "initPackets", at = @At("TAIL"), remap = false)
    private static void batvehicles_mts_patch$registerPackets(byte packetIndex, CallbackInfo ci) {
        InterfaceManager.packetInterface.registerPacket(PacketPhoneAction.PACKET_INDEX, PacketPhoneAction.class);
    }
}
