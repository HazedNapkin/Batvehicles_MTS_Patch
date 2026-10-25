package batvehicles_mts_patch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import minecrafttransportsimulator.entities.instances.PartEngine;

/**
 * Stops engines with the {@link #CONSTANT_NO_WEAR} definition constant from ever wearing.
 * MTS adds hours for running, over-revving, overheating, low oil and taking damage, and there's no
 * JSON way to turn that off (an engineWearFactor of 0 is reset to 1 on load, and damage ignores it).
 * Hours lower an engine's top RPM and, past 500, make normal engines backfire, so we hold them at 0.
 * Clearing them at the start of the tick undoes anything added since the last one (such as damage),
 * and clearing them at the end means the saved and synced value is always 0.
 */
@Mixin(value = PartEngine.class, remap = false)
public abstract class PartEngineMixin {
    //Mixin only allows private static fields in a mixin class.
    private static final String CONSTANT_NO_WEAR = "no_engine_wear";

    @Inject(method = "update", at = {@At("HEAD"), @At("RETURN")}, remap = false)
    private void batvehicles_mts_patch$clearHours(CallbackInfo ci) {
        PartEngine engine = (PartEngine) (Object) this;
        if (engine.hoursVar.currentValue != 0 && engine.getOrCreateVariable(CONSTANT_NO_WEAR).isActive) {
            engine.hoursVar.setTo(0, false);
        }
    }
}
