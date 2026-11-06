package acup.gunfeel;

import acup.gunfeel.compat.Feature;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * spectatorNoGuns (server, ntm-gunfeel-server.properties, on by default): a player in spectator mode does not use an
 * NTM gun. NTM's bug: its server takes the gun keys of any player (KeybindPayload.handleServer, ItemGunBaseNT
 * .handleKeybind) and its client draws the gun's crosshair, ammo counter and scope whatever the game mode, so a
 * spectator fired and hurt mobs (only the blocks were safe, where the mod's bullets ask Player.mayBuild).
 *
 * <p>Server: the spectator's gun keys and ammo choices do nothing, the gun is put away as one out of the hand (no
 * reload, burst or swing goes on), and the keys held when the mode changed are let go without their release actions
 * (MixinSpectatorGun, GunFeelMod). Client: the fire keys are not sent, and neither NTM's HUD of the gun nor the mod's
 * corner HUD and ammo wheel are drawn (MixinSpectatorKeys, MixinSpectatorHud, AmmoLockKey). On a connected client
 * the server's file counts (ServerSettings); a server of an older build has no such key, and the client takes its
 * own, but only the server's half stops a key held as the mode changed.
 */
public final class SpectatorGuns {
    public static final String KEY = "spectatorNoGuns";

    private SpectatorGuns() {
    }

    /** This entity is a spectator who may not use guns now. */
    public static boolean blocks(Entity entity) {
        return entity instanceof Player player && player.isSpectator() && Feature.SPECTATOR.on() && ReaimTimes.flag(KEY, true);
    }
}
