package batvehicles_mts_patch.phone;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import batvehicles_mts_patch.phone.PacketPhoneAction.Action;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperItemStack;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;

/**
 * Runs phone calls on the server.  One job per vehicle; calling a vehicle that's already on its
 * way restarts its call.
 */
public final class SummonManager {
    private static final Map<UUID, SummonJob> jobs = new ConcurrentHashMap<>();

    private SummonManager() {
    }

    /**Handles a button press from the phone menu.**/
    static void handleAction(AWrapperWorld world, IWrapperPlayer player, Action action, UUID vehicleID) {
        //Only act on vehicles registered on the phone the player is actually holding.
        if (!VehiclePhone.isPhone(player.getHeldItem())) {
            return;
        }
        IWrapperItemStack stack = player.getHeldStack();
        List<VehiclePhone.Entry> entries = VehiclePhone.getEntries(stack);
        VehiclePhone.Entry entry = entries.stream().filter(e -> e.vehicleID.equals(vehicleID)).findFirst().orElse(null);
        if (entry == null) {
            return;
        }

        SummonJob existing = jobs.remove(vehicleID);
        if (existing != null) {
            existing.cancel();
        }
        switch (action) {
            case SUMMON: {
                jobs.put(vehicleID, new SummonJob(world, player, vehicleID, entry.name));
                break;
            }
            case STOP: {
                VehiclePhone.message(player, existing != null ? "Cancelled the call to " + entry.name + "." : entry.name + " isn't on its way.");
                break;
            }
            case REMOVE: {
                entries.remove(entry);
                VehiclePhone.setEntries(stack, entries);
                VehiclePhone.message(player, "Removed " + entry.name + " from the phone.");
                break;
            }
        }
    }

    /**Called at the start of every server tick.**/
    public static void tick() {
        Iterator<SummonJob> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().tick()) {
                iterator.remove();
            }
        }
        VehicleTracker.tick();
    }

    /**Called every vehicle tick, on both sides, just before MTS moves it.**/
    public static void onVehicleTick(EntityVehicleF_Physics vehicle) {
        VehicleTracker.onVehicleTick(vehicle);
        if (!vehicle.world.isClient() && vehicle.ticksExisted % 20 == 0 && vehicle.getOrCreateVariable(SummonFlight.VAR_ACTIVE).isActive && !jobs.containsKey(vehicle.uniqueUUID)) {
            //Flight left on from a call that no longer exists, like one cut off by a restart.
            SummonFlight.stop(vehicle);
        }
        SummonFlight.apply(vehicle);
    }

    public static void onServerStopping() {
        jobs.values().forEach(SummonJob::cancel);
        jobs.clear();
        VehicleTracker.saveAll();
        VehicleTracker.clear();
    }
}
