package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One AI mob's mind: its profile, what it knows ({@link TargetMemory}) and the search cells it has looked into. Its
 * senses look around every profile.period ticks, spread over the ticks by the mob's id. Driven by the mob's own tick
 * (the Goal), so a mob in a chunk that does not tick thinks nothing.
 */
public final class AiBot {
    public final Mob mob;
    public final AiProfile profile;
    public final TargetMemory memory;
    /** search cells looked into, of the target lost last */
    public final LongOpenHashSet cleared = new LongOpenHashSet();
    private int clearedFor = Integer.MIN_VALUE;
    private long clearedLost = Long.MIN_VALUE;
    public final AiMover mover;
    public final AiGunner gunner;
    public final AiProne prone;
    /** what it does with what it knows; null: it only senses (a stand drives it) */
    public @Nullable AiBrain brain;
    /** a player within aiRadius: it thinks; else it stands still */
    boolean active = true;

    AiBot(Mob mob, AiProfile profile) {
        this.mob = mob;
        this.profile = profile;
        this.memory = new TargetMemory(profile);
        this.eyes = profile;
        this.mover = new AiMover(mob);
        this.gunner = new AiGunner(mob, new AiAmmo(0));
        this.prone = new AiProne(mob);
    }

    public boolean active() {
        return active;
    }

    /** a look around now and then, the memory's clock always */
    public void tick(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, List<LivingEntity> targets, long tick) {
        if ((tick + mob.getId()) % profile.period() == 0) perceive(voxels, smoke, targets, tick);
        memory.update(tick);
    }

    private @Nullable AiSkill eyesFor;
    private AiProfile eyes;

    /** its profile as its eyes notice with it: the skill (aiSkill) moves the time to notice from a regular's */
    private AiProfile eyes() {
        AiSkill skill = AiSkill.current();
        if (skill != eyesFor) {
            eyesFor = skill;
            eyes = profile.withReaction(Math.max(1, profile.reaction() + skill.notice() - AiSkill.REGULAR.notice()));
        }
        return eyes;
    }

    public void perceive(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, List<LivingEntity> targets, long tick) {
        memory.look();
        for (LivingEntity t : targets) {
            if (t == mob || !t.isAlive() || !AiTeams.enemy(mob, t)) continue;
            int notice = Senses.look(voxels, smoke, mob, t, eyes(), t.getId() == memory.focusId);
            if (notice >= 0) memory.seen(t.getId(), t.getX(), t.getY(), t.getZ(), tick, notice).parts = Senses.parts;
        }
        memory.endLook(tick);
    }

    /** the cleared set belongs to one loss of one target: a new loss starts it over */
    public LongOpenHashSet clearedFor(TargetMemory.Contact c) {
        if (c.id != clearedFor || c.lostTick != clearedLost) {
            cleared.clear();
            clearedFor = c.id;
            clearedLost = c.lostTick;
        }
        return cleared;
    }
}
