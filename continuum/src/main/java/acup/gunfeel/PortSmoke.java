package acup.gunfeel;

import java.util.List;

import com.hbm.client.HbmKeybindsClient;
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

// port/continuum: a dev check of the gun feel on NTM Continuum, in a singleplayer world (-Dntmgunfeel.portSmoke, gradle
// -Pcontinuum.portSmoke with -Pcontinuum.world=<save>). Per gun: hand it with ammo, reload, fire a burst as a player
// would, log what the mod did (model kick frames, camera pitch moved), take screenshots at rest and while firing
// (run-continuum/screenshots/portsmoke_*), then quit. Continuum's own GunSmokeClient refuses to run on its release jar.
public final class PortSmoke {
    private static final boolean ENABLED = System.getProperty("ntmgunfeel.portSmoke") != null;
    private static final List<String[]> CASES = List.of(
            new String[] {"g3", "gun_g3", "ammo_standard_r556_sp"},
            new String[] {"uzi", "gun_uzi", "ammo_standard_p9_sp"},
            new String[] {"shotgun", "gun_autoshotgun", "ammo_standard_g12"},
            // a burst into a wall, then a zombie steps in front of the holes: they must not show through it
            new String[] {"wall", "gun_g3", "ammo_standard_r556_sp"});

    private enum Stage { WAIT, SELECT, RELOAD, RELOAD_WAIT, REST, FIRE, AFTER, HITBOXES, DONE }

    private static Stage stage = Stage.WAIT;
    private static int index, ticks, stageTicks, kickFrames, gunFrames;
    private static float pitchBefore, pitchPeak;
    private static int shotsBefore;
    private static String shot;
    private static int failures;

    private PortSmoke() {
    }

    static void register() {
        if (!ENABLED) return;
        GunFeel.LOGGER.info("[port-smoke] on");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> frame());
    }

    private static String[] current() {
        return CASES.get(index);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == Stage.DONE) {
            mc.stop();
            return;
        }
        // runs in the background: a window out of focus must not pause the game (and the check with it)
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || server == null || mc.getOverlay() != null || mc.screen != null) return;
        if (++ticks > 20 * 120) {
            GunFeel.LOGGER.error("[port-smoke] timed out in case {} at {}", current()[0], stage);
            failures++;
            finish();
            return;
        }
        stageTicks++;
        ItemStack held = mc.player.getMainHandItem();
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
                        // a little down: the burst lands on the ground a few blocks ahead (holes)
                        p.setXRot(25F);
                        if (c[0].equals("wall")) wall(p);
                        p.inventoryMenu.broadcastChanges();
                    });
                }
                if (stageTicks == 3) {
                    // the client owns its view: the same turn as the server's
                    if (current()[0].equals("wall")) {
                        mc.player.setYRot(mc.player.getDirection().toYRot());
                        mc.player.setXRot(0F);
                    } else mc.player.setXRot(25F);
                }
                if (BuiltInRegistries.ITEM.getKey(held.getItem()).getPath().equals(current()[1]) && stageTicks > 10) next(Stage.RELOAD);
                else if (stageTicks > 100) fail("the gun never arrived");
            }
            case RELOAD -> {
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                if (held.getItem() instanceof ItemGunBaseNT gun && gun.getPrimaryMagazine(held, 0) != null
                        && gun.getPrimaryMagazine(held, 0).getAmount(held, null) > 0
                        && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE) {
                    shot = "rest";
                    next(Stage.REST);
                } else if (stageTicks > 160) fail("no reload");
            }
            case REST -> {
                if (stageTicks > 10) {
                    kickFrames = 0;
                    gunFrames = 0;
                    pitchBefore = mc.player.getXRot();
                    pitchPeak = 0F;
                    shotsBefore = ClientSmoke.packets;
                    MouseInput.fireButton(0, true);
                    next(Stage.FIRE);
                }
            }
            case FIRE -> {
                if (stageTicks == 4) shot = "firing";
                if (stageTicks > 10) {
                    MouseInput.fireButton(0, false);
                    next(Stage.AFTER);
                }
            }
            case AFTER -> {
                boolean wall = current()[0].equals("wall");
                if (wall && stageTicks == 5) zombieInFront(server, mc.player.getUUID(), 3.0);
                if (stageTicks == (wall ? 28 : 15)) shot = "after";
                if (stageTicks > 30) {
                    float pitch = mc.player.getXRot() - pitchBefore;
                    boolean ok = kickFrames > 0;
                    GunFeel.LOGGER.info("[port-smoke] {} {}: gun drawn {} frames, model kick {} frames, camera pitch peak {} deg, after {} deg, server shots {}, holes {}",
                            ok ? "OK" : "FAIL", current()[1], gunFrames, kickFrames, String.format("%.2f", pitchPeak), String.format("%.2f", pitch),
                            ClientSmoke.packets - shotsBefore, holes());
                    if (!ok) failures++;
                    nextCase();
                }
            }
            case HITBOXES -> {
                // F3+B on a zombie in front: the model outlines (ModelHitDebug), then off again
                if (stageTicks == 1) {
                    var id = mc.player.getUUID();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        p.getInventory().setItem(0, ItemStack.EMPTY);
                        var look = p.getLookAngle().multiply(1, 0, 1).normalize().scale(3.5);
                        // two zombies, the far one half hidden behind the near one: its outline must not show through
                        var side = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x).normalize().scale(0.4);
                        for (int k = 0; k < 2; k++) {
                            var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(p.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                            if (z == null) return;
                            double d = k == 0 ? 1.0 : 1.8;
                            z.setPos(p.getX() + look.x * d + side.x * k, p.getY(), p.getZ() + look.z * d + side.z * k);
                            z.setYRot(p.getYRot() + 180F);
                            z.setNoAi(true);
                            z.setSilent(true);
                            p.level().addFreshEntity(z);
                        }
                    });
                    mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES,
                            net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.ALWAYS_ON);
                }
                if (stageTicks == 30) shot = "hitboxes";
                if (stageTicks > 40) {
                    mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES,
                            net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.NEVER);
                    var id = mc.player.getUUID();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p != null) p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                                p.getBoundingBox().inflate(16)).forEach(net.minecraft.world.entity.Entity::discard);
                    });
                    GunFeel.LOGGER.info("[port-smoke] OK hitboxes: F3+B drawn for 40 ticks without an error");
                    finish();
                }
            }
            default -> {
            }
        }
    }

    private static void frame() {
        if (stage == Stage.FIRE || stage == Stage.AFTER) {
            var player = Minecraft.getInstance().player;
            if (player != null && Math.abs(player.getXRot() - pitchBefore) > Math.abs(pitchPeak)) pitchPeak = player.getXRot() - pitchBefore;
        }
        if (stage == Stage.FIRE || stage == Stage.AFTER || stage == Stage.REST) {
            if (GunFeel.lastDrawn != 0L && System.nanoTime() - GunFeel.lastDrawn < 100_000_000L) gunFrames++;
            if (!ModelRecoil.idle()) kickFrames++;
        }
        if (shot != null) {
            Minecraft mc = Minecraft.getInstance();
            String name = "portsmoke_" + (stage == Stage.HITBOXES ? "" : current()[0] + "_") + shot + ".png";
            shot = null;
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, m -> {
            });
        }
    }

    // a stone wall six blocks ahead along the facing axis, the player looking straight at it
    private static void wall(net.minecraft.server.level.ServerPlayer p) {
        var dir = p.getDirection();
        p.setYRot(dir.toYRot());
        p.setXRot(0F);
        var level = p.level();
        var base = p.blockPosition().relative(dir, 6);
        var side = dir.getClockWise();
        for (int a = -3; a <= 3; a++) for (int h = 0; h <= 4; h++)
            level.setBlockAndUpdate(base.relative(side, a).above(h), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
    }

    private static void zombieInFront(MinecraftServer server, java.util.UUID id, double distance) {
        server.execute(() -> {
            var p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            var look = p.getLookAngle().multiply(1, 0, 1).normalize().scale(distance);
            var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(p.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (z == null) return;
            z.setPos(p.getX() + look.x, p.getY(), p.getZ() + look.z);
            z.setYRot(p.getYRot() + 180F);
            z.setNoAi(true);
            z.setSilent(true);
            p.level().addFreshEntity(z);
        });
    }

    private static int holes() {
        try {
            var f = Tracers.class.getDeclaredField("holesMade");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
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
        GunFeel.LOGGER.error("[port-smoke] FAIL {}: {}", current()[1], why);
        failures++;
        nextCase();
    }

    private static void nextCase() {
        MouseInput.fireButton(0, false);
        HbmKeybindsClient.reloadKey.setDown(false);
        if (++index >= CASES.size()) {
            index = CASES.size() - 1;
            next(Stage.HITBOXES);
        }
        else next(Stage.SELECT);
    }

    private static void finish() {
        GunFeel.LOGGER.info("[port-smoke] done: {} failed of {}", failures, CASES.size());
        next(Stage.DONE);
    }
}
