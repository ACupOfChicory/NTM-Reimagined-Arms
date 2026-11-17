package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's loudest shot: a hundred soldiers of one side in GROUPS groups of EACH, one group every 30 blocks out
 * from a place to 300, round it; nobody about. A shot goes off there (its sound alone, no round flies): a rifle's (80
 * blocks), Folly's (NTM plays it at volume 100: 1600 blocks), then a series of Folly's. Counted, each round: the server's
 * tick before the shot and after it (the most, and on average), how many heard it, how many are about something a while
 * after (not idle), and the groups that went towards the shot. Folly's shot wakes every AI mob within 1600 blocks: the
 * server's tick takes it, and no group runs off to it (there is no foe known), so their hearing is not cut short.
 */
final class AiCheckFolly {
    static final int X0 = 5000, Z0 = 0, GROUPS = 10, EACH = 10, STEP = 30, WARM = 200, AFTER = 600, SERIES = 10, SERIES_EVERY = 20;
    static final double CX = X0 + 320.5, CZ = Z0 + 320.5;
    /** a group went towards the shot: its middle this much nearer it, blocks */
    static final double TOWARDS = 8;
    /** the server's tick after a shot at most, ms (sped up; under load) */
    static final double FIRST_MOST = 25;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine"),
            HENRY = Identifier.fromNamespaceAndPath("hbm", "gun_henry"), FOLLY = Identifier.fromNamespaceAndPath("hbm", "gun_folly");
    static final String TEAM = "folly";
    /** rounds: the shot's gun, how many shots */
    static final Identifier[] GUNS = {HENRY, FOLLY, FOLLY};
    static final int[] SHOTS = {1, 1, SERIES};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> mobs = new ArrayList<>();
    private final List<Vec3> from = new ArrayList<>();
    private long last;
    private double warmSum, afterSum, afterMax, firstMax;
    private int warmN, afterN, heard, shots, busyNear, busyFar;
    private final List<String> lines = new ArrayList<>();
    final List<double[]> results = new ArrayList<>();

    AiCheckFolly(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private Vec3 groupAt(int g) {
        double d = STEP * (g + 1), a = Math.toRadians(36.0 * g);
        return new Vec3(CX + d * Math.cos(a), y, CZ + d * Math.sin(a));
    }

    boolean tick() {
        long now = System.nanoTime();
        double ms = last == 0 ? 0 : (now - last) / 1e6;
        last = now;
        if (!started) {
            started = true;
            AiBots.awake = true;
            for (int g = 0; g < GROUPS; g++) {
                Vec3 at = groupAt(g);
                int bx = (int) Math.floor(at.x), bz = (int) Math.floor(at.z);
                for (int cx = (bx - 8) >> 4; cx <= (bx + 8) >> 4; cx++)
                    for (int cz = (bz - 8) >> 4; cz <= (bz + 8) >> 4; cz++) level.setChunkForced(cx, cz, true);
                for (int x = bx - 7; x <= bx + 7; x++)
                    for (int z = bz - 7; z <= bz + 7; z++) {
                        level.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                        for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(x, y + h, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
            }
            start(0);
            return false;
        }
        if (round >= GUNS.length) return true;
        ticks++;
        for (Mob m : mobs) if (m.isAlive()) m.setHealth(m.getMaxHealth());
        if (ticks > WARM / 2 && ticks <= WARM) {
            warmSum += ms;
            warmN++;
        }
        int t = ticks - WARM;
        if (t > 0) {
            afterSum += ms;
            afterN++;
            afterMax = Math.max(afterMax, ms);
            if (t <= 40) firstMax = Math.max(firstMax, ms);
        }
        if (t >= 0 && shots < SHOTS[round] && t % SERIES_EVERY == 0) shoot();
        if (t == 100) {
            lines.add(String.format(Locale.ROOT, "about something at 100 ticks %d", busy()));
            // the groups within the rifle's 80 blocks (the first two, 30 and 60 out) and those further
            busyNear = busyFar = 0;
            for (int i = 0; i < mobs.size(); i++)
                if (AiBots.get(mobs.get(i)) instanceof AiBot b && b.brain instanceof AiSoldier so && so.mode() != AiSoldier.Mode.IDLE) {
                    if (i < 2 * EACH) busyNear++;
                    else busyFar++;
                }
        }
        if (t < AFTER + SERIES_EVERY * (SHOTS[round] - 1)) return false;
        end();
        if (round + 1 < GUNS.length) start(round + 1);
        else {
            round = GUNS.length;
            report();
        }
        return round >= GUNS.length;
    }

    private void shoot() {
        var item = BuiltInRegistries.ITEM.getValue(GUNS[round]);
        if (!(item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) {
            check.check("the loudest shot: a gun " + GUNS[round], false);
            shots = SHOTS[round];
            return;
        }
        ItemStack stack = new ItemStack(item);
        var rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
        SoundEvent sound = rec.getFireSound(stack).get();
        long t0 = System.nanoTime();
        level.playSound(null, CX, y + 1.5, CZ, sound, SoundSource.PLAYERS, rec.getFireVolume(stack), 1F);
        double hearMs = (System.nanoTime() - t0) / 1e6;
        if (shots == 0) {
            int n = 0;
            for (Mob m : mobs) {
                AiBot b = AiBots.get(m);
                if (b != null && b.memory.noise() != null) n++;
            }
            heard = n;
            lines.add(String.format(Locale.ROOT, "heard %d of %d (%.0f blocks), the sound's call %.2f ms", n, mobs.size(), sound.getRange(rec.getFireVolume(stack)), hearMs));
        }
        shots++;
    }

    private int busy() {
        int n = 0;
        for (Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b != null && b.brain instanceof AiSoldier s && s.mode() != AiSoldier.Mode.IDLE) n++;
        }
        return n;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        shots = 0;
        warmSum = afterSum = afterMax = firstMax = 0;
        warmN = afterN = 0;
        lines.clear();
        AiSquads.forget(level);
        from.clear();
        for (int g = 0; g < GROUPS; g++) {
            Vec3 at = groupAt(g);
            for (int i = 0; i < EACH; i++) {
                double x = at.x - 4 + 2 * (i % 5), z = at.z - 1 + 2 * (i / 5);
                List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE));
                if (made.isEmpty()) continue;
                Mob m = made.get(0);
                m.setPos(x, y, z);
                var hp = m.getAttribute(Attributes.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(1000);
                m.setHealth(1000);
                AiTeams.join(level, m, TEAM);
                mobs.add(m);
            }
            from.add(at);
        }
        AiBots.refreshTargets(level);
    }

    private void end() {
        int towards = 0;
        double moved = 0;
        for (int g = 0; g < GROUPS; g++) {
            double sx = 0, sz = 0;
            int n = 0;
            for (int i = g * EACH; i < (g + 1) * EACH && i < mobs.size(); i++) {
                Mob m = mobs.get(i);
                if (!m.isAlive()) continue;
                sx += m.getX();
                sz += m.getZ();
                n++;
            }
            if (n == 0) continue;
            Vec3 was = from.get(g);
            double before = Math.hypot(was.x - CX, was.z - CZ), after = Math.hypot(sx / n - CX, sz / n - CZ);
            moved += before - after;
            if (before - after >= TOWARDS) towards++;
        }
        int busy = busy();
        double warm = warmN == 0 ? 0 : warmSum / warmN, avg = afterN == 0 ? 0 : afterSum / afterN;
        String gun = GUNS[round].getPath().replace("gun_", "");
        check.out(String.format(Locale.ROOT, "     loudest shot, %s x%d: tick %.2f ms before, after the shot %.2f ms the most in 40 ticks, %.2f the most in %d, %.2f on average;"
                        + " %s; about something at the end %d of %d; groups gone %.0f blocks or more towards the shot %d of %d (%.1f blocks on average)",
                gun, SHOTS[round], warm, firstMax, afterMax, afterN, avg, String.join(", ", lines), busy, mobs.size(), TOWARDS, towards, GROUPS, moved / GROUPS));
        results.add(new double[] {round, warm, firstMax, avg, heard, towards, busy});
        // a rifle's heard by the two groups within its 80 blocks; Folly's by all, and the server's tick takes it (with a
        // hundred heard, X1274 + gunshotRange: 4 to 7 ms the most after it, a rifle's 10 to 11), none run to it
        if (round == 0) check.check(String.format(Locale.ROOT, "the loudest shot: a rifle's shot is heard by those within its 80 blocks alone (%d of %d)",
                heard, mobs.size()), heard == 2 * EACH);
        // and those who heard it turn to it (out of sight of it, 30 and 60 blocks off: with NTM's own range, 16 blocks to a
        // carbine or a Henry, gunshotRange 0, none hears it, none turns)
        if (round == 0) check.check(String.format(Locale.ROOT, "the loudest shot: those within a rifle's reach who heard it are about it 100 ticks after, the rest idle (%d of %d near, %d further)",
                busyNear, 2 * EACH, busyFar), busyNear == 2 * EACH && busyFar == 0);
        else check.check(String.format(Locale.ROOT, "the loudest shot: Folly x%d is heard by all, the server's tick at most %.0f ms after it (%.1f), no group runs to it (%d of %d)",
                SHOTS[round], FIRST_MOST, firstMax, towards, GROUPS), heard == mobs.size() && firstMax <= FIRST_MOST && towards == 0);
        for (Mob m : mobs) m.discard();
        mobs.clear();
    }

    private void report() {
    }
}
