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
 */
final class AiCheckTeams {
    static final int SIDE = 5, LIMIT = 3600;
    static final String RED = "armsai_red", BLUE = "armsai_blue";
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_heavy_revolver");

    private static AiCheckTeams counting;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int ticks;
    private final List<Mob> red = new ArrayList<>(), blue = new ArrayList<>();
    // hits by red on blue, by blue on red, by either on its own
    private int redHits, blueHits, ownHits, friendlyAtStart;
    private boolean done;

    AiCheckTeams(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (done) return true;
        if (ticks == 0) start();
        ticks++;
        int r = alive(red), b = alive(blue);
        if (r == 0 || b == 0 || ticks >= LIMIT) finish(r, b);
        return done;
    }

    private void start() {
        AiBots.awake = true;
        friendlyAtStart = AiBots.friendlyHits;
        spawn(red, RED, new Vec3(65.5, y, 9.5));
        spawn(blue, BLUE, new Vec3(65.5, y, 33.5));
        AiBots.refreshTargets(level);
        if (counting == null) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
            AiCheckTeams c = counting;
            if (c != null) c.hurt(e);
        });
        counting = this;
    }

    private void spawn(List<Mob> into, String team, Vec3 at) {
        var gun = BuiltInRegistries.ITEM.getValue(GUN);
        for (Mob m : AiSpawn.spawn(level, at, AiSpawn.Kind.FBI, SIDE, gun)) {
            AiTeams.join(level, m, team);
            AiBot bot = AiBots.get(m);
            if (bot != null) bot.gunner.ammo.setInfinite();
            into.add(m);
        }
    }

    private List<Mob> concat() {
        List<Mob> all = new ArrayList<>(red);
        all.addAll(blue);
        return all;
    }

    private static int alive(List<Mob> side) {
        int n = 0;
        for (Mob m : side) if (m.isAlive() && !m.isRemoved()) n++;
        return n;
    }

    private void hurt(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof LivingEntity from) || from == e.getEntity()) return;
        boolean fromRed = red.contains(from), fromBlue = blue.contains(from);
        if (!fromRed && !fromBlue) return;
        boolean toRed = red.contains(e.getEntity()), toBlue = blue.contains(e.getEntity());
        if (fromRed && toRed || fromBlue && toBlue) ownHits++;
        else if (fromRed && toBlue) redHits++;
        else if (fromBlue && toRed) blueHits++;
    }

    private void finish(int r, int b) {
        done = true;
        counting = null;
        AiBots.awake = false;
        String won = r > 0 && b == 0 ? "red won" : b > 0 && r == 0 ? "blue won" : r == 0 ? "both down" : "nobody won";
        check.out(String.format(Locale.ROOT, "     teams, %d on %d, FBI with heavy revolvers (their own), endless rounds: %s in %d ticks (%.1f s), red %d left, blue %d left; "
                + "hits on the other team red %d, blue %d; on their own %d (AI mobs hit by AI mobs they do not fight: %d)", SIDE, SIDE, won, ticks, ticks / 20.0,
                r, b, redHits, blueHits, ownHits, AiBots.friendlyHits - friendlyAtStart));
        boolean fought = redHits + blueHits >= 10 && (r < SIDE || b < SIDE);
        if (!fought) for (Mob m : red.size() + blue.size() == 0 ? List.<Mob>of() : concat()) {
            AiBot bot = AiBots.get(m);
            check.out(String.format(Locale.ROOT, "       %s %s at %.1f %.1f %.1f: %s", m.getTeam() == null ? "no team" : m.getTeam().getName(), m.getType().toShortString(),
                    m.getX(), m.getY(), m.getZ(), bot == null ? "no bot" : "active " + bot.active() + ", contacts " + bot.memory.contacts().stream().map(c -> c.state.toString()).toList()
                            + ", brain " + (bot.brain == null ? "-" : bot.brain.getClass().getSimpleName()) + ", pulls " + bot.gunner.pulls + ", shots " + bot.gunner.shots
                            + ", gun " + m.getMainHandItem().getItem()));
        }
        check.check("two teams of AI mobs fight each other", fought);
        check.check("no AI mob hits one of its own team", ownHits == 0);
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
