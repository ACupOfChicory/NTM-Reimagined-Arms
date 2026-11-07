package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import acup.gunfeel.mixin.mc.MobGoalsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The part of the AI check that needs the server to run: mobs spawned by the command's own code walk their paths,
 * find another way when one is walled up, shoot, reload from what they carry and stop when it is spent, and stand
 * still with nobody about. One step a server tick, each with a time limit; the server stops at the end.
 */
final class AiCheckTicks {
    private enum Phase {
        SETUP, WALK, AROUND, SHOOT, SPEND, ENDLESS, FREEZE, DONE
    }

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.SETUP;
    private int ticks, total;
    private Mob walker, target, fbi;
    private AiBot bot;
    private boolean passedHole, closed;
    private int crossedAt = Integer.MIN_VALUE;
    private int searchesBefore, reserveBefore, loadedBefore, loadedTotalBefore;
    private float healthBefore;

    AiCheckTicks(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    /** one server tick; true when the check is over */
    boolean tick() {
        ticks++;
        total++;
        switch (phase) {
            case SETUP -> setup();
            case WALK -> walk();
            case AROUND -> around();
            case SHOOT -> shoot();
            case SPEND -> spend();
            case ENDLESS -> endless();
            case FREEZE -> freeze();
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    private void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private static int goals(Mob m, Class<?> kind) {
        int n = 0;
        for (WrappedGoal w : ((MobGoalsAccess) m).gunfeel$goals().getAvailableGoals()) if (kind.isInstance(w.getGoal())) n++;
        return n;
    }

    private void setup() {
        // the yard's chunks tick with no player about
        for (int cx = 0; cx <= 2; cx++)
            for (int cz = 0; cz <= 2; cz++) level.setChunkForced(cx, cz, true);

        List<Mob> made = AiSpawn.spawn(level, new Vec3(12.5, y, 18.5), AiSpawn.Kind.DUMMY, 1, null);
        check.check("the command's code spawns a dummy", made.size() == 1);
        if (made.isEmpty()) {
            next(Phase.DONE);
            return;
        }
        walker = made.get(0);
        walker.setPos(12.5, y, 18.5);
        bot = AiBots.get(walker);
        check.check("the dummy is an AI mob with its one goal", bot != null && goals(walker, AiGoal.class) == 1);
        AiAdapter.adapt(walker);
        AiAdapter.adapt(walker);
        check.check("taken over again: still one goal", goals(walker, AiGoal.class) == 1);
        check.check("its navigation keeps the AI's paths when blocks change",
                ((AiNavigation) walker.getNavigation()).gunfeel$aiOwned() && !walker.getNavigation().shouldRecomputePath(new BlockPos(15, y, 24)));
        int cap = bot.gunner.capacity();
        check.check("comes in with a full gun and " + AiAdapter.RESERVE_MAGS + " magazines besides",
                cap > 0 && bot.gunner.loaded() == cap && bot.gunner.ammo.reserve() == cap * AiAdapter.RESERVE_MAGS);
        check.out(String.format(Locale.ROOT, "     dummy: %s, %d rounds a magazine, %d besides",
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(walker.getMainHandItem().getItem()), cap, bot.gunner.ammo.reserve()));

        List<Mob> agents = AiSpawn.spawn(level, new Vec3(30.5, y, 12.5), AiSpawn.Kind.FBI, 1, null);
        if (!agents.isEmpty()) {
            fbi = agents.get(0);
            check.check("and no goal of its own picks a target", ((MobGoalsAccess) fbi).gunfeel$targets().getAvailableGoals().isEmpty());
            check.check("it holds an NTM gun, loaded", AiBots.get(fbi) != null && AiBots.get(fbi).gunner.gun() != null
                    && AiBots.get(fbi).gunner.loaded() == AiBots.get(fbi).gunner.capacity());
        } else check.check("an FBI agent spawns", false);

        // a target so the dummy has someone about (not a player: it would stand still), out of the way
        target = (Mob) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"))
                .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        target.setPos(36.5, y, 12.5);
        target.addTag(AiBots.TARGET_TAG);
        var hp = target.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        target.setHealth(1000);
        level.addFreshEntity(target);

        bot.mover.goTo(BlockPos.asLong(16, y, 28), 0.8, 1.0);
        next(Phase.WALK);
    }

    /** an FBI agent's goals once NTM's load hook has run (it gives a mob with an NTM gun a goal to shoot it) */
    private void fbiGoals() {
        if (fbi == null || ticks != 30) return;
        StringBuilder names = new StringBuilder();
        for (WrappedGoal w : ((MobGoalsAccess) fbi).gunfeel$goals().getAvailableGoals())
            names.append(' ').append(w.getPriority()).append(':').append(w.getGoal().getClass().getSimpleName());
        int all = ((MobGoalsAccess) fbi).gunfeel$goals().getAvailableGoals().size();
        check.check("an FBI agent keeps only swimming and the AI's goal (" + names.toString().trim() + ")",
                all == goals(fbi, FloatGoal.class) + goals(fbi, AiGoal.class) && goals(fbi, AiGoal.class) == 1);
    }

    private void walk() {
        fbiGoals();
        if ((int) Math.floor(walker.getZ()) == 24) passedHole |= (int) Math.floor(walker.getX()) == 15;
        if (bot.mover.arrived()) {
            check.out(String.format(Locale.ROOT, "     walked round the wall through the hole in %d ticks; %d searches, %.0f us searching",
                    ticks, bot.mover.searches, bot.mover.searchNanos / 1000.0 / Math.max(1, bot.mover.searches)));
            check.check("walked to the far side of the wall through the hole", passedHole);
            // back north, by a second gap at x = 30; the hole is walled up while it walks
            set(30, y, 24, Blocks.AIR.defaultBlockState());
            set(30, y + 1, 24, Blocks.AIR.defaultBlockState());
            searchesBefore = bot.mover.searches;
            bot.mover.goTo(BlockPos.asLong(14, y, 16), 0.8, 1.0);
            next(Phase.AROUND);
        } else if (ticks > 400 || bot.mover.failed()) {
            check.check("walked to the far side of the wall (stuck at " + walker.blockPosition().toShortString() + ")", false);
            next(Phase.SHOOT);
        }
    }

    private void around() {
        if (!closed && ticks >= 6) {
            closed = true;
            set(15, y, 24, Blocks.STONE.defaultBlockState());
            set(15, y + 1, 24, Blocks.STONE.defaultBlockState());
        }
        if (closed && (int) Math.floor(walker.getZ()) == 24) crossedAt = (int) Math.floor(walker.getX());
        if (bot.mover.arrived()) {
            check.out(String.format(Locale.ROOT, "     hole walled up on the way: back across the wall at x %d in %d ticks, %d searches more",
                    crossedAt, ticks, bot.mover.searches - searchesBefore));
            check.check("found another way when the hole was walled up", crossedAt != 15 && crossedAt != Integer.MIN_VALUE
                    && bot.mover.searches > searchesBefore + 1);
            startShooting();
        } else if (ticks > 600 || bot.mover.failed()) {
            check.check("found another way when the hole was walled up (stuck at " + walker.blockPosition().toShortString() + ")", false);
            startShooting();
        }
    }

    private void startShooting() {
        bot.mover.stop();
        walker.setPos(14.5, y, 16.5);
        target.setPos(14.5, y, 9.5);
        healthBefore = target.getHealth();
        reserveBefore = bot.gunner.ammo.reserve();
        loadedBefore = bot.gunner.loaded();
        bot.gunner.jitter(0);
        next(Phase.SHOOT);
    }

    private Vec3 chest(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.6, 0);
    }

    private void shoot() {
        bot.gunner.aim(chest(target));
        bot.gunner.fire(true);
        // a magazine and a reload: the reload is paid from what it carries
        if (bot.gunner.ammo.reserve() < reserveBefore && bot.gunner.loaded() > 0 && !bot.gunner.reloading() || ticks > 400) {
            int cap = bot.gunner.capacity();
            check.out(String.format(Locale.ROOT, "     shot %d rounds in %d ticks (%d pulls), the target lost %.1f health; reloaded %d from %d carried",
                    loadedBefore, ticks, bot.gunner.pulls, healthBefore - target.getHealth(), reserveBefore - bot.gunner.ammo.reserve(), reserveBefore));
            check.check("shoots the target in front of it", target.getHealth() < healthBefore);
            check.check("an empty gun is reloaded from what it carries, a magazine's worth",
                    reserveBefore - bot.gunner.ammo.reserve() == cap && bot.gunner.loaded() == cap);
            bot.gunner.ammo.set(2);
            loadedTotalBefore = bot.gunner.ammo.loadedTotal;
            next(Phase.SPEND);
        }
    }

    private void spend() {
        bot.gunner.aim(chest(target));
        bot.gunner.fire(true);
        if (ticks == 300) {
            check.out("     with 2 rounds besides: " + bot.gunner.loaded() + " in the gun, " + bot.gunner.ammo.reserve() + " besides, after 300 ticks of fire");
            check.check("runs dry: the last 2 rounds were loaded and shot, no reload from nothing",
                    bot.gunner.dry() && bot.gunner.ammo.loadedTotal - loadedTotalBefore == 2);
            bot.gunner.ammo.setInfinite();
            next(Phase.ENDLESS);
        }
    }

    private void endless() {
        bot.gunner.fire(false);
        if (bot.gunner.loaded() == bot.gunner.capacity() || ticks > 200) {
            check.check("a mob with endless rounds reloads and still has endless", bot.gunner.loaded() == bot.gunner.capacity()
                    && bot.gunner.ammo.infinite());
            bot.gunner.aim(null);
            bot.gunner.release();
            target.discard();
            bot.mover.goTo(BlockPos.asLong(20, y, 12), 0.8, 1.0);
            next(Phase.FREEZE);
        }
    }

    private void freeze() {
        if (ticks == 60) {
            check.check("nobody about: it stands still and thinks nothing", !bot.active() && walker.getNavigation().isDone());
            if (fbi != null) check.check("the FBI agent picked no target of its own", fbi.getTarget() == null);
            check.out(String.format(Locale.ROOT, "     path searches: %d, %d failed; %d server ticks in all", bot.mover.searches,
                    bot.mover.failures, total));
            walker.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
            next(Phase.DONE);
        }
    }
}
