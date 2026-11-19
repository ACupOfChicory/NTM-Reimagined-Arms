package acup.gunfeel;

import java.util.List;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// The arms' timeline: what a hand does at this moment of the gun's NTM animation, from the gun's settings (Config)
// and the animation playing (GunAnimState): hidden, turned around the part it holds (keyValue), carrying something
// the animation moves (carry, carryState), on a path of keys over the gun's parts (pathState), letting go on a turning
// bus (release). Taken out of Arms as it was; Arms draws the arms with it.
final class ArmTimeline {
    // the animation playing; a version whose NTM has no animation bus (NtmFeature.ANIM_BUS) gives none: no port
    static final GunAnimState ANIM = CoreServices.load(GunAnimState.class, GunAnimState.NONE);

    private ArmTimeline() {
    }

    // the pose of the nth drawing of a part this frame (in the gun's aligned frame), null = not drawn
    interface PartPoses {
        Matrix4f pose(String part, int nth);
    }

    private static final String[] hideSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, float[]>[] hideKeys = new Map[] {Map.of(), Map.of()};

    // "RELOAD 0-650; RELOAD_END 0-700" (ms from the animation's start, several ranges allowed): the hand hidden then
    static boolean hidden(int hand) {
        String src = hand == 0 ? Config.armRightHide : Config.armLeftHide;
        if (src.isEmpty()) return false;
        if (!src.equals(hideSource[hand])) {
            hideSource[hand] = src;
            hideKeys[hand] = ArmRig.ranges(src);
        }
        float[] r = forAnimation(hideKeys[hand]);
        if (r == null) return false;
        float t = animMillis();
        for (int j = 0; j + 1 < r.length; j += 2) if (t >= r[j] && t < r[j + 1]) return true;
        return false;
    }

    private static final String[] keysSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, float[]>[] keys = new Map[] {Map.of(), Map.of()};

    // angle of the left hand around the part it holds at this moment of the animation (armLeftKeys), NaN = none
    static float leftAngle() {
        return keyValue(Config.armLeftKeys, 0);
    }

    static float animMillis() {
        return ANIM.millis();
    }

    // value of time keys ("RELOAD 0:-90 240:90; ...") at this moment of the current animation, NaN = none
    static float keyValue(String src, int slot) {
        if (src.isEmpty()) return Float.NaN;
        String type = ANIM.type();
        if (!ANIM.playing() || type == null) return Float.NaN;
        if (!src.equals(keysSource[slot])) {
            keysSource[slot] = src;
            keys[slot] = ArmRig.angleKeys(src);
        }
        float[] k = keys[slot].get(type);
        if (k == null) return Float.NaN;
        float t = animMillis();
        if (t <= k[0]) return k[1];
        for (int j = 2; j + 1 < k.length; j += 2) {
            if (t < k[j]) {
                float u = (t - k[j - 2]) / Math.max(k[j] - k[j - 2], 1F);
                u = u * u * (3F - 2F * u);
                return k[j - 1] + (k[j + 1] - k[j - 1]) * u;
            }
        }
        return k[k.length - 1];
    }

    // what a hand carries: a point moved by an NTM bus within a part, the hand on its `dir` side.
    // Several buses with a point each ("SHELL1|SHELL2", "x,y,z|x,y,z"): the hand takes the one whose bus
    // moves in the animation playing now (Liberator loads one of four shells), and keeps it while none moves.
    record Carry(String part, String[] buses, int grab, float[][] ats, float[] dir, int[] active) {
        String bus() {
            return buses[active[0]];
        }

        float[] at() {
            return ats[Math.min(active[0], ats.length - 1)];
        }

        void pick() {
            if (buses.length < 2) return;
            if (!ANIM.playing()) return;
            for (int j = 0; j < buses.length; j++) {
                if (ANIM.busTime(buses[j]) > 0) {
                    active[0] = j;
                    return;
                }
            }
        }
    }

    static final Carry NO_CARRY = new Carry("", new String[] {""}, 0, new float[][] {new float[3]}, ArmRig.DEFAULT_SIDE, new int[1]);
    private static final String[] carrySource = new String[2];
    private static final Carry[] carry = {NO_CARRY, NO_CARRY};

    // armRightCarry / armLeftCarry: "<part> <bus> <grab ms> x,y,z [dx,dy,dz]"
    static Carry carry(int hand) {
        String src = hand == 0 ? Config.armRightCarry : Config.armLeftCarry;
        // a reload may carry differently (armRightCarryReload / armLeftCarryReload)
        String reload = hand == 0 ? Config.armRightCarryReload : Config.armLeftCarryReload;
        if (!reload.isEmpty()) {
            String playing = ANIM.type();
            if (GunAnimState.RELOAD.equals(playing) || GunAnimState.RELOAD_CYCLE.equals(playing) || GunAnimState.RELOAD_END.equals(playing))
                src = reload;
        }
        if (!src.equals(carrySource[hand])) {
            carrySource[hand] = src;
            ArmRig.CarrySpec c = ArmRig.carry(src);
            carry[hand] = c == null ? NO_CARRY
                    : new Carry(c.part(), c.buses(), c.grab(), c.ats(), c.dir() != null ? c.dir() : NO_CARRY.dir, new int[1]);
        }
        return carry[hand];
    }

    // Carry keys, per animation: "RELOAD 0:0 120:1 300:1/300 520:0 ; RELOAD_CYCLE ..." = ms:weight (0 on the gun,
    // 1 on the carried thing), optionally /b = where along the bus the thing is taken from (ms; walking b back
    // retraces its path), or @x,y,z = a fixed point instead of the bus. An animation name may be followed by
    // /<bus> to apply only to animations that have that bus (SPAS-12: RELOAD/PumpGrip = the empty reload).
    // Each entry: {t, w, kind (0 live, 1 bus time, 2 fixed point), b, x, y, z}
    private static final String[] carryKeysSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, List<float[]>>[] carryKeys = new Map[] {Map.of(), Map.of()};

    // armLeftPath / armRightPath, per animation (ArmRig.pathKeys)
    private static final String[] pathSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, List<ArmRig.PathKey>>[] pathKeys = new Map[] {Map.of(), Map.of()};

    // {weight on the path (0 = at the grip), point x,y,z, side x,y,z, shoulder shift x,y,z} in camera space, or null off the path
    static float[] pathState(int hand, Matrix4f cameraInv, Vector3f restShoulder, PartPoses poses) {
        String src = hand == 0 ? Config.armRightPath : Config.armLeftPath;
        if (src.isEmpty()) return null;
        String srcKey = src + "\n" + Config.armPathTimes;
        if (!srcKey.equals(pathSource[hand])) {
            pathSource[hand] = srcKey;
            pathKeys[hand] = ArmRig.pathKeys(src, Config.armPathTimes);
        }
        List<ArmRig.PathKey> k = forAnimation(pathKeys[hand]);
        if (k == null) return null;
        float t = animMillis();
        ArmRig.PathKey a = k.get(0), b = a;
        float u = 0F;
        if (t >= k.get(k.size() - 1).ms()) a = b = k.get(k.size() - 1);
        else if (t > a.ms()) {
            for (int j = 1; j < k.size(); j++) {
                if (t < k.get(j).ms()) {
                    a = k.get(j - 1);
                    b = k.get(j);
                    u = (t - a.ms()) / Math.max(b.ms() - a.ms(), 1F);
                    break;
                }
            }
        }
        float s = u * u * (3F - 2F * u);
        float[] pa = pathPoint(a, cameraInv, restShoulder, hand == 0, poses), pb = pathPoint(b, cameraInv, restShoulder, hand == 0, poses);
        if (pa == null && pb == null) return null;
        // the grip end has no shoulder shift: it fades in and out with the weight
        if (pa == null) return new float[] {s, pb[0], pb[1], pb[2], pb[3], pb[4], pb[5], pb[6] * s, pb[7] * s, pb[8] * s, pb[9] * s, pb[10] * s,pb[11],pb[12],pb[13]};
        if (pb == null) return new float[] {1F - s, pa[0], pa[1], pa[2], pa[3], pa[4], pa[5], pa[6] * (1F - s), pa[7] * (1F - s), pa[8] * (1F - s), pa[9] * (1F - s), pa[10] * (1F - s),pa[11],pa[12],pa[13]};
        float[] out = new float[15];
        out[0] = 1F;
        for (int j = 0; j < 14; j++) out[j + 1] = pa[j] + (pb[j] - pa[j]) * s;
        return out;
    }

    // a key's point and side in camera space; null for grip (or a part not drawn)
    private static float[] pathPoint(ArmRig.PathKey key, Matrix4f cameraInv, Vector3f restShoulder, boolean right, PartPoses poses) {
        if (key.part() == null) return null;
        Matrix4f pose = poses.pose(key.part(), key.nth());
        if (pose == null) return null;
        Matrix4f within = new Matrix4f(cameraInv).mul(pose);
        Vector3f at = within.transformPosition(key.at()[0], key.at()[1], key.at()[2], new Vector3f());
        Vector3f d = new Vector3f(key.side());
        Vector3f side = d.lengthSquared() < 1e-8F ? new Vector3f()
                : within.transformDirection(new Vector3f(d)).normalize().mul(d.length());
        var shoulder = new Vector3f(key.shoulder());
        if (key.followShoulder()) shoulder.add(at).sub(restShoulder);
        float roll = key.roll();
        if (!Float.isFinite(roll)) {
            float load = right ? Config.armLoadRollRight : Config.armLoadRollLeft;
            roll = Float.isFinite(load) ? load - (right ? Config.armRollRight : Config.armRollLeft) : 0F;
        }
        var contact=ArmContact.offset(key.contact());
        return new float[] {at.x, at.y, at.z, side.x, side.y, side.z, shoulder.x, shoulder.y, shoulder.z, key.contact()>0 ? 1F : 0F, roll,contact.x,contact.y,contact.z};
    }

    // keys for the animation playing now: "<TYPE>/<bus>" when the animation has that bus, else "<TYPE>"
    private static <T> T forAnimation(Map<String, T> byName) {
        String type = ANIM.type();
        if (!ANIM.playing() || type == null) return null;
        String prefix = type + "/";
        for (Map.Entry<String, T> e : byName.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String bus = e.getKey().substring(prefix.length());
            if (ANIM.hasBus(bus)) return e.getValue();
        }
        return byName.get(type);
    }

    // {weight, x, y, z}: how much the hand is on the carried thing now and where that is (aligned frame of the
    // carry part); null = the hand stays on the gun. toAligned: the gun model's frame to its aligned one.
    static float[] carryState(int hand, Matrix3f toAligned) {
        String src = hand == 0 ? Config.armRightCarryKeys : Config.armLeftCarryKeys;
        if (src.isEmpty() || carry(hand) == NO_CARRY) return null;
        carry(hand).pick();
        if (!src.equals(carryKeysSource[hand])) {
            carryKeysSource[hand] = src;
            carryKeys[hand] = ArmRig.carryKeys(src);
        }
        List<float[]> k = forAnimation(carryKeys[hand]);
        if (k == null) return null;
        float t = animMillis();
        float[] a = k.get(0), b = a;
        float u = 0F;
        if (t >= k.get(k.size() - 1)[0]) a = b = k.get(k.size() - 1);
        else if (t > a[0]) {
            for (int j = 1; j < k.size(); j++) {
                if (t < k.get(j)[0]) {
                    a = k.get(j - 1);
                    b = k.get(j);
                    u = (t - a[0]) / Math.max(b[0] - a[0], 1F);
                    break;
                }
            }
        }
        float s = u * u * (3F - 2F * u);
        float w = a[1] + (b[1] - a[1]) * s;
        if (w <= 0F) return null;
        Carry c = carry(hand);
        Vector3f p;
        if (a[2] != 2F && b[2] != 2F) {
            float live = Math.max(t, c.grab);
            float ba = a[2] == 1F ? a[3] : live, bb = b[2] == 1F ? b[3] : live;
            p = busPoint(c, ba + (bb - ba) * u, toAligned);
        } else {
            Vector3f pa = a[2] == 2F ? fixedPoint(c, a, toAligned) : busPoint(c, a[2] == 1F ? a[3] : Math.max(t, c.grab), toAligned);
            Vector3f pb = b[2] == 2F ? fixedPoint(c, b, toAligned) : busPoint(c, b[2] == 1F ? b[3] : Math.max(t, c.grab), toAligned);
            p = pa.lerp(pb, s);
        }
        return new float[] {Math.min(w, 1F), p.x, p.y, p.z};
    }

    static boolean magazineInspect(int hand) {
        return hand == 1 && MagazineInspect.enabled(Config.armInspectReleaseMs)
                && GunAnimState.INSPECT.equals(ANIM.type());
    }

    // the carried point with the bus at time `ms`: the bus's location (model frame) plus the point
    private static Vector3f busPoint(Carry c, float ms, Matrix3f toAligned) {
        float[] a = c.at();
        Vector3f p = new Vector3f(a[0], a[1], a[2]);
        double[] t = ANIM.busAt(c.bus(), (int) ms);
        if (t == null) return p;
        return p.add(toAligned.transform(new Vector3f((float) t[0], (float) t[1], (float) t[2])));
    }

    private static Vector3f fixedPoint(Carry c, float[] key, Matrix3f toAligned) {
        float[] a = c.at();
        return new Vector3f(a[0], a[1], a[2]).add(toAligned.transform(new Vector3f(key[4], key[5], key[6])));
    }

    // progress (0..1) of the turn on a release bus (armReleaseLeft / armReleaseRight) while the hand is off the gun,
    // else 0; the turn is on whichever axis the bus rotates (Maresleg FLIP: x, Henry ROLL: z)
    static float release(String bus) {
        if (bus.isEmpty() || !ANIM.playing()) return 0F;
        return release(bus, ANIM.busNow(bus));
    }

    static float release(String bus, double[] t) {
        if (bus.isEmpty() || t == null) return 0F;
        double angle = Math.max(Math.abs(t[0]), Math.max(Math.abs(t[1]), Math.abs(t[2])));
        double turns = angle / 360.0;
        float p = (float) (turns - Math.floor(turns));
        return p > 1e-3F && p < 1F - 1e-3F ? p : 0F;
    }
}
