package acup.gunfeel;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;

// A dev scenario for looking (gradle runClient -Pscenario=look): in ShootScenario's flat world a wall of stone, glass
// and iron bars (stone behind them) ahead and a zombie that stands still to the side (under a stone roof, so the sun does not burn it). The
// player fires gun_maresleg and a burst of gun_am180 at the wall, then turns to the zombie with F3+B on and fires at
// it. The game saves screenshots at the moments to look at (run/client/screenshots/look-*.png): the HUD, the tracers
// in flight (look-sky-3: a shot into the sky seen from aside), the shots at the wall and their holes, the zombie's
// model outline with its head zone. Nothing is checked: the pictures are for eyes.
final class LookScenario {
    private LookScenario() {
    }

    private static final int BUILD = 5, GIVE = 40, HUD = 60, SKY = 70, SHOT = 100, HOLES = 140, SWITCH = 160, BURST = 180,
            BURST_TICKS = 20, HOLES2 = 240, TURN = 260, ZOMBIE = 280, AT_ZOMBIE = 300, DONE = 340;

    // the zombie's place relative to the player's feet: 3 to the left (+x, the player looks along +z), 7 ahead
    private static final int ZX = 3, ZZ = 7;

    static void step(Minecraft mc, int t) {
        if (t == BUILD) {
            // with -Dntmgunfeel.trace (runClient -Pscenario sets it) the run is written too: what the client got
            Trace.recording = true;
            final int px = (int) Math.floor(mc.thePlayer.posX), py = (int) Math.floor(mc.thePlayer.boundingBox.minY),
                    pz = (int) Math.floor(mc.thePlayer.posZ);
            ShootScenario.onServer(() -> build(px, py, pz));
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> {
                ShootScenario.give(0, "gun_maresleg");
                ShootScenario.give(1, "gun_am180");
            });
            mc.thePlayer.inventory.currentItem = 0;
        } else if (t == HUD) {
            shot(mc, "hud");
        } else if (t == SKY - 1) {
            // over the wall, into the sky: the tracer flies for ticks instead of a part of one
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -30F;
        } else if (t == SKY) {
            ShootScenario.trigger(true, mc);
        } else if (t > SKY && t <= SKY + 5) {
            if (t == SKY + 2) {
                ShootScenario.trigger(false, mc);
                // the bullets fly on along the shot; seen from 40 degrees aside, the tracers cross the picture
                // (straight ahead they point away from the eye and shrink to a dot at the crosshair)
                mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 40F;
                mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -20F;
            }
            shot(mc, "sky-" + (t - SKY));
        } else if (t == SHOT - 5) {
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        } else if (t == SHOT) {
            ShootScenario.trigger(true, mc);
        } else if (t == SHOT + 1) {
            shot(mc, "wall-1");
        } else if (t == SHOT + 2) {
            ShootScenario.trigger(false, mc);
            shot(mc, "wall-2");
        } else if (t == HOLES) {
            shot(mc, "holes");
        } else if (t == SWITCH) {
            mc.thePlayer.inventory.currentItem = 1;
        } else if (t == BURST) {
            ShootScenario.trigger(true, mc);
        } else if (t == BURST + 5 || t == BURST + 12) {
            shot(mc, "burst-" + (t - BURST));
        } else if (t == BURST + BURST_TICKS) {
            ShootScenario.trigger(false, mc);
        } else if (t == HOLES2) {
            shot(mc, "holes-burst");
        } else if (t == TURN) {
            // F3+B, and the camera on the zombie's chest
            RenderManager.debugBoundingBox = true;
            double dx = ZX + .5 - (mc.thePlayer.posX - Math.floor(mc.thePlayer.posX)), dz = ZZ + .5 - (mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ));
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(Math.atan2(1.62 - 1.2, Math.sqrt(dx * dx + dz * dz)));
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        } else if (t == ZOMBIE) {
            shot(mc, "zombie-f3b");
        } else if (t == AT_ZOMBIE) {
            mc.thePlayer.inventory.currentItem = 0;
        } else if (t == AT_ZOMBIE + 10) {
            ShootScenario.trigger(true, mc);
        } else if (t == AT_ZOMBIE + 11) {
            shot(mc, "zombie-shot");
        } else if (t == AT_ZOMBIE + 12) {
            ShootScenario.trigger(false, mc);
        } else if (t == AT_ZOMBIE + 20) {
            shot(mc, "zombie-after");
        } else if (t == DONE) {
            RenderManager.debugBoundingBox = false;
            GunFeel.LOGGER.info("Scenario look done: screenshots in {}", new File(mc.mcDataDir, "screenshots"));
            mc.shutdown();
        }
    }

    // the integrated server: noon, the wall 10 ahead (stone, its middle column glass), the zombie and its roof
    private static void build(int px, int py, int pz) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        w.setWorldTime(6000);
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy < 4; dy++) {
                // the column right of the glass: iron bars before stone, holes go through the gaps (ModelCells)
                w.setBlock(px + dx, py + dy, pz + 10, dx == 0 ? Blocks.glass : dx == -1 ? Blocks.iron_bars : Blocks.stone, 0, 3);
                if (dx == -1) w.setBlock(px + dx, py + dy, pz + 11, Blocks.stone, 0, 3);
            }
        w.setBlock(px + ZX, py + 3, pz + ZZ, Blocks.stone, 0, 3);
        EntityZombie z = new EntityZombie(w);
        z.setLocationAndAngles(px + ZX + .5, py, pz + ZZ + .5, 180F, 0F);
        z.rotationYawHead = z.renderYawOffset = 180F;
        z.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
        z.func_110163_bv(); // never despawns
        w.spawnEntityInWorld(z);
        GunFeel.LOGGER.info("Scenario look: wall at z {}, zombie at {} {} {}", pz + 10, px + ZX, py, pz + ZZ);
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, "look-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        GunFeel.LOGGER.info("Scenario look: screenshot look-{}.png", name);
    }
}
