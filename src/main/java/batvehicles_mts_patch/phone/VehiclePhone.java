package batvehicles_mts_patch.phone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import batvehicles_mts_patch.BatPatchMod;
import minecrafttransportsimulator.entities.components.AEntityE_Interactable;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.items.components.IItemEntityInteractable.CallbackType;
import minecrafttransportsimulator.items.instances.ItemItem;
import minecrafttransportsimulator.jsondefs.AJSONInteractableEntity;
import minecrafttransportsimulator.jsondefs.AJSONInteractableEntity.JSONCustomKeybind;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperItemStack;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketPlayerChatMessage;
import minecrafttransportsimulator.systems.LanguageSystem;

/**
 * The vehicle phone item.  It's a plain MTS item from our pack; the behaviour comes from a mixin
 * into {@link ItemItem} that forwards here when the item is the phone.
 * <p>
 * Shift-right-click a vehicle to register it.  Right-click anywhere else to open the phone menu.
 * Registered vehicles are stored on the phone stack, so the phone can be handed to someone else.
 */
public final class VehiclePhone {
    public static final String SYSTEM_NAME = "vehicle_phone";
    /**
     * Vehicle constant that makes an aircraft fly in when called, even if it doesn't expose
     * {@link #VAR_HOVER} and {@link #VAR_STAY_ON}.  Aircraft that can't fly in are teleported to the ground instead.
     **/
    public static final String CONSTANT_AIRCRAFT_ALLOWED = "phone_summon_aircraft";
    /**Variables an aircraft needs, like the Batwing's, to fly in, hover over the player and land.**/
    static final String VAR_HOVER = "hover";
    static final String VAR_STAY_ON = "stay_on";
    /**Vehicle variable for how far away it appears before driving in, in blocks.  0 uses the default.**/
    public static final String VAR_SUMMON_DISTANCE = "phone_summon_distance";
    static final int MAX_VEHICLES = 8;
    private static final String DATA_VEHICLES = "phoneVehicles";
    private static final String ENTRY_SEPARATOR = ";";

    private VehiclePhone() {
    }

    public static boolean isPhone(AItemBase item) {
        return item instanceof ItemItem && BatPatchMod.MODID.equals(((ItemItem) item).definition.packID) && SYSTEM_NAME.equals(((ItemItem) item).definition.systemName);
    }

    /**Shift-right-click on a vehicle registers it.  Anything else goes through to normal clicking.**/
    public static CallbackType onEntityInteraction(AEntityE_Interactable<?> entity, IWrapperPlayer player, boolean rightClick) {
        if (!rightClick || !player.isSneaking()) {
            return CallbackType.SKIP;
        }
        if (entity.world.isClient()) {
            return CallbackType.NONE;
        }
        EntityVehicleF_Physics vehicle = entity instanceof EntityVehicleF_Physics ? (EntityVehicleF_Physics) entity : (entity instanceof APart ? ((APart) entity).vehicleOn : null);
        if (vehicle == null) {
            return CallbackType.NONE;
        }
        if (!canControl(player, vehicle)) {
            message(player, "You need this vehicle's key to register it.");
            return CallbackType.NONE;
        }

        IWrapperItemStack stack = player.getHeldStack();
        List<Entry> entries = getEntries(stack);
        String name = getName(vehicle);
        entries.removeIf(entry -> entry.vehicleID.equals(vehicle.uniqueUUID));
        if (entries.size() >= MAX_VEHICLES) {
            message(player, "This phone is full.  Remove a vehicle first.");
            return CallbackType.NONE;
        }
        entries.add(new Entry(vehicle.uniqueUUID, name));
        setEntries(stack, entries);
        VehicleTracker.update(vehicle);
        message(player, "Registered " + name + ".");
        return CallbackType.NONE;
    }

    /**Right-click opens the phone menu on the client.**/
    public static boolean onUsed(AWrapperWorld world, IWrapperPlayer player) {
        if (world.isClient()) {
            new GUIVehiclePhone(getEntries(player.getHeldStack()));
        }
        return true;
    }

    /**
     * True if the player may register and call this vehicle: it has no key, or they're
     * carrying its key, or they're an operator.
     */
    static boolean canControl(IWrapperPlayer player, EntityVehicleF_Physics vehicle) {
        if (vehicle.keyUUID == null || player.isOP()) {
            return true;
        }
        for (int i = 0; i < player.getInventory().getSize(); ++i) {
            IWrapperNBT data = player.getInventory().getStack(i).getData();
            if (data != null && vehicle.keyUUID.equals(data.getUUID(ItemItem.KEY_UUID_TAG))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if this aircraft can fly itself over to the player: it has the
     * {@link #CONSTANT_AIRCRAFT_ALLOWED} constant, or it or one of its parts exposes both
     * {@link #VAR_HOVER} and {@link #VAR_STAY_ON} as custom variables or keybinds.
     */
    static boolean canFlyIn(EntityVehicleF_Physics vehicle) {
        if (vehicle.getOrCreateVariable(CONSTANT_AIRCRAFT_ALLOWED).isActive) {
            return true;
        }
        Set<String> variables = new HashSet<>();
        addControlVariables(vehicle.definition, variables);
        for (APart part : vehicle.allParts) {
            addControlVariables(part.definition, variables);
        }
        return variables.contains(VAR_HOVER) && variables.contains(VAR_STAY_ON);
    }

    private static void addControlVariables(AJSONInteractableEntity definition, Set<String> variables) {
        if (definition.rendering != null && definition.rendering.customVariables != null) {
            variables.addAll(definition.rendering.customVariables);
        }
        if (definition.customKeybinds != null) {
            for (JSONCustomKeybind keybind : definition.customKeybinds) {
                if (keybind.action != null && keybind.action.variable != null) {
                    variables.add(keybind.action.variable);
                }
            }
        }
    }

    static String getName(EntityVehicleF_Physics vehicle) {
        return vehicle.subDefinition.name != null ? vehicle.subDefinition.name : vehicle.definition.general.name;
    }

    static List<Entry> getEntries(IWrapperItemStack stack) {
        List<Entry> entries = new ArrayList<>();
        IWrapperNBT data = stack.getData();
        if (data != null) {
            for (String value : data.getStrings(DATA_VEHICLES)) {
                int split = value.indexOf(ENTRY_SEPARATOR);
                if (split > 0) {
                    try {
                        entries.add(new Entry(UUID.fromString(value.substring(0, split)), value.substring(split + 1)));
                    } catch (IllegalArgumentException e) {
                        //Corrupt entry, drop it.
                    }
                }
            }
        }
        return entries;
    }

    static void setEntries(IWrapperItemStack stack, List<Entry> entries) {
        IWrapperNBT data = stack.getData();
        if (data == null) {
            data = InterfaceManager.coreInterface.getNewNBTWrapper();
        }
        List<String> values = new ArrayList<>();
        entries.forEach(entry -> values.add(entry.vehicleID + ENTRY_SEPARATOR + entry.name));
        data.setStrings(DATA_VEHICLES, values);
        stack.setData(data);
    }

    static void message(IWrapperPlayer player, String text) {
        player.sendPacket(new PacketPlayerChatMessage(player, LanguageSystem.SYSTEM_DEBUG, "[Phone] " + text));
    }

    static final class Entry {
        final UUID vehicleID;
        final String name;

        Entry(UUID vehicleID, String name) {
            this.vehicleID = vehicleID;
            this.name = name;
        }
    }
}
