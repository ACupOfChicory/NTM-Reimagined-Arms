package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.BulletRules;
import acup.gunfeel.BulletServer;
import acup.gunfeel.PierceSteps;
import acup.gunfeel.ReaimTimes;
import acup.gunfeel.TestLog;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port: 26.2's MixinBulletServer (0762086) on NTM 1.7.10's EntityBulletBaseMK4 (onImpact, onUpdate, motionMult: the
// same methods; tick -> onUpdate, an override of Minecraft's, so remapped). Not here: the server's announcement of
// each bullet to the players around (ShotPayload) and of where it stopped in a creature (BulletStopPayload): the
// tracers' port sends them from here (gunfeel$announce, gunfeel$entityAfter).
// Server: explosive bullets shatter glass; bullets fly faster (bulletSpeed in ntm-gunfeel-server.properties, TaCZ-like),
// bullet rules break blocks and stop ricochets, and the hits go to the test log.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBulletServer implements PierceSteps.Holder {
    @Unique
    private final PierceSteps gunfeel$steps = new PierceSteps();

    @Override
    public PierceSteps gunfeel$steps() {
        return gunfeel$steps;
    }

    @Shadow
    public BulletConfig config;

    @Shadow
    public float damage;

    @Shadow
    protected abstract double motionMult();

    @Unique
    private boolean gunfeel$announced;

    // only real bullets (NTM's default 10 blocks a tick); slow projectiles such as grenades keep their speed
    @Inject(method = "motionMult", at = @At("RETURN"), cancellable = true)
    private void gunfeel$speed(CallbackInfoReturnable<Double> cir) {
        if (config != null && config.velocity >= 5F) cir.setReturnValue(cir.getReturnValue() * ReaimTimes.number("bulletSpeed", 1.6F));
    }

    // a bullet that blows up where it hits (explosive buckshot) breaks the glass it hits, as a plain bullet does (NTM
    // breaks blocks of glass), and blows up there: an impact fuze goes off on the glass
    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$shatter(MovingObjectPosition mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || config.onImpact == null || self.worldObj.isRemote || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        // port: 26.2 asks for the glass sound (NoteBlockInstrument.HAT); 1.7.10 has the glass material
        Block b = self.worldObj.getBlock(mop.blockX, mop.blockY, mop.blockZ);
        if (b.getMaterial() == Material.glass || b == Blocks.glowstone) self.worldObj.func_147480_a(mop.blockX, mop.blockY, mop.blockZ, false);
    }

    // bullet rules (ntm-gunfeel-bullets.txt): a plain bullet breaks the block it hits when a "break" rule says so, then
    // stops there or, with "pass", flies on; an explosive bullet breaks it and still goes off as NTM has it
    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void gunfeel$breakBlock(MovingObjectPosition mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || self.worldObj.isRemote || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        Vec3 at = mop.hitVec;
        int got = BulletServer.blockHit(self, mop.blockX, mop.blockY, mop.blockZ, at.xCoord, at.yCoord, at.zCoord);
        if (got == 0 || config.onImpact != null) return;
        self.setPosition(at.xCoord, at.yCoord, at.zCoord);
        if (got == 1) self.setDead();
        ci.cancel();
    }

    // bullet rules "ricochet <blocks> no" (soft ground): where NTM's standard ricochet would bounce the bullet off,
    // it stops in the block instead
    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void gunfeel$noRicochet(MovingObjectPosition mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || config.onImpact != null || config.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET || self.worldObj.isRemote
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        World w = self.worldObj;
        int x = mop.blockX, y = mop.blockY, z = mop.blockZ;
        Block b = w.getBlock(x, y, z);
        if (b.getMaterial() == Material.air || BulletRules.ricochet(b, w.getBlockMetadata(x, y, z), w, x, y, z)) return;
        int f = mop.sideHit;
        double l = Math.sqrt(self.motionX * self.motionX + self.motionY * self.motionY + self.motionZ * self.motionZ);
        if (l < 1e-9 || f < 0 || f > 5) return;
        double cos = (acup.gunfeel.BlockRay.stepX(f) * self.motionX + acup.gunfeel.BlockRay.stepY(f) * self.motionY
                + acup.gunfeel.BlockRay.stepZ(f) * self.motionZ) / l;
        if (Math.abs(Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos)))) - 90.0) > config.ricochetAngle) return;
        Vec3 at = mop.hitVec;
        if (TestLog.server) TestLog.s(self.getEntityId(), "ricochet", "block", TestLog.block(w, x, y, z), "pos", new int[] {x, y, z}, "result", "stopped");
        self.setPosition(at.xCoord, at.yCoord, at.zCoord);
        self.setDead();
        ci.cancel();
    }

    @Unique
    private boolean gunfeel$targetAlive;

    // the test log: the bullet's damage and the creature's health as the hit begins
    @Unique
    private float gunfeel$damageBefore, gunfeel$healthBefore;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$entityBefore(MovingObjectPosition mop, CallbackInfo ci) {
        boolean entity = mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mop.entityHit != null;
        gunfeel$targetAlive = entity && mop.entityHit.isEntityAlive();
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        // the damage the bullet has where the creature is, not after the blocks behind it
        if (!self.worldObj.isRemote && entity && mop.hitVec != null) gunfeel$steps.enter(self, mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
        gunfeel$damageBefore = damage;
        gunfeel$healthBefore = entity && mop.entityHit instanceof EntityLivingBase ? ((EntityLivingBase) mop.entityHit).getHealth() : Float.NaN;
    }

    // bullet rules: "entity" and "kill" effects of the creature the bullet hit
    @Inject(method = "onImpact", at = @At("RETURN"))
    private void gunfeel$entityAfter(MovingObjectPosition mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (self.worldObj.isRemote || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY || mop.entityHit == null) return;
        BulletServer.entityHit(self, mop.entityHit, mop.hitVec, gunfeel$targetAlive, gunfeel$damageBefore, gunfeel$healthBefore);
    }

    // the blocks still owed after the creatures this tick were hit (PierceSteps)
    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$pierceOwed(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (!self.worldObj.isRemote) gunfeel$steps.finish(self);
    }

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$announce(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        // the test log's lines during this bullet's tick (its hits, breaking, ricochets) are this bullet's
        if (TestLog.server && !self.worldObj.isRemote) TestLog.shot(self.getEntityId());
        if (gunfeel$announced || self.worldObj.isRemote) return;
        gunfeel$announced = true;
        if (config == null) config = self.getBulletConfig();
        // only fast bullets get the client's tracer; slow projectiles (grenades, Shredder's plasma drops) stay NTM's
        if (config == null || config.velocity < 5F) return;
        double mult = motionMult();
        Entity owner = self.getThrower();
        if (TestLog.server) {
            ItemStack held = owner instanceof EntityLivingBase ? ((EntityLivingBase) owner).getHeldItem() : null;
            TestLog.s(self.getEntityId(), "fire", "ammo", config.id, "owner", owner == null ? -1 : owner.getEntityId(),
                    "gun", held == null ? "-" : net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem()),
                    "at", TestLog.point(self.posX, self.posY, self.posZ), "v", TestLog.point(self.motionX * mult, self.motionY * mult, self.motionZ * mult),
                    "damage", damage, "speedMult", ReaimTimes.number("bulletSpeed", 1.6F), "wear", BulletServer.wearText(owner));
        }
    }
}
