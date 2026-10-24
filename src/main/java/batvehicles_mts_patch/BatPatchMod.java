package batvehicles_mts_patch;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point.  The grapple ({@link GrappleSystem}) and the vehicle phone
 * ({@link batvehicles_mts_patch.phone.VehiclePhone}) hook into MTS through mixins, and the
 * phone's server jobs are ticked through {@link PlatformHooks}.  Keeping this class small means
 * the only Minecraft-version-specific code is here, in PlatformHooks, and in the build.
 */
@Mod(BatPatchMod.MODID)
public class BatPatchMod {
    public static final String MODID = "batvehicles_mts_patch";

    public BatPatchMod() {
        PlatformHooks.registerEvents();
    }
}
