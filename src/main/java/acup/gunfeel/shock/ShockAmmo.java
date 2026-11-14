package acup.gunfeel.shock;

import java.util.List;

import com.hbm.items.EnumCasingType;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.particle.SpentCasing;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.RegistryHandle;

import acup.gunfeel.DebugLog;
import acup.gunfeel.ModRegistrar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * The 12 gauge electroshock shell (a Taser XREP of NTM's shotguns): one round, little damage, and a hit on someone
 * shocks them (ShockServer). An item of the mod's own; its NTM config is made once at the common setup, on both sides
 * in the same order, and goes into every magazine that takes NTM's 12 gauge buckshot (the Maresleg and its kin, the
 * SPAS-12, the Auto-Shotgun and the Sexy one): not the Shredder's, whose rounds are beams.
 */
public final class ShockAmmo {
    public static RegistryHandle<Item> ITEM;
    private static BulletConfig config;
    // NTM's damage of a click times the ammo's: one pellet of buckshot (the table brings it to about 2)
    public static final float DAMAGE_MULT = 0.125F;

    private ShockAmmo() {
    }

    public static void register(IEventBus modBus) {
        ModRegistrar.subscribe(modBus);
        ITEM = NeoForgeRegistrar.registerItem(ModRegistrar.NAMESPACE + ":ammo_g12_shock", Item::new, () -> new Item.Properties().stacksTo(64));
        ResourceKey<CreativeModeTab> weapons = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("hbm", "weapons"));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey().equals(weapons)) e.accept(new ItemStack(ITEM.get()));
        });
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(ShockAmmo::setup));
        // the shock's slowing, tick by tick as it wears off
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> ShockServer.tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone.class,
                e -> ShockServer.cloned(e.getEntity()));
    }

    /** the shell's config (null before the common setup) */
    public static BulletConfig config() {
        return config;
    }

    static synchronized void setup() {
        if (config != null) return;
        config = new BulletConfig()
                .setItem(() -> ITEM.get())
                .setCasing(EnumCasingType.BUCKSHOT_ADVANCED, 6)
                .setDamage(DAMAGE_MULT)
                .setSpread(0.01F)
                .setRicochetAngle(5F)
                .setKnockback(0F)
                .setHeadshot(1F)
                .setCasing(new SpentCasing(SpentCasing.CasingType.SHOTGUN).setColor(0x2F6FD8, SpentCasing.COLOR_CASE_12GA).setScale(0.75F).register("12GA_SHOCK"));
        config.onEntityHit = (bullet, hit) -> {
            // what the target had before NTM's damage: a hit that did no harm (no PvP, a claim, invulnerable) does not shock
            float before = hit instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() instanceof net.minecraft.world.entity.LivingEntity l
                    ? l.getHealth() + l.getAbsorptionAmount() : Float.NaN;
            BulletConfig.LAMBDA_STANDARD_ENTITY_HIT.accept(bullet, hit);
            ShockServer.hit(bullet, hit, before);
        };
        int mags = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof ItemGunBaseNT gun) mags += add(gun);
        }
        DebugLog.LOGGER.info("The 12 gauge shock shell: config {}, in {} magazines", config.id, mags);
    }

    // into each magazine of the gun that takes NTM's 12 gauge buckshot; how many it went into
    private static int add(ItemGunBaseNT gun) {
        int n = 0;
        // no stack: the gun's own configs (stacks cannot be made yet at the common setup; a weapon mod's magazine
        // is NTM's own, made from these)
        ItemStack stack = null;
        for (int c = 0; c < gun.getConfigCount(); c++) {
            for (Receiver rec : gun.getConfig(stack, c).getReceivers(stack)) {
                IMagazine<?> mag = rec.getMagazine(stack);
                if (mag instanceof MagazineSingleTypeBase s && takes(s.acceptedBullets) && !s.acceptedBullets.contains(config)) {
                    s.acceptedBullets.add(config);
                    n++;
                }
            }
        }
        return n;
    }

    static boolean takes(List<BulletConfig> accepted) {
        return accepted.contains(XFactory12ga.g12);
    }
}
