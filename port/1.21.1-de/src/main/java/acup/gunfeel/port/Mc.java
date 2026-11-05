package acup.gunfeel.port;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

// What 26.2 has as one call and Minecraft 1.21.1 does otherwise, for the shared files: rewrite.txt turns the 26.2
// call into one of these. Each says what the 26.2 one does.
public final class Mc {
    private Mc() {
    }

    // 26.2 ClientAvatarState.getBackwardsInterpolatedWalkDistance(partial): -(walkDist + (walkDist - walkDistO) * partial)
    public static float backwardsWalkDistance(Player player, float partial) {
        return -(player.walkDist + (player.walkDist - player.walkDistO) * partial);
    }

    // 26.2 Vec3.normalize(): zero below 1e-5f, where 1.21.1's is zero below 1e-4 (a part's thin cube, as the hoglin's
    // mane, 6.3e-5 across, keeps its axis)
    public static Vec3 normalize(Vec3 v) {
        double d = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        return d < 1.0E-5F ? Vec3.ZERO : new Vec3(v.x / d, v.y / d, v.z / d);
    }

    // 26.2 Entity.getKnownSpeed(): for a player what its client last sent, as 1.21.1 getKnownMovement(); for anything
    // else the blocks it moved last tick, where 1.21.1 gives its velocity instead
    public static Vec3 knownSpeed(Entity e) {
        if (e instanceof Player || e.getControllingPassenger() instanceof Player) return e.getKnownMovement();
        return new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
    }

    // 26.2 Screenshot.takeScreenshot(target, then): 1.21.1 reads the frame right away and gives the image back
    public static void screenshot(RenderTarget target, Consumer<NativeImage> then) {
        then.accept(Screenshot.takeScreenshot(target));
    }

    // 26.2 NativeImage keeps ARGB in getPixel/setPixel/getPixels; 1.21.1's getPixelRGBA and the rest are the raw
    // bytes R, G, B, A as an int, so 0xAABBGGRR: red and blue change places
    private static int swapRedBlue(int c) {
        return c & 0xFF00FF00 | (c & 0xFF) << 16 | c >>> 16 & 0xFF;
    }

    public static int[] pixels(NativeImage image) {
        int[] p = image.getPixelsRGBA();
        for (int i = 0; i < p.length; i++) p[i] = swapRedBlue(p[i]);
        return p;
    }

    // 26.2 DynamicTexture.getPixels(): its image, as 1.21.1 getPixels()
    public static NativeImage pixels(net.minecraft.client.renderer.texture.DynamicTexture texture) {
        return texture.getPixels();
    }

    // 26.2 !NativeImage.isClosed(): 1.21.1 frees an image by zeroing its pixels pointer, read here
    private static final java.lang.reflect.Field IMAGE_PIXELS;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = NativeImage.class.getDeclaredField("pixels");
            f.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            acup.gunfeel.GunFeel.LOGGER.warn("NativeImage.pixels cannot be read: every image is taken as open", e);
        }
        IMAGE_PIXELS = f;
    }

    public static boolean allocated(NativeImage image) {
        try {
            return IMAGE_PIXELS == null || IMAGE_PIXELS.getLong(image) != 0;
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    public static int pixel(NativeImage image, int x, int y) {
        return swapRedBlue(image.getPixelRGBA(x, y));
    }

    public static void setPixel(NativeImage image, int x, int y, int argb) {
        image.setPixelRGBA(x, y, swapRedBlue(argb));
    }

    // 26.2 DataComponents.GLIDER: in 1.21.1 only the elytra glides
    public static boolean glider(ItemStack stack) {
        return stack.is(Items.ELYTRA);
    }

    // 26.2 DataComponents.EQUIPPABLE: what slot the item is worn in; 1.21.1 has the Equipable items
    public record Equip(EquipmentSlot slot) {
    }

    public static Equip equippable(ItemStack stack) {
        Equipable e = Equipable.get(stack);
        return e == null ? null : new Equip(e.getEquipmentSlot());
    }

    // 26.2 EquipmentSlot.SADDLE: 1.21.1 keeps a saddle as a flag of the animal
    public static Item saddle(LivingEntity living) {
        return living instanceof Saddleable s && s.isSaddled() ? Items.SADDLE : Items.AIR;
    }

    // 26.2 Player.sendOverlayMessage(text): the line above the hotbar
    public static void overlay(Player player, net.minecraft.network.chat.Component text) {
        player.displayClientMessage(text, true);
    }

    // 26.2 GuiGraphicsExtractor.blurBeforeThisStratum(): blur what is drawn so far, as 1.21.1 Screen does under a menu
    public static void blur(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        g.flush();
        mc.gameRenderer.processBlurEffect(mc.getTimer().getGameTimeDeltaPartialTick(false));
        mc.getMainRenderTarget().bindWrite(false);
    }

    // 26.2 BlockEntityRenderDispatcher.getRenderer(be): the 26.2 kind of renderer, which DE's own are; null for a
    // vanilla one, which draws through 1.21.1's render() and nothing can read before it draws
    @SuppressWarnings("rawtypes")
    public static com.hbm.backport.client.core.BlockEntityRenderer renderer(net.minecraft.world.level.block.entity.BlockEntity be) {
        var r = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        return r instanceof com.hbm.backport.client.core.BlockEntityRenderer b ? b : null;
    }

    // 26.2 RenderType.pipeline().isCull(): DE's types know their pipeline; a vanilla type keeps it in its state
    public static boolean cull(net.minecraft.client.renderer.RenderType type) {
        var pipeline = com.hbm.backport.client.rendertype.RenderTypeInfo.pipeline(type);
        if (pipeline != null) return pipeline.isCull();
        return VanillaRenderTypes.cull(type);
    }

    // 26.2 RenderType.hasBlending(): DE's types by their pipeline's blend function, a vanilla type by its transparency
    public static boolean blending(net.minecraft.client.renderer.RenderType type) {
        var pipeline = com.hbm.backport.client.rendertype.RenderTypeInfo.pipeline(type);
        if (pipeline != null) return pipeline.getColorTargetState().blendFunction().isPresent();
        return VanillaRenderTypes.blending(type);
    }

    // 26.2 RenderType.primitiveTopology(): what 1.21.1 calls the type's mode
    public static com.hbm.backport.client.rendertype.PrimitiveTopology topology(net.minecraft.client.renderer.RenderType type) {
        var pipeline = com.hbm.backport.client.rendertype.RenderTypeInfo.pipeline(type);
        if (pipeline != null) return pipeline.getPrimitiveTopology();
        return com.hbm.backport.client.rendertype.PrimitiveTopology.of(type.mode());
    }

    // 26.2 PoseStack.Pose.set(other): the matrix and the normal of other
    public static void setPose(com.mojang.blaze3d.vertex.PoseStack.Pose to, com.mojang.blaze3d.vertex.PoseStack.Pose from) {
        to.pose().set(from.pose());
        to.normal().set(from.normal());
    }

    // 26.2 VertexConsumer.addVertexWith2DPose(pose, x, y): a GUI vertex through a 2D pose, at depth 0
    public static VertexConsumer vertex2d(VertexConsumer vc, Matrix3x2f pose, float x, float y) {
        Vector2f p = pose.transformPosition(x, y, new Vector2f());
        return vc.addVertex(p.x, p.y, 0);
    }

    private static volatile float worldFov = 70F;

    // 26.2 Camera.getFov(): the field of view the level is drawn with, zoom included. 1.21.1 keeps it inside
    // GameRenderer: it is read back from the level's projection each frame (the port's GunFeel, AFTER_SKY). That
    // projection is the perspective times the hurt tilt and the view bob (turns and shifts): its second row is the
    // perspective's 1/tan(fov/2) times a unit row of the turn, so its length is the fov without them. The nausea
    // warp (a scale) is not taken out
    public static float worldFov() {
        return worldFov;
    }

    private static volatile float handFov = 70F;

    // 26.2 Camera.hudFov: the field of view the hand was last drawn with (mixin/mc/MixinHandFov, renderItemInHand's
    // projection); 70 until a hand is drawn
    public static float handFov(net.minecraft.client.Camera camera) {
        return handFov;
    }

    public static void handProjection(double fov) {
        if (fov > 1 && fov < 179) handFov = (float) fov;
    }

    public static void levelProjection(org.joml.Matrix4f projection) {
        double y = Math.sqrt((double) projection.m01() * projection.m01() + (double) projection.m11() * projection.m11()
                + (double) projection.m21() * projection.m21());
        if (y > 1e-6) worldFov = (float) Math.toDegrees(2.0 * Math.atan(1.0 / y));
    }
}
