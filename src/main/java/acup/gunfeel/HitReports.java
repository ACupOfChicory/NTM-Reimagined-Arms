package acup.gunfeel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client: this player's own bullets, as Tracers follows them, against the models it draws; what they hit and how far
 * their path is tested, to the server once a tick (HitReportPayload, ClientHits). Only while the server's clientHits is on.
 */
public final class HitReports {
    private HitReports() {
    }

    private static final class Followed {
        float tested = -1;
        boolean dirty;
        final List<HitReportPayload.Claim> claims = new ArrayList<>();
    }

    private static final Map<Integer, Followed> FOLLOWED = new LinkedHashMap<>();
    /** the stand: each hit as it was reported, by the bullet */
    static final List<int[]> STAND_LOG = new ArrayList<>();
    static boolean standLog;

    /** a shot announced to this player: is it theirs, to follow and report */
    static boolean follow(ShotPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || p.owner() != mc.player.getId() || p.id() < 0 || !ClientHits.on()) return false;
        FOLLOWED.put(p.id(), new Followed());
        if (FOLLOWED.size() > 1024) FOLLOWED.remove(FOLLOWED.keySet().iterator().next());
        return true;
    }

    static boolean followed(int id) {
        return FOLLOWED.containsKey(id);
    }

    /** this player's shot not followed here (no tracers, NTM draws the round): the server decides its hits */
    public static void untracked(ShotPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || p.owner() != mc.player.getId() || p.id() < 0 || !ClientHits.on()) return;
        Followed f = new Followed();
        f.tested = Float.NaN;
        f.dirty = true;
        FOLLOWED.put(p.id(), f);
    }

    /** a mob the path met: its distance along the path, the point, the part */
    record Met(Entity entity, double distance, Vec3 point, byte zone) {
    }

    /**
     * what the path from {@code a} to {@code b} meets, nearest first: the drawn models of mobs and players; the shooter
     * too once out of the gun's guard ({@code ownerToo}); {@code first}: the path's first step, from the shooter's eyes
     */
    static List<Met> meet(ClientLevel level, int owner, boolean ownerToo, boolean first, Vec3 a, Vec3 b) {
        AABB area = new AABB(a, b).inflate(1.0);
        java.util.function.Predicate<Entity> filter = e -> e.isPickable() && e.isAlive() && (ownerToo || e.getId() != owner)
                && !(e instanceof net.minecraft.world.entity.projectile.Projectile);
        List<Met> out = new ArrayList<>();
        for (Entity e : ModelHits.candidates(level, null, level.getEntities((Entity) null, area, filter), area, filter)) {
            // mobs and players (their boxes are the models'); the rest the server's NTM test decides
            if (!(e.getBoundingBox() instanceof ModelAABB box)) continue;
            AABB wide = box.inflate(0.3);
            // inside from the start: only at the muzzle (later steps begin where the last one ended)
            var hit = first && wide.contains(a) ? java.util.Optional.of(a) : wide.clip(a, b);
            if (hit.isEmpty()) continue;
            Vec3 p = hit.get();
            out.add(new Met(e, p.distanceTo(a), p, zone(e, p)));
        }
        out.sort(Comparator.comparingDouble(Met::distance));
        return out;
    }

    /** the part at {@code p} as the server's HeadDamage would take it from this model */
    static byte zone(Entity e, Vec3 p) {
        var head = ModelHits.head(e, p);
        if (!head.applied()) return HitReportPayload.BODY;
        if ((!head.head() || head.source().equals("height")) && ModelHits.limb(e, p)) return HitReportPayload.LIMB;
        return head.head() ? HitReportPayload.HEAD : HitReportPayload.BODY;
    }

    static void hit(int bullet, Met m, double age) {
        Followed f = FOLLOWED.get(bullet);
        if (f == null) return;
        f.claims.add(new HitReportPayload.Claim(m.entity().getId(), (float) age, m.point(), m.zone()));
        f.dirty = true;
        if (standLog) STAND_LOG.add(new int[] { bullet, m.entity().getId(), m.zone() });
    }

    /** the bullet's path is tested up to {@code age} ticks from its start (+∞: it is done) */
    static void tested(int bullet, double age) {
        Followed f = FOLLOWED.get(bullet);
        if (f == null || Float.isNaN(f.tested) || age <= f.tested) return;
        f.tested = (float) age;
        f.dirty = true;
    }

    /** once a tick: what changed, to the server; the bullets done are let go */
    public static void tick() {
        if (FOLLOWED.isEmpty()) return;
        List<HitReportPayload.Entry> out = new ArrayList<>();
        var it = FOLLOWED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Followed f = e.getValue();
            if (!f.dirty) continue;
            if (out.size() >= HitReportPayload.MAX_ENTRIES) break;
            List<HitReportPayload.Claim> claims = f.claims.size() <= HitReportPayload.MAX_CLAIMS ? List.copyOf(f.claims)
                    : List.copyOf(f.claims.subList(0, HitReportPayload.MAX_CLAIMS));
            out.add(new HitReportPayload.Entry(e.getKey(), f.tested, claims));
            f.claims.clear();
            f.dirty = false;
            if (Float.isNaN(f.tested) || f.tested == Float.POSITIVE_INFINITY) it.remove();
        }
        if (!out.isEmpty() && Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new HitReportPayload(out));
    }

    public static void clear() {
        FOLLOWED.clear();
    }
}
