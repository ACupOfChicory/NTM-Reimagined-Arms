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
    /** ticks a fighter ran in a line of fire of its own side's */
    private int runInLine;
    /** the distance from each fighter to its nearest foe, summed over the fighters' ticks */
    private double foeDistance;
    private int foeDistanceTicks;
    /** falling back (AiSoldier.fallBack): a squad's fighters' ticks on the way, of them with one of its side near firing, hit and down on the way */
    int backTicks, backCovered, backDowns, backHits;
    /**
     * The retreat (AiCheckRetreat): the team shaken at once (its confidence to SHAKEN) when SHAKE_HITS hits have been
     * made either way, the round over AFTER_SHAKE ticks later; null: none. At the shake and at the end: its fighters,
     * their mean distance to their nearest foe; its falls back after it
     */
    final String shaken;
    static final int SHAKE_HITS = 4, AFTER_SHAKE = 400;
    static final float SHAKEN = 0.05F;
    int shakenAt = -1, shakenThen, shakenLeft, shakenBacks;
    double shakenFar, shakenFarEnd;
    private final it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap backHealth = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet backing = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
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
        this(check, level, y, battle, loners, null);
    }

    AiCheckTeams(AiCheck check, ServerLevel level, int y, boolean battle, String loners, String shaken) {
        this.shaken = shaken;
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
        for (AiBot bot : bots)
            if (bot.mob.isAlive() && bot.mover.runs() && FireLines.standsIn(level, bot.mob)) runInLine++;
        // how near each fighter keeps to its nearest foe (AiConfidence: pressing on, holding back)
        for (AiBot bot : bots) {
            if (!bot.mob.isAlive()) continue;
            double near = Double.MAX_VALUE;
            for (AiBot o : bots)
                if (o != bot && o.mob.isAlive() && !AiTeams.own(bot.mob, o.mob)) near = Math.min(near, bot.mob.distanceTo(o.mob));
            if (near < Double.MAX_VALUE) {
                foeDistance += near;
                foeDistanceTicks++;
            }
        }
        // falling back: is one of its side near firing, does it go down on the way
        for (AiBot bot : bots) {
            int id = bot.mob.getId();
            boolean was = backing.contains(id);
            if (!bot.mob.isAlive()) {
                if (was) backDowns++;
                backing.remove(id);
                continue;
            }
            // in a squad only: those alone have none to cover them
            if (!(bot.brain instanceof AiSoldier s) || !s.fallingBack() || AiSquads.of(bot) == null) {
                backing.remove(id);
                continue;
            }
            // hit on the way: its health down since the tick before
            if (was && bot.mob.getHealth() < backHealth.get(id)) backHits++;
            backHealth.put(id, bot.mob.getHealth());
            backing.add(id);
            backTicks++;
            for (AiBot o : bots)
                if (o != bot && o.mob.isAlive() && AiTeams.own(bot.mob, o.mob) && o.mob.distanceToSqr(bot.mob) < 24 * 24 && o.gunner.firingAt() != null) {
                    backCovered++;
                    break;
                }
        }
        if (shaken != null) {
            if (shakenAt < 0 && redHits + blueHits >= SHAKE_HITS) {
                shakenAt = ticks;
                for (AiBot bot : bots)
                    if (bot.mob.isAlive() && onTeam(bot, shaken)) {
                        bot.confidence.set(SHAKEN, level.getGameTime());
                        shakenThen++;
                        if (bot.brain instanceof AiSoldier s) shakenBacks -= s.fallBacks;
                    }
                shakenFar = far(shaken);
            }
            if (shakenAt >= 0 && ticks - shakenAt >= AFTER_SHAKE) {
                finish(r, b);
                return done;
            }
        }
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

    /** the stands: where a mob hurt by a blast at `at` was running from a grenade to, and from which */
    private static String ran(@org.jspecify.annotations.Nullable AiBot v, @org.jspecify.annotations.Nullable Vec3 at) {
        if (v == null || at == null || !(v.brain instanceof AiSoldier s) || s.dodgeFrom() == null) return "";
        long c = s.dodgeCell();
        return String.format(Locale.ROOT, ", running from a burst %.1f off this one to a place %s off this one%s", s.dodgeFrom().distanceTo(at),
                c == Long.MIN_VALUE ? "-" : String.format(Locale.ROOT, "%.1f", at.distanceTo(new Vec3(net.minecraft.core.BlockPos.getX(c) + 0.5,
                        net.minecraft.core.BlockPos.getY(c), net.minecraft.core.BlockPos.getZ(c) + 0.5))), v.mover.arrived() ? ", there" : "");
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
                Vec3 fled = ts != null ? ts.dodgeFrom() : null;
                if (blasts.size() < 6) blasts.add(String.format(Locale.ROOT, "its own: %.1f damage at %.1f %.1f %.1f, %.1f from the burst %s (%.1f from where it was to,"
                        + " %.1f as it flew); %s, going to %s, run to %s from a burst %s", e.getAmount(), from.getX(), from.getY(), from.getZ(),
                        at == null ? -1 : at.distanceTo(from.position()), at == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z),
                        at == null || planned == null ? -1 : at.distanceTo(planned), at == null || last[1] == null ? -1 : at.distanceTo(last[1]),
                        ts != null ? ts.mode() + " (" + ts.why.label + ")" : "-", t == null || t.mover.goal() == Long.MIN_VALUE ? "-" : cell(t.mover.goal()),
                        dodge == null || dodge == Long.MIN_VALUE ? "-" : cell(dodge),
                        fled == null ? "-" : at == null ? "?" : String.format(Locale.ROOT, "%.1f off its own", fled.distanceTo(at))) + flight(from));
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
                        + " was to (%.1f as it flew); %s%s", e.getAmount(), from.distanceTo(e.getEntity()), at == null ? -1 : at.distanceTo(e.getEntity().position()), behind ? " (behind a block)" : "",
                        at == null || planned == null ? -1 : at.distanceTo(planned), at == null || last[1] == null ? -1 : at.distanceTo(last[1]), v != null && v.brain instanceof AiSoldier s ? s.mode() + " (" + s.why.label + ")" : "-",
                        ran(v, at)) + flight(from));
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

    private static boolean onTeam(AiBot bot, String team) {
        return bot.mob.getTeam() != null && team.equals(bot.mob.getTeam().getName());
    }

    /** the mean distance from the team's fighters alive to their nearest foe (0: none) */
    private double far(String team) {
        double sum = 0;
        int n = 0;
        for (AiBot bot : bots) {
            if (!bot.mob.isAlive() || !onTeam(bot, team)) continue;
            double near = Double.MAX_VALUE;
            for (AiBot o : bots)
                if (o != bot && o.mob.isAlive() && !AiTeams.own(bot.mob, o.mob)) near = Math.min(near, bot.mob.distanceTo(o.mob));
            if (near < Double.MAX_VALUE) {
                sum += near;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private void finish(int r, int b) {
        done = true;
        // the retreat: where the shaken team got to, what it has left, how many times it fell back since the shake
        if (shaken != null && shakenAt >= 0) {
            shakenFarEnd = far(shaken);
            for (AiBot bot : bots)
                if (onTeam(bot, shaken)) {
                    if (bot.mob.isAlive()) shakenLeft++;
                    if (bot.brain instanceof AiSoldier s) shakenBacks += s.fallBacks;
                }
        }
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
        int downs = 0, brief = 0, early = 0, friendHolds = 0, coverHolds = 0, shifts = 0, peekRun = 0, low = 0, slides = 0, rolls = 0, press = 0, hold = 0, fall = 0, backs = 0;
        for (AiBot bot : bots) {
            downs += bot.prone.downs;
            brief += bot.prone.briefLies;
            early += bot.prone.shortLies;
            friendHolds += bot.gunner.holds;
            coverHolds += bot.gunner.coverHolds;
            low += bot.stoop.ticks;
            slides += bot.dash.slides;
            rolls += bot.dash.rolls;
            press += bot.confidence.pressTicks;
            hold += bot.confidence.holdTicks;
            fall += bot.confidence.fallTicks;
            if (bot.brain instanceof AiSoldier s) {
                // its own changes and the ones forced on it (cover seen into), as the owner's log counts them
                shifts += s.shifts + s.relocations;
                peekRun = Math.max(peekRun, s.longestPeekRun);
                backs += s.fallBacks;
            }
        }
        // per fighter a minute: over the minutes each fighter was alive
        double minutes = Math.max(1, aliveTicks) / 1200.0;
        check.out(String.format(Locale.ROOT, "     tactics: %d times down, %d of them under %d ticks, %d up within %d ticks not sent running; ticks held for a friend in the line %d,"
                + " for its own cover %d; cover changes %d (%.2f a fighter a minute); most looks out running from one place %d; ticks run low past low cover %d;"
                + " slides into cover %d, rolls under fire %d; ticks run in a friend's line of fire %d; confidence: ticks pressing on %d, holding back %d,"
                + " a fighter's nearest foe %.1f blocks off on average; falling back: ticks %d, falls back %d, a squad's ticks on the way %d, of them covered %d, hit on the way %d, down on the way %d", downs, brief, AiProne.BRIEF, early, AiProne.MIN_LIE, friendHolds, coverHolds, shifts, shifts / minutes,
                peekRun, low, slides, rolls, runInLine, press, hold, foeDistanceTicks == 0 ? 0 : foeDistance / foeDistanceTicks, fall, backs, backTicks, backCovered, backHits, backDowns));
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
