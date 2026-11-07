package acup.gunfeel;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// NTM's lasers go through glass (laserGlass, server, on): glass of any kind the bullet rules know (Materials: vanilla
// glass and panes, NTM's glass, wired glass, laminate; a machine's window) is gone through as air, not worn, broken,
// paid for or holed. Lasers are the beams of light (with beamRules, BeamServer): the capacitor rounds of the Laser
// Pistol, Pew Pew and Laser Rifle (the infrared ones too: NTM gives them fire damage, they are the same red laser), the
// Morning Glory's emerald rounds, Lacunae's, the laser grenade's beams, the Shredder's plasma beam (its slow drops are
// bullets and meet glass as bullets do; they come out where the beam stops), and the Maxwell turret's maser; and the
// flying laser shots of NTM's mobs (bullets, BulletServer, Tracers): the masked man's bolt, tracer and orb, BOT Prime's
// and the UFO's bolt and laser (worm_bolt, worm_laser). Not the Tesla Cannon or the Tesla arcs, Tau, the Tauon turret,
// the N.I.4.N.I., the Aberrator: they meet glass as bullets do.
public final class BeamLasers {
    private BeamLasers() {
    }

    public static final String KEY = "laserGlass";

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // the lasers' rounds by their fields, looked up by name: a field another NTM has not leaves out only its laser
    // (Compat would turn the whole core off for a missing direct reference)
    static final String[][] FIELDS = {
            {"com.hbm.items.weapon.sedna.factory.XFactoryEnergy", "energy_las", "energy_las_overcharge", "energy_las_ir", "energy_emerald",
                    "energy_emerald_overcharge", "energy_emerald_ir"},
            {"com.hbm.items.weapon.sedna.factory.XFactory762mm", "energy_lacunae", "energy_lacunae_overcharge", "energy_lacunae_ir"},
            {"com.hbm.items.weapon.grenade.ItemGrenadeFilling", "laser"},
            {"com.hbm.items.weapon.sedna.factory.XFactory12ga", "g12_shredder", "g12_shredder_slug", "g12_shredder_flechette", "g12_shredder_magnum",
                    "g12_shredder_explosive", "g12_shredder_phosphorus"},
            // the mobs' laser shots: bullets (EntityBulletBaseMK4), NTM's laser damage
            {"com.hbm.items.weapon.sedna.factory.XFactoryNPC", "maskman_bolt", "maskman_tracer", "maskman_orb", "worm_bolt", "worm_laser"}};

    private static volatile @Nullable Set<BulletConfig> lasers;
    // looks that found some fields but not all: kept after this many (an NTM without one of them), till then asked again
    // (asked while NTM was still making its configs)
    private static final int TRIES = 64;
    private static volatile int tries;

    private static Set<BulletConfig> lasers() {
        Set<BulletConfig> s = lasers;
        if (s != null) return s;
        Set<BulletConfig> out = Collections.newSetFromMap(new IdentityHashMap<>());
        int all = 0;
        for (String[] row : FIELDS) for (int i = 1; i < row.length; i++) {
            all++;
            if (field(row[0], row[i]) instanceof BulletConfig c) out.add(c);
        }
        if (out.size() == all || !out.isEmpty() && ++tries >= TRIES) lasers = out;
        return out;
    }

    private static @Nullable Object field(String owner, String name) {
        try {
            return Class.forName(owner, true, BulletConfig.class.getClassLoader()).getField(name).get(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    // the round is one of NTM's lasers
    public static boolean laser(@Nullable BulletConfig round) {
        return round != null && lasers().contains(round);
    }

    // a beam of this round goes through glass now
    public static boolean throughGlass(@Nullable BulletConfig round) {
        return laser(round) && on();
    }

    // the first block that stops a shot of `round` going from `from` to `to` (PassableClip.clip, `passed` told of the
    // blocks it flies through); a laser goes on through the glass on its way, the glass not told of
    public static BlockHitResult clip(Level level, @Nullable BulletConfig round, Vec3 from, Vec3 to, Consumer<BlockHitResult> passed) {
        if (!throughGlass(round)) return PassableClip.clip(level, from, to, passed);
        Consumer<BlockHitResult> told = h -> {
            if (!glass(level, h)) passed.accept(h);
        };
        Vec3 dir = to.subtract(from).normalize(), at = from;
        double len = from.distanceToSqr(to);
        for (int i = 0; i < STEPS; i++) {
            BlockHitResult hit = PassableClip.clip(level, at, to, told);
            if (hit.getType() != HitResult.Type.BLOCK || !glass(level, hit)) return hit;
            at = past(level, hit, dir);
            if (at.distanceToSqr(from) >= len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
        }
        return PassableClip.clip(level, at, to);
    }

    // the same with no blocks to tell of (a client's bullet): a plain shot takes PassableClip's short way
    public static BlockHitResult clip(Level level, @Nullable BulletConfig round, Vec3 from, Vec3 to) {
        return throughGlass(round) ? clip(level, round, from, to, h -> {
        }) : PassableClip.clip(level, from, to);
    }

    // the most glass a shot goes through in one look (BeamServer.STEPS)
    static final int STEPS = 64;

    // just past the block it hit, going along `dir` (unit): out of its shape, else a little on
    static Vec3 past(Level level, BlockHitResult hit, Vec3 dir) {
        BlockHitResult out = PassableClip.exit(level, hit, dir);
        Vec3 p = out != null ? out.getLocation() : hit.getLocation().add(dir.scale(0.05));
        return p.add(dir.scale(1e-3));
    }

    // the block hit is glass by the bullet rules (what a bullet pierces and holes as glass)
    public static boolean glass(Level level, BlockHitResult hit) {
        if (hit instanceof ModelSurface.GlassHit) return true;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        return BulletRules.material(Cells.ruleState(level, pos, state)).material().glass();
    }
}
