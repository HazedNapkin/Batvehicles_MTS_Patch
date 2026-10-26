package batvehicles_mts_patch.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import batvehicles_mts_patch.GrappleSystem;
import batvehicles_mts_patch.phone.SummonManager;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.VehicleGroundDeviceCollection;
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
    @Shadow(remap = false)
    @Final
    private Point3D motionApplied;

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lminecrafttransportsimulator/entities/instances/AEntityVehicleD_Moving;moveVehicle()V"), remap = false)
    private void batvehicles_mts_patch$applyGrapples(CallbackInfo ci) {
        Object self = this;
        if (self instanceof EntityVehicleF_Physics) {
            GrappleSystem.updateVehicle((EntityVehicleF_Physics) self);
            //Phone summons go last, so a summoned aircraft's flight overrides anything else this tick.
            SummonManager.onVehicleTick((EntityVehicleF_Physics) self);
        }
    }

    /**
     * Applies the grapple's push out of a wall, right after MTS moves the vehicle.  MTS cancels any
     * move made by a box that's already inside a block, so the push can't go through motion.
     * Adding it to motionApplied as well keeps client and server in step: the server sends
     * motionApplied to clients, and clients count it towards what they've already moved.
     * This is the first isZero() after the ApplyMotions profiler section starts, which is
     * the rotation check straight after position.add(motionApplied).
     */
    @Inject(method = "moveVehicle", slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=ApplyMotions")), at = @At(value = "INVOKE", target = "Lminecrafttransportsimulator/baseclasses/Point3D;isZero()Z", ordinal = 0), remap = false)
    private void batvehicles_mts_patch$pushOutOfWalls(CallbackInfo ci) {
        Object self = this;
        if (self instanceof EntityVehicleF_Physics) {
            EntityVehicleF_Physics vehicle = (EntityVehicleF_Physics) self;
            Point3D pushOut = GrappleSystem.takePushOut(vehicle);
            if (pushOut != null) {
                vehicle.position.add(pushOut);
                motionApplied.add(pushOut);
            }
        }
    }

    /**
     * Skips MTS's pitch levelling while a grapple line is holding one end of the car up.
     * Otherwise MTS sees airborne wheels and tips the lifted end straight back down every tick.
     */
    @Redirect(method = "moveVehicle", at = @At(value = "INVOKE", target = "Lminecrafttransportsimulator/baseclasses/VehicleGroundDeviceCollection;performPitchCorrection(Lminecrafttransportsimulator/baseclasses/Point3D;)V"), remap = false)
    private void batvehicles_mts_patch$holdGrapplePitch(VehicleGroundDeviceCollection groundDevices, Point3D groundMotion) {
        Object self = this;
        if (!(self instanceof EntityVehicleF_Physics) || !GrappleSystem.isPitchHeld((EntityVehicleF_Physics) self)) {
            groundDevices.performPitchCorrection(groundMotion);
        }
    }
}
