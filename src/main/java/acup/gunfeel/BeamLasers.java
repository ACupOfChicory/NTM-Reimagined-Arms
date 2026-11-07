package acup.gunfeel;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

// NTM's lasers go through glass (beamLaserGlass, server, on; with beamRules): glass of any kind the bullet rules know
// (Materials: vanilla glass and panes, NTM's glass, wired glass, laminate; a machine's window) is gone through as air,
// not worn, broken or paid for. Lasers are the beams of light: the capacitor rounds of the Laser Pistol, Pew Pew and
// Laser Rifle (the infrared ones too: NTM gives them fire damage, they are the same red laser), the Morning Glory's
// emerald rounds, Lacunae's, the laser grenade's beams, the Shredder's shells (NTM's laser damage and a cyan laser;
// their burst goes off where the beam stops), and the Maxwell turret's maser. Not the Tesla Cannon or the Tesla arcs,
// Tau, the Tauon turret, the N.I.4.N.I., the Aberrator: they meet glass as bullets do.
public final class BeamLasers {
    private BeamLasers() {
    }

    public static final String KEY = "beamLaserGlass";

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
                    "g12_shredder_explosive", "g12_shredder_phosphorus"}};

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

    // the block hit is glass by the bullet rules (what a bullet pierces and holes as glass)
    public static boolean glass(Level level, BlockHitResult hit) {
        if (hit instanceof ModelSurface.GlassHit) return true;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        return BulletRules.material(Cells.ruleState(level, pos, state)).material().glass();
    }
}
