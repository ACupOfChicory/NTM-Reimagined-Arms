package acup.gunfeel;

import java.util.List;
import java.util.function.IntPredicate;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.client.anim.HbmAnimations;
import com.hbm.items.tool.ItemAmmoBag;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.mojang.blaze3d.platform.InputConstants;

import dev.golem.core.client.MouseInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

// port/continuum: a dev check of the HUD part on NTM Continuum, in a singleplayer world (-Dntmgunfeel.hudSmoke, gradle
// -Pntmgunfeel.hudSmoke=true with -Pcontinuum.world=<save>): the corner HUD with a gun in hand, the ammo lock (key tap),
// the radial menu (key held, pointer on the ammo from the ammo bag, a left click held through the menu that must not
// shoot), the reload with the chosen ammo, then the instant switch (the draw starts the frame the slot changes, the
// server's draw is not started again) against the switch without it. Logs [hud-smoke], screenshots
// run-continuum/screenshots/hudsmoke_*.png, then quits.
public final class HudSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.hudSmoke");
    private static final String GUN = "gun_uzi", OTHER = "gun_g3";

    private enum Stage {
        WAIT, GIVE, RELOAD, RELOAD_WAIT, HUD, LOCK, LOCK_WAIT, UNLOCK, UNLOCK_WAIT, MENU, MENU_OPEN, CLICK, CLICK_HELD,
        SWITCHED, FIRE, FIRE_AFTER, SWITCH, SWITCH_WAIT, SWITCH_BACK, SWITCH_BACK_WAIT, DONE
    }

    private static Stage stage = Stage.WAIT;
    private static int ticks, stageTicks, failures, shotsBefore;
    private static String shot;
    private static BulletConfig target;
    private static boolean mouseHeld;
    // the switch: frames since the slot changed, the draw found then, and was it started again later
    private static int switchFrames = -1, drawFrame = -1;
    private static long switchNanos, drawNanos;
    private static Object drawAnim, lastAnim;
    private static boolean wasInstant;
    private static int bagApBefore, draws;

    private HudSmoke() {
    }

    static void register() {
        if (!ENABLED) return;
        GunFeel.LOGGER.info("[hud-smoke] on");
        // the left button held as a player would: Continuum polls the physical button (Golem's harness hook)
        try {
            var m = Class.forName("dev.golem.core.harness.HarnessHooks").getDeclaredMethod("install", IntPredicate.class, IntPredicate.class);
            m.setAccessible(true);
            m.invoke(null, (IntPredicate) k -> false, (IntPredicate) b -> b == 0 && mouseHeld);
        } catch (ReflectiveOperationException | RuntimeException e) {
            GunFeel.LOGGER.warn("[hud-smoke] no harness hook, the held click is not physical: {}", e.toString());
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> frame());
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
        if (++ticks > 20 * 120) {
            fail("timed out at " + stage);
            finish();
            return;
        }
        stageTicks++;
        ItemStack held = mc.player.getMainHandItem();
        IMagazine<?> mag = held.getItem() instanceof ItemGunBaseNT ? AmmoLock.primary(held) : null;
        switch (stage) {
            case WAIT -> {
                if (stageTicks > 40) next(Stage.GIVE);
            }
            case GIVE -> {
                if (stageTicks == 1) {
                    var id = mc.player.getUUID();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        var inv = p.getInventory();
                        for (int i = 0; i < inv.getContainerSize(); i++) inv.setItem(i, ItemStack.EMPTY);
                        inv.setSelectedSlot(0);
                        inv.setItem(0, stack(GUN, 1));
                        inv.setItem(1, stack(OTHER, 1));
                        inv.setItem(27, stack("ammo_standard_p9_sp", 64));
                        inv.setItem(28, stack("ammo_standard_p9_fmj", 40));
                        inv.setItem(29, stack("ammo_standard_p9_jhp", 12));
                        // armour piercing only in an ammo bag: the menu offers it, the reload takes it from there
                        ItemStack bag = stack("ammo_bag", 1);
                        new ItemAmmoBag.InventoryAmmoBag(bag).setStackInSlot(0, stack("ammo_standard_p9_ap", 30));
                        inv.setItem(30, bag);
                        inv.setItem(31, stack("ammo_standard_r556_sp", 64));
                        p.setXRot(15F);
                        p.inventoryMenu.broadcastChanges();
                    });
                }
                if (stageTicks == 3) mc.player.setXRot(15F);
                if (path(held).equals(GUN) && stageTicks > 10) next(Stage.RELOAD);
                else if (stageTicks > 100) fail("the gun never arrived");
            }
            case RELOAD -> {
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                if (loaded(held, mag)) {
                    shot = "hud";
                    next(Stage.HUD);
                } else if (stageTicks > 160) fail("no reload");
            }
            case HUD -> {
                if (stageTicks == 5) {
                    var inv = mc.player.getInventory();
                    BulletConfig type = (BulletConfig) mag.getType(held, inv);
                    GunFeel.LOGGER.info("[hud-smoke] {} hud: corner HUD hook {}, magazine {}/{} {}, reserve {}, icon {}", ok(hudHooked()),
                            hudHooked() ? "in GunHudOverlay.render" : "missing", mag.getAmount(held, inv), mag.getCapacity(held), type.id,
                            AmmoLock.count(type, inv), mag.getIconForHUD(held, mc.player).getHoverName().getString());
                    if (!hudHooked()) failures++;
                    next(Stage.LOCK);
                }
            }
            case LOCK, UNLOCK -> {
                // a tap of the ammo key
                AmmoLockKey.KEY.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(stage == Stage.LOCK ? Stage.LOCK_WAIT : Stage.UNLOCK_WAIT);
            }
            case LOCK_WAIT -> {
                if (AmmoLock.locked(held)) {
                    if (stageTicks >= 5) {
                        GunFeel.LOGGER.info("[hud-smoke] OK lock: the tap locked the ammo");
                        shot = "locked";
                        next(Stage.UNLOCK);
                    }
                } else if (stageTicks > 40) {
                    fail("the tap did not lock the ammo");
                    next(Stage.UNLOCK);
                }
            }
            case UNLOCK_WAIT -> {
                if (!AmmoLock.locked(held)) {
                    GunFeel.LOGGER.info("[hud-smoke] OK unlock: the second tap unlocked it after {} ticks", stageTicks);
                    next(Stage.MENU);
                } else if (stageTicks > 40) {
                    fail("the second tap did not unlock the ammo");
                    next(Stage.MENU);
                }
            }
            case MENU -> {
                AmmoLockKey.KEY.setDown(true);
                if (RadialMenu.isOpen()) {
                    List<BulletConfig> choices = RadialMenu.choices(held);
                    target = choices.stream().filter(c -> c.id.getPath().equals("p9_ap")).findFirst().orElse(null);
                    GunFeel.LOGGER.info("[hud-smoke] {} menu: open after {} ticks held, choices {}", ok(target != null), stageTicks,
                            choices.stream().map(c -> c.id.getPath()).toList());
                    if (target == null) {
                        fail("no armour piercing ammo (from the bag) in the menu");
                        AmmoLockKey.KEY.setDown(false);
                        next(Stage.SWITCH);
                        return;
                    }
                    // the pointer onto its sector, as the mouse would move it (MixinMouseTurn)
                    int i = choices.indexOf(target), n = choices.size();
                    double a = i * Math.PI * 2 / n;
                    RadialMenu.move(Math.sin(a) * 40 / 0.4, -Math.cos(a) * 40 / 0.4);
                    next(Stage.MENU_OPEN);
                } else if (stageTicks > 20) {
                    fail("the menu did not open");
                    AmmoLockKey.KEY.setDown(false);
                    next(Stage.SWITCH);
                }
            }
            case MENU_OPEN -> {
                if (stageTicks == 8) shot = "menu";
                if (stageTicks == 10) {
                    BulletConfig hover = RadialMenu.hovered();
                    GunFeel.LOGGER.info("[hud-smoke] {} pointer: hovers {}", ok(hover == target), hover == null ? null : hover.id);
                    if (hover != target) failures++;
                    // a left click as the mouse makes it, the button then held through the menu
                    shotsBefore = ClientSmoke.packets;
                    bagApBefore = bagCount(mc, target);
                    mouseHeld = true;
                    InputConstants.Key button = InputConstants.Type.MOUSE.getOrCreate(0);
                    KeyMapping.set(button, true);
                    KeyMapping.click(button);
                    MouseInput.fireButton(0, true);
                    next(Stage.CLICK);
                }
            }
            case CLICK -> {
                if (stageTicks == 2) {
                    GunFeel.LOGGER.info("[hud-smoke] {} click: menu {}", ok(!RadialMenu.isOpen()), RadialMenu.isOpen() ? "still open" : "closed");
                    if (RadialMenu.isOpen()) failures++;
                    AmmoLockKey.KEY.setDown(false);
                }
                if (stageTicks >= 12) {
                    // let go of the button: up to here it was held, the menu gone
                    mouseHeld = false;
                    KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), false);
                    MouseInput.fireButton(0, false);
                    next(Stage.CLICK_HELD);
                }
            }
            case CLICK_HELD -> {
                if (target != null && mag.getType(held, null) == target && loaded(held, mag)) {
                    int shots = ClientSmoke.packets - shotsBefore;
                    var inv = mc.player.getInventory();
                    GunFeel.LOGGER.info("[hud-smoke] {} choice: loaded {} {}/{} after {} ticks; shots during the held click {}; AP in the bag {} -> {}; "
                            + "reserve SP {}", ok(shots == 0), target.id, mag.getAmount(held, inv), mag.getCapacity(held), stageTicks, shots,
                            bagApBefore, bagCount(mc, target), AmmoLock.count(com.hbm.items.weapon.sedna.BulletConfigs.get("hbm:p9_sp"), inv));
                    if (shots != 0) failures++;
                    shot = "chosen";
                    next(Stage.SWITCHED);
                } else if (stageTicks > 200) {
                    fail("the chosen ammo was not loaded (type " + (mag == null ? null : ((BulletConfig) mag.getType(held, null)).id) + ")");
                    next(Stage.SWITCH);
                }
            }
            case SWITCHED -> {
                if (stageTicks > 10) {
                    // the button pressed again after the menu: the gun fires
                    shotsBefore = ClientSmoke.packets;
                    mouseHeld = true;
                    MouseInput.fireButton(0, true);
                    next(Stage.FIRE);
                }
            }
            case FIRE -> {
                if (stageTicks > 6) {
                    mouseHeld = false;
                    MouseInput.fireButton(0, false);
                    next(Stage.FIRE_AFTER);
                }
            }
            case FIRE_AFTER -> {
                if (stageTicks > 10) {
                    int shots = ClientSmoke.packets - shotsBefore;
                    GunFeel.LOGGER.info("[hud-smoke] {} fire after the menu: {} shots", ok(shots > 0), shots);
                    if (shots == 0) failures++;
                    shot = "fired";
                    next(Stage.SWITCH);
                }
            }
            case SWITCH, SWITCH_BACK -> {
                if (stageTicks == 20) {
                    wasInstant = stage == Stage.SWITCH;
                    // off for the comparison: Config.instantSwitch would be read again from the config as the gun changes
                    feature(wasInstant);
                    switchFrames = 0;
                    draws = 0;
                    drawFrame = -1;
                    drawAnim = null;
                    // what the new slot held before (Continuum keeps an animation per hand, slot and gun index)
                    lastAnim = slotAnim(stage == Stage.SWITCH ? 1 : 0);
                    switchNanos = System.nanoTime();
                    mc.player.getInventory().setSelectedSlot(stage == Stage.SWITCH ? 1 : 0);
                    next(stage == Stage.SWITCH ? Stage.SWITCH_WAIT : Stage.SWITCH_BACK_WAIT);
                }
            }
            case SWITCH_WAIT, SWITCH_BACK_WAIT -> {
                if (stageTicks == 30) {
                    String what = wasInstant ? "instant switch" : "switch without it";
                    boolean ok = drawFrame >= 0 && (!wasInstant || draws == 1 && drawFrame <= 1);
                    GunFeel.LOGGER.info("[hud-smoke] {} {} to {}: draw started {} frames ({} ms) after the slot changed; draws started {}{}", ok(ok),
                            what, path(held), drawFrame, String.format("%.1f", (drawNanos - switchNanos) / 1e6), draws,
                            wasInstant && draws == 1 ? " (the server's was not started again)" : "");
                    if (!ok) failures++;
                    feature(true);
                    if (stage == Stage.SWITCH_WAIT) next(Stage.SWITCH_BACK);
                    else finish();
                }
            }
            default -> {
            }
        }
    }

    private static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if ((stage == Stage.SWITCH_WAIT || stage == Stage.SWITCH_BACK_WAIT) && switchFrames >= 0 && mc.player != null) {
            Item item = mc.player.getMainHandItem().getItem();
            var anim = HbmAnimations.getRelevantAnim(InteractionHand.MAIN_HAND, 0);
            if (anim != lastAnim) {
                GunFeel.LOGGER.info("[hud-smoke] frame {} (+{} ms): main hand animation {} for {}, InstantSwitch waiting {}", switchFrames,
                        String.format("%.1f", (System.nanoTime() - switchNanos) / 1e6), anim == null ? null : Integer.toHexString(System.identityHashCode(anim)),
                        anim == null ? null : BuiltInRegistries.ITEM.getKey(anim.key).getPath(), waiting());
                lastAnim = anim;
                if (anim != null && anim.key == item) draws++;
                if (drawFrame < 0 && anim != null && anim.key == item) {
                    drawFrame = switchFrames;
                    drawNanos = System.nanoTime();
                    drawAnim = anim;
                }
            }
            if (switchFrames == 2) shot = wasInstant ? "switch_instant" : "switch_server";
            switchFrames++;
        }
        if (shot != null) {
            String name = "hudsmoke_" + shot + ".png";
            shot = null;
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, m -> {
            });
        }
    }

    private static void feature(boolean on) {
        try {
            var m = acup.gunfeel.compat.Feature.class.getDeclaredMethod("set", boolean.class);
            m.setAccessible(true);
            m.invoke(acup.gunfeel.compat.Feature.INSTANT_SWITCH, on);
        } catch (ReflectiveOperationException e) {
            GunFeel.LOGGER.warn("[hud-smoke] could not turn the instant switch {}", on ? "on" : "off");
        }
    }

    private static Object slotAnim(int slot) {
        try {
            var f = HbmAnimations.class.getDeclaredField("HANDS");
            f.setAccessible(true);
            return ((Object[][][]) f.get(null))[InteractionHand.MAIN_HAND.ordinal()][slot][0];
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String waiting() {
        try {
            var f = InstantSwitch.class.getDeclaredField("waiting");
            f.setAccessible(true);
            return java.util.Arrays.toString((boolean[]) f.get(null));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    // the mod's HUD handler merged into Continuum's GunHudOverlay (MixinClientRegistry)
    private static boolean hudHooked() {
        for (var m : com.hbm.client.GunHudOverlay.class.getDeclaredMethods()) if (m.getName().contains("gunfeel$hud")) return true;
        return false;
    }

    private static boolean loaded(ItemStack held, IMagazine<?> mag) {
        return mag != null && mag.getAmount(held, null) > 0 && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE;
    }

    private static int bagCount(Minecraft mc, BulletConfig type) {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var bag = IMagazine.ammoBag(inv.getItem(i));
            if (bag == null) continue;
            for (int j = 0; j < bag.getSlots(); j++) if (type.matchesAmmo(bag.getStackInSlot(j))) n += bag.getStackInSlot(j).getCount();
        }
        return n;
    }

    private static String path(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static String ok(boolean ok) {
        return ok ? "OK" : "FAIL";
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", id)), count);
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static void fail(String why) {
        GunFeel.LOGGER.error("[hud-smoke] FAIL {}", why);
        failures++;
    }

    private static void finish() {
        mouseHeld = false;
        AmmoLockKey.KEY.setDown(false);
        HbmKeybindsClient.reloadKey.setDown(false);
        feature(true);
        GunFeel.LOGGER.info("[hud-smoke] done: {} failed", failures);
        next(Stage.DONE);
    }
}
