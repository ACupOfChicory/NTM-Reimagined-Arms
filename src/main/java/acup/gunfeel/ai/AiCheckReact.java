package acup.gunfeel.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * How fast an AI mob takes a player it did not expect, and how well it follows one running: measures, no limits. One
 * AI dummy at a time, idle, facing south; a fake player in survival (one a bullet can hurt: {@link #hittable}).
 * <ul>
 * <li>Reaction: the player shows up at 5, 10, 15 blocks straight ahead, 60 degrees aside (the field of view is 140
 * across), or walks out from behind a wall's end. Ticks from when it is first in plain sight to the dummy knowing of it
 * (VISIBLE), to its first trigger pull, to its first hit; three times each, for recruit, regular and veteran.</li>
 * <li>Running: the player sprints across the dummy's look (0.28 blocks a tick, 4 blocks either way and back) at 5, 10,
 * 15 blocks, or stands there; shots and hits for a heavy revolver, a carbine and a grease gun (regular), the revolver
 * for recruit and veteran.</li>
 * </ul>
 * Hits are the player's incoming damage from the dummy's bullets (each bullet once; a bullet is fast enough to hit in
 * the tick it is fired, so its flight cannot be followed between ticks). The table goes to the result; every case to
 * react-trace.txt next to it.
 */
final class AiCheckReact {
    static final int X0 = 190, Z0 = 4, SIZE = 40;
    /** the dummy's place (feet), facing +z */
    static final double BX = 210.5, BZ = 8.5;
    static final int[] DIST = {5, 10, 15};
    static final int REPS = 3;
    /** ticks idle before the player shows up; the most a reaction case runs after; a running case's ticks */
    static final int SETTLE = 20, LIMIT = 160, RUN_TICKS = 400;
    static final double SPRINT = 0.28, WALK = 0.2158, RUN_HALF = 4;
    /** where the player waits between cases: straight up, out of any dummy's sight */
    static final double PARK_Y = 150;
    static final Identifier REVOLVER = Identifier.fromNamespaceAndPath("hbm", "gun_heavy_revolver"),
            CARBINE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine"), GREASE = Identifier.fromNamespaceAndPath("hbm", "gun_greasegun");

    enum Kind {
        FRONT("ahead"), SIDE("60 deg aside"), CORNER("round a wall"), RUN("running across"), STAND("standing");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        boolean reaction() {
            return this == FRONT || this == SIDE || this == CORNER;
        }
    }

    private record Case(AiSkill skill, Kind kind, int dist, Identifier gun, int rep) {
    }

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final List<Case> cases = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();
    private int at = -1, ticks;
    private FakePlayer player;
    private Mob dummy;
    private AiBot bot;
    private boolean wallUp;
    private int wallDist;
    // the case: tick in plain sight, knew, pulled, hit (from the start; -1 not yet); shots and hits
    private int exposed, knew, pulled, hit, shotsAt;
    private final IntOpenHashSet hitBullets = new IntOpenHashSet();
    private String exposedOff = "";
    private String pullWhy = "";
    private boolean awakeBefore;
    // results: per skill, kind, dist (reaction: rows of reps; running: summed)
    private final Map<String, List<int[]>> reactions = new LinkedHashMap<>();
    private final Map<String, int[]> runs = new LinkedHashMap<>();
    private static AiCheckReact counting;

    AiCheckReact(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
        for (AiSkill s : List.of(AiSkill.REGULAR, AiSkill.RECRUIT, AiSkill.VETERAN)) {
            for (Kind k : List.of(Kind.FRONT, Kind.SIDE, Kind.CORNER))
                for (int d : DIST)
                    for (int r = 0; r < REPS; r++) cases.add(new Case(s, k, d, REVOLVER, r));
            for (Identifier g : s == AiSkill.REGULAR ? List.of(REVOLVER, CARBINE, GREASE) : List.of(REVOLVER))
                for (int d : DIST)
                    for (Kind k : List.of(Kind.RUN, Kind.STAND)) cases.add(new Case(s, k, d, g, 0));
        }
    }

    /**
     * A fake player a bullet can hurt as a real one: NeoForge makes it invulnerable, and as it never ticks, the server
     * never stops waiting for its client to load (a player is unhurt until then). Plenty of health: a stand keeps it
     * alive. Stands only: a real player has neither.
     */
    static void hittable(FakePlayer p) {
        p.setInvulnerable(false);
        for (int i = 0; i < 100 && !p.connection.hasClientLoaded(); i++) p.connection.tickClientLoadTimeout();
        var hp = p.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        p.setHealth(p.getMaxHealth());
    }

    boolean tick() {
        if (at < 0) {
            setup();
            at = 0;
            start();
            return false;
        }
        ticks++;
        step();
        Case c = cases.get(at);
        boolean over = c.kind.reaction() ? hit >= 0 || exposed >= 0 && ticks - exposed > LIMIT || ticks > SETTLE + LIMIT + 60
                : ticks >= SETTLE + RUN_TICKS;
        if (over) {
            finish();
            if (++at >= cases.size()) {
                report();
                return true;
            }
            start();
        }
        return false;
    }

    private void setup() {
        awakeBefore = AiBots.awake;
        AiBots.awake = true;
        for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
        build(-1);
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7d001"), "armsai_react"));
        player.setGameMode(GameType.SURVIVAL);
        park();
        level.addNewPlayer(player);
        hittable(player);
        counting = this;
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
            AiCheckReact r = counting;
            if (r != null) r.hurt(e);
        });
    }

    private void hurt(LivingIncomingDamageEvent e) {
        if (e.getEntity() != player || dummy == null || e.getSource().getEntity() != dummy) return;
        Entity bullet = e.getSource().getDirectEntity();
        if (!hitBullets.add(bullet == null ? -ticks : bullet.getId())) return;
        if (hit < 0) hit = ticks;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    /** the floor; a wall two high from x 212 to 217 one block nearer than the player at dist (dist -1: none) */
    private void build(int dist) {
        if (dist < 0 ? !wallUp && at >= 0 : wallUp && wallDist == dist) return;
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        wallUp = dist >= 0;
        wallDist = dist;
        if (wallUp) {
            int wz = Mth.floor(BZ) + dist - 1;
            for (int x = 212; x <= 217; x++) {
                set(x, y, wz, stone);
                set(x, y + 1, wz, stone);
            }
        }
        TacticalMap.of(level).want((int) BX, y, (int) BZ + 10, 24, 6);
    }

    private void park() {
        player.snapTo(BX, y + PARK_Y, BZ, 0F, 0F);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void start() {
        Case c = cases.get(at);
        ticks = 0;
        exposed = knew = pulled = hit = -1;
        hitBullets.clear();
        exposedOff = pullWhy = "";
        AiSkill.override = c.skill;
        build(c.kind == Kind.CORNER ? c.dist : -1);
        park();
        var gun = BuiltInRegistries.ITEM.getValue(c.gun);
        List<Mob> made = AiSpawn.spawn(level, new Vec3(BX, y, BZ), AiSpawn.Kind.DUMMY, 1, gun);
        dummy = made.isEmpty() ? null : made.get(0);
        bot = dummy == null ? null : AiBots.get(dummy);
        if (dummy == null || bot == null) return;
        dummy.setPos(BX, y, BZ);
        dummy.setYRot(0F);
        dummy.setYHeadRot(0F);
        dummy.setYBodyRot(0F);
        dummy.setXRot(0F);
        var hp = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        dummy.setHealth(dummy.getMaxHealth());
        bot.gunner.ammo.setInfinite();
        bot.gunner.loadFree();
        shotsAt = bot.gunner.shots;
        AiBots.refreshTargets(level);
    }

    /** where the player is t ticks after showing up */
    private Vec3 place(Case c, int t) {
        double z = BZ + c.dist;
        return switch (c.kind) {
            case FRONT, STAND -> new Vec3(BX, y, z);
            case SIDE -> new Vec3(BX + c.dist * Math.sin(Math.toRadians(60)), y, BZ + c.dist * Math.cos(Math.toRadians(60)));
            case CORNER -> new Vec3(Math.max(BX, BX + 3 - WALK * t), y, z);
            case RUN -> {
                // from the west end, sprinting east and back
                double leg = 2 * RUN_HALF, s = (SPRINT * t) % (2 * leg);
                yield new Vec3(BX - RUN_HALF + (s < leg ? s : 2 * leg - s), y, z);
            }
        };
    }

    private void step() {
        Case c = cases.get(at);
        if (dummy == null || bot == null) return;
        dummy.setHealth(dummy.getMaxHealth());
        int t = ticks - SETTLE;
        if (t < 0) {
            park();
        } else {
            if (t == 0) AiBots.refreshTargets(level);
            Vec3 p = place(c, t);
            Vec3 prev = t == 0 ? p : place(c, t - 1);
            float yaw = (float) (Mth.atan2(dummy.getZ() - p.z, dummy.getX() - p.x) * Mth.RAD_TO_DEG) - 90F;
            player.snapTo(p.x, p.y, p.z, yaw, 0F);
            player.setDeltaMovement(p.subtract(prev));
            player.setSprinting(c.kind == Kind.RUN);
        }
        player.setHealth(player.getMaxHealth());
        player.invulnerableTime = 0;
        if (t < 0) return;
        if (exposed < 0 && inSight()) {
            exposed = ticks;
            Vec3 eye = dummy.getEyePosition(), view = dummy.getViewVector(1F);
            Vec3 to = player.position().add(0, player.getBbHeight() * 0.55, 0).subtract(eye).normalize();
            exposedOff = String.format(Locale.ROOT, "%.0f deg off its look, mode %s", Math.toDegrees(Math.acos(Mth.clamp(to.dot(view), -1, 1))),
                    bot.brain instanceof AiSoldier s ? s.mode().name() : "-");
        }
        TargetMemory.Contact contact = bot.memory.contact(player.getId());
        if (knew < 0 && contact != null && contact.state == TargetMemory.State.VISIBLE) knew = ticks;
        if (pulled < 0 && bot.gunner.pulls > 0) {
            pulled = ticks;
            pullWhy = bot.brain instanceof AiSoldier s ? s.mode().name() + " (" + s.why.label + ")" : "-";
        }
    }

    /** a line from the dummy's eyes to the player's head, chest or legs that no block hides (field of view aside) */
    private boolean inSight() {
        Vec3 eye = dummy.getEyePosition();
        for (double part : new double[] {0.9, 0.55, 0.2})
            if (Senses.clearLine(AiBots.voxels(level), null, eye, player.position().add(0, player.getBbHeight() * part, 0))) return true;
        return false;
    }

    private void finish() {
        Case c = cases.get(at);
        String key = c.skill.name() + "|" + c.kind + "|" + c.dist + "|" + c.gun.getPath();
        if (dummy == null || bot == null) {
            trace.add("case " + key + ": no dummy");
        } else {
            int shots = bot.gunner.shots - shotsAt;
            if (c.kind.reaction()) {
                int from = exposed;
                reactions.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {rel(knew, from), rel(pulled, from), rel(hit, from)});
                trace.add(String.format(Locale.ROOT, "%s rep %d: in sight at %d (%s), knew %s, pulled %s (%s), hit %s; shots %d hits %d",
                        key, c.rep, exposed - SETTLE, exposedOff, tk(rel(knew, from)), tk(rel(pulled, from)), pullWhy, tk(rel(hit, from)), shots,
                        hitBullets.size()));
            } else {
                int[] sum = runs.computeIfAbsent(key, k -> new int[3]);
                sum[0] += shots;
                sum[1] += hitBullets.size();
                sum[2] = rel(knew, SETTLE);
                trace.add(String.format(Locale.ROOT, "%s: knew %s, first pull %s, shots %d hits %d", key, tk(rel(knew, SETTLE)),
                        tk(rel(pulled, SETTLE)), shots, hitBullets.size()));
            }
            dummy.discard();
        }
        // its bullets still in the air would land in the next round (AiCheckUnseen)
        AiCheck.clearShots(level);
        dummy = null;
        bot = null;
        park();
    }

    private static int rel(int t, int from) {
        return t < 0 || from < 0 ? -1 : t - from;
    }

    private static String tk(int t) {
        return t < 0 ? "never" : Integer.toString(t);
    }

    private void report() {
        AiSkill.override = null;
        AiBots.awake = awakeBefore;
        counting = null;
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        AiBots.refreshTargets(level);
        check.out("     react: ticks from in plain sight to knowing (VISIBLE) / first pull / first hit, " + REPS + " runs each (heavy revolver)");
        int totalHits = 0, totalCases = 0;
        for (var e : reactions.entrySet()) {
            String[] k = e.getKey().split("\\|");
            StringBuilder knew = new StringBuilder(), pull = new StringBuilder(), hit = new StringBuilder();
            for (int[] r : e.getValue()) {
                knew.append(knew.isEmpty() ? "" : "/").append(tk(r[0]));
                pull.append(pull.isEmpty() ? "" : "/").append(tk(r[1]));
                hit.append(hit.isEmpty() ? "" : "/").append(tk(r[2]));
                if (r[2] >= 0) totalHits++;
                totalCases++;
            }
            check.out(String.format(Locale.ROOT, "     react %-8s %-13s %2s bl: knew %-11s pull %-11s hit %s", k[0], Kind.valueOf(k[1]).label, k[2], knew, pull, hit));
        }
        check.out("     react: running across (sprint 0.28 bl/t, 8 blocks to and fro) and standing, " + RUN_TICKS + " ticks: hits / shots");
        int shots = 0;
        for (var e : runs.entrySet()) {
            String[] k = e.getKey().split("\\|");
            int[] s = e.getValue();
            shots += s[0];
            totalHits += s[1];
            check.out(String.format(Locale.ROOT, "     react %-8s %-18s %-15s %2s bl: %3d / %3d = %3.0f%%, knew after %s", k[0], k[3],
                    Kind.valueOf(k[1]).label, k[2], s[1], s[0], s[0] == 0 ? 0 : 100.0 * s[1] / s[0], tk(s[2])));
        }
        check.check("the reaction check ran every case (" + totalCases + " reactions, " + runs.size() + " running or standing)",
                totalCases == reactions.size() * REPS && !runs.isEmpty());
        check.check("hits on the fake player are counted (" + totalHits + ")", totalHits > 0 && shots > 0);
        String file = System.getProperty(AiCheck.PROPERTY);
        if (file == null) return;
        try {
            Files.write(Path.of(file).resolveSibling("react-trace.txt"), trace, StandardCharsets.UTF_8);
        } catch (IOException e) {
            check.out("     (the react trace was not written: " + e + ")");
        }
    }
}
