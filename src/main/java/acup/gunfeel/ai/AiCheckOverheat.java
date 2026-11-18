package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.items.weapon.sedna.factory.XFactory50;

import acup.gunfeel.heat.Overheat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The AI check's barrel heat (acup.gunfeel.heat.Overheat) on four lanes at once, NTM's own fire through it all:
 * <ul>
 * <li>M2 bot: a bot with an M2 told to fire on and on at a target: it heats, its bursts are cut once warm, it never takes
 * the barrel past AI_WAIT and a shot over; then its barrel set at 0.9: it holds fire until it is down to AI_RESUME;</li>
 * <li>AM-180: the trigger held by hand with the barrel kept at the top: it jams (NTM's jam) about JAM_MAX of the shots;</li>
 * <li>M2 hand: the trigger let go with the barrel at the top, over and over: the round goes off on its own about COOK_MAX
 * of the times, COOK_DELAY after; its wear about WEAR_MAX a cold shot's; the spread at the top the gun's own times
 * SPREAD_MAX more than cold;</li>
 * <li>water: a hot gun in water cools by TAU_WET, one in the air by TAU.</li>
 * </ul>
 */
final class AiCheckOverheat {
    static final int X0 = 7600, Z0 = 0, LENGTH = 40;
    static final double TARGET = 20;
    static final Identifier M2 = Identifier.fromNamespaceAndPath("hbm", "gun_m2"), AM180 = Identifier.fromNamespaceAndPath("hbm", "gun_am180");
    /** the M2 bot: its free fire, then the wait from 0.9 */
    static final int FIRE = 900, WAIT = 900;
    static final int COOK_TRIES = 40, COOK_EACH = 50;
    static final int JAM_TICKS = 1500;
    static final int WET = 200;

    private static AiCheckOverheat counting;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int ticks = -1;
    private Mob bot, jammer, cooker, wet, dry, target;
    // the M2 bot: the most heat, shots, its longest burst once warm, the run of shots so far, the tick of the last
    private float botMost;
    private int botShots, warmBurstMost, burst, waitShots, waitFirst = -1;
    private float waitFirstHeat;
    private long lastBotShot = Long.MIN_VALUE;
    private boolean waitStarted;
    // the AM-180: shots, jams, was it jammed last tick
    private int jamShots, jams;
    private boolean wasJammed;
    // the M2 by hand: tries, cook-offs, their delays, wear a cook-off added
    private int cookTry = -1, cooks, cookDelayMin = Integer.MAX_VALUE, cookDelayMax;
    private long cookTryAt;
    private boolean cookedThisTry;
    private float wearHot = -1;

    AiCheckOverheat(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (ticks < 0) {
            setup();
            ticks = 0;
            return false;
        }
        ticks++;
        long now = level.getGameTime();
        botLane(now);
        jamLane(now);
        cookLane(now);
        wetLane(now);
        for (Mob m : List.of(bot, jammer, cooker, wet, dry, target)) m.setHealth(m.getMaxHealth());
        target.invulnerableTime = 0;
        if (ticks >= Math.max(FIRE + WAIT, Math.max(JAM_TICKS, COOK_TRIES * COOK_EACH + 10))) {
            finish();
            return true;
        }
        return false;
    }

    private void setup() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int cx = (X0 - 8) >> 4; cx <= (X0 + 24) >> 4; cx++)
            for (int cz = (Z0 - 8) >> 4; cz <= (Z0 + LENGTH) >> 4; cz++) level.setChunkForced(cx, cz, true);
        for (int x = X0 - 4; x < X0 + 20; x++)
            for (int z = Z0 - 4; z < Z0 + LENGTH; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), stone, Block.UPDATE_CLIENTS);
                for (int h = 0; h < 4; h++) level.setBlock(new BlockPos(x, y + h, z), air, Block.UPDATE_CLIENTS);
            }
        // the wet one's pool, two deep
        for (int h = 0; h < 2; h++) level.setBlock(new BlockPos(X0 + 16, y + h, Z0 + 2), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        bot = shooter(X0 + 0.5, M2);
        jammer = shooter(X0 + 4.5, AM180);
        cooker = shooter(X0 + 8.5, M2);
        dry = shooter(X0 + 12.5, M2);
        wet = shooter(X0 + 16.5, M2);
        target = (Mob) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy")).create(level, EntitySpawnReason.COMMAND);
        target.setPos(X0 + 0.5, y, Z0 + 2.5 + TARGET);
        target.addTag(AiBots.TARGET_TAG);
        target.setNoGravity(true);
        health(target);
        level.addFreshEntity(target);
        AiBots.refreshTargets(level);
        if (counting == null) NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            AiCheckOverheat c = counting;
            if (c != null && e.getEntity() instanceof EntityBulletBaseMK4 b) c.bullet(b);
        });
        counting = this;
    }

    private Mob shooter(double x, Identifier gun) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, Z0 + 2.5), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(gun));
        Mob m = made.get(0);
        m.setPos(x, y, Z0 + 2.5);
        m.setYRot(0);
        m.setYHeadRot(0);
        m.setYBodyRot(0);
        health(m);
        m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(BuiltInRegistries.ITEM.getValue(gun)));
        AiBot b = AiBots.get(m);
        if (b != null) {
            b.brain = null;
            b.gunner.ammo.setInfinite();
            b.gunner.loadFree();
        }
        return m;
    }

    private static void health(Mob m) {
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
    }

    private void bullet(EntityBulletBaseMK4 b) {
        long now = level.getGameTime();
        if (b.getOwner() == bot) {
            float h = Overheat.heat(bot.getMainHandItem(), now);
            botShots++;
            if (ticks > FIRE) {
                waitShots++;
                if (waitFirst < 0) {
                    waitFirst = ticks - FIRE - 1;
                    waitFirstHeat = h;
                }
            } else botMost = Math.max(botMost, h);
            burst = now - lastBotShot <= AiGunner.BURST_GAP ? burst + 1 : 1;
            lastBotShot = now;
            if (h > Overheat.AI_SHORT + 0.02F) warmBurstMost = Math.max(warmBurstMost, burst);
        } else if (b.getOwner() == jammer) jamShots++;
        else if (b.getOwner() == cooker && cookTry >= 0 && !cookedThisTry) {
            cookedThisTry = true;
            cooks++;
            int d = (int) (now - cookTryAt);
            cookDelayMin = Math.min(cookDelayMin, d);
            cookDelayMax = Math.max(cookDelayMax, d);
            // NTM adds the shot's wear after the bullet is in the world: read next tick
            if (wearHot < 0) wearPending = true;
        }
    }

    private float wearBefore;
    private boolean wearPending;

    /** the M2 bot: fire on at the target, then from 0.9 */
    private void botLane(long now) {
        AiBot b = AiBots.get(bot);
        if (b == null) return;
        ItemStack stack = bot.getMainHandItem();
        if (ticks == FIRE + 1) {
            Overheat.set(stack, 0.9F, now);
            waitStarted = true;
        }
        Vec3 chest = target.position().add(0, target.getBbHeight() * 0.6, 0);
        b.gunner.track(target.getId(), chest, now);
        b.gunner.fire(ticks < FIRE + WAIT);
    }

    /** the AM-180 held down with its barrel at the top: count its jams */
    private void jamLane(long now) {
        if (ticks > JAM_TICKS) return;
        ItemStack stack = jammer.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        AiBot b = AiBots.get(jammer);
        if (b != null && ItemGunBaseNT.getState(stack, 0) == ItemGunBaseNT.GunState.IDLE) b.gunner.loadFree();
        Overheat.set(stack, 1F, now);
        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        boolean jammed = state == ItemGunBaseNT.GunState.JAMMED;
        if (jammed && !wasJammed) jams++;
        wasJammed = jammed;
        // ready: let go and pull again (a jam ends ready for a new pull)
        if (state == ItemGunBaseNT.GunState.IDLE) {
            gun.handleKeybind(jammer, null, stack, EnumKeybind.GUN_PRIMARY, false);
            gun.handleKeybind(jammer, null, stack, EnumKeybind.GUN_PRIMARY, true);
        }
    }

    /** the M2 let go at the top heat each try: does the round go off on its own */
    private void cookLane(long now) {
        if (wearPending) {
            wearPending = false;
            wearHot = ItemGunBaseNT.getWear(cooker.getMainHandItem(), 0) - wearBefore;
        }
        int t = ticks - 1;
        if (t % COOK_EACH != 0 || t / COOK_EACH >= COOK_TRIES) return;
        ItemStack stack = cooker.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT)) return;
        AiBot b = AiBots.get(cooker);
        if (b != null) b.gunner.loadFree();
        if (cookTry < 0) {
            // the spread at the top and cold, NTM's calcSpread with the mod's on top
            Receiver r = ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0];
            var ctx = new ItemGunBaseNT.LambdaContext(((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0), cooker, null, 0);
            Overheat.set(stack, 0F, now);
            float cold = Lego.calcSpread(ctx, stack, r, XFactory50.bmg50_fmj, true, 0, true);
            Overheat.set(stack, 1F, now);
            float hot = Lego.calcSpread(ctx, stack, r, XFactory50.bmg50_fmj, true, 0, true);
            float want = r.getInnateSpread(stack) * Overheat.SPREAD_MAX;
            check.out(String.format(Locale.ROOT, "     overheat: M2 spread cold %.4f, at the top %.4f (+%.4f, the gun's own %.4f x%.0f)", cold, hot, hot - cold,
                    r.getInnateSpread(stack), Overheat.SPREAD_MAX));
            check.check("overheat: an M2 at the top spreads its own spread x" + Overheat.SPREAD_MAX + " more than cold", Math.abs(hot - cold - want) < 1e-4F);
        }
        cookTry++;
        cookedThisTry = false;
        cookTryAt = now;
        wearBefore = ItemGunBaseNT.getWear(stack, 0);
        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
        ItemGunBaseNT.setPrimary(stack, 0, false);
        // just let go: its last shot a tick ago at the top
        Overheat.set(stack, 1F, now - 1);
    }

    /** a hot M2 in the water, one beside it in the air: both at the top at WET_FROM, read WET ticks after */
    private void wetLane(long now) {
        wet.setPos(X0 + 16.5, y, Z0 + 2.5);
        wet.setDeltaMovement(Vec3.ZERO);
        if (ticks == WET_FROM) {
            Overheat.set(wet.getMainHandItem(), 1F, now);
            Overheat.set(dry.getMainHandItem(), 1F, now);
        } else if (ticks == WET_FROM + WET) {
            wetHeat = Overheat.heat(wet.getMainHandItem(), now);
            dryHeat = Overheat.heat(dry.getMainHandItem(), now);
        }
    }

    static final int WET_FROM = 20;
    private float wetHeat = -1, dryHeat = -1;

    private void finish() {
        // from 0.9 down to AI_RESUME in the air
        int cool = (int) Math.ceil(Overheat.TAU * Math.log(0.9 / Overheat.AI_RESUME));
        check.out(String.format(Locale.ROOT, "     overheat: M2 bot fired %d shots in %d ticks, the most heat %.2f, its longest burst warm %d (at most %d); from 0.9: its first shot "
                + "%d ticks after (cooled to %.2f in %d), %d shots in all", botShots - waitShots, FIRE, botMost, warmBurstMost, Overheat.Gun.M2.aiBurst, waitFirst, waitFirstHeat,
                cool, waitShots));
        check.check("overheat: an M2 bot firing on keeps its barrel under " + Overheat.AI_WAIT + " and a shot, bursts cut once warm",
                botShots - waitShots > 150 && botMost < Overheat.AI_WAIT + 0.02F && warmBurstMost <= Overheat.Gun.M2.aiBurst + 1 && botMost > Overheat.AI_SHORT);
        check.check("overheat: an M2 bot at 0.9 holds fire until " + Overheat.AI_RESUME + ", then fires again",
                waitFirst >= cool - 2 && waitFirstHeat <= Overheat.AI_RESUME + 0.01F && waitShots > 10);
        float jamShare = jamShots == 0 ? 0F : (float) jams / jamShots;
        check.out(String.format(Locale.ROOT, "     overheat: AM-180 at the top: %d shots, %d jams (%.1f%%, want %.0f%%)", jamShots, jams, 100F * jamShare, 100F * Overheat.JAM_MAX));
        check.check("overheat: an AM-180 at the top jams about " + Overheat.JAM_MAX + " of its shots", jamShots > 100 && jamShare > 0.04F && jamShare < 0.2F);
        float cookShare = (float) cooks / COOK_TRIES;
        check.out(String.format(Locale.ROOT, "     overheat: M2 let go at the top: %d of %d went off (%.0f%%, want %.0f%%), %d to %d ticks after; wear of the shot %.2f (x%.0f)",
                cooks, COOK_TRIES, 100F * cookShare, 100F * Overheat.COOK_MAX, cooks == 0 ? 0 : cookDelayMin, cookDelayMax, wearHot, Overheat.WEAR_MAX));
        check.check("overheat: an M2 let go at the top cooks off about half the times, " + Overheat.COOK_DELAY_MIN + " to " + Overheat.COOK_DELAY_MAX + " ticks after",
                cookShare > 0.2F && cookShare < 0.8F && cookDelayMin >= Overheat.COOK_DELAY_MIN && cookDelayMax <= Overheat.COOK_DELAY_MAX + 2);
        check.check("overheat: a shot at the top wears the barrel about x" + Overheat.WEAR_MAX, wearHot > Overheat.WEAR_MAX * 0.9F && wearHot < Overheat.WEAR_MAX * 1.01F);
        float wantWet = (float) Math.exp(-WET / Overheat.TAU_WET), wantDry = (float) Math.exp(-WET / Overheat.TAU);
        check.out(String.format(Locale.ROOT, "     overheat: from the top %d ticks: in water %.2f (want %.2f), in the air %.2f (want %.2f)", WET, wetHeat, wantWet, dryHeat,
                wantDry));
        check.check("overheat: a hot gun cools four times as fast in water", Math.abs(wetHeat - wantWet) < 0.06F && Math.abs(dryHeat - wantDry) < 0.02F);
        for (Mob m : List.of(bot, jammer, cooker, wet, dry, target)) m.discard();
        counting = null;
    }
}
