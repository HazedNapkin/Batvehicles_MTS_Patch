package batvehicles_mts_patch.phone;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.packets.components.APacketPlayer;

/**
 * Sent from the phone menu to the server to summon, stop or remove a registered vehicle.
 * Registered with MTS's packets by a mixin, at {@link #PACKET_INDEX}.
 */
public class PacketPhoneAction extends APacketPlayer {
    /**Well above MTS's own packets, which number about 55.**/
    public static final byte PACKET_INDEX = 120;

    private final Action action;
    private final UUID vehicleID;

    public PacketPhoneAction(IWrapperPlayer player, Action action, UUID vehicleID) {
        super(player);
        this.action = action;
        this.vehicleID = vehicleID;
    }

    public PacketPhoneAction(ByteBuf buf) {
        super(buf);
        this.action = Action.values()[buf.readByte()];
        this.vehicleID = readUUIDFromBuffer(buf);
    }

    @Override
    public void writeToBuffer(ByteBuf buf) {
        super.writeToBuffer(buf);
        buf.writeByte(action.ordinal());
        writeUUIDToBuffer(vehicleID, buf);
    }

    @Override
    protected void handle(AWrapperWorld world, IWrapperPlayer player) {
        if (!world.isClient()) {
            SummonManager.handleAction(world, player, action, vehicleID);
        }
    }

    public enum Action {
        SUMMON,
        STOP,
        REMOVE
    }
}
