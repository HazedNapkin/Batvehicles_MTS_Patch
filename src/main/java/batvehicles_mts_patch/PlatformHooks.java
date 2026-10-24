package batvehicles_mts_patch;

import batvehicles_mts_patch.phone.SummonManager;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * The few things MTS's wrappers don't expose, done with Minecraft/Forge 1.20.1 directly.
 * This is the only class besides {@link BatPatchMod} to change when porting to another version.
 */
public final class PlatformHooks {

    private PlatformHooks() {
    }

    /**Hooks our server-side systems into Forge's events.**/
    static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.START) {
                SummonManager.tick();
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, event -> SummonManager.onServerStopping());
    }

    /**
     * Tells Minecraft an entity's velocity was changed on the server.  Without this, players
     * ignore it, as their movement is client-side.
     */
    public static void markVelocityChanged(IWrapperEntity wrapper) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(wrapper.getID());
            if (entity != null) {
                entity.hurtMarked = true;
                return;
            }
        }
    }

    /**
     * Force-loads, or stops force-loading, the chunk at the position in the player's dimension.
     * Used to wake up vehicles parked out of range.
     */
    public static void setChunkForced(IWrapperPlayer player, Point3D position, boolean forced) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || player == null) {
            return;
        }
        ServerPlayer mcPlayer = server.getPlayerList().getPlayer(player.getID());
        if (mcPlayer != null) {
            mcPlayer.serverLevel().setChunkForced((int) Math.floor(position.x) >> 4, (int) Math.floor(position.z) >> 4, forced);
        }
    }
}
