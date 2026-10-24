package batvehicles_mts_patch.phone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.InterfaceManager;

/**
 * Remembers where every vehicle was last seen, per dimension, so the phone can find vehicles
 * whose chunks aren't loaded.  Positions are kept in memory and saved to the dimension's MTS data
 * file every so often, as each save also sends the data to every client.
 */
public final class VehicleTracker {
    private static final String DATA_NAME = "batvehicles_mts_patch_vehicles";
    private static final int TRACK_INTERVAL = 20;
    private static final int SAVE_INTERVAL = 600;

    private static final Map<AWrapperWorld, WorldPositions> worlds = new HashMap<>();
    private static int ticksUntilSave = SAVE_INTERVAL;

    private VehicleTracker() {
    }

    /**Called every vehicle tick on the server.**/
    public static void onVehicleTick(EntityVehicleF_Physics vehicle) {
        if (!vehicle.world.isClient() && vehicle.ticksExisted % TRACK_INTERVAL == 0) {
            update(vehicle);
        }
    }

    /**Records the vehicle's current position now.**/
    static void update(EntityVehicleF_Physics vehicle) {
        WorldPositions positions = get(vehicle.world);
        Point3D last = positions.positions.get(vehicle.uniqueUUID);
        if (last == null || !last.isDistanceToCloserThan(vehicle.position, 1)) {
            positions.positions.put(vehicle.uniqueUUID, vehicle.position.copy());
            positions.dirty = true;
        }
    }

    /**Where the vehicle was last seen in this world, or null if it never was.**/
    static Point3D getLastPosition(AWrapperWorld world, UUID vehicleID) {
        return get(world).positions.get(vehicleID);
    }

    /**Called every server tick.  Saves changed positions now and then.**/
    static void tick() {
        if (--ticksUntilSave <= 0) {
            ticksUntilSave = SAVE_INTERVAL;
            saveAll();
        }
    }

    static void saveAll() {
        worlds.forEach((world, positions) -> {
            if (positions.dirty) {
                IWrapperNBT data = InterfaceManager.coreInterface.getNewNBTWrapper();
                positions.positions.forEach((id, position) -> data.setPoint3d(id.toString(), position));
                world.setData(DATA_NAME, data);
                positions.dirty = false;
            }
        });
    }

    /**Forgets everything, for when the server stops.  Worlds are re-read next time.**/
    static void clear() {
        worlds.clear();
    }

    private static WorldPositions get(AWrapperWorld world) {
        return worlds.computeIfAbsent(world, w -> {
            WorldPositions positions = new WorldPositions();
            IWrapperNBT data = w.getData(DATA_NAME);
            if (data != null) {
                for (String name : data.getAllNames()) {
                    try {
                        positions.positions.put(UUID.fromString(name), data.getPoint3d(name));
                    } catch (IllegalArgumentException e) {
                        //Not one of ours.  Skip it.
                    }
                }
            }
            return positions;
        });
    }

    private static class WorldPositions {
        final Map<UUID, Point3D> positions = new HashMap<>();
        boolean dirty;
    }
}
