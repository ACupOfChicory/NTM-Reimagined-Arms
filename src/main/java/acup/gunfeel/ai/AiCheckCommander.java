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
 * side's losses. A last round, on: the
 * player alone, shut in a stone box (no one sees it): two squads are sent, the third is held in reserve; the two look
 * through the area, find no one and give the player up (HIDDEN ticks).
 */
final class AiCheckCommander {
    static final int X0 = 1300, Z0 = 200, W = 180, L = 200, RUN = 1600, HIDDEN = 3000, ROUNDS = 5, SQUADS = 3, EACH = 3;
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
    private int ordersBefore, reserveBefore, gaveUpBefore;
    /** per round: {on, the mobs seen at, the player seen at, most at the mobs, most at the player, ours down, orders, held in reserve, given up} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckCommander(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private boolean on() {
        return round % 2 == 1 || hidden();
    }

    /** the last round: the player alone, in a box */
    private boolean hidden() {
        return round == ROUNDS - 1;
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
        long now = level.getGameTime();
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
            List<AiCommander.Group> groups = AiCommander.groups(level, rep);
            int[] sent = new int[2];
            for (AiSquads.Squad s : AiSquads.squads(level)) {
                if (!ours.contains(s.bots.get(0).mob)) continue;
                AiCommander.Group g = AiCommander.counted(s, groups, now);
                if (g == null) continue;
                if (g.has(player.getId())) sent[1]++;
                else for (Mob m : theirs) if (g.has(m.getId())) {
                    sent[0]++;
                    break;
                }
            }
            for (int g = 0; g < 2; g++) most[g] = Math.max(most[g], sent[g]);
        }
        if (hidden() ? ticks < HIDDEN : ticks < RUN && !(seenAt[0] >= 0 && seenAt[1] >= 0 && ticks > Math.max(seenAt[0], seenAt[1]) + 100)) return false;
        int down = 0;
        for (Mob m : ours) if (!m.isAlive()) down++;
        results.add(new int[] {on() ? 1 : 0, seenAt[0], seenAt[1], most[0], most[1], down, AiCommander.orders - ordersBefore, AiCommander.reserve - reserveBefore,
                AiCommander.gaveUp - gaveUpBefore});
        clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
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
        AiCommander.on = on();
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
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7d0" + String.format(Locale.ROOT, "%02d", r)), "armsai_cmd" + r));
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(PLAYER_X, y, Z0 + FAR_Z, 180F, 0F);
        level.addNewPlayer(player);
        AiCheckReact.hittable(player);
        ordersBefore = AiCommander.orders;
        reserveBefore = AiCommander.reserve;
        gaveUpBefore = AiCommander.gaveUp;
        AiBots.refreshTargets(level);
    }

    private boolean finish() {
        AiCommander.on = true;
        AiSquads.offTeams.clear();
        String[] names = {"aiCommander off (before)", "aiCommander on"};
        boolean onBoth = true, onFew = true, offNone = true;
        int[] hid = results.size() == ROUNDS ? results.get(ROUNDS - 1) : null;
        int onRounds = 0;
        double onFirst = 0;
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            check.out(String.format(Locale.ROOT, "     commander, round %d, %s%s: the mobs seen at tick %d, the player at %d; the most squads at once at the mobs %d, at the player %d;"
                    + " ours down %d; orders %d, held in reserve %d, given up %d", i + 1, names[r[0]], i == ROUNDS - 1 ? ", the player alone in a box" : "", r[1], r[2], r[3], r[4],
                    r[5], r[6], r[7], r[8]));
            if (i == ROUNDS - 1) continue;
            if (r[0] == 1) {
                onRounds++;
                if (r[1] < 0 || r[2] < 0) onBoth = false;
                else onFirst += Math.min(r[1], r[2]);
                if (r[3] > AiCommander.PER || r[4] > AiCommander.PER) onFew = false;
            } else if (r[1] >= 0 || r[2] >= 0) offNone = false;
        }
        check.out("     " + AiCommander.info());
        check.check("commander: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "commander: on, its squads find both groups out of their sight, the first in %.0f ticks on average", onRounds == 0 ? 0 : onFirst / onRounds),
                onRounds > 0 && onBoth);
        check.check("commander: on, no more than " + AiCommander.PER + " squads sent to a group at once", onFew);
        check.check("commander: off, they stay and see neither group", offNone);
        check.check(String.format(Locale.ROOT, "commander: the player alone, out of sight: no more than %d squads sent (%d), the rest held in reserve (%d times), the area given up (%d)",
                AiCommander.PER, hid == null ? -1 : hid[4], hid == null ? -1 : hid[7], hid == null ? -1 : hid[8]), hid != null && hid[4] <= AiCommander.PER && hid[7] > 0 && hid[8] > 0);
        return true;
    }
}
