package batvehicles_mts_patch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import batvehicles_mts_patch.GrappleSystem;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;

/**
 * Hooks the grapple line into MTS vehicle movement.
 * We inject just before moveVehicle(), so all of MTS's own forces, braking and tyre grip
 * have already been applied to motion/rotation for this tick.  Anything we add here goes
 * through MTS's normal collision checks and client/server movement sync.
 * MTS classes aren't obfuscated, so nothing here is remapped.
 * The target is package-private, so it's named by string rather than class literal.
 */
@Mixin(targets = "minecrafttransportsimulator.entities.instances.AEntityVehicleD_Moving", remap = false)
public abstract class VehicleMovingMixin {

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lminecrafttransportsimulator/entities/instances/AEntityVehicleD_Moving;moveVehicle()V"), remap = false)
    private void batvehicles_mts_patch$applyGrapples(CallbackInfo ci) {
        Object self = this;
        if (self instanceof EntityVehicleF_Physics) {
            GrappleSystem.updateVehicle((EntityVehicleF_Physics) self);
        }
    }
}
