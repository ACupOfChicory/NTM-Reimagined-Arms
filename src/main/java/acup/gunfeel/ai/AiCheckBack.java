package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The crowd's last stand, on its field: a foe close at one's back. First two side by side who cannot move (as the hub's
 * two in bunkerntm), facing a foe a block off whose back is to them: each holds fire for the other (one of its own close
 * beside), and the fight must still be fought (the foe notices them at its back, or as it looks about); once such a
 * fight was 1600 ticks of nobody firing. The bots kept awake: with the crowd's target gone they would sleep. Then a
 * soldier's eyes (Senses.look, its back to the player, no ticks: a soldier left alone looks about) on a player a block
 * behind it, standing and crouching still, and three blocks behind: the near ones are seen, the crouching one much
 * later (one may still lie low behind), the far one not.
 */
final class AiCheckBack {
    static final double X = 100.5, Z = 12.5, PZ = 22.5;
    static final int RUN = 300;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int ticks = -1;
    private final List<Mob> mobs = new ArrayList<>();
    private @org.jspecify.annotations.Nullable Mob foe;
    /** the tick the foe noticed the two (-1 never), health lost by it and by the two */
    private int noticed = -1;
    /** whether every AI mob thought, players near or not, before this stand (with the crowd's target gone they would sleep) */
    private boolean awakeBefore;
    private double lostFoe, lostPair;

    AiCheckBack(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    /** true when done */
    boolean tick() {
        if (ticks < 0) {
            start();
            ticks = 0;
            return false;
        }
        if (++ticks > RUN) {
            finish();
            looks();
            return true;
        }
        if (foe == null) return false;
        AiBot b = AiBots.get(foe);
        if (noticed < 0 && b != null && mobs.stream().anyMatch(m -> m != foe && visible(b, m))) noticed = ticks;
        lostFoe += heal(foe);
        for (Mob m : mobs) if (m != foe) lostPair += heal(m);
        return false;
    }

    /** noticed (in sight, past the time to notice), not a glimpse */
    private static boolean visible(AiBot b, LivingEntity e) {
        TargetMemory.Contact c = b.memory.contact(e.getId());
        return c != null && c.state == TargetMemory.State.VISIBLE;
    }

    private static double heal(LivingEntity e) {
        double lost = e.getMaxHealth() - e.getHealth();
        e.setHealth(e.getMaxHealth());
        return lost;
    }

    private Mob soldier(double x, double z, String team) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, null).get(0);
        m.setPos(x, y, z);
        face(m);
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
        AiBot b = AiBots.get(m);
        if (b != null) b.gunner.ammo.setInfinite();
        AiTeams.join(level, m, team);
        mobs.add(m);
        return m;
    }

    /** facing south */
    private static void face(Mob m) {
        m.setYRot(0F);
        m.setYHeadRot(0F);
        m.setYBodyRot(0F);
        m.setXRot(0F);
    }

    private void start() {
        awakeBefore = AiBots.awake;
        AiBots.awake = true;
        // the two side by side facing south, kept where they stand; the foe a block south of the east one, facing south too
        for (double dx : new double[] {0, 1}) {
            Mob m = soldier(X + dx, Z, "armsai_back_pair");
            var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        foe = soldier(X + 1, Z + 1.1, "armsai_back_foe");
        AiBots.refreshTargets(level);
    }

    private void finish() {
        AiBot b = foe == null ? null : AiBots.get(foe);
        String did = b == null ? "" : String.format(Locale.ROOT, "; the foe %s, pulls %d, held for its own %d", b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-",
                b.gunner.pulls, b.gunner.holds);
        int pairHolds = 0;
        for (Mob m : mobs) if (m != foe && AiBots.get(m) instanceof AiBot p) pairHolds += p.gunner.holds;
        check.out(String.format(Locale.ROOT, "     at its back, a foe a block off, its back to two side by side: noticed %s; health lost by it %.0f, by the two %.0f%s; the two held for each other %d ticks",
                noticed < 0 ? "never in " + RUN + " ticks" : "in " + noticed + " ticks", lostFoe, lostPair, did, pairHolds));
        check.check("a foe a block off with its back to two side by side notices them and the fight is fought (hits taken) in " + RUN + " ticks",
                noticed >= 0 && lostFoe + lostPair > 0);
        for (Mob m : mobs) m.discard();
        mobs.clear();
        foe = null;
        AiBots.awake = awakeBefore;
        AiBots.refreshTargets(level);
    }

    /** a soldier's eyes on a player behind it: the ticks it takes to notice (-1 not seen) */
    private void looks() {
        Mob one = soldier(X, PZ, "armsai_back_one");
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7ba01"), "armsai_back"));
        player.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(player);
        AiBot b = AiBots.get(one);
        VoxelRay.Source voxels = AiBots.voxels(level);
        int[] t = new int[3];
        for (int i = 0; i < 3; i++) {
            player.snapTo(X, y, PZ - (i == 2 ? 3 : 1.1), 180F, 0F);
            boolean crouch = i == 1;
            player.setShiftKeyDown(crouch);
            player.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
            face(one);
            t[i] = b == null ? -2 : Senses.look(voxels, null, one, player, b.profile);
        }
        // and in front, standing a block off: the time behind its back is longer
        player.snapTo(X, y, PZ + 1.1, 0F, 0F);
        player.setShiftKeyDown(false);
        player.setPose(Pose.STANDING);
        face(one);
        int front = b == null ? -2 : Senses.look(voxels, null, one, player, b.profile);
        check.out(String.format(Locale.ROOT, "     at its back, a player a block behind: noticed in %d ticks standing, %d crouching still; three blocks behind %d; a block in front %d (-1: not seen)",
                t[0], t[1], t[2], front));
        check.check("a player standing a block behind a soldier is noticed, later than in front", t[0] >= 0 && front >= 0 && t[0] > front);
        check.check(String.format(Locale.ROOT, "a player crouching still a block behind is noticed much later than one standing there (%d ticks, %d)", t[1], t[0]),
                t[0] >= 0 && t[1] >= 2 * t[0]);
        check.check("a player standing three blocks behind is not noticed", t[2] < 0);
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        one.discard();
        mobs.clear();
    }
}
