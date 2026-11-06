package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;

// Dev (-Pscenario=glass, ShootScenario's flat world): bullet holes on glass, for screenshots checked by numbers. Three
// lone blocks in a row 4 ahead of the player, a stone wall far behind them and another behind the player: NTM's
// reinforced laminate and its pane (drawn with the translucent terrain: their holes go in the late pass,
// drawLateHoles) and NTM's reinforced glass (cut-out, drawn with the solid terrain: a control), and one more laminate
// in the floor beyond them, shot from above (its hole on the top face, below the eyes and above the feet). Glass a bullet breaks
// (vanilla's, NTM's ash glass) holds no hole. The screen is taken
// without the GUI and the hand, from in front and from behind the row, before the shots and after the server has fired
// one real NTM bullet (9 mm AP) at the middle of each block: run/forge/screenshots/glass-{front,back}-{0,1}.png (the
// floor block is seen from in front only). The
// holes the client holds go to glass.txt; the game quits.
final class GlassScenario {
    private GlassScenario() {
    }

    private static final int PLACE = 40, FRONT0 = 70, BEHIND0 = 75, BACK0 = 95, AHEAD0 = 100, FIRE = 120, GAP = 10, FRONT1 = 190,
            BEHIND1 = 195, BACK1 = 215, CHECK = 220, DONE = 240;

    // the blocks, by dx from the player: laminate, its pane (along x, across the shot), reinforced glass, all at eye
    // level in the row; laminate in the floor two beyond the row
    private static final String[] BLOCKS = {"hbm:reinforced_laminate", "hbm:reinforced_laminate_pane", "hbm:reinforced_glass",
            "hbm:reinforced_laminate"};
    private static final int ROW = 4, WALL = 12, BACK_WALL = -4, BEHIND = 8;
    private static final int[] DX = {2, 0, -2, 0}, DY = {1, 1, 1, -1}, DZ = {ROW, ROW, ROW, ROW + 2};

    private static int[] feet;
    private static final List<String> lines = new ArrayList<String>();

    static void step(Minecraft mc, int t) {
        if (t == PLACE) ShootScenario.onServer(GlassScenario::place);
        if (t == PLACE + 1) {
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.particleSetting = 2;
        }
        if (t == BEHIND0 || t == BEHIND1) ShootScenario.onServer(() -> stand(true));
        if (t == AHEAD0) ShootScenario.onServer(() -> stand(false));
        for (int i = 0; i < BLOCKS.length; i++) if (t == FIRE + i * GAP) {
            final int n = i;
            ShootScenario.onServer(() -> fire(n));
        }
        if (t == FRONT0) shot(mc, "front-0");
        if (t == BACK0) shot(mc, "back-0");
        if (t == FRONT1) shot(mc, "front-1");
        if (t == BACK1) shot(mc, "back-1");
        if (t == CHECK) check();
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario glass: done");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void place() {
        EntityPlayerMP p = player();
        feet = new int[] {(int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ)};
        World w = p.world;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.setWorldTime(6000);
        for (int i = 0; i < BLOCKS.length; i++)
            Mc.setBlock(w, feet[0] + DX[i], feet[1] + DY[i], feet[2] + DZ[i], Block.getBlockFromName(BLOCKS[i]), 0, 3);
        for (int x = -8; x <= 8; x++) for (int y = 0; y <= 6; y++) {
            Mc.setBlock(w, feet[0] + x, feet[1] + y, feet[2] + WALL, Blocks.STONE, 0, 3);
            Mc.setBlock(w, feet[0] + x, feet[1] + y, feet[2] + BACK_WALL, Blocks.STONE, 0, 3);
        }
        stand(false);
        lines.add("feet " + feet[0] + "," + feet[1] + "," + feet[2]);
    }

    // in front of the row looking at it (+z), or behind it looking back (-z)
    private static void stand(boolean behind) {
        player().connection.setPlayerLocation(feet[0] + .5, feet[1], feet[2] + (behind ? BEHIND : 0) + .5, behind ? 180F : 0F, 0F);
    }

    private static void fire(int n) {
        World w = player().world;
        BulletConfig round = com.hbm.items.weapon.sedna.factory.XFactory9mm.p9_ap;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, round, 1F, 0F, 0F, 0F);
        boolean floor = DY[n] < 0;
        // the row from 2 in front of it; the floor block from 1.5 above its top
        b.setPosition(feet[0] + DX[n] + 0.5, feet[1] + 1.5, feet[2] + DZ[n] + (floor ? 0.5 : -2.0));
        if (floor) b.shoot(0, -1, 0, 1F, 0F);
        else b.shoot(0, 0, 1, 1F, 0F);
        b.damage = 30F;
        w.spawnEntity(b);
        lines.add(String.format(Locale.ROOT, "shot %s id %d at %.3f %.3f %.3f", BLOCKS[n], b.getEntityId(), b.posX, b.posY, b.posZ));
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, "glass-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        lines.add(String.format(Locale.ROOT, "screenshot glass-%s.png camera %.3f %.3f %.3f yaw %.1f", name, mc.player.posX, mc.player.posY + mc.player.getEyeHeight(),
                mc.player.posZ, mc.player.rotationYaw));
    }

    private static void check() {
        for (Tracers.HoleView h : Tracers.holesNow())
            lines.add(String.format(Locale.ROOT, "hole %d,%d,%d face %d late %s at %.3f %.3f %.3f", h.x, h.y, h.z, h.face, h.late, h.pos[0], h.pos[1], h.pos[2]));
        for (int i = 0; i < BLOCKS.length; i++)
            lines.add("block " + BLOCKS[i] + " " + Block.REGISTRY.getNameForObject(Minecraft.getMinecraft().world.getBlockState(Mc.pos(feet[0] + DX[i], feet[1] + DY[i], feet[2] + DZ[i])).getBlock()));
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "glass.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario glass: glass.txt", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario glass: {}", l);
    }
}
