package acup.gunfeel;

import com.hbm.capability.HbmCapability;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.GameType;

// port: the client's half of 26.2's SpectatorStand (-Pscenario=spectator, ShootScenario's flat world; the server's half
// is SpectatorCheck). The player holds gun_uzi with the fire key held a few ticks: in creative, as a spectator, and back
// in creative. Counted: the frames NTM was asked for the gun's HUD and the frames it was not (MixinSpectatorHud),
// whether the client sent the press (NTM keeps the state it sent in the player's capability, MixinSpectatorKeys) and the
// rounds the server's magazine lost. Lines "Scenario spectator: ..." in the log, then the game quits.
public final class SpectatorScenario {
    private SpectatorScenario() {
    }

    private static final int GIVE = 20, SPECTATOR = 100, BACK = 180, DONE = 280;
    private static final int[] PRESS = { 60, 140, 240 };
    private static final String[] PHASE = { "creative", "spectator", "creative again" };
    private static final int HOLD = 5, READ = 12;

    private static int asked, skipped, ok, failed;
    private static boolean sent;
    private static volatile int roundsBefore = -1, roundsAfter = -1;

    public static boolean on() {
        return "spectator".equals(ShootScenario.NAME);
    }

    // MixinSpectatorHud: NTM was asked for a held NTM gun's HUD this frame, or not
    public static void hud(boolean drawn) {
        if (drawn) asked++;
        else skipped++;
    }

    private static int rounds() {
        EntityPlayerMP p = (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
        ItemStack stack = p.getHeldItemMainhand();
        if (!(stack.getItem() instanceof ItemGunBaseNT)) return -1;
        IMagazine<?> mag = AmmoLock.primary(stack);
        return mag == null ? -1 : mag.getAmount(stack, p.inventory);
    }

    private static void mode(GameType g) {
        ShootScenario.onServer(() -> ((EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList()
                .getPlayers().get(0)).setGameType(g));
    }

    private static void check(String what, boolean pass, Object got) {
        if (pass) ok++;
        else failed++;
        GunFeel.LOGGER.info("Scenario spectator: {} {} (got {})", pass ? "PASS" : "FAIL", what, got);
    }

    static void step(Minecraft mc, int t) {
        if (t == 0) {
            mc.player.rotationPitch = mc.player.prevRotationPitch = 0F;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> ShootScenario.give(0, "gun_uzi"));
            mc.player.inventory.currentItem = 0;
        } else if (t == SPECTATOR) {
            mode(GameType.SPECTATOR);
        } else if (t == BACK) {
            mode(GameType.CREATIVE);
        } else if (t == DONE) {
            GunFeel.LOGGER.info("Scenario spectator: {} PASS, {} FAIL", ok, failed);
            mc.shutdown();
        }
        for (int i = 0; i < PRESS.length; i++) {
            int p = PRESS[i];
            if (t == p - 2) ShootScenario.onServer(() -> roundsBefore = rounds());
            if (t == p) {
                asked = skipped = 0;
                ShootScenario.trigger(true, mc);
            }
            if (t == p + 2) sent = HbmCapability.getData(mc.player).getKeyPressed(EnumKeybind.GUN_PRIMARY);
            if (t == p + HOLD) ShootScenario.trigger(false, mc);
            if (t == p + READ - 2) ShootScenario.onServer(() -> roundsAfter = rounds());
            if (t == p + READ) {
                boolean spectator = i == 1;
                String got = "spectator " + mc.player.isSpectator() + ", HUD asked " + asked + " frames, skipped " + skipped + ", press sent " + sent
                        + ", magazine " + roundsBefore + " -> " + roundsAfter;
                if (spectator) check(PHASE[i] + ": no gun HUD, no press sent, no shot", mc.player.isSpectator() && asked == 0 && skipped > 0 && !sent
                        && roundsBefore >= 0 && roundsAfter == roundsBefore, got);
                else check(PHASE[i] + ": the gun HUD, the press sent, shots", !mc.player.isSpectator() && asked > 0 && skipped == 0 && sent
                        && roundsAfter >= 0 && roundsAfter < roundsBefore, got);
            }
        }
    }
}
