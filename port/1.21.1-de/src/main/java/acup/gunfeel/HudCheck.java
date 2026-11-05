package acup.gunfeel;

import java.util.List;

import com.hbm.client.Keybinds;
import com.hbm.items.ModItems;
import com.hbm.items.tool.ItemAmmoBag;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

// port/1.21.1-de: the check of the HUD part on DE in the smoke world (gradle checkClient -PcheckHud), by numbers only:
// the corner HUD hooked into DE's gun HUD, the ammo lock (a key tap, a second tap), the radial menu (the key held, the
// pointer on the ammo that is only in the ammo bag, a left click held through the menu that must not shoot), the
// reload with the chosen ammo, a shot after the menu, the instant switch (the draw starts the frame the slot changes
// and the server's is not started again) against the switch without it, and the aimed Maresleg's animations.
// Shots are counted by the magazine. Results go to ClientSmoke's report (build/clientcheck/result.txt).
public final class HudCheck {
    public static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.checkHud");
    private static final String GUN = "gun_uzi", OTHER = "gun_g3";

    private enum Stage {
        WAIT, GIVE, RELOAD, RELOAD_WAIT, HUD, LOCK, LOCK_WAIT, UNLOCK, UNLOCK_WAIT, MENU, MENU_OPEN, CLICK, CLICK_HELD,
        SWITCHED, FIRE, FIRE_AFTER, SPREAD, SWITCH, SWITCH_WAIT, SWITCH_BACK, SWITCH_BACK_WAIT, MARESLEG, DONE
    }

    private static boolean started;
    private static Stage stage = Stage.WAIT;
    private static final int HOLD_AFTER = 20;
    private static int ticks, stageTicks, roundsBefore, bagApBefore, loadedAt;
    private static BulletConfig target;
    // the switch: frames since the slot changed, the frame the new gun's draw was found, and how many draws started
    private static int switchFrames = -1, drawFrame = -1, shownFrame = -1, draws, switchTo;
    private static long switchNanos, drawNanos;
    private static Object lastAnim;
    private static boolean wasInstant;
    private static volatile String spreadLine;
    private static volatile boolean spreadOk;

    private HudCheck() {
    }

    static void start() {
        if (started) return;
        started = true;
        GunFeel.LOGGER.info("[hudcheck] start");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> frame());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == Stage.DONE) return;
        mc.options.pauseOnLostFocus = false;
        // DE's gun keys act only in a focused window; the stand's window may be behind another
        mc.setWindowActive(true);
        if (mc.player == null || server == null || mc.getOverlay() != null || mc.screen != null) return;
        if (++ticks > 20 * 180) {
            check(false, "стенд не дошёл до конца, стоит на " + stage);
            finish();
            return;
        }
        stageTicks++;
        ItemStack held = mc.player.getMainHandItem();
        IMagazine<?> mag = held.getItem() instanceof ItemGunBaseNT ? AmmoLock.primary(held) : null;
        var inv = mc.player.getInventory();
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
                        var pinv = p.getInventory();
                        for (int i = 0; i < pinv.getContainerSize(); i++) pinv.setItem(i, ItemStack.EMPTY);
                        pinv.selected = 0;
                        pinv.setItem(0, stack(GUN, 1));
                        pinv.setItem(1, stack(OTHER, 1));
                        pinv.setItem(27, stack("ammo_standard_p9_sp", 64));
                        pinv.setItem(28, stack("ammo_standard_p9_fmj", 40));
                        pinv.setItem(29, stack("ammo_standard_p9_jhp", 12));
                        // armour piercing only in an ammo bag: the menu offers it, the reload takes it from there
                        ItemStack bag = stack("ammo_bag", 1);
                        var contents = ItemAmmoBag.contents(bag);
                        contents.setItem(0, stack("ammo_standard_p9_ap", 30));
                        contents.setChanged();
                        pinv.setItem(30, bag);
                        pinv.setItem(31, stack("ammo_standard_r556_sp", 64));
                        p.setXRot(15F);
                        p.inventoryMenu.broadcastChanges();
                    });
                }
                if (stageTicks == 3) mc.player.setXRot(15F);
                if (path(held).equals(GUN) && stageTicks > 10) next(Stage.RELOAD);
                else if (stageTicks > 100) {
                    check(false, "HUD: оружие не выдано");
                    finish();
                }
            }
            case RELOAD -> {
                Keybinds.RELOAD.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                if (loaded(held, mag)) next(Stage.HUD);
                else if (stageTicks > 160) {
                    check(false, "HUD: нет перезарядки");
                    next(Stage.LOCK);
                }
            }
            case HUD -> {
                if (stageTicks == 5) {
                    BulletConfig type = (BulletConfig) mag.getType(held, inv);
                    boolean hooked = hudHooked();
                    int reserve = AmmoLock.count(type, inv);
                    // DE's gun HUD drawn here into a buffer that counts what is written: the corner HUD's text and
                    // boxes, then with the corner HUD off NTM's own components; the two differ, and neither is empty
                    boolean corner = Config.cornerHud;
                    int ours = hudVertices(true), ntm = hudVertices(false);
                    Config.cornerHud = corner;
                    check(hooked && corner && ours > 0 && ntm > 0 && ours != ntm && reserve == given(type) - mag.getAmount(held, inv),
                            "HUD: угловой HUD " + (hooked ? "в ClientRegistry.renderGunHudComponents" : "не подключён")
                                    + ", вершин в буфере " + ours + " (у HUD NTM " + ntm + ")"
                                    + ", магазин " + mag.getAmount(held, inv) + "/" + mag.getCapacity(held) + " " + name(type)
                                    + ", запас " + reserve + ", значок " + mag.getIconForHUD(held, mc.player).getHoverName().getString());
                    // the tracers' muzzle: the hand's field of view as 1.21.1 draws it (no fluid, alive: 70)
                    float hand = acup.gunfeel.port.Mc.handFov(mc.gameRenderer.getMainCamera());
                    check(hand == 70F, "TRACERS: fov руки " + hand + ", мира " + acup.gunfeel.port.Mc.worldFov());
                    next(Stage.LOCK);
                }
            }
            case LOCK, UNLOCK -> {
                AmmoLockKey.KEY.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(stage == Stage.LOCK ? Stage.LOCK_WAIT : Stage.UNLOCK_WAIT);
            }
            case LOCK_WAIT -> {
                if (AmmoLock.locked(held)) {
                    if (stageTicks >= 5) {
                        check(true, "AMMO_CHOICE: нажатие закрепило патрон за " + stageTicks + " тиков");
                        next(Stage.UNLOCK);
                    }
                } else if (stageTicks > 40) {
                    check(false, "AMMO_CHOICE: нажатие не закрепило патрон");
                    next(Stage.UNLOCK);
                }
            }
            case UNLOCK_WAIT -> {
                if (!AmmoLock.locked(held)) {
                    check(true, "AMMO_CHOICE: второе нажатие сняло закрепление за " + stageTicks + " тиков");
                    next(Stage.MENU);
                } else if (stageTicks > 40) {
                    check(false, "AMMO_CHOICE: второе нажатие не сняло закрепление");
                    next(Stage.MENU);
                }
            }
            case MENU -> {
                AmmoLockKey.KEY.setDown(true);
                if (RadialMenu.isOpen()) {
                    List<BulletConfig> choices = RadialMenu.choices(held);
                    target = choices.contains(XFactory9mm.p9_ap) ? XFactory9mm.p9_ap : null;
                    check(target != null, "AMMO_CHOICE: меню открылось через " + stageTicks + " тиков, выбор "
                            + choices.stream().map(HudCheck::name).toList());
                    if (target == null) {
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
                    check(false, "AMMO_CHOICE: меню не открылось");
                    AmmoLockKey.KEY.setDown(false);
                    next(Stage.SWITCH);
                }
            }
            case MENU_OPEN -> {
                if (stageTicks == 10) {
                    BulletConfig hover = RadialMenu.hovered();
                    check(hover == target, "AMMO_CHOICE: указатель на " + name(hover));
                    roundsBefore = mag.getAmount(held, inv);
                    bagApBefore = bagCount(inv, target);
                    // a left click as the mouse makes it, the button then held through the menu
                    InputConstants.Key button = InputConstants.Type.MOUSE.getOrCreate(0);
                    KeyMapping.set(button, true);
                    KeyMapping.click(button);
                    next(Stage.CLICK);
                }
            }
            case CLICK -> {
                if (stageTicks == 2) {
                    check(!RadialMenu.isOpen(), "AMMO_CHOICE: после щелчка меню " + (RadialMenu.isOpen() ? "открыто" : "закрыто"));
                    AmmoLockKey.KEY.setDown(false);
                }
                if (stageTicks >= 12) {
                    // the button stays down through the reload and HOLD_AFTER ticks after it
                    loadedAt = -1;
                    next(Stage.CLICK_HELD);
                }
            }
            case CLICK_HELD -> {
                if (loadedAt < 0 && mag != null && mag.getType(held, null) == target && loaded(held, mag)) loadedAt = stageTicks;
                if (loadedAt >= 0 && stageTicks >= loadedAt + HOLD_AFTER) {
                    KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), false);
                    // the magazine was emptied into the inventory and filled with the chosen ammo: a shot would show
                    // as fewer rounds than the bag gave
                    int bagAp = bagCount(inv, target), loaded = mag.getAmount(held, inv);
                    check(loaded == bagApBefore - bagAp && loaded > 0, "AMMO_CHOICE: заряжен " + name(target) + " " + loaded + "/"
                            + mag.getCapacity(held) + " за " + loadedAt + " тиков, кнопка держалась ещё " + (stageTicks - loadedAt)
                            + ", AP в сумке " + bagApBefore + " -> " + bagAp + " (выстрелов при зажатой кнопке нет, если взято столько же, "
                            + "сколько в магазине; до меню в магазине " + roundsBefore + ")");
                    next(Stage.SWITCHED);
                } else if (loadedAt < 0 && stageTicks > 200) {
                    KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), false);
                    check(false, "AMMO_CHOICE: выбранный патрон не заряжен (тип "
                            + (mag == null ? null : name((BulletConfig) mag.getType(held, null))) + ")");
                    next(Stage.SWITCH);
                }
            }
            case SWITCHED -> {
                if (stageTicks > 10) {
                    // the button pressed again after the menu: the gun fires
                    roundsBefore = mag.getAmount(held, inv);
                    KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), true);
                    KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(0));
                    next(Stage.FIRE);
                }
            }
            case FIRE -> {
                if (stageTicks > 6) {
                    KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), false);
                    next(Stage.FIRE_AFTER);
                }
            }
            case FIRE_AFTER -> {
                if (stageTicks > 10) {
                    int shots = roundsBefore - mag.getAmount(held, inv);
                    check(shots > 0, "AMMO_CHOICE: после меню кнопка стреляет: " + shots + " выстрелов");
                    next(Stage.SPREAD);
                }
            }
            case SPREAD -> {
                if (stageTicks == 1) {
                    spreadLine = null;
                    var id = mc.player.getUUID();
                    server.execute(() -> spreadLine = spread(server.getPlayerList().getPlayer(id)));
                }
                if (spreadLine != null) {
                    check(spreadOk, spreadLine);
                    next(Stage.SWITCH);
                } else if (stageTicks > 40) {
                    check(false, "SPREAD: сервер не ответил");
                    next(Stage.SWITCH);
                }
            }
            case SWITCH, SWITCH_BACK -> {
                if (stageTicks == 20) {
                    wasInstant = stage == Stage.SWITCH;
                    // off for the comparison, as the key would turn it off
                    feature(wasInstant);
                    switchTo = wasInstant ? 1 : 0;
                    switchFrames = 0;
                    draws = 0;
                    drawFrame = -1;
                    shownFrame = -1;
                    lastAnim = HbmAnimations.hotbar[switchTo][0];
                    switchNanos = System.nanoTime();
                    inv.selected = switchTo;
                    next(wasInstant ? Stage.SWITCH_WAIT : Stage.SWITCH_BACK_WAIT);
                }
            }
            case SWITCH_WAIT, SWITCH_BACK_WAIT -> {
                if (stageTicks == 30) {
                    String what = wasInstant ? "мгновенная смена" : "смена без неё";
                    // instant: the new gun drawn and its draw started the frame the slot changed, and the server's draw came and
                    // was skipped (nothing waits for it); without it both later
                    String waits = waiting();
                    boolean ok = drawFrame >= 0 && shownFrame >= 0 && (wasInstant ? draws == 1 && drawFrame == 0 && shownFrame == 0
                            && !waits.contains("true") : drawFrame > 1 && shownFrame > 1);
                    check(ok, "INSTANT_SWITCH: " + what + " на " + path(held) + ": нарисован через " + shownFrame
                            + " кадров, доставание началось через " + drawFrame + " кадров (" + String.format("%.1f", (drawNanos - switchNanos) / 1e6)
                            + " мс), начато доставаний " + draws + ", ждёт сервера " + waits);
                    feature(true);
                    switchFrames = -1;
                    next(stage == Stage.SWITCH_WAIT ? Stage.SWITCH_BACK : Stage.MARESLEG);
                }
            }
            case MARESLEG -> {
                maresleg();
                finish();
            }
            default -> {
            }
        }
    }

    private static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if ((stage != Stage.SWITCH_WAIT && stage != Stage.SWITCH_BACK_WAIT) || switchFrames < 0 || mc.player == null) return;
        // DE keeps an animation per hotbar slot and gun index, keyed by the item's description id
        var anim = HbmAnimations.getRelevantAnim(0);
        if (anim != null && anim != lastAnim) {
            draws++;
            if (drawFrame < 0) {
                drawFrame = switchFrames;
                drawNanos = System.nanoTime();
            }
        }
        if (anim != null) lastAnim = anim;
        if (shownFrame < 0 && drawnItem(mc) == mc.player.getMainHandItem().getItem()) shownFrame = switchFrames;
        switchFrames++;
    }

    // on the server: NTM's spread with the shooter (MixinLegoSpread) is Spread's over NTM's own (calcSpread without
    // a shooter, which the mixin leaves alone), from the hip and aimed; called in one tick, so the bloom is the same
    private static String spread(net.minecraft.server.level.ServerPlayer p) {
        if (p == null) return "SPREAD: нет игрока";
        ItemStack stack = p.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return "SPREAD: в руке не оружие";
        var config = gun.getConfig(stack, 0);
        var receiver = config.getReceivers(stack)[0];
        var bullet = (BulletConfig) receiver.getMagazine(stack).getType(stack, p.getInventory());
        var ctx = new ItemGunBaseNT.LambdaContext(config, p, p.getInventory(), 0);
        boolean hooked = false;
        for (var m : com.hbm.items.weapon.sedna.factory.Lego.class.getDeclaredMethods()) hooked |= m.getName().contains("gunfeel$state");
        // by reflection: a stand's call would count as the core's use of NTM (CompatTest, ntmgunfeel-ntm-api.txt)
        java.lang.reflect.Method calc;
        float baseHip, baseAim, hip, aim;
        try {
            calc = com.hbm.items.weapon.sedna.factory.Lego.class.getMethod("calcSpread", ItemGunBaseNT.LambdaContext.class, ItemStack.class,
                    com.hbm.items.weapon.sedna.Receiver.class,
                    BulletConfig.class, boolean.class, int.class, boolean.class);
            baseHip = (float) calc.invoke(null, null, stack, receiver, bullet, false, 0, false);
            baseAim = (float) calc.invoke(null, null, stack, receiver, bullet, false, 0, true);
            hip = (float) calc.invoke(null, ctx, stack, receiver, bullet, false, 0, false);
            aim = (float) calc.invoke(null, ctx, stack, receiver, bullet, false, 0, true);
        } catch (ReflectiveOperationException e) {
            return "SPREAD: нет Lego.calcSpread: " + e;
        }
        float wantHip = Spread.apply(p, stack, false, baseHip), wantAim = Spread.apply(p, stack, true, baseAim);
        spreadOk = hooked && hip == wantHip && aim == wantAim;
        return "SPREAD: " + path(stack) + " от бедра NTM " + baseHip + " -> " + hip + " (Spread " + wantHip + "), прицельно NTM " + baseAim
                + " -> " + aim + " (Spread " + wantAim + "), обработчик в Lego.calcSpread: " + hooked;
    }

    // the sawn-off Maresleg cycles like the full one while aimed and spins as NTM has it otherwise; the akimbo pair
    // keeps NTM's (MixinGunConfigAnims). Both cycles last as long (the sounds match): they are told apart by every bus's
    // transformation each 50 ms
    private static void maresleg() {
        ItemStack sawn = new ItemStack(ModItems.GUN_MARESLEG_BROKEN.get());
        ItemStack pair = new ItemStack(ModItems.GUN_MARESLEG_AKIMBO.get());
        var anims = ((ItemGunBaseNT) sawn.getItem()).getConfig(sawn, 0).getAnims(sawn);
        String full = trace(XFactory12ga.LAMBDA_MARESLEG_ANIMS.apply(sawn, GunAnimation.CYCLE));
        String spin = trace(XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS.apply(sawn, GunAnimation.CYCLE));
        ItemGunBaseNT.setIsAiming(sawn, true);
        String aimed = trace(anims.apply(sawn, GunAnimation.CYCLE));
        ItemGunBaseNT.setIsAiming(sawn, false);
        String hip = trace(anims.apply(sawn, GunAnimation.CYCLE));
        boolean pairKept = ((ItemGunBaseNT) pair.getItem()).getConfig(pair, 0).getAnims(pair) == XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS;
        check(!full.equals(spin) && aimed.equals(full) && hip.equals(spin) && pairKept, "MARESLEG: взвод обреза прицельно "
                + (aimed.equals(full) ? "как у полного" : aimed.equals(spin) ? "разворотом" : "другой") + ", от бедра "
                + (hip.equals(spin) ? "разворотом" : hip.equals(full) ? "как у полного" : "другой") + " (шины " + buses(anims.apply(sawn, GunAnimation.CYCLE))
                + "), у пары анимации NTM: " + pairKept);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, ?> busMap(com.hbm.render.anim.BusAnimation a) {
        try {
            var f = com.hbm.render.anim.BusAnimation.class.getDeclaredField("animationBuses");
            f.setAccessible(true);
            return (java.util.Map<String, ?>) f.get(a);
        } catch (ReflectiveOperationException e) {
            return java.util.Map.of();
        }
    }

    private static String buses(com.hbm.render.anim.BusAnimation a) {
        return new java.util.TreeSet<>(busMap(a).keySet()).toString();
    }

    private static String trace(com.hbm.render.anim.BusAnimation a) {
        StringBuilder b = new StringBuilder(a.getDuration() + ":");
        for (String bus : new java.util.TreeSet<>(busMap(a).keySet())) {
            b.append(bus);
            for (int t = 0; t <= a.getDuration(); t += 50) b.append(java.util.Arrays.toString(a.getTimedTransformation(bus, t)));
        }
        return b.toString();
    }

    // the item the hand renderer draws in the main hand
    private static net.minecraft.world.item.Item drawnItem(Minecraft mc) {
        try {
            var f = net.minecraft.client.renderer.ItemInHandRenderer.class.getDeclaredField("mainHandItem");
            f.setAccessible(true);
            return ((ItemStack) f.get(mc.getEntityRenderDispatcher().getItemInHandRenderer())).getItem();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // what InstantSwitch still waits for from the server, per gun index
    private static String waiting() {
        try {
            var f = InstantSwitch.class.getDeclaredField("waiting");
            f.setAccessible(true);
            return java.util.Arrays.toString((boolean[]) f.get(null));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static void feature(boolean on) {
        try {
            var m = acup.gunfeel.compat.Feature.class.getDeclaredMethod("set", boolean.class);
            m.setAccessible(true);
            m.invoke(acup.gunfeel.compat.Feature.INSTANT_SWITCH, on);
        } catch (ReflectiveOperationException e) {
            GunFeel.LOGGER.warn("[hudcheck] could not turn the instant switch {}", on ? "on" : "off");
        }
    }

    // DE's gun HUD (ClientRegistry.renderGunHud, by reflection as a stand's call) into a buffer that only counts the
    // vertices written: text and fills; blits and GuiShapes draw on their own and are not counted
    private static int hudVertices(boolean corner) {
        Config.cornerHud = corner;
        int[] n = {0};
        var counting = new com.mojang.blaze3d.vertex.VertexConsumer() {
            public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
                n[0]++;
                return this;
            }

            public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
                return this;
            }

            public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
                return this;
            }

            public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
                return this;
            }

            public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
                return this;
            }

            public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
                return this;
            }
        };
        var buffers = new net.minecraft.client.renderer.MultiBufferSource.BufferSource(new com.mojang.blaze3d.vertex.ByteBufferBuilder(256),
                new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>()) {
            @Override
            public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
                return counting;
            }

            @Override
            public void endBatch() {
            }

            @Override
            public void endBatch(net.minecraft.client.renderer.RenderType type) {
            }
        };
        try {
            var g = com.hbm.backport.client.gui.GuiGraphicsExtractor.of(new net.minecraft.client.gui.GuiGraphics(Minecraft.getInstance(), buffers));
            com.hbm.client.ClientRegistry.class.getMethod("renderGunHud", com.hbm.backport.client.gui.GuiGraphicsExtractor.class).invoke(null, g);
            g.flush();
        } catch (ReflectiveOperationException | RuntimeException e) {
            GunFeel.LOGGER.warn("[hudcheck] the gun HUD could not be drawn", e);
            return -1;
        }
        return n[0];
    }

    // the mod's HUD handler merged into DE's ClientRegistry (MixinClientRegistry)
    private static boolean hudHooked() {
        for (var m : com.hbm.client.ClientRegistry.class.getDeclaredMethods()) if (m.getName().contains("gunfeel$hud")) return true;
        return false;
    }

    private static boolean loaded(ItemStack held, IMagazine<?> mag) {
        return mag != null && mag.getAmount(held, null) > 0 && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE;
    }

    // rounds of that 9 mm type the stand gave: what the reserve shows is that less the magazine
    private static int given(BulletConfig type) {
        return switch (name(type)) {
            case "p9_sp" -> 64;
            case "p9_fmj" -> 40;
            case "p9_jhp" -> 12;
            case "p9_ap" -> 30;
            default -> -1;
        };
    }

    // DE numbers its bullet configs; the stand's 9 mm by name
    private static String name(BulletConfig c) {
        return c == null ? "null" : c == XFactory9mm.p9_sp ? "p9_sp" : c == XFactory9mm.p9_fmj ? "p9_fmj" : c == XFactory9mm.p9_jhp ? "p9_jhp"
                : c == XFactory9mm.p9_ap ? "p9_ap" : "#" + c.id;
    }

    private static int bagCount(net.minecraft.world.Container inv, BulletConfig type) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var bag = IMagazine.ammoBag(inv.getItem(i));
            if (bag == null) continue;
            for (int j = 0; j < bag.getContainerSize(); j++) if (type.matchesAmmo(bag.getItem(j))) n += bag.getItem(j).getCount();
        }
        return n;
    }

    private static String path(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("hbm", id)), count);
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static void check(boolean ok, String text) {
        ClientSmoke.check(ok, text);
    }

    private static void finish() {
        KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(0), false);
        AmmoLockKey.KEY.setDown(false);
        Keybinds.RELOAD.setDown(false);
        feature(true);
        next(Stage.DONE);
        ClientSmoke.finish();
    }
}
