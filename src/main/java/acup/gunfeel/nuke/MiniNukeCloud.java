package acup.gunfeel.nuke;

import com.hbm.entity.ModEntities;
import com.hbm.entity.effect.EntityNukeTorex;
import com.hbm.packet.toclient.MukePayload;
import com.hbm.handler.threading.TargetPoint;
import com.hbm.packet.PacketWire;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.compat.Feature;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

// NTM's small nuclear blasts (the Fat Mine, the Fat Man's shells, the artillery mini nuke, the nuclear creeper and the
// rest: every MukePayload NTM sends) drawn with the cloud of NTM's bombs (EntityNukeTorex) instead of the muke
// particles, sized to the blast the way NTM sizes a bomb's cloud to its yield (scale = radius / 100). The cloud is
// NTM's synced entity, so a client needs nothing new. Its palettes MINI and MINI_BALE colour it as NTM's bomb and
// balefire bomb do and mark it small for the client: no bomb sound, no screen flash, no shake, a shorter shock ring
// (MixinMiniNukeTorex). Server key miniNukeCloud (true); false or the mixin not applied: NTM's muke particles.
public final class MiniNukeCloud {
    // torex types of the mod's own, the colours of NTM's types 0 (a bomb) and 1 (the balefire bomb)
    public static final byte MINI = 64, MINI_BALE = 65;
    // NTM's EntityNukeTorex.statFac: scale = yield * 0.01
    static final float SCALE_PER_BLOCK = 0.01F;
    // a muke without a known blast nearby: the Fat Mine's and the standard Fat Man shell's radius
    static final float DEFAULT_SIZE = 10F;
    // the Fat Man's Tiny Tot volley (eight small blasts at once): one cloud the size of a standard shell's
    static final float TINY_VOLLEY_SIZE = 10F;
    // a tiny blast this close in blocks and ticks to the volley's first cloud joins it
    static final double MERGE_RANGE = 16;
    static final long MERGE_TICKS = 2;
    // a blast's radius (ExplosionVNT, NTM's MK5, ExplosionNukeSmall's params) counts for a muke this close, same tick
    static final double HINT_RANGE = 2;

    // the level held weakly: a world left to the menu is not kept by its last blast
    private record Mark(java.lang.ref.WeakReference<Object> ref, double x, double y, double z, float size, long time) {
        Mark(Object level, double x, double y, double z, float size, long time) {
            this(new java.lang.ref.WeakReference<>(level), x, y, z, size, time);
        }

        boolean in(Object level) {
            return ref.get() == level;
        }
    }

    private static Mark hint, lastTiny;

    private MiniNukeCloud() {
    }

    // the stand's choice (MiniNukeStand: NTM's particles against the cloud), null: the server key
    public static volatile Boolean forced;

    public static boolean on() {
        Boolean f = forced;
        return Feature.MINI_NUKE_CLOUD.on() && (f != null ? f : ReaimTimes.flag("miniNukeCloud", true));
    }

    // only NTM's muke packet is taken over; every other packet goes out as NTM sends it
    public static boolean takes(Object payload) {
        return payload instanceof MukePayload;
    }

    public static float scale(float size) {
        return Math.max(0.01F, Math.min(5F, size * SCALE_PER_BLOCK));
    }

    // a blast's radius at a point, for the muke NTM may send there this tick (server thread only)
    public static void hint(Level level, double x, double y, double z, float size) {
        if (level instanceof ServerLevel server) mark(server, x, y, z, size, server.getGameTime());
    }

    static void mark(Object level, double x, double y, double z, float size, long time) {
        if (size > 0F) hint = new Mark(level, x, y, z, size, time);
    }

    static float sizeAt(Object level, double x, double y, double z, long time) {
        Mark h = hint;
        if (h == null || !h.in(level) || h.time != time) return DEFAULT_SIZE;
        double dx = h.x - x, dy = h.y - y, dz = h.z - z;
        return dx * dx + dy * dy + dz * dz <= HINT_RANGE * HINT_RANGE ? h.size : DEFAULT_SIZE;
    }

    // a tiny blast of a volley whose cloud is already there: true, and nothing to spawn
    static boolean joinsVolley(Object level, double x, double y, double z, long time) {
        Mark t = lastTiny;
        if (t != null && t.in(level) && time - t.time <= MERGE_TICKS && time >= t.time) {
            double dx = t.x - x, dz = t.z - z;
            if (dx * dx + dz * dz <= MERGE_RANGE * MERGE_RANGE) return true;
        }
        lastTiny = new Mark(level, x, y, z, TINY_VOLLEY_SIZE, time);
        return false;
    }

    // NTM's muke packet (MixinMiniNukeSend): the cloud instead, sized by the blast that sent it. False: the blast is
    // where the cloud would not be seen (cloudSeen), NTM's packet goes out as it is
    public static boolean fromMuke(ServerLevel level, double x, double y, double z, boolean tiny, boolean balefire) {
        if (!cloudSeen(level, x, z)) return false;
        long time = level.getGameTime();
        if (tiny) {
            if (!joinsVolley(level, x, y, z, time)) cloud(level, x, y, z, TINY_VOLLEY_SIZE, balefire);
        } else {
            cloud(level, x, y, z, sizeAt(level, x, y, z, time), balefire);
        }
        return true;
    }

    // the cloud of a small nuclear blast of radius size (the mod's own: the nuclear .50 calls this), or NTM's muke
    // particles where the cloud would not be seen
    public static void spawn(ServerLevel level, double x, double y, double z, float size, boolean balefire) {
        if (on() && cloudSeen(level, x, z)) {
            cloud(level, x, y, z, size, balefire);
            return;
        }
        passing.set(true);
        try {
            PacketWire.sendToAllAround(new MukePayload(x, y, z, false, balefire), new TargetPoint(level, x, y, z, 250));
        } finally {
            passing.set(false);
        }
    }

    // the mod's own muke (spawn's fallback): MixinMiniNukeSend lets it out
    public static boolean passing() {
        return passing.get();
    }

    private static final ThreadLocal<Boolean> passing = ThreadLocal.withInitial(() -> false);

    // NTM's cloud is an entity: past the players' simulation distance its chunk does not tick, the cloud is never sent
    // and nothing would be seen (NTM's bombs alike), where NTM's muke packet reaches 250 blocks. There the muke stays.
    static boolean cloudSeen(ServerLevel level, double x, double z) {
        return level.isPositionEntityTicking(BlockPos.containing(x, level.getMinY() + 1, z));
    }

    private static void cloud(ServerLevel level, double x, double y, double z, float size, boolean balefire) {
        EntityNukeTorex torex = new EntityNukeTorex(ModEntities.NUKE_TOREX.get(), level);
        torex.setScale(scale(size));
        torex.setTorexType(balefire ? MINI_BALE : MINI);
        torex.setPos(x, y, z);
        level.addFreshEntity(torex);
    }

    public static boolean mini(EntityNukeTorex torex) {
        byte type = torex.getTorexType();
        return type == MINI || type == MINI_BALE;
    }

    // the ticks a small cloud's shock ring grows (1.5 blocks a tick as a bomb's, NTM's bombs: 150 at any size): to
    // five times the blast's radius
    public static int shockTicks(double scale) {
        return (int) Math.max(8, Math.min(150, Math.round(scale / SCALE_PER_BLOCK * 5 / 1.5)));
    }

    // a shock ring puff's width and height at least, blocks: NTM's are the scale times 5 and 2, half a block at a Fat
    // Mine's: specks of smoke on the ground, no ring to see (a bomb's are 5 blocks and more)
    static final float SHOCK_WIDE = 2.5F, SHOCK_HIGH = 1F;

    public static float shockWidth(float ntm) {
        return Math.max(SHOCK_WIDE, ntm);
    }

    public static float shockHeight(float ntm) {
        return Math.max(SHOCK_HIGH, ntm);
    }

    // NTM's shock ring is laid down a tick at a time at 1.5 blocks a tick more, its puffs going out at the scale times
    // 0.25 a tick (still at a small one's) and living up to 400 ticks: under scale 1 the ring left behind fills its
    // disc, smoke all over the ground and no ring to see. A small cloud's puffs ride the ring out at its own pace and
    // live SHOCK_LIFE ticks: a band at its edge, the ground behind it clear
    static final double SHOCK_PACE = 1.5;
    static final int SHOCK_LIFE = 10;

    // the motion NTM's shock puff is given (its own speed: the scale times 0.25) for it to go out SHOCK_PACE a tick
    public static double shockMotion(double scale) {
        return SHOCK_PACE / Math.max(1e-3, scale * 0.25);
    }

    public static int shockLife() {
        return SHOCK_LIFE;
    }

    // the ground laid waste round a small blast out to this many times its radius (within it the crater is bare), and
    // the passes made (the stand)
    static final float WASTE_TIMES = 2F;
    public static int wastePasses;

    public static float wasteRadius(float blast) {
        return blast * WASTE_TIMES;
    }

    private static Mark lastWaste;

    // NTM's muke packet (MixinMiniNukeSend, before the blast or after it): the ground round the blast laid waste as a
    // nuke's is (MiniWasteMutator: grass, leaves and plants dead or gone, uranium ore to schrabidium; logs, planks,
    // doors and coal left), out to wasteRadius of the blast that sent it; a Tiny Tot volley once. One more pass of NTM's
    // rays, no harm and no sound. Server key miniNukeWaste (true)
    public static void waste(ServerLevel level, double x, double y, double z, boolean tiny) {
        if (!ReaimTimes.flag("miniNukeWaste", true)) return;
        long time = level.getGameTime();
        if (tiny) {
            Mark t = lastWaste;
            if (t != null && t.in(level) && time - t.time <= MERGE_TICKS && time >= t.time) {
                double dx = t.x - x, dz = t.z - z;
                if (dx * dx + dz * dz <= MERGE_RANGE * MERGE_RANGE) return;
            }
            lastWaste = new Mark(level, x, y, z, TINY_VOLLEY_SIZE, time);
        }
        float size = tiny ? TINY_VOLLEY_SIZE : sizeAt(level, x, y, z, time);
        // (the pass is an ExplosionVNT too: the blast's radius it would leave for the cloud's size kept as it was)
        Mark kept = hint;
        try {
            MiniWasteMutator.apply(new com.hbm.explosion.vanillant.ExplosionVNT(level, x, y, z, wasteRadius(size))).explode();
        } finally {
            hint = kept;
        }
        wastePasses++;
    }

    // NTM's bomb at scale 1: its heat at the start (EntityNukeTorex.simulateTick: (int) (50 * s * s))
    static final double BOMB_HEAT = 50;

    // a small cloud's heat at an age, the one its puffs' colour is drawn from (EntityNukeTorex.Cloudlet.updateColor:
    // the colour falls off with the distance squared over the heat). NTM cuts the heat to a whole number, 50 * s * s:
    // under scale 0.142 (a blast of radius 14) none at all, and the cloud is grey from its first tick. Here a bomb's
    // at scale 1, cooling the same way, by the scale squared as the puffs' distances go by the scale: the colours of
    // a bomb's cloud the size of this one
    public static double heat(double scale, int age, int maxAge) {
        double cooled = BOMB_HEAT - Math.pow(BOMB_HEAT * age / Math.max(1, maxAge), 0.6);
        return Math.max(1e-3, scale * scale * cooled);
    }

    // the shock ring's puffs a tick at most (NTM's bombs: 100), 60 at least: fewer and a ring is a spot
    static final int SHOCK_PUFFS = 60;

    public static int shockPuffs(double scale) {
        return (int) Math.max(SHOCK_PUFFS, Math.min(100, Math.round(100 * scale / 0.25)));
    }

    // client: the colours of NTM's types 0 and 1 (EntityNukeTorex.Cloudlet.updateColor) under the mod's two types
    public static void registerPalettes() {
        try {
            EntityNukeTorex.registerPalette(MINI, (c, col) -> {
                c.colorR = 0.1 + 2.4 * col;
                c.colorG = 0.075 + 1.225 * col;
                c.colorB = 0.05 + 0.35 * col;
            });
            EntityNukeTorex.registerPalette(MINI_BALE, (c, col) -> {
                c.colorR = 0.1 + 0.9 * col;
                c.colorG = 0.1 + 1.9 * col;
                c.colorB = 0.1 + 0.4 * col;
            });
        } catch (IllegalStateException e) {
            acup.gunfeel.DebugLog.LOGGER.warn("Small nuclear clouds keep NTM's colours: torex type {} or {} is taken ({})", MINI,
                    MINI_BALE, e.getMessage());
        }
    }
}
