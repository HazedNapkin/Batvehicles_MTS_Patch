package batvehicles_mts_patch;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point.  All the real work happens in {@link GrappleSystem}, which is
 * called from a mixin into the MTS vehicle physics.  Keeping this class empty means
 * the only Minecraft-version-specific code in the mod is this file and the build.
 */
@Mod(BatPatchMod.MODID)
public class BatPatchMod {
    public static final String MODID = "batvehicles_mts_patch";
}
