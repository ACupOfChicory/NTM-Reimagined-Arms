package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's hatch (AiHatch, stage 6 after T8): a stone plain, under it a room dug out SHAFT blocks down, its way in a
 * ladder up a shaft to a trapdoor shut in the ground (an oak one and NTM's steel one by turns). One of a team in the
 * room's far corner; a squad of three of another on the ground, a grenade each, knowing it is there. Rounds by twos,
 * aiHatches off (before) and on. Counted: who of the squad got down and who is left, whether the one below went down and
 * by what, grenades dropped down the shaft and where they burst, the squad hurt by a blast. Then UP rounds the other
 * way: the squad in the room, the one it knows of on the ground; counted who came up. A drill of the way down
 * (the one below is no match for a squad either way: NTM's bunker, AiCheckBunkerNtm, is the fight).
 */
final class AiCheckHatch {
    static final int X0 = 4400, Z0 = 0, SX = 4410, SZ = 0, SHAFT = 9, RUN = 1600, ROUNDS = 8, SQUAD = 3;
    /** then rounds the other way, an oak hatch and a steel one: the squad in the room, the one it knows of up on the ground */
    static final int UP = 2;
    /** the room under it: x SX-RX..SX+RX, z SZ..SZ+RZ, three high */
    static final int RX = 3, RZ = 6;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, downs, ownBlast, frags0, begun0, deep0, down0, noFrag0, unsafe0, rim0;
    private String belowBy = "-";
    private int belowAt = -1;
    private Mob below;
    private final List<Mob> squad = new ArrayList<>();
    private float[] health = new float[SQUAD];
    private final Map<Integer, Vec3> nades = new HashMap<>();
    private final List<String> bursts = new ArrayList<>();
    /** per round: {on, steel, squad left, got down, the one below down at (-1 never), by a blast, grenades down, squad hurt by a blast} */
    private final List<int[]> results = new ArrayList<>();
    private final boolean hatchOn = AiHatch.on;
    /** a soldier's legs in the shaft every few ticks (looking into a climb) */
    private static final boolean trace = false;

    AiCheckHatch(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    /** the ground's top block, the room's floor's top */
    private int top() {
        return y - 1;
    }

    private int floor() {
        return top() - SHAFT;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 - 8) >> 4; cx <= (X0 + 40) >> 4; cx++)
                for (int cz = (Z0 - 24) >> 4; cz <= (Z0 + 24) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            build();
            start(0);
            return false;
        }
        ticks++;
        for (var e : level.getEntities((Entity) null, new AABB(X0 - 8, floor() - 2, Z0 - 24, X0 + 40, y + 30, Z0 + 24),
                e -> e.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("grenade"))) nades.put(e.getId(), e.position());
        for (var it = nades.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            Entity e = level.getEntity(en.getKey());
            if (e != null && !e.isRemoved()) continue;
            Vec3 at = en.getValue();
            bursts.add(String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z));
            check.out(String.format(Locale.ROOT, "     hatch, round %d, tick %d: a grenade gone at %.1f %.1f %.1f (the floor's at %d)", round + 1, ticks, at.x, at.y, at.z, floor() + 1));
            it.remove();
        }
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < health[i]) {
                var by = m.getLastDamageSource();
                if (by == null || by.getDirectEntity() == null || !by.getDirectEntity().getType().toShortString().contains("bullet")) ownBlast++;
            }
            if (health[i] > 0 && (round >= ROUNDS ? m.getY() >= y - 0.1 : m.getY() < top() - 1) && !wasDown[i]) {
                wasDown[i] = true;
                downs++;
            }
            health[i] = h;
        }
        if (belowAt < 0 && !below.isAlive()) {
            belowAt = ticks;
            var by = below.getLastDamageSource();
            belowBy = by == null ? "?" : by.type().msgId() + (by.getDirectEntity() == null ? "" : " " + by.getDirectEntity().getType().toShortString());
        }
        if (trace && round >= ROUNDS && ticks % 5 == 0 && ticks < 300)
            for (Mob m : squad)
                if (m.isAlive() && Math.abs(m.getX() - SX - 0.5) < 0.8 && Math.abs(m.getZ() - SZ - 0.5) < 0.8 && m.getY() < y + 0.5)
                    check.out(String.format(Locale.ROOT, "     hatch, round %d, tick %d: in the shaft %d at %.2f %.2f %.2f v %.2f %.2f %.2f climb %s hcol %s ground %s pose %s zza %.2f xxa %.2f nav %s legs %s",
                            round + 1, ticks, m.getId(), m.getX(), m.getY(), m.getZ(), m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z, m.onClimbable(), m.horizontalCollision,
                            m.onGround(), m.getPose(), m.zza, m.xxa, m.getNavigation().isDone() ? "done" : "on " + m.getNavigation().getPath().getNextNodePos(),
                            AiBots.get(m) instanceof AiBot b ? b.mover.state() + " " + (b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "") : ""));
        if (ticks % 200 == 0) {
            StringBuilder w = new StringBuilder();
            for (Mob m : squad)
                w.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                        AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : ""));
            check.out(String.format(Locale.ROOT, "     hatch, round %d, tick %d:%s", round + 1, ticks, w));
        }
        boolean anyOut = squad.stream().anyMatch(Mob::isAlive);
        boolean allUp = round >= ROUNDS && downs >= squad.stream().filter(Mob::isAlive).count();
        if (ticks < RUN && anyOut && below.isAlive() && !allUp) return false;
        end();
        if (round + 1 < ROUNDS + UP) {
            start(round + 1);
            return false;
        }
        return finish();
    }

    private boolean[] wasDown = new boolean[SQUAD];

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = X0 - 8; x <= X0 + 40; x++)
            for (int z = Z0 - 24; z <= Z0 + 24; z++) {
                for (int yy = floor() - 2; yy <= top(); yy++) level.setBlock(p.set(x, yy, z), stone, Block.UPDATE_CLIENTS);
                for (int yy = y; yy <= y + 8; yy++) level.setBlock(p.set(x, yy, z), air, Block.UPDATE_CLIENTS);
            }
        // the room under it, three high, its floor floor()
        for (int x = SX - RX; x <= SX + RX; x++)
            for (int z = SZ; z <= SZ + RZ; z++)
                for (int yy = floor() + 1; yy <= floor() + 3; yy++) level.setBlock(p.set(x, yy, z), air, Block.UPDATE_CLIENTS);
        // the shaft: ladders on its north wall from the room's floor up to under the hatch
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int yy = floor() + 1; yy < top(); yy++) level.setBlock(p.set(SX, yy, SZ), ladder, Block.UPDATE_CLIENTS);
        hatch(false);
    }

    /** the trapdoor shut at the shaft's top, flush with the ground: oak, or NTM's steel one */
    private void hatch(boolean steel) {
        Block b = steel ? BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "trapdoor_steel")) : Blocks.OAK_TRAPDOOR;
        if (!(b instanceof TrapDoorBlock)) b = Blocks.OAK_TRAPDOOR;
        level.setBlock(new BlockPos(SX, top(), SZ), b.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.SOUTH).setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false), Block.UPDATE_ALL);
    }

    private void start(int r) {
        round = r;
        ticks = downs = ownBlast = 0;
        belowAt = -1;
        belowBy = "-";
        bursts.clear();
        nades.clear();
        for (int i = 0; i < SQUAD; i++) wasDown[i] = false;
        boolean up = r >= ROUNDS;
        // built again: the grenade down the shaft before broke its foot's rungs
        if (r > 0) build();
        // (up: off, else the one on the ground drops its grenade down on the squad: the ladder alone is counted)
        AiHatch.on = hatchOn && !up && r % 2 == 1;
        AiHatch.forget(level);
        hatch(up ? r % 2 == 1 : r / 2 % 2 == 1);
        frags0 = AiHatch.frags;
        begun0 = AiHatch.begun;
        deep0 = AiHatch.deepNoFrag;
        down0 = AiHatch.down;
        noFrag0 = AiHatch.noFrag;
        unsafe0 = AiHatch.unsafe;
        rim0 = AiHatch.notAtRim;
        double fy = floor() + 1;
        Vec3 foe = up ? new Vec3(SX + 6.5, y, SZ + 8.5) : new Vec3(SX + RX - 0.5, fy, SZ + RZ - 0.5);
        if (up) {
            // a still target on the ground (no AI of its own: it does not come down to them)
            below = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            below.setNoAi(true);
            below.addTag("armsai_target");
            below.setInvulnerable(true);
            below.setPos(foe.x, foe.y, foe.z);
            level.addFreshEntity(below);
        } else {
            below = AiSpawn.spawn(level, foe, AiSpawn.Kind.DUMMY, 1, null).get(0);
            // it holds its corner (it does not walk: up the ladder it came to the squad, and the drill was not one)
            var speed = below.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        below.setPos(foe.x, foe.y, foe.z);
        AiTeams.join(level, below, "hatch_in" + r);
        squad.clear();
        for (int i = 0; i < SQUAD; i++) {
            Vec3 at = up ? new Vec3(SX - RX + 0.5 + i, fy, SZ + RZ - 0.5) : new Vec3(SX + 12.5, y, SZ + i * 2 - 1.5);
            Mob m = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, null).get(0);
            m.setPos(at.x, at.y, at.z);
            AiTeams.join(level, m, "hatch_out" + r);
            AiBot b = AiBots.get(m);
            if (b != null) b.grenades = Math.max(b.grenades, 1);
            squad.add(m);
            health[i] = m.getHealth();
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m), ob = AiBots.get(below);
            if (b != null) b.memory.seen(below.getId(), below.getX(), below.getY(), below.getZ(), now, 0);
            if (ob != null) ob.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
        }
    }

    private void end() {
        int left = (int) squad.stream().filter(Mob::isAlive).count();
        boolean steel = round >= ROUNDS ? round % 2 == 1 : round / 2 % 2 == 1;
        boolean blast = belowAt >= 0 && !belowBy.contains("bullet");
        results.add(new int[] {round >= ROUNDS ? 2 : AiHatch.on ? 1 : 0, steel ? 1 : 0, left, downs, belowAt, blast ? 1 : 0, AiHatch.frags - frags0, ownBlast});
        check.out(String.format(Locale.ROOT, "     hatch, round %d (%s, %s): %d ticks; of the squad %d of %d left, %d got " + (round >= ROUNDS ? "up" : "down") + "; the one below %s; ways down begun %d, grenades down %d (too deep %d, none %d, waited for its own %d, off the rim %d), down the ladder %d; bursts %s; the squad hurt by a blast %d",
                round + 1, AiHatch.on ? "on" : "off", steel ? "steel" : "oak", ticks, left, SQUAD, downs, belowAt < 0 ? "up" : "down at " + belowAt + " by " + belowBy, AiHatch.begun - begun0,
                AiHatch.frags - frags0, AiHatch.deepNoFrag - deep0, AiHatch.noFrag - noFrag0, AiHatch.unsafe - unsafe0, AiHatch.notAtRim - rim0, AiHatch.down - down0, bursts, ownBlast));
        for (Mob m : squad) m.discard();
        below.discard();
        for (var e : level.getEntitiesOfClass(Entity.class, new AABB(X0 - 8, floor() - 2, Z0 - 24, X0 + 40, y + 30, Z0 + 24),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
    }

    private boolean finish() {
        AiHatch.on = hatchOn;
        int[] off = new int[4], on = new int[4];
        int upOak = 0, upSteel = 0;
        for (int[] r : results)
            if (r[0] == 2) {
                if (r[1] == 1) upSteel += r[3];
                else upOak += r[3];
            }
        for (int[] r : results) {
            if (r[0] == 2) continue;
            int[] s = r[0] == 1 ? on : off;
            s[0] += r[3];
            s[1] += r[4] >= 0 ? 1 : 0;
            s[2] += r[6];
            s[3]++;
        }
        // rounds on with one of the squad down, by the hatch, or the one below down by the grenade dropped through it
        // (a round is over once the one below is down)
        int steelDown = 0, oakDown = 0, hurt = 0;
        for (int[] r : results)
            if (r[0] == 1) {
                boolean through = r[3] > 0 || r[5] == 1 && r[6] > 0;
                if (through && r[1] == 1) steelDown++;
                if (through && r[1] == 0) oakDown++;
                hurt += r[7];
            }
        check.out(String.format(Locale.ROOT, "     hatch: off: got down %d, the one below down %d of %d rounds; on: got down %d (oak %d, steel %d), the one below down %d of %d rounds, grenades down %d",
                off[0], off[1], off[3], on[0], oakDown, steelDown, on[1], on[3], on[2]));
        if (hatchOn) {
            check.check(String.format(Locale.ROOT, "hatch: the squad goes down the shaft through an oak and a steel hatch (rounds: oak %d, steel %d of %d each)", oakDown, steelDown, on[3] / 2),
                    oakDown == on[3] / 2 && steelDown == on[3] / 2);
            check.check(String.format(Locale.ROOT, "hatch: a grenade down the shaft first (%d in %d rounds)", on[2], on[3]), on[2] * 2 >= on[3]);
            check.check("hatch: none of the squad hurt by its own grenade down the shaft (" + hurt + ")", hurt == 0);
            check.check(String.format(Locale.ROOT, "hatch: the squad comes up the shaft through an oak and a steel hatch (oak %d, steel %d of %d each)", upOak, upSteel, SQUAD),
                    upOak > 0 && upSteel > 0);
        }
        return true;
    }
}
