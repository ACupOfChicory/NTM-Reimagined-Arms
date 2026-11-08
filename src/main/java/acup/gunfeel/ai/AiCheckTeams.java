package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's teams, on the fight's field (its wall and block for cover): five FBI agents of a team "red" at its
 * south end and five of "blue" at its north end, all with heavy revolvers (an FBI agent's own; it wears armour 20)
 * and endless rounds, no player about. They fight till one team is down or {@value #LIMIT} ticks are over; hits on
 * the other team and on their own are counted (none on their own).
 *
 * <p>The battle ({@code battle}) is the fight as the owner had it: a field of its own with low walls of sandbags and
 * high ones of stone between the two teams, each FBI agent with the gun NTM gives it (a heavy revolver or a SPAS-12)
 * and the rounds it comes with; its tactics are held to the stage's marks (no lying down for a moment, no looking out
 * from one place over and over, cover changed now and then).
 */
final class AiCheckTeams {
    static final int SIDE = 5, LIMIT = 3600;
    /** the battle's field */
    static final int BX0 = 8, BZ0 = 120, BSIZE = 48;
    /** the battle's marks: lies under BRIEF ticks at most, looks out running from one place at most, cover changes a fighter a minute at least */
    static final int MARK_BRIEF = 0, MARK_PEEK_RUN = 4;
    static final double MARK_SHIFTS = 2;
    static final String RED = "armsai_red", BLUE = "armsai_blue";
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_heavy_revolver");

    private static AiCheckTeams counting;
    /** the one listener for hits, for the teams and the battle alike */
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final boolean battle;
    /** the duel (AiCheckDuel): the team that fights without squads (AiSquads.offTeams); null: both in squads */
    final String loners;
    /** at the end: the fighters each team had left */
    int redLeft = -1, blueLeft = -1;
    private int ticks;
    private final List<Mob> red = new ArrayList<>(), blue = new ArrayList<>();
    // every fighter's mind, kept past its death for the tactics' counts
    private final List<AiBot> bots = new ArrayList<>();
    // the ticks the fighters were alive, all together
    private long aliveTicks;
    // hits by red on blue, by blue on red, by either on its own
    private int redHits, blueHits, ownHits, friendlyAtStart;
    /** of the hits on their own, those by a blast (a grenade), and what each was doing */
    private int ownBlasts, selfBlasts;
    private final List<String> blasts = new java.util.ArrayList<>();
    /** the grenades thrown and those that left their planned flight before this fight */
    private int tracedAtStart, partedAtStart;
    /** the duel: the hits on the other team, red's and blue's; the damage done to it; the shots fired */
    int redHitsOut, blueHitsOut, redShots, blueShots;
    double redDamage, blueDamage;
    private boolean done;
    // the sounds that reached the AI in the fight: per name, how many, how many loud and made by no mob or player
    // near them, and the distance from such a loud one to the nearest fighter
    private final java.util.Map<String, int[]> sounds = new java.util.TreeMap<>();
    private final java.util.Map<String, List<Double>> farFrom = new java.util.TreeMap<>();

    AiCheckTeams(AiCheck check, ServerLevel level, int y) {
        this(check, level, y, false);
    }

    AiCheckTeams(AiCheck check, ServerLevel level, int y, boolean battle) {
        this(check, level, y, battle, null);
    }

    AiCheckTeams(AiCheck check, ServerLevel level, int y, boolean battle, String loners) {
        this.loners = loners;
        this.check = check;
        this.level = level;
        this.y = y;
        this.battle = battle;
    }

    boolean tick() {
        if (done) return true;
        if (ticks == 0) start();
        ticks++;
        int r = alive(red), b = alive(blue);
        aliveTicks += r + b;
        if (r == 0 || b == 0 || ticks >= LIMIT) finish(r, b);
        return done;
    }

    private void start() {
        AiBots.awake = true;
        // squads for both teams (the duel: one), made anew for this fight
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        if (loners != null) AiSquads.offTeams.add(loners);
        AiSquads.forget(level);
        friendlyAtStart = AiBots.friendlyHits;
        tracedAtStart = AiGrenades.traced;
        partedAtStart = AiGrenades.partings.size();
        if (battle) {
            field();
            spawn(red, RED, new Vec3(BX0 + BSIZE / 2 + 0.5, y, BZ0 + 3.5));
            spawn(blue, BLUE, new Vec3(BX0 + BSIZE / 2 + 0.5, y, BZ0 + BSIZE - 3.5));
        } else {
            spawn(red, RED, new Vec3(65.5, y, 9.5));
            spawn(blue, BLUE, new Vec3(65.5, y, 33.5));
        }
        AiBots.refreshTargets(level);
        if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
            AiCheckTeams c = counting;
            if (c != null) c.hurt(e);
        });
        listening = true;
        counting = this;
        AiBots.soundTrace = (lvl, snd) -> {
            int[] n = sounds.computeIfAbsent(snd.name(), k -> new int[3]);
            n[0]++;
            if (snd.loud()) n[1]++;
            if (snd.loud() && !snd.ofMob() && !snd.ofPlayer()) {
                n[2]++;
                double best = Double.MAX_VALUE;
                for (Mob m : concat()) if (m.isAlive()) best = Math.min(best, m.position().distanceTo(new Vec3(snd.x(), snd.y(), snd.z())));
                farFrom.computeIfAbsent(snd.name(), k -> new ArrayList<>()).add(best);
            }
        };
    }

    /**
     * The battle's field: stone ground, and between the teams' ends (south red, north blue) rows of cover each side
     * of the middle, mirrored: low walls of sandbags (stone where NTM has none), high walls of stone two high, and a
     * stone pillar three high in the middle with low walls by the sides.
     */
    private void field() {
        for (int cx = BX0 >> 4; cx <= (BX0 + BSIZE) >> 4; cx++)
            for (int cz = BZ0 >> 4; cz <= (BZ0 + BSIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var bags = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("hbm", "sandbags")).map(b -> b.defaultBlockState()).orElse(stone);
        for (int x = BX0; x < BX0 + BSIZE; x++)
            for (int z = BZ0; z < BZ0 + BSIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        int mid = BZ0 + BSIZE / 2;
        for (int sign : new int[] {-1, 1}) {
            // low walls 10 out from the middle, high walls 6 out, each mirrored
            for (int x0 : new int[] {BX0 + 6, BX0 + 20, BX0 + 34})
                for (int x = x0; x < x0 + 4; x++) set(x, y, mid + sign * 10, bags);
            for (int x0 : new int[] {BX0 + 12, BX0 + 28})
                for (int x = x0; x < x0 + 4; x++) {
                    set(x, y, mid + sign * 6, stone);
                    set(x, y + 1, mid + sign * 6, stone);
                }
        }
        for (int x = BX0 + 23; x <= BX0 + 24; x++)
            for (int z = mid - 1; z <= mid; z++)
                for (int h = 0; h < 3; h++) set(x, y + h, z, stone);
        for (int x0 : new int[] {BX0 + 4, BX0 + 41})
            for (int x = x0; x < x0 + 3; x++) set(x, y, mid, bags);
        // the sandbags joined along their rows, as placed by hand
        if (bags != stone)
            for (int x = BX0; x < BX0 + BSIZE; x++)
                for (int z = BZ0; z < BZ0 + BSIZE; z++) {
                    var here = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                    if (here.getBlock() != bags.getBlock()) continue;
                    boolean e = level.getBlockState(new net.minecraft.core.BlockPos(x + 1, y, z)).getBlock() == bags.getBlock();
                    boolean w = level.getBlockState(new net.minecraft.core.BlockPos(x - 1, y, z)).getBlock() == bags.getBlock();
                    set(x, y, z, AiCheck.joined(here, e, w, false, false));
                }
        TacticalMap.of(level).want(BX0 + BSIZE / 2, y, mid, BSIZE / 2 + 4, 6);
    }

    private void set(int x, int yy, int z, net.minecraft.world.level.block.state.BlockState st) {
        level.setBlock(new net.minecraft.core.BlockPos(x, yy, z), st, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    private void spawn(List<Mob> into, String team, Vec3 at) {
        // the battle: the gun NTM gives an FBI agent and the rounds it comes with
        // the duel: all with the heavy revolver (an FBI agent's own) and the rounds it comes with, so that the guns NTM
        // hands out at random do not decide it
        var gun = battle && loners == null ? null : BuiltInRegistries.ITEM.getValue(GUN);
        for (Mob m : AiSpawn.spawn(level, at, AiSpawn.Kind.FBI, SIDE, gun)) {
            AiTeams.join(level, m, team);
            AiBot bot = AiBots.get(m);
            if (bot != null) {
                if (!battle && loners == null) bot.gunner.ammo.setInfinite();
                bots.add(bot);
            }
            into.add(m);
        }
    }

    /** the guns the fighters hold, counted */
    private String guns() {
        java.util.Map<String, Integer> n = new java.util.TreeMap<>();
        for (Mob m : concat()) n.merge(BuiltInRegistries.ITEM.getKey(m.getMainHandItem().getItem()).getPath(), 1, Integer::sum);
        return n.toString();
    }

    private List<Mob> concat() {
        List<Mob> all = new ArrayList<>(red);
        all.addAll(blue);
        return all;
    }

    private static String cell(long c) {
        net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(c);
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static int alive(List<Mob> side) {
        int n = 0;
        for (Mob m : side) if (m.isAlive() && !m.isRemoved()) n++;
        return n;
    }

    /** the thrower's grenade going off: how it was thrown and where it left its planned flight */
    private String flight(LivingEntity from) {
        String f = AiGrenades.parted(level, from);
        return f == null ? "" : "\n         its flight: " + f;
    }

    private void hurt(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof LivingEntity from)) return;
        if (from == e.getEntity()) {
            // its own grenade
            if (e.getSource().is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) && (red.contains(from) || blue.contains(from))) {
                selfBlasts++;
                // where its grenade burst and was meant to, where it stood, and what it was doing
                Vec3[] last = AiGrenades.bursting(level, from);
                Vec3 at = last == null ? null : last[0];
                AiBot t = from instanceof Mob fm ? AiBots.get(fm) : null;
                AiSoldier ts = t != null && t.brain instanceof AiSoldier x ? x : null;
                Vec3 planned = ts != null ? ts.planned : null;
                Long dodge = ts != null ? ts.dodgeCell() : null;
                if (blasts.size() < 6) blasts.add(String.format(Locale.ROOT, "its own: %.1f damage at %.1f %.1f %.1f, %.1f from the burst %s (%.1f from where it was to,"
                        + " %.1f as it flew); %s, going to %s, run to %s", e.getAmount(), from.getX(), from.getY(), from.getZ(),
                        at == null ? -1 : at.distanceTo(from.position()), at == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z),
                        at == null || planned == null ? -1 : at.distanceTo(planned), at == null || last[1] == null ? -1 : at.distanceTo(last[1]),
                        ts != null ? ts.mode() + " (" + ts.why.label + ")" : "-", t == null || t.mover.goal() == Long.MIN_VALUE ? "-" : cell(t.mover.goal()),
                        dodge == null || dodge == Long.MIN_VALUE ? "-" : cell(dodge)) + flight(from));
            }
            return;
        }
        boolean fromRed = red.contains(from), fromBlue = blue.contains(from);
        if (!fromRed && !fromBlue) return;
        boolean toRed = red.contains(e.getEntity()), toBlue = blue.contains(e.getEntity());
        if (fromRed && toRed || fromBlue && toBlue) {
            ownHits++;
            if (e.getSource().is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
                ownBlasts++;
                AiBot v = AiBots.get((Mob) e.getEntity());
                // where the thrower's grenade was last (the damage's own position is the thrower's)
                Vec3[] last = AiGrenades.bursting(level, from);
                Vec3 at = last == null ? null : last[0];
                AiBot t = from instanceof Mob fm ? AiBots.get(fm) : null;
                Vec3 planned = t != null && t.brain instanceof AiSoldier ts ? ts.planned : null;
                boolean behind = at != null && AiGrenades.behind(AiBots.voxels(level), at, e.getEntity().getX(), e.getEntity().getY(), e.getEntity().getZ());
                if (blasts.size() < 6) blasts.add(String.format(Locale.ROOT, "%.1f damage, %.1f from the thrower, %.1f from the burst%s, the burst %.1f from where it"
                        + " was to (%.1f as it flew); %s", e.getAmount(), from.distanceTo(e.getEntity()), at == null ? -1 : at.distanceTo(e.getEntity().position()), behind ? " (behind a block)" : "",
                        at == null || planned == null ? -1 : at.distanceTo(planned), at == null || last[1] == null ? -1 : at.distanceTo(last[1]), v != null && v.brain instanceof AiSoldier s ? s.mode() + " (" + s.why.label + ")" : "-") + flight(from));
            }
        }
        else if (fromRed && toBlue) {
            redHits++;
            redDamage += e.getAmount();
        } else if (fromBlue && toRed) {
            blueHits++;
            blueDamage += e.getAmount();
        }
    }

    private void finish(int r, int b) {
        done = true;
        redLeft = r;
        blueLeft = b;
        AiSquads.offTeams.clear();
        counting = null;
        AiBots.awake = false;
        String won = r > 0 && b == 0 ? "red won" : b > 0 && r == 0 ? "blue won" : r == 0 ? "both down" : "nobody won";
        if (loners != null) check.out("     duel: " + (RED.equals(loners) ? "red" : "blue") + " alone, the other team in squads");
        check.out(String.format(Locale.ROOT, "     " + (battle ? "battle, %d on %d, FBI with their own guns (" + guns() + "), the rounds they come with, low and high walls"
                : "teams, %d on %d, FBI with heavy revolvers (their own), endless rounds") + ": %s in %d ticks (%.1f s), red %d left, blue %d left; "
                + "hits on the other team red %d, blue %d; on their own %d, %d by a blast (AI mobs hit by AI mobs they do not fight: %d)", SIDE, SIDE, won, ticks, ticks / 20.0,
                r, b, redHits, blueHits, ownHits, ownBlasts, AiBots.friendlyHits - friendlyAtStart));
        for (String s : blasts) check.out("       own blast: " + s);
        int thrown = 0, dodges = 0;
        for (AiBot bot : bots)
            if (bot.brain instanceof AiSoldier s) {
                thrown += s.thrown;
                dodges += s.dodges;
            }
        check.out(String.format(Locale.ROOT, "       grenades thrown %d, run from %d times; throwers hit by their own %d times", thrown, dodges, selfBlasts));
        List<String> parted = AiGrenades.partings.subList(partedAtStart, AiGrenades.partings.size());
        check.out(String.format(Locale.ROOT, "       planned flights: %d of %d grenades left them", parted.size(), AiGrenades.traced - tracedAtStart));
        for (int i = 0; i < Math.min(6, parted.size()); i++) check.out("         " + parted.get(i));
        boolean fought = redHits + blueHits >= 10 && (r < SIDE || b < SIDE);
        if (!fought) for (Mob m : red.size() + blue.size() == 0 ? List.<Mob>of() : concat()) {
            AiBot bot = AiBots.get(m);
            check.out(String.format(Locale.ROOT, "       %s %s at %.1f %.1f %.1f: %s", m.getTeam() == null ? "no team" : m.getTeam().getName(), m.getType().toShortString(),
                    m.getX(), m.getY(), m.getZ(), bot == null ? "no bot" : "active " + bot.active() + ", contacts " + bot.memory.contacts().stream().map(c -> c.state.toString()).toList()
                            + ", brain " + (bot.brain == null ? "-" : bot.brain.getClass().getSimpleName()) + ", pulls " + bot.gunner.pulls + ", shots " + bot.gunner.shots
                            + ", gun " + m.getMainHandItem().getItem()));
        }
        int downs = 0, brief = 0, early = 0, friendHolds = 0, coverHolds = 0, shifts = 0, peekRun = 0, low = 0, slides = 0, rolls = 0;
        for (AiBot bot : bots) {
            downs += bot.prone.downs;
            brief += bot.prone.briefLies;
            early += bot.prone.shortLies;
            friendHolds += bot.gunner.holds;
            coverHolds += bot.gunner.coverHolds;
            low += bot.stoop.ticks;
            slides += bot.dash.slides;
            rolls += bot.dash.rolls;
            if (bot.brain instanceof AiSoldier s) {
                // its own changes and the ones forced on it (cover seen into), as the owner's log counts them
                shifts += s.shifts + s.relocations;
                peekRun = Math.max(peekRun, s.longestPeekRun);
            }
        }
        // per fighter a minute: over the minutes each fighter was alive
        double minutes = Math.max(1, aliveTicks) / 1200.0;
        check.out(String.format(Locale.ROOT, "     tactics: %d times down, %d of them under %d ticks, %d up within %d ticks not sent running; ticks held for a friend in the line %d,"
                + " for its own cover %d; cover changes %d (%.2f a fighter a minute); most looks out running from one place %d; ticks run low past low cover %d;"
                + " slides into cover %d, rolls under fire %d", downs, brief, AiProne.BRIEF, early, AiProne.MIN_LIE, friendHolds, coverHolds, shifts, shifts / minutes,
                peekRun, low, slides, rolls));
        AiBots.soundTrace = null;
        StringBuilder heard = new StringBuilder("     sounds that reached the AI in the fight (count, loud, loud and made by no mob or player near: distance to the nearest fighter):");
        for (var e : sounds.entrySet()) {
            int[] n = e.getValue();
            List<Double> d = farFrom.getOrDefault(e.getKey(), List.of());
            List<Double> sorted = new ArrayList<>(d);
            java.util.Collections.sort(sorted);
            heard.append(String.format(Locale.ROOT, " %s %d, loud %d, loud on its own %d%s;", e.getKey(), n[0], n[1], n[2],
                    sorted.isEmpty() ? "" : String.format(Locale.ROOT, " (%.1f..%.1f bl, median %.1f)", sorted.get(0), sorted.get(sorted.size() - 1), sorted.get(sorted.size() / 2))));
        }
        check.out(heard.toString());
        if (battle && loners == null) {
            check.check("the battle: no lying down for less than " + AiProne.BRIEF / 20 + " s (" + brief + " times)", brief <= MARK_BRIEF);
            check.check("the battle: no looking out from one place more than " + MARK_PEEK_RUN + " times running (" + peekRun + ")", peekRun <= MARK_PEEK_RUN);
            check.check(String.format(Locale.ROOT, "the battle: cover changed %.0f times a fighter a minute at least (%.2f)", MARK_SHIFTS, shifts / minutes),
                    shifts / minutes >= MARK_SHIFTS);
        }
        if (loners != null) {
            // the duel's rounds are summed up by AiCheckDuel
            redHitsOut = redHits;
            blueHitsOut = blueHits;
            for (AiBot bot : bots) {
                if (bot.mob.getTeam() != null && RED.equals(bot.mob.getTeam().getName())) redShots += bot.gunner.shots;
                else blueShots += bot.gunner.shots;
            }
        } else check.check("two teams of AI mobs fight each other", fought);
        check.check("no AI mob hits one of its own team", ownHits == 0);
        check.check("no AI mob is hit by its own grenade", selfBlasts == 0);
        for (Mob m : red) m.discard();
        for (Mob m : blue) m.discard();
        var board = level.getScoreboard();
        for (String t : List.of(RED, BLUE)) {
            var team = board.getPlayerTeam(t);
            if (team != null) board.removePlayerTeam(team);
        }
        AiBots.refreshTargets(level);
    }
}
