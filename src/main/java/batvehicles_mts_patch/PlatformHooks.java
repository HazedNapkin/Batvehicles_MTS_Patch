package batvehicles_mts_patch;

import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * The few things MTS's wrappers don't expose, done with Minecraft/Forge 1.20.1 directly.
 * This is the only class besides {@link BatPatchMod} to change when porting to another version.
 */
public final class PlatformHooks {

    private PlatformHooks() {
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
}
