package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hbm.client.render.weapon.WeaponRenderer;
import com.hbm.client.render.weapon.WeaponRenderers;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.compat.CompatClient;
import acup.gunfeel.compat.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

// port/continuum: the client hooks of src/main/java GunFeel on NTM Continuum. Continuum draws its guns with
// WeaponRenderer (Golem Core's Gem), keeps the last shots per hand and handles the use key itself
// (HbmKeybindsClient), so the 26.2 hooks into ItemRenderWeaponBase and client.Keybinds are not here.
public final class GunFeel {
    public static final Logger LOGGER = LoggerFactory.getLogger("ntm-gunfeel");

    // renderer drawing the first-person gun right now (null outside of it)
    public static WeaponRenderer current;
    public static boolean currentAkimbo;
    // System.nanoTime() of the last first-person gun draw (PortSmoke)
    public static long lastDrawn;
    private static final Map<Item, Long> SEEN_SHOT = new IdentityHashMap<>();

    static void init() {
        PortSmoke.register();
        ClientSmoke.init();
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
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, GunFeel::onRenderHand);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, e -> {
            if (Config.enabled && Feature.RECOIL.on() && CameraRecoil.roll != 0F) e.setRoll(e.getRoll() + CameraRecoil.roll);
        });
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> CompatClient.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, e -> {
            if (Feature.TRACERS.on()) Tracers.submit(e);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterWeather.class, e -> {
            if (Feature.TRACERS.on()) Tracers.afterWeather(e);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueFeatures.class, e -> {
            if (Feature.TRACERS.on()) Tracers.afterOpaqueFeatures(e);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueBlocks.class, e -> ModelHitDebug.captureTerrain());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterLevel.class, e -> {
            ModelHitClient.finishFrame();
            ModelHitDebug.render(e.getModelViewMatrix());
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            ModelHitClient.clear();
            GunExtent.clear();
            CompatClient.leave();
            if (Feature.TRACERS.on()) Tracers.clear();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.generate();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.tick();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> {
            if (Feature.MACHINE_MESH.on()) MeshShapes.frame();
        });
    }

    // the gun is held with both hands, so the off-hand item is not drawn (it stays in the slot)
    private static void onRenderHand(RenderHandEvent event) {
        if (!Config.enabled || !Config.hideOffhand || event.getHand() != InteractionHand.OFF_HAND || !Feature.ARMS.on()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (WeaponRenderers.forStack(player.getMainHandItem()) == null) return;
        if (WeaponRenderers.forStack(event.getItemStack()) != null) return; // dual wielding guns
        event.setCanceled(true);
    }

    // Continuum stamps gun.lastShotByHand[hand][i] when the shot animation arrives; a newer stamp is a new shot
    public static void checkShot(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun.lastShotByHand == null) return;
        long newest = 0;
        for (long[] hand : gun.lastShotByHand) for (long t : hand) newest = Math.max(newest, t);
        Long seen = SEEN_SHOT.put(gun, newest);
        if (seen == null || newest <= seen) return;
        ModelRecoil.shot(ItemGunBaseNT.getIsAiming(stack));
    }
}
