package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.ShotPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// Server: explosive bullets shatter glass; bullets fly faster (bulletSpeed in ntm-gunfeel-server.properties, TaCZ-like) and every new bullet is
// announced to the players around, who draw its tracer and hole themselves.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBulletServer implements acup.gunfeel.PierceSteps.Holder, acup.gunfeel.ClientHits.Holder, acup.gunfeel.ClientHits.MotionMult {
    @Unique
    private final acup.gunfeel.PierceSteps gunfeel$steps = new acup.gunfeel.PierceSteps();
    // its hits as its shooter's client saw them (ClientHits)
    @Unique
    private final acup.gunfeel.ClientHits.State gunfeel$hits = new acup.gunfeel.ClientHits.State();

    @Override
    public acup.gunfeel.ClientHits.State gunfeel$hits() {
        return gunfeel$hits;
    }

    @Override
    public double gunfeel$motionMult() {
        return motionMult();
    }

    @Override
    public acup.gunfeel.PierceSteps gunfeel$steps() {
        return gunfeel$steps;
    }

    @Shadow
    public BulletConfig config;

    @Shadow
    protected abstract double motionMult();

    @Unique
    private boolean gunfeel$announced;
    @Unique private Vec3 gunfeel$tickStart;

    // only real bullets (NTM's default 10 blocks a tick); slow projectiles such as grenades keep their speed
    // the return value modified, not set: water's f (MixinWaterBullet) multiplies it after
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "motionMult", at = @At("RETURN"))
    private double gunfeel$speed(double mult) {
        // a stopped explosive sinking moves its own path a tick (WaterTick.sink)
        if (acup.gunfeel.water.WaterBullet.stopped((EntityBulletBaseMK4) (Object) this)) return mult;
        return config != null && config.velocity >= 5F ? mult * ReaimTimes.number("bulletSpeed", 1.6F) : mult;
    }

    // where it last hit anything: where it stopped, if this hit takes it away (AiUnderFire). First of the injectors
    // here: the ones below may cancel the rest
    @Unique
    private Vec3 gunfeel$lastImpact;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$impactAt(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        if (mop != null && mop.getType() != net.minecraft.world.phys.HitResult.Type.MISS) gunfeel$lastImpact = mop.getLocation();
    }

    // the blast stand (BlastStand): the block a player's bullet stopped in
    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$standBlock(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        if (acup.gunfeel.ClientHits.standLog && mop instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
            acup.gunfeel.ClientHits.standBlock((EntityBulletBaseMK4) (Object) this, hit.getBlockPos());
    }

    // a bullet that blows up where it hits (explosive buckshot) breaks the glass it hits, as a plain bullet does (NTM
    // breaks blocks that sound like glass and glowstone), and blows up there: an impact fuze goes off on the glass
    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$shatter(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || config.onImpact == null || self.level().isClientSide()
                || !(mop instanceof net.minecraft.world.phys.BlockHitResult hit)) return;
        net.minecraft.world.level.block.state.BlockState state = self.level().getBlockState(hit.getBlockPos());
        if (state.instrument() == net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HAT
                || state.is(net.minecraft.world.level.block.Blocks.GLOWSTONE))
            self.level().destroyBlock(hit.getBlockPos(), false);
    }

    // bullet rules (ntm-gunfeel-bullets.txt): a plain bullet breaks the block it hits when a "break" rule says so, then
    // stops there or, with "pass", flies on; an explosive bullet breaks it and still goes off as NTM has it
    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void gunfeel$breakBlock(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || self.level().isClientSide() || !(mop instanceof net.minecraft.world.phys.BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        int got = acup.gunfeel.BulletServer.blockHit(self, hit);
        if (got == 0 || config.onImpact != null) return;
        Vec3 at = hit.getLocation();
        self.setPos(at.x, at.y, at.z);
        if (got == 1) self.discard();
        ci.cancel();
    }

    // bullet rules "ricochet <blocks> no" (soft ground): where NTM's standard ricochet would bounce the bullet off,
    // it stops in the block instead
    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void gunfeel$noRicochet(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || config.onImpact != null || config.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET || self.level().isClientSide()
                || !(mop instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        net.minecraft.world.level.block.state.BlockState state = self.level().getBlockState(hit.getBlockPos());
        if (state.isAir() || acup.gunfeel.BulletRules.ricochet(acup.gunfeel.Cells.ruleState(self.level(), hit.getBlockPos(), state))) return;
        net.minecraft.core.Direction f = hit.getDirection();
        Vec3 vel = self.getDeltaMovement();
        double l = vel.length();
        if (l < 1e-9) return;
        double cos = (f.getStepX() * vel.x + f.getStepY() * vel.y + f.getStepZ() * vel.z) / l;
        if (Math.abs(Math.toDegrees(Math.acos(Math.clamp(cos, -1.0, 1.0))) - 90.0) > config.ricochetAngle) return;
        Vec3 at = hit.getLocation();
        if (acup.gunfeel.TestLog.server) acup.gunfeel.TestLog.s(self.getId(), "ricochet", "block", state, "pos", hit.getBlockPos(), "result", "stopped");
        self.setPos(at.x, at.y, at.z);
        self.discard();
        ci.cancel();
    }

    @Unique
    private boolean gunfeel$targetAlive;
    @Unique
    private float gunfeel$waterK = 1F;

    // the test log: the bullet's damage and the creature's health as the hit begins
    @Unique
    private float gunfeel$damageBefore, gunfeel$healthBefore;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$entityBefore(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        gunfeel$targetAlive = mop instanceof net.minecraft.world.phys.EntityHitResult e && e.getEntity().isAlive();
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        // the damage the bullet has where the creature is, not after the blocks behind it
        if (!self.level().isClientSide() && mop instanceof net.minecraft.world.phys.EntityHitResult && mop.getLocation() != null)
            gunfeel$steps.enter(self, mop.getLocation());
        // the client's hit this is: its part (HeadDamage); the mob is hit once by this bullet
        if (!self.level().isClientSide() && mop instanceof net.minecraft.world.phys.EntityHitResult hit) acup.gunfeel.ClientHits.impact(self, hit.getEntity());
        // water: what is left of the damage where the creature is, × f² (WaterTick); undone after the hit
        gunfeel$waterK = !self.level().isClientSide() && mop instanceof net.minecraft.world.phys.EntityHitResult
                ? acup.gunfeel.water.WaterTick.k(self, mop.getLocation()) : 1F;
        if (gunfeel$waterK != 1F) self.damage *= gunfeel$waterK;
        gunfeel$damageBefore = self.damage;
        gunfeel$healthBefore = mop instanceof net.minecraft.world.phys.EntityHitResult e && e.getEntity() instanceof LivingEntity l ? l.getHealth() : Float.NaN;
    }

    // bullet rules: "entity" and "kill" effects of the creature the bullet hit
    @Inject(method = "onImpact", at = @At("RETURN"))
    private void gunfeel$entityAfter(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (self.level().isClientSide() || !(mop instanceof net.minecraft.world.phys.EntityHitResult e)) return;
        if (gunfeel$waterK != 1F && gunfeel$waterK > 0F) self.damage /= gunfeel$waterK;
        gunfeel$waterK = 1F;
        if(self.isRemoved() && !(self instanceof acup.gunfeel.CollisionProbe) && config!=null && config.velocity>=5F
                && self.level() instanceof ServerLevel level) {
            double fraction=acup.gunfeel.ModelHits.contactTime(e.getEntity(),e.getLocation());
            if(!Double.isFinite(fraction))fraction=Math.min(1,e.getLocation().distanceTo(gunfeel$tickStart==null?self.position():gunfeel$tickStart)/Math.max(1e-9,self.getDeltaMovement().length()*motionMult()));
            PacketDistributor.sendToPlayersNear(level,null,self.getX(),self.getY(),self.getZ(),256,
                    new acup.gunfeel.BulletStopPayload(self.getId(),Math.max(0,self.ticksInAir-1)+fraction,e.getLocation()));
        }
        acup.gunfeel.BulletServer.entityHit(self, e.getEntity(), e.getLocation(), gunfeel$targetAlive, gunfeel$damageBefore, gunfeel$healthBefore);
        acup.gunfeel.ClientHits.impactEnd(self);
    }

    // the blocks still owed after the creatures this tick were hit (PierceSteps)
    @Inject(method = "tick", at = @At("RETURN"))
    private void gunfeel$pierceOwed(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (!self.level().isClientSide()) gunfeel$steps.finish(self);
        acup.gunfeel.ClientHits.tickEnd(self);
        // the path it flew this tick stirs the gas it went through
        if (self.level() instanceof ServerLevel server && gunfeel$tickStart != null) acup.gunfeel.gas.GasStir.bullet(server, gunfeel$tickStart, self.position());
        // and the AI mobs it passes or stops near are suppressed by it (only real bullets, as the players' tracers)
        if (self.level() instanceof ServerLevel server && gunfeel$tickStart != null && config != null && config.velocity >= 5F
                && !(self instanceof acup.gunfeel.CollisionProbe) && !acup.gunfeel.BeamLasers.laser(config))
            acup.gunfeel.ai.AiUnderFire.bullet(server, self, gunfeel$tickStart, self.getDeltaMovement().scale(motionMult()), gunfeel$lastImpact,
                    config.onImpact != null || acup.gunfeel.ExplosiveAmmo.heavy(config));
    }

    // announced first, then a player's bullet near mobs waits for its client's report of this tick's path (ClientHits):
    // the tick is not run, and does not count toward the bullet's life
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void gunfeel$announceOrWait(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        gunfeel$announce(self);
        if (!self.level().isClientSide() && acup.gunfeel.ClientHits.hold(self)) {
            ((Entity) self).tickCount--;
            ci.cancel();
        }
    }

    @Unique
    private void gunfeel$announce(EntityBulletBaseMK4 self) {
        gunfeel$tickStart=self.position();
        gunfeel$lastImpact = null;
        // the test log's lines during this bullet's tick (its hits, breaking, ricochets) are this bullet's
        if (acup.gunfeel.TestLog.server && !self.level().isClientSide()) acup.gunfeel.TestLog.shot(self.getId());
        if (gunfeel$announced || self instanceof acup.gunfeel.CollisionProbe || !(self.level() instanceof ServerLevel server)) return;
        gunfeel$announced = true;
        if (config == null) config = self.getBulletConfig();
        // only fast bullets get the client's tracer; slow projectiles (grenades, Shredder's plasma drops) stay NTM's
        if (config == null || config.velocity < 5F) return;
        double mult = motionMult();
        Vec3 v = self.getDeltaMovement().scale(mult);
        Entity owner = self.getOwner();
        // the start of the path the server tests on the first tick (MixinThrowableHit): the thrower's eyes when near
        LivingEntity thrower = self.getThrower();
        Vec3 eye = thrower == null ? null : acup.gunfeel.ai.AiLean.eye(thrower);
        if (eye != null && eye.distanceToSqr(self.position()) >= 9.0) eye = null;
        ShotPayload p = new ShotPayload(config.id, owner == null ? -1 : owner.getId(), self.getX(), self.getY(), self.getZ(),
                (float) v.x, (float) v.y, (float) v.z, (float) (config.gravity * mult), config.expires, self.damage,
                eye == null ? Double.NaN : eye.x, eye == null ? Double.NaN : eye.y, eye == null ? Double.NaN : eye.z, self.getId());
        PacketDistributor.sendToPlayersNear(server, null, self.getX(), self.getY(), self.getZ(), 256.0, p);
        acup.gunfeel.ClientHits.announced(self);
        if (acup.gunfeel.TestLog.server) acup.gunfeel.TestLog.s(self.getId(), "fire", "ammo", config.id, "owner", owner == null ? -1 : owner.getId(),
                "gun", owner instanceof LivingEntity le ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem()) : "-",
                "at", self.position(), "v", v, "damage", self.damage, "speedMult", ReaimTimes.number("bulletSpeed", 1.6F),
                "wear", acup.gunfeel.BulletServer.wearText(owner));
    }
}
