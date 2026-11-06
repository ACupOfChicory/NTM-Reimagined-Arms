package acup.gunfeel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Port stand (-PcheckOutlineDrift, with -PcheckMobs; notes/ports/de-1.21.1.md, «F3+B на ходу»): after MobSmoke's
// stage 4 the server walks the target along -x, turns it and holds it. Every frame the F3+B outline drawn for it is set
// against the model that frame drew (the pose publishChecked took from it), box for box, and every cube's edges against
// the head zones (green on a head cube where its planes keep it, orange elsewhere, no orange under the green, no green
// off the head's cubes). The rule is 26.2's: the pose and the head are exact, the orange contour of the limbs may show
// an older model (its new contour is built in the background): the last frame's, or one younger than ModelContours.LAG
// (-PcontourLag, 1/60 s). An older one fails the stand. Every server tick the zone a player's shot meets (ModelHits.clip in the player's shot
// context, as NTM's bullets take it) is set against the model on the last frame the player saw before it, by the
// front-most point a row of rays along the motion meets on each.
final class OutlineDrift {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.checkOutlineDrift");
    static final int WALK = 30, TURN = 20, STAND = 20;
    static final double STEP = .15;
    static final float SPIN = 9;
    private static final Vec3 MOTION = new Vec3(-1, 0, 0);
    private static final double START_X = 10.75;

    private static volatile int target = -1, tick = -1;
    private static volatile boolean running, finished, listening;
    private static volatile List<Vec3[]> rays = List.of();
    // the client's frame: what it drew and the model it drew
    private static List<ModelBox> truth, drawnBoxes;
    // the models the frames before drew, oldest first, each with its frame's ModelContours.callAt (the clock the contour's
    // age is kept by): a limb's orange may show the last one's or one younger than ModelContours.LAG (the contour of a
    // new pose is built in the background), not older
    private record Past(long nanos, List<ModelBox> boxes) {}
    private static final java.util.ArrayDeque<Past> PAST = new java.util.ArrayDeque<>();
    private static long drawnAt;
    private static long orangePoints, orangeNow, orangeLast, orangeLate;
    private static ModelHitDebug.Colored drawnColors;
    private static List<ModelHits.StandPart> drawnParts;
    private static List<HeadZone> drawnZones;
    private record Frame(long nanos, double front) {}
    private static final List<Frame> FRAMES = new ArrayList<>();
    private record Sample(long nanos, int tick, double front, boolean pose) {}
    private static final ConcurrentLinkedQueue<Sample> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final class Drift { int frames, noTruth, mismatch; double sum, max, along; }
    private static final Map<String, Drift> DRIFT = new LinkedHashMap<>();
    private static int colorFrames;
    // the time a frame spends on drawing F3+B (ModelHitDebug) and on this check, for the log
    static long drawNanos, checkNanos;
    private static long headPoints, headOrange, headMissing, bodyPoints, bodyGreen, shared, greenPoints, greenOff, meshGreen;

    private OutlineDrift() {}

    // the server ticks where the client shows the phase: it follows the server by its lerp, 3 ticks for a mob
    static String phase(int t) {
        if (t >= 5 && t < WALK) return "ход";
        if (t >= WALK + 5 && t < WALK + TURN) return "поворот";
        if (t >= WALK + TURN + 8 && t < WALK + TURN + STAND) return "на месте";
        return null;
    }

    static boolean finished() { return finished; }

    // client: after stage 4, the target on the server
    static void start(int id) {
        target = id; tick = -1; running = finished = false;
        truth = drawnBoxes = null; PAST.clear(); drawnColors = null; drawnParts = null; drawnZones = null;
        orangePoints = orangeNow = orangeLast = orangeLate = 0;
        FRAMES.clear(); SAMPLES.clear(); DRIFT.clear(); drawNanos = checkNanos = 0;
        colorFrames = 0; headPoints = headOrange = headMissing = bodyPoints = bodyGreen = shared = greenPoints = greenOff = meshGreen = 0;
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (!listening) {
            listening = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre.class,
                    e -> serverTick(e.getServer()));
        }
        server.execute(() -> {
            if (!(server.overworld().getEntity(id) instanceof LivingEntity mob)) { finished = true; return; }
            mob.setPos(START_X, mob.getY(), mob.getZ());
            turn(mob, 90);
            var out = new ArrayList<Vec3[]>();
            for (int i = 1; i <= 9; i++) {
                double y = mob.getY() + mob.getBbHeight() * i / 10;
                out.add(new Vec3[] {new Vec3(1.5, y, mob.getZ()), new Vec3(15.5, y, mob.getZ())});
            }
            rays = List.copyOf(out);
            tick = 0; running = true;
        });
    }

    static void stop() { running = false; finished = true; }

    private static void turn(LivingEntity mob, float yaw) {
        mob.setYRot(yaw); mob.setYBodyRot(yaw); mob.setYHeadRot(yaw);
    }

    private static void serverTick(net.minecraft.server.MinecraftServer server) {
        if (!running) return;
        var level = server.overworld();
        if (!(level.getEntity(target) instanceof LivingEntity mob)) { stop(); return; }
        int t = tick;
        if (phase(t) != null) {
            var player = server.getPlayerList().getPlayers().getFirst();
            var context = ModelHits.beginShot(player);
            double front = Double.POSITIVE_INFINITY;
            // the shot's time: the last frame the player saw before it
            long shot = System.nanoTime();
            try {
                for (var ray : rays) {
                    var hit = ModelHits.clip(mob, ray[0], ray[1], mob.getBoundingBox());
                    if (hit.isPresent()) front = Math.min(front, hit.get().x);
                }
            } finally { ModelHits.endShot(context); }
            SAMPLES.add(new Sample(shot, t, front, ModelHits.hasPose(mob)));
        }
        // the shot above meets the mob as the tick found it; it moves for the next one
        if (t < WALK) mob.setPos(mob.getX() - STEP, mob.getY(), mob.getZ());
        else if (t < WALK + TURN) turn(mob, 90 + SPIN * (t - WALK + 1));
        tick = t + 1;
        if (tick >= WALK + TURN + STAND) stop();
    }

    // client, ModelHitClient.publishChecked through MobSmoke.captured: the model this frame drew, relative to the entity
    static void captured(LivingEntity entity, List<ModelBox> rendered) {
        if (!running || entity.getId() != target) return;
        Vec3 at = entity.position();
        truth = rendered.stream().map(b -> new ModelBox(b.part(), b.x() + at.x, b.y() + at.y, b.z() + at.z,
                b.ux(), b.uy(), b.uz(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz(), b.mesh())).toList();
    }

    // client, ModelHitDebug: the outline drawn for it this frame
    static void drawn(Entity entity, List<ModelBox> boxes, ModelHitDebug.Colored colored) {
        if (!running || entity.getId() != target) return;
        drawnBoxes = List.copyOf(boxes); drawnColors = colored; drawnAt = ModelContours.callAt();
        drawnParts = ModelHits.standParts(entity); drawnZones = ModelHits.headZones(entity);
    }

    // client, AFTER_LEVEL after ModelHitClient.finishFrame: this frame's poses are all published
    static void endFrame() {
        if (!running) { truth = drawnBoxes = null; PAST.clear(); return; }
        String phase = phase(tick);
        var seen = truth;
        if (phase != null && drawnBoxes != null) {
            var d = DRIFT.computeIfAbsent(phase, k -> new Drift());
            if (truth == null) d.noTruth++;
            else if (truth.size() != drawnBoxes.size()) d.mismatch++;
            else {
                double worst = 0, along = 0;
                for (int i = 0; i < truth.size(); i++) {
                    worst = Math.max(worst, offset(drawnBoxes.get(i), truth.get(i)));
                    along += origin(drawnBoxes.get(i)).subtract(origin(truth.get(i))).dot(MOTION);
                }
                d.frames++; d.sum += worst; d.max = Math.max(d.max, worst); d.along += along / truth.size();
            }
            long c0 = System.nanoTime(); colors(drawnParts, drawnZones, drawnColors); late(drawnColors); checkNanos += System.nanoTime() - c0;
        }
        // stamped once the frame is done: a shot after this saw it
        if (seen != null) FRAMES.add(new Frame(System.nanoTime(), front(seen)));
        if (seen != null) {
            PAST.addLast(new Past(drawnAt != 0 ? drawnAt : System.nanoTime(), seen));
            while (PAST.size() > 1 && System.nanoTime() - PAST.getFirst().nanos() > 2 * ModelContours.LAG) PAST.removeFirst();
        }
        drawnAt = 0;
        truth = drawnBoxes = null; drawnColors = null; drawnParts = null; drawnZones = null;
    }

    private static Vec3 origin(ModelBox b) { return new Vec3(b.x(), b.y(), b.z()); }

    // the orange in the body's frame (ModelContours.Frame of the first part, the frame ModelHitDebug draws it in) against
    // the edges of this frame's model and the earlier ones allowed (the last frame's, and those younger than LAG), each
    // in its own body frame: on this one's, on an allowed earlier one's only, or on none (later)
    private static void late(ModelHitDebug.Colored colored) {
        if (colored == null || truth == null || truth.isEmpty() || drawnBoxes.isEmpty()) return;
        var frame = ModelContours.Frame.of(drawnBoxes.getFirst());
        var now = bodyEdges(truth);
        var earlier = new ArrayList<ModelHitDebug.Segments>();
        boolean last = true;
        for (var it = PAST.descendingIterator(); it.hasNext(); last = false) {
            var past = it.next();
            // the last frame's within a quarter second, as ModelContours.good keeps it
            if (drawnAt - past.nanos() > (last ? 250_000_000L : ModelContours.LAG)) break;
            if (past.boxes().size() == truth.size()) earlier.add(bodyEdges(past.boxes()));
        }
        for (var segment : colored.orange())
            for (double f : new double[] {.25, .5, .75}) {
                Vec3 p = frame.local(segment[0].lerp(segment[1], f));
                orangePoints++;
                if (on(p, now)) orangeNow++;
                else if (earlier.stream().anyMatch(e -> on(p, e))) orangeLast++;
                else orangeLate++;
            }
    }

    private static ModelHitDebug.Segments bodyEdges(List<ModelBox> boxes) {
        var frame = ModelContours.Frame.of(boxes.getFirst());
        var out = new ArrayList<Vec3[]>();
        for (var box : boxes) for (var edge : edges(box)) out.add(new Vec3[] {frame.local(edge[0]), frame.local(edge[1])});
        return new ModelHitDebug.Segments(out);
    }

    // within UNDER of one of the segments
    private static boolean on(Vec3 p, ModelHitDebug.Segments segments) {
        for (var s : segments.near(p, p)) {
            Vec3 d = s[1].subtract(s[0]);
            double length = d.lengthSqr();
            double t = length < 1e-18 ? 0 : Math.max(0, Math.min(1, p.subtract(s[0]).dot(d) / length));
            if (s[0].add(d.scale(t)).distanceToSqr(p) < ModelHitDebug.UNDER * ModelHitDebug.UNDER) return true;
        }
        return false;
    }

    // the farthest of a box's corners from the other box's same corner
    static double offset(ModelBox a, ModelBox b) {
        double worst = 0;
        for (int corner = 0; corner < 8; corner++) {
            double i = corner & 1, j = corner >> 1 & 1, k = corner >> 2 & 1;
            worst = Math.max(worst, ModelContours.point(a, i, j, k).distanceTo(ModelContours.point(b, i, j, k)));
        }
        return worst;
    }

    // the front-most x the rays meet on the boxes, coming from -x
    private static double front(List<ModelBox> boxes) {
        double front = Double.POSITIVE_INFINITY;
        for (var ray : rays) {
            Vec3 a = ray[0], b = ray[1];
            for (var box : boxes) {
                double t = box.clip(a.x, a.y, a.z, b.x, b.y, b.z);
                if (t >= 0 && t <= 1) front = Math.min(front, a.x + (b.x - a.x) * t);
            }
        }
        return front;
    }

    // every cube's edges: on a head cube (where its zone's planes keep it) green and not orange, elsewhere not green
    // (unless the point lies on a head cube too); the green on the head's cubes
    private static void colors(List<ModelHits.StandPart> parts, List<HeadZone> zones, ModelHitDebug.Colored colored) {
        if (parts == null || colored == null) return;
        colorFrames++;
        var greens = new ModelHitDebug.Segments(colored.green()); var oranges = new ModelHitDebug.Segments(colored.orange());
        for (var part : parts) {
            var zone = part.head() ? zoneOf(part.box(), zones) : null;
            for (var edge : edges(part.box()))
                // a mesh's edges as short as a millionth of a block have no direction to be under another along
                if (edge[0].distanceToSqr(edge[1]) > 1e-8) for (double f : new double[] {.1, .3, .5, .7, .9}) {
                    Vec3 p = edge[0].lerp(edge[1], f);
                    Vec3 along = edge[1].subtract(edge[0]).normalize();
                    // orange over the edge runs along it; one that only meets it there (a body edge ending on the head's) is not
                    boolean green = covered(p, greens.near(p, p), null);
                    if (zone != null && zone.planes().stream().allMatch(plane -> plane.accepts(p))) {
                        headPoints++;
                        // on a plane the orange cut-off part starts: that point is both
                        if (covered(p, oranges.near(p, p), along) && !onPlane(p, zone)) { headOrange++; if (headOrange <= 6) explain(part, p, along, colored); }
                        if (!green) headMissing++;
                    } else {
                        bodyPoints++;
                        // green over a body edge runs along it too; a head's plane cut crossing it there is not over it
                        if (covered(p, greens.near(p, p), along)) { if (onHead(p, zones, ModelHitDebug.UNDER) == 1) shared++; else { bodyGreen++; if (bodyGreen <= 6) explainBody(part, p, along, colored); } }
                    }
                }
        }
        for (var segment : colored.green())
            for (double f : new double[] {.25, .5, .75}) {
                int on = onHead(segment[0].lerp(segment[1], f), zones, 1e-5);
                greenPoints++;
                if (on == 0) greenOff++;
                if (on == 2) meshGreen++;
            }
    }

    // a head point under orange: which orange segment, and the nearest green one, for the log
    private static void explain(ModelHits.StandPart part, Vec3 p, Vec3 along, ModelHitDebug.Colored colored) {
        StringBuilder out = new StringBuilder("[outlinedrift] оранжевое на голове: часть " + part.box().part() + " сетка " + part.box().mesh() + " точка " + p + " ребро " + along);
        for (var list : List.of(colored.orange(), colored.green())) {
            Vec3[] best = null; double bestD = Double.POSITIVE_INFINITY;
            for (var s : list) {
                Vec3 d = s[1].subtract(s[0]); double l = d.lengthSqr();
                double t = l < 1e-18 ? 0 : Math.max(0, Math.min(1, p.subtract(s[0]).dot(d) / l));
                double dist = s[0].add(d.scale(t)).distanceTo(p);
                boolean parallel = l > 1e-18 && d.normalize().cross(along).lengthSqr() < 1e-8;
                if (parallel && dist < bestD) { bestD = dist; best = s; }
            }
            out.append(list == colored.orange() ? "; оранжевый " : "; зелёный ").append(best == null ? "нет" : best[0] + "→" + best[1] + " на " + bestD);
        }
        GunFeel.LOGGER.info(out.toString());
    }

    private static void explainBody(ModelHits.StandPart part, Vec3 p, Vec3 along, ModelHitDebug.Colored colored) {
        StringBuilder out = new StringBuilder("[outlinedrift] зелёное на теле: часть " + part.box().part() + " точка " + p + " ребро " + along);
        for (var s : colored.green()) {
            Vec3 d = s[1].subtract(s[0]); double l = d.lengthSqr();
            double t = l < 1e-18 ? 0 : Math.max(0, Math.min(1, p.subtract(s[0]).dot(d) / l));
            double dist = s[0].add(d.scale(t)).distanceTo(p);
            if (dist < ModelHitDebug.ON) out.append("; зелёный ").append(s[0]).append("→").append(s[1]).append(" t ").append(t)
                    .append(" вдоль ").append(l > 1e-18 && d.normalize().cross(along).lengthSqr() < 1e-8);
        }
        GunFeel.LOGGER.info(out.toString());
    }

    private static boolean onPlane(Vec3 p, HeadZone zone) {
        for (var plane : zone.planes()) {
            double n = Math.sqrt(plane.x() * plane.x() + plane.y() * plane.y() + plane.z() * plane.z());
            if (n > 0 && Math.abs(plane.x() * p.x + plane.y() * p.y + plane.z() * p.z + plane.d()) / n < 1e-4) return true;
        }
        return false;
    }

    // the cubes of a zone (its pieces, else its box) and the zone a pose box is one of
    private static List<ModelBox> cubes(HeadZone zone) { return zone.pieces().isEmpty() ? List.of(zone.box()) : zone.pieces(); }

    private static HeadZone zoneOf(ModelBox box, List<HeadZone> zones) {
        for (var zone : zones) {
            if (same(zone.box(), box)) return zone;
            for (var piece : zone.pieces()) if (same(piece, box)) return zone;
        }
        return null;
    }

    private static boolean same(ModelBox a, ModelBox b) { return offset(a, b) < 1e-6; }

    // a cube's edges: its twelve, or a mesh part's outline
    private static List<Vec3[]> edges(ModelBox box) {
        var out = new ArrayList<Vec3[]>();
        if (box.mesh() != 0) {
            for (var strip : ModelContours.edges(List.of(box)))
                for (int i = 0; i + 1 < strip.length; i += 2) out.add(new Vec3[] {strip[i], strip[i + 1]});
            return out;
        }
        for (int corner = 0; corner < 8; corner++)
            for (int axis = 1; axis <= 4; axis <<= 1)
                if ((corner & axis) == 0) {
                    int other = corner | axis;
                    out.add(new Vec3[] {ModelContours.point(box, corner & 1, corner >> 1 & 1, corner >> 2 & 1),
                            ModelContours.point(box, other & 1, other >> 1 & 1, other >> 2 & 1)});
                }
        return out;
    }

    // p on a segment (within ModelHitDebug.ON), or one along `along` if it is given, within ModelHitDebug.UNDER as split
    // takes the orange off the green
    private static boolean covered(Vec3 p, List<Vec3[]> segments, Vec3 along) {
        for (var s : segments) {
            Vec3 d = s[1].subtract(s[0]);
            double length = d.lengthSqr();
            if (along != null && (length < 1e-18 || d.normalize().cross(along).lengthSqr() > 1e-8)) continue;
            double t = length < 1e-18 ? 0 : Math.max(0, Math.min(1, p.subtract(s[0]).dot(d) / length));
            double within = along != null ? ModelHitDebug.UNDER : ModelHitDebug.ON;
            if (s[0].add(d.scale(t)).distanceToSqr(p) < within * within) return true;
        }
        return false;
    }

    // 1: p is on the surface of a zone's cube (within `within`), where the zone's planes keep it; 2: a mesh cube's bounds
    // hold it (its surface is not checked); 0: neither. A body edge within UNDER of a head cube's is drawn by the head's
    // green, as split takes the orange there off
    private static int onHead(Vec3 p, List<HeadZone> zones, double within) {
        int found = 0;
        for (var zone : zones) {
            if (!zone.planes().stream().allMatch(plane -> plane.x() * p.x + plane.y() * p.y + plane.z() * p.z + plane.d() >= -1e-6)) continue;
            for (var box : cubes(zone)) {
                double[] local = local(box, p);
                if (local == null) continue;
                boolean inside = true;
                double face = Double.POSITIVE_INFINITY;
                for (int i = 0; i < 3; i++) {
                    double size = axis(box, i).length();
                    if (local[i] < -within / size || local[i] > 1 + within / size) inside = false;
                    face = Math.min(face, Math.min(Math.abs(local[i]), Math.abs(1 - local[i])) * size);
                }
                if (!inside) continue;
                if (box.mesh() != 0) found = 2;
                else if (face < within) return 1;
            }
        }
        return found;
    }

    private static Vec3 axis(ModelBox b, int i) {
        return i == 0 ? new Vec3(b.ux(), b.uy(), b.uz()) : i == 1 ? new Vec3(b.vx(), b.vy(), b.vz()) : new Vec3(b.wx(), b.wy(), b.wz());
    }

    // p in the box's own frame (0..1 on each axis), by Cramer's rule
    private static double[] local(ModelBox b, Vec3 p) {
        Vec3 u = axis(b, 0), v = axis(b, 1), w = axis(b, 2), r = p.subtract(origin(b));
        double det = u.dot(v.cross(w));
        if (Math.abs(det) < 1e-15) return null;
        return new double[] {r.dot(v.cross(w)) / det, u.dot(r.cross(w)) / det, u.dot(v.cross(r)) / det};
    }

    // the stand's lines for this case
    record Line(boolean ok, String text) {}
    static List<Line> report() {
        var out = new ArrayList<Line>();
        String mode = ModelHitDebug.LEGACY ? " (прежняя обводка)" : "";
        GunFeel.LOGGER.info(String.format(java.util.Locale.ROOT, "[outlinedrift] время: рисование F3+B %.1f мс, проверка цветов %.1f мс, кадров с цветами %d", drawNanos / 1e6, checkNanos / 1e6, colorFrames));
        for (String name : new String[] {"ход", "поворот", "на месте"})
            if (!DRIFT.containsKey(name)) out.add(new Line(false, "F3+B от модели кадра" + mode + ", " + name + ": ни одного кадра с обводкой цели"));
        for (var entry : DRIFT.entrySet()) {
            var d = entry.getValue();
            out.add(new Line(d.frames > 0 && d.max < 1e-3 && d.noTruth == 0 && d.mismatch == 0, String.format(java.util.Locale.ROOT,
                    "F3+B от модели кадра%s, %s: кадров %d, смещение среднее %.4f, наибольшее %.4f блока, вдоль хода %.4f; без снимка %d, другое число частей %d",
                    mode, entry.getKey(), d.frames, d.frames == 0 ? 0 : d.sum / d.frames, d.max, d.frames == 0 ? 0 : d.along / d.frames, d.noTruth, d.mismatch)));
        }
        out.add(new Line(colorFrames > 0 && headOrange == 0 && headMissing == 0 && bodyGreen == 0 && greenOff == 0, String.format(java.util.Locale.ROOT,
                "цвета F3+B по зонам%s: кадров %d; точек рёбер головы %d, оранжевых %d, без зелёного %d; точек тела %d, зелёных %d (на кубе головы %d); точек зелёного %d, мимо кубов головы %d (в сетке, не проверено %d)",
                mode, colorFrames, headPoints, headOrange, headMissing, bodyPoints, bodyGreen, shared, greenPoints, greenOff, meshGreen)));
        out.add(new Line(orangePoints > 0 && orangeLate == 0, String.format(java.util.Locale.ROOT,
                "отставание оранжевого%s (как в 26.2: модель прошлого кадра или моложе %.1f мс): точек %d, на модели кадра %d, на прошлой или моложе порога %d, старше %d",
                mode, ModelContours.LAG / 1e6, orangePoints, orangeNow, orangeLast, orangeLate)));
        // the server's zone against the last frame before each shot
        Map<String, double[]> server = new LinkedHashMap<>();
        int unpaired = 0, missed = 0, noPose = 0;
        for (var s : SAMPLES) {
            String phase = phase(s.tick());
            Frame seen = null;
            for (var f : FRAMES) if (f.nanos() <= s.nanos()) seen = f;
            if (seen == null) { unpaired++; continue; }
            if (!s.pose()) noPose++;
            if (!Double.isFinite(s.front()) || !Double.isFinite(seen.front())) { missed++; continue; }
            double ahead = (s.front() - seen.front()) * MOTION.x;
            var a = server.computeIfAbsent(phase, k -> new double[3]);
            a[0]++; a[1] += ahead; a[2] = Math.max(a[2], Math.abs(ahead));
        }
        for (var entry : server.entrySet()) {
            var a = entry.getValue();
            // what it is, not a check: the server holds the pose the client sent up to ModelHits.INTERVAL ticks ago and
            // carries it by its velocity, while the model on the screen goes on animating, standing too
            out.add(new Line(true, String.format(java.util.Locale.ROOT,
                    "зона сервера от модели на последнем кадре игрока, %s: выстрелов %d, вперёд по ходу среднее %.4f, наибольшее %.4f блока (шаг моба %.2f за тик)",
                    entry.getKey(), (int) a[0], a[1] / a[0], a[2], STEP)));
        }
        out.add(new Line(!SAMPLES.isEmpty() && unpaired == 0 && missed == 0 && noPose == 0, "зона сервера: выстрелов " + SAMPLES.size() + ", без кадра до выстрела " + unpaired + ", лучи мимо " + missed + ", без позы на сервере " + noPose));
        return out;
    }
}
