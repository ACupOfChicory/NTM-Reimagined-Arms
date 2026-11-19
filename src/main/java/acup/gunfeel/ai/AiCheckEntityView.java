package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import acup.gunfeel.EntityBody;
import acup.gunfeel.LevelEntities;
import acup.gunfeel.Prone;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.mixin.mc.EntityAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

/**
 * The AI check's entities for core (EntityView, Body: 26.2's LevelEntities, EntityBody), against the game's own
 * getters on live entities: an NTM test dummy (a mob, on a team), an armour stand (living, no mob) and an arrow (not
 * living), on a floor of their own. Every Body method is the entity's getter, standing and again the same tick after
 * it is moved, turned, crouched and pushed; one Body an entity, its number its own; near is getEntitiesOfClass, in its
 * order, nothing cut; the living-only ones throw on the arrow. Then the stand is removed with its Body held (gone by
 * its number, the Body read as left) and the dummy goes to the nether (its old Body removed, the one there another).
 */
final class AiCheckEntityView {
    static final int X = -200, Z = -200, SETTLE = 10;
    static final String TEAM = "armsai_entityview";

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int t = -1;
    private Mob dummy;
    private LivingEntity stand;
    private Projectile arrow;
    private final List<String> wrong = new ArrayList<>();

    AiCheckEntityView(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        t++;
        if (t == 0) {
            level.setChunkForced(X >> 4, Z >> 4, true);
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    level.setBlockAndUpdate(new BlockPos(X + dx, y, Z + dz), Blocks.STONE.defaultBlockState());
                    for (int up = 1; up < 5; up++) level.setBlockAndUpdate(new BlockPos(X + dx, y + up, Z + dz), Blocks.AIR.defaultBlockState());
                }
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"));
            dummy = (Mob) type.create(level, EntitySpawnReason.COMMAND);
            dummy.setPos(X + 0.5, y + 1, Z + 0.5);
            level.addFreshEntity(dummy);
            PlayerTeam team = level.getScoreboard().getPlayerTeam(TEAM);
            if (team == null) team = level.getScoreboard().addPlayerTeam(TEAM);
            level.getScoreboard().addPlayerToTeam(dummy.getScoreboardName(), team);
            stand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setPos(X + 2.5, y + 1, Z + 0.5);
            level.addFreshEntity(stand);
            arrow = net.minecraft.world.entity.EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
            arrow.setPos(X - 1.5, y + 2, Z + 1.5);
            arrow.setNoGravity(true);
            arrow.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(arrow);
            return false;
        }
        if (t < SETTLE) return false;
        EntityView view = new LevelEntities(level);
        for (Entity e : List.of(dummy, stand, arrow)) same("standing " + name(e), e);
        oneBody(view);
        near(view);
        livingOnly();
        dummy.setPos(dummy.getX() + 0.3, dummy.getY(), dummy.getZ() - 0.2);
        dummy.setYRot(37);
        dummy.setYHeadRot(55);
        dummy.setXRot(12);
        dummy.setPose(Pose.CROUCHING);
        dummy.setDeltaMovement(0.1, 0.2, 0.3);
        dummy.setSprinting(true);
        same("moved, turned, crouched and pushed the same tick: dummy", dummy);
        check.check("entityview: the dummy crouching is crouching to its Body", EntityBody.of(dummy).crouching());
        removed(view);
        dimension(view);
        check.out(String.format(Locale.ROOT, "     entityview: %d differences from the game's getters%s", wrong.size(),
                wrong.isEmpty() ? "" : ": " + String.join("; ", wrong.subList(0, Math.min(12, wrong.size())))));
        check.check("entityview: every Body method is the entity's getter", wrong.isEmpty());
        arrow.discard();
        level.getScoreboard().removePlayerTeam(level.getScoreboard().getPlayerTeam(TEAM));
        level.setChunkForced(X >> 4, Z >> 4, false);
        return true;
    }

    private static String name(Entity e) {
        return e instanceof Mob ? "dummy" : e instanceof LivingEntity ? "stand" : "arrow";
    }

    private void eq(String what, Object body, Object game) {
        if (!Objects.equals(body, game)) wrong.add(what + " " + body + " vs " + game);
    }

    private void eq3(String what, double[] body, Vec3 game) {
        if (body[0] != game.x || body[1] != game.y || body[2] != game.z) wrong.add(what + " " + body[0] + "," + body[1] + "," + body[2] + " vs " + game);
    }

    /** every Body method against the entity's getter, now */
    private void same(String at, Entity e) {
        Body b = EntityBody.of(e);
        String w = at + ": ";
        eq(w + "id", b.id(), e.getId());
        eq(w + "living", b.living(), e instanceof LivingEntity);
        eq(w + "player", b.player(), e instanceof net.minecraft.world.entity.player.Player);
        eq(w + "x", b.x(), e.getX());
        eq(w + "y", b.y(), e.getY());
        eq(w + "z", b.z(), e.getZ());
        eq(w + "blockX", b.blockX(), e.blockPosition().getX());
        eq(w + "blockY", b.blockY(), e.blockPosition().getY());
        eq(w + "blockZ", b.blockZ(), e.blockPosition().getZ());
        eq(w + "bbHeight", b.bbHeight(), e.getBbHeight());
        eq(w + "eyeHeight", b.eyeHeight(), e.getEyeHeight());
        eq(w + "eyeHeight stand", b.eyeHeight(Stance.STAND), e.getDimensions(Pose.STANDING).eyeHeight());
        eq(w + "eyeHeight crouch", b.eyeHeight(Stance.CROUCH), e.getDimensions(Pose.CROUCHING).eyeHeight());
        eq(w + "height stand", b.height(Stance.STAND), e.getDimensions(Pose.STANDING).height());
        eq(w + "height crouch", b.height(Stance.CROUCH), e.getDimensions(Pose.CROUCHING).height());
        double[] o = new double[6];
        b.eye(o);
        eq3(w + "eye", o, e.getEyePosition());
        b.look(o);
        eq3(w + "look", o, e.getLookAngle());
        b.view(0.5F, o);
        eq3(w + "view", o, e.getViewVector(0.5F));
        b.motion(o);
        eq3(w + "motion", o, e.getDeltaMovement());
        b.lastSpeed(o);
        eq3(w + "lastSpeed", o, ((EntityAccess) e).gunfeel$lastKnownSpeed());
        b.box(o);
        AABB box = e.getBoundingBox();
        if (o[0] != box.minX || o[1] != box.minY || o[2] != box.minZ || o[3] != box.maxX || o[4] != box.maxY || o[5] != box.maxZ) wrong.add(w + "box");
        eq(w + "yRot", b.yRot(), e.getYRot());
        eq(w + "yHeadRot", b.yHeadRot(), e.getYHeadRot());
        eq(w + "xRot", b.xRot(), e.getXRot());
        eq(w + "onGround", b.onGround(), e.onGround());
        eq(w + "inWater", b.inWater(), e.isInWater());
        eq(w + "passenger", b.passenger(), e.isPassenger());
        // the same generator: from one seed, one number through the Body, the same straight from the entity
        e.getRandom().setSeed(20261120L);
        float r = b.random().nextFloat();
        e.getRandom().setSeed(20261120L);
        eq(w + "random", r, e.getRandom().nextFloat());
        eq(w + "crouching", b.crouching(), e.getPose() == Pose.CROUCHING);
        eq(w + "poseName", b.poseName(), String.valueOf(e.getPose()));
        eq(w + "sprinting", b.sprinting(), e.isSprinting());
        eq(w + "alive", b.alive(), e.isAlive());
        eq(w + "removed", b.removed(), e.isRemoved());
        eq(w + "team", b.team(), e.getTeam() == null ? null : e.getTeam().getName());
        if (e instanceof LivingEntity le) {
            eq(w + "climbing", b.climbing(), le.onClimbable());
            eq(w + "lying", b.lying(), Prone.lying(le));
            eq(w + "speed", b.speed(), le.getAttributeValue(Attributes.MOVEMENT_SPEED));
        }
        if (e instanceof Mob m) eq(w + "leftHanded", b.leftHanded(), m.isLeftHanded());
    }

    private void oneBody(EntityView view) {
        boolean ok = true;
        for (Entity e : List.of(dummy, stand, arrow)) {
            Body b = view.byId(e.getId());
            ok &= b != null && b == view.byId(e.getId()) && b == EntityBody.of(e) && b.id() == e.getId();
        }
        check.check("entityview: one Body an entity, the same each time, its number the entity's", ok);
        check.check("entityview: no Body by a number no entity has", view.byId(Integer.MIN_VALUE) == null);
        check.check("entityview: the dummy's team is its Body's", TEAM.equals(EntityBody.of(dummy).team()) && EntityBody.of(stand).team() == null);
    }

    private void near(EntityView view) {
        AABB box = new AABB(X - 4, y - 1, Z - 4, X + 5, y + 5, Z + 5);
        boolean ok = true;
        int[] kinds = {EntityView.LIVING, EntityView.MOB, EntityView.PROJECTILE};
        List<Class<? extends Entity>> classes = List.of(LivingEntity.class, Mob.class, Projectile.class);
        StringBuilder counts = new StringBuilder();
        for (int k = 0; k < kinds.length; k++) {
            List<? extends Entity> game = level.getEntitiesOfClass(classes.get(k), box);
            Body[] out = new Body[game.size() + 3];
            int n = view.near(kinds[k], box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, out);
            ok &= n == game.size();
            for (int i = 0; i < Math.min(n, game.size()); i++) ok &= out[i] == EntityBody.of(game.get(i));
            if (!game.isEmpty()) ok &= view.near(kinds[k], box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, new Body[game.size() - 1]) == -game.size();
            counts.append(' ').append(game.size());
        }
        check.out("     entityview: near living, mobs, projectiles:" + counts);
        check.check("entityview: near is the game's getEntitiesOfClass, its order, nothing cut (living 2, mobs 1, projectiles 1)",
                ok && counts.toString().equals(" 2 1 1"));
    }

    private void livingOnly() {
        Body a = EntityBody.of(arrow), s = EntityBody.of(stand);
        check.check("entityview: on the arrow (not living) the living ones throw", throwsOn(a::climbing) && throwsOn(a::speed) && throwsOn(a::lying)
                && throwsOn(a::leftHanded));
        check.check("entityview: on the stand (living, no mob) leftHanded throws", throwsOn(s::leftHanded));
    }

    private static boolean throwsOn(Supplier<?> s) {
        try {
            s.get();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void removed(EntityView view) {
        Body held = EntityBody.of(stand);
        double x = stand.getX(), sy = stand.getY(), z = stand.getZ();
        int id = stand.getId();
        stand.discard();
        same("removed, its Body held: stand", stand);
        check.check("entityview: a removed one is gone by its number, its held Body removed and where it was",
                view.byId(id) == null && held.removed() && !held.alive() && held.x() == x && held.y() == sy && held.z() == z && held == EntityBody.of(stand));
    }

    private void dimension(EntityView view) {
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            check.out("     entityview: no nether here, the change of dimension not checked");
            return;
        }
        Body held = EntityBody.of(dummy);
        int id = dummy.getId();
        Entity there = dummy.teleport(new TeleportTransition(nether, new Vec3(X + 0.5, 64, Z + 0.5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        boolean ok = held.removed() && view.byId(id) == null && there != null && EntityBody.of(there) != held;
        check.out(String.format(Locale.ROOT, "     entityview: to the nether: went %s, held removed %s (%s), by number here %s, there %s",
                there == null ? "no" : there == dummy ? "the same object" : "another object", held.removed(), dummy.getRemovalReason(),
                view.byId(id) == null ? "none" : "still", there == null ? "-" : there.level().dimension().identifier()));
        same("gone to the nether, its Body held: dummy", dummy);
        if (there != null) {
            // by its number there as the game has it there now (it may join that level only later)
            Entity found = nether.getEntity(there.getId());
            ok &= new LevelEntities(nether).byId(there.getId()) == (found == null ? null : EntityBody.of(found));
            check.out("     entityview: in the nether by its number the game finds it " + (found == null ? "not yet" : found == there ? "(the same)" : "(another!)"));
            there.discard();
        }
        check.check("entityview: gone to another dimension, its held Body is the one left (removed), the one there another", ok);
    }
}
