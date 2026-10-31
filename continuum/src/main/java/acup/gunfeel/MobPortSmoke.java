package acup.gunfeel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import dev.golem.core.client.MouseInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

// port/continuum: a dev check of the model hitboxes of NTM Continuum's mobs, in a singleplayer world
// (-Pntmgunfeel.mobsSmoke=true with -Pcontinuum.world=<save>). Per mob: spawn it in front without AI, F3+B on and a
// screenshot of its outlines (run-continuum/screenshots/mobsmoke_<mob>.png), then a short G3 burst at it. The log tells
// what the client captured (source, parts), whether the server took the pose, and where the server's bullet met the
// model ("[mobs-smoke]" lines; ModelHits' own "[modeldebug] server clip_model"). Then quits.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class MobPortSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.mobsSmoke");
    // entity id, distance in blocks, and the gun, its ammo and how many ticks to hold the trigger when not the G3's
    private static final List<String[]> ALL = List.of(
            new String[] {"hbm:entity_glyphid", "4"},
            new String[] {"hbm:entity_tesla_crab", "3.5"},
            new String[] {"hbm:entity_taintcrawler", "3.5"},
            new String[] {"hbm:entity_mob_mask_man", "7"},
            new String[] {"hbm:entity_cyber_crab", "3"},
            new String[] {"hbm:entity_mob_nuclear_creeper", "3.5"},
            new String[] {"hbm:entity_undead_soldier", "3.5"},
            new String[] {"minecraft:zombie", "3.5"},
            // a hitscan beam (EntityBulletBeamBase, MixinBeamModelQuery): the server's clip_model names the shooter
            new String[] {"minecraft:husk", "3.5", "gun_minigun_lacunae", "ammo_standard_capacitor", "40"});
    // -Pntmgunfeel.mobsCases=glyphid,zombie: only the mobs whose id holds one of these
    private static final List<String[]> CASES = ALL.stream().filter(c -> System.getProperty("ntmgunfeel.mobsCases") == null
            || java.util.Arrays.stream(System.getProperty("ntmgunfeel.mobsCases").split(",")).anyMatch(c[0]::contains)).toList();

    private enum Stage { WAIT, SPAWN, OUTLINE, GUN, RELOAD, RELOAD_WAIT, FIRE, AFTER, DONE }

    private record Capture(String source, int boxes, String parts) {}

    private static final Map<UUID, Capture> CAPTURED = new HashMap<>();
    private static final Map<UUID, Integer> ACCEPTED = new HashMap<>();
    private static Stage stage = Stage.WAIT;
    private static int index, ticks, stageTicks, failures;
    private static UUID target;
    private static String shot;

    private MobPortSmoke() {
    }

    static synchronized void captured(LivingEntity entity, List<ModelBox> boxes, String source) {
        if (!ENABLED) return;
        // each part with its triangles (a box with no mesh is solid)
        var parts = new java.util.TreeSet<String>();
        for (ModelBox b : boxes) parts.add(b.part() + (b.mesh() == 0 ? "" : ":" + ModelMesh.get(b.mesh()).triangles().length / 9));
        CAPTURED.put(entity.getUUID(), new Capture(source, boxes.size(), String.join(",", parts)));
    }

    static synchronized void accepted(LivingEntity entity, List<ModelBox> boxes) {
        if (ENABLED) ACCEPTED.put(entity.getUUID(), boxes.size());
    }

    private static String[] current() {
        return CASES.get(index);
    }

    private static String gun() {
        return current().length > 2 ? current()[2] : "gun_g3";
    }

    private static String name() {
        return current()[0].substring(current()[0].indexOf(':') + 1);
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
        if (++ticks > 20 * 240) {
            GunFeel.LOGGER.error("[mobs-smoke] timed out in case {} at {}", name(), stage);
            failures++;
            finish();
            return;
        }
        stageTicks++;
        var id = mc.player.getUUID();
        ItemStack held = mc.player.getMainHandItem();
        switch (stage) {
            case WAIT -> {
                // night: zombies would burn, and the fire hides their outlines
                if (stageTicks == 1) server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), "time set midnight"));
                if (stageTicks > 40) next(Stage.SPAWN);
            }
            case SPAWN -> {
                if (stageTicks == 1) {
                    String[] c = current();
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        p.getInventory().setItem(0, ItemStack.EMPTY);
                        p.getInventory().setSelectedSlot(0);
                        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(c[0]));
                        Entity mob = type.create(p.level(), EntitySpawnReason.COMMAND);
                        if (mob == null) return;
                        var look = Vec3.directionFromRotation(0F, p.getYRot()).normalize().scale(Double.parseDouble(c[1]));
                        mob.setPos(p.getX() + look.x, p.getY(), p.getZ() + look.z);
                        mob.setYRot(p.getYRot() + 180F);
                        mob.setYHeadRot(p.getYRot() + 180F);
                        if (mob instanceof LivingEntity l) l.yBodyRot = p.getYRot() + 180F;
                        if (mob instanceof Mob m) m.setNoAi(true);
                        mob.setSilent(true);
                        p.level().addFreshEntity(mob);
                    });
                }
                if (stageTicks > 5) {
                    var found = mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(12),
                            l -> l != mc.player && BuiltInRegistries.ENTITY_TYPE.getKey(l.getType()).toString().equals(current()[0]));
                    if (!found.isEmpty()) {
                        target = found.getFirst().getUUID();
                        aim(mc, found.getFirst());
                        mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
                        next(Stage.OUTLINE);
                    } else if (stageTicks > 60) fail("the mob never arrived");
                }
            }
            case OUTLINE -> {
                var mob = mob(mc);
                if (mob != null) aim(mc, mob);
                if (stageTicks == 30) shot = "mobsmoke_" + name() + ".png";
                if (stageTicks > 35) {
                    mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
                    Capture c;
                    Integer server1;
                    synchronized (MobPortSmoke.class) {
                        c = CAPTURED.get(target);
                        server1 = ACCEPTED.get(target);
                    }
                    GunFeel.LOGGER.info("[mobs-smoke] {} {}: client source={} boxes={} parts=[{}], server took a pose of {} boxes",
                            c != null && server1 != null ? "OK" : "FAIL", name(), c == null ? "none" : c.source(),
                            c == null ? 0 : c.boxes(), c == null ? "" : c.parts(), server1 == null ? "no" : server1);
                    if (c == null || server1 == null) failures++;
                    next(Stage.GUN);
                }
            }
            case GUN -> {
                if (stageTicks == 1) server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id);
                    if (p == null) return;
                    p.getInventory().setItem(0, stack(gun(), 1));
                    p.getInventory().setItem(35, stack(current().length > 3 ? current()[3] : "ammo_standard_r556_sp", 64));
                    p.inventoryMenu.broadcastChanges();
                });
                if (BuiltInRegistries.ITEM.getKey(held.getItem()).getPath().equals(gun()) && stageTicks > 10) next(Stage.RELOAD);
                else if (stageTicks > 100) fail("the gun never arrived");
            }
            case RELOAD -> {
                HbmKeybindsClient.reloadKey.setDown(stageTicks <= 2);
                if (stageTicks > 2) next(Stage.RELOAD_WAIT);
            }
            case RELOAD_WAIT -> {
                var mob = mob(mc);
                if (mob != null) aim(mc, mob);
                if (held.getItem() instanceof ItemGunBaseNT gun && gun.getPrimaryMagazine(held, 0) != null
                        && gun.getPrimaryMagazine(held, 0).getAmount(held, null) > 0
                        && ItemGunBaseNT.getState(held, 0) == ItemGunBaseNT.GunState.IDLE && stageTicks > 20) {
                    MouseInput.fireButton(0, true);
                    next(Stage.FIRE);
                } else if (stageTicks > 160) fail("no reload");
            }
            case FIRE -> {
                var mob = mob(mc);
                if (mob != null) aim(mc, mob);
                if (stageTicks > (current().length > 4 ? Integer.parseInt(current()[4]) : 3)) {
                    MouseInput.fireButton(0, false);
                    next(Stage.AFTER);
                }
            }
            case AFTER -> {
                if (stageTicks > 30) {
                    var mob = mob(mc);
                    GunFeel.LOGGER.info("[mobs-smoke] {} shot: health {} (see the server clip_model line for the part hit)",
                            name(), mob == null ? "dead" : String.format("%.1f/%.1f", mob.getHealth(), mob.getMaxHealth()));
                    server.execute(() -> {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p == null) return;
                        p.getInventory().setItem(0, ItemStack.EMPTY);
                        p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), l -> !(l instanceof net.minecraft.world.entity.player.Player))
                                .forEach(Entity::discard);
                    });
                    nextCase();
                }
            }
            default -> {
            }
        }
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

    private static LivingEntity mob(Minecraft mc) {
        if (target == null || mc.level == null) return null;
        for (Entity entity : mc.level.entitiesForRendering())
            if (entity.getUUID().equals(target) && entity instanceof LivingEntity l) return l;
        return null;
    }

    // the player looks at the middle of the mob's box (the server takes the client's view with its next move packet)
    private static void aim(Minecraft mc, LivingEntity mob) {
        var eye = mc.player.getEyePosition();
        var to = mob.getBoundingBox().getCenter().subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        // NTM's bullet starts a quarter block right of the eyes and flies along the view: aim that much left
        double r = Math.toRadians(yaw);
        to = to.add(Math.cos(r) * 0.25, 0, Math.sin(r) * 0.25);
        yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", id)), count);
    }

    private static void next(Stage s) {
        stage = s;
        stageTicks = 0;
    }

    private static void fail(String why) {
        GunFeel.LOGGER.error("[mobs-smoke] FAIL {}: {}", name(), why);
        failures++;
        nextCase();
    }

    private static void nextCase() {
        MouseInput.fireButton(0, false);
        HbmKeybindsClient.reloadKey.setDown(false);
        target = null;
        if (++index >= CASES.size()) {
            index = CASES.size() - 1;
            finish();
        } else next(Stage.SPAWN);
    }

    private static void finish() {
        GunFeel.LOGGER.info("[mobs-smoke] done: {} failed of {}", failures, CASES.size());
        next(Stage.DONE);
    }
}
