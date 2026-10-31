package acup.gunfeel;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import dev.golem.core.client.MouseInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

// port/continuum: a dev check of bullet holes on glass, in a singleplayer world (-Pntmgunfeel.glassSmoke=true with
// -Pcontinuum.world=<save>). A pane of clear glass and one of red stained glass five blocks ahead, a stone wall behind
// them; three G3 shots into each pane, first with improved transparency off (the holes draw after the weather,
// Tracers.afterWeather), then on (LateHoles261, after the translucent terrain). Screenshots
// run-continuum/screenshots/glasssmoke_<mode>.png; the option is put back as it was. Then quits.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class GlassPortSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.glassSmoke");
    private static final String[] MODES = {"default", "improved"};

    private enum Stage { WAIT, BUILD, RELOAD, RELOAD_WAIT, FIRE, AFTER, DONE }

    private static Stage stage = Stage.WAIT;
    private static int mode, pane, ticks, stageTicks, failures;
    private static boolean transparencyBefore;
    private static float yaw0;
    private static String shot;
    private static net.minecraft.core.BlockPos built;
    private static net.minecraft.core.Direction builtFacing;

    private GlassPortSmoke() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == Stage.DONE) {
            mc.stop();
            return;
        }
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || server == null || mc.getOverlay() != null || mc.screen != null) return;
        if (++ticks > 20 * 180) {
            GunFeel.LOGGER.error("[glass-smoke] timed out in mode {} at {}", MODES[mode], stage);
            failures++;
            finish(mc);
            return;
        }
        stageTicks++;
        var id = mc.player.getUUID();
        ItemStack held = mc.player.getMainHandItem();
        switch (stage) {
            case WAIT -> {
                if (stageTicks == 1) server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
                if (stageTicks > 40) {
                    transparencyBefore = mc.options.improvedTransparency().get();
                    next(Stage.BUILD);
                }
            }
            case BUILD -> {
                if (stageTicks == 1) {
                    boolean improved = mode == 1;
                    if (mc.options.improvedTransparency().get() != improved) mc.options.improvedTransparency().set(improved);
                    yaw0 = mc.player.getDirection().toYRot();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        var dir = p.getDirection();
                        var side = dir.getClockWise();
                        var level = p.level();
                        var base = p.blockPosition();
                        built = base;
                        builtFacing = dir;
                        for (int a = -4; a <= 4; a++) for (int h = 0; h <= 4; h++) {
                            var glass = base.relative(dir, 5).relative(side, a).above(h);
                            level.setBlockAndUpdate(glass, a < 0 ? Blocks.GLASS.defaultBlockState()
                                    : a > 0 ? Blocks.RED_STAINED_GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(base.relative(dir, 10).relative(side, a).above(h),
                                    (a + h) % 2 == 0 ? Blocks.STONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
                        }
                        p.getInventory().setSelectedSlot(0);
                        p.getInventory().setItem(0, stack("gun_g3", 1));
                        p.getInventory().setItem(35, stack("ammo_standard_r556_sp", 64));
                        p.inventoryMenu.broadcastChanges();
                    });
                }
                if (BuiltInRegistries.ITEM.getKey(held.getItem()).getPath().equals("gun_g3") && stageTicks > 20) {
                    pane = 0;
                    next(Stage.RELOAD);
                } else if (stageTicks > 100) fail("the gun never arrived");
            }
            case RELOAD -> {
                aim(mc);
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                aim(mc);
                if (held.getItem() instanceof ItemGunBaseNT gun && gun.getPrimaryMagazine(held, 0) != null
                        && gun.getPrimaryMagazine(held, 0).getAmount(held, null) > 0
                        && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE && stageTicks > 10) {
                    next(Stage.FIRE);
                } else if (stageTicks > 160) fail("no reload");
            }
            case FIRE -> {
                // three single shots a little apart, so the holes do not lie on each other
                int shotIndex = (stageTicks - 1) / 6;
                aim(mc, (shotIndex - 1) * 3F, shotIndex == 1 ? -2F : 1F);
                MouseInput.fireButton(0, (stageTicks - 1) % 6 < 2);
                if (stageTicks > 18) {
                    MouseInput.fireButton(0, false);
                    next(Stage.AFTER);
                }
            }
            case AFTER -> {
                aim(mc, 0F, 0F);
                if (stageTicks == 20) shot = "glasssmoke_" + MODES[mode] + "_" + (pane == 0 ? "clear" : "red") + ".png";
                if (stageTicks > 25) {
                    GunFeel.LOGGER.info("[glass-smoke] {} {}: improved transparency {}, holes made {}", MODES[mode],
                            pane == 0 ? "clear glass" : "red stained glass", mc.options.improvedTransparency().get(), holes());
                    if (pane == 0) {
                        pane = 1;
                        next(Stage.RELOAD_WAIT);
                    } else if (++mode >= MODES.length) finish(mc);
                    else next(Stage.BUILD);
                }
            }
            default -> {
            }
        }
    }

    // at the middle of the clear pane (pane 0, left) or the red one (pane 1, right), at eye height; turned by the
    // given degrees
    private static void aim(Minecraft mc) {
        aim(mc, 0F, 0F);
    }

    private static void aim(Minecraft mc, float yawBy, float pitch) {
        float side = pane == 0 ? -1 : 1;
        float yaw = yaw0 + side * (float) Math.toDegrees(Math.atan2(2.5, 5.0)) + yawBy;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    @SubscribeEvent
    static void frame(RenderFrameEvent.Post e) {
        if (!ENABLED || shot == null) return;
        Minecraft mc = Minecraft.getInstance();
        String name = shot;
        shot = null;
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, m -> {
        });
    }

    private static int holes() {
        try {
            var f = Tracers.class.getDeclaredField("holesMade");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException ex) {
            return -1;
        }
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", id)), count);
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static void fail(String why) {
        GunFeel.LOGGER.error("[glass-smoke] FAIL {}: {}", MODES[mode], why);
        failures++;
        if (++mode >= MODES.length) finish(Minecraft.getInstance());
        else next(Stage.BUILD);
    }

    private static void finish(Minecraft mc) {
        MouseInput.fireButton(0, false);
        HbmKeybindsClient.reloadKey.setDown(false);
        if (mc.options.improvedTransparency().get() != transparencyBefore) mc.options.improvedTransparency().set(transparencyBefore);
        mc.options.save();
        // the world is left as it was: the panes and the wall go
        var server = mc.getSingleplayerServer();
        var base = built;
        var dir = builtFacing;
        if (server != null && base != null) server.execute(() -> {
            var level = server.overworld();
            var side = dir.getClockWise();
            for (int a = -4; a <= 4; a++) for (int h = 0; h <= 4; h++) for (int d : new int[] {5, 10})
                level.setBlockAndUpdate(base.relative(dir, d).relative(side, a).above(h), Blocks.AIR.defaultBlockState());
        });
        GunFeel.LOGGER.info("[glass-smoke] done: {} failed of {}", failures, MODES.length);
        next(Stage.DONE);
    }
}
