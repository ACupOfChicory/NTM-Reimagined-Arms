package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.crosshair.CrosshairStyle;
import acup.gunfeel.crosshair.DynamicCrosshair;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// runCheckClient -PcrosshairStand: the dynamic crosshair (DynamicCrosshair) in the game. On a stone floor under an open
// sky, the player in survival looks up at the sky:
//  - each class's style: the G3 a cross, the SPAS-12 a square, the Uzi brackets, the revolver a corner, the
//    Panzerschreck a ruler; the Stinger keeps NTM's (with its lock-on);
//  - the gap: the base and the spread NTM and the mod work out now, as TaCZ's inaccuracy × 0.8; crouched it is no
//    wider than standing, in the air (slow falling) wider;
//  - a kick (NTM's recoil field, as its recoil lambdas leave it) jumps the gap out and it comes back;
//  - aiming (the gun's aiming flag set on both sides) hides it where NTM hides its own;
//  - the frame: the square's lines in the screenshot where the gap puts them, light, and the sky inside.
final class CrosshairStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.crosshairStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static int step, until;
    private static int took0, drawn0, standing, crouched, air, peak, settled;
    private static volatile com.mojang.blaze3d.platform.NativeImage shot;
    private static net.minecraft.client.CloudStatus clouds;
    private static final List<String> SEEN = new ArrayList<>();

    private CrosshairStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        // no clouds in the sky behind the crosshair (given back at the end)
        clouds = mc.options.cloudStatus().get();
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        onServer(p -> {
            var level = p.level();
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, level.getServer());
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, level.getServer());
            level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "time set noon");
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y <= 30; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(level, O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), 0F, -40F, false);
            return null;
        });
        step = 0;
        until = now() + 40;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setXRot(-40F);
        mc.player.setYRot(0F);
        if (step == 999 || now() < until) return;
        try {
            switch (step) {
                // the styles, gun by gun
                case 0 -> hold("hbm:gun_g3", 1);
                case 1 -> style("hbm:gun_g3", CrosshairStyle.CROSS, 2);
                case 2 -> hold("hbm:gun_spas12", 3);
                case 3 -> style("hbm:gun_spas12", CrosshairStyle.SQUARE, 4);
                case 4 -> hold("hbm:gun_uzi", 5);
                case 5 -> style("hbm:gun_uzi", CrosshairStyle.BRACKETS, 6);
                case 6 -> hold("hbm:gun_light_revolver", 7);
                case 7 -> style("hbm:gun_light_revolver", CrosshairStyle.CORNER, 8);
                case 8 -> hold("hbm:gun_panzerschreck", 9);
                case 9 -> style("hbm:gun_panzerschreck", CrosshairStyle.RULER, 10);
                case 10 -> hold("hbm:gun_stinger", 30);
                case 30 -> {
                    // counted once the Stinger is in the client's hand
                    took0 = DynamicCrosshair.took;
                    wait(10, 11);
                }
                case 11 -> {
                    check(DynamicCrosshair.took == took0, "Стингер: прицел NTM с захватом цели (кадров наших " + (DynamicCrosshair.took - took0) + ")");
                    hold("hbm:gun_g3", 12);
                }
                case 12 -> wait(30, 14);
                // the gap from the spread: standing, crouched, in the air
                case 14 -> {
                    standing = DynamicCrosshair.lastGap;
                    double want = 4 + Math.min(DynamicCrosshair.inaccuracy(DynamicCrosshair.lastSpread) * 0.8, 20);
                    log("G3 стоя: разброс {} ({}°), как неточность TaCZ {}, зазор {}", f(DynamicCrosshair.lastSpread),
                            f(Math.toDegrees(Math.atan(DynamicCrosshair.lastSpread))), f(DynamicCrosshair.inaccuracy(DynamicCrosshair.lastSpread)), standing);
                    check(DynamicCrosshair.lastSpread > 0F && standing == Math.round(want) && standing > 4,
                            "G3 стоя: зазор 4 + разброс × 0,8 (" + standing + ", ждали " + f(want) + ")");
                    mc.options.keyShift.setDown(true);
                    wait(20, 15);
                }
                case 15 -> {
                    crouched = DynamicCrosshair.lastGap;
                    log("G3 присев: разброс {}, зазор {}", f(DynamicCrosshair.lastSpread), crouched);
                    check(crouched <= standing, "G3 присев: не шире, чем стоя (" + crouched + " ≤ " + standing + ")");
                    mc.options.keyShift.setDown(false);
                    onServer(p -> {
                        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
                        p.teleportTo(p.level(), O.getX() + 0.5, O.getY() + 6, O.getZ() + 0.5, Set.of(), 0F, -40F, false);
                        return null;
                    });
                    wait(20, 16);
                }
                case 16 -> {
                    air = DynamicCrosshair.lastGap;
                    log("G3 в воздухе: разброс {}, на земле {}, зазор {}", f(DynamicCrosshair.lastSpread), mc.player.onGround(), air);
                    check(!mc.player.onGround() && air > standing, "G3 в воздухе: шире, чем стоя (" + air + " > " + standing + ")");
                    onServer(p -> {
                        p.removeEffect(MobEffects.SLOW_FALLING);
                        p.teleportTo(p.level(), O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), 0F, -40F, false);
                        return null;
                    });
                    wait(30, 17);
                }
                // a kick
                case 17 -> {
                    settled = DynamicCrosshair.lastGap;
                    ItemGunBaseNT.recoilVertical = 3F;
                    peak = 0;
                    step = 18;
                    until = now() + 1;
                }
                case 18 -> {
                    peak = Math.max(peak, DynamicCrosshair.lastGap);
                    if (now() < until + 3) return;
                    log("G3 толчок 3°: зазор {} → пик {}", settled, peak);
                    check(peak >= settled + 4, "толчок отдачи раскрывает прицел (" + settled + " → " + peak + ")");
                    wait(30, 19);
                }
                case 19 -> {
                    log("G3 после толчка: зазор {}", DynamicCrosshair.lastGap);
                    check(DynamicCrosshair.lastGap == settled, "после толчка прицел сходится обратно (" + DynamicCrosshair.lastGap + ", было " + settled + ")");
                    // the frame: the SPAS-12's square
                    hold("hbm:gun_spas12", 20);
                }
                case 20 -> {
                    shot = null;
                    net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> shot = image);
                    step = 21;
                }
                case 21 -> {
                    if (shot == null) return;
                    pixels(shot, DynamicCrosshair.lastGap);
                    shot.close();
                    // aiming
                    drawn0 = DynamicCrosshair.drawn;
                    hold("hbm:gun_g3", 22);
                }
                case 22 -> {
                    onServer(p -> {
                        ItemGunBaseNT.setIsAiming(p.getMainHandItem(), true);
                        return null;
                    });
                    ItemGunBaseNT.setIsAiming(mc.player.getMainHandItem(), true);
                    wait(30, 23);
                }
                case 23 -> {
                    var stack = mc.player.getMainHandItem();
                    var gun = (ItemGunBaseNT) stack.getItem();
                    boolean hides = gun.getConfig(stack, 0).getHideCrosshair(stack);
                    int d0 = DynamicCrosshair.drawn;
                    step = 24;
                    until = now() + 5;
                    drawn0 = d0;
                    SEEN.add(hides ? "hide" : "keep");
                    log("G3 прицеливание: прогресс {}, NTM прячет прицел {}", f(ItemGunBaseNT.aimingProgress), hides);
                }
                case 24 -> {
                    boolean hides = SEEN.getLast().equals("hide");
                    int d = DynamicCrosshair.drawn - drawn0;
                    check(ItemGunBaseNT.aimingProgress >= 1F && (hides ? d == 0 : d > 0),
                            "прицеливание: прицел " + (hides ? "скрыт" : "остаётся, как у NTM") + " (кадров с линиями " + d + ")");
                    onServer(p -> {
                        ItemGunBaseNT.setIsAiming(p.getMainHandItem(), false);
                        return null;
                    });
                    mc.options.cloudStatus().set(clouds);
                    ClientSmoke.finish();
                    step = 999;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[crosshair-stand] сбой", t);
            check(false, "без сбоя: " + t);
            ClientSmoke.finish();
            step = 999;
        }
    }

    // the square's lines in the frame: light where the gap puts them, the sky inside
    private static void pixels(com.mojang.blaze3d.platform.NativeImage im, int gap) {
        var w = Minecraft.getInstance().getWindow();
        int s = (int) w.getGuiScale(), x = DynamicCrosshair.centre(w.getGuiScaledWidth()), y = DynamicCrosshair.centre(w.getGuiScaledHeight());
        int lines = 0, cells = 0;
        for (int[] r : CrosshairStyle.SQUARE.rects(x, y, gap))
            for (int gx = r[0]; gx < r[2]; gx++) for (int gy = r[1]; gy < r[3]; gy++) {
                cells++;
                if (light(im.getPixel(gx * s + s / 2, gy * s + s / 2))) lines++;
            }
        int inside = 0, insideLight = 0;
        for (int gx = x - gap + 2; gx <= x + gap - 2; gx++) for (int gy = y - gap + 2; gy <= y + gap - 2; gy++) {
            if (Math.abs(gx - x) <= 1 && Math.abs(gy - y) <= 1) continue; // the dot and its shadow
            inside++;
            if (light(im.getPixel(gx * s + s / 2, gy * s + s / 2))) insideLight++;
        }
        log("кадр: квадрат с зазором {}, масштаб GUI {}: светлых клеток линий {}/{}, внутри светлых {}/{}", gap, s, lines, cells, insideLight, inside);
        check(cells > 0 && lines == cells && inside > 0 && insideLight == 0,
                "кадр: линии квадрата на местах, внутри небо (" + lines + "/" + cells + ", внутри " + insideLight + ")");
    }

    // the crosshair's colour (FFFFCC at 0.9) over the sky: every channel light, whichever order the image keeps them in
    private static boolean light(int c) {
        int a = c & 255, b = c >> 8 & 255, d = c >> 16 & 255;
        return Math.min(a, Math.min(b, d)) > 180 && Math.max(a, Math.max(b, d)) > 230;
    }

    private static void hold(String id, int next) {
        onServer(p -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))));
            return null;
        });
        wait(20, next);
    }

    private static void style(String id, CrosshairStyle want, int next) {
        log("{}: стиль {}, зазор {}, разброс {}", id, DynamicCrosshair.lastStyle, DynamicCrosshair.lastGap, f(DynamicCrosshair.lastSpread));
        check(DynamicCrosshair.lastStyle == want, id + ": " + want + " (" + DynamicCrosshair.lastStyle + ")");
        step = next;
    }

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(server.getPlayerList().getPlayers().getFirst())).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[crosshair-stand] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "прицел: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[crosshair-stand] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
