package acup.gunfeel.gas;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.entity.effect.EntityMist;
import com.hbm.entity.projectile.EntityChemical;
import com.hbm.handler.radiation.RadiationSystemNT;
import com.hbm.inventory.fluid.NTMFluidProperties;
import com.hbm.inventory.fluid.NTMFluidProperty;
import com.hbm.inventory.fluid.trait.FT_VentRadiation;

import acup.gunfeel.host.Hook;
import acup.gunfeel.host.HookPoint;
import acup.gunfeel.compat.Feature;
import acup.gunfeel.mixin.MistAccess;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * NTM's gas weapons let this mod's clouds out, on the server (system {@code ntm_gas}; with smoke off, as NTM had it):
 *
 * <ul>
 * <li>the phosgene creeper's blast: a phosgene cloud ({@link GasKind#PHOSGENE}, doses and the gas mask) the size of
 * NTM's mist of it (10 by 5 blocks), {@link #CREEPER_SCALE} of a grenade, in place of the mist;
 * <li>the chemical thrower: where a gas blob of its stream ends (3 s on, or in a wall) its gas is left as a cloud,
 * half a grenade a second of stream ({@link #PER_BLOB} a blob, a blob a tick). Chlorine, phosgene and mustard are this
 * mod's gases; steam, oxygen, UF6, PuF6 and waste gas are {@link NtmGas} clouds that do what NTM's mist of the fluid
 * does ({@link #affect}). The blobs of one stream are gathered {@link #GATHER} ticks and go out together, the first as
 * a cloud and the next ones feeding it while it takes feeds and they land near it: at most two events a second a
 * stream, and a full tank (50 s) makes about a dozen clouds. A stream's cloud leaves its own thrower alone until
 * {@link ChemSpare#SPARE} ticks after the last of their gas is out of it, as NTM's stream does, and then hurts them as
 * it does anyone.
 * </ul>
 *
 * The stream itself (its damage and look) is NTM's.
 */
public final class ChemClouds {
    /** The phosgene creeper's cloud: NTM's mist is 10 by 10 by 5 blocks; the gas sinks into a sheet half as high. */
    public static final float CREEPER_SCALE = 1.25F;
    /** A grenade's worth of gas a blob: half a grenade a second at one blob a tick (NTM: 3 mB a tick). */
    public static final float PER_BLOB = 0.5F / 20F;
    /** Ticks a stream's blobs are gathered before they go out as one. */
    public static final int GATHER = 10;
    /** A stream nothing came from for this long is forgotten (its cloud is not fed any more). */
    private static final int FORGET = 200;
    /** Least density at a head that counts. */
    public static final float LEAST = 0.02F;
    /** Ticks between two of a radioactive cloud's contaminations of the ground (NTM's mist: every tick). */
    private static final int RAD_EVERY = 20;

    private record Key(int thrower, SmokeKind kind) {
    }

    private static final class Stream {
        long first = -1, last;
        int blobs;
        double x, y, z, dx, dy, dz;
        Vec3 lastAt = Vec3.ZERO;
        int cloud = -1;
    }

    // all on the server's thread; the values hold their level, so a level's are dropped on its unload (forget), not by
    // the weak keys
    private static final Map<ServerLevel, Map<Key, Stream>> STREAMS = new WeakHashMap<>();
    // what each one has taken of a cloud's NTM rule, by gas: a whole one is one tick of NTM's mist
    private static final Map<ServerLevel, Map<LivingEntity, float[]>> TAKEN = new WeakHashMap<>();
    private static final Map<ServerLevel, EntityMist[]> MISTS = new WeakHashMap<>();
    private static final Map<ServerLevel, ChemSpare> SPARES = new WeakHashMap<>();

    private ChemClouds() {
    }

    private static boolean on() {
        return Feature.NTM_GAS.on() && SmokeClouds.enabled();
    }

    /**
     * The phosgene creeper went off at {@code at}: its cloud is let out. False (nothing let out, NTM's mist goes as it
     * was) with smoke off or the spot shut in.
     */
    public static boolean creeper(ServerLevel level, Vec3 at) {
        if (!on()) return false;
        return SmokeClouds.spawn(level, at.add(0, 0.5, 0), new Vec3(0, -1, 0), GasKind.PHOSGENE.smoke(), CREEPER_SCALE) >= 0;
    }

    /** The cloud kind a fluid's gas blob leaves, or null (not a gas this mod makes clouds of). */
    public static SmokeKind kindOf(Fluid fluid) {
        if (fluid == null || EntityChemical.getStyleFromType(fluid) != EntityChemical.ChemicalStyle.GAS) return null;
        Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
        if (!"hbm".equals(id.getNamespace())) return null;
        return switch (id.getPath()) {
            case "chlorine" -> GasKind.CHLORINE.smoke();
            case "phosgene" -> GasKind.PHOSGENE.smoke();
            case "mustardgas" -> GasKind.MUSTARD.smoke();
            default -> {
                NtmGas g = NtmGas.byFluid(id.getPath());
                yield g == null ? null : g.smoke();
            }
        };
    }

    /** A chemical thrower's blob is about to go (its age is up, or it hit a block at {@code hit}, null for its own place). */
    public static void blobEnded(EntityChemical chem, Vec3 hit) {
        if (chem.isRemoved() || !(chem.level() instanceof ServerLevel level) || !on()) return;
        SmokeKind kind = kindOf(chem.getChemType());
        if (kind == null) return;
        LivingEntity thrower = chem.getThrower();
        Key key = new Key(thrower != null ? thrower.getId() : -1, kind);
        Stream s = STREAMS.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(key, k -> new Stream());
        long now = level.getGameTime();
        if (s.blobs == 0) s.first = now;
        s.last = now;
        s.blobs++;
        Vec3 at = hit != null ? hit : chem.position();
        s.lastAt = at;
        Vec3 v = chem.getDeltaMovement();
        s.x += at.x;
        s.y += at.y;
        s.z += at.z;
        s.dx += v.x;
        s.dy += v.y;
        s.dz += v.z;
    }

    /** Once a level tick: the gathered blobs go out, and the NTM gases' clouds do their work. */
    @Hook(HookPoint.GAS_SERVER_TICK)
    public static void tick(ServerLevel level) {
        Map<Key, Stream> streams = STREAMS.get(level);
        long now = level.getGameTime();
        ChemSpare spare = SPARES.get(level);
        if (spare != null) spare.expire(now);
        if (streams != null && !streams.isEmpty()) {
            for (Iterator<Map.Entry<Key, Stream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Key, Stream> e = it.next();
                Stream s = e.getValue();
                if (s.blobs > 0 && now - s.first >= GATHER) let(level, e.getKey(), s);
                else if (s.blobs == 0 && now - s.last > FORGET) it.remove();
            }
        }
        affect(level);
    }

    // a stream's gathered blobs go out where they ended on average: feeding its cloud, or as a new one
    private static void let(ServerLevel level, Key key, Stream s) {
        SmokeKind kind = key.kind();
        int n = s.blobs;
        Vec3 at = new Vec3(s.x / n, s.y / n, s.z / n), dir = new Vec3(s.dx, s.dy, s.dz);
        float scale = n * PER_BLOB;
        s.blobs = 0;
        s.x = s.y = s.z = s.dx = s.dy = s.dz = 0;
        if (!(s.cloud >= 0 && SmokeClouds.feed(level, s.cloud, at, dir, scale))) {
            s.cloud = SmokeClouds.spawn(level, at, dir, kind, scale);
            // the middle of blobs that ended in two places can be in a wall: then where the last one ended
            if (s.cloud < 0) s.cloud = SmokeClouds.spawn(level, s.lastAt, dir, kind, scale);
        }
        // the gas comes out STIR_AHEAD ticks on, over the kind's release
        if (s.cloud >= 0 && key.thrower() >= 0)
            SPARES.computeIfAbsent(level, l -> new ChemSpare()).let(s.cloud, key.thrower(), level.getGameTime(), SmokeClouds.STIR_AHEAD + kind.release());
    }

    /** The entity a cloud spares now (its own thrower, for a while: {@link ChemSpare}), or -1. */
    public static int spares(ServerLevel level, SmokeClouds.Cloud c) {
        ChemSpare s = SPARES.get(level);
        return s == null ? -1 : s.spares(c.id, level.getGameTime());
    }

    /** Forgets a level's streams and doses (it unloads). */
    public static void forget(ServerLevel level) {
        STREAMS.remove(level);
        TAKEN.remove(level);
        MISTS.remove(level);
        SPARES.remove(level);
    }

    /**
     * The NTM gases' clouds on the living: NTM's mist of the fluid, one of its ticks for every whole of the density at
     * the head taken over the ticks (a cloud at density 1 is the mist itself; at 0.25, one of its ticks in four), and the
     * radioactive ones contaminate the ground under their middle as the mist does.
     */
    static void affect(ServerLevel level) {
        SmokeClouds all = SmokeClouds.get(level);
        Map<LivingEntity, float[]> was = TAKEN.get(level);
        if (all == null || all.isEmpty()) {
            if (was != null) TAKEN.remove(level);
            return;
        }
        // made at the first one in an NTM gas: a level with only smoke and this mod's gases makes nothing a tick
        Map<LivingEntity, float[]> taken = null;
        long time = level.getGameTime();
        for (SmokeClouds.Cloud c : all.clouds()) {
            NtmGas gas = NtmGas.of(c.kind());
            if (gas == null || !c.grid.occupied()) continue;
            NTMFluidProperty prop = property(gas);
            if (prop == null) continue;
            EntityMist mist = mist(level, gas);
            if (mist == null) continue;
            int[] b = c.grid.box();
            if (time % RAD_EVERY == 0 && prop.hasTrait(FT_VentRadiation.class)) {
                FT_VentRadiation rad = (FT_VentRadiation) prop.getTrait(FT_VentRadiation.class);
                float share = Math.min(1F, c.grid.total() / (gas.smoke().fill() * NtmGas.VOLUME * Math.max(0.05F, c.scale)));
                BlockPos mid = new BlockPos(c.baseX() + (b[0] + b[3]) / 2, c.baseY() + b[1], c.baseZ() + (b[2] + b[5]) / 2);
                RadiationSystemNT.incrementRad(level, mid, rad.getRadPerMB() * 2F * RAD_EVERY * share, 5120.0);
            }
            AABB box = new AABB(c.baseX() + b[0], c.baseY() + b[1], c.baseZ() + b[2], c.baseX() + b[3] + 1, c.baseY() + b[4] + 1, c.baseZ() + b[5] + 1);
            int spared = spares(level, c);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
                if (e.getId() == spared) continue;
                BlockPos head = BlockPos.containing(e.getEyePosition());
                float v = Math.min(1F, c.cell(head.getX(), head.getY(), head.getZ()));
                if (v < LEAST) continue;
                if (taken == null) taken = new IdentityHashMap<>();
                float[] t = taken.computeIfAbsent(e, k -> {
                    float[] before = was != null ? was.get(k) : null;
                    return before != null ? before : new float[NtmGas.values().length];
                });
                t[gas.ordinal()] += v;
                if (t[gas.ordinal()] < 1F) continue;
                t[gas.ordinal()] -= 1F;
                mist.setPos(e.getX(), e.getY(), e.getZ());
                ((MistAccess) mist).gunfeel$affect(level, e, prop, v);
            }
        }
        if (taken != null) TAKEN.put(level, taken);
        else if (was != null) TAKEN.remove(level);
    }

    private static NTMFluidProperty property(NtmGas gas) {
        Fluid f = fluid(gas);
        return f == null ? null : NTMFluidProperties.get(f);
    }

    private static Fluid fluid(NtmGas gas) {
        return BuiltInRegistries.FLUID.getOptional(Identifier.fromNamespaceAndPath("hbm", gas.fluid)).orElse(null);
    }

    // one of NTM's mists of each gas a level, never in the world: what its tick does to one in it is the rule
    private static EntityMist mist(ServerLevel level, NtmGas gas) {
        EntityMist[] m = MISTS.computeIfAbsent(level, l -> new EntityMist[NtmGas.values().length]);
        if (m[gas.ordinal()] == null) {
            Fluid f = fluid(gas);
            if (f == null) return null;
            m[gas.ordinal()] = new EntityMist(level).setType(f);
        }
        return m[gas.ordinal()];
    }

    /** For the stand: how many streams gather now. */
    public static int streams(ServerLevel level) {
        Map<Key, Stream> s = STREAMS.get(level);
        return s == null ? 0 : s.size();
    }
}
