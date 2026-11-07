package acup.gunfeel.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The AI check's player who was in creative: a fake player in the level (as a real one is, in its list of players),
 * in creative, spawns two AI dummies by them (as /armsai spawn does), walks twelve blocks off and shoots, then goes
 * to survival and stands in the open in front of them. Each dummy must pull the trigger soon after. Three rounds on
 * a field of its own east of the crowd's: the open field with the player's sounds heard (as before creative players
 * were unheard: the sounds a little aside of them), the open field with them unheard (the dummies facing the player),
 * and the field with a wall to hide behind (the dummy runs to it with its back to the player, then fights from it). A trace of every dummy (its mode, what it knows of the player, where it looks) goes next to the
 * result file.
 */
final class AiCheckUnseen {
    static final int X0 = 140, Z0 = 4, SIZE = 40;
    /** where the player spawns the dummies and where they walk to */
    static final double PX = 160.5, PZ0 = 30.5, PZ1 = 18.5;
    static final int DUMMIES = 2;
    // the round's ticks: standing, walking off, shooting, survival from SWITCH on, the round over at END at most
    static final int WALK = 20, STAND = 80, SWITCH = 140, END = 440;
    /** the ticks after survival by which each dummy must have pulled the trigger: in the open, with a wall to get behind */
    static final int OPEN_LIMIT = 60, COVER_LIMIT = 200;

    private record Round(String name, boolean heard, boolean wall) {
    }

    private static final List<Round> ROUNDS = List.of(new Round("open field, heard", true, false), new Round("open field, unheard", false, false),
            new Round("a wall to hide behind, heard", true, true));

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final List<String> trace = new ArrayList<>();
    private int round = -1, ticks;
    private FakePlayer player;
    private final List<Mob> dummies = new ArrayList<>();
    // per dummy: the first tick (from the switch) it knew the player was there, pulled the trigger; its head's turn
    // after the switch, degrees; whether it heard anything before
    private int[] seenAt, pulledAt, pullsAt;
    private float[] lastHead;
    private double[] turned;
    private boolean[] heard;
    private int hits;

    AiCheckUnseen(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (round < 0) {
            field();
            round = 0;
            start();
        }
        ticks++;
        step();
        if (ticks >= END || ticks > SWITCH + 40 && allPulled()) {
            finish();
            if (++round >= ROUNDS.size()) {
                writeTrace();
                return true;
            }
            start();
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void field() {
        for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
            if (e.getEntity() == player) hits++;
        });
    }

    private void build(boolean wall) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        // a wall two high off to the side, between the player and the cells behind it
        if (wall) for (int x = 166; x <= 171; x++) {
            set(x, y, 28, stone);
            set(x, y + 1, 28, stone);
        }
        TacticalMap.of(level).want((int) PX, y, (int) PZ0 - 6, 24, 6);
    }

    private void start() {
        Round r = ROUNDS.get(round);
        ticks = 0;
        hits = 0;
        build(r.wall);
        // a fresh player each round: one taken out of the level stays removed
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7c7c" + round), "armsai_unseen" + round));
        player.setGameMode(GameType.CREATIVE);
        player.snapTo(PX, y, PZ0, 0F, 0F);
        level.addNewPlayer(player);
        // one a bullet can hurt (else every hit stops at its invulnerability and is never counted)
        AiCheckReact.hittable(player);
        dummies.clear();
        dummies.addAll(AiSpawn.spawn(level, new Vec3(PX, y, PZ0), AiSpawn.Kind.DUMMY, DUMMIES, null));
        // unheard, they turn nowhere: facing where the player will stand (north), as the player who stood in front
        // of them; one facing away from a player it never heard need not see them
        if (!r.heard) for (Mob m : dummies) {
            m.setYRot(180F);
            m.setYHeadRot(180F);
            m.setYBodyRot(180F);
        }
        int n = dummies.size();
        seenAt = new int[n];
        pulledAt = new int[n];
        pullsAt = new int[n];
        lastHead = new float[n];
        turned = new double[n];
        heard = new boolean[n];
        for (int i = 0; i < n; i++) {
            seenAt[i] = pulledAt[i] = -1;
            lastHead[i] = dummies.get(i).getYHeadRot();
        }
        trace.add("== round " + round + ": " + r.name);
    }

    private void step() {
        Round r = ROUNDS.get(round);
        // the player: stands, walks north, shoots, then stands in survival facing the dummies
        double z = ticks < WALK ? PZ0 : ticks < STAND ? PZ0 + (PZ1 - PZ0) * (ticks - WALK) / (STAND - WALK) : PZ1;
        player.snapTo(PX, y, z, 0F, 0F);
        player.setHealth(player.getMaxHealth());
        if (ticks >= WALK && ticks < STAND && ticks % 6 == 0) sound(r, SoundEvents.STONE_STEP, 0.15F);
        if (ticks >= STAND && ticks < SWITCH && (ticks - STAND) % 20 == 10) sound(r, SoundEvents.ARROW_SHOOT, 4F);
        if (ticks == SWITCH) player.setGameMode(GameType.SURVIVAL);
        for (int i = 0; i < dummies.size(); i++) {
            Mob m = dummies.get(i);
            AiBot bot = AiBots.get(m);
            if (bot == null) continue;
            if (ticks < SWITCH && bot.memory.noise() != null) heard[i] = true;
            if (ticks == SWITCH) pullsAt[i] = bot.gunner.pulls;
            if (ticks > SWITCH) {
                turned[i] += Math.abs(Mth.wrapDegrees(m.getYHeadRot() - lastHead[i]));
                TargetMemory.Contact c = bot.memory.contact(player.getId());
                if (seenAt[i] < 0 && c != null && c.state == TargetMemory.State.VISIBLE) seenAt[i] = ticks - SWITCH;
                if (pulledAt[i] < 0 && bot.gunner.pulls > pullsAt[i]) pulledAt[i] = ticks - SWITCH;
            }
            lastHead[i] = m.getYHeadRot();
            if (ticks > SWITCH && ticks <= SWITCH + 120 ? ticks % 5 == 0 : ticks % 20 == 0) trace.add(line(i, m, bot));
        }
    }

    /** a sound of the player's: at their feet (unheard while in creative), or a little aside, as the dummies heard it before */
    private void sound(Round r, net.minecraft.sounds.SoundEvent s, float volume) {
        double aside = r.heard ? (ticks / 6 % 2 == 0 ? 2.5 : -2.5) : 0;
        level.playSound(null, player.getX() + aside, player.getY() + (volume > 1 ? 1.5 : 0), player.getZ(), s, SoundSource.PLAYERS, volume, 1F);
    }

    private String line(int i, Mob m, AiBot bot) {
        TargetMemory.Contact c = bot.memory.contact(player.getId());
        Vec3 eye = m.getEyePosition(), view = m.getViewVector(1F);
        double dx = player.getX() - eye.x, dy = player.getY() + 1.08 - eye.y, dz = player.getZ() - eye.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double off = Math.toDegrees(Math.acos(Mth.clamp((dx * view.x + dy * view.y + dz * view.z) / d, -1, 1)));
        String mode = bot.brain instanceof AiSoldier s ? s.mode().name() + " (" + s.why.label + ")" : "-";
        TargetMemory.Noise n = bot.memory.noise();
        return String.format(Locale.ROOT, "t%4d #%d %s at %.1f %.1f d %.1f | %s | noise %s | head %.0f body %.0f yrot %.0f pitch %.0f, %.0f deg off the player"
                + " | pulls %d shots %d loaded %d | walk %s | %s", ticks - SWITCH, i, mode, m.getX(), m.getZ(), d,
                c == null ? "no contact" : c.state + " sight " + c.sightTicks, n == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f%s", n.x, n.z, n.loud ? " loud" : ""),
                Mth.wrapDegrees(m.getYHeadRot()), Mth.wrapDegrees(m.yBodyRot), Mth.wrapDegrees(m.getYRot()), m.getXRot(), off, bot.gunner.pulls, bot.gunner.shots,
                bot.gunner.loaded(), bot.mover.state(), player.isCreative() ? "creative" : "survival");
    }

    private boolean allPulled() {
        for (int p : pulledAt) if (p < 0) return false;
        return true;
    }

    private void finish() {
        Round r = ROUNDS.get(round);
        int limit = r.wall ? COVER_LIMIT : OPEN_LIMIT;
        StringBuilder each = new StringBuilder();
        boolean ok = !dummies.isEmpty(), anyHeard = false;
        double after = Math.max(1, ticks - SWITCH) / 20.0;
        for (int i = 0; i < dummies.size(); i++) {
            each.append(String.format(Locale.ROOT, "%s #%d knew after %s, pulled after %s, head turned %.0f deg/s", i == 0 ? "" : ";", i, ticks(seenAt[i]),
                    ticks(pulledAt[i]), turned[i] / after));
            ok &= pulledAt[i] >= 0 && pulledAt[i] <= limit;
            anyHeard |= heard[i];
        }
        check.out(String.format(Locale.ROOT, "     a player out of creative, %s: %d dummies, %s; hit %d times", r.name, dummies.size(), each, hits));
        check.check("AI dummies that " + (r.heard ? "heard" : "did not hear") + " a player in creative shoot at them in survival within "
                + limit / 20 + " s (" + r.name + ")", ok);
        if (!r.heard) check.check("the sounds about a player in creative go unheard", !anyHeard);
        for (Mob m : dummies) m.discard();
        dummies.clear();
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        AiBots.refreshTargets(level);
    }

    private static String ticks(int t) {
        return t < 0 ? "never" : t + " ticks";
    }

    private void writeTrace() {
        String file = System.getProperty(AiCheck.PROPERTY);
        if (file == null) return;
        try {
            Files.write(Path.of(file).resolveSibling("unseen-trace.txt"), trace, StandardCharsets.UTF_8);
        } catch (IOException e) {
            check.out("     (the unseen trace was not written: " + e + ")");
        }
    }
}
