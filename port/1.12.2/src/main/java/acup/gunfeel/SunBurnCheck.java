package acup.gunfeel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

// port: a dev check of SunBurn without a window, on a dev server started with a file "sunburncheck" in its folder
// (run/server). Zombies and skeletons stand bare under the noon sky: without the rule the sun sets them on fire, with
// `gamerule ntmgunfeelSunBurn false` it does not, and fire set otherwise still burns them. "sunburncheck: ..." lines and
// a summary to the log, then the server stops.
final class SunBurnCheck {
    private static final int PHASE = 300;
    private static int tick, ok, failed;
    private static final List<EntityLiving> MOBS = new ArrayList<>();
    private static final boolean[] BURNT = new boolean[8];

    private SunBurnCheck() {
    }

    // GunFeelMod.serverStarted
    static void maybeRun() {
        if (!new File("sunburncheck").exists()) return;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SunBurnCheck.Ticks());
    }

    public static final class Ticks {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            try {
                step(FMLCommonHandler.instance().getMinecraftServerInstance().worlds[0]);
            } catch (RuntimeException | LinkageError x) {
                failed++;
                GunFeel.LOGGER.error("sunburncheck: crashed", x);
                finish();
            }
        }
    }

    private static void check(String what, boolean pass, Object got) {
        if (pass) ok++;
        else failed++;
        GunFeel.LOGGER.info("sunburncheck: {} {} (got {})", pass ? "ok  " : "FAIL", what, got);
    }

    private static void step(WorldServer w) {
        if (tick == 0) {
            w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            w.getGameRules().setOrCreateGameRule(SunBurn.RULE, "true");
            // a run stopped halfway leaves its mobs: bare undead that cannot be hurt and do not think, by the spawn
            for (var old : new ArrayList<>(w.loadedEntityList))
                if ((old instanceof EntityZombie || old instanceof EntitySkeleton) && old.getIsInvulnerable() && ((EntityLiving) old).isAIDisabled()
                        && old.getDistanceSq(w.getSpawnPoint()) < 400) old.setDead();
            w.setWorldTime(6000);
            w.getWorldInfo().setRaining(false);
            w.getWorldInfo().setThundering(false);
            BlockPos spawn = w.getSpawnPoint();
            for (int i = 0; i < 4; i++) {
                BlockPos at = w.getTopSolidOrLiquidBlock(spawn.add(i * 3, 0, 0));
                EntityLiving mob = i % 2 == 0 ? new EntityZombie(w) : new EntitySkeleton(w);
                mob.setLocationAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                mob.setNoAI(true);
                mob.enablePersistence();
                mob.setEntityInvulnerable(true);
                w.spawnEntity(mob);
                MOBS.add(mob);
            }
            GunFeel.LOGGER.info("sunburncheck: rule true, {} mobs at {}, sky {}", MOBS.size(), MOBS.get(0).getPosition(),
                    w.canSeeSky(MOBS.get(0).getPosition()));
        }
        tick++;
        for (int i = 0; i < MOBS.size(); i++) if (MOBS.get(i).isBurning()) BURNT[i + (tick > PHASE ? 4 : 0)] = true;
        if (tick == PHASE) {
            int n = 0;
            for (int i = 0; i < 4; i++) if (BURNT[i]) n++;
            check("with the rule true (as without it) the sun sets each bare zombie and skeleton on fire", n == 4, n + "/4");
            w.getGameRules().setOrCreateGameRule(SunBurn.RULE, "false");
            for (EntityLiving m : MOBS) m.extinguish();
        }
        if (tick == 2 * PHASE) {
            int n = 0;
            for (int i = 4; i < 8; i++) if (BURNT[i]) n++;
            check("with ntmgunfeelSunBurn false the sun sets none on fire", n == 0, n + "/4");
            MOBS.get(0).setFire(5);
            check("fire set otherwise still burns", MOBS.get(0).isBurning(), MOBS.get(0).isBurning());
            finish();
        }
    }

    // the dev world is left as it was: no mobs of the check, the sun burns
    private static void finish() {
        tick = Integer.MIN_VALUE;
        for (EntityLiving m : MOBS) m.setDead();
        FMLCommonHandler.instance().getMinecraftServerInstance().worlds[0].getGameRules().setOrCreateGameRule(SunBurn.RULE, "true");
        GunFeel.LOGGER.info("sunburncheck: {} ok, {} failed", ok, failed);
        FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
    }
}
