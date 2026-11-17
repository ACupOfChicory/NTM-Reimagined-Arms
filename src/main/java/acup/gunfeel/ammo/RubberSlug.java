package acup.gunfeel.ammo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.EnumCasingType;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.particle.SpentCasing;
import com.hbm.registration.RegistryHandle;

import acup.gunfeel.DebugLog;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;

/**
 * The 12 gauge rubber slug: a less lethal round. A hit does RubberRule.DAMAGE before armour whatever the gun (armour
 * takes its share as of any bullet: the balance's armour, NTM's on mobs), and never takes anyone below RubberRule.FLOOR
 * (RubberServer). It pushes the one hit back along the shot, slows and confuses a player or an AI bot and frightens an
 * undead off (RubberServer). Hardly goes through anything: little damage for the bullet rules' blocks, no piercing.
 * Goes where NTM's 12 gauge buckshot goes, as the shock shell.
 */
public final class RubberSlug {
    public static RegistryHandle<Item> ITEM;
    private static BulletConfig config;
    /** NTM's damage of the shot times this (the bullet rules' blocks see it; a hit's own is DAMAGE) */
    static final float DAMAGE_MULT = 0.2F;

    private RubberSlug() {
    }

    public static void register(IEventBus modBus) {
        ITEM = NewAmmo.item(modBus, "ammo_g12_rubber_slug");
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(RubberSlug::setup));
        RubberServer.register();
    }

    /** the slug's config (null before the common setup) */
    public static BulletConfig config() {
        return config;
    }

    public static boolean is(BulletConfig c) {
        return c != null && c == config;
    }

    static synchronized void setup() {
        if (config != null) return;
        config = new BulletConfig()
                .setItem(() -> ITEM.get())
                .setCasing(EnumCasingType.SHOTSHELL, 6)
                .setDamage(DAMAGE_MULT)
                .setSpread(0.0F)
                .setRicochetAngle(25F)
                .setKnockback(0F)
                .setHeadshot(1F)
                .setCasing(new SpentCasing(SpentCasing.CasingType.SHOTGUN).setColor(0xD9D6CF, SpentCasing.COLOR_CASE_12GA).setScale(0.75F)
                        .register("12GA_RUBBER"));
        config.onEntityHit = (bullet, hit) -> {
            LivingEntity target = hit instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity l ? l : null;
            // its own damage whatever the gun and the table: armour and the part hit after, as for any bullet
            if (target != null) bullet.damage = RubberRule.DAMAGE;
            BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet, hit);
            if (target != null) RubberServer.hit(bullet, target);
        };
        int mags = NewAmmo.addTo(config, g -> true, accepted -> accepted.contains(XFactory12ga.g12));
        DebugLog.LOGGER.info("The 12 gauge rubber slug: config {}, in {} magazines", config.id, mags);
    }
}
