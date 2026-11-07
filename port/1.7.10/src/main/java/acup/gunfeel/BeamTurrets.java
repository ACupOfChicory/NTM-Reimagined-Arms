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
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port: 26.2's BeamTurrets (X581) for 1.7.10: the turrets' look is Library.isObstructedOpaque in entityInLOS (shared by
// every turret: only the two beam turrets' goes here), the Tesla's Library.isObstructed in zap; hurtServer ->
// attackEntityFrom; the damage is NTM X5863's getBaseDamage() (a Maxwell's times its upgrades), X5808's own numbers where
// it has none; the turret's own blocks are
// BlockDummyable's cells around its core (findCore); a turret is a TileEntity and its own methods go through
// TurretAccess (NTM's turret implements OpenComputers' interfaces, not on the port's classpath).
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

    // the damage the turret's beam has (NTM's updateFiringTick): a Maxwell's by its upgrades; a Tauon's shot is its base
    // damage to 4/3 of it (X5808: 30 to 40), and whether a target is in sight is judged by the least
    public static float damage(TileEntity turret, int red, int black) {
        float base = base(turret);
        return turret instanceof TileEntityTurretMaxwell ? (black * 10 + red + 1F) * base : base;
    }

    // NTM X5863's getBaseDamage(); NTM X5808 has none (a mixin into it would turn the whole bullets system off): a
    // Tauon's least 30 and a Maxwell's 0.25 there
    private static final java.lang.reflect.Method BASE = baseMethod();

    private static java.lang.reflect.Method baseMethod() {
        try {
            return Class.forName("com.hbm.tileentity.turret.TileEntityTurretBaseNT").getMethod("getBaseDamage");
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    static float base(TileEntity turret) {
        if (BASE != null) try {
            return (Float) BASE.invoke(turret);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // as X5808
        }
        return turret instanceof TileEntityTurretMaxwell ? 0.25F : 30F;
    }

    // NTM's isObstructedOpaque(target's middle, pivot) for a beam turret: true when the beam does not get there
    public static boolean blocked(TileEntity turret, Vec3 target, Vec3 pivot, float damage) {
        World level = turret.getWorldObj();
        return level == null || BeamServer.reach(level, null, pivot, target, damage, round(turret), false, own(turret)) < 0F;
    }

    // the turret's shot at its target: the damage that gets there (with the zone of the place it meets on the model), or
    // -1 when blocks stop it
    public static float hit(TileEntity turret, Entity target, float damage) {
        World level = turret.getWorldObj();
        if (level == null) return damage;
        Vec3 pivot = ((TurretAccess) turret).gunfeel$pivot();
        Vec3 middle = ((TurretAccess) turret).gunfeel$middle(target);
        Vec3 dir = pivot.subtract(middle).normalize(); // port: 1.7.10's a.subtract(b) is b - a
        double reach = target.width + target.height;
        Vec3 far = Vec3.createVectorHelper(middle.xCoord + dir.xCoord * reach, middle.yCoord + dir.yCoord * reach, middle.zCoord + dir.zCoord * reach);
        Vec3 met = ModelHits.clipMc(target, pivot, far, target.boundingBox);
        Vec3 at = met != null ? met : middle;
        float left = BeamServer.reach(level, null, pivot, at, damage, round(turret), true, own(turret));
        if (left < 0F) return -1F;
        if (met != null && target instanceof EntityLivingBase)
            left = HeadDamage.beamDamage(left, (EntityLivingBase) target, new MovingObjectPosition(target, at));
        return left;
    }

    // the round a Tauon fires (its threshold negation and armour piercing go into the piercing), a Maxwell none
    private static BulletConfig round(TileEntity turret) {
        return turret instanceof TileEntityTurretTauon ? ((TurretAccess) turret).gunfeel$round() : null;
    }

    // the turret's own blocks: its core and the cells that belong to it
    private static BeamServer.Own own(TileEntity turret) {
        World level = turret.getWorldObj();
        int cx = turret.xCoord, cy = turret.yCoord, cz = turret.zCoord;
        return (x, y, z) -> {
            if (x == cx && y == cy && z == cz) return true;
            Block b = level.getBlock(x, y, z);
            if (!(b instanceof BlockDummyable)) return false;
            try {
                int[] core = ((BlockDummyable) b).findCore(level, x, y, z);
                return core != null && core[0] == cx && core[1] == cy && core[2] == cz;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    // NTM's damage of an arc to `e` when `count` creatures are in reach (TileEntityTesla.zap)
    public static float arc(EntityLivingBase e, int count) {
        return MathHelper.clamp_float(e.getMaxHealth() * 0.5F, 3.0F, 20.0F) / Math.max(1, count);
    }

    // NTM's Library.isObstructed(from, to) in TileEntityTesla.zap: true when the arc does not get there
    public static boolean arcBlocked(World level, Vec3 from, Vec3 to, float damage) {
        return BeamServer.reach(level, null, from, to, damage, null, false, coil(level, from)) < 0F;
    }

    // the arc's hit: the damage that gets to `e`, or -1 when blocks stop it
    public static float arcHit(World level, Entity source, Vec3 from, EntityLivingBase e, float damage) {
        Vec3 to = Vec3.createVectorHelper(e.posX, e.posY + e.height / 2.0F, e.posZ);
        return BeamServer.reach(level, source, from, to, damage, null, true, coil(level, from));
    }

    // the coil the arc comes from (NTM: offset over the coil's block), gone through as air
    private static BeamServer.Own coil(World level, Vec3 from) {
        int x = MathHelper.floor_double(from.xCoord), y = MathHelper.floor_double(from.yCoord - TileEntityTesla.offset),
                z = MathHelper.floor_double(from.zCoord);
        if (!(level.getTileEntity(x, y, z) instanceof TileEntityTesla)) return null;
        return (bx, by, bz) -> bx == x && by == y && bz == z;
    }
}
