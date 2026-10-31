package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.client.anim.HbmAnimations;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import dev.golem.core.client.MouseInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

// port/continuum-arms: a dev check of the arms on NTM Continuum, in a singleplayer world (-Dntmgunfeel.armsSmoke=<guns>,
// gradle -Pntmgunfeel.armsSmoke=maresleg,spas12 with -Pcontinuum.world=<save>; "all" = every gun below). Per gun: hand it
// with ammo, then at rest, a reload, an inspect, aimed with a shot, a shot from the hip; screenshots at set moments of
// each animation (run-continuum/screenshots/arms_<gun>_<moment>.png) with the arms' placement logged for that frame
// ([armdebug], Arms.debugArms), then quit.
public final class ArmsSmoke {
    private static final String WANTED = System.getProperty("ntmgunfeel.armsSmoke");
    // name, gun, ammo
    private static final List<String[]> GUNS = List.of(
            new String[] {"maresleg", "gun_maresleg", "ammo_standard_g12"},
            new String[] {"spas12", "gun_spas12", "ammo_standard_g12"},
            new String[] {"henry", "gun_henry", "ammo_standard_m357_sp"},
            new String[] {"lincoln", "gun_henry_lincoln", "ammo_standard_m357_sp"},
            new String[] {"liberator", "gun_liberator", "ammo_standard_g12"},
            new String[] {"sexy", "gun_autoshotgun_sexy", "ammo_standard_g12"},
            new String[] {"g3", "gun_g3", "ammo_standard_r556_sp"});
    // moments of an animation to take (fractions of its length)
    private static final float[] RELOAD_AT = {0.3F, 0.7F}, CYCLE_AT = {0.25F, 0.6F}, INSPECT_AT = {0.25F, 0.5F, 0.8F}, ONE_AT = {0.5F};

    private enum Stage { WAIT, SELECT, EQUIP, REST, RELOAD, RELOAD_WAIT, INSPECT, INSPECT_WAIT, AIM, AIMED, AIM_FIRE, UNAIM, FIRE, FIRE_WAIT, DONE }

    private static final List<String[]> cases = new ArrayList<>();
    private static Stage stage = Stage.WAIT;
    private static int index, ticks, stageTicks, failures;
    private static String shot;
    private static boolean debugFrame;
    // which (cue, moment) of this gun were taken
    private static final List<String> taken = new ArrayList<>();

    private ArmsSmoke() {
    }

    static void register() {
        if (WANTED == null) return;
        for (String[] g : GUNS) if (WANTED.equals("all") || List.of(WANTED.split(",")).contains(g[0])) cases.add(g);
        if (cases.isEmpty()) return;
        GunFeel.LOGGER.info("[arms-smoke] on: {}", cases.stream().map(c -> c[0]).toList());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> framePre());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> framePost());
    }

    private static String[] current() {
        return cases.get(index);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == Stage.DONE) {
            mc.stop();
            return;
        }
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || server == null || mc.getOverlay() != null || mc.screen != null) return;
        if (++ticks > 20 * 90) {
            fail("timed out at " + stage);
            ticks = 0;
            return;
        }
        stageTicks++;
        ItemStack held = mc.player.getMainHandItem();
        boolean idle = held.getItem() instanceof ItemGunBaseNT && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE
                && HbmAnimations.getRelevantAnim(0) == null;
        switch (stage) {
            case WAIT -> {
                if (stageTicks > 40) next(Stage.SELECT);
            }
            case SELECT -> {
                if (stageTicks == 1) {
                    String[] c = current();
                    var id = mc.player.getUUID();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        p.getInventory().setSelectedSlot(0);
                        p.getInventory().setItem(0, stack(c[1], 1));
                        p.getInventory().setItem(35, stack(c[2], 64));
                        p.setXRot(10F);
                        p.inventoryMenu.broadcastChanges();
                    });
                }
                if (stageTicks == 3) mc.player.setXRot(10F);
                if (BuiltInRegistries.ITEM.getKey(held.getItem()).getPath().equals(current()[1]) && stageTicks > 5) next(Stage.EQUIP);
                else if (stageTicks > 100) fail("the gun never arrived");
            }
            case EQUIP -> {
                if (stageTicks > 10 && idle) next(Stage.REST);
            }
            case REST -> {
                if (stageTicks == 10) {
                    shot = "rest";
                    GunGeometry g = Arms.geometry(held.getItem());
                    GunFeel.LOGGER.info("[arms-smoke] {}: geometry {} (variant {}, missing '{}', length {}), arms drawn here: {}",
                            current()[0], g == null ? "none" : "read", g == null ? -1 : g.variant, g == null ? "" : g.missing,
                            g == null ? 0F : g.length(), ArmsGem.fits(held));
                    if (g == null) failures++;
                }
                if (stageTicks > 15) next(Stage.RELOAD);
            }
            case RELOAD -> {
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                if (stageTicks > 10 && idle && loaded(held)) next(Stage.INSPECT);
                else if (stageTicks > 20 * 30) fail("no reload");
            }
            case INSPECT -> {
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.INSPECT_WAIT);
            }
            case INSPECT_WAIT -> {
                if (stageTicks > 10 && idle) next(Stage.AIM);
                else if (stageTicks > 20 * 15) fail("inspect never ended");
            }
            case AIM -> {
                // a press toggles aiming once the server takes it: pressed again every 10 ticks until it has
                HbmKeybindsClient.gunSecondaryKey.setDown(stageTicks % 10 <= 2 && stageTicks % 10 > 0);
                if (ItemGunBaseNT.getIsAiming(held) && stageTicks % 10 > 2) next(Stage.AIMED);
                else if (stageTicks > 100) next(Stage.AIMED);
            }
            case AIMED -> {
                if (stageTicks == 20) {
                    shot = "aim";
                    GunFeel.LOGGER.info("[arms-smoke] {}: aiming {}", current()[0], ItemGunBaseNT.getIsAiming(held));
                }
                if (stageTicks == 25) MouseInput.fireButton(0, true);
                if (stageTicks == 26) {
                    MouseInput.fireButton(0, false);
                    next(Stage.AIM_FIRE);
                }
            }
            case AIM_FIRE -> {
                if (stageTicks == 1) shot = "aim_fire";
                if (stageTicks > 10 && idle) next(Stage.UNAIM);
                else if (stageTicks > 20 * 10) next(Stage.UNAIM);
            }
            case UNAIM -> {
                HbmKeybindsClient.gunSecondaryKey.setDown(stageTicks % 10 <= 2 && stageTicks % 10 > 0);
                if (!ItemGunBaseNT.getIsAiming(held) && stageTicks % 10 > 2 && ItemGunBaseNT.aimingProgress == 0F) next(Stage.FIRE);
                else if (stageTicks > 100) next(Stage.FIRE);
            }
            case FIRE -> {
                MouseInput.fireButton(0, stageTicks == 1);
                if (stageTicks > 1) next(Stage.FIRE_WAIT);
            }
            case FIRE_WAIT -> {
                // the model kick, turning around the right hand's grip
                if (stageTicks == 1) shot = "fire";
                if (stageTicks > 10 && idle || stageTicks > 20 * 10) {
                    GunFeel.LOGGER.info("[arms-smoke] {} done: took {}", current()[0], taken);
                    nextCase();
                }
            }
            default -> {
            }
        }
    }

    // the moments of the animation playing now; the first frame past one that is not taken yet is taken
    private static String moment() {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        GunAnimation cue = AnimCue.of(0);
        if (anim == null || cue == null) return null;
        float[] at = switch (cue) {
            case RELOAD -> RELOAD_AT;
            case CYCLE -> CYCLE_AT;
            case INSPECT -> INSPECT_AT;
            case RELOAD_CYCLE, RELOAD_END -> ONE_AT;
            default -> new float[0];
        };
        float length = Math.max(1, anim.animation.getDuration());
        float f = (com.hbm.util.GameTime.now() - anim.startMillis) / length;
        String aimed = stage == Stage.AIM_FIRE ? "aim_" : "";
        for (float a : at) {
            String name = aimed + cue.name().toLowerCase(java.util.Locale.ROOT) + "_" + Math.round(a * 100);
            if (f >= a && f < a + 0.15F && !taken.contains(name)) return name;
        }
        return null;
    }

    private static void framePre() {
        if (stage == Stage.WAIT || stage == Stage.SELECT || stage == Stage.DONE) return;
        if (shot == null) {
            String m = moment();
            if (m != null) shot = m;
        }
        debugFrame = shot != null;
        Arms.debugArms = debugFrame;
        if (debugFrame) GunFeel.LOGGER.info("[arms-smoke] {} {}", current()[0], shot);
    }

    private static void framePost() {
        Arms.debugArms = false;
        if (!debugFrame || shot == null) return;
        debugFrame = false;
        Minecraft mc = Minecraft.getInstance();
        String name = "arms_" + current()[0] + "_" + shot + ".png";
        taken.add(shot);
        shot = null;
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, m -> {
        });
    }

    private static boolean loaded(ItemStack held) {
        return held.getItem() instanceof ItemGunBaseNT gun && gun.getPrimaryMagazine(held, 0) != null
                && gun.getPrimaryMagazine(held, 0).getAmount(held, null) > 0;
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", id)), count);
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static void fail(String why) {
        GunFeel.LOGGER.error("[arms-smoke] FAIL {}: {}", current()[1], why);
        failures++;
        nextCase();
    }

    private static void nextCase() {
        MouseInput.fireButton(0, false);
        HbmKeybindsClient.reloadKey.setDown(false);
        HbmKeybindsClient.gunSecondaryKey.setDown(false);
        taken.clear();
        ticks = 0;
        if (++index >= cases.size()) {
            index = cases.size() - 1;
            GunFeel.LOGGER.info("[arms-smoke] done: {} failed of {}", failures, cases.size());
            next(Stage.DONE);
        } else next(Stage.SELECT);
    }
}
