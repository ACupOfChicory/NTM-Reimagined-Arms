package acup.gunfeel;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.BulletConfigs;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

// port/continuum-bullets: a self-check of the server bullet mixins on NTM Continuum (this node's, no main counterpart),
// on the server once the world is up (the 26.1 client's own server will do): -Pntmgunfeel.bulletSmoke=<file>.
// Zombies without AI high in the air, real NTM bullets ticked by hand on the server thread, as BulletCheck does:
//   spread    a zombie fires a few rounds with spread from its eyes (MixinThrowableHit seeds the spread in a trace:
//             two runs with -Ptrace give the same headings, see the "fire" lines);
//   point     a zombie fires at one standing closer than its muzzle: the path starts at the eyes and hits it;
//   head      a bullet into a zombie's head and one into its body: the model's zones (MixinHeadDamage, HeadDamage);
//   explosive a 40 mm HE grenade into the head and into the body: the contact share follows the zone (ExplosiveDamage);
//   ricochet  a shallow shot off steel bounces and plays the mod's steel ricochet (MixinRicochetSound, test log);
//   hold aim  a gun with NTM's aim toggle: aim on when the key is pressed, off when it is let go (MixinHoldAim).
// One line per check to the file and the log ("[bullet-smoke]"), then the game stops (exit 1 if a check failed).
@EventBusSubscriber(modid = "ntmgunfeel")
public final class BulletSmoke {
    private static final String FILE = System.getProperty("ntmgunfeel.bulletSmoke");
    private static final int X = 300, Y = 120, Z = 300;
    private static final List<String> lines = new ArrayList<>();
    private static boolean ok = true;
    private static ServerLevel level;

    private BulletSmoke() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        if (FILE == null || FILE.isBlank()) return;
        int code;
        try {
            level = e.getServer().overworld();
            for (int cx = X / 16 - 1; cx <= (X + 210) / 16; cx++) for (int cz = Z / 16 - 1; cz <= Z / 16 + 1; cz++) {
                level.setChunkForced(cx, cz, true);
                // before the first tick the forced chunks are not loaded: creatures added there would stay hidden
                level.getChunk(cx, cz);
            }
            spread();
            pointBlank();
            zones();
            explosive();
            ricochet();
            holdAim();
            line(ok ? "ALL OK" : "SOME FAILED");
            code = ok ? 0 : 1;
        } catch (Throwable t) {
            line("FAIL crashed: " + t);
            for (StackTraceElement s : t.getStackTrace()) line("    at " + s);
            code = 1;
        }
        BulletCheck.finish(FILE, lines, code);
    }

    private static void spread() {
        Zombie shooter = zombie(X, Y, Z);
        BulletConfig plain = plain(), pellets = pellets();
        line("     plain round " + plain.id + ", pellets " + (pellets == null ? "none" : pellets.id));
        int off = 0;
        for (BulletConfig c : pellets == null ? List.of(plain) : List.of(plain, pellets)) {
            for (int i = 0; i < 4; i++) {
                EntityBulletBaseMK4 b = new EntityBulletBaseMK4(shooter, c, 10F, 4F, 0, 0, 0);
                Vec3 h = b.getDeltaMovement();
                line(String.format(Locale.ROOT, "     spread %s #%d heading %.9f %.9f %.9f", c.id, i, h.x, h.y, h.z));
                if (Math.abs(h.x) > 1e-6 || Math.abs(h.y) > 1e-6) off++;
                b.tick();
                b.discard();
            }
        }
        check("spread: the rounds leave off the line of sight", true, off > 0);
        shooter.discard();
    }

    // the target stands 0.6 blocks before the shooter's eyes, the muzzle 1 block: the bullet starts inside the target
    private static void pointBlank() {
        Zombie shooter = zombie(X + 20, Y, Z);
        Zombie target = zombie(X + 20, Y, Z + 0.6);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(shooter, plain(), 5F, 0F, 0, 0, 1.0);
        b.damage = 5F;
        float before = target.getHealth();
        for (int i = 0; i < 2 && !b.isRemoved(); i++) b.tick();
        check("point blank: the bullet from inside the target hits it", true, target.getHealth() < before);
        b.discard();
        shooter.discard();
        target.discard();
    }

    private static void zones() {
        float body = hit(X + 40, 0.9, 8F), head = hit(X + 44, 1.75, 8F);
        line(String.format(Locale.ROOT, "     zones: body took %.3f, head took %.3f", body, head));
        // a zombie's 2 armour points take 1.6 % of either (vanilla): the zones are compared with each other
        check("zones: the body takes the bullet's damage less the armour", true, Math.abs(body - 8F * 0.984F) < 1e-3F);
        check("zones: the head takes x1.75 of the body", true, Math.abs(head / body - 1.75F) < 1e-4F);
    }

    // a round straight along +z into a zombie (no shooter) at height fy over its feet: the health it lost
    private static float hit(double x, double fy, float damage) {
        Zombie target = zombie(x, Y, Z);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, plain(), 1F, 0F, 0F, 0F);
        b.setPos(x, Y + fy, Z - 3);
        b.shoot(0, 0, 1, 0F);
        b.damage = damage;
        float before = target.getHealth();
        for (int i = 0; i < 3 && !b.isRemoved(); i++) b.tick();
        float took = before - target.getHealth();
        b.discard();
        target.discard();
        return took;
    }

    private static void explosive() {
        BulletConfig he = BulletConfigs.get("hbm:g40_he");
        if (he == null) {
            fail("explosive: no hbm:g40_he");
            return;
        }
        float body = blast(he, X + 80, 0.9), head = blast(he, X + 120, 1.75);
        line(String.format(Locale.ROOT, "     explosive %s: body took %.3f, head took %.3f", he.id, body, head));
        check("explosive: the grenade hurts", true, body > 0F);
        check("explosive: its contact share in the head is more", true, head > body + 1F);
    }

    private static float blast(BulletConfig config, double x, double fy) {
        Zombie target = zombie(x, Y, Z);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, config, 1F, 0F, 0F, 0F);
        b.setPos(x, Y + fy, Z - 1.5);
        b.shoot(0, 0, 1, 0F);
        b.damage = 8F;
        float before = target.getHealth();
        for (int i = 0; i < 4 && !b.isRemoved(); i++) b.tick();
        float took = before - target.getHealth();
        b.discard();
        target.discard();
        return took;
    }

    // a round at half its ricochet angle onto a steel face: NTM bounces it; off steel the mod's ricochet sound plays instead of NTM's
    private static void ricochet() {
        BlockState steel = null;
        for (String id : new String[] {"hbm:block_steel", "hbm:steel_wall", "minecraft:iron_block"}) {
            BlockState s = state(id);
            if (s == null) continue;
            BulletRules.Sound sound = BulletRules.hit(s).sound();
            if (sound != null && sound.id().startsWith("ntmgunfeel:bullet.hit.steel") && BulletRules.ricochet(s)) {
                steel = s;
                break;
            }
        }
        if (steel == null) {
            fail("ricochet: no steel block with a ricochet in the bullet rules");
            return;
        }
        BlockPos at = new BlockPos(X + 160, Y, Z);
        for (int dz = 0; dz <= 8; dz++) level.setBlock(at.offset(0, 0, dz), steel, Block.UPDATE_CLIENTS);
        BulletConfig c = plain();
        line("     ricochet off " + BuiltInRegistries.BLOCK.getKey(steel.getBlock()) + ", " + c.id + " ricochet angle " + c.ricochetAngle);
        java.nio.file.Path log = TestLog.set(false, true);
        long before = size(log);
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        double a = Math.toRadians(Math.min(10.0, c.ricochetAngle * 0.5));
        // from 3 blocks before the row, down onto the middle of its third block's top (not onto a seam between two
        // blocks: there BulletServer.clip takes the next block as entered from its side, and NTM stops the round)
        b.setPos(at.getX() + .5, Y + 1 + 5.5 * Math.tan(a), at.getZ() - 3);
        b.shoot(0, -Math.sin(a), Math.cos(a), 0F);
        b.damage = 10F;
        b.tick();
        boolean bounced = !b.isRemoved() && b.getDeltaMovement().y > 0;
        b.discard();
        TestLog.set(false, false);
        String text = tail(log, before);
        check("ricochet: the round bounces off steel", true, bounced);
        check("ricochet: the test log has the mod's steel ricochet", true, text.contains("ricochet") && text.contains("sound=ours"));
    }

    private static void holdAim() {
        ItemGunBaseNT gun = null;
        ItemStack stack = null;
        for (String id : new String[] {"hbm:gun_g3", "hbm:gun_uzi", "hbm:gun_henry"}) {
            var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
            if (item.isEmpty() || !(item.get() instanceof ItemGunBaseNT g)) continue;
            ItemStack s = new ItemStack(g);
            if (g.getConfig(s, 0).getPressTertiary(s) == Lego.LAMBDA_TOGGLE_AIM) {
                gun = g;
                stack = s;
                break;
            }
        }
        if (gun == null) {
            fail("hold aim: no gun with NTM's aim toggle");
            return;
        }
        Zombie holder = zombie(X + 200, Y, Z);
        holder.setItemSlot(EquipmentSlot.MAINHAND, stack);
        line("     hold aim with " + BuiltInRegistries.ITEM.getKey(gun));
        gun.handleKeybind(holder, null, stack, EnumKeybind.GUN_TERTIARY, true);
        check("hold aim: pressed, it aims", true, ItemGunBaseNT.getIsAiming(stack));
        gun.handleKeybind(holder, null, stack, EnumKeybind.GUN_TERTIARY, false);
        check("hold aim: let go, it stops aiming", false, ItemGunBaseNT.getIsAiming(stack));
        holder.discard();
    }

    private static Zombie zombie(double x, double y, double z) {
        Zombie zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.setPos(x, y, z);
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setYRot(0);
        zombie.setYHeadRot(0);
        zombie.yBodyRot = 0;
        zombie.setXRot(0);
        level.addFreshEntity(zombie);
        return zombie;
    }

    // as BulletCheck: a plain, single, fast round without an impact effect, the least gravity and spread
    private static BulletConfig plain() {
        BulletConfig best = null;
        for (BulletConfig c : BulletConfigs.all()) {
            if (c == null || c.velocity < 5F || c.onImpact != null || c.doesPenetrate || c.projectilesMax > 1
                    || c.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET) continue;
            if (best == null || c.gravity + c.spread < best.gravity + best.spread) best = c;
        }
        return best;
    }

    // a shotgun's: several fast plain pellets
    private static BulletConfig pellets() {
        for (BulletConfig c : BulletConfigs.all())
            if (c != null && c.velocity >= 5F && c.onImpact == null && c.projectilesMax > 1) return c;
        return null;
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    private static long size(java.nio.file.Path p) {
        try {
            return p == null ? 0 : Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String tail(java.nio.file.Path p, long from) {
        try {
            byte[] all = Files.readAllBytes(p);
            return new String(all, (int) Math.min(from, all.length), (int) Math.max(0, all.length - from), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void check(String name, Object want, Object got) {
        boolean pass = java.util.Objects.equals(want, got);
        ok &= pass;
        line((pass ? "OK   " : "FAIL ") + name + (pass ? "" : ": want " + want + ", got " + got));
    }

    private static void fail(String name) {
        ok = false;
        line("FAIL " + name);
    }

    private static void line(String s) {
        lines.add(s);
        GunFeel.LOGGER.info("[bullet-smoke] {}", s);
    }
}
