package acup.gunfeel;

import com.hbm.blocks.BlockDummyable;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.tileentity.machine.TileEntityTesla;
import com.hbm.tileentity.turret.TileEntityTurretMaxwell;
import com.hbm.tileentity.turret.TileEntityTurretTauon;

import acup.gunfeel.mixins.TurretAccess;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port: 26.2's BeamTurrets (X581) for 1.12.2 CE: the turrets' look is Library.isObstructedOpaque in entityInLOS (shared
// by every turret: only the two beam turrets' goes here), the Tesla's Library.isObstructed in zap; hurtServer ->
// attackEntityFrom; NTM CE divides an arc's damage before it clamps it; the turret's own blocks are BlockDummyable's
// cells around its core (findCore); a turret is a TileEntity and its own methods go through TurretAccess (NTM's turret
// implements OpenComputers' interfaces).
// NTM's beams with no beam entity: the Tauon and Maxwell turrets and the Tesla arcs (the coil, the Tesla and taint
// crabs, the Tesla armor). With beamRules (BeamServer) they see and hit through blocks as a bullet of their damage
// would (BeamServer.reach), not by NTM's look along the blocks' boxes: through a fence's gap, through glass they pierce.
// The shot wears and breaks blocks on its way by the bullet rules and the target gets what is left of the damage; a
// turret's shot counts where it meets the model (head x1.75, limbs x0.5), an arc does not (NTM splits it between all
// it reaches). MixinBeamTurretSight, MixinBeamTauon, MixinBeamMaxwell, MixinBeamTesla
public final class BeamTurrets {
    private BeamTurrets() {
    }

    public static boolean beam(TileEntity turret) {
        return turret instanceof TileEntityTurretTauon || turret instanceof TileEntityTurretMaxwell;
    }

    // NTM's damage of a Tauon's shot is 30 to 40; whether a target is in sight is judged by the least
    static final float TAUON_LEAST = 30F;

    // the damage the turret's beam has: a Maxwell's by its upgrades (NTM's updateFiringTick)
    public static float damage(TileEntity turret, int red, int black) {
        return turret instanceof TileEntityTurretMaxwell ? (black * 10 + red + 1) * 0.25F : TAUON_LEAST;
    }

    // NTM's isObstructedOpaque(target's middle, pivot) for a beam turret: true when the beam does not get there
    public static boolean blocked(TileEntity turret, Vec3d target, Vec3d pivot, float damage) {
        World level = turret.getWorld();
        return level == null || BeamServer.reach(level, null, pivot, target, damage, round(turret), false, own(turret)) < 0F;
    }

    // the turret's shot at its target: the damage that gets there (with the zone of the place it meets on the model), or
    // -1 when blocks stop it
    public static float hit(TileEntity turret, Entity target, float damage) {
        World level = turret.getWorld();
        if (level == null) return damage;
        Vec3d pivot = ((TurretAccess) turret).gunfeel$pivot();
        Vec3d middle = ((TurretAccess) turret).gunfeel$middle(target);
        Vec3d dir = middle.subtract(pivot).normalize();
        double reach = target.width + target.height;
        Vec3d far = new Vec3d(middle.x + dir.x * reach, middle.y + dir.y * reach, middle.z + dir.z * reach);
        Vec3d met = ModelHits.clipMc(target, pivot, far, target.getEntityBoundingBox());
        Vec3d at = met != null ? met : middle;
        float left = BeamServer.reach(level, null, pivot, at, damage, round(turret), true, own(turret));
        if (left < 0F) return -1F;
        if (met != null && target instanceof EntityLivingBase)
            left = HeadDamage.beamDamage(left, (EntityLivingBase) target, new RayTraceResult(target, at));
        return left;
    }

    // the round a Tauon fires (its threshold negation and armour piercing go into the piercing), a Maxwell none
    private static BulletConfig round(TileEntity turret) {
        return turret instanceof TileEntityTurretTauon ? ((TurretAccess) turret).gunfeel$round() : null;
    }

    // the turret's own blocks: its core and the cells that belong to it
    private static BeamServer.Own own(TileEntity turret) {
        World level = turret.getWorld();
        int cx = turret.getPos().getX(), cy = turret.getPos().getY(), cz = turret.getPos().getZ();
        return (x, y, z) -> {
            if (x == cx && y == cy && z == cz) return true;
            Block b = Mc.block(level, x, y, z);
            if (!(b instanceof BlockDummyable)) return false;
            try {
                int[] core = ((BlockDummyable) b).findCore(level, x, y, z);
                return core != null && core[0] == cx && core[1] == cy && core[2] == cz;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    // NTM's damage of an arc to `e` when `count` creatures are in reach (TileEntityTesla.zap; port: NTM CE clamps it
    // after dividing)
    public static float arc(EntityLivingBase e, int count) {
        return MathHelper.clamp(0.5F * e.getMaxHealth() / Math.max(1, count), 3.0F, 20.0F);
    }

    // NTM's Library.isObstructed(from, to) in TileEntityTesla.zap: true when the arc does not get there
    public static boolean arcBlocked(World level, Vec3d from, Vec3d to, float damage) {
        return BeamServer.reach(level, null, from, to, damage, null, false, coil(level, from)) < 0F;
    }

    // the arc's hit: the damage that gets to `e`, or -1 when blocks stop it
    public static float arcHit(World level, Entity source, Vec3d from, EntityLivingBase e, float damage) {
        Vec3d to = new Vec3d(e.posX, e.posY + e.height / 2.0F, e.posZ);
        return BeamServer.reach(level, source, from, to, damage, null, true, coil(level, from));
    }

    // the coil the arc comes from (NTM: offset over the coil's block), gone through as air
    private static BeamServer.Own coil(World level, Vec3d from) {
        int x = MathHelper.floor(from.x), y = MathHelper.floor(from.y - TileEntityTesla.offset),
                z = MathHelper.floor(from.z);
        if (!(Mc.te(level, x, y, z) instanceof TileEntityTesla)) return null;
        return (bx, by, bz) -> bx == x && by == y && bz == z;
    }
}
