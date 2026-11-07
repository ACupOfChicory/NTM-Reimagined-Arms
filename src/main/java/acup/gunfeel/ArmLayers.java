package acup.gunfeel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.mixin.mc.LivingEntityRendererAccess;
import acup.gunfeel.mixin.mc.ModelPartAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.SpinAttackEffectLayer;
import net.minecraft.client.renderer.entity.layers.StuckInBodyLayer;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

// armArmor: what the player model wears on its arms in third person, drawn on the arms holding a gun. The player
// renderer's own layers are run (vanilla armour with its dye, trims and glint, NTM's suits, other mods' layers), with
// the arm posed as our arm box: the same pose, thickness and cut. Whatever they submit is kept only where it sits on
// that arm: a model with an arm draws that arm alone; any other geometry is kept near the arm, the rest of the
// player model having been moved far away for the moment (NTM's female body layer draws head and body whatever is
// hidden). Where our arm is cut at the shoulder end, the layers are cut there too.
public final class ArmLayers {
    // pixels the parts other than the drawn arm are moved by while the layers run; geometry beyond KEEP (in the arm's
    // frame, blocks before its scale) is thrown away
    private static final float AWAY = 16000F;
    private static final float KEEP = 64F;

    private static AvatarRenderer<AbstractClientPlayer> renderer;
    private static AvatarRenderState state;
    private static boolean fresh, prepared, failed;
    // weak: a player left for the main menu does not keep its world
    private static java.lang.ref.WeakReference<LocalPlayer> owner = new java.lang.ref.WeakReference<>(null);
    private static final boolean[] SLEEVE = new boolean[2];
    private static final Set<Class<?>> WARNED = new java.util.HashSet<>();
    // leave() runs on the network thread: it only asks, and the render thread lets the failures go (settle) before it
    // reads them again, so a count it was writing does not come back into the next world
    private static volatile boolean left;

    private ArmLayers() {
    }

    // leaving a world: the player and its render state are let go (read again in the next world)
    public static void leave() {
        prepared = false;
        left = true;
        state = null;
        owner = new java.lang.ref.WeakReference<>(null);
    }

    // a new frame of the gun in hand: the player's render state is read again on first use
    public static void newFrame() {
        fresh = true;
        frame++;
        settle();
    }

    private static void settle() {
        if (!left) return;
        left = false;
        FAILED.clear();
        WARNED.clear();
    }

    // whether the skin's second layer shows on this arm the way the third-person model has it now (NTM hides it under
    // its suits); null = armArmor off or nothing read
    public static Boolean sleeve(boolean right) {
        return prepare() ? SLEEVE[right ? 0 : 1] : null;
    }

    private static boolean prepare() {
        if (!Config.armArmor || failed) return false;
        // read in this frame for this player (a new world brings a new one)
        if (!fresh) return prepared && owner.get() == Minecraft.getInstance().player;
        fresh = false;
        prepared = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        try {
            AvatarRenderer<AbstractClientPlayer> r = Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player);
            AvatarRenderState s = r.createRenderState(player, ItemRenderWeaponBase.interp);
            // the layers are drawn in the hand's frame: no outline (glowing), and none for a spectator (as AvatarRenderer)
            s.outlineColor = 0;
            if (s.isSpectator) return false;
            PlayerModel model = r.getModel();
            model.setupAnim(s);
            SLEEVE[0] = model.rightSleeve.visible;
            SLEEVE[1] = model.leftSleeve.visible;
            renderer = r;
            state = s;
            owner = new java.lang.ref.WeakReference<>(player);
            prepared = true;
        } catch (Throwable t) {
            failed = true;
            GunFeel.LOGGER.warn("armArmor: could not read the player's model, armour on the arms is off", t);
        }
        return prepared;
    }

    // the layers that never sit on an arm or must not be drawn there: the held item (the gun itself), arrows and
    // stingers stuck in the body, the cape, the head item, elytra, parrots, ears, the riptide swirl
    private static boolean skipped(RenderLayer<?, ?> layer) {
        return layer instanceof ItemInHandLayer || layer instanceof StuckInBodyLayer || layer instanceof CapeLayer
                || layer instanceof CustomHeadLayer || layer instanceof WingsLayer || layer instanceof ParrotOnShoulderLayer
                || layer instanceof Deadmau5EarsLayer || layer instanceof SpinAttackEffectLayer;
    }

    // m: the arm box's pose (Arms.submit; the arm part, offset as PlayerModel's, drawn in it); shown: pixels of the
    // arm drawn from the hand end (12 = whole)
    public static void submit(SubmitNodeCollector collector, int light, Matrix4f m, boolean right, int shown) {
        long t0 = System.nanoTime();
        try {
            submitLayers(collector, light, m, right, shown);
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    // the time spent here and in the drawing the layers left for later, since the last call (the stand's measure)
    static long nanos;

    static long takeNanos() {
        long n = nanos;
        nanos = 0L;
        return n;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void submitLayers(SubmitNodeCollector collector, int light, Matrix4f m, boolean right, int shown) {
        if (!prepare()) return;
        // leave() may let the state go on the network thread while the layers draw: they keep this frame's
        AvatarRenderState state = ArmLayers.state;
        if (state == null) return;
        Matrix4f toArm = new Matrix4f(m).invert();
        if (!Float.isFinite(toArm.m00() + toArm.m11() + toArm.m22() + toArm.m30() + toArm.m31() + toArm.m32())) return;
        Cut cut = new Cut(toArm, shown < 12 ? (12 - shown) / 16F : Float.NaN, right);
        PlayerModel parent = renderer.getModel();
        ModelPart arm = right ? parent.rightArm : parent.leftArm;
        List<ModelPart> parts = parent.allParts();
        boolean[] visible = new boolean[parts.size()];
        for (int i = 0; i < visible.length; i++) visible[i] = parts.get(i).visible;
        Map<String, ModelPart> top = ((ModelPartAccess) (Object) parent.root()).gunfeel$children();
        List<ModelPart> moved = new ArrayList<>(top.size());
        List<PartPose> movedPose = new ArrayList<>(top.size());
        PartPose armPose = null;
        try {
            // as LivingEntityRenderer does before its layers (NTM's mixins hide the skin's layers under its suits)
            parent.setupAnim(state);
            armPose = arm.storePose();
            arm.resetPose();
            for (ModelPart p : top.values()) {
                if (p == arm) continue;
                movedPose.add(p.storePose());
                moved.add(p);
                p.visible = false;
                p.y += AWAY;
            }
            arm.visible = true;
            SubmitNodeCollector only = (SubmitNodeCollector) wrap(collector, SubmitNodeCollector.class, cut);
            Object[] worn = worn(owner.get());
            for (RenderLayer layer : ((LivingEntityRendererAccess) renderer).gunfeel$layers()) {
                if (skipped(layer)) continue;
                layer(m, layer.getClass(), worn, pose -> layer.submit(pose, only, light, state, state.yRot, state.xRot));
            }
        } finally {
            for (int i = 0; i < moved.size(); i++) moved.get(i).loadPose(movedPose.get(i));
            if (armPose != null) arm.loadPose(armPose);
            for (int i = 0; i < visible.length; i++) parts.get(i).visible = visible[i];
        }
    }

    // in how many frames in a row a layer may throw with the same armour on before the arms leave it out (until the
    // player leaves the world): a failure in the first frames, before the player's data came, is tried again, and a frame
    // it draws in starts the count anew. A frame counts once, whether the layer threw on one arm or on both
    static final int TRIES = 3;

    // the frames of the gun in hand (newFrame); the render thread's alone, as FAILED and WARNED
    private static long frame;

    // a layer's failures by the armour worn: the frames in a row it threw in and the last of them; cleared when the
    // player leaves the world
    private static final Map<Failed, Fails> FAILED = new java.util.HashMap<>();

    private record Fails(int frames, long last) {
    }

    // One layer on the arm at the arm's pose m. Each layer gets a stack of its own: one that throws between its own push
    // and pop, or pops more than it pushed, leaves the layers after it their pose all the same. False: left out, or it
    // threw.
    static boolean layer(Matrix4f m, Class<?> type, Object[] worn, java.util.function.Consumer<PoseStack> submit) {
        settle();
        Failed key = new Failed(type, worn);
        Fails fails = FAILED.get(key);
        if (fails != null && fails.frames() >= TRIES) return false;
        PoseStack pose = new PoseStack();
        pose.mulPose(m);
        try {
            submit.accept(pose);
            return true;
        } catch (Throwable t) {
            // this frame counted already (the other arm); the frame before it threw too; or anew
            int n = fails == null || fails.last() < frame - 1 ? 1 : fails.last() == frame ? fails.frames() : fails.frames() + 1;
            FAILED.put(key, new Fails(n, frame));
            if (WARNED.add(type)) GunFeel.LOGGER.warn("armArmor: layer {} failed on the arm", type.getName(), t);
            if (n == TRIES) GunFeel.LOGGER.warn("armArmor: layer {} failed on the arm in {} frames in a row with this armour on, left out of the arms until the world is left",
                    type.getName(), TRIES);
            return false;
        }
    }

    // a layer and the armour worn when it threw (the items in the four armour slots): the same layer with other armour
    // on is tried anew
    private record Failed(Class<?> layer, Object[] worn) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Failed f && f.layer == layer && java.util.Arrays.equals(f.worn, worn);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(layer) * 31 + java.util.Arrays.hashCode(worn);
        }
    }

    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOUR = {net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET};

    private static Object[] worn(LocalPlayer player) {
        Object[] out = new Object[ARMOUR.length];
        if (player == null) return out;
        for (int i = 0; i < ARMOUR.length; i++) {
            net.minecraft.world.item.ItemStack stack = player.getItemBySlot(ARMOUR[i]);
            out[i] = stack.isEmpty() ? null : stack.getItem();
        }
        return out;
    }

    // A collector that passes on only what lands on the arm: models drawn by their arm alone, other geometry through
    // the cut. Text, items, shadows and the rest are dropped.
    private static Object wrap(OrderedSubmitNodeCollector target, Class<?> type, Cut cut) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "ArmLayers.only";
                };
            }
            if (name.equals("order") && target instanceof SubmitNodeCollector c)
                return wrap(c.order((Integer) args[0]), OrderedSubmitNodeCollector.class, cut);
            if (name.equals("submitModel") && !method.isDefault() && args.length == 10) {
                model(target, cut, (Model<?>) args[0], args[1], (PoseStack) args[2], (RenderType) args[3], (Integer) args[4],
                        (Integer) args[5], (Integer) args[6], (TextureAtlasSprite) args[7]);
                return null;
            }
            if (name.equals("submitCustomGeometry") && args.length == 3) {
                // a part moved away (a helmet, the body): not built at all; what is left is cut vertex by vertex
                if (far(cut, ((PoseStack) args[0]).last().pose(), 0F, 0F, 0F)) return null;
                RenderType type0 = (RenderType) args[1];
                SubmitNodeCollector.CustomGeometryRenderer r = (SubmitNodeCollector.CustomGeometryRenderer) args[2];
                target.submitCustomGeometry((PoseStack) args[0], type0, (p, buffer) -> cut.run(buffer, type0, b -> r.render(p, b)));
                return null;
            }
            // the other submitModel / submitModelPart forms end in the one above
            if (method.isDefault() && name.startsWith("submitModel")) return InvocationHandler.invokeDefault(proxy, method, args);
            return none(method.getReturnType());
        };
        return Proxy.newProxyInstance(ArmLayers.class.getClassLoader(), new Class<?>[] {type}, h);
    }

    // a point of a submitted pose beyond KEEP from the arm
    private static boolean far(Cut cut, Matrix4f pose, float x, float y, float z) {
        Vector4f at = new Vector4f(x, y, z, 1F);
        pose.transform(at);
        cut.toArm.transform(at);
        return !(Math.abs(at.x) <= KEEP && Math.abs(at.y) <= KEEP && Math.abs(at.z) <= KEEP);
    }

    private static Object none(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0F;
        if (t == double.class) return 0.0;
        return null;
    }

    // A model is drawn by its arm alone, as the arm box is: its other parts hidden, the arm in its first pose. One with
    // no arm (a part handed over on its own) goes through the cut whole.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void model(OrderedSubmitNodeCollector target, Cut cut, Model<?> model, Object modelState, PoseStack pose,
            RenderType type, int light, int overlay, int tint, TextureAtlasSprite sprite) {
        String armName = cut.right ? "right_arm" : "left_arm";
        boolean simple = model instanceof Model.Simple;
        if (!simple && !(model instanceof HumanoidModel<?>) && !model.root().hasChild(armName)) return;
        // a part handed over on its own is drawn later as it is then: whether it sits on the arm is read now (a part
        // of the player model other than the arm is hidden and away at this moment), and its pose kept
        PartPose kept = null;
        if (simple) {
            ModelPart part = model.root();
            if (!part.visible) return;
            if (far(cut, pose.last().pose(), part.x / 16F, part.y / 16F, part.z / 16F)) return;
            kept = part.storePose();
        }
        PartPose simplePose = kept;
        target.submitCustomGeometry(pose, type, (p, buffer) -> cut.run(buffer, type, b -> draw(model, modelState, simplePose,
                b, p, cut, armName, light, overlay, tint, sprite)));
    }

    // a model's drawing, run later in the batch flush: a failure there turns armArmor off instead of ending the game
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void draw(Model<?> model, Object modelState, PartPose simplePose, VertexConsumer b, PoseStack.Pose p, Cut cut,
            String armName, int light, int overlay, int tint, TextureAtlasSprite sprite) {
        if (failed) return;
        try {
            VertexConsumer out = sprite == null ? b : sprite.wrap(b);
            PoseStack at = new PoseStack();
            at.last().set(p);
            if (model instanceof Model.Simple) {
                ModelPart part = model.root();
                PartPose now = part.storePose();
                part.loadPose(simplePose);
                try {
                    model.renderToBuffer(at, out, light, overlay, tint);
                } finally {
                    part.loadPose(now);
                }
                return;
            }
            ((Model) model).setupAnim(modelState);
            // as vanilla draws a model: mods hide parts through NeoForge's hook
            var shown = net.neoforged.neoforge.client.ClientHooks.updateModelVisibility(model, modelState);
            ModelPart root = model.root();
            ModelPart arm = model instanceof HumanoidModel<?> hm ? (cut.right ? hm.rightArm : hm.leftArm) : root.getChild(armName);
            Map<String, ModelPart> top = ((ModelPartAccess) (Object) root).gunfeel$children();
            List<ModelPart> hidden = new ArrayList<>(top.size());
            for (ModelPart part : top.values()) {
                if (part != arm && part.visible) {
                    part.visible = false;
                    hidden.add(part);
                }
            }
            arm.resetPose();
            try {
                root.render(at, out, light, overlay, tint);
            } finally {
                for (ModelPart part : hidden) part.visible = true;
                net.neoforged.neoforge.client.ClientHooks.resetVisibility(shown);
            }
        } catch (Throwable t) {
            failed = true;
            GunFeel.LOGGER.warn("armArmor: {} failed on the arm, armour on the arms is off", model.getClass().getName(), t);
        }
    }

    // Keeps the primitives near the arm and cuts them at the arm's cut (y in the arm's frame below `y` dropped).
    static final class Cut {
        final Matrix4f toArm;
        final float y;
        final boolean right;

        Cut(Matrix4f toArm, float y, boolean right) {
            this.toArm = toArm;
            this.y = y;
            this.right = right;
        }

        void run(VertexConsumer buffer, RenderType type, java.util.function.Consumer<VertexConsumer> draw) {
            PrimitiveTopology mode = type.primitiveTopology();
            int n = mode == PrimitiveTopology.QUADS ? 4 : mode == PrimitiveTopology.TRIANGLES ? 3 : 0;
            if (n == 0 || failed) return;
            long t0 = System.nanoTime();
            try {
                Clip clip = new Clip(n);
                draw.accept(clip);
                // a model that failed half drawn (draw) sends nothing on: no part of it, no vertex left unfinished
                if (!failed) clip.flush(buffer, this, type);
            } catch (Throwable t) {
                // run in the batch flush: a failure turns armArmor off instead of ending the game
                failed = true;
                GunFeel.LOGGER.warn("armArmor: drawing on the arm failed, armour on the arms is off", t);
            }
            nanos += System.nanoTime() - t0;
        }
    }

    // The vertices as they come, then sent on primitive by primitive: dropped far from the arm, cut at its cut.
    static final class Clip implements VertexConsumer {
        static final int COLOR = 1, UV = 2, OVERLAY = 4, LIGHT = 8, NORMAL = 16;
        final int per;
        // x y z u v nx ny nz, then color, overlay, light, what was set
        float[] f = new float[8 * 64];
        int[] i = new int[4 * 64];
        int count = -1;
        int in, out;

        Clip(int per) {
            this.per = per;
        }

        private VertexConsumer start(float x, float y, float z) {
            count++;
            if (count * 8 + 8 > f.length) {
                f = java.util.Arrays.copyOf(f, f.length * 2);
                i = java.util.Arrays.copyOf(i, i.length * 2);
            }
            int b = count * 8;
            f[b] = x;
            f[b + 1] = y;
            f[b + 2] = z;
            for (int k = 3; k < 8; k++) f[b + k] = 0F;
            int c = count * 4;
            i[c] = -1;
            i[c + 1] = 0;
            i[c + 2] = 0;
            i[c + 3] = 0;
            return this;
        }

        private void set(int what) {
            if (count >= 0) i[count * 4 + 3] |= what;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return start(x, y, z);
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor((a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | b & 255);
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (count < 0) return this;
            i[count * 4] = argb;
            set(COLOR);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (count < 0) return this;
            f[count * 8 + 3] = u;
            f[count * 8 + 4] = v;
            set(UV);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (count < 0) return this;
            i[count * 4 + 1] = u & 0xFFFF | v << 16;
            set(OVERLAY);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (count < 0) return this;
            i[count * 4 + 2] = u & 0xFFFF | v << 16;
            set(LIGHT);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (count < 0) return this;
            f[count * 8 + 5] = x;
            f[count * 8 + 6] = y;
            f[count * 8 + 7] = z;
            set(NORMAL);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            start(x, y, z);
            int b = count * 8, c = count * 4;
            f[b + 3] = u;
            f[b + 4] = v;
            f[b + 5] = nx;
            f[b + 6] = ny;
            f[b + 7] = nz;
            i[c] = color;
            i[c + 1] = overlay;
            i[c + 2] = light;
            i[c + 3] = COLOR | UV | OVERLAY | LIGHT | NORMAL;
        }

        // the test log: what was sent on, in the arm's pixels (the arm box: x -3..1 or -2..1 on the right, -1..3 or
        // -1..2 on the left, y from the cut to 10, z -2..2)
        private boolean log;
        private Matrix4f toArm;
        private boolean right;
        private final float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        void flush(VertexConsumer to, Cut cut, RenderType type) {
            log = TestLog.frames();
            toArm = cut.toArm;
            right = cut.right;
            int total = count + 1;
            in = total;
            Vector4f p = new Vector4f();
            float[] side = new float[per];
            float[] poly = new float[8 * (per + 2)];
            int[] polyI = new int[4 * (per + 2)];
            for (int s = 0; s + per <= total; s += per) {
                boolean far = false, all = true, none = true;
                for (int k = 0; k < per; k++) {
                    int b = (s + k) * 8;
                    cut.toArm.transform(p.set(f[b], f[b + 1], f[b + 2], 1F));
                    if (Math.abs(p.x) > KEEP || Math.abs(p.y) > KEEP || Math.abs(p.z) > KEEP) far = true;
                    side[k] = Float.isNaN(cut.y) ? 1F : p.y - cut.y;
                    if (side[k] < 0F) all = false;
                    else none = false;
                }
                if (far || none) continue;
                if (all) {
                    for (int k = 0; k < per; k++) emit(to, f, i, s + k, p);
                    out += per;
                    continue;
                }
                // Sutherland-Hodgman against the cut plane, then back to this topology
                int m = 0;
                for (int k = 0; k < per; k++) {
                    int a = s + k, b = s + (k + 1) % per;
                    float da = side[k], db = side[(k + 1) % per];
                    if (da >= 0F) copy(a, poly, polyI, m++);
                    if ((da >= 0F) != (db >= 0F)) lerp(a, b, da / (da - db), poly, polyI, m++);
                }
                if (m < 3) continue;
                if (per == 4) {
                    for (int k = 1; k + 1 < m; k += 2) {
                        int last = Math.min(k + 2, m - 1);
                        emit(to, poly, polyI, 0, p);
                        emit(to, poly, polyI, k, p);
                        emit(to, poly, polyI, k + 1, p);
                        emit(to, poly, polyI, last, p);
                        out += 4;
                    }
                } else {
                    for (int k = 1; k + 1 < m; k++) {
                        emit(to, poly, polyI, 0, p);
                        emit(to, poly, polyI, k, p);
                        emit(to, poly, polyI, k + 1, p);
                        out += 3;
                    }
                }
            }
            if (log && out > 0) TestLog.c(-1, "arm_layer", "right", right, "type", type.toString(), "cut", Float.isNaN(cut.y) ? 12 : 12 - Math.round(cut.y * 16F),
                    "in", in, "out", out, "min", out == 0 ? null : new org.joml.Vector3f(box[0], box[1], box[2]),
                    "max", out == 0 ? null : new org.joml.Vector3f(box[3], box[4], box[5]));
        }

        private void copy(int from, float[] pf, int[] pi, int to) {
            System.arraycopy(f, from * 8, pf, to * 8, 8);
            System.arraycopy(i, from * 4, pi, to * 4, 4);
        }

        private void lerp(int a, int b, float t, float[] pf, int[] pi, int to) {
            for (int k = 0; k < 8; k++) pf[to * 8 + k] = f[a * 8 + k] + (f[b * 8 + k] - f[a * 8 + k]) * t;
            int ca = i[a * 4], cb = i[b * 4];
            int color = 0;
            for (int sh = 0; sh < 32; sh += 8) {
                int va = ca >>> sh & 255, vb = cb >>> sh & 255;
                color |= Math.round(va + (vb - va) * t) << sh;
            }
            pi[to * 4] = color;
            // overlay and light from the end that is kept
            int near = t < 0.5F ? a : b;
            pi[to * 4 + 1] = i[near * 4 + 1];
            pi[to * 4 + 2] = i[near * 4 + 2];
            pi[to * 4 + 3] = i[a * 4 + 3] & i[b * 4 + 3];
        }

        private void emit(VertexConsumer to, float[] pf, int[] pi, int v, Vector4f p) {
            int b = v * 8, c = v * 4, what = pi[c + 3];
            if (log) {
                toArm.transform(p.set(pf[b], pf[b + 1], pf[b + 2], 1F));
                // arm frame -> the arm part's pixels (the part sits at x -5 / 5, y 2)
                float x = p.x * 16F + (right ? 5F : -5F), y = p.y * 16F - 2F, z = p.z * 16F;
                box[0] = Math.min(box[0], x);
                box[1] = Math.min(box[1], y);
                box[2] = Math.min(box[2], z);
                box[3] = Math.max(box[3], x);
                box[4] = Math.max(box[4], y);
                box[5] = Math.max(box[5], z);
            }
            if (what == (COLOR | UV | OVERLAY | LIGHT | NORMAL)) {
                to.addVertex(pf[b], pf[b + 1], pf[b + 2], pi[c], pf[b + 3], pf[b + 4], pi[c + 1], pi[c + 2], pf[b + 5], pf[b + 6], pf[b + 7]);
                return;
            }
            VertexConsumer w = to.addVertex(pf[b], pf[b + 1], pf[b + 2]);
            if ((what & COLOR) != 0) w.setColor(pi[c]);
            if ((what & UV) != 0) w.setUv(pf[b + 3], pf[b + 4]);
            if ((what & OVERLAY) != 0) w.setUv1(pi[c + 1] & 0xFFFF, pi[c + 1] >>> 16);
            if ((what & LIGHT) != 0) w.setUv2(pi[c + 2] & 0xFFFF, pi[c + 2] >>> 16);
            if ((what & NORMAL) != 0) w.setNormal(pf[b + 5], pf[b + 6], pf[b + 7]);
        }
    }
}
