package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;

// armArmor: what the player model wears on its arms in third person, drawn on the arms holding a gun: the chestplate's
// model (vanilla armour with its dye, overlay and glint, NTM's suits, other mods' models from getArmorModel), with the
// arm posed as our arm box: the same pose, thickness and cut. Whatever it draws is kept only where it sits on that arm.
//
// port: 26.2's ArmLayers (X510, 7471717) after 1.12.2's (cdb2911). 1.7.10 has no render layers: RendererLivingEntity
// draws the armour itself, a renderPassModel per pass from RenderPlayer.shouldRenderPass (pass 1: the chestplate, the
// body and both arms; the other passes are the head, legs and feet), so:
// - the pass: pass 1 as shouldRenderPass and doRender have it (the model from ForgeHooksClient.getArmorModel, the
//   texture from RenderBiped.getArmorResource, the dye, then the overlay and the glint), done here.
//   RenderPlayerEvent.SetArmorModel is not posted: NTM draws its armour mods there (the gas mask on the head, the Tesla
//   coil and the jetpack on the back, all four armour pieces' mods in each pass), none of them on an arm (1.12.2 leaves
//   NTM's jetpack layer out too);
// - the pose: the player model (RenderPlayer.modelBipedMain) gets the arm where our arm box hangs, (-5, 2, 0) or
//   (5, 2, 0), unturned, and its other parts moved AWAY pixels down. NTM's suits (ModelArmorBase) copy their pose from
//   that model themselves (ModelRendererObj.copyRotationFrom, rotation points too); every other ModelBiped drawn copies
//   it at the end of its setRotationAngles (MixinModelBipedPose), as 26.2's armour models follow the parent model's pose;
// - the cut: two GL clip planes in the arm's frame, one at the arm's cut (what lies toward the shoulder is not drawn)
//   and one KEEP blocks out, past which the moved parts are.
// 1.7.10's ModelBiped.render does not lower a sneaking model (RenderPlayer moves the whole player), so the sneak key is
// left alone.
// The stand's numbers (the box of what is drawn, in the arm's pixels) come from a second run in GL's feedback mode,
// only while the trace log takes frames.
public final class ArmLayers {
    // pixels the player model's parts other than the drawn arm are moved by while the pass runs (10000 blocks: past the
    // hand's far plane at any view distance, should a driver ignore clip planes); geometry beyond KEEP blocks in the
    // arm's frame is not drawn
    static final float AWAY = 160000F;
    static final float KEEP = 64F;
    // RenderPlayer.shouldRenderPass's pass for the chestplate (armour slot 3 - pass = 2)
    static final int PASS_CHEST = 1;
    private static final ResourceLocation GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");

    private static RenderPlayer renderer;
    private static boolean fresh, prepared, failed;
    // weak: a player left for the main menu does not keep its world
    private static volatile WeakReference<EntityPlayer> owner = new WeakReference<>(null);
    private static final boolean[] SLEEVE = new boolean[2];
    // the chestplates whose model failed on the arm: warned once (until the world is left)
    private static final Set<Class<?>> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // while the pass runs on an arm: the player model and its pose for that arm (MixinModelBipedPose)
    private static ModelBiped posed;
    private static Pose posedPose;
    private static final FloatBuffer MATRIX = floats(16), COLOR = floats(16);
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

    // whether the skin's second layer (Et Futurum's 64 x 64 skins) shows on this arm the way the third-person model
    // has it; null = armArmor off or nothing read
    public static Boolean sleeve(boolean right) {
        return prepare() ? SLEEVE[right ? 0 : 1] : null;
    }

    private static boolean prepare() {
        if (!Config.armArmor || failed) return false;
        Minecraft mc = Minecraft.getMinecraft();
        // read in this frame for this player (a new world brings a new one)
        if (!fresh) return prepared && owner.get() == mc.thePlayer;
        fresh = false;
        prepared = false;
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return false;
        try {
            Render r = RenderManager.instance.getEntityRenderObject(player);
            if (!(r instanceof RenderPlayer)) return false;
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

    // port: 26.2's NTM hides the sleeves under its suits in the player model's setupAnim; 1.7.10 has no sleeves of its
    // own (Et Futurum's skins bring them, PlayerSkins) and NTM does not hide them, so main's rule is kept: no sleeve
    // under an NTM suit's chestplate (it would show through the suit's gaps), as 1.12.2 has it
    static boolean sleeveShown(EntityPlayer player, boolean right) {
        ItemStack chest = player == null ? null : player.inventory.armorItemInSlot(3 - PASS_CHEST);
        return !suit(chest) || leftOut(worn(player));
    }

    // how often in a row the chestplate may throw on the arm with the same armour on before the arms leave it out
    // (its sleeve then shows) until the player leaves the world: a failure in the first frames, before the player's
    // data came, is tried again, and one that draws in between starts the count anew (26.2 X570, per layer; 1.7.10
    // draws one layer, the chestplate, so the armour worn is the key)
    static final int TRIES = 3;

    // failures in a row by the armour worn; cleared when the player leaves the world
    private static final Map<List<Object>, Integer> FAILED = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean leftOut(Object[] worn) {
        Integer fails = FAILED.get(java.util.Arrays.asList(worn));
        return fails != null && fails >= TRIES;
    }

    // The chestplate on the arm with this armour on (type: its item's class, for the log). False: left out, or it
    // threw. The GL matrix stack is put back by draw
    static boolean layer(Object[] worn, Class<?> type, Runnable draw) {
        List<Object> key = java.util.Arrays.asList(worn.clone());
        Integer fails = FAILED.get(key);
        if (fails != null && fails >= TRIES) return false;
        try {
            draw.run();
            if (fails != null) FAILED.remove(key);
            return true;
        } catch (Throwable t) {
            int n = fails == null ? 1 : fails + 1;
            FAILED.put(key, n);
            if (WARNED.add(type)) GunFeel.LOGGER.warn("armArmor: {} failed on the arm", type.getName(), t);
            if (n == TRIES) GunFeel.LOGGER.warn("armArmor: {} failed on the arm {} times in a row with this armour on, left out of the arms until the world is left",
                    type.getName(), TRIES);
            return false;
        }
    }

    // the items in the four armour slots (null: empty)
    static Object[] worn(EntityPlayer player) {
        Object[] out = new Object[4];
        if (player == null) return out;
        for (int i = 0; i < 4; i++) {
            ItemStack stack = player.inventory.armorItemInSlot(i);
            out[i] = stack == null ? null : stack.getItem();
        }
        return out;
    }

    static boolean suit(ItemStack chest) {
        return chest != null && chest.getItem() instanceof com.hbm.items.armor.ArmorFSB;
    }

    // m: the arm box's frame (Arms.drawBox: the arm part, offset as ModelBiped's, drawn in it); shown: pixels of the
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

    private static void draw(Matrix4f m, boolean right, int shown) {
        if (!prepare()) return;
        EntityPlayer player = owner.get();
        if (player == null) return;
        ItemStack chest = player.inventory.armorItemInSlot(3 - PASS_CHEST);
        Object[] worn = worn(player);
        if (chest == null || !(chest.getItem() instanceof ItemArmor) || leftOut(worn)) return;
        if (!Float.isFinite(m.m00() + m.m11() + m.m22() + m.m30() + m.m31() + m.m32()) || !Float.isFinite(m.determinant())
                || m.determinant() == 0F) return;
        ModelBiped parent = renderer.modelBipedMain;
        float cut = shown < 12 ? (12 - shown) / 16F : Float.NaN;
        Pose saved = new Pose(parent);
        // RendererLivingEntity and RenderPlayer set these on each third-person render; in first person the player model
        // may never have been drawn, and ModelBase starts as a child (the armour model then takes the half-sized child
        // body from it, ModelBiped.render); aimedBow is NTM's (its guns aim like a bow in third person)
        boolean child = parent.isChild, sneak = parent.isSneak, riding = parent.isRiding, bow = parent.aimedBow;
        float swing = parent.onGround;
        GlSaved gl = new GlSaved();
        Plane[] clips = {new Plane(GL11.GL_CLIP_PLANE0), new Plane(GL11.GL_CLIP_PLANE1)};
        // a model that throws between its own push and pop (NTM's suits push at the start of render) leaves a matrix
        // on the stack, one that pops more than it pushed takes the caller's: the depth is put back as it was
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH), projection = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH),
                texture = GL11.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH), attrib = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH),
                list = GL11.glGetInteger(GL11.GL_LIST_INDEX);
        GL11.glPushMatrix();
        try {
            parent.isChild = false;
            parent.isSneak = false;
            parent.isRiding = false;
            parent.aimedBow = false;
            parent.onGround = 0F;
            pose(parent, right);
            posedPose = new Pose(parent);
            posed = parent;
            MATRIX.clear();
            m.get(MATRIX);
            GL11.glLoadMatrix(MATRIX);
            planes(cut);
            gl.passState();
            float partial = Tracers.framePartial(Minecraft.getMinecraft());
            if (!layer(worn, chest.getItem().getClass(), () -> run(player, chest, partial, false))) undo(list);
            if (TestLog.frames()) {
                long t0 = System.nanoTime();
                measure(player, chest, partial, m, right, shown);
                measured = System.nanoTime() - t0;
            }
        } finally {
            posed = null;
            posedPose = null;
            unfollow();
            saved.load(parent);
            parent.isChild = child;
            parent.isSneak = sneak;
            parent.isRiding = riding;
            parent.aimedBow = bow;
            parent.onGround = swing;
            // the other stacks a model may have pushed on and not popped (NTM's suits push at the start of render)
            popTo(GL11.GL_TEXTURE, GL11.GL_TEXTURE_STACK_DEPTH, texture);
            popTo(GL11.GL_PROJECTION, GL11.GL_PROJECTION_STACK_DEPTH, projection);
            for (int i = 0; i < 16 && GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH) > attrib; i++) GL11.glPopAttrib();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            for (Plane c : clips) c.restore();
            // at most the stack's size (32 in GL's minimum, 64 here): a model that threw while it compiled its display list
            // leaves GL recording, where a pop is recorded and not run, and the depth would never come down
            for (int i = 0; i < 64 && GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > depth; i++) GL11.glPopMatrix();
            // popped below it: the caller's matrices are lost, but its pops after no longer go under the stack's bottom
            for (int i = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH); i < depth; i++) GL11.glPushMatrix();
            gl.restore();
        }
    }

    // a stack back to `depth` matrices (at most 64 pops), that stack current after
    private static void popTo(int mode, int depthOf, int depth) {
        GL11.glMatrixMode(mode);
        for (int i = 0; i < 64 && GL11.glGetInteger(depthOf) > depth; i++) GL11.glPopMatrix();
    }

    // After a model that threw on the arm (layer false): a display list it was compiling (ModelRenderer.compileDisplayList)
    // is ended, so the rest is run and not recorded, and what it began on the Tessellator is dropped (1.12.2 4d04c9e)
    static void undo(int list) {
        if (list == 0 && GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0) GL11.glEndList();
        if (net.minecraft.client.renderer.Tessellator.instance instanceof TessellatorDrop d) d.gunfeel$drop();
    }

    // RenderPlayer.shouldRenderPass's pass 1 without its event, and RendererLivingEntity.doRender's draw of it: the
    // model, its overlay (a dyed piece's undyed part) and the glint, at the model's rest (the pose is ours). A failure
    // goes on to layer (counted) or measure (not)
    private static void run(EntityPlayer player, ItemStack chest, float partial, boolean marked) {
        ModelBiped model = null;
        try {
            ItemArmor armor = (ItemArmor) chest.getItem();
            Minecraft mc = Minecraft.getMinecraft();
            if (marked) Feedback.layer(0);
            mc.getTextureManager().bindTexture(RenderBiped.getArmorResource(player, chest, PASS_CHEST, null));
            ModelBiped vanilla = renderer.modelArmorChestplate;
            vanilla.bipedHead.showModel = false;
            vanilla.bipedHeadwear.showModel = false;
            vanilla.bipedBody.showModel = true;
            vanilla.bipedRightArm.showModel = true;
            vanilla.bipedLeftArm.showModel = true;
            vanilla.bipedRightLeg.showModel = false;
            vanilla.bipedLeftLeg.showModel = false;
            model = ForgeHooksClient.getArmorModel(player, chest, PASS_CHEST, vanilla);
            if (model == null) return;
            ModelBiped parent = renderer.modelBipedMain;
            model.onGround = parent.onGround;
            model.isRiding = parent.isRiding;
            model.isChild = parent.isChild;
            int color = armor.getColor(chest);
            if (color != -1) GL11.glColor3f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F);
            else GL11.glColor3f(1F, 1F, 1F);
            float age = player.ticksExisted + partial;
            model.setLivingAnimations(player, 0F, 0F, partial);
            model.render(player, 0F, 0F, age, 0F, 0F, 0.0625F);
            if (color != -1) {
                if (marked) Feedback.layer(1);
                mc.getTextureManager().bindTexture(RenderBiped.getArmorResource(player, chest, PASS_CHEST, "overlay"));
                GL11.glColor3f(1F, 1F, 1F);
                model.render(player, 0F, 0F, age, 0F, 0F, 0.0625F);
            }
            if (chest.isItemEnchanted()) {
                if (marked) Feedback.layer(2);
                glint(model, player, age);
            }
        } finally {
            // a model may leave another matrix stack current
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    // RendererLivingEntity.doRender's glint: two passes over the model with the glint texture moving, drawn only where
    // the armour is (depth EQUAL)
    private static void glint(ModelBiped model, EntityPlayer player, float age) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(GLINT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glColor4f(0.5F, 0.5F, 0.5F, 1F);
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glDepthMask(false);
        try {
            for (int k = 0; k < 2; k++) {
                GL11.glDisable(GL11.GL_LIGHTING);
                float c = 0.76F;
                GL11.glColor4f(0.5F * c, 0.25F * c, 0.8F * c, 1F);
                GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
                float shift = age * (0.001F + k * 0.003F) * 20F;
                float s = 0.33333334F;
                GL11.glScalef(s, s, s);
                GL11.glRotatef(30F - k * 60F, 0F, 0F, 1F);
                GL11.glTranslatef(0F, shift, 0F);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                model.render(player, 0F, 0F, age, 0F, 0F, 0.0625F);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
        }
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

    // a clip plane as it was before the pass (another mod may use one)
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

    // The player model posed for one arm: the arm where Arms' arm box hangs (Arms.drawBox: x -5 / 5, y 2), unturned; the
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

    // MixinModelBipedPose: a ModelBiped drawn on the arm takes the posed player model's pose at the end of its
    // setRotationAngles; the player model itself gets its pose back
    //
    // The models kept between draws (RenderPlayer's modelArmorChestplate, every player's; a mod's cached model) are
    // given their own pose back after the pass (unfollow): ModelBiped.setRotationAngles never sets the body's rotation
    // point or the arms' y, so the moved parts would stay AWAY in third person
    private static final Map<ModelBiped, Pose> FOLLOWED = new IdentityHashMap<>();

    public static void follow(ModelBiped model) {
        ModelBiped from = posed;
        if (from == null) return;
        if (model == from) {
            posedPose.load(model);
            return;
        }
        if (!FOLLOWED.containsKey(model)) FOLLOWED.put(model, new Pose(model));
        posedPose.load(model);
    }

    static void unfollow() {
        for (Map.Entry<ModelBiped, Pose> e : FOLLOWED.entrySet()) e.getValue().load(e.getKey());
        FOLLOWED.clear();
    }

    static ModelRenderer[] parts(ModelBiped m) {
        return new ModelRenderer[] {m.bipedHead, m.bipedHeadwear, m.bipedBody, m.bipedRightArm, m.bipedLeftArm, m.bipedRightLeg, m.bipedLeftLeg};
    }

    // the seven parts' rotation points and angles (1.7.10 has no ModelBase.copyModelAngles: this is it)
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

    // The GL state the pass may change, taken before it and put back after (1.7.10 has no GlStateManager: straight GL)
    static final class GlSaved {
        final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), lighting = GL11.glIsEnabled(GL11.GL_LIGHTING),
                rescale = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL), normalize = GL11.glIsEnabled(GL11.GL_NORMALIZE),
                texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        // without GL 1.4 (vanilla 1.7.10 runs on less) one blend function for colour and alpha, as OpenGlHelper has it
        // (its openGL14 is private: the context's own capabilities)
        final boolean gl14 = org.lwjgl.opengl.GLContext.getCapabilities().OpenGL14;
        final int srcRgb = GL11.glGetInteger(gl14 ? GL14.GL_BLEND_SRC_RGB : GL11.GL_BLEND_SRC),
                dstRgb = GL11.glGetInteger(gl14 ? GL14.GL_BLEND_DST_RGB : GL11.GL_BLEND_DST),
                srcA = gl14 ? GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) : srcRgb,
                dstA = gl14 ? GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) : dstRgb,
                depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC),
                shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL), bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        final float[] color = new float[4];

        GlSaved() {
            COLOR.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR);
            COLOR.get(color);
        }

        // as RendererLivingEntity has it when it draws the armour passes: no culling, alpha-tested, not blended (the
        // glint blends itself), normals rescaled; renormalized too, the arm being stretched unevenly
        void passState() {
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDepthMask(true);
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }

        void restore() {
            set(blend, GL11.GL_BLEND);
            set(cull, GL11.GL_CULL_FACE);
            set(alpha, GL11.GL_ALPHA_TEST);
            set(lighting, GL11.GL_LIGHTING);
            set(rescale, GL12.GL_RESCALE_NORMAL);
            set(normalize, GL11.GL_NORMALIZE);
            // the lightmap's coords first (NTM's suits set them, and put them back only when nothing failed), then the
            // unit: the texture's state and binding are the unit's
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            OpenGlHelper.setActiveTexture(unit);
            set(texture, GL11.GL_TEXTURE_2D);
            set(depth, GL11.GL_DEPTH_TEST);
            OpenGlHelper.glBlendFunc(srcRgb, dstRgb, srcA, dstA);
            GL11.glDepthMask(depthMask);
            GL11.glDepthFunc(depthFunc);
            GL11.glAlphaFunc(alphaFunc, alphaRef);
            GL11.glShadeModel(shade);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL11.glColor4f(color[0], color[1], color[2], color[3]);
        }

        private static void set(boolean on, int cap) {
            if (on) GL11.glEnable(cap);
            else GL11.glDisable(cap);
        }
    }

    // ---- the stand's numbers: the pass again in GL's feedback mode, nothing drawn, the clipped primitives read back

    private static FloatBuffer feedback;

    private static void measure(EntityPlayer player, ItemStack chest, float partial, Matrix4f m, boolean right, int shown) {
        if (feedback == null) feedback = floats(1 << 21);
        // feedback keeps only what is in the view volume; 26.2's numbers are the whole arm, the part off the screen
        // too: here a parallel view of everything within SEEN blocks of the eye, the clip planes still on
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glScalef(1F / SEEN, 1F / SEEN, 1F / SEEN);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            measureIn(player, chest, partial, m, right, shown);
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }

    static final float SEEN = 16F;

    private static void measureIn(EntityPlayer player, ItemStack chest, float partial, Matrix4f m, boolean right, int shown) {
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
            run(player, chest, partial, true);
        } catch (Throwable t) {
            // counted in the pass itself (layer); the stand's run only measures
            undo(0);
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
        for (Map.Entry<String, Box> e : boxes(values, n, toArm, viewport, right, chest.getItem().getClass().getSimpleName()).entrySet()) {
            Box b = e.getValue();
            TestLog.c(-1, "arm_layer", "right", right, "type", e.getKey(), "cut", shown, "in", b.polygons, "out", b.vertices,
                    "min", new double[] {b.min[0], b.min[1], b.min[2]}, "max", new double[] {b.max[0], b.max[1], b.max[2]});
        }
    }

    // the box of what was drawn per draw and texture, in the arm part's pixels (the arm box: x -3..1 or -2..1 on the
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
    // the pass's draws, by the pass-through mark run() puts before each
    static final String[] DRAWS = {"armor", "overlay", "glint"};

    // GL_3D feedback values: tokens, each vertex window x, y, z. The pass-through values (Feedback) say which draw (its
    // index in DRAWS) and which texture (-1 - its index in Feedback.TEXTURES) the polygons after them come from.
    // toArm: (projection x the arm's frame) inverted; item: the chestplate's class, the box's name before the draw.
    static Map<String, Box> boxes(float[] f, int n, Matrix4f toArm, int[] viewport, boolean right, String item) {
        Map<String, Box> out = new LinkedHashMap<>();
        String draw = "?", texture = "-";
        Vector4f v = new Vector4f();
        int i = 0;
        while (i < n) {
            int token = (int) f[i++];
            switch (token) {
                case PASS -> {
                    int k = (int) f[i++];
                    if (k >= 0) {
                        draw = item + "/" + (k < DRAWS.length ? DRAWS[k] : "draw" + k);
                        texture = "-";
                    } else {
                        int t = -1 - k;
                        texture = t < Feedback.TEXTURES.size() ? Feedback.TEXTURES.get(t) : "tex" + t;
                    }
                }
                case POLYGON -> {
                    int count = (int) f[i++];
                    Box b = out.computeIfAbsent(draw + " " + texture, s -> new Box());
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
