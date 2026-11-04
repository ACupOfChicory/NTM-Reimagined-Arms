package acup.gunfeel;

import com.hbm.client.Keybinds;
import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.HbmKeybinds.EnumKeybind;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.mixin.mc.LocalPlayerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// clickAim: NTM builds a bullet from the turn of the player the server has, and the server hears the turn only with
// the move packet the client sends once a tick (once a second at tick rate 1), while NTM sends a gun key at once, in
// the frame of the click. A turn and a click in one frame shot along the old turn; a burst lagged the camera by a tick.
// Now the client sends vanilla's turn packet with the turn the player sees just before NTM's key (both keys and
// releases: some guns fire on the release), and after every frame while a fire key is held. The server takes the
// packets of one connection in order, so the first bullet goes where the player looked when clicking and a burst
// follows the camera to a frame. The client sets the turn in vanilla anyway: nothing new is trusted to it.
public final class ClickAim {
    private ClickAim() {
    }

    // NTM is about to send a gun key going down or up
    public static void gunKey() {
        if (on()) send(Minecraft.getInstance().player);
    }

    // after a frame is drawn: while a fire key is held the server gets the turn of every frame that turned
    public static void frame() {
        if (!on()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !Keybinds.isGunHeld()) return;
        HbmPlayerProps props = HbmPlayerProps.getData(player);
        if (props.getKeyPressed(EnumKeybind.GUN_PRIMARY) || props.getKeyPressed(EnumKeybind.GUN_SECONDARY)) send(player);
    }

    static boolean on() {
        return Config.enabled && Config.clickAim && Feature.CLICK_AIM.on();
    }

    private static void send(LocalPlayer player) {
        if (player == null || player.connection == null) return;
        LocalPlayerAccess sent = (LocalPlayerAccess) player;
        float yRot = player.getYRot(), xRot = player.getXRot();
        if (!changed(yRot, xRot, sent.gunfeel$yRotLast(), sent.gunfeel$xRotLast())) return;
        // ground and collision as the server last heard them: only the turn changes
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yRot, xRot, sent.gunfeel$lastOnGround(), sent.gunfeel$lastHorizontalCollision()));
        // vanilla's own record of what it sent, so its next tick does not send the same turn again
        sent.gunfeel$setYRotLast(yRot);
        sent.gunfeel$setXRotLast(xRot);
    }

    // vanilla's test in LocalPlayer.sendPosition: any change of the turn is sent
    public static boolean changed(float yRot, float xRot, float yRotLast, float xRotLast) {
        return yRot != yRotLast || xRot != xRotLast;
    }
}
