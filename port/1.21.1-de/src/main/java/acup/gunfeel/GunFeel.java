package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import acup.gunfeel.compat.CompatClient;
import acup.gunfeel.compat.Feature;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

// The port's copy of the root's GunFeel (notes/ports/de-1.21.1.md, "Копии"): 1.21.1 has one RenderLevelStageEvent
// with stages for 26.2's AfterWeather, AfterOpaqueBlocks and the rest, and no SubmitCustomGeometryEvent (Tracers gets
// them as port/StageEvent).
// Client-side state and hooks; loaded only on the client (see GunFeelMod).
public final class GunFeel {
    public static final Logger LOGGER = LoggerFactory.getLogger("ntm-gunfeel");

    // Renderer drawing the first-person gun right now (null outside of it).
    public static ItemRenderWeaponBase current;
    public static boolean currentAkimbo;
    private static final Map<Item, Long> SEEN_SHOT = new IdentityHashMap<>();

    static void init() {
        acup.gunfeel.compat.PunchyCompat.install();
        acup.gunfeel.compat.PunchyMovesCompat.install();
        ClientSmoke.init();
        if (Feature.TRACERS.on()) {
            acup.gunfeel.water.WaterFx.init();
            // a blast at water (WaterBlast, from the server): bubbles and a push under water, a column on the surface
            acup.gunfeel.water.WaterEvents.listenBlasts(acup.gunfeel.water.WaterFx::blastAt);
        }
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> {
            ModelHitClient.frame();
            ModelHitClient.diagnostics();
            Config.poll();
            LocalPlayer player = Minecraft.getInstance().player;
            Item held = player == null ? null : player.getMainHandItem().getItem();
            Config.select(held instanceof ItemGunBaseNT ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held).toString() : null);
            if (Feature.INSTANT_SWITCH.on()) InstantSwitch.frame(player);
            if (Config.enabled && Feature.RECOIL.on()) CameraRecoil.frame();
        });
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> ClickAim.frame());
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, GunFeel::onRenderHand);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, e -> {
            if (Config.enabled && Feature.RECOIL.on() && CameraRecoil.roll != 0F) e.setRoll(e.getRoll() + CameraRecoil.roll);
        });
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> {
            if (Feature.ARMS.on()) GripCommand.register(e.getDispatcher());
            CompatClient.register(e.getDispatcher());
        });
        // the root's SubmitCustomGeometry and AfterOpaqueFeatures: after the block entities, before the translucent
        // terrain. As in 26.2 the frame's holes and tracers are submitted first (submit() also updates the batches),
        // the batched holes drawn, then what was submitted; the holes on glass that 26.2 submits after the
        // translucent terrain (improved transparency) after it here too. AfterWeather as it is
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.class, e -> {
            var stage = e.getStage();
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) acup.gunfeel.port.Mc.levelProjection(e.getProjectionMatrix());
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) ModelHitDebug.beginLevel();
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) ModelHitDebug.captureFog();
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES && (Feature.TRACERS.on() || GlassStand.ENABLED)) {
                var ev = acup.gunfeel.port.StageEvent.of(e);
                if (Feature.TRACERS.on()) Tracers.submit(ev);
                // after the tracers: they hand over the rounds flying in water
                var level = net.minecraft.client.Minecraft.getInstance().level;
                if (level != null && Feature.TRACERS.on()) {
                    var cam = e.getCamera().getPosition();
                    acup.gunfeel.water.WaterRings.submit(ev, level, cam);
                    acup.gunfeel.water.SunkBullets.submit(ev, level, cam);
                }
                if (GlassStand.ENABLED) GlassStand.submit(ev);
                if (Feature.TRACERS.on()) Tracers.afterOpaqueFeatures(ev);
                ev.flush();
            }
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) acup.gunfeel.port.StageEvent.flushLate();
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_WEATHER && Feature.TRACERS.on())
                Tracers.afterWeather(acup.gunfeel.port.StageEvent.of(e));
            if (stage == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                acup.gunfeel.port.StageEvent.endFrame();
                ModelHitClient.finishFrame();
                // F3+B's model outlines with the pose this frame drew, once it is published (26.2: AfterLevel too)
                ModelHitDebug.render(e.getModelViewMatrix(), e.getCamera());
                OutlineDrift.endFrame();
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            ModelHitClient.clear();
            CompatClient.leave();
            ArmLayers.leave();
            if (Feature.TRACERS.on()) Tracers.clear();
            if (Feature.TRACERS.on()) acup.gunfeel.water.WaterFx.clear();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.generate();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.tick();
            WalkSprint.tick();
            if (Feature.TRACERS.on()) acup.gunfeel.water.WaterFx.clientTick();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.frame();
        });
        // NTM keeps the attack and pick keys off the world while a gun is held on them, but not the use key: aiming
        // on the right button also opened doors, pressed buttons, placed the off-hand block
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered.class, e -> {
            if (Feature.HOLD_AIM.on() && e.isUseItem() && com.hbm.client.Keybinds.isGunHeld() && com.hbm.client.Keybinds.isGunKey(Minecraft.getInstance().options.keyUse)) {
                e.setCanceled(true);
                e.setSwingHand(false);
            }
        });
    }

    // The gun is held with both hands, so the off-hand item is not drawn (it stays in the slot).
    private static void onRenderHand(RenderHandEvent event) {
        if (!Config.enabled || !Config.hideOffhand || event.getHand() != InteractionHand.OFF_HAND || !Feature.ARMS.on()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (ItemRenderWeaponBase.get(player.getMainHandItem()) == null) return;
        if (ItemRenderWeaponBase.get(event.getItemStack()) != null) return; // dual wielding guns
        event.setCanceled(true);
    }

    // NTM stamps gun.lastShot[i] when the shot animation arrives; a newer stamp is a new shot.
    public static void checkShot(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        long newest = 0;
        for (long t : gun.lastShot) newest = Math.max(newest, t);
        Long seen = SEEN_SHOT.put(gun, newest);
        if (seen == null || newest <= seen) return;
        ModelRecoil.shot(ItemGunBaseNT.getIsAiming(stack));
        WalkSprint.onShot();
    }
}
