package acup.gunfeel.ammo;

import com.hbm.data.ExplosionData;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.items.EnumCasingType;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory50;
import com.hbm.items.weapon.sedna.factory.XFactoryCatapult;
import com.hbm.particle.SpentCasing;
import com.hbm.registration.RegistryHandle;
import com.hbm.saveddata.satellites.SatelliteDetector;
import com.hbm.sound.ModSounds;

import acup.gunfeel.DebugLog;
import acup.gunfeel.nuke.MiniWasteMutator;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/**
 * The nuclear .50 BMG: no damage of its own, wherever it hits (a block, a creature; point blank too) it goes off as NTM's
 * Fat Mine times NuclearRule.SCALE, breaking no blocks: the blast's hurt and knock on the creatures, radiation, the
 * cloud (NuclearFx) and the mini nuke's sound. Only the AMAT rifles take it (gun_amat, its Subtlety and Penance), and
 * each of its shots wears the rifle by a fixed share of its durability (NuclearRule.wear, MixinNuclearWear) in place of
 * NTM's wear a shot.
 */
public final class NuclearBmg {
    public static RegistryHandle<Item> ITEM;
    private static BulletConfig config;

    private NuclearBmg() {
    }

    public static void register(IEventBus modBus) {
        ITEM = NewAmmo.item(modBus, "ammo_bmg50_nuclear");
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(NuclearBmg::setup));
    }

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
                .setCasing(EnumCasingType.LARGE_STEEL, 1)
                .setDamage(0F)
                .setWear(0F)
                .setOnImpact(NuclearBmg::impact)
                .setCasing(new SpentCasing(SpentCasing.CasingType.BOTTLENECK).setColor(0x8C3B4F).setScale(1.5F).register("bmg50nuclear"));
        int mags = NewAmmo.addTo(config, g -> NuclearRule.shots(NewAmmo.gunId(g)) > 0, acc -> acc.contains(XFactory50.bmg50_sp));
        DebugLog.LOGGER.info("The nuclear .50: config {}, in {} magazines", config.id, mags);
    }

    // where it hits: the blast, a little off the face it hit (out of the block); not the shooter it leaves (NTM's own guard)
    private static void impact(EntityBulletBaseMK4 bullet, HitResult mop) {
        if (mop instanceof EntityHitResult eh && bullet.tickCount < 3 && eh.getEntity() == bullet.getThrower()) return;
        if (bullet.isRemoved() || !(bullet.level() instanceof ServerLevel level)) return;
        bullet.discard();
        Direction dir = mop instanceof BlockHitResult b ? b.getDirection() : Direction.UP;
        Vec3 hit = mop.getLocation();
        blast(level, hit.x + dir.getStepX() * 0.25, hit.y + dir.getStepY() * 0.25, hit.z + dir.getStepZ() * 0.25);
    }

    /** the Fat Mine's blast (BlockLandmineFat.explodeVariant) times SCALE, no blocks broken, a nuke's waste laid */
    public static void blast(ServerLevel level, double x, double y, double z) {
        float damage = NuclearRule.damage(ExplosionData.MINE_NUKE_DAMAGE.get());
        ExplosionVNT vnt = new ExplosionVNT(level, x, y, z, NuclearRule.size());
        // the blast breaks nothing, but what its rays reach dies as under a nuke (MiniWasteMutator)
        MiniWasteMutator.apply(vnt);
        vnt.setEntityProcessor(new EntityProcessorCrossSmooth(NuclearRule.MINE_NODE, damage).withRangeMod(NuclearRule.MINE_RANGE_MOD));
        vnt.setPlayerProcessor(new PlayerProcessorStandard());
        vnt.explode();
        XFactoryCatapult.incrementRad(level, x, y, z, NuclearRule.rad());
        SatelliteDetector.reportEvent(level, 300, SatelliteDetector.BurstIntensity.LOW, x, z);
        NuclearFx.miniNuke(level, x, y, z, NuclearRule.size());
        level.playSound(null, x, y, z, (SoundEvent) ModSounds.GUN_MINI_NUKE_EXPLOSION.get(), SoundSource.BLOCKS, NuclearRule.MINE_VOLUME, 0.9F);
        // the waste out past the blast, dying away at its edge, as a mini nuke's (MiniNukeCloud.lay)
        acup.gunfeel.nuke.MiniNukeCloud.lay(level, x, y, z, NuclearRule.size());
    }

    /** the wear a shot of the round leaves on {@code stack}'s config {@code index} (MixinNuclearWear) */
    public static float wearAfter(ItemStack stack, GunConfig gun, int index) {
        return NuclearRule.wear(ItemGunBaseNT.getWear(stack, index), gun.getDurability(stack), NuclearRule.shots(NewAmmo.gunId(stack.getItem())));
    }

    /**
     * whether the receiver would fire a nuclear round from a worn out gun (owner 2026-11-17: not that; the gun's other
     * rounds fire as NTM has it)
     */
    public static boolean blocked(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, com.hbm.items.weapon.sedna.Receiver rec) {
        if (config == null || stack == null || ctx == null || ctx.config() == null || rec == null) return false;
        if (!is(rec.getMagazine(stack).getType(stack, ctx.inventory()) instanceof BulletConfig c ? c : null)) return false;
        return NuclearRule.wornOut(ItemGunBaseNT.getWear(stack, ctx.configIndex()), ctx.config().getDurability(stack));
    }
}
