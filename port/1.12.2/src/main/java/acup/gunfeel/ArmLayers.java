package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import acup.gunfeel.mixins.early.RenderLivingAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerArrow;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerDeadmau5Head;
import net.minecraft.client.renderer.entity.layers.LayerElytra;
import net.minecraft.client.renderer.entity.layers.LayerEntityOnShoulder;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

// armArmor: what the player model wears on its arms in third person, drawn on the arms holding a gun. The player
// renderer's own layers are run (vanilla armour with its dye and glint, NTM's suits, other mods' layers), with the arm
// posed as our arm box: the same pose, thickness and cut. Whatever they draw is kept only where it sits on that arm.
//
// port: 26.2's ArmLayers (X510, 7471717) for 1.12.2's immediate GL. 26.2 hands the layers a collector that keeps the
// arm's geometry vertex by vertex; 1.12.2's layers draw straight to GL (display lists, NTM's OBJ and VBO parts), so:
// - the pose: the player model (RenderPlayer's ModelPlayer) gets the arm where our arm box hangs, (-5, 2, 0) or
//   (5, 2, 0), unturned (slim arms too: 1.12.2's slim model hangs them at y 2.5, our box at 26.2's 2.0), and its other
//   parts moved AWAY pixels down. NTM's suits (ModelArmorBase) copy their pose from that model themselves; every other
//   ModelBiped a layer draws copies it at the end of its setRotationAngles (MixinModelBipedPose), as 26.2's armour
//   models follow the parent model's pose;
// - the cut: two GL clip planes in the arm's frame, one at the arm's cut (what lies toward the shoulder is not drawn)
//   and one KEEP blocks out, past which the moved parts are;
// - the sneak: ModelBiped.render and NTM's ModelArmorBase.render lower the whole model 0.2 blocks while the entity
//   sneaks, which 26.2's arm pose has no counterpart of (its crouch moves parts, the arm's pose is reset): the player's
//   sneak key reads as up while the layers run.
// The stand's numbers (the box of what is drawn, in the arm's pixels) come from a second run in GL's feedback mode,
// only while the trace log takes frames.
public final class ArmLayers {
    // pixels the player model's parts other than the drawn arm are moved by while the layers run (10000 blocks: past the
    // hand's far plane at any view distance, should a driver ignore clip planes); geometry beyond KEEP blocks in the
    // arm's frame is not drawn
    static final float AWAY = 160000F;
    static final float KEEP = 64F;

    private static RenderPlayer renderer;
    private static boolean fresh, prepared, failed;
    // weak: a player left for the main menu does not keep its world
    private static volatile WeakReference<EntityPlayerSP> owner = new WeakReference<>(null);
    private static final boolean[] SLEEVE = new boolean[2];
    // leave() runs on the network thread
    private static final Set<Class<?>> WARNED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    // while the layers run on an arm: the player model and its pose for that arm (MixinModelBipedPose)
    private static ModelBiped posed;
    private static Pose posedPose;
    private static final FloatBuffer MATRIX = floats(16);
    private static final java.nio.DoubleBuffer PLANE = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();

    private ArmLayers() {
    }

    // leaving a world (FML's ClientDisconnectionFromServerEvent; 26.2: LoggingOut): the player is let go
    public static void leave() {
        prepared = false;
        FAILED.clear();
        WARNED.clear();
        owner = new WeakReference<>(null);
    }

    // a new frame of the gun in hand (Arms.begin): the player is read again on first use
    public static void newFrame() {
        fresh = true;
    }

    // whether the skin's second layer shows on this arm the way the third-person model has it now (NTM hides it under
    // its suits); null = armArmor off or nothing read
    public static Boolean sleeve(boolean right) {
        return prepare() ? SLEEVE[right ? 0 : 1] : null;
    }

    private static boolean prepare() {
        if (!Config.armArmor || failed) return false;
        Minecraft mc = Minecraft.getMinecraft();
        // read in this frame for this player (a new world brings a new one)
        if (!fresh) return prepared && owner.get() == mc.player;
        fresh = false;
        prepared = false;
        EntityPlayerSP player = mc.player;
        if (player == null) return false;
        try {
            Render<?> r = mc.getRenderManager().getEntityRenderObject(player);
            // none for a spectator, as RenderLivingBase draws no layers on one
            if (!(r instanceof RenderPlayer) || player.isSpectator()) return false;
            SLEEVE[0] = sleeveShown(player, true);
            SLEEVE[1] = sleeveShown(player, false);
            renderer = (RenderPlayer) r;
            owner = new WeakReference<>(player);
            prepared = true;
        } catch (Throwable t) {
            failed = true;
            GunFeel.LOGGER.warn("armArmor: could not read the player's model, armour on the arms is off", t);
        }
        return prepared;
    }

    // port: 26.2's NTM hides the sleeves under its suits in the player model's setupAnim; NTM CE does it before each
    // third-person render (ModEventHandlerClient.preRenderLiving: an ArmorFSB chestplate hides both), so the rule is
    // read here: the player's Skin Customization (PlayerSkins.sleeve) and no NTM suit's chestplate
    static boolean sleeveShown(EntityPlayer player, boolean right) {
        if (!PlayerSkins.sleeve(player, right)) return false;
        ItemStack chest = player == null ? ItemStack.EMPTY : player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
        return !suit(chest);
    }

    static boolean suit(ItemStack chest) {
        return chest != null && !chest.isEmpty() && chest.getItem() instanceof com.hbm.items.gear.ArmorFSB;
    }

    // the layers that never sit on an arm or must not be drawn there: the held item (the gun itself), arrows stuck in
    // the body, the cape, the head item, elytra, parrots, ears, NTM's jetpack (drawn from the model's root on the back)
    static boolean skipped(LayerRenderer<?> layer) {
        return layer instanceof LayerHeldItem || layer instanceof LayerArrow || layer instanceof LayerCape
                || layer instanceof LayerCustomHead || layer instanceof LayerElytra || layer instanceof LayerEntityOnShoulder
                || layer instanceof LayerDeadmau5Head || layer.getClass().getName().equals("com.hbm.handler.JetpackHandler$JetpackLayer");
    }

    // m: the arm box's frame (Arms.drawBox: the arm part, offset as ModelPlayer's, drawn in it); shown: pixels of the
    // arm drawn from the hand end (12 = whole)
    public static void submit(Matrix4f m, boolean right, int shown) {
        long t0 = System.nanoTime();
        measured = 0L;
        try {
            draw(m, right, shown);
        } catch (Throwable t) {
            // the frame goes on without armour on the arms, for the rest of the game
            failed = true;
            GunFeel.LOGGER.warn("armArmor: could not draw on the arms, armour on the arms is off", t);
        } finally {
            nanos += System.nanoTime() - t0 - measured;
        }
    }

    // the time spent here since the last call, the stand's own feedback run left out (the stand's measure)
    static long nanos;
    private static long measured;

    static long takeNanos() {
        long n = nanos;
        nanos = 0L;
        return n;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void draw(Matrix4f m, boolean right, int shown) {
        if (!prepare()) return;
        EntityPlayerSP player = owner.get();
        if (player == null) return;
        if (!Float.isFinite(m.m00() + m.m11() + m.m22() + m.m30() + m.m31() + m.m32()) || !Float.isFinite(m.determinant())
                || m.determinant() == 0F) return;
        ModelBiped parent = renderer.getMainModel();
        List<LayerRenderer> layers = (List) ((RenderLivingAccess) renderer).gunfeel$layers();
        float cut = shown < 12 ? (12 - shown) / 16F : Float.NaN;
        Pose saved = new Pose(parent);
        // RenderLivingBase and RenderPlayer set these on each third-person render; in first person the player model
        // may never have been drawn, and ModelBase starts as a child (a layer's model then takes the half-sized child
        // body from it, ModelBiped.render)
        boolean child = parent.isChild, sneakModel = parent.isSneak, riding = parent.isRiding;
        float swing = parent.swingProgress;
        GlSaved gl = new GlSaved();
        Plane[] clips = {new Plane(GL11.GL_CLIP_PLANE0), new Plane(GL11.GL_CLIP_PLANE1)};
        boolean sneak = player.movementInput != null && player.movementInput.sneak;
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        GL11.glPushMatrix();
        try {
            parent.isChild = false;
            parent.isSneak = false;
            parent.isRiding = false;
            parent.swingProgress = 0F;
            pose(parent, right);
            posedPose = new Pose(parent);
            posed = parent;
            if (sneak) player.movementInput.sneak = false;
            MATRIX.clear();
            MATRIX.put(m.get(new float[16])).flip();
            GL11.glLoadMatrix(MATRIX);
            planes(cut);
            gl.layerState();
            float partial = Minecraft.getMinecraft().getRenderPartialTicks();
            run(layers, player, partial, false);
            if (TestLog.frames()) {
                long t0 = System.nanoTime();
                measure(layers, player, partial, m, right, shown);
                measured = System.nanoTime() - t0;
            }
        } finally {
            posed = null;
            posedPose = null;
            unfollow();
            if (sneak) player.movementInput.sneak = true;
            saved.load(parent);
            parent.isChild = child;
            parent.isSneak = sneakModel;
            parent.isRiding = riding;
            parent.swingProgress = swing;
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            for (Plane c : clips) c.restore();
            popTo(depth);
            gl.restore();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void run(List<LayerRenderer> layers, EntityPlayerSP player, float partial, boolean marked) {
        float age = player.ticksExisted + partial;
        Object[] worn = worn(player);
        GlSaved state = null;
        for (int k = 0; k < layers.size(); k++) {
            LayerRenderer layer = layers.get(k);
            if (skipped(layer)) continue;
            Failed key = new Failed(layer.getClass(), worn);
            Integer fails = FAILED.get(key);
            if (fails != null && fails >= TRIES) continue;
            if (marked) Feedback.layer(k);
            // the layers' state as draw set it, for after one that throws
            if (state == null) state = new GlSaved();
            int modelview = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH), projection = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH),
                    texture = GL11.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH), attrib = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
            GL11.glPushMatrix();
            boolean threw = false;
            try {
                // as RenderLivingBase.renderLayers, at the model's rest: the pose is ours (MixinModelBipedPose)
                layer.doRenderLayer(player, 0F, 0F, partial, age, 0F, 0F, 0.0625F);
            } catch (Throwable t) {
                threw = true;
                failed(key, fails, t);
            } finally {
                // a layer may leave another matrix stack current, and one that throws between its own push and pop
                // (NTM's suits push at the start of render) leaves matrices on the stacks: each depth is put back
                popTo(GL11.GL_TEXTURE, GL11.GL_TEXTURE_STACK_DEPTH, texture);
                popTo(GL11.GL_PROJECTION, GL11.GL_PROJECTION_STACK_DEPTH, projection);
                popTo(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_STACK_DEPTH, modelview);
                for (int n = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH); n > attrib; n--) GL11.glPopAttrib();
                if (threw) {
                    // a buffer it began and never drew would make every later begin in the game throw
                    try {
                        net.minecraft.client.renderer.Tessellator.getInstance().getBuffer().finishDrawing();
                    } catch (IllegalStateException notBuilding) {
                        // none begun
                    }
                    // what it changed of the state is not left to the layers after it
                    state.restore();
                }
                GlStateManager.color(1F, 1F, 1F, 1F);
            }
        }
    }

    // a stack back to `depth` matrices, that stack current after
    private static void popTo(int mode, int depthOf, int depth) {
        GL11.glMatrixMode(mode);
        for (int n = GL11.glGetInteger(depthOf); n > depth; n--) GL11.glPopMatrix();
    }

    // the modelview stack back to `depth` matrices
    private static void popTo(int depth) {
        popTo(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_STACK_DEPTH, depth);
    }

    // how often a layer may throw with the same armour on before the arms leave it out (until the player leaves the
    // world): a failure in the first frames, before the player's data came, is tried again
    static final int TRIES = 3;

    private static void failed(Failed key, Integer before, Throwable t) {
        int fails = before == null ? 1 : before + 1;
        FAILED.put(key, fails);
        if (WARNED.add(key.layer)) GunFeel.LOGGER.warn("armArmor: layer {} failed on the arm", key.layer.getName(), t);
        if (fails == TRIES) GunFeel.LOGGER.warn("armArmor: layer {} failed on the arm {} times with this armour on, left out of the arms until the world is left",
                key.layer.getName(), TRIES);
    }

    // a layer and the armour worn when it threw (the items in the four armour slots): the same layer with other armour
    // on is tried anew
    private static final class Failed {
        final Class<?> layer;
        final Object[] worn;

        Failed(Class<?> layer, Object[] worn) {
            this.layer = layer;
            this.worn = worn;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Failed) || ((Failed) o).layer != layer) return false;
            for (int i = 0; i < worn.length; i++) if (((Failed) o).worn[i] != worn[i]) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(layer);
            for (Object w : worn) h = h * 31 + System.identityHashCode(w);
            return h;
        }
    }

    // a layer's failures by the armour worn; cleared when the player leaves the world
    private static final Map<Failed, Integer> FAILED = new java.util.concurrent.ConcurrentHashMap<>();

    private static final net.minecraft.inventory.EntityEquipmentSlot[] ARMOUR = {net.minecraft.inventory.EntityEquipmentSlot.HEAD,
            net.minecraft.inventory.EntityEquipmentSlot.CHEST, net.minecraft.inventory.EntityEquipmentSlot.LEGS,
            net.minecraft.inventory.EntityEquipmentSlot.FEET};

    private static Object[] worn(EntityPlayerSP player) {
        Object[] out = new Object[ARMOUR.length];
        for (int i = 0; i < ARMOUR.length; i++) {
            ItemStack stack = player.getItemStackFromSlot(ARMOUR[i]);
            out[i] = stack.isEmpty() ? null : stack.getItem();
        }
        return out;
    }

    // the plane at the arm's cut (y below `cut` in the arm's frame not drawn) and the one KEEP out (past it the moved
    // parts); glClipPlane takes them in the frame now loaded, the arm's
    private static void planes(float cut) {
        clip(GL11.GL_CLIP_PLANE0, keepPlane());
        if (Float.isNaN(cut)) GL11.glDisable(GL11.GL_CLIP_PLANE1);
        else clip(GL11.GL_CLIP_PLANE1, cutPlane(cut));
    }

    private static void clip(int plane, double[] eq) {
        PLANE.clear();
        PLANE.put(eq).flip();
        GL11.glClipPlane(plane, PLANE);
        GL11.glEnable(plane);
    }

    // a clip plane as it was before the layers (another mod may use one)
    private static final class Plane {
        final int plane;
        final boolean on;
        final double[] eq = new double[4];

        Plane(int plane) {
            this.plane = plane;
            on = GL11.glIsEnabled(plane);
            if (on) {
                java.nio.DoubleBuffer b = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
                GL11.glGetClipPlane(plane, b);
                b.get(eq);
            }
        }

        // glGetClipPlane gives the plane in eye coordinates: it is put back with an identity modelview
        void restore() {
            if (!on) {
                GL11.glDisable(plane);
                return;
            }
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            PLANE.clear();
            PLANE.put(eq).flip();
            GL11.glClipPlane(plane, PLANE);
            GL11.glPopMatrix();
            GL11.glEnable(plane);
        }
    }

    // a point (x, y, z) is drawn where a x + b y + c z + d >= 0
    static double[] cutPlane(float cut) {
        return new double[] {0.0, 1.0, 0.0, -cut};
    }

    static double[] keepPlane() {
        return new double[] {0.0, -1.0, 0.0, KEEP};
    }

    // The player model posed for one arm: the arm where Arms' arm box hangs (Arms.cutArm: x -5 / 5, y 2), unturned; the
    // other parts AWAY pixels down
    static void pose(ModelBiped model, boolean right) {
        ModelRenderer arm = right ? model.bipedRightArm : model.bipedLeftArm;
        for (ModelRenderer p : parts(model)) {
            if (p == arm) continue;
            p.rotationPointY += AWAY;
        }
        arm.rotationPointX = right ? -5F : 5F;
        arm.rotationPointY = 2F;
        arm.rotationPointZ = 0F;
        arm.rotateAngleX = arm.rotateAngleY = arm.rotateAngleZ = 0F;
    }

    // MixinModelBipedPose: a ModelBiped drawn by a layer on the arm takes the posed player model's pose at the end of its
    // setRotationAngles (ModelPlayer then copies it to its sleeves and jacket); the player model itself gets its pose
    // back
    //
    // The models a layer keeps (LayerBipedArmor's two, shared by every player) are given their own pose back after the
    // layers (unfollow): ModelBiped.setRotationAngles never sets the body's rotation point or the arms' y, so the moved
    // parts would stay AWAY in third person
    private static final Map<ModelBiped, Pose> FOLLOWED = new IdentityHashMap<>();

    public static void follow(ModelBiped model) {
        ModelBiped from = posed;
        if (from == null) return;
        if (model == from) {
            posedPose.load(model);
            return;
        }
        if (!FOLLOWED.containsKey(model)) FOLLOWED.put(model, new Pose(model));
        ModelRenderer[] src = parts(from), dst = parts(model);
        for (int i = 0; i < src.length; i++) ModelBase.copyModelAngles(src[i], dst[i]);
    }

    static void unfollow() {
        for (Map.Entry<ModelBiped, Pose> e : FOLLOWED.entrySet()) e.getValue().load(e.getKey());
        FOLLOWED.clear();
    }

    static ModelRenderer[] parts(ModelBiped m) {
        return new ModelRenderer[] {m.bipedHead, m.bipedHeadwear, m.bipedBody, m.bipedRightArm, m.bipedLeftArm, m.bipedRightLeg, m.bipedLeftLeg};
    }

    // the seven parts' rotation points and angles
    static final class Pose {
        final float[] v = new float[7 * 6];

        Pose(ModelBiped m) {
            ModelRenderer[] p = parts(m);
            for (int i = 0; i < p.length; i++) {
                int b = i * 6;
                v[b] = p[i].rotationPointX;
                v[b + 1] = p[i].rotationPointY;
                v[b + 2] = p[i].rotationPointZ;
                v[b + 3] = p[i].rotateAngleX;
                v[b + 4] = p[i].rotateAngleY;
                v[b + 5] = p[i].rotateAngleZ;
            }
        }

        void load(ModelBiped m) {
            ModelRenderer[] p = parts(m);
            for (int i = 0; i < p.length; i++) {
                int b = i * 6;
                p[i].rotationPointX = v[b];
                p[i].rotationPointY = v[b + 1];
                p[i].rotationPointZ = v[b + 2];
                p[i].rotateAngleX = v[b + 3];
                p[i].rotateAngleY = v[b + 4];
                p[i].rotateAngleZ = v[b + 5];
            }
        }
    }

    // The GL state the layers may change, taken before them and put back after, through GlStateManager (its cache) and
    // straight to GL: what a layer changed past GlStateManager is put back too
    static final class GlSaved {
        final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), lighting = GL11.glIsEnabled(GL11.GL_LIGHTING),
                rescale = GL11.glIsEnabled(0x803A), normalize = GL11.glIsEnabled(GL11.GL_NORMALIZE),
                texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final int unit = GlStateManager.activeTextureUnit;
        final float lightX = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessX,
                lightY = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessY;
        final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC),
                shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL), bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        final float[] color = new float[4];

        GlSaved() {
            FloatBuffer c = floats(16);
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, c);
            c.get(color);
        }

        // as RenderPlayer and RenderLivingBase have it when they run the layers: no culling, blended as the skin
        // (PLAYER_SKIN), alpha-tested; normals renormalized, the arm being stretched unevenly
        void layerState() {
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableAlpha();
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableNormalize();
            GlStateManager.enableTexture2D();
            GlStateManager.depthMask(true);
            GlStateManager.color(1F, 1F, 1F, 1F);
        }

        void restore() {
            set(blend, GL11.GL_BLEND, GlStateManager::enableBlend, GlStateManager::disableBlend);
            set(cull, GL11.GL_CULL_FACE, GlStateManager::enableCull, GlStateManager::disableCull);
            set(alpha, GL11.GL_ALPHA_TEST, GlStateManager::enableAlpha, GlStateManager::disableAlpha);
            set(lighting, GL11.GL_LIGHTING, GlStateManager::enableLighting, GlStateManager::disableLighting);
            set(rescale, 0x803A, GlStateManager::enableRescaleNormal, GlStateManager::disableRescaleNormal);
            set(normalize, GL11.GL_NORMALIZE, GlStateManager::enableNormalize, GlStateManager::disableNormalize);
            // the unit first: the texture's state and binding are the unit's; the lightmap's coords (NTM's suits set
            // them, and put them back only when nothing failed)
            net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GlStateManager.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit + unit);
            set(texture, GL11.GL_TEXTURE_2D, GlStateManager::enableTexture2D, GlStateManager::disableTexture2D);
            set(depth, GL11.GL_DEPTH_TEST, GlStateManager::enableDepth, GlStateManager::disableDepth);
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
            GlStateManager.depthMask(depthMask);
            GL11.glDepthMask(depthMask);
            GlStateManager.depthFunc(depthFunc);
            GL11.glDepthFunc(depthFunc);
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
            GL11.glAlphaFunc(alphaFunc, alphaRef);
            GlStateManager.shadeModel(shade);
            GL11.glShadeModel(shade);
            GlStateManager.bindTexture(bound);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GlStateManager.color(color[0], color[1], color[2], color[3]);
            GL11.glColor4f(color[0], color[1], color[2], color[3]);
        }

        private static void set(boolean on, int cap, Runnable enable, Runnable disable) {
            (on ? enable : disable).run();
            if (on) GL11.glEnable(cap);
            else GL11.glDisable(cap);
        }
    }

    // ---- the stand's numbers: the layers again in GL's feedback mode, nothing drawn, the clipped primitives read back

    private static FloatBuffer feedback;

    @SuppressWarnings("rawtypes")
    private static void measure(List<LayerRenderer> layers, EntityPlayerSP player, float partial, Matrix4f m, boolean right, int shown) {
        if (feedback == null) feedback = floats(1 << 21);
        // feedback keeps only what is in the view volume; 26.2's numbers are the whole arm, the part off the screen
        // too: here a parallel view of everything within SEEN blocks of the eye, the clip planes still on
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glScalef(1F / SEEN, 1F / SEEN, 1F / SEEN);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            measureIn(layers, player, partial, m, right, shown);
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }

    static final float SEEN = 16F;

    @SuppressWarnings("rawtypes")
    private static void measureIn(List<LayerRenderer> layers, EntityPlayerSP player, float partial, Matrix4f m, boolean right, int shown) {
        FloatBuffer proj = floats(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
        java.nio.IntBuffer vp = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        float[] p = new float[16];
        proj.get(p);
        Matrix4f toArm = new Matrix4f().set(p).mul(m).invert();
        int[] viewport = {vp.get(0), vp.get(1), vp.get(2), vp.get(3)};
        feedback.clear();
        GL11.glFeedbackBuffer(GL11.GL_3D, feedback);
        GL11.glRenderMode(GL11.GL_FEEDBACK);
        Feedback.start();
        int n;
        try {
            run(layers, player, partial, true);
        } finally {
            Feedback.stop();
            n = GL11.glRenderMode(GL11.GL_RENDER);
        }
        if (n < 0) {
            TestLog.c(-1, "arm_layer_overflow", "right", right);
            return;
        }
        float[] values = new float[n];
        feedback.position(0);
        feedback.get(values, 0, n);
        int cut = shown;
        for (Map.Entry<String, Box> e : boxes(values, n, toArm, viewport, right, layers).entrySet()) {
            Box b = e.getValue();
            TestLog.c(-1, "arm_layer", "right", right, "type", e.getKey(), "cut", cut, "in", b.polygons, "out", b.vertices,
                    "min", new double[] {b.min[0], b.min[1], b.min[2]}, "max", new double[] {b.max[0], b.max[1], b.max[2]});
        }
    }

    // the box of what was drawn per layer and texture, in the arm part's pixels (the arm box: x -3..1 or -2..1 on the
    // right, -1..3 or -1..2 on the left, y from the cut to 10, z -2..2)
    static final class Box {
        final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int polygons, vertices;

        void add(float x, float y, float z) {
            vertices++;
            min[0] = Math.min(min[0], x);
            min[1] = Math.min(min[1], y);
            min[2] = Math.min(min[2], z);
            max[0] = Math.max(max[0], x);
            max[1] = Math.max(max[1], y);
            max[2] = Math.max(max[2], z);
        }
    }

    static final int PASS = 0x0700, POINT = 0x0701, LINE = 0x0702, POLYGON = 0x0703, BITMAP = 0x0704, DRAW_PIXEL = 0x0705,
            COPY_PIXEL = 0x0706, LINE_RESET = 0x0707;

    // GL_3D feedback values: tokens, each vertex window x, y, z. The pass-through values (Feedback) say which layer
    // (its index, from 0) and which texture (-1 - its index in Feedback.TEXTURES) the polygons after them come from.
    // toArm: (projection x the arm's frame) inverted.
    @SuppressWarnings("rawtypes")
    static Map<String, Box> boxes(float[] f, int n, Matrix4f toArm, int[] viewport, boolean right, List<LayerRenderer> layers) {
        Map<String, Box> out = new LinkedHashMap<>();
        String layer = "?", texture = "-";
        Vector4f v = new Vector4f();
        int i = 0;
        while (i < n) {
            int token = (int) f[i++];
            switch (token) {
                case PASS -> {
                    int k = (int) f[i++];
                    if (k >= 0) {
                        layer = layers == null || k >= layers.size() ? "layer" + k : layers.get(k).getClass().getSimpleName();
                        texture = "-";
                    } else {
                        int t = -1 - k;
                        texture = t < Feedback.TEXTURES.size() ? Feedback.TEXTURES.get(t) : "tex" + t;
                    }
                }
                case POLYGON -> {
                    int count = (int) f[i++];
                    Box b = out.computeIfAbsent(layer + " " + texture, s -> new Box());
                    b.polygons++;
                    for (int c = 0; c < count; c++, i += 3) {
                        armPixels(f[i], f[i + 1], f[i + 2], toArm, viewport, right, v);
                        b.add(v.x, v.y, v.z);
                    }
                }
                case POINT, BITMAP, DRAW_PIXEL, COPY_PIXEL -> i += 3;
                case LINE, LINE_RESET -> i += 6;
                default -> i = n;
            }
        }
        return out;
    }

    // a window point back in the arm part's pixels: (x, y) in the viewport, z in the depth range 0..1
    static Vector4f armPixels(float wx, float wy, float wz, Matrix4f toArm, int[] viewport, boolean right, Vector4f out) {
        out.set(2F * (wx - viewport[0]) / viewport[2] - 1F, 2F * (wy - viewport[1]) / viewport[3] - 1F, 2F * wz - 1F, 1F);
        toArm.transform(out);
        out.div(out.w);
        // the arm's frame -> the arm part's pixels (the part hangs at x -5 / 5, y 2)
        return out.set(out.x * 16F + (right ? 5F : -5F), out.y * 16F - 2F, out.z * 16F, 1F);
    }

    // the pass-through marks of the feedback run (MixinTextureBind marks each texture bound)
    public static final class Feedback {
        static final List<String> TEXTURES = new ArrayList<>();
        private static boolean on;

        static void start() {
            on = true;
        }

        static void stop() {
            on = false;
        }

        static void layer(int k) {
            GL11.glPassThrough(k);
        }

        public static void texture(ResourceLocation r) {
            if (!on) return;
            String s = String.valueOf(r);
            int t = TEXTURES.indexOf(s);
            if (t < 0) {
                t = TEXTURES.size();
                TEXTURES.add(s);
            }
            GL11.glPassThrough(-1 - t);
        }
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
