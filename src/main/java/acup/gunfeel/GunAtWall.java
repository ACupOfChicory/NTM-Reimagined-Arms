package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The gun at a wall: a player standing close to a block with a gun in hand (first person) holds it back and the muzzle
 * up instead of pushing the barrel into the block.
 *
 * <p>The gun's size in blocks: NTM draws the gun in the hands in a space of its own, not in blocks (the G3 is 9.4 long
 * there, its muzzle 7.2 ahead of the eyes; the picture is the same whatever the scale, so nothing on screen tells it).
 * In the world (in another player's hands) NTM draws each gun's model at a scale of its own, the third person's of its
 * item model (models/item/gun_*_base.json, display.thirdperson_righthand.scale, model units to blocks). So a gun's
 * blocks per unit of the hands' space are its model's length times that scale over its length as drawn in the hands.
 * A gun with no such scale (its item model not NTM's gun model, or the file not read) is left as it is.
 *
 * <p>The gun's two ends are taken from its model as drawn at rest (the last frame, without the sway and WalkSprint's
 * poses), where they show on screen in the world (the hands' own field of view counted in, as Tracers places the
 * muzzle), in blocks. Each frame two rays go through the world, along the gun (from where it comes before the eyes) to
 * its muzzle and from the eyes to the muzzle, blocks by their collision only (grass and flowers do not stop a gun, glass
 * and fences do), not mobs; a ledge no higher than a step where the gun meets it (a stair, a slab, a low wall's top, the
 * floor) is not a wall: the ray goes on over it a step higher. The nearer wall gives how far forward the muzzle may reach; the muzzle is turned up as far as
 * it takes to stay before it (75° at most), and the gun drawn back by how far into the wall it would go.
 *
 * <p>Only the muzzle's depth is kept before the wall: the gun turned up is not tested against a ceiling or a wall beside
 * it. The gun is measured only at rest: while an NTM animation moves it (drawn, reloaded, inspected, fired) its last
 * measure at rest stands, and a gun just drawn is left as it is until it comes to rest. An NTM animation other than a
 * shot's takes the gun out of the wall's pose while it plays (WalkSprint), and the wall's pose takes the place of the
 * walk's and the sprint's (others). Not for the other hand or an akimbo pair (WalkSprint leaves those passes alone).
 *
 * <p>A shot is not held back: the bullet flies as always, and a fire key held or aiming lowers the muzzle back to the
 * front; the gun stays drawn back, but not while aiming (the sights would come nearer the eye).
 */
public final class GunAtWall {
    /** The eyes are never nearer a wall than this (the player's half width): there the gun is all the way back. */
    static final float NEAR = 0.3F;
    /** The muzzle up at most (degrees), and how far before the wall it stays (blocks). */
    static final float MAX_PITCH = 75F, MARGIN = 0.05F;
    /** Drawn back toward the eye at full, in the gun's length L... */
    static final float BACK = 0.15F;
    /** ...but no more than this share of the gun's middle's distance from the eye (a long gun's L is long). */
    static final float BACK_OF_MIDDLE = 0.35F;
    /** A ledge no higher than this over where the gun meets it (blocks) is not a wall: a stair, a slab, a low wall's top. */
    static final float STEP = 0.55F;
    /** Seconds to come in and to go out; a fire key or aiming lowers the muzzle quicker. */
    static final float IN = 0.15F, OUT = 0.25F, LOWER = 0.1F;

    // the gun's back (where it comes before the eyes) and muzzle at rest, in the world's view space in blocks (x right,
    // y up, z back), from the last frame; the muzzle and the turn's middle in the space WalkSprint turns the gun in (its
    // units), blocks per its unit, and the gun they belong to
    private static final Vector3f BACK_END = new Vector3f(), MUZZLE = new Vector3f(), FRONT_LOCAL = new Vector3f();
    private static float blocksPerUnit;
    private static Item measured;
    // the muzzle as drawn the last frame (with every pose), in blocks, for the stand
    private static final Vector3f DRAWN = new Vector3f();
    // the last frame's: how far into the wall, and how far forward the muzzle may reach (blocks, infinite: no wall)
    private static float lastInto, lastLimit = Float.POSITIVE_INFINITY;
    // each gun's third person scale, NaN where there is none
    private static final Map<Item, Float> SCALES = new HashMap<>();

    private GunAtWall() {
    }

    /** A gun's third person scale from its NTM item model, NaN when there is none. */
    static float thirdPersonScale(Item item) {
        return SCALES.computeIfAbsent(item, GunAtWall::readScale);
    }

    /** On joining a world: the scales read again (resource packs may have changed), no gun measured. */
    public static void reset() {
        SCALES.clear();
        measured = null;
    }

    private static float readScale(Item item) {
        try {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            JsonObject model = json(Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json")).getAsJsonObject("model");
            if (model == null || !"hbm:gun".equals(model.get("type").getAsString())) return Float.NaN;
            Identifier base = Identifier.parse(model.get("base").getAsString());
            // the display may come from a parent model, one step up at most
            JsonObject baseModel = json(Identifier.fromNamespaceAndPath(base.getNamespace(), "models/" + base.getPath() + ".json"));
            float v = scale(baseModel.getAsJsonObject("display"));
            if (Float.isNaN(v) && baseModel.has("parent")) {
                Identifier parent = Identifier.parse(baseModel.get("parent").getAsString());
                v = scale(json(Identifier.fromNamespaceAndPath(parent.getNamespace(), "models/" + parent.getPath() + ".json")).getAsJsonObject("display"));
            }
            return v;
        } catch (Exception e) {
            // read again on the next world joined (reset)
            GunFeel.LOGGER.debug("gun at a wall: no third person scale for {}: {}", item, e.toString());
            return Float.NaN;
        }
    }

    /** display.thirdperson_righthand.scale's first number (NTM scales its guns evenly), NaN when missing or not positive. */
    static float scale(JsonObject display) {
        if (display == null || !display.has("thirdperson_righthand")) return Float.NaN;
        var s = display.getAsJsonObject("thirdperson_righthand").getAsJsonArray("scale");
        if (s == null || s.isEmpty()) return Float.NaN;
        float v = s.get(0).getAsFloat();
        return v > 0F && Float.isFinite(v) ? v : Float.NaN;
    }

    private static JsonObject json(Identifier path) throws java.io.IOException {
        try (var in = Minecraft.getInstance().getResourceManager().open(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * After NTM placed the gun (WalkSprint.gunPlaced), in the pose stack's space (NTM's pose, in the world's axes): the
     * ends at rest and as drawn; and, in the space WalkSprint turns the gun in, the muzzle. Its blocks per unit by its
     * third person scale; to the world's view space and its field of view, as Tracers.view does for the muzzle.
     */
    static void placed(Item item, Vector3f back, Vector3f front, Vector3f drawnFront, Vector3f frontLocal, float localLength) {
        // only at rest: while an NTM animation moves the gun (drawn, reloaded, inspected, fired) the last measure at rest
        // stands, a gun just drawn has none yet (drawn, it may point at the floor)
        if (com.hbm.render.anim.HbmAnimations.getRelevantAnim(0) != null) return;
        measured = null;
        GunGeometry g = Arms.geometry(item);
        float scale = thirdPersonScale(item);
        if (g == null || !(localLength > 1e-4F)) return;
        // in the units WalkSprint turns the gun in (the pitch is solved there)
        float c = blocksPerUnit(g.length(), scale, localLength);
        if (!(c > 0F)) return;
        Matrix4f view = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack());
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        float k = fovScale(camera.getFov(), Tracers.hudFov(camera));
        Vector3f b = toView(view, back, k).mul(c), f = toView(view, front, k).mul(c);
        DRAWN.set(toView(view, drawnFront, k).mul(c));
        if (!b.isFinite() || !f.isFinite() || !(-f.z() > 1e-3F)) return;
        // the part of the gun before the eyes: a back end behind them (a launcher on the shoulder) may be in a wall
        // behind the player or in the floor
        if (-b.z() < 0F) b.lerp(f, -b.z() / (f.z() - b.z()));
        BACK_END.set(b);
        MUZZLE.set(f);
        FRONT_LOCAL.set(frontLocal);
        blocksPerUnit = c;
        measured = item;
    }

    /** Blocks per unit of the hands' space: the model's length in the world (its length times the third person scale) over its length drawn. */
    static float blocksPerUnit(float modelLength, float scale, float drawnLength) {
        if (!(modelLength > 0F) || !(scale > 0F) || !(drawnLength > 0F)) return Float.NaN;
        return modelLength * scale / drawnLength;
    }

    static Vector3f toView(Matrix4f view, Vector3f p, float k) {
        Vector3f v = view.transformPosition(new Vector3f(p));
        v.x *= k;
        v.y *= k;
        return v;
    }

    /** The hands are drawn with their own field of view: a point at x, y shows where x·k, y·k would in the world's. */
    static float fovScale(float world, float hud) {
        if (!(hud > 1F && hud < 179F && world > 1F && world < 179F)) return 1F;
        return (float) (Math.tan(Math.toRadians(world * 0.5)) / Math.tan(Math.toRadians(hud * 0.5)));
    }

    /**
     * How far into the wall the gun would go, 0..1, along a ray from depth a to depth b (forward of the eyes) whose wall
     * is at the share t of the way (t ≥ 1: no wall): 0 at the muzzle, 1 where the wall is once the eyes are NEAR it.
     */
    static float into(float t, float depthA, float depthB) {
        if (!(t < 1F)) return 0F;
        float full = depthB > depthA ? Math.clamp((NEAR - depthA) / (depthB - depthA), 0F, 0.95F) : 0F;
        return Math.clamp((1F - t) / (1F - full), 0F, 1F);
    }

    /**
     * Each frame before NTM places the gun drawn (WalkSprint.apply): how far into a wall it would go, 0..1, and how far
     * forward its muzzle may reach (limit).
     */
    static float into(LocalPlayer player, Item item) {
        lastInto = 0F;
        lastLimit = Float.POSITIVE_INFINITY;
        if (measured != item || player == null || player.isSpectator()) return 0F;
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Vec3 back = world(camera, BACK_END), muzzle = world(camera, MUZZLE);
        // depths forward of the eyes (view space looks down -z)
        float db = -BACK_END.z(), df = -MUZZLE.z();
        float ta = ray(player, back, muzzle), te = ray(player, eye, muzzle);
        lastInto = Math.max(into(ta, db, df), into(te, 0F, df));
        if (ta < 1F) lastLimit = Math.min(lastLimit, db + ta * (df - db));
        if (te < 1F) lastLimit = Math.min(lastLimit, te * df);
        return lastInto;
    }

    static float limitNow() {
        return lastLimit;
    }

    private static Vec3 world(Camera camera, Vector3f v) {
        Vector3f w = camera.rotation().transform(new Vector3f(v));
        return camera.position().add(w.x, w.y, w.z);
    }

    /** The share of the way from a to b where the first block's collision is, or 1 for none; over two ledges at most. */
    private static float ray(LocalPlayer player, Vec3 a, Vec3 b) {
        return ray(player, a, b, 2);
    }

    private static float ray(LocalPlayer player, Vec3 a, Vec3 b, int ledges) {
        double len = a.distanceTo(b);
        if (len < 1e-4) return 1F;
        BlockHitResult hit = player.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return 1F;
        float t = (float) (hit.getLocation().distanceTo(a) / len);
        // a ceiling met from below, a ray starting inside a block: a wall; but a ceiling only the ray gone a step higher
        // over a ledge meets is not where the gun goes (stairs in a low passage)
        boolean ceiling = hit.getDirection() == Direction.DOWN;
        if (ceiling && ledges < 2 && !hit.isInside()) return 1F;
        if (ledges <= 0 || hit.isInside() || ceiling || !ledge(player, hit.getLocation(), b.subtract(a).scale(1.0 / len)))
            return t;
        // over the ledge: the rest of the way a step higher
        Vec3 up = new Vec3(0.0, STEP, 0.0);
        return t + (1F - t) * ray(player, hit.getLocation().add(up), b.add(up), ledges - 1);
    }

    /** Whether the block met at p going dir is a ledge: a step above, just inside it, no block's collision. */
    private static boolean ledge(LocalPlayer player, Vec3 p, Vec3 dir) {
        Vec3 q = p.add(dir.scale(0.02)).add(0.0, STEP, 0.0);
        BlockPos pos = BlockPos.containing(q);
        var level = player.level();
        for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(player)).toAabbs())
            if (box.move(pos).contains(q)) return false;
        return true;
    }

    /** The drawing back at the weight w, in L: BACK, but no more than BACK_OF_MIDDLE of the middle's distance from the eye. */
    static float back(float w, float length, float middle) {
        if (w <= 0F || !(length > 1e-6F)) return 0F;
        return w * Math.min(BACK, BACK_OF_MIDDLE * middle / length);
    }

    /**
     * The muzzle's turn up (degrees, 0..MAX_PITCH) that keeps it MARGIN before a wall limit blocks forward of the eyes:
     * the muzzle at f and the turn's middle at p (the space WalkSprint turns in, its units; y up, z back), drawn back by
     * back units, c blocks a unit, and its rest depth in blocks (the turn and the shift found in units are put on it).
     */
    static float pitch(float limit, Vector3f f, Vector3f p, float back, float c, float restDepth) {
        if (!Float.isFinite(limit) || !(c > 0F)) return 0F;
        float ry = f.y() - p.y(), rz = f.z() - p.z();
        float goal = limit - MARGIN;
        if (depth(0F, ry, rz, p.z(), back, c, restDepth, f.z()) <= goal) return 0F;
        float lo = 0F, hi = MAX_PITCH;
        if (depth(hi, ry, rz, p.z(), back, c, restDepth, f.z()) > goal) return MAX_PITCH;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) / 2F;
            if (depth(mid, ry, rz, p.z(), back, c, restDepth, f.z()) > goal) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    // the muzzle's depth in blocks turned up by deg around the middle and drawn back
    private static float depth(float deg, float ry, float rz, float pz, float back, float c, float restDepth, float fz) {
        double a = Math.toRadians(deg);
        float z = pz + (float) (ry * Math.sin(a) + rz * Math.cos(a)) + back;
        return restDepth + (fz - z) * c;
    }

    /** The pose: the muzzle up by deg, lowered by low (0..1, a fire key or aiming), drawn back by back (in L). */
    static WalkSprint.Pose pose(float deg, float low, float back) {
        return new WalkSprint.Pose(deg * (1F - low), 0F, 0F, 0F, 0F, back);
    }

    /**
     * An NTM animation the gun at a wall gives way to (it goes out of its pose while it plays): all but a shot's, the
     * other barrel's and a dry click's (a fire key held lowers the muzzle there, the gun stays back) and a barrel spinning
     * up or down; one with no type gives way too.
     */
    static boolean givesWay(com.hbm.render.anim.AnimationEnums.GunAnimation type) {
        if (type == null) return true;
        return switch (type) {
            case CYCLE, CYCLE_EMPTY, CYCLE_DRY, ALT_CYCLE, SPINUP, SPINDOWN -> false;
            default -> true;
        };
    }

    /** The walk's and the sprint's poses' share beside the wall's at its weight w: the wall's takes their place. */
    static float others(float w) {
        return 1F - WalkSprint.ease(Math.clamp(w, 0F, 1F));
    }

    /** The muzzle's turn up for the gun drawn now, for the limit of the last into (WalkSprint.apply). */
    static float pitchNow(Item item, Vector3f pivot, float backUnits) {
        if (measured != item) return 0F;
        return pitch(lastLimit, FRONT_LOCAL, pivot, backUnits, blocksPerUnit, -MUZZLE.z());
    }

    /** For the stand's trace: the muzzle at rest in the world's view space in blocks, or null while not measured. */
    static Vector3f muzzle(Item item) {
        return measured == item ? new Vector3f(MUZZLE) : null;
    }

    static Vector3f drawnMuzzle() {
        return new Vector3f(DRAWN);
    }

    static float blocksPerUnitNow() {
        return blocksPerUnit;
    }

    /** For the stand: a point of the world's view space in the world, by the camera now. */
    static Vec3 inWorld(Vector3f v) {
        return world(Minecraft.getInstance().gameRenderer.mainCamera(), v);
    }

    static Vector3f backEnd(Item item) {
        return measured == item ? new Vector3f(BACK_END) : null;
    }
}
