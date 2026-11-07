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
 * across (and back every 50 ticks: the lanes are 30 blocks wide), strafing in a steady rhythm, strafing unevenly and
 * walking to and fro. Both aim down the sights, where the
 * gun's spread is nearly none: what is counted is the hand. The minds are off: each shooter is told its own target, in
 * lanes side by side, three for the AI and three for the perfect hand. Hits are counted by who shot them.
 *
 * <p>Rounds: a semi-automatic carbine with the recoil as the server has it, every motion; then an automatic grease gun
 * (NTM kicks it 2 up a shot) on a standing and a running target, without recoil, with it and with it three times as
 * strong (aiRecoil 3): a burst lifts the AI's view; the strong recoil makes it miss more far off and on the run (the
 * usual one it mostly takes back before the next shot, and the gun's spread counts for more there: shown, not
 * checked). The perfect hand is never kicked.
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
    /** the bot's share of the perfect hand's hits it must reach, per motion (the first round); UNEVEN only shown */
    static final double[] AT_LEAST = {0.9, 0.7, 0.5, 0, 0.8};

    /** motions with one gun at one recoil strength (NaN: the server's) */
    record Round(String what, Identifier gun, float recoil, List<Motion> motions) {
    }

    static final List<Round> ROUNDS = List.of(
            new Round("a carbine, recoil as the server has it", Identifier.fromNamespaceAndPath("hbm", "gun_carbine"), Float.NaN, List.of(Motion.values())),
            new Round("a grease gun, no recoil", Identifier.fromNamespaceAndPath("hbm", "gun_greasegun"), 0F, List.of(Motion.STAND, Motion.RUN)),
            new Round("a grease gun, recoil", Identifier.fromNamespaceAndPath("hbm", "gun_greasegun"), 1F, List.of(Motion.STAND, Motion.RUN)),
            new Round("a grease gun, recoil x3", Identifier.fromNamespaceAndPath("hbm", "gun_greasegun"), 3F, List.of(Motion.STAND, Motion.RUN)),
            new Round("an M2, no recoil", Identifier.fromNamespaceAndPath("hbm", "gun_m2"), 0F, List.of(Motion.STAND, Motion.RUN)),
            new Round("an M2, recoil", Identifier.fromNamespaceAndPath("hbm", "gun_m2"), 1F, List.of(Motion.STAND, Motion.RUN)),
            new Round("an M2, recoil x3", Identifier.fromNamespaceAndPath("hbm", "gun_m2"), 3F, List.of(Motion.STAND, Motion.RUN)));
    /** the rounds of each gun without recoil, with it and with x3: the grease gun (shown), the M2 (checked) */
    static final int GREASE = 1, M2 = 4;
    /**
     * the most the AI's view at 30 blocks may go above a standing target without recoil, and how much more with it,
     * degrees; how many fewer hits of its shots (at 30 and 60 blocks, standing and running, all together) it must have with
     * three times the recoil. With the server's recoil it is shown only: a regular takes an M2's climb back once it
     * notices, holding fire till then, so on a standing target it shoots less often rather than worse; on a running one
     * it misses a few more, too few for a check of a few hundred shots
     */
    static final double CLIMB_CALM = 0.5, CLIMB_MORE = 0.4, FEWER = 0.1;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1, motion, ticks;
    // the AI's hits of shots per round, motion and distance; the most its view went above the target (30 blocks,
    // standing) per round
    private final int[][][] aiHits = new int[ROUNDS.size()][Motion.values().length][3], aiShots = new int[ROUNDS.size()][Motion.values().length][3];
    private final double[] climb = new double[ROUNDS.size()];
    private final Mob[] shooters = new Mob[LANES], targets = new Mob[LANES];
    private final AiBot[] bots = new AiBot[LANES];
    private final int[] hits = new int[LANES], shots = new int[LANES], shotsBefore = new int[LANES], first = new int[LANES];
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
        if (round < 0) setup();
        else {
            ticks++;
            for (int lane = 0; lane < LANES; lane++) step(lane);
            if (ticks >= RUN_TICKS) next();
        }
        return round >= ROUNDS.size();
    }

    private Motion motion() {
        return ROUNDS.get(round).motions().get(motion);
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
        var gun = BuiltInRegistries.ITEM.getValue(ROUNDS.get(0).gun());
        for (int lane = 0; lane < LANES; lane++) {
            Vec3 at = new Vec3(laneX(lane), y, Z0 + 6.5);
            List<Mob> made = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, gun);
            if (made.isEmpty()) {
                check.check("the aim check: a shooter spawns", false);
                round = ROUNDS.size();
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
        round = 0;
        motion = 0;
        start();
    }

    // a round's gun in every hand, loaded, its recoil, and the first motion's targets
    private void startRound() {
        Round r = ROUNDS.get(round);
        AiRecoil.override = r.recoil();
        var gun = BuiltInRegistries.ITEM.getValue(r.gun());
        for (int lane = 0; lane < LANES; lane++) {
            if (shooters[lane] == null || bots[lane] == null) continue;
            shooters[lane].setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(gun));
            bots[lane].gunner.loadFree();
        }
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
        report();
        motion++;
        if (motion >= ROUNDS.get(round).motions().size()) {
            round++;
            motion = 0;
            if (round >= ROUNDS.size()) {
                compare();
                finish();
                return;
            }
        }
        start();
    }

    private void start() {
        if (motion == 0) startRound();
        ticks = 0;
        for (int i = 0; i < 3; i++) tracks[i] = track(motion(), DIST[i]);
        for (int lane = 0; lane < LANES; lane++) {
            // the hand's error and the gun's spread from a seed of the run's own: the same numbers each time
            // (the same for a gun with and without recoil)
            if (shooters[lane] != null) shooters[lane].getRandom().setSeed((round == 0 ? 0 : 500) + 10L * motion + lane);
            hits[lane] = shots[lane] = 0;
            first[lane] = -1;
            shotsBefore[lane] = bots[lane] == null ? 0 : bots[lane].gunner.shots;
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
        } else {
            g.track(t.getId(), p.add(0, chest, 0), level.getGameTime());
            if (lane == 1 && motion() == Motion.STAND && ticks > 20 && ticks < CEASE) {
                Vec3 eye = s.getEyePosition(), c = p.add(0, chest, 0);
                climb[round] = Math.max(climb[round], AiAim.pitchTo(eye.x, eye.y, eye.z, c.x, c.y, c.z) - s.getXRot());
            }
        }
        g.fire(ticks < CEASE);
        shots[lane] += g.shots - shotsBefore[lane];
        shotsBefore[lane] = g.shots;
    }

    private void report() {
        Motion m = motion();
        StringBuilder line = new StringBuilder("     aim, " + ROUNDS.get(round).what() + ", " + m.what + ", the AI / a perfect hand, hits of shots:");
        boolean ok = true;
        for (int i = 0; i < 3; i++) {
            double ai = shots[i] == 0 ? 0 : (double) hits[i] / shots[i], best = shots[i + 3] == 0 ? 0 : (double) hits[i + 3] / shots[i + 3];
            aiHits[round][m.ordinal()][i] = hits[i];
            aiShots[round][m.ordinal()][i] = shots[i];
            line.append(String.format(Locale.ROOT, " %.0f blocks %d/%d (%.0f%%, first at %d) / %d/%d (%.0f%%);", DIST[i], hits[i], shots[i], 100 * ai, first[i],
                    hits[i + 3], shots[i + 3], 100 * best));
            ok &= shots[i] > 0 && ai >= AT_LEAST[m.ordinal()] * best;
        }
        if (m == Motion.STAND) line.append(String.format(Locale.ROOT, " the AI's view at most %.2f degrees above the target (30 blocks)", climb[round]));
        check.out(line.toString());
        if (round == 0 && AT_LEAST[m.ordinal()] > 0)
            check.check(String.format(Locale.ROOT, "%s: the AI hits at least %.0f%% as often as a perfect hand", m.what, 100 * AT_LEAST[m.ordinal()]), ok);
    }

    // each gun without recoil against with it: a gun that fires every 4 ticks (the grease gun, shown) and one every 2
    // whose climb stays (the M2, checked)
    private void compare() {
        int stand = Motion.STAND.ordinal(), run = Motion.RUN.ordinal();
        check.out(String.format(Locale.ROOT, "     the grease gun: the AI's view at most %.2f degrees above the target without recoil, %.2f with it, %.2f x3",
                climb[GREASE], climb[GREASE + 1], climb[GREASE + 2]));
        check.check(String.format(Locale.ROOT, "an M2 burst lifts the AI's view: at most %.2f degrees above the target without recoil, %.2f with it, %.2f x3",
                climb[M2], climb[M2 + 1], climb[M2 + 2]), climb[M2] <= CLIMB_CALM && climb[M2 + 1] >= climb[M2] + CLIMB_MORE);
        double[] of = new double[ROUNDS.size()], running = new double[ROUNDS.size()];
        for (int r = 1; r < ROUNDS.size(); r++) {
            int h = 0, n = 0, hr = 0, nr = 0;
            for (int m : new int[] {stand, run})
                for (int i = 1; i <= 2; i++) {
                    h += aiHits[r][m][i];
                    n += aiShots[r][m][i];
                    if (m == run) {
                        hr += aiHits[r][m][i];
                        nr += aiShots[r][m][i];
                    }
                }
            of[r] = n == 0 ? 0 : (double) h / n;
            running[r] = nr == 0 ? 0 : (double) hr / nr;
        }
        for (int g : new int[] {GREASE, M2})
            check.out(String.format(Locale.ROOT, "     %s's hits of shots at 30 and 60 blocks without recoil / with it / x3: standing and running %.0f / %.0f / %.0f%%, "
                    + "running only %.0f / %.0f / %.0f%%; shots at a standing target 60 blocks off %d / %d / %d", g == M2 ? "the M2" : "the grease gun", 100 * of[g],
                    100 * of[g + 1], 100 * of[g + 2], 100 * running[g], 100 * running[g + 1], 100 * running[g + 2], aiShots[g][stand][2], aiShots[g + 1][stand][2],
                    aiShots[g + 2][stand][2]));
        check.check("an M2's strong recoil (x3) makes the AI miss more far off and on the run", of[M2] > 0 && of[M2 + 2] <= of[M2] - FEWER);
    }

    private void finish() {
        counting = null;
        AiRecoil.override = Float.NaN;
        for (int lane = 0; lane < LANES; lane++) {
            if (shooters[lane] != null) shooters[lane].discard();
            if (targets[lane] != null) targets[lane].discard();
        }
        AiBots.refreshTargets(level);
    }
}
