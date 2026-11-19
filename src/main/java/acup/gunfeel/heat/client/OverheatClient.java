package acup.gunfeel.heat.client;

import acup.gunfeel.heat.Overheat;
import acup.gunfeel.heat.OverheatServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client: a hot barrel smokes at the muzzle of whoever holds it, players and AI bots alike (the heat comes with the gun the
 * server sends). Thin wisps from SMOKE_FROM, thicker to the top heat. One's own gun in first person has NTM's smoke after
 * the shot (longer when hot: MixinOverheat), so it gets none of these.
 */
public final class OverheatClient {
    private OverheatClient() {
    }

    /** wisps only of guns this near the camera, blocks */
    static final double NEAR = 48;
    /** a wisp a tick at most, this share of the ticks at the top heat */
    static final float WISPS = 0.4F;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
    }

    private static void tick() {
        if (!Overheat.on()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        Entity camera = mc.getCameraEntity();
        if (camera == null) return;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        long now = level.getGameTime();
        Vec3 eye = camera.getEyePosition();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || e.isInvisible() || e.distanceToSqr(eye) > NEAR * NEAR) continue;
            if (e == camera && firstPerson) continue;
            if (Overheat.gun(living.getMainHandItem()) == null) continue;
            float h = Overheat.heat(living.getMainHandItem(), now);
            float share = WISPS * Overheat.ramp(h, Overheat.SMOKE_FROM, 1F);
            if (share <= 0F || level.getRandom().nextFloat() >= share) continue;
            Vec3 m = OverheatServer.muzzle(living);
            level.addParticle(ParticleTypes.SMOKE, m.x, m.y, m.z, (level.getRandom().nextFloat() - 0.5F) * 0.01, 0.02 + 0.03 * h,
                    (level.getRandom().nextFloat() - 0.5F) * 0.01);
        }
    }
}
