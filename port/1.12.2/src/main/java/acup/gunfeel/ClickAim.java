package acup.gunfeel;

import com.hbm.capability.HbmCapability;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.mixins.early.PlayerSPAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketPlayer;

// port: 26.2's ClickAim (e809204). clickAim: NTM builds a bullet from the turn of the player the server has, and the
// server hears the turn only with the move packet the client sends once a tick, while NTM sends a gun key at once
// (HbmKeybinds.handleProps). A turn and a click before the next tick shot along the old turn; a burst lagged the
// camera by up to a tick. Now the client sends vanilla's turn packet with the turn the player sees just before NTM's
// key (MixinClickAim; keys and releases), and after every frame while a fire key is held. The server takes the packets
// of one connection in order, so the first bullet goes where the player looked when clicking and a burst follows the
// camera to a frame. The client sets the turn in vanilla anyway: nothing new is trusted to it.
public final class ClickAim {
    private ClickAim() {
    }

    // NTM is about to send one of its keys going down or up (port: any NTM key; the turn goes with a gun in hand)
    public static void gunKey() {
        if (!on()) return;
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (gunHeld(player)) send(player);
    }

    // after a frame is drawn: while a fire key is held the server gets the turn of every frame that turned
    public static void frame() {
        if (!on()) return;
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (!gunHeld(player)) return;
        HbmCapability.IHBMData props = HbmCapability.getData(player);
        if (props.getKeyPressed(EnumKeybind.GUN_PRIMARY) || props.getKeyPressed(EnumKeybind.GUN_SECONDARY)) send(player);
    }

    static boolean on() {
        return Config.enabled && Config.clickAim && Feature.CLICK_AIM.on();
    }

    private static boolean gunHeld(EntityPlayerSP player) {
        if (player == null) return false;
        ItemStack held = player.getHeldItemMainhand();
        return !held.isEmpty() && held.getItem() instanceof ItemGunBaseNT;
    }

    private static void send(EntityPlayerSP player) {
        if (player.connection == null) return;
        PlayerSPAccess sent = (PlayerSPAccess) player;
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        if (!changed(yaw, pitch, sent.gunfeel$lastYaw(), sent.gunfeel$lastPitch())) return;
        // ground as the server last heard it: only the turn changes
        player.connection.sendPacket(new CPacketPlayer.Rotation(yaw, pitch, sent.gunfeel$lastOnGround()));
        // vanilla's own record of what it sent, so its next tick does not send the same turn again
        sent.gunfeel$setLastYaw(yaw);
        sent.gunfeel$setLastPitch(pitch);
    }

    // vanilla's test in EntityPlayerSP.onUpdateWalkingPlayer: any change of the turn is sent
    public static boolean changed(float yaw, float pitch, float lastYaw, float lastPitch) {
        return yaw != lastYaw || pitch != lastPitch;
    }
}
