package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's parts of a body: an AI dummy with a carbine, behind a low wall of its own (so it stays there and fires
 * over it), shoots at a target 15 blocks north of it that shows the whole of itself, its head alone over a wall two
 * high, its head and chest over a wall one high, or its legs alone under a beam (the target floating as high as that
 * takes). Each twice: aiming at the chest always (as before) and at the part it sees. Its shots and the hits on the
 * target are counted for the same time.
 */
final class AiCheckParts {
    static final int X0 = 240, Z0 = 4, SIZE = 40, RUN = 500, LIMIT = 200;
    /** the wall and the beam reach this far either side of the target: the shooter may step aside */
    static final int WIDE = 6;
    static final double SX = 260.5, SZ = 34.5, TX = 260.5, TZ = 19.5;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private enum Shows {
        WHOLE("the whole of it"), HEAD("its head over a wall two high"), HEAD_CHEST("its head and chest over a wall one high"),
        LEGS("its legs under a beam");

        final String text;

        Shows(String text) {
            this.text = text;
        }
    }

    private static AiCheckParts counting;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1, ticks, seenAt, shotsAt, hits, firstShot, firstParts;
    private final List<String> ends = new ArrayList<>();
    private Mob shooter, target;
    private double targetY;
    private boolean started;
    // per round: shots, hits, ticks to the first shot after it saw the target
    private final List<int[]> results = new ArrayList<>();

    AiCheckParts(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private static Shows shows(int round) {
        return Shows.values()[round / 2];
    }

    private static boolean parts(int round) {
        return round % 2 == 1;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckParts c = counting;
                if (c != null && e.getEntity() == c.target && e.getSource().getEntity() == c.shooter) c.hits++;
            });
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        AiBot bot = AiBots.get(shooter);
        if (bot == null) {
            check.check("the parts: the shooter is an AI mob", false);
            return finishAll();
        }
        target.setHealth(target.getMaxHealth());
        // held where it is: hits push it back, and a target pushed a block or two off goes behind its wall or beam
        // (its legs show under the beam by a few hundredths of a block from the shooter's eye)
        target.setPos(TX, targetY, TZ);
        target.setDeltaMovement(Vec3.ZERO);
        TargetMemory.Contact c = bot.memory.contact(target.getId());
        if (seenAt < 0 && c != null && c.state == TargetMemory.State.VISIBLE) {
            seenAt = ticks;
            shotsAt = bot.gunner.shots;
            hits = 0;
            firstParts = c.parts;
        }
        if (seenAt >= 0 && firstShot < 0 && bot.gunner.shots > shotsAt) firstShot = ticks - seenAt;
        if (seenAt >= 0 && ticks - seenAt >= RUN || seenAt < 0 && ticks >= LIMIT) {
            int shots = seenAt < 0 ? 0 : bot.gunner.shots - shotsAt;
            results.add(new int[] {shots, hits, seenAt < 0 ? -1 : seenAt, firstShot, firstParts});
            ends.add(String.format(Locale.ROOT, "%s %s: the shooter ended at %.1f %.1f %.1f, %s, seeing %s; the target at %.1f, %.2f high", shows(round),
                    parts(round) ? "parts" : "chest", shooter.getX(), shooter.getY(), shooter.getZ(),
                    bot.brain instanceof AiSoldier m ? m.mode() + (bot.prone.lying() ? " lying" : "") : "-", seen(c == null ? 0 : c.parts), target.getY(),
                    target.getBbHeight()));
            shooter.discard();
            target.discard();
            if (round + 1 >= Shows.values().length * 2) return finishAll();
            start(round + 1);
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build(Shows s) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        int tx = (int) Math.floor(TX), tz = (int) Math.floor(TZ), sx = (int) Math.floor(SX), sz = (int) Math.floor(SZ);
        // the shooter's own low wall: its cover, it fires over it standing
        for (int x = sx - 2; x <= sx + 2; x++) set(x, y, sz - 1, stone);
        switch (s) {
            case WHOLE -> {
            }
            case HEAD -> {
                // the target floating 0.75 up behind a wall two high: its chest (0.55 of its height) well under the
                // wall's top, its head (0.9) over it
                for (int x = tx - WIDE; x <= tx + WIDE; x++) {
                    set(x, y, tz + 1, stone);
                    set(x, y + 1, tz + 1, stone);
                }
            }
            case HEAD_CHEST -> {
                for (int x = tx - WIDE; x <= tx + WIDE; x++) set(x, y, tz + 1, stone);
            }
            case LEGS -> {
                // a beam a block up just before the target, which floats half a block up: its legs (0.2 of its
                // height) under the beam, its chest (0.55) well behind it
                for (int x = tx - WIDE; x <= tx + WIDE; x++)
                    for (int h = 1; h <= 3; h++) set(x, y + h, tz + 1, stone);
            }
        }
        TacticalMap.of(level).want((int) SX, y, (int) ((SZ + TZ) / 2), 20, 6);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        seenAt = -1;
        firstShot = -1;
        firstParts = 0;
        hits = 0;
        Shows s = shows(r);
        AiSoldier.aimParts = parts(r);
        build(s);
        target = (Mob) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy")).create(level,
                EntitySpawnReason.COMMAND);
        // floating (no gravity): its head alone over the wall two high, its head and chest well over the wall one high
        // (its legs under it), its legs alone under the beam
        targetY = y + switch (s) {
            case HEAD -> 0.75;
            case HEAD_CHEST -> 0.25;
            case LEGS -> 0.5;
            case WHOLE -> 0.0;
        };
        target.setPos(TX, targetY, TZ);
        target.setNoGravity(true);
        target.addTag(AiBots.TARGET_TAG);
        var hp = target.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        target.setHealth(1000);
        level.addFreshEntity(target);
        List<Mob> made = AiSpawn.spawn(level, new Vec3(SX, y, SZ), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN));
        shooter = made.get(0);
        shooter.setPos(SX, y, SZ);
        shooter.setYRot(180F);
        shooter.setYHeadRot(180F);
        shooter.setYBodyRot(180F);
        // rooted behind its wall: this stage is about where it aims, not where it goes (lying to reload, it loses the
        // target, and once it went round the target's wall to look)
        var speed = shooter.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        AiBot bot = AiBots.get(shooter);
        if (bot != null) bot.gunner.ammo.setInfinite();
        AiBots.refreshTargets(level);
    }

    /** a part aimed at: 3 hits at least, a quarter of its shots at least, and 15 points over aiming at the chest */
    private static boolean better(int[] part, int[] chest) {
        double share = (double) part[1] / Math.max(1, part[0]);
        return part[1] >= 3 && share >= 0.25 && share > (double) chest[1] / Math.max(1, chest[0]) + 0.15;
    }

    private boolean finishAll() {
        counting = null;
        AiBots.awake = false;
        AiSoldier.aimParts = true;
        StringBuilder b = new StringBuilder("     parts of a body, an AI dummy with a carbine 15 blocks off, hits of shots aiming at the chest / at the part it sees:");
        int[][] r = results.toArray(new int[0][]);
        for (int i = 0; i + 1 < r.length; i += 2) {
            Shows s = shows(i);
            b.append(String.format(Locale.ROOT, " %s (seen %s): %d/%d (%s, first shot %d ticks after it saw it) / %d/%d (%s, first %d);", s.text,
                    seen(r[i + 1][4]), r[i][1], r[i][0], pct(r[i][1], r[i][0]), r[i][3], r[i + 1][1], r[i + 1][0], pct(r[i + 1][1], r[i + 1][0]), r[i + 1][3]));
        }
        check.out(b.toString());
        for (String e : ends) check.out("       " + e);
        check.check("the parts: every round ran", r.length == Shows.values().length * 2);
        if (r.length == Shows.values().length * 2) {
            // its head alone over a wall: aiming at the chest hits the wall; at the head, the head
            // by the share of shots that hit (the shots in a run vary a little with reloads and lying down)
            check.check("aims at a head over a wall and hits it (aiming at the chest hits the wall)", better(r[3], r[2]));
            // its head and chest over a low wall: at the middle of them; the chest is clear of the wall too, so the gain
            // is small (+0 to +30 points over eight runs) and the check only that it is no worse, give or take a few
            check.check("aims at the middle of a head and chest over a low wall: hits no fewer than at the chest (4 either way)", r[5][1] + 4 >= r[4][1]);
            check.check("aims at legs under a beam and hits them", better(r[7], r[6]));
            check.check("the whole of it in the open: hit as before", r[1][1] >= r[0][1] * 0.7 && r[1][1] >= 3);
        }
        return true;
    }

    private static String seen(int parts) {
        if (parts == 0) return "nothing";
        List<String> p = new ArrayList<>();
        if ((parts & Senses.HEAD) != 0) p.add("head");
        if ((parts & Senses.CHEST) != 0) p.add("chest");
        if ((parts & Senses.LEGS) != 0) p.add("legs");
        return String.join("+", p);
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : Math.round(100.0 * a / b) + "%";
    }
}
