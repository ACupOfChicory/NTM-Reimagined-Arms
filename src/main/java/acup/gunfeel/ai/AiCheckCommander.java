package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The AI check's commander (AiCommander, stage 5 R5). Rounds by turns, aiCommander off (before) and on: three
 * squads of three of a side in a row, SQUAD_GAP apart; out of their sight (farther than the soldier's eyes reach),
 * two groups of their enemies: three mobs of another team with no guns (they neither fight nor are made squads) and
 * a player (a fake one, in survival, hard to kill). RUN ticks. Counted: whether each group was seen by one of the
 * side, the tick it first was, the most squads sent to one group at once (as the commander gives its orders), the
 * side's losses. A round after, on: the player alone, shut in a stone box (no one sees it): two squads are sent, the
 * third is held in reserve; the two look through the area, find no one and give the player up (HIDDEN ticks). Last,
 * where the side loses soldiers (R5b), aiCommanderHelp off (before), then on twice: the mobs alone, not to be hurt, the
 * player in creative (no group); two squads are sent, the third held in reserve; once the mobs are seen, one of each
 * squad sent is downed by them. Counted: whether the reserve then goes and sees them, how long after (AFTER ticks at
 * most).
 */
final class AiCheckCommander {
    static final int X0 = 1300, Z0 = 200, W = 180, L = 200, RUN = 1600, HIDDEN = 3000, MAIN = 5, SQUADS = 3, EACH = 3;
    /**
     * the losses' rounds with aiCommanderHelp off, then on: in one round of twenty or so every squad has an order when
     * ours are downed and there is no reserve to watch (not counted); with help on, the reserve was sent in 7 rounds of
     * 10 (with none sent in three, 2 runs of 20 failed), and in one of 40 sent it did not see them before the round
     * was over
     */
    static final int HELPS_OFF = 2, HELPS_ON = 4, ROUNDS = MAIN + HELPS_OFF + HELPS_ON;
    /** the box's round; after the losses, the round goes on this long, ticks */
    static final int HIDDEN_ROUND = MAIN - 1, AFTER = 1200;
    static final double SQUAD_Z = 15.5, FAR_Z = 150.5, SQUAD_GAP = 40;
    static final double[] FIRST_X = {1320.5, 1360.5, 1400.5};
    /** the foes: the mobs' group, the player */
    static final double MOBS_X = 1320.5, PLAYER_X = 1420.5;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final String OURS = "cmd_a", THEIRS = "cmd_b";

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> ours = new ArrayList<>(), theirs = new ArrayList<>();
    private FakePlayer player;
    private Item rifle;
    /** the tick the side first saw each group (the mobs, the player), -1: not yet; the most squads sent to each at once */
    private final int[] seenAt = new int[2], most = new int[2];
    private int ordersBefore, reserveBefore, gaveUpBefore, helpedBefore;
    /** per round: {on, the mobs seen at, the player seen at, most at the mobs, most at the player, ours down, orders, held in reserve, given up} */
    private final List<int[]> results = new ArrayList<>();
    /** the losses' rounds: the tick ours were downed (-1: not yet), the tick the reserve first saw the mobs after */
    private int lossAt, reserveSeen;
    /** those held in reserve when ours were downed */
    private final List<Mob> reserveMobs = new ArrayList<>();
    /** per losses' round: {help on, the mobs seen at, ours downed at, the reserve saw them at, most at the mobs, ours down, held in reserve, sent where the side loses soldiers, the reserve's size} */
    private final List<int[]> helps = new ArrayList<>();

    AiCheckCommander(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private boolean on() {
        return round % 2 == 1 || round >= HIDDEN_ROUND;
    }

    /** the box's round: the player alone, in a box */
    private boolean hidden() {
        return round == HIDDEN_ROUND;
    }

    /** the losses' rounds; the first HELPS_OFF of them with aiCommanderHelp off */
    private boolean losses() {
        return round > HIDDEN_ROUND;
    }

    private boolean help() {
        return round < MAIN || round >= MAIN + HELPS_OFF;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + W) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + L) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            rifle = BuiltInRegistries.ITEM.getValue(RIFLE);
            build();
            start(0);
            return false;
        }
        ticks++;
        AiCommander.on = on();
        AiCommander.help = help();
        AiCommander.tasks = false;
        long now = level.getGameTime();
        if (losses()) return lossesTick(now);
        for (int g = 0; g < 2; g++) {
            if (seenAt[g] >= 0) continue;
            List<LivingEntity> group = g == 0 ? new ArrayList<>(theirs) : List.of(player);
            if (group.isEmpty()) continue;
            for (Mob m : ours) {
                AiBot b = AiBots.get(m);
                if (b == null || !m.isAlive()) continue;
                for (LivingEntity e : group) {
                    TargetMemory.Contact c = b.memory.contact(e.getId());
                    if (c != null && c.state == TargetMemory.State.VISIBLE) seenAt[g] = ticks;
                }
            }
        }
        // squads sent to each group, as the commander had it when it gave its orders this tick: by the order half of
        // each squad or more carries (between its orders squads are made again as they move, a few with an order and
        // others in one, till it looks again)
        Mob rep = null;
        for (Mob m : ours) if (m.isAlive()) rep = m;
        if (AiCommander.commandedAt.getOrDefault(OURS, Long.MIN_VALUE) == now && rep != null) {
            List<AiIntel.Group> groups = AiCommander.groups(level, OURS);
            int[] sent = sent(groups, now);
            for (int g = 0; g < 2; g++) most[g] = Math.max(most[g], sent[g]);
        }
        if (hidden() ? ticks < HIDDEN : ticks < RUN && !(seenAt[0] >= 0 && seenAt[1] >= 0 && ticks > Math.max(seenAt[0], seenAt[1]) + 100)) return false;
        results.add(new int[] {on() ? 1 : 0, seenAt[0], seenAt[1], most[0], most[1], down(), AiCommander.orders - ordersBefore, AiCommander.reserve - reserveBefore,
                AiCommander.gaveUp - gaveUpBefore});
        return next();
    }

    private int down() {
        int down = 0;
        for (Mob m : ours) if (!m.isAlive()) down++;
        return down;
    }

    private boolean next() {
        clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /** squads of ours counted to each group (the mobs, the player), as the commander counts them */
    private int[] sent(List<AiIntel.Group> groups, long now) {
        int[] sent = new int[2];
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            if (!ours.contains(s.bots.get(0).mob)) continue;
            AiIntel.Group g = AiCommander.counted(s, groups, now);
            if (g == null) continue;
            if (g.has(player.getId())) sent[1]++;
            else for (Mob m : theirs) if (g.has(m.getId())) {
                sent[0]++;
                break;
            }
        }
        return sent;
    }

    private static boolean sees(Mob m, List<Mob> them) {
        AiBot b = AiBots.get(m);
        if (b == null || !m.isAlive()) return false;
        for (Mob e : them) {
            TargetMemory.Contact c = b.memory.contact(e.getId());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return true;
        }
        return false;
    }

    /**
     * A losses' round: once one of ours sees the mobs, one of each squad sent to them is downed by them; then whether
     * the reserve (those with no order at that moment) goes and sees them
     */
    private boolean lossesTick(long now) {
        Mob rep = null;
        for (Mob m : ours) if (m.isAlive()) rep = m;
        if (rep == null) return lossesDone();
        List<AiIntel.Group> groups = AiCommander.groups(level, OURS);
        if (AiCommander.commandedAt.getOrDefault(OURS, Long.MIN_VALUE) == now) most[0] = Math.max(most[0], sent(groups, now)[0]);
        if (seenAt[0] < 0) for (Mob m : ours) if (sees(m, theirs)) seenAt[0] = ticks;
        if (seenAt[0] >= 0 && lossAt < 0) {
            lossAt = ticks;
            List<Mob> sentMobs = new ArrayList<>();
            for (AiSquads.Squad s : AiSquads.squads(level)) {
                if (!ours.contains(s.bots.get(0).mob)) continue;
                AiIntel.Group g = AiCommander.counted(s, groups, now);
                if (g == null || g.has(player.getId())) continue;
                for (AiBot b : s.bots) sentMobs.add(b.mob);
                // one of the squad downed by one of them
                AiBot hit = s.bots.get(s.bots.size() - 1);
                hit.mob.hurtServer(level, level.damageSources().mobAttack(theirs.get(0)), 1000F);
            }
            for (Mob m : ours) if (m.isAlive() && !sentMobs.contains(m)) {
                AiBot b = AiBots.get(m);
                if (b != null && (b.order == null || now > b.order.until)) reserveMobs.add(m);
            }
        }
        if (lossAt >= 0 && reserveSeen < 0) for (Mob m : reserveMobs) if (sees(m, theirs)) reserveSeen = ticks;
        if (lossAt >= 0 ? ticks < lossAt + AFTER : ticks < RUN) return false;
        return lossesDone();
    }

    private boolean lossesDone() {
        helps.add(new int[] {help() ? 1 : 0, seenAt[0], lossAt, reserveSeen, most[0], down(), AiCommander.reserve - reserveBefore, AiCommander.helped - helpedBefore,
                reserveMobs.size()});
        return next();
    }

    private void clear() {
        for (Mob m : ours) m.discard();
        for (Mob m : theirs) m.discard();
        ours.clear();
        theirs.clear();
        if (player != null) {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            player = null;
        }
        box(false);
    }

    /** a stone box round the player's place, or none */
    private void box(boolean on) {
        BlockState st = on ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        int px = (int) Math.floor(PLAYER_X), pz = (int) Math.floor(Z0 + FAR_Z);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int h = 0; h < 4; h++)
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3) set(px + dx, y + h, pz + dz, st);
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    /** a flat field, nothing on it */
    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + W; x++)
            for (int z = Z0; z < Z0 + L; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        TacticalMap.of(level).want(X0 + W / 2, y, Z0 + L / 2, Math.max(W, L) / 2 + 4, 8);
    }

    private Mob spawn(double x, double z, String team, float yaw, boolean armed) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, rifle).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (armed) {
            if (b != null) b.gunner.ammo.set(b.gunner.capacity() * 4);
        } else m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        java.util.Arrays.fill(seenAt, -1);
        java.util.Arrays.fill(most, 0);
        lossAt = reserveSeen = -1;
        reserveMobs.clear();
        AiCommander.on = on();
        AiCommander.help = help();
        // R5's orders, as with aiCommanderTasks off (the staff's tasks: the war's stand)
        AiCommander.tasks = false;
        AiCommander.forget(level);
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        // the foes stand where they are: no squads of theirs, so no orders
        AiSquads.offTeams.add(THEIRS);
        AiSquads.forget(level);
        for (int s = 0; s < SQUADS; s++)
            for (int i = 0; i < EACH; i++) ours.add(spawn(FIRST_X[s] + (i - 1) * 2, Z0 + SQUAD_Z, OURS, 0F, true));
        if (!hidden()) for (int i = 0; i < 3; i++) theirs.add(spawn(MOBS_X + (i - 1) * 3, Z0 + FAR_Z, THEIRS, 180F, false));
        else box(true);
        // the losses' rounds: the mobs not to be hurt (the fight goes on till the reserve comes)
        if (losses()) for (Mob m : theirs) m.setInvulnerable(true);
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7d0" + String.format(Locale.ROOT, "%02d", r)), "armsai_cmd" + r));
        // in creative in the losses' rounds: no group, the mobs alone
        player.setGameMode(losses() ? GameType.CREATIVE : GameType.SURVIVAL);
        player.snapTo(PLAYER_X, y, Z0 + FAR_Z, 180F, 0F);
        level.addNewPlayer(player);
        AiCheckReact.hittable(player);
        ordersBefore = AiCommander.orders;
        reserveBefore = AiCommander.reserve;
        gaveUpBefore = AiCommander.gaveUp;
        helpedBefore = AiCommander.helped;
        AiBots.refreshTargets(level);
    }

    private boolean finish() {
        AiCommander.on = true;
        AiCommander.help = true;
        AiCommander.tasks = true;
        AiSquads.offTeams.clear();
        String[] names = {"aiCommander off (before)", "aiCommander on"};
        boolean onBoth = true, onFew = true, offNone = true;
        int[] hid = results.size() == MAIN ? results.get(HIDDEN_ROUND) : null;
        int onRounds = 0;
        double onFirst = 0;
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            check.out(String.format(Locale.ROOT, "     commander, round %d, %s%s: the mobs seen at tick %d, the player at %d; the most squads at once at the mobs %d, at the player %d;"
                    + " ours down %d; orders %d, held in reserve %d, given up %d", i + 1, names[r[0]], i == HIDDEN_ROUND ? ", the player alone in a box" : "", r[1], r[2], r[3], r[4],
                    r[5], r[6], r[7], r[8]));
            if (i == HIDDEN_ROUND) continue;
            if (r[0] == 1) {
                onRounds++;
                if (r[1] < 0 || r[2] < 0) onBoth = false;
                else onFirst += Math.min(r[1], r[2]);
                if (r[3] > AiCommander.PER || r[4] > AiCommander.PER) onFew = false;
            } else if (r[1] >= 0 || r[2] >= 0) offNone = false;
        }
        // where the side loses soldiers: off, the reserve stays (it may still come by a squad made again with others);
        // on, it goes, one squad more than PER at most
        String[] helpNames = {"aiCommanderHelp off (before)", "aiCommanderHelp on"};
        // a round with no reserve when ours were downed tells nothing either way
        boolean helpOff = true, helpOn = true;
        double after = 0;
        int offHelps = 0, onHelps = 0, onHelped = 0, onUnseen = 0, noReserve = 0;
        for (int i = 0; i < helps.size(); i++) {
            int[] h = helps.get(i);
            check.out(String.format(Locale.ROOT, "     commander, losses' round %d, %s: the mobs seen at tick %d, ours downed at %d, the reserve (%d) saw them at %d; the most squads at once"
                    + " at the mobs %d; ours down %d; held in reserve %d, sent where the side loses soldiers %d", i + 1, helpNames[h[0]], h[1], h[2], h[8], h[3], h[4], h[5], h[6], h[7]));
            if (h[2] >= 0 && h[8] == 0) noReserve++;
            else if (h[0] == 0) {
                offHelps++;
                if (h[2] < 0 || h[7] > 0 || h[4] > AiCommander.PER) helpOff = false;
            } else {
                onHelps++;
                // the reserve there in each round but one at most; sent by the help in one at least (a squad there may leave before the
                // next orders, and the reserve go as any squad would)
                onHelped += h[7];
                if (h[2] < 0) helpOn = false;
                else if (h[3] < 0) onUnseen++;
                else after += h[3] - h[2];
            }
        }
        check.out("     " + AiCommander.info());
        check.check("commander: all rounds ran", results.size() == MAIN && helps.size() == ROUNDS - MAIN);
        check.check(String.format(Locale.ROOT, "commander: aiCommanderHelp off, the reserve is not sent where the side loses soldiers, no more than %d squads at the group"
                + " (%d rounds; %d of all with no reserve, not counted)", AiCommander.PER, offHelps, noReserve), offHelps > 0 && helpOff);
        check.check(String.format(Locale.ROOT, "commander: aiCommanderHelp on, the reserve goes where the side loses soldiers and sees them, %.0f ticks after the losses on average"
                + " (sent %d times in %d rounds, not seen in %d: one at most)", onHelps - onUnseen == 0 ? 0 : after / (onHelps - onUnseen), onHelped, onHelps, onUnseen),
                onHelps > 1 && helpOn && onHelped > 0 && onUnseen <= 1);
        check.check(String.format(Locale.ROOT, "commander: on, its squads find both groups out of their sight, the first in %.0f ticks on average", onRounds == 0 ? 0 : onFirst / onRounds),
                onRounds > 0 && onBoth);
        check.check("commander: on, no more than " + AiCommander.PER + " squads sent to a group at once", onFew);
        check.check("commander: off, they stay and see neither group", offNone);
        check.check(String.format(Locale.ROOT, "commander: the player alone, out of sight: no more than %d squads sent (%d), the rest held in reserve (%d times), the area given up (%d)",
                AiCommander.PER, hid == null ? -1 : hid[4], hid == null ? -1 : hid[7], hid == null ? -1 : hid[8]), hid != null && hid[4] <= AiCommander.PER && hid[7] > 0 && hid[8] > 0);
        return true;
    }
}
