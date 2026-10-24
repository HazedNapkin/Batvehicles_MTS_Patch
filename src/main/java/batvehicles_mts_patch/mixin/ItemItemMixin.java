package batvehicles_mts_patch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import batvehicles_mts_patch.phone.VehiclePhone;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.entities.components.AEntityE_Interactable;
import minecrafttransportsimulator.items.components.IItemEntityInteractable.CallbackType;
import minecrafttransportsimulator.items.instances.ItemItem;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;

/**
 * Gives the vehicle phone its behaviour.  The phone is a plain "none" type MTS item, so we
 * catch its clicks here before MTS's generic item code sees them.
 */
@Mixin(value = ItemItem.class, remap = false)
public abstract class ItemItemMixin {

    @Inject(method = "doEntityInteraction", at = @At("HEAD"), cancellable = true, remap = false)
    private void batvehicles_mts_patch$phoneEntityInteraction(AEntityE_Interactable<?> entity, BoundingBox hitBox, IWrapperPlayer player, boolean rightClick, CallbackInfoReturnable<CallbackType> cir) {
        if (VehiclePhone.isPhone((ItemItem) (Object) this)) {
            cir.setReturnValue(VehiclePhone.onEntityInteraction(entity, player, rightClick));
        }
    }

    @Inject(method = "onUsed", at = @At("HEAD"), cancellable = true, remap = false)
    private void batvehicles_mts_patch$phoneUsed(AWrapperWorld world, IWrapperPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (VehiclePhone.isPhone((ItemItem) (Object) this)) {
            cir.setReturnValue(VehiclePhone.onUsed(world, player));
        }
    }
}
