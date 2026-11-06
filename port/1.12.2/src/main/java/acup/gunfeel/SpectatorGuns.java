package acup.gunfeel;

import acup.gunfeel.compat.Feature;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * port: 26.2's SpectatorGuns on 1.12.2. spectatorNoGuns (server, ntm-gunfeel-server.properties, on by default): a
 * player in spectator mode does not use an NTM gun. NTM CE's bug, as NTM's on 26.2: its server takes the gun keys of
 * any player (KeybindPacket, HbmKeybindsServer.onPressedServer, ItemGunBaseNT.handleKeybind) and its client draws the
 * gun's crosshair and ammo counter whatever the game mode, so a spectator fired and hurt mobs.
 *
 * <p>Server: the spectator's gun keys and ammo choices do nothing, the gun is put away as one out of the hand (no
 * reload, burst or swing goes on), and the keys held when the mode changed are let go without their release actions
 * (MixinSpectatorGun, AmmoSelectPayload, AmmoLockPayload). Client: the gun keys are not pressed, and neither NTM's HUD
 * of the gun nor the mod's corner HUD and ammo wheel are drawn (MixinSpectatorKeys, MixinSpectatorHud, AmmoLockKey).
 */
public final class SpectatorGuns {
    public static final String KEY = "spectatorNoGuns";

    private SpectatorGuns() {
    }

    /** This entity is a spectator who may not use guns now. */
    public static boolean blocks(Entity entity) {
        return entity instanceof EntityPlayer && ((EntityPlayer) entity).isSpectator() && Feature.SPECTATOR.on() && ReaimTimes.flag(KEY, true);
    }
}
