package acup.gunfeel;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.handler.HbmKeybinds.EnumKeybind;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.mixin.mc.LocalPlayerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// port/continuum: src/main/java ClickAim on NTM Continuum. Continuum builds a bullet from the server's turn of the
// player too, and sends a gun key at once: HbmKeybindsClient.report sends a KeybindPayload whenever a gun key changes,
// from its end tick and from its mouse button callback. MixinClickAim sends vanilla's turn packet with the turn the
// player sees just before that payload (keys and releases), and after every frame while a fire key is held the turn
// of that frame goes too. The server takes the packets of one connection in order.
public final class ClickAim {
    private ClickAim() {
    }

    // Continuum is about to send a gun key going down or up
    public static void gunKey() {
        if (on()) send(Minecraft.getInstance().player);
    }

    // after a frame is drawn: while a fire key is held the server gets the turn of every frame that turned. Continuum's
    // client keeps what it last reported per key; with no gun in hand or a screen open it reports them released.
    public static void frame() {
        if (!on()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (HbmKeybindsClient.isHeld(EnumKeybind.GUN_PRIMARY) || HbmKeybindsClient.isHeld(EnumKeybind.GUN_SECONDARY)) send(player);
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
