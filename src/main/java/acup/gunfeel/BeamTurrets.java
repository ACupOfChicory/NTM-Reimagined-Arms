package acup.gunfeel;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.hbm.blockentity.machine.BlockEntityTesla;
import com.hbm.blockentity.turret.BlockEntityTurretBaseNT;
import com.hbm.blockentity.turret.BlockEntityTurretMaxwell;
import com.hbm.blockentity.turret.BlockEntityTurretTauon;
import com.hbm.blocks.multiblock.BlockMultiblockCell;
import com.hbm.blocks.multiblock.MultiblockSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

// NTM's beams with no beam entity: the Tauon and Maxwell turrets and the Tesla arcs (the coil, the Tesla and taint
// crabs, the Tesla armor). With beamRules (BeamServer) they see and hit through blocks as a bullet of their damage
// would (BeamServer.reach), not by NTM's look along the blocks' boxes: through a fence's gap, through glass they pierce.
// The shot wears and breaks blocks on its way by the bullet rules and the target gets what is left of the damage; a
// turret's shot counts where it meets the model (head x1.75, limbs x0.5), an arc does not (NTM splits it between all
// it reaches). In water they lose damage and go out (BeamWater); a Maxwell's maser goes through glass (BeamLasers).
// MixinBeamTurretSight, MixinBeamTauon, MixinBeamMaxwell, MixinBeamTesla
public final class BeamTurrets {
    private BeamTurrets() {
    }

    public static boolean beam(BlockEntityTurretBaseNT turret) {
        return turret instanceof BlockEntityTurretTauon || turret instanceof BlockEntityTurretMaxwell;
    }

    // NTM's damage of a Tauon's shot is 30 to 40; whether a target is in sight is judged by the least
    static final float TAUON_LEAST = 30F;

    // the damage the turret's beam has: a Maxwell's by its upgrades (NTM's updateFiringTick)
    public static float damage(BlockEntityTurretBaseNT turret, int red, int black) {
        return turret instanceof BlockEntityTurretMaxwell ? (black * 10 + red + 1) * 0.25F : TAUON_LEAST;
    }

    // NTM's isObstructedOpaque(target's middle, pivot) for a beam turret: true when the beam does not get there
    public static boolean blocked(BlockEntityTurretBaseNT turret, Vec3 target, Vec3 pivot, float damage) {
        Level level = turret.getLevel();
        return level == null || BeamServer.reach(level, null, pivot, target, damage, round(turret), false, own(turret), laser(turret)) < 0F;
    }

    // the zone's multiplier of the place the last shot met on the model (1: none), for HeadDamage.attack: after the armour
    private static float lastMultiplier = 1F;

    // the turret's shot at its target: the damage that gets there, or -1 when blocks stop it; then {@link #hurt}
    public static float hit(BlockEntityTurretBaseNT turret, Entity target, float damage) {
        Level level = turret.getLevel();
        if (level == null) return damage;
        Vec3 pivot = turret.getTurretPos();
        Vec3 middle = turret.getEntityPos(target);
        Vec3 far = middle.add(middle.subtract(pivot).normalize().scale(target.getBbWidth() + target.getBbHeight()));
        var met = ModelHits.clip(target, pivot, far, target.getBoundingBox());
        Vec3 at = met.orElse(middle);
        float left = BeamServer.reach(level, null, pivot, at, damage, round(turret), true, own(turret), laser(turret));
        lastMultiplier = 1F;
        if (left < 0F) return -1F;
        if (met.isPresent() && target instanceof LivingEntity living) lastMultiplier = HeadDamage.beamMultiplier(living, new EntityHitResult(target, at));
        return left;
    }

    // NTM's hurt of the shot {@link #hit} worked out, its zone's multiplier after the armour
    public static boolean hurt(Entity target, float left, HeadDamage.Hurt hurt) {
        float multiplier = lastMultiplier;
        lastMultiplier = 1F;
        return target instanceof LivingEntity living ? HeadDamage.attack(living, left, multiplier, hurt) : hurt.hurt(left);
    }

    // a Maxwell's maser is a laser (BeamLasers): through glass; a Tauon's tau is not
    static boolean laser(BlockEntityTurretBaseNT turret) {
        return turret instanceof BlockEntityTurretMaxwell;
    }

    // the round a Tauon fires (its threshold negation and armour piercing go into the piercing), a Maxwell none
    private static com.hbm.items.weapon.sedna.@Nullable BulletConfig round(BlockEntityTurretBaseNT turret) {
        return turret instanceof BlockEntityTurretTauon ? turret.getFirstConfigLoaded() : null;
    }

    // the turret's own blocks: its core and the cells that belong to it
    private static Predicate<BlockPos> own(BlockEntityTurretBaseNT turret) {
        Level level = turret.getLevel();
        BlockPos core = turret.getBlockPos();
        return pos -> pos.equals(core) || core.equals(coreOf(level, pos));
    }

    private static @Nullable BlockPos coreOf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockMultiblockCell)) return null;
        try {
            return MultiblockSurface.coreOfAny(level, pos, state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // NTM's damage of an arc to `e` when `count` creatures are in reach (BlockEntityTesla.zap)
    public static float arc(LivingEntity e, int count) {
        return Mth.clamp(e.getMaxHealth() * 0.5F, 3.0F, 20.0F) / Math.max(1, count);
    }

    // NTM's isObstructed(from, to) in BlockEntityTesla.zap: true when the arc does not get there
    public static boolean arcBlocked(Level level, Vec3 from, Vec3 to, float damage) {
        return BeamServer.reach(level, null, from, to, damage, null, false, coil(level, from)) < 0F;
    }

    // the arc's hit: the damage that gets to `e`, or -1 when blocks stop it
    public static float arcHit(Level level, @Nullable Entity source, Vec3 from, LivingEntity e, float damage) {
        Vec3 to = new Vec3(e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ());
        return BeamServer.reach(level, source, from, to, damage, null, true, coil(level, from));
    }

    // the coil the arc comes from (NTM: 1.75 over the coil's block), gone through as air
    private static @Nullable Predicate<BlockPos> coil(Level level, Vec3 from) {
        BlockPos below = BlockPos.containing(from.x, from.y - 1.75, from.z);
        if (!(level.getBlockEntity(below) instanceof BlockEntityTesla)) return null;
        return below::equals;
    }
}
