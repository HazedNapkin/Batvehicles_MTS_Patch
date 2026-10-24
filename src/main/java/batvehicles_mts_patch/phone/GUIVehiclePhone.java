package batvehicles_mts_patch.phone;

import java.util.List;

import batvehicles_mts_patch.phone.PacketPhoneAction.Action;
import minecrafttransportsimulator.baseclasses.ColorRGB;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.guis.components.AGUIBase;
import minecrafttransportsimulator.guis.components.GUIComponentButton;
import minecrafttransportsimulator.guis.components.GUIComponentLabel;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.rendering.RenderText.TextAlignment;

/**
 * The phone menu.  Lists the vehicles registered on the phone, with buttons to call one,
 * stop a call in progress, or remove it from the phone.  Client only.
 */
public class GUIVehiclePhone extends AGUIBase {
    private static final int ROW_TOP = 34;
    private static final int ROW_HEIGHT = 18;

    private final List<VehiclePhone.Entry> entries;
    private GUIComponentLabel[] distanceLabels;

    public GUIVehiclePhone(List<VehiclePhone.Entry> entries) {
        super();
        this.entries = entries;
    }

    @Override
    public void setupComponents() {
        super.setupComponents();
        addComponent(new GUIComponentLabel(guiLeft + getWidth() / 2, guiTop + 10, ColorRGB.BLACK, "Vehicle Phone", TextAlignment.CENTERED, 1.5F));
        if (entries.isEmpty()) {
            addComponent(new GUIComponentLabel(guiLeft + 15, guiTop + ROW_TOP, ColorRGB.BLACK, "No vehicles registered.  Shift-right-click a vehicle with the phone to add it.", TextAlignment.LEFT_ALIGNED, 1.0F, 226));
            distanceLabels = new GUIComponentLabel[0];
            return;
        }

        distanceLabels = new GUIComponentLabel[entries.size()];
        for (int i = 0; i < entries.size(); ++i) {
            VehiclePhone.Entry entry = entries.get(i);
            int rowY = guiTop + ROW_TOP + i * ROW_HEIGHT;
            addComponent(new GUIComponentLabel(guiLeft + 12, rowY + 5, ColorRGB.BLACK, entry.name, TextAlignment.LEFT_ALIGNED, 1.0F, 100));
            addComponent(distanceLabels[i] = new GUIComponentLabel(guiLeft + 115, rowY + 5, ColorRGB.DARK_GRAY, "", TextAlignment.LEFT_ALIGNED, 1.0F));
            addComponent(new ActionButton(guiLeft + 150, rowY, 36, "Call", Action.SUMMON, entry));
            addComponent(new ActionButton(guiLeft + 188, rowY, 36, "Stop", Action.STOP, entry));
            addComponent(new ActionButton(guiLeft + 226, rowY, 18, "X", Action.REMOVE, entry));
        }
        addComponent(new GUIComponentLabel(guiLeft + 12, guiTop + getHeight() - 16, ColorRGB.DARK_GRAY, "Shift-right-click a vehicle to add it.", TextAlignment.LEFT_ALIGNED, 0.75F));
    }

    @Override
    public void setStates() {
        super.setStates();
        if (distanceLabels == null) {
            return;
        }
        //Show distance for vehicles this client can see.  Others are too far away to be loaded.
        IWrapperPlayer player = InterfaceManager.clientInterface.getClientPlayer();
        for (int i = 0; i < distanceLabels.length; ++i) {
            EntityVehicleF_Physics vehicle = player.getWorld().getEntity(entries.get(i).vehicleID);
            distanceLabels[i].text = vehicle != null ? (int) vehicle.position.distanceTo(player.getPosition()) + "m" : "far";
        }
    }

    private class ActionButton extends GUIComponentButton {
        private final Action action;
        private final VehiclePhone.Entry entry;

        ActionButton(int x, int y, int width, String text, Action action, VehiclePhone.Entry entry) {
            super(GUIVehiclePhone.this, x, y, width, ROW_HEIGHT - 2, text);
            this.action = action;
            this.entry = entry;
        }

        @Override
        public void onClicked(boolean leftSide) {
            IWrapperPlayer player = InterfaceManager.clientInterface.getClientPlayer();
            InterfaceManager.packetInterface.sendToServer(new PacketPhoneAction(player, action, entry.vehicleID));
            if (action != Action.STOP) {
                close();
            }
        }
    }
}
