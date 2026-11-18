package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.particle.ParticleSpentCasing;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.SleepingCasing;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

// NTM's spent casing lying still sleeps: NTM moves every casing every tick with a collision search, a lying one too,
// and it does not move. Lying still (on the ground SETTLE ticks running, moved and moving less than STILL a tick) on a
// block (under its box's bottom corners: one to four blocks, not all of them air; a casing on a fence or a wall, whose
// top is above its block, stays awake) its tick skips that move and does what the move does to a lying casing: on the
// ground, its fall bounced back, its pitch spin damped. It wakes, and moves as NTM's, when one of those blocks changes,
// and every CHECK ticks moves as NTM's to see it still lies. Its smoke and its spin go on as NTM's. Should NTM change,
// nothing here applies.
@Mixin(value = ParticleSpentCasing.class, remap = false)
public abstract class MixinCasingSleep extends Particle implements SleepingCasing {
    @Unique
    private static final int SETTLE = 4, CHECK = 20;
    @Unique
    private static final double STILL = 1.0E-4;

    @Shadow
    private float momentumPitch;

    // the blocks under its bottom corners as it fell asleep, gunfeel$under of them; 0 awake
    @Unique
    private final long[] gunfeel$at = new long[4];
    @Unique
    private final BlockState[] gunfeel$was = new BlockState[4];
    @Unique
    private final BlockPos.MutableBlockPos gunfeel$pos = new BlockPos.MutableBlockPos();
    @Unique
    private int gunfeel$under, gunfeel$lying, gunfeel$slept;

    protected MixinCasingSleep(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Override
    public boolean gunfeel$asleep() {
        return gunfeel$under > 0;
    }

    // the blocks under it as they were
    @Unique
    private boolean gunfeel$same() {
        for (int i = 0; i < gunfeel$under; i++) if (gunfeel$state(gunfeel$at[i]) != gunfeel$was[i]) return false;
        return true;
    }

    // the blocks under its box's bottom corners taken; none if they are all air
    @Unique
    private void gunfeel$watch() {
        AABB box = getBoundingBox();
        int y = Mth.floor(box.minY - 0.01);
        boolean solid = false;
        gunfeel$under = 0;
        for (int c = 0; c < 4; c++) {
            long p = BlockPos.asLong(Mth.floor((c & 1) == 0 ? box.minX : box.maxX), y, Mth.floor((c & 2) == 0 ? box.minZ : box.maxZ));
            boolean seen = false;
            for (int i = 0; i < gunfeel$under; i++) seen |= gunfeel$at[i] == p;
            if (seen) continue;
            BlockState s = gunfeel$state(p);
            gunfeel$at[gunfeel$under] = p;
            gunfeel$was[gunfeel$under++] = s;
            solid |= !s.isAir();
        }
        if (!solid) gunfeel$under = 0;
    }

    @Unique
    private BlockState gunfeel$state(long p) {
        return level.getBlockState(gunfeel$pos.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)));
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/hbm/particle/ParticleSpentCasing;move(DDD)V"))
    private void gunfeel$sleep(ParticleSpentCasing self, double dx, double dy, double dz, Operation<Void> move) {
        if (gunfeel$under > 0 && ++gunfeel$slept % CHECK != 0 && gunfeel$same()) {
            // NTM's move on a casing lying still: it hits the ground, its fall bounces back, its pitch spin damps
            onGround = true;
            yd *= -0.5;
            if (Math.abs(momentumPitch) > 1.0E-7) momentumPitch *= -0.75F;
            return;
        }
        double x0 = x, y0 = y, z0 = z;
        move.call(self, dx, dy, dz);
        boolean still = onGround && Math.abs(x - x0) < STILL && Math.abs(y - y0) < STILL && Math.abs(z - z0) < STILL
                && Math.abs(xd) < STILL && Math.abs(zd) < STILL;
        if (!still) {
            gunfeel$under = 0;
            gunfeel$lying = 0;
        } else if (gunfeel$under > 0 || ++gunfeel$lying >= SETTLE) {
            gunfeel$watch();
            gunfeel$slept = 0;
        }
    }
}
