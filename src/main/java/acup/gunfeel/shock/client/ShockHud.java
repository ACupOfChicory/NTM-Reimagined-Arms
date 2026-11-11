package acup.gunfeel.shock.client;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;

import acup.gunfeel.GunFeel;
import acup.gunfeel.mixin.mc.GameRendererAccess;
import acup.gunfeel.mixin.mc.GuiLayerManagerAccess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client: the whole HUD glitched while shocked (ShockClient.glitch() above 0), the HUDs of other mods too. The HUD's
 * layers (vanilla's, NTM's, the mod's, other mods', and what mods draw at RenderGuiEvent) go into a GUI render state of
 * this class's instead of the game's (MixinShockHud); a second GuiRenderer draws that into a texture of the screen's
 * size, cleared to nothing (MixinShockGuiTarget), right before the game's GUI is drawn (MixinShockGuiComposite); the
 * texture is then laid over the frame through {@code shock_glitch.fsh}: rows slid sideways, cells swapped, torn lines,
 * red and blue apart, flicker and noise, and the whole of it shaken. The rest of the GUI is drawn after it as ever.
 * Not glitched: the chat, the subtitles, the camera's overlays (a pumpkin, the vignette, the suppression's dark) and
 * the sleep's dark; nothing while a screen other than the chat is open (the menus, the inventory, Esc).
 */
public final class ShockHud {
    private ShockHud() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ntmgunfeel", path);
    }

    /** the layers drawn as ever, not glitched */
    private static final Set<Identifier> KEEP = Set.of(VanillaGuiLayers.CHAT, VanillaGuiLayers.SUBTITLE_OVERLAY, VanillaGuiLayers.CAMERA_OVERLAYS,
            VanillaGuiLayers.SLEEP_OVERLAY, id("suppression"), id("gas_hud"), id("smoke"));

    static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("ShockInfo", UniformType.UNIFORM_BUFFER)
            .withSampler("HudSampler")
            .build();
    // the glitched HUD over the frame (premultiplied: the GUI's blending into a cleared texture leaves it so)
    public static final RenderPipeline GLITCH = RenderPipeline.builder()
            .withLocation(id("pipeline/shock_glitch"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/shock_glitch"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static final int UBO_SIZE = 32;

    /** the game's picture-in-picture renderers (MixinShockGuiComposite), so the HUD's entities and the like draw here too */
    public static List<PictureInPictureRendererRegistration<?>> pip = List.of();
    private static GuiRenderState state;
    private static GuiRenderer renderer;
    private static TextureTarget target;
    private static MappableRingBuffer ubo;
    // the HUD went into this class's state this frame, at this strength
    private static boolean extracted;
    private static float strength;
    // the glitch's frame: a new picture of it every 40 to 110 ms, its seed and the HUD's shift then (screen shares)
    private static long nextFrame;
    private static float seed, shiftX, shiftY;
    private static final Random RANDOM = new Random();
    private static boolean failed;
    // the stand: frames glitched
    public static int frames;

    static void init(IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, e -> e.registerPipeline(GLITCH));
    }

    /** Hud.extractRenderState's layers (MixinShockHud): glitched while shocked, else as ever */
    public static void extract(GuiLayerManager layers, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        float s = failed ? 0F : ShockClient.glitch();
        if (s <= 0.001F || mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
            layers.render(graphics, delta);
            return;
        }
        if (state == null) state = new GuiRenderState();
        // anew each time: a frame extracted and never drawn (a panorama shot) leaves nothing behind
        state.reset();
        int mx = (int) mc.mouseHandler.getScaledXPos(mc.getWindow()), my = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
        GuiGraphicsExtractor glitch = new GuiGraphicsExtractor(mc, state, mx, my);
        // GuiLayerManager.render, with the HUD's own layers (and what mods draw at its events) into the glitch
        if (NeoForge.EVENT_BUS.post(new RenderGuiEvent.Pre(glitch, delta)).isCanceled()) return;
        for (GuiLayerManager.NamedLayer layer : ((GuiLayerManagerAccess) layers).gunfeel$layers()) {
            GuiGraphicsExtractor g = KEEP.contains(layer.name()) ? graphics : glitch;
            if (!NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Pre(g, delta, layer.name(), layer.layer())).isCanceled()) {
                layer.layer().render(g, delta);
                NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Post(g, delta, layer.name(), layer.layer()));
            }
        }
        NeoForge.EVENT_BUS.post(new RenderGuiEvent.Post(glitch, delta));
        extracted = true;
        strength = s;
    }

    /** where a GuiRenderer draws (MixinShockGuiTarget): this class's into its texture */
    public static RenderTarget target(GuiRenderer r, RenderTarget main) {
        return r == renderer && target != null ? target : main;
    }

    /** right before the game's GUI is drawn (MixinShockGuiComposite): the glitched HUD into its texture and over the frame */
    public static void beforeGui() {
        if (!extracted) return;
        extracted = false;
        try {
            draw();
        } catch (RuntimeException e) {
            // never twice: the HUD goes back to the game's own drawing
            failed = true;
            GunFeel.LOGGER.error("The shock's HUD glitch failed; the HUD is drawn as ever from now on", e);
            if (state != null) state.reset();
        }
    }

    /** lets go of the glitch's renderer, texture and buffer (logging out): made anew at the next shock */
    public static void release() {
        extracted = false;
        if (state != null) state.reset();
        try {
            if (renderer != null) renderer.close();
            if (target != null) target.destroyBuffers();
            if (ubo != null) ubo.close();
        } catch (RuntimeException e) {
            GunFeel.LOGGER.warn("The shock's HUD glitch: letting go of its renderer", e);
        }
        renderer = null;
        target = null;
        ubo = null;
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (renderer == null) renderer = new GuiRenderer(state, ((GameRendererAccess) mc.gameRenderer).gunfeel$features(), pip);
        if (target == null) target = new TextureTarget("Shock HUD", main.width, main.height, true, GpuFormat.RGBA8_UNORM);
        else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height);
        var device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0F, 0F, 0F, 0F), target.getDepthTexture(), 0.0);
        renderer.render();
        renderer.endFrame();
        long now = System.nanoTime();
        if (now >= nextFrame) {
            nextFrame = now + (40 + RANDOM.nextInt(70)) * 1_000_000L;
            seed = RANDOM.nextFloat() * 1000F;
            shiftX = (RANDOM.nextFloat() - 0.5F) * 0.03F;
            shiftY = (RANDOM.nextFloat() - 0.5F) * 0.02F;
        }
        float time = (now / 1_000_000L % 3_600_000L) / 1000F;
        // the shake: the frame's jump and a quick tremble on top
        float sx = (shiftX + (float) Math.sin(time * 71.0) * 0.004F) * strength;
        float sy = (shiftY + (float) Math.sin(time * 89.0 + 1.1) * 0.003F) * strength;
        if (ubo == null) ubo = new MappableRingBuffer(() -> "Shock info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        try (var view = ubo.currentBuffer().map(false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putVec4(time, strength, seed, (float) main.width / Math.max(1, main.height));
            b.putVec4(sx, sy, main.width, main.height);
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Shock HUD glitch", main.getColorTextureView(), Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(GLITCH);
            pass.setUniform("ShockInfo", ubo.currentBuffer());
            pass.bindTexture("HudSampler", target.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        ubo.rotate();
        frames++;
    }
}
