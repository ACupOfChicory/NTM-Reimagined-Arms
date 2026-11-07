package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's aim: an AI soldier's eye and hand (AiAim, a regular) against the same gun in a perfect hand (at once
 * exactly where the bullet meets the target, always firing), at 10, 30 and 60 blocks, on a target standing, sprinting
 * across, strafing in a steady rhythm, strafing unevenly and walking to and fro. Both aim down the sights, where the
 * gun's spread is nearly none: what is counted is the hand. The minds are off: each shooter is told its own target, in
 * lanes side by side, three for the AI and three for the perfect hand. Hits are counted by who shot them.
 */
final class AiCheckAim {
    enum Motion {
        STAND("standing"), RUN("sprinting across"), STEADY("strafing, 10 ticks a side"), UNEVEN("strafing, 6..14 ticks a side"),
        TO_AND_FRO("walking to and fro");

        final String what;

        Motion(String what) {
            this.what = what;
        }
    }

    static final int X0 = 136, Z0 = 4, LANE = 30, LANES = 6, LENGTH = 76, RUN_TICKS = 300, CEASE = 280;
    static final double[] DIST = {10, 30, 60};
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    /** the bot's share of the perfect hand's hits it must reach, per motion; UNEVEN only shown */
    static final double[] AT_LEAST = {0.9, 0.8, 0.5, 0, 0.8};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int motion = -1, ticks;
    private final Mob[] shooters = new Mob[LANES], targets = new Mob[LANES];
    private final AiBot[] bots = new AiBot[LANES];
    private final int[] hits = new int[LANES], shots = new int[LANES], loaded = new int[LANES], first = new int[LANES];
    /** the target's places for this motion, per distance */
    private final double[][][] tracks = new double[3][][];
    private static AiCheckAim counting;

    AiCheckAim(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    // lanes 0..2: the AI at 10, 30, 60 blocks; 3..5: the perfect hand
    private static boolean perfect(int lane) {
        return lane >= 3;
    }

    private static double dist(int lane) {
        return DIST[lane % 3];
    }

    private double laneX(int lane) {
        return X0 + LANE / 2 + lane * LANE + 0.5;
    }

    boolean tick() {
        if (motion < 0) {
            setup();
            return motion >= Motion.values().length;
        }
        ticks++;
        for (int lane = 0; lane < LANES; lane++) step(lane);
        if (ticks >= RUN_TICKS) next();
        return motion >= Motion.values().length;
    }

    private void setup() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        int x1 = X0 + LANES * LANE;
        for (int cx = X0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= (Z0 + LENGTH) >> 4; cz++) level.setChunkForced(cx, cz, true);
        for (int x = X0; x < x1; x++)
            for (int z = Z0; z < Z0 + LENGTH; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), stone, Block.UPDATE_CLIENTS);
                for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(x, y + h, z), air, Block.UPDATE_CLIENTS);
            }
        var gun = BuiltInRegistries.ITEM.getValue(GUN);
        for (int lane = 0; lane < LANES; lane++) {
            Vec3 at = new Vec3(laneX(lane), y, Z0 + 6.5);
            List<Mob> made = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, gun);
            if (made.isEmpty()) {
                check.check("the aim check: a shooter spawns", false);
                motion = Motion.values().length;
                return;
            }
            Mob s = made.get(0);
            s.setPos(at.x, at.y, at.z);
            health(s);
            shooters[lane] = s;
            bots[lane] = AiBots.get(s);
            if (bots[lane] != null) {
                bots[lane].brain = null;
                bots[lane].gunner.ammo.setInfinite();
            }
            Mob t = (Mob) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"))
                    .create(level, EntitySpawnReason.COMMAND);
            t.setPos(at.x, y, at.z + dist(lane));
            t.addTag(AiBots.TARGET_TAG);
            t.setNoGravity(true);
            health(t);
            level.addFreshEntity(t);
            targets[lane] = t;
        }
        AiBots.refreshTargets(level);
        if (counting == null) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
            AiCheckAim c = counting;
            if (c != null) c.hurt(e);
        });
        counting = this;
        next();
    }

    private static void health(Mob m) {
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
    }

    private void hurt(LivingIncomingDamageEvent e) {
        for (int lane = 0; lane < LANES; lane++)
            if (e.getEntity() == targets[lane] && e.getSource().getEntity() == shooters[lane]) {
                hits[lane]++;
                if (first[lane] < 0) first[lane] = ticks;
            }
    }

    /** the place of the target a tick apart for this motion, from its lane's spot: across (x) or along (z) */
    private static double[][] track(Motion m, double d) {
        int n = RUN_TICKS + 40;
        double[][] out = new double[n][2];
        Random r = new Random(42);
        double p = m == Motion.RUN ? -7 : 0, v = 0;
        int side = 1, left = m == Motion.RUN ? 50 : m == Motion.TO_AND_FRO ? (int) Math.round(d / 2 / 0.2158) : 10;
        for (int t = 0; t < n; t++) {
            out[t][m == Motion.TO_AND_FRO ? 1 : 0] = p;
            double push = switch (m) {
                case STAND -> 0;
                case RUN -> 0.1274 * side;
                case TO_AND_FRO -> -0.098 * side;
                default -> 0.098 * side;
            };
            if (m != Motion.STAND && --left == 0) {
                side = -side;
                left = switch (m) {
                    case RUN -> 50;
                    case TO_AND_FRO -> (int) Math.round(d / 2 / 0.2158);
                    case UNEVEN -> 6 + r.nextInt(9);
                    default -> 10;
                };
            }
            v = 0.546 * v + push;
            p += v;
        }
        return out;
    }

    private Vec3 spot(int lane, double t) {
        double[][] tr = tracks[lane % 3];
        int i = (int) Math.max(0, Math.min(tr.length - 2, Math.floor(t)));
        double f = Math.max(0, Math.min(1, t - i));
        double ax = tr[i][0] + (tr[i + 1][0] - tr[i][0]) * f, az = tr[i][1] + (tr[i + 1][1] - tr[i][1]) * f;
        return new Vec3(laneX(lane) + ax, y, Z0 + 6.5 + dist(lane) + az);
    }

    private void next() {
        if (motion >= 0) report();
        motion++;
        ticks = 0;
        if (motion >= Motion.values().length) {
            finish();
            return;
        }
        for (int i = 0; i < 3; i++) tracks[i] = track(Motion.values()[motion], DIST[i]);
        for (int lane = 0; lane < LANES; lane++) {
            hits[lane] = shots[lane] = 0;
            first[lane] = -1;
            loaded[lane] = bots[lane] == null ? 0 : bots[lane].gunner.loaded();
        }
    }

    private void step(int lane) {
        Mob s = shooters[lane], t = targets[lane];
        AiBot bot = bots[lane];
        if (s == null || t == null || bot == null) return;
        Vec3 p = spot(lane, ticks);
        t.setPos(p.x, p.y, p.z);
        t.setDeltaMovement(Vec3.ZERO);
        t.invulnerableTime = 0;
        t.setHealth(t.getMaxHealth());
        s.setHealth(s.getMaxHealth());
        // down the sights, both
        ItemStack stack = s.getMainHandItem();
        if (stack.getItem() instanceof ItemGunBaseNT gun && !ItemGunBaseNT.getIsAiming(stack))
            gun.handleKeybind(s, null, stack, EnumKeybind.GUN_TERTIARY, true);
        AiGunner g = bot.gunner;
        double chest = t.getBbHeight() * 0.6;
        if (perfect(lane)) {
            // the bullet leaves next tick and moves a tick at a time: it meets the target where the target is then
            int k = (int) Math.ceil(dist(lane) / g.bulletSpeed());
            Vec3 at = spot(lane, ticks + k).add(0, chest, 0);
            g.aim(at);
            g.exact = at;
        } else g.track(t.getId(), p.add(0, chest, 0), level.getGameTime());
        g.fire(ticks < CEASE);
        int now = g.loaded();
        if (now >= 0 && now < loaded[lane]) shots[lane] += loaded[lane] - now;
        loaded[lane] = now;
    }

    private void report() {
        Motion m = Motion.values()[motion];
        StringBuilder line = new StringBuilder("     aim, " + m.what + ", the AI / a perfect hand, hits of shots:");
        boolean ok = true;
        for (int i = 0; i < 3; i++) {
            double ai = shots[i] == 0 ? 0 : (double) hits[i] / shots[i], best = shots[i + 3] == 0 ? 0 : (double) hits[i + 3] / shots[i + 3];
            line.append(String.format(Locale.ROOT, " %.0f blocks %d/%d (%.0f%%, first at %d) / %d/%d (%.0f%%);", DIST[i], hits[i], shots[i], 100 * ai, first[i],
                    hits[i + 3], shots[i + 3], 100 * best));
            ok &= shots[i] > 0 && ai >= AT_LEAST[motion] * best;
        }
        check.out(line.toString());
        if (AT_LEAST[motion] > 0)
            check.check(String.format(Locale.ROOT, "%s: the AI hits at least %.0f%% as often as a perfect hand", m.what, 100 * AT_LEAST[motion]), ok);
    }

    private void finish() {
        counting = null;
        for (int lane = 0; lane < LANES; lane++) {
            if (shooters[lane] != null) shooters[lane].discard();
            if (targets[lane] != null) targets[lane].discard();
        }
        AiBots.refreshTargets(level);
    }
}
