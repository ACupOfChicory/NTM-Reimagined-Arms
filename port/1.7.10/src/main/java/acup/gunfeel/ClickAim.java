package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.mixins.early.PlayerSPAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;

// port: 26.2's ClickAim (e809204), cut to what 1.7.10's server allows. clickAim: NTM builds a bullet from the turn of
// the player the server has, and the server hears the turn only with the move packet the client sends once a tick,
// while NTM sends a gun key at once (HbmKeybinds.handleProps). A turn and a click before the next tick shot along the
// old turn. Now, just before NTM's key with a gun in hand (MixinClickAim; keys and releases), the client sends its
// tick's move packet early, as vanilla's turn packet with the turn the player sees, and skips the packet of its next
// sendMotionUpdates (MixinClickAimMove). The server takes the packets of one connection in order: the first bullet
// goes where the player looked when clicking.
// Not as 26.2: 1.7.10's server runs a tick of the player for every move packet (NetHandlerPlayServer.processPlayer,
// onUpdateEntity), so a packet more would be a tick more (a gun firing faster, hunger, potions): the early packet
// stands for the tick's own, and the turn is not sent every frame of a burst. A burst follows the turn of the last
// tick, as in NTM. The client sets the turn in vanilla anyway: nothing new is trusted to it.
public final class ClickAim {
    private ClickAim() {
    }

    // the tick's move packet has gone early: vanilla's next one is skipped
    private static boolean early;

    // NTM is about to send one of its keys going down or up (port: any NTM key; the turn goes with a gun in hand)
    public static void gunKey() {
        if (!on()) return;
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        // riding, vanilla sends the turn from onUpdate, not sendMotionUpdates: nothing to stand in for
        if (gunHeld(player) && player.ridingEntity == null && !early) send(player);
    }

    // vanilla's sendMotionUpdates is about to send the tick's move packet: false when it went early
    public static boolean sendMove() {
        if (!early) return true;
        early = false;
        return false;
    }

    static boolean on() {
        return Config.enabled && Config.clickAim && Feature.CLICK_AIM.on();
    }

    private static boolean gunHeld(EntityClientPlayerMP player) {
        if (player == null) return false;
        ItemStack held = player.getHeldItem();
        return held != null && held.getItem() instanceof ItemGunBaseNT;
    }

    private static void send(EntityClientPlayerMP player) {
        if (player.sendQueue == null) return;
        PlayerSPAccess sent = (PlayerSPAccess) player;
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        if (!changed(yaw, pitch, sent.gunfeel$lastYaw(), sent.gunfeel$lastPitch())) return;
        // ground as the server last heard it: only the turn changes; the tick's movement goes with the next packet
        player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, sent.gunfeel$lastOnGround()));
        // vanilla's own record of what it sent, so its next tick does not send the same turn again
        sent.gunfeel$setLastYaw(yaw);
        sent.gunfeel$setLastPitch(pitch);
        early = true;
    }

    // vanilla's test in EntityClientPlayerMP.sendMotionUpdates: any change of the turn is sent
    public static boolean changed(float yaw, float pitch, float lastYaw, float lastPitch) {
        return yaw != lastYaw || pitch != lastPitch;
    }
}
