package acup.gunfeel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Player arms holding the first-person NTM gun with both hands.
// The first model NTM submits for a gun is its body; the parts it draws give the gun's shape
// (GunGeometry). Hands sit on that shape (Grips), follow the body through NTM's animations, and the
// arms are the player's skinned arms reaching back from the hands along directions fixed to the camera. Arm
// orientation is built against the camera's up axis so arms don't twist when the view turns.
public final class Arms {
    // every variant of a gun seen so far (a weapon mod can change which parts NTM draws), and the one in hand
    private static final Map<Item, List<GunGeometry>> VARIANTS = new IdentityHashMap<>();
    private static final Map<Item, GunGeometry> CURRENT = new IdentityHashMap<>();
    private static final Map<GunGeometry, float[][]> GRIP_CACHE = new IdentityHashMap<>();
    // the gun body and the parts each hand holds relative to the aimed gun frame while no animation plays, per gun variant
    private static final Map<GunGeometry, ArmPoses.GunRest> GUN_REST = new IdentityHashMap<>();

    // each arm at rest, per gun variant: during animations the arm keeps this length and thickness
    private static final Map<GunGeometry, ArmRest[]> ARM_REST = new IdentityHashMap<>();

    private static final Matrix4f CAMERA = new Matrix4f();
    private static final Matrix4f KICKED = new Matrix4f();
    // gun frame after aiming, before the model kick
    private static final Matrix4f AIMED = new Matrix4f();
    private static final Matrix4f BODY = new Matrix4f();
    // body frame relative to the gun frame (after the kick), from the previous frame
    private static final Matrix4f REL = new Matrix4f();
    private static boolean haveRel;
    private static Item relItem;
    private static int gripVersion = -1;

    // pose of the part each hand moves with (armPartRight / armPartLeft), this frame
    // [2], [3]: the part the right / left hand carries things within (armRightCarry / armLeftCarry)
    private static final Matrix4f[] FOLLOW = {new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()};
    private static final boolean[] HAVE_FOLLOW = new boolean[4];
    private static final int[] FOLLOW_ID = {-1, -1, -1, -1};

    // the parts NTM drew this frame (of the first model it drew) and where
    private static final int MAX_PARTS = 128;
    private static final Matrix4f FROM_ALIGNED = new Matrix4f();
    private static final int[] FRAME_PART = new int[MAX_PARTS];
    private static final Matrix4f[] FRAME_POSE = new Matrix4f[MAX_PARTS];
    private static int frameParts;
    private static HFRWavefrontObject frameModel;

    private static Item item;
    private static GunGeometry geometry;
    private static boolean capturing, haveCamera;
    private static boolean pistolHoldFrame;

    private Arms() {
    }

    // A gun's rest pose is read while no animation moves it. Taken in hand for the first time it plays its draw
    // animation, so the arms would wait for it to end: that frame the gun is first run through its renderer with
    // the animations held still and drawn nowhere (measuring), only to read the pose.
    public static boolean measuring;
    private static final Map<Item, Integer> MEASURES = new IdentityHashMap<>();
    public static final net.minecraft.client.renderer.SubmitNodeCollector NOWHERE = (net.minecraft.client.renderer.SubmitNodeCollector)
            java.lang.reflect.Proxy.newProxyInstance(Arms.class.getClassLoader(), new Class<?>[] {net.minecraft.client.renderer.SubmitNodeCollector.class},
                    (proxy, m, args) -> {
                        Class<?> t = m.getReturnType();
                        if (t.isInstance(proxy)) return proxy;
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "Arms.NOWHERE";
                            };
                        }
                        if (t == boolean.class) return false;
                        if (t == int.class) return 0;
                        if (t == long.class) return 0L;
                        if (t == float.class) return 0F;
                        if (t == double.class) return 0.0;
                        return null;
                    });

    // its rest pose not read yet (a few tries per gun: one whose pose cannot be read is not run twice every frame)
    public static boolean needsMeasure(Item held) {
        GunGeometry g = CURRENT.get(held);
        if (g != null && g.aligned()) {
            ArmPoses.GunRest gr = GUN_REST.get(g);
            if (gr != null && gr.valid) return false;
        }
        return MEASURES.merge(held, 1, Integer::sum) <= 5;
    }

    // the variant of the gun last drawn in hand
    public static GunGeometry geometry(Item item) {
        GunGeometry g = CURRENT.get(item);
        return g != null && g.aligned() ? g : null;
    }

    public static void begin(ItemStack stack) {
        resetPistolHoldFrame();
        item = stack.getItem();
        geometry = null;
        haveCamera = false;
        frameModel = null;
        frameParts = 0;
        java.util.Arrays.fill(HAVE_FOLLOW, false);
        java.util.Arrays.fill(FOLLOW_ID, -1);
        capturing = true;
        ArmLayers.newFrame();
    }

    static void resetPistolHoldFrame() {
        pistolHoldFrame = false;
    }

    // a standardAimingTransform call: only NTM's placing call sets it, a later one in the frame (the heavy
    // revolver's kick) keeps what the placing call set
    public static void pistolHoldFrame(boolean placing, boolean placed) {
        if (placing) pistolHoldFrame = placed;
    }

    static boolean pistolHoldFrame() {
        return pistolHoldFrame;
    }

    // frame before NTM's setupFirstPerson: camera-fixed, +X left, +Y up, +Z forward
    public static void cameraFrame(PoseStack pose) {
        CAMERA.set(pose.last().pose());
        haveCamera = true;
    }

    // gun frame after aiming, before the model kick
    public static void aimFrame(PoseStack pose) {
        AIMED.set(pose.last().pose());
    }

    // gun frame after the kick, right before NTM draws the model
    public static void gunFrame(PoseStack pose) {
        KICKED.set(pose.last().pose());
    }

    // the stand (ScopeSmokeStand): the gun's origin as drawn last, in the camera's frame and in the pose's own
    static Vector3f[] standGun() {
        return new Vector3f[] {new Matrix4f(CAMERA).invert().mul(KICKED).transformPosition(new Vector3f()), KICKED.transformPosition(new Vector3f())};
    }

    public static void part(PoseStack pose, HFRWavefrontObject model, int part) {
        if (!capturing) return;
        if (frameModel == null) {
            frameModel = model;
            FOLLOW_ID[0] = partByName(model, Config.armPartRight);
            FOLLOW_ID[1] = partByName(model, Config.armPartLeft);
            FOLLOW_ID[2] = partByName(model, ArmTimeline.carry(0).part());
            FOLLOW_ID[3] = partByName(model, ArmTimeline.carry(1).part());
        }
        if (model != frameModel) return;
        if (frameParts < MAX_PARTS) {
            if (FRAME_POSE[frameParts] == null) FRAME_POSE[frameParts] = new Matrix4f();
            FRAME_POSE[frameParts].set(pose.last().pose());
            FRAME_PART[frameParts++] = part;
        }
        for (int i = 0; i < 4; i++) {
            if (!HAVE_FOLLOW[i] && FOLLOW_ID[i] >= 0 && part == FOLLOW_ID[i]) {
                FOLLOW[i].set(pose.last().pose());
                HAVE_FOLLOW[i] = true;
            }
        }
    }

    private static int partByName(HFRWavefrontObject model, String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < model.groups.length; i++) if (model.groups[i].name.equalsIgnoreCase(name)) return i;
        return -1;
    }

    // the right-hand grip as drawn this frame, in the pose stack's space (after end())
    public static boolean rightGripRendered(Item held, Vector3f out) {
        if (!haveRel || relItem != held || geometry(held) == null) return false;
        float[] right = grips(held)[0];
        REL.transformPosition(right[0], right[1], right[2], out);
        KICKED.transformPosition(out);
        return true;
    }

    // the gun's two ends along its length as placed this frame without the model kick, in the pose stack's space
    public static boolean gunEndsAimed(Item held, Vector3f back, Vector3f front) {
        if (!haveRel || relItem != held || geometry(held) == null) return false;
        GunGeometry g = geometry(held);
        // KICKED·REL is the body as drawn, AIMED·REL the same before the kick
        AIMED.transformPosition(REL.transformPosition(0F, 0F, g.minZ, back));
        AIMED.transformPosition(REL.transformPosition(0F, 0F, g.maxZ, front));
        return true;
    }

    // the corners of the gun's cloud of vertices (GunGeometry.extremes) as placed this frame without the model kick, in
    // the pose stack's space; null while the gun is not known yet
    public static Vector3f[] gunExtremesAimed(Item held) {
        if (!haveRel || relItem != held || geometry(held) == null) return null;
        Vector3f[] in = geometry(held).extremes();
        Vector3f[] out = new Vector3f[in.length];
        for (int i = 0; i < in.length; i++) out[i] = AIMED.transformPosition(REL.transformPosition(in[i], new Vector3f()));
        return out;
    }

    // the middle of the gun's cloud of vertices (their mean across, halfway along) as placed this frame without the
    // model kick, in the pose stack's space
    public static boolean gunMiddleAimed(Item held, Vector3f out) {
        if (!haveRel || relItem != held || geometry(held) == null) return false;
        GunGeometry g = geometry(held);
        AIMED.transformPosition(REL.transformPosition(g.meanX, g.meanY, (g.minZ + g.maxZ) / 2F, out));
        return true;
    }

    // the same as drawn this frame
    public static boolean gunMiddleRendered(Item held, Vector3f out) {
        if (!haveRel || relItem != held || geometry(held) == null) return false;
        GunGeometry g = geometry(held);
        KICKED.transformPosition(REL.transformPosition(g.meanX, g.meanY, (g.minZ + g.maxZ) / 2F, out));
        return true;
    }

    // the gun's two ends along its length (model z min / max) as drawn this frame, in the pose stack's space
    public static boolean gunEndsRendered(Item held, Vector3f back, Vector3f front) {
        if (!haveRel || relItem != held || geometry(held) == null) return false;
        GunGeometry g = geometry(held);
        KICKED.transformPosition(REL.transformPosition(0F, 0F, g.minZ, back));
        KICKED.transformPosition(REL.transformPosition(0F, 0F, g.maxZ, front));
        return true;
    }

    // pivot (right-hand grip) and gun length in the gun frame, for the model kick
    public static float kickFrame(Item held, Vector3f pivot) {
        if (!haveRel || relItem != held || geometry(held) == null) return 0F;
        float[] right = grips(held)[0];
        REL.transformPosition(right[0], right[1], right[2], pivot);
        GunGeometry g = geometry(held);
        Vector3f a = REL.transformPosition(0F, 0F, g.minZ, new Vector3f());
        Vector3f b = REL.transformPosition(0F, 0F, g.maxZ, new Vector3f());
        return a.distance(b);
    }

    public static void end(ItemStack stack, SubmitNodeCollector collector, int light, boolean drawArms) {
        capturing = false;
        geometry = variant();
        int body = -1;
        if (geometry != null) {
            // the body: the gun's biggest part (smaller parts may be drawn first, moved by an animation)
            for (int k = 0; k < frameParts && body < 0; k++) if (FRAME_PART[k] < 0 || FRAME_PART[k] == geometry.bodyPart) body = k;
        }
        if (body < 0) {
            haveRel = false;
            return;
        }
        BODY.set(FRAME_POSE[body]);
        if (!geometry.aligned()) {
            // how the renderer turns the model and places its parts, read while no animation moves them
            if (HbmAnimations.getRelevantAnim(0) == null) {
                Matrix4f rel = new Matrix4f(KICKED).invert().mul(BODY);
                org.joml.Matrix3f r = new org.joml.Matrix3f();
                rel.get3x3(r);
                Matrix4f toBody = new Matrix4f(BODY).invert();
                for (int k = 0; k < frameParts; k++) {
                    int p = FRAME_PART[k];
                    if (p >= 0 && geometry.partRest[p] == null) geometry.partRest[p] = new Matrix4f(toBody).mul(FRAME_POSE[k]);
                }
                geometry.align(r);
            }
            if (!geometry.aligned()) {
                haveRel = false;
                return;
            }
        }
        // model space -> the gun's aligned frame (a whole-quarter-turn rotation: its inverse is its transpose)
        Matrix4f fromAligned = new Matrix4f().set(new org.joml.Matrix3f(geometry.toAligned).transpose());
        FROM_ALIGNED.set(fromAligned);
        BODY.mul(fromAligned);
        for (int i = 0; i < 4; i++) if (HAVE_FOLLOW[i]) FOLLOW[i].mul(fromAligned);
        REL.set(KICKED).invert().mul(BODY);
        haveRel = true;
        relItem = item;
        if (measuring) {
            if (!haveCamera) return;
            ArmPoses.GunRest gr = GUN_REST.computeIfAbsent(geometry, k -> new ArmPoses.GunRest());
            Matrix4f aimedInv = new Matrix4f(AIMED).invert();
            gr.body.set(aimedInv).mul(BODY);
            for (int i = 0; i < 2; i++) gr.hold[i].set(aimedInv).mul(HAVE_FOLLOW[i] ? FOLLOW[i] : BODY);
            gr.valid = true;
            return;
        }
        if (drawArms && haveCamera && Config.arms) draw(collector, light);
    }

    // The variant matching the parts drawn this frame. A new variant is only taken while no animation
    // plays (animations may hide parts for a moment); during one the gun keeps its current variant.
    private static GunGeometry variant() {
        if (frameModel == null || frameParts == 0) return null;
        BitSet drawn = new BitSet();
        for (int k = 0; k < frameParts; k++) {
            if (FRAME_PART[k] < 0) drawn.set(0, frameModel.groups.length);
            else if (FRAME_PART[k] < frameModel.groups.length) drawn.set(FRAME_PART[k]);
        }
        List<GunGeometry> list = VARIANTS.computeIfAbsent(item, k -> new ArrayList<>());
        GunGeometry g = null;
        for (GunGeometry v : list) if (v.model == frameModel && v.parts.equals(drawn)) g = v;
        if (g == null && HbmAnimations.getRelevantAnim(0) == null && !drawn.isEmpty()) {
            g = new GunGeometry(frameModel, drawn, list.size());
            list.add(g);
        }
        if (g == null) g = CURRENT.get(item);
        if (g != null) CURRENT.put(item, g);
        return g;
    }

    private static float[][] grips(Item held) {
        if (gripVersion != Grips.version()) {
            GRIP_CACHE.clear();
            PROFILE_CACHE.clear();
            gripVersion = Grips.version();
        }
        return GRIP_CACHE.computeIfAbsent(geometry(held), geo -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(held).toString(), geo.missing);
            float[] r = g.rightAt() != null ? g.rightAt() : geo.grip(g.right());
            float[] l = g.leftAt() != null ? g.leftAt() : geo.grip(g.left());
            return new float[][] {r, l, geo.holdCenter(r), geo.holdCenter(l)};
        });
    }

    private static final int PROFILE_STEPS = 64;
    private static final Map<GunGeometry, float[][][]> PROFILE_CACHE = new IdentityHashMap<>();

    // the path each hand slides along: the gun's surface from stock to muzzle at that hand's x/y fractions
    private static float[][][] profiles(Item held) {
        grips(held); // keeps the caches in step with the grip file
        return PROFILE_CACHE.computeIfAbsent(geometry(held), geo -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(held).toString(), geo.missing);
            float[] r = g.rightAt() != null ? geo.fractions(g.rightAt()) : g.right();
            float[] l = g.leftAt() != null ? geo.fractions(g.leftAt()) : g.left();
            return new float[][][] {geo.profile(r[0], r[1], PROFILE_STEPS), geo.profile(l[0], l[1], PROFILE_STEPS)};
        });
    }

    public static boolean slim() {
        var player = Minecraft.getInstance().player;
        return player != null && player.getSkin().model() == PlayerModelType.SLIM;
    }

    private static void draw(SubmitNodeCollector collector, int light) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isInvisible()) return;
        float aiming = ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress)
                * ItemRenderWeaponBase.interp;
        float aimWeight = PistolHold.curve(aiming);
        float[][] grip = grips(item);
        Identifier skin = player.getSkin().body().texturePath();
        boolean slim = slim();
        boolean atRest = HbmAnimations.getRelevantAnim(0) == null && ModelRecoil.idle();
        ArmPoses.GunRest gr = GUN_REST.computeIfAbsent(geometry, k -> new ArmPoses.GunRest());
        Item held = item;
        if (!ArmPoses.begin(CAMERA, AIMED, BODY, FOLLOW, HAVE_FOLLOW, FROM_ALIGNED, gr, geometry, geometry.toAligned,
                h -> profiles(held)[h], Arms::partPose, grip, slim, aimWeight, atRest, pistolHoldFrame, debugArms)) return;
        ArmRest[] rests = ARM_REST.computeIfAbsent(geometry, k -> new ArmRest[] {new ArmRest(), new ArmRest()});
        for (int i = 0; i < 2; i++) {
            boolean right = i == 0;
            ModelPart[] parts = CUT.computeIfAbsent((right ? 1000 : 0) + (slim ? 100 : 0) + 12,
                    k -> cutArm(right, slim, 12));
            ModelPart arm = parts[0];
            ModelPart sleeve = parts[1];
            boolean sleeveShown = player.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
            int placed = ArmPoses.hand(i, rests[i]);
            if (placed == ArmPoses.HIDDEN) {
                ClientSmoke.armBranch(right, "hidden");
                if (TestLog.frames()) TestLog.c(-1, "arm_hidden", "right", right, "anim", HbmAnimations.getGunAnimation(0), "t", ArmTimeline.animMillis());
                if (debugArms) GunFeel.LOGGER.info("[armdebug] {} {} t={} hidden", right ? "R" : "L", HbmAnimations.getGunAnimation(0), fmt(ArmTimeline.animMillis()));
                continue;
            }
            if (placed == ArmPoses.DRAWN) submit(collector, light, arm, skin, ArmPoses.out, ArmPoses.outCrop, right, slim, sleeveShown, sleeve);
        }
    }

    // /gunfeel armdebug: every frame, how each arm was placed, to the log ("[armdebug]")
    public static boolean debugArms;

    // the branch's name alone (rest, path, carry, release, around, hold, magazine): the trace compares it exactly
    private static String branchName() {
        int space = ArmPoses.branch.indexOf(' ');
        return space < 0 ? ArmPoses.branch : ArmPoses.branch.substring(0, space);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt(Vector3f v) {
        return String.format(java.util.Locale.ROOT, "(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }

    // the part's pose this frame (in the gun's aligned frame), or null when it was not drawn
    static List<String> editorParts() {
        if (frameModel == null) return List.of();
        var names = new ArrayList<String>();
        for (var group : frameModel.groups) names.add(group.name);
        return names;
    }

    // the arm editor: the part was drawn in the last frame (a path key on it moves the hand); no frame yet = drawn
    static boolean editorPartDrawn(String name, int nth) {
        return frameModel == null || partPose(name, nth) != null;
    }

    private static Matrix4f partPose(String name, int nth) {
        int id = partByName(frameModel, name);
        if (id < 0) return null;
        int found = -1, n = 0;
        for (int k = 0; k < frameParts; k++) {
            if (FRAME_PART[k] != id) continue;
            if (nth < 0 || n == nth) found = k;
            if (n++ == nth) break;
        }
        return found < 0 ? null : new Matrix4f(FRAME_POSE[found]).mul(FROM_ALIGNED);
    }

    // /gunfeel armdebug: where the drawn arm lands on the screen, projected the way the hands are drawn
    // (70 deg hud fov as NTM adjusts it, the window's aspect). hand / cut = middle of the hand end / of the
    // cut (shoulder) end, screen fractions from the top left corner, "out" = outside the window or behind
    // the eye; vis = share of points over the arm's surface inside the window. Not hidden by the gun.
    private static String onScreen(Matrix4f m, int crop, boolean right, boolean slim) {
        var mc = Minecraft.getInstance();
        float fov = com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase.adjustHudFov(70F);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f clip = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05F, 100F)
                .mul(new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack())).mul(m);
        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, half = (slim ? 1.5F : 2F) / 16F;
        float top = 12F / 16F, cut = (12F - crop) / 16F;
        int in = 0, all = 0;
        for (int k = 0; k <= 8; k++) {
            float y = cut + (top - cut) * k / 8F;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                all++;
                if (ArmMath.screenPoint(clip, cx + a * half, y, b * 2F / 16F) != null) in++;
            }
        }
        return "screen hand=" + screenText(clip, cx, top) + " cut=" + screenText(clip, cx, cut) + " vis=" + Math.round(100F * in / all) + "%";
    }

    private static String screenText(Matrix4f clip, float x, float y) {
        org.joml.Vector4f v = clip.transform(new org.joml.Vector4f(x, y, 0F, 1F));
        if (v.w <= 1e-4F) return "behind";
        float sx = (v.x / v.w + 1F) * 0.5F, sy = (1F - v.y / v.w) * 0.5F;
        boolean out = sx < 0F || sx > 1F || sy < 0F || sy > 1F;
        return String.format(java.util.Locale.ROOT, "(%.2f %.2f)%s", sx, sy, out ? "out" : "");
    }

    private static void submit(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, Matrix4f m,
            int crop, boolean right, boolean slim, boolean sleeveShown, ModelPart sleeve) {
        int hand = right ? 0 : 1;
        var holder = Minecraft.getInstance().player;
        ArmPoses.finish(m, crop, right, slim, holder == null ? null : holder.getMainHandItem().getItem());
        m = ArmPoses.out;
        crop = ArmPoses.outCrop;
        final int shown = crop;
        ClientSmoke.armBranch(right, branchName());
        if (TestLog.frames()) {
            // three points of the drawn arm in camera space, for comparing in blocks: the grip (the hand end's middle,
            // moved to the contact point while a path holds it), the wrist (4 pixels back) and the elbow (the far
            // end of what is drawn, where the arm is cut; the shoulder when it is whole)
            HbmAnimations.Animation playing = HbmAnimations.getRelevantAnim(0);
            Matrix4f inCamera = new Matrix4f(CAMERA).invert().mul(m);
            Vector3f grip = ArmContact.pivot(right, slim, ArmPoses.pathContact[hand]);
            Vector3f axis = ArmContact.pivot(right, slim, new Vector3f());
            Vector3f wrist = inCamera.transformPosition(new Vector3f(axis.x, 8F / 16F, axis.z));
            Vector3f elbow = inCamera.transformPosition(new Vector3f(axis.x, (12 - crop) / 16F, axis.z));
            TestLog.c(-1, "arm", "right", right, "anim", playing == null ? "none" : HbmAnimations.getGunAnimation(0),
                    "t", playing == null ? -1L : com.hbm.util.GameTime.now() - playing.startMillis, "crop", crop, "slim", slim,
                    "branch", branchName(), "contact", new Vector3f(ArmPoses.pathContact[hand]),
                    "grip", inCamera.transformPosition(grip), "wrist", wrist, "elbow", elbow, "m", inCamera);
        }
        if (debugArms) {
            GunAnimation type = HbmAnimations.getGunAnimation(0);
            Vector3f origin = m.transformPosition(new Vector3f());
            Vector3f along = m.transformDirection(new Vector3f(0F, 1F, 0F));
            GunFeel.LOGGER.info("[armdebug] {} {} t={} {} | {} | origin={} up={} crop={} | {}", right ? "R" : "L",
                    HbmAnimations.getRelevantAnim(0) == null ? "-" : type, fmt(ArmTimeline.animMillis()), ArmPoses.branch, right ? "" : ArmPoses.branchNote,
                    fmt(origin), fmt(along), shown, onScreen(m, shown, right, slim));
            ArmPoses.branchNote = "";
        }
        PoseStack pose = new PoseStack();
        pose.mulPose(m);
        if (shown < 12) {
            ModelPart[] cut = CUT.computeIfAbsent((right ? 1000 : 0) + (slim ? 100 : 0) + shown, k -> cutArm(right, slim, shown));
            arm = cut[0];
            sleeve = cut[1];
        }
        arm.resetPose();
        arm.visible = true;
        // armArmor: the skin's second layer as the third-person model has it (NTM's suits hide it)
        Boolean modelSleeve = ArmLayers.sleeve(right);
        sleeve.visible = modelSleeve != null ? modelSleeve : sleeveShown;
        collector.submitModelPart(arm, pose, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);
        ArmLayers.submit(collector, light, m, right, shown);
    }

    private static final Map<Integer, ModelPart[]> CUT = new HashMap<>();

    // The hand end of the player's arm (and sleeve): its last `n` of 12 pixels, textured as on the whole arm.
    // Same boxes, texture offsets and pivot as PlayerModel's arms; the sides take their texture from the hand
    // end (texture offset moved by 12 - n), the end caps from the usual place.
    private static ModelPart[] cutArm(boolean right, boolean slim, int n) {
        float w = slim ? 3F : 4F;
        float x0 = right ? (slim ? -2F : -3F) : -1F;
        float y0 = -2F + (12 - n);
        int au = right ? 40 : 32, av = right ? 16 : 48, su = right ? 40 : 48, sv = right ? 32 : 48;
        java.util.Set<net.minecraft.core.Direction> sides = java.util.EnumSet.of(net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST);
        java.util.Set<net.minecraft.core.Direction> caps = java.util.EnumSet.of(net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN);
        ModelPart sleeve = new ModelPart(List.of(
                new ModelPart.Cube(su, sv + 12 - n, x0, y0, -2F, w, n, 4F, 0.25F, 0.25F, 0.25F, false, 64F, 64F, sides),
                new ModelPart.Cube(su, sv, x0, y0, -2F, w, n, 4F, 0.25F, 0.25F, 0.25F, false, 64F, 64F, caps)), Map.of());
        ModelPart arm = new ModelPart(List.of(
                new ModelPart.Cube(au, av + 12 - n, x0, y0, -2F, w, n, 4F, 0F, 0F, 0F, false, 64F, 64F, sides),
                new ModelPart.Cube(au, av, x0, y0, -2F, w, n, 4F, 0F, 0F, 0F, false, 64F, 64F, caps)), Map.of("sleeve", sleeve));
        arm.setInitialPose(net.minecraft.client.model.geom.PartPose.offset(right ? -5F : 5F, 2F, 0F));
        sleeve.setInitialPose(net.minecraft.client.model.geom.PartPose.ZERO);
        return new ModelPart[] {arm, sleeve};
    }
}
