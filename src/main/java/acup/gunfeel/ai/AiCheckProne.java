package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import acup.gunfeel.Spread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's lying down, on the crowd's open field: a soldier behind a wall one high goes down between bursts
 * and to reload and gets up to fire (never fires lying there); one in the open with the target 40 blocks off lies and
 * fires lying, with the crawling spread; one with the target 8 blocks off never lies; one in the open with the target
 * 20 blocks off lies when shots fly past it; one lying far off sent crawling 5 blocks crawls between bursts and
 * keeps still to fire (the server's no-shot-while-crawling rule, ProneFire: no pull of its trigger is thrown away).
 * Lying, it is a crawling player's size.
 */
final class AiCheckProne {
    private enum Phase {
        LOW, FAR, NEAR, PINNED, CRAWL, DONE
    }

    static final int RUN = 260, SHORT = 160, CRAWL_AT = 50;
    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.LOW;
    private int ticks;
    private Mob soldier, target;
    private AiBot bot;
    private int lyingTicks, pullsLying, pullsStanding, lastPulls;
    /** ticks it lay moving faster than the crawling rule's speed, ticks the target was hit while it lay; all scenes */
    private int crawlTicks, hitsLying, crawlPullsAll, crawlHeldAll, shortLiesAll;
    private Vec3 crawlFrom;
    private double fastestLying;
    private float lyingHeight = -1, lyingEye = -1, spreadLying = -1, spreadStanding = -1;

    AiCheckProne(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (phase == Phase.DONE) return true;
        if (ticks == 0) start();
        ticks++;
        if (soldier == null) return phase == Phase.DONE;
        watch();
        if (phase == Phase.PINNED && ticks % 5 == 0 && ticks <= 60) flyPast();
        if (phase == Phase.CRAWL && ticks == CRAWL_AT && bot != null) {
            // lying, firing: sent 5 blocks off, towards the target
            crawlFrom = soldier.position();
            bot.mover.goTo(BlockPos.asLong(97, y, 38), 0.35, AiSoldier.WALK);
        }
        int length = phase == Phase.LOW || phase == Phase.FAR || phase == Phase.CRAWL ? RUN : SHORT;
        if (ticks >= length) finish();
        return phase == Phase.DONE;
    }

    private void set(int x, int yy, int z, boolean stone) {
        level.setBlock(new BlockPos(x, yy, z), (stone ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void health(Mob m) {
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
    }

    private void start() {
        // run alone (-PaiStage=prone), the crowd's field is not there yet
        if (phase == Phase.LOW && level.getBlockState(new BlockPos(AiCheckCrowd.X0, y - 1, AiCheckCrowd.Z0)).isAir()) AiCheckCrowd.field(level, y);
        // the low wall stands only for the first scene
        for (int x = 105; x <= 115; x++) set(x, y, 20, phase == Phase.LOW);
        Vec3 s, t;
        switch (phase) {
            case LOW -> {
                s = new Vec3(110.5, y, 24.5);
                t = new Vec3(110.5, y, 5.5);
            }
            case FAR, CRAWL -> {
                s = new Vec3(93.5, y, 41.5);
                t = new Vec3(122.5, y, 12.5);
            }
            case NEAR -> {
                s = new Vec3(110.5, y, 24.5);
                t = new Vec3(110.5, y, 16.5);
            }
            default -> {
                s = new Vec3(110.5, y, 34.5);
                t = new Vec3(110.5, y, 14.5);
            }
        }
        List<Mob> made = AiSpawn.spawn(level, s, AiSpawn.Kind.DUMMY, 1, null);
        if (made.isEmpty()) {
            check.check("the lying check: a soldier spawns", false);
            phase = Phase.DONE;
            return;
        }
        soldier = made.get(0);
        soldier.setPos(s.x, s.y, s.z);
        float yaw = (float) (Math.atan2(t.z - s.z, t.x - s.x) * 180 / Math.PI) - 90F;
        soldier.setYRot(yaw);
        soldier.setYHeadRot(yaw);
        soldier.setYBodyRot(yaw);
        health(soldier);
        bot = AiBots.get(soldier);
        if (bot != null) bot.gunner.ammo.setInfinite();
        if (phase == Phase.LOW)
            // whoever it shoots dies by an AI dummy, not by "12.3 / 20.0" (NTM names its dummy by its health)
            check.check("an AI dummy is called so in death messages (" + soldier.getDisplayName().getString() + ")",
                    soldier.getDisplayName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents named
                            && named.getKey().equals("ntmgunfeel.armsai.dummy"));
        target = (Mob) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"))
                .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        target.setPos(t.x, t.y, t.z);
        target.addTag(AiBots.TARGET_TAG);
        health(target);
        level.addFreshEntity(target);
        AiBots.refreshTargets(level);
        lyingTicks = pullsLying = pullsStanding = lastPulls = crawlTicks = hitsLying = 0;
        fastestLying = 0;
    }

    private void watch() {
        boolean hit = target.getHealth() < target.getMaxHealth();
        soldier.setHealth(soldier.getMaxHealth());
        target.setHealth(target.getMaxHealth());
        if (bot == null) return;
        boolean lying = soldier.getPose() == Pose.SWIMMING;
        int pulls = bot.gunner.pulls;
        if (pulls > lastPulls) {
            if (lying) pullsLying += pulls - lastPulls;
            else pullsStanding += pulls - lastPulls;
        }
        lastPulls = pulls;
        if (lying) {
            lyingTicks++;
            if (hit) hitsLying++;
            Vec3 v = ((acup.gunfeel.mixin.mc.EntityAccess) soldier).gunfeel$lastKnownSpeed();
            if (v != null && v.horizontalDistance() * 20 > acup.gunfeel.ProneFire.DEFAULT_SPEED) crawlTicks++;
            if (v != null) fastestLying = Math.max(fastestLying, v.horizontalDistance() * 20);
            lyingHeight = soldier.getBbHeight();
            lyingEye = soldier.getEyeHeight();
            spreadLying = Spread.apply(soldier, soldier.getMainHandItem(), false, 1F);
        } else if (spreadStanding < 0 && soldier.onGround() && soldier.getKnownSpeed().horizontalDistanceSqr() < 1e-6)
            spreadStanding = Spread.apply(soldier, soldier.getMainHandItem(), false, 1F);
    }

    /** an arrow from the target's side that passes a block and a bit beside the soldier's chest */
    private void flyPast() {
        Vec3 chest = soldier.position().add(0, 1.0, 0);
        Vec3 from = target.position().add(0, 1.5, 0);
        Vec3 aside = chest.add(1.3, 0, 0);
        Arrow a = new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
        a.setOwner(target);
        Vec3 d = aside.subtract(from);
        a.shoot(d.x, d.y, d.z, 3.0F, 0F);
        a.setNoGravity(true);
        level.addFreshEntity(a);
    }

    private void finish() {
        AiProne p = bot == null ? null : bot.prone;
        String what = p == null ? "-" : String.format(Locale.ROOT, "lay %d ticks (%d times down), fired %d lying and %d standing (%d shots, %d ticks held for a friend, %s)",
                lyingTicks, p.downs, pullsLying, pullsStanding, bot.gunner.shots, bot.gunner.holds,
                bot.memory.contacts().stream().map(c -> c.state.toString()).toList());
        if (bot != null) {
            crawlPullsAll += bot.gunner.crawlPulls;
            crawlHeldAll += bot.gunner.crawlHeldTicks;
            shortLiesAll += p.shortLies;
            what += String.format(Locale.ROOT, "; up early %d times, last reload %d ticks", p.shortLies, bot.gunner.lastReload);
            what += String.format(Locale.ROOT, "; lying: %d shots, target hit %d ticks, crawled %d ticks (at most %.2f blocks a second); pulls crawling %d, trigger held crawling %d ticks",
                    bot.gunner.shotsLying, hitsLying, crawlTicks, fastestLying, bot.gunner.crawlPulls, bot.gunner.crawlHeldTicks);
        }
        switch (phase) {
            case LOW -> {
                check.out("     behind a wall one high, the target 19 blocks off: " + what);
                check.check("behind low cover it fires only standing, and goes down for no short reload (under " + AiProne.LONG_RELOAD + " ticks)",
                        p != null && pullsStanding > 0 && pullsLying == 0 && (bot.gunner.lastReload >= AiProne.LONG_RELOAD || p.downs == 0));
                check.out(String.format(Locale.ROOT, "     lying it is %.1f high, eyes at %.1f (a crawling player: 0.6, 0.4)", lyingHeight, lyingEye));
                check.check("lying it is a crawling player's size", Math.abs(lyingHeight - 0.6F) < 1e-3 && Math.abs(lyingEye - 0.4F) < 1e-3);
            }
            case FAR -> {
                check.out(String.format(Locale.ROOT, "     in the open, the target 41 blocks off: %s; spread lying %.3f, standing still %.3f (NTM's x1)", what,
                        spreadLying, spreadStanding));
                check.check("in the open with the target far off it lies and fires lying and hits, with the crawling spread",
                        p != null && pullsLying > 0 && hitsLying > 0 && spreadLying > 0 && spreadLying < spreadStanding);
            }
            case NEAR -> {
                check.out("     in the open, the target 8 blocks off: " + what);
                check.check("close up it never lies", p != null && p.downs == 0 && pullsStanding > 0);
            }
            case PINNED -> {
                check.out(String.format(Locale.ROOT, "     in the open, the target 20 blocks off, arrows past it: %d near misses; %s", p == null ? 0 : p.nearMisses, what));
                check.check("shots flying past pin it down: it lies", p != null && p.nearMisses >= AiProne.PINNING && p.downs > 0);
            }
            case CRAWL -> {
                double moved = crawlFrom == null ? 0 : crawlFrom.distanceTo(soldier.position());
                check.out(String.format(Locale.ROOT, "     in the open, the target 41 blocks off, sent 5 blocks off lying (crawled %.1f, legs %s): %s", moved, bot.mover.state(), what));
                check.check("lying, it crawls between bursts and fires only still: it crawls, fires lying and hits",
                        p != null && crawlTicks > 0 && moved > 3 && bot.gunner.shotsLying > 0 && hitsLying > 0);
                check.check("once down it stays down " + AiProne.MIN_LIE / 20 + " s at least unless sent running, in any scene (" + shortLiesAll + " times up early)",
                        shortLiesAll == 0);
                check.check("no pull of its trigger while crawling, in any scene (" + crawlPullsAll + " pulls, held " + crawlHeldAll + " ticks)",
                        crawlPullsAll == 0 && crawlHeldAll == 0);
            }
            default -> {
            }
        }
        soldier.discard();
        target.discard();
        for (var e : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Arrow.class), a -> true)) e.discard();
        soldier = null;
        phase = Phase.values()[phase.ordinal() + 1];
        ticks = 0;
        if (phase == Phase.DONE) for (int x = 105; x <= 115; x++) set(x, y, 20, false);
    }
}
