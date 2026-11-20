package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import acup.gunfeel.EntityBody;
import acup.gunfeel.LevelEntities;
import acup.gunfeel.Prone;
import acup.gunfeel.CoreServices;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
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
            // a tag on the stand and the arrow (Body.tagged), none on the dummy
            stand.addTag("gunfeel_check");
            level.addFreshEntity(stand);
            arrow = net.minecraft.world.entity.EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
            arrow.setPos(X - 1.5, y + 2, Z + 1.5);
            arrow.addTag("gunfeel_check");
            arrow.setNoGravity(true);
            arrow.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(arrow);
            river();
            return false;
        }
        if (t < SETTLE) return false;
        EntityView view = new LevelEntities(level);
        for (Entity e : List.of(dummy, stand, arrow)) same("standing " + name(e), e);
        commanderPorts();
        oneBody(view);
        near(view);
        players(view);
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
        // BodyControl.stance: the game's pose it sets is the stance its Body reads back (lying: the swimming pose)
        BodyControl control = CoreServices.load(BodyControl.class, BodyControl.MISSING);
        Body db = EntityBody.of(dummy);
        boolean set = true;
        for (byte s : new byte[] {Stance.PRONE, Stance.STAND, Stance.CROUCH, Stance.PRONE}) {
            control.stance(db, s);
            set &= db.stance() == s && dummy.getPose() == (s == Stance.STAND ? Pose.STANDING : s == Stance.CROUCH ? Pose.CROUCHING : Pose.SWIMMING);
        }
        same("lying by BodyControl: dummy", dummy);
        check.check("entityview: the dummy lying is crawling to its Body, as the game's isVisuallyCrawling", db.crawling() && dummy.isVisuallyCrawling());
        // a player (the server's fake one): flying as in creative and gliding are its Body's flying and fallFlying
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        player.setPos(X + 0.5, y + 1, Z - 1.5);
        player.getAbilities().flying = true;
        player.startFallFlying();
        same("flying and gliding: player", player);
        Body pb = EntityBody.of(player);
        boolean flew = pb.flying() && pb.fallFlying() && player.isFallFlying();
        player.getAbilities().flying = false;
        player.stopFallFlying();
        same("landed: player", player);
        check.check("entityview: a player flying and gliding is flying and fallFlying to its Body, and not once landed",
                flew && !pb.flying() && !pb.fallFlying());
        check.check("entityview: BodyControl.stance sets the game's pose, the Body's stance() reads it back", set);
        control.stance(db, Stance.CROUCH);
        // BodyControl's walk: the game's navigation takes the cells as one path (a node a cell, its end the last), a stop
        // ends it; want sets the MoveControl's point; the dummy's MoveControl is the game's, never stuck
        int cx = dummy.getBlockX(), cy = dummy.getBlockY(), cz = dummy.getBlockZ();
        long[] cells = {BlockPos.asLong(cx, cy, cz), BlockPos.asLong(cx + 1, cy, cz), BlockPos.asLong(cx + 2, cy, cz)};
        boolean walked = control.walk(db, cells, true, 1.0) && !control.done(db) && !dummy.getNavigation().isDone();
        net.minecraft.world.level.pathfinder.Path path = dummy.getNavigation().getPath();
        walked &= path != null && path.getNodeCount() == 3 && path.getTarget().equals(BlockPos.of(cells[2])) && path.canReach()
                && path.getEndNode() != null && path.getEndNode().asBlockPos().equals(BlockPos.of(cells[2]))
                && control.pathNext(db) == path.getNextNodeIndex();
        control.speed(db, 0.5);
        walked &= !control.done(db);
        control.stop(db);
        walked &= control.done(db) && dummy.getNavigation().isDone() && control.pathNext(db) == 0;
        control.want(db, cx + 0.5, cy, cz + 0.25, 0.7);
        net.minecraft.world.entity.ai.control.MoveControl mc = dummy.getMoveControl();
        walked &= mc.hasWanted() && mc.getWantedX() == cx + 0.5 && mc.getWantedY() == cy && mc.getWantedZ() == cz + 0.25 && mc.getSpeedModifier() == 0.7
                && !control.stuck(db);
        check.check("entityview: BodyControl walks the cells as the game's path, stops it, sets the MoveControl's point", walked);
        // BodyControl's turn: each the game's setter, read back by the Body and the game's getters; the game's
        // MoveControl never turned to a path, face nothing to it; lookAt sets the LookControl's point
        control.yRot(db, 21F);
        control.yHeadRot(db, -33F);
        control.xRot(db, 17F);
        control.yBodyRot(db, 23F);
        boolean turnSet = db.yRot() == 21F && dummy.getYRot() == 21F && db.yHeadRot() == -33F && dummy.getYHeadRot() == -33F
                && db.xRot() == 17F && dummy.getXRot() == 17F && dummy.yBodyRot == 23F && !control.turned(db);
        control.face(db, 21F);
        control.lookAt(db, cx + 0.5, cy + 1.25, cz + 5.5, 10F, 20F);
        net.minecraft.world.entity.ai.control.LookControl lc = dummy.getLookControl();
        turnSet &= lc.getWantedX() == cx + 0.5 && lc.getWantedY() == cy + 1.25 && lc.getWantedZ() == cz + 5.5;
        check.check("entityview: BodyControl turns the body, head and look by the game's setters, the Body reads them back", turnSet);
        // BodyControl.place: the game's setPos on a mob and on what is no mob (the arrow); the Body reads the place and
        // the box there at once, as the game's getters do; each put back after
        boolean placed = true;
        for (Entity e : List.of(dummy, arrow)) {
            Body eb = EntityBody.of(e);
            double x0 = e.getX(), y0 = e.getY(), z0 = e.getZ();
            control.place(eb, x0 + 0.75, y0 + 0.5, z0 - 0.25);
            double[] box = new double[6];
            eb.box(box);
            net.minecraft.world.phys.AABB bb = e.getBoundingBox();
            placed &= eb.x() == x0 + 0.75 && e.getX() == x0 + 0.75 && eb.y() == y0 + 0.5 && e.getY() == y0 + 0.5 && eb.z() == z0 - 0.25 && e.getZ() == z0 - 0.25
                    && box[0] == bb.minX && box[1] == bb.minY && box[2] == bb.minZ && box[3] == bb.maxX && box[4] == bb.maxY && box[5] == bb.maxZ
                    && bb.minY == y0 + 0.5;
            same("placed by BodyControl: " + name(e), e);
            control.place(eb, x0, y0, z0);
            placed &= eb.x() == x0 && e.getX() == x0;
        }
        check.check("entityview: BodyControl.place is the game's setPos, on a mob and on an arrow; the Body reads the place and its box at once", placed);
        spawnPlaces();
        worldReads();
        groundAlike();
        // ItemTable: the item in hand by its number is the registry's id, the same String each time; air 0; no item for
        // a number none has
        acup.gunfeel.host.ItemTable items = CoreServices.load(acup.gunfeel.host.ItemTable.class, acup.gunfeel.host.ItemTable.MISSING);
        net.minecraft.world.item.ItemStack before = dummy.getMainHandItem();
        dummy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
        int stick = db.held(Body.MAIN_HAND);
        String name = items.name(stick);
        boolean named = name.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.STICK).toString())
                && name.equals("minecraft:stick") && items.name(stick) == name && items.name(acup.gunfeel.host.ItemTable.AIR).equals("minecraft:air");
        try {
            items.name(Integer.MAX_VALUE);
            named = false;
        } catch (IllegalArgumentException e) {
            // none has it
        }
        dummy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, before);
        check.check("entityview: ItemTable names the item in hand by its number as the registry does, the same String each time", named);
        // Body.worn: each armour slot's item by its number, named by ItemTable as the registry does (a piece in a slot of
        // another kind too, NTM's chestplate); an empty slot and a stack of none 0; no Body.worn on an arrow
        net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
        net.minecraft.world.item.ItemStack[] dressed = new net.minecraft.world.item.ItemStack[4];
        for (int i = 0; i < 4; i++) dressed[i] = dummy.getItemBySlot(slots[i]);
        net.minecraft.world.item.Item ntmPlate = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "steel_plate"));
        net.minecraft.world.item.ItemStack none = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BOOTS);
        none.setCount(0);
        String[] want = {"minecraft:diamond_boots", "hbm:steel_plate", "minecraft:air", "minecraft:air"};
        net.minecraft.world.item.ItemStack[] put = {new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS), new net.minecraft.world.item.ItemStack(ntmPlate),
                net.minecraft.world.item.ItemStack.EMPTY, none};
        boolean worn = ntmPlate != net.minecraft.world.item.Items.AIR;
        for (int i = 0; i < 4; i++) dummy.setItemSlot(slots[i], put[i]);
        for (int i = 0; i < 4; i++) {
            int n = db.worn(i);
            worn &= items.name(n).equals(want[i]) && (n == acup.gunfeel.host.ItemTable.AIR) == (i >= 2)
                    && items.name(n).equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(dummy.getItemBySlot(slots[i]).getItem()).toString());
        }
        worn &= db.worn(Body.HEAD) != db.held(Body.MAIN_HAND) && Body.HEAD == 0 && Body.FEET == 3;
        try {
            EntityBody.of(arrow).worn(Body.CHEST);
            worn = false;
        } catch (RuntimeException e) {
            // the living only
        }
        for (int i = 0; i < 4; i++) dummy.setItemSlot(slots[i], dressed[i]);
        check.check("entityview: Body.worn is the item in each armour slot by its number, an empty slot and a stack of none air; an arrow has none", worn);
        roles();
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

    /**
     * core's SpawnPlace against the game's code it was moved from (AiSpawn.place as it was, below), on this level: from
     * the same seed, over the floor, at its edge and high in the air, for one, four and nine mobs, each mob put the same
     * way to the bit (the place, its turns, free or not)
     */
    private void spawnPlaces() {
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"));
        Vec3[] points = {new Vec3(X + 0.5, y + 1, Z + 0.5), new Vec3(X + 4.5, y + 1.3, Z - 3.5), new Vec3(X + 0.5, y + 24, Z + 0.5)};
        int tried = 0, same = 0, free = 0;
        for (Vec3 at : points)
            for (int count : new int[] {1, 4, 9})
                for (long seed = 1; seed <= 12; seed++) {
                    Mob was = (Mob) type.create(level, EntitySpawnReason.COMMAND), now = (Mob) type.create(level, EntitySpawnReason.COMMAND);
                    // the same start (a mob is made turned a random way): one never put keeps it
                    for (Mob m : new Mob[] {was, now}) {
                        m.setYRot(0);
                        m.setYHeadRot(0);
                        m.setYBodyRot(0);
                    }
                    boolean a = gamePlace(level, was, at, net.minecraft.util.RandomSource.create(seed), count);
                    boolean b = SpawnPlace.place(acup.gunfeel.Wiring.world(level), EntityBody.of(now), at.x, at.y, at.z,
                            acup.gunfeel.GameRandoms.of(net.minecraft.util.RandomSource.create(seed)), count);
                    tried++;
                    if (a) free++;
                    if (a == b && was.getX() == now.getX() && was.getY() == now.getY() && was.getZ() == now.getZ() && was.getYRot() == now.getYRot()
                            && was.getYHeadRot() == now.getYHeadRot() && was.yBodyRot == now.yBodyRot) same++;
                    else if (tried - same <= 3) check.out(String.format(Locale.ROOT, "     entityview: unlike at %s count %d seed %d: game %b %.4f %.4f %.4f %.3f/%.3f/%.3f, core %b %.4f %.4f %.4f %.3f/%.3f/%.3f", at, count, seed,
                            a, was.getX(), was.getY(), was.getZ(), was.getYRot(), was.getYHeadRot(), was.yBodyRot, b, now.getX(), now.getY(), now.getZ(), now.getYRot(), now.getYHeadRot(), now.yBodyRot));
                    was.discard();
                    now.discard();
                }
        check.out(String.format(Locale.ROOT, "     entityview: SpawnPlace and the game's code: %d of %d alike, %d of them free", same, tried, free));
        check.check("entityview: SpawnPlace puts a mob as AiSpawn's own code did, to the bit, from the same seed", same == tried && free > 0 && free < tried);
    }

    /**
     * WorldView's tops, chunks and lowest y as the game's own (every heightmap, by the stand's floor and off it; a kind
     * the game has not throws), and the water's port (LevelFluids, StaffGround's liquids) as getFluidState: none where
     * there is none, water where the game's water tag is, on a source, flowing water, lava and a slab with water in it
     */
    private void worldReads() {
        acup.gunfeel.host.WorldView w = acup.gunfeel.Wiring.world(level);
        var kinds = net.minecraft.world.level.levelgen.Heightmap.Types.values();
        boolean tops = kinds.length == 6;
        for (int[] c : new int[][] {{X, Z}, {X + 3, Z - 2}, {X - 40, Z + 25}})
            for (int k = 0; k < kinds.length; k++) tops &= w.height(c[0], c[1], k) == level.getHeight(kinds[k], c[0], c[1]);
        for (int k : new int[] {-1, kinds.length}) {
            try {
                w.height(X, Z, k);
                tops = false;
            } catch (IllegalArgumentException e) {
                // the game has no such heightmap
            }
        }
        tops &= w.hasChunk(X >> 4, Z >> 4) == level.hasChunk(X >> 4, Z >> 4) && w.hasChunk(X >> 4, Z >> 4)
                && w.hasChunk(100_000 >> 4, 100_000 >> 4) == level.hasChunk(100_000 >> 4, 100_000 >> 4) && !w.hasChunk(100_000 >> 4, 100_000 >> 4)
                && w.minY() == level.getMinY();
        check.check("entityview: WorldView's tops by every heightmap, its chunks loaded and its lowest y are the game's", tops);
        // a row of liquids on the floor, each alone (no updates: none flows meanwhile), then air again
        var water = Blocks.WATER.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState[] row = {water, water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3),
                Blocks.LAVA.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true),
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()};
        var fluids = acup.gunfeel.water.LevelFluids.of(level);
        boolean same = true;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            BlockPos p = new BlockPos(X - 4 + i, y + 1, Z + 4);
            level.setBlock(p, row[i], 2 | 16);
            var fs = level.getFluidState(p);
            var cell = fluids.at(p.getX(), p.getY(), p.getZ());
            boolean empty = cell == null, wet = cell != null && cell.liquid() == acup.gunfeel.water.LiquidEvents.Liquid.WATER;
            same &= empty == fs.isEmpty() && wet == fs.is(net.minecraft.tags.FluidTags.WATER);
            seen.append(i == 0 ? "" : ", ").append(empty ? "none" : cell.liquid());
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        check.out("     entityview: the water's port on a source, flowing water, lava, a waterlogged slab, air, stone: " + seen);
        check.check("entityview: LevelFluids.at is getFluidState: none where it is empty, water where the game's water tag is", same
                && seen.toString().equals("WATER, WATER, LAVA, WATER, none, none"));
    }

    /** the staff's ground (StaffGround) checked over a river east of the floor: RX its middle line, its banks' top y */
    private static final int RX = X + 24, GROUND = 12;

    /**
     * Stone ground over GROUND blocks each way of (RX, Z), its top at y; a river along z at RX - 1 to RX + 1, two blocks of
     * water (y and y - 1) but a ford at Z + 3 (y only); stone towers on the bank, 4 high at RX - 6 (more than a sleeping
     * soldier climbs a step) and 3 high at RX + 6; the tactical map wanted over it all. No updates: the water stays put.
     */
    private void river() {
        for (int cx = (RX - GROUND - 1) >> 4; cx <= (RX + GROUND + 1) >> 4; cx++)
            for (int cz = (Z - GROUND - 1) >> 4; cz <= (Z + GROUND + 1) >> 4; cz++) level.setChunkForced(cx, cz, true);
        var stone = Blocks.STONE.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = RX - GROUND; x <= RX + GROUND; x++)
            for (int z = Z - GROUND; z <= Z + GROUND; z++) {
                boolean river = Math.abs(x - RX) <= 1, ford = river && z == Z + 3;
                int tower = x == RX - 6 && Math.abs(z - Z + 5) <= 1 ? 4 : x == RX + 6 && Math.abs(z - Z - 6) <= 1 ? 3 : 0;
                level.setBlock(new BlockPos(x, y - 2, z), stone, 2 | 16);
                level.setBlock(new BlockPos(x, y - 1, z), river && !ford ? water : stone, 2 | 16);
                level.setBlock(new BlockPos(x, y, z), river ? water : stone, 2 | 16);
                for (int up = 1; up <= 6; up++) level.setBlock(new BlockPos(x, y + up, z), up <= tower ? stone : air, 2 | 16);
            }
        TacticalMap.of(level).want(RX, y + 1, Z, GROUND - 4, 3);
    }

    /**
     * StaffGround's detour and stand against AiCommander's own former code (d927fbe, below), on the river: detour from
     * starts all over it to points across the river, along the bank and back on dry ground, its way the same block for
     * block (or null alike); stand over every column round the river, at three heights of the soldier's own, by the map
     * as far as it is built now (built cells and not yet built alike)
     */
    private void groundAlike() {
        acup.gunfeel.host.WorldView w = acup.gunfeel.Wiring.world(level);
        var fluids = acup.gunfeel.water.LevelFluids.of(level);
        TacticalMap map = TacticalMap.of(level);
        int ways = 0, nulls = 0, detours = 0, alike = 0, edge = 0;
        for (int sx = RX - 10; sx <= RX + 10; sx += 2)
            for (int sz = Z - 10; sz <= Z + 10; sz += 4) {
                int y0 = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
                for (int r = (sx - AiCommander.DETOUR) >> 4; r <= (sx + AiCommander.DETOUR) >> 4; r++)
                    for (int q = (sz - AiCommander.DETOUR) >> 4; q <= (sz + AiCommander.DETOUR) >> 4; q++)
                        if (!level.hasChunk(r, q)) {
                            edge++;
                            r = Integer.MAX_VALUE - 1;
                            break;
                        }
                for (double[] to : new double[][] {{RX + 20.5, sz + 0.5}, {sx + 0.5, Z + 20.5}, {RX - 20.5, sz + 0.5}}) {
                    long[] was = gameDetour(level, sx, sz, y0, to[0], to[1]);
                    long[] now = StaffGround.detour(w, fluids, sx, sz, y0, to[0], to[1]);
                    detours++;
                    if (java.util.Arrays.equals(was, now)) alike++;
                    if (was == null) nulls++;
                    else ways++;
                }
            }
        // at the edge of the loaded chunks east of the floor (the first one not loaded, the one before it loaded): from
        // its last loaded blocks to points round them
        int border = Integer.MIN_VALUE;
        for (int cx = (X >> 4) + 1; cx < (X >> 4) + 400 && border == Integer.MIN_VALUE; cx++)
            if (!level.hasChunk(cx, Z >> 4) && level.hasChunk(cx - 1, Z >> 4)) border = cx << 4;
        boolean lone = border != Integer.MIN_VALUE;
        int[][] edges = {{border - 1, Z}, {border - 3, Z + 5}, {border - 8, Z - 6}, {border - 12, Z + 2}, {border - 1, Z - 11}};
        if (lone)
            for (int[] e : edges) {
                int y0 = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e[0], e[1]);
                edge++;
                for (double[] to : new double[][] {{e[0] + 20.5, e[1] + 0.5}, {e[0] - 20.5, e[1] + 3.5}, {e[0] + 0.5, e[1] + 20.5}, {e[0] + 4.5, e[1] - 20.5}}) {
                    long[] was = gameDetour(level, e[0], e[1], y0, to[0], to[1]);
                    long[] now = StaffGround.detour(w, fluids, e[0], e[1], y0, to[0], to[1]);
                    detours++;
                    if (java.util.Arrays.equals(was, now)) alike++;
                    if (was == null) nulls++;
                    else ways++;
                }
            }
        check.out(String.format(Locale.ROOT, "     entityview: StaffGround.detour and AiCommander's own: %d of %d alike, %d of them a way, %d none; %d starts with a chunk not loaded in reach",
                alike, detours, ways, nulls, edge));
        check.check("entityview: StaffGround.detour finds AiCommander's own way round the river, block for block", alike == detours && ways > 0 && nulls > 0 && lone);
        int stands = 0, standAlike = 0, cells = 0, tops = 0, none = 0;
        for (int bx = RX - 10; bx <= RX + 10; bx++)
            for (int bz = Z - 10; bz <= Z + 10; bz++)
                for (int own : new int[] {y + 1, y + 5, y - 3}) {
                    long was = gameStand(level, bx, bz, own), now = StaffGround.stand(w, map, bx, bz, own);
                    stands++;
                    if (was == now) standAlike++;
                    if (was == Long.MIN_VALUE) none++;
                    else if (map.cellsAt(bx, BlockPos.getY(was), bz) == null) tops++;
                    else cells++;
                }
        // at the lone chunk's edges: the columns beside it not loaded
        if (lone)
            for (int[] e : edges)
                for (int own : new int[] {0, 64, 200}) {
                    long was = gameStand(level, e[0], e[1], own), now = StaffGround.stand(w, map, e[0], e[1], own);
                    stands++;
                    if (was == now) standAlike++;
                    if (was == Long.MIN_VALUE) none++;
                    else tops++;
                }
        for (int cx = (RX - GROUND - 1) >> 4; cx <= (RX + GROUND + 1) >> 4; cx++)
            for (int cz = (Z - GROUND - 1) >> 4; cz <= (Z + GROUND + 1) >> 4; cz++) level.setChunkForced(cx, cz, false);
        check.out(String.format(Locale.ROOT, "     entityview: StaffGround.stand and AiCommander's own: %d of %d alike (%d a cell, %d the top where the map is not built, %d none)",
                standAlike, stands, cells, tops, none));
        check.check("entityview: StaffGround.stand picks AiCommander's own cell to stand in", standAlike == stands && cells > 0);
    }

    private static long @org.jspecify.annotations.Nullable [] gameDetour(ServerLevel level, int x0, int z0, int y0, double tx, double tz) {
        int side = AiCommander.DETOUR * 2 + 1;
        int[] from = new int[side * side], ys = new int[side * side];
        java.util.Arrays.fill(from, -2);
        int start = AiCommander.DETOUR * side + AiCommander.DETOUR;
        from[start] = -1;
        ys[start] = y0;
        int[] queue = new int[side * side];
        int head = 0, tail = 0, best = start;
        queue[tail++] = start;
        double bestD = Math.hypot(x0 + 0.5 - tx, z0 + 0.5 - tz);
        double startD = bestD;
        while (head < tail) {
            int c = queue[head++];
            int cx = c % side, cz = c / side;
            for (int d = 0; d < 4; d++) {
                int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int n = nz * side + nx;
                if (from[n] != -2) continue;
                int bx = x0 + nx - AiCommander.DETOUR, bz = z0 + nz - AiCommander.DETOUR;
                from[n] = -3;
                if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
                int ny = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                if (Math.abs(ny - ys[c]) > AiCommander.DRIFT_CLIMB) continue;
                var fluid = level.getFluidState(new BlockPos(bx, ny - 1, bz));
                if (!fluid.isEmpty() && (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(bx, ny - 2, bz)).isEmpty())) continue;
                from[n] = c;
                ys[n] = ny;
                queue[tail++] = n;
                double dd = Math.hypot(bx + 0.5 - tx, bz + 0.5 - tz);
                if (dd < bestD) {
                    bestD = dd;
                    best = n;
                }
            }
        }
        if (best == start || startD - bestD < 2) return null;
        int len = 0;
        for (int c = best; c != start; c = from[c]) len++;
        long[] path = new long[len];
        for (int c = best, i = len - 1; c != start; c = from[c], i--)
            path[i] = (long) (x0 + c % side - AiCommander.DETOUR) << 32 | (z0 + c / side - AiCommander.DETOUR) & 0xFFFFFFFFL;
        return path;
    }

    private static long gameStand(ServerLevel level, int bx, int bz, int ownY) {
        TacticalMap map = TacticalMap.of(level);
        int top0 = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        boolean unknown = false;
        // at about its own height (a step up or down: a house's floor, the ground round a wall) the nearest across;
        // else the nearest across at any height (a hill's top), nearest its own height
        long near = Long.MIN_VALUE, any = Long.MIN_VALUE;
        double nearD = Double.MAX_VALUE, anyD = Double.MAX_VALUE;
        for (int dx = -AiCommander.SNAP; dx <= AiCommander.SNAP; dx++)
            for (int dz = -AiCommander.SNAP; dz <= AiCommander.SNAP; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = dx == 0 && dz == 0 ? top0 : level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double across = dx * dx + dz * dz;
                for (int y = top, low = Math.max(level.getMinY(), Math.min(top, ownY) - AiCommander.DOWN); y >= low; y--) {
                    SectionCells c = map.cellsAt(x, y, z);
                    if (c == null) {
                        unknown = true;
                        break;
                    }
                    if (c.find(x & 15, y & 15, z & 15) < 0) continue;
                    int dy = Math.abs(y - ownY);
                    if (dy <= AiCommander.LEVEL && across < nearD) {
                        nearD = across;
                        near = BlockPos.asLong(x, y, z);
                    }
                    double d = across * 1000 + dy;
                    if (d < anyD) {
                        anyD = d;
                        any = BlockPos.asLong(x, y, z);
                    }
                }
            }
        if (near != Long.MIN_VALUE) return near;
        if (any != Long.MIN_VALUE) return any;
        return unknown ? BlockPos.asLong(bx, top0, bz) : Long.MIN_VALUE;
    }

    /** AiSpawn.place as it was before core's SpawnPlace (589b844), for the comparison above */
    private static boolean gamePlace(ServerLevel level, Mob mob, Vec3 at, net.minecraft.util.RandomSource rng, int count) {
        double reach = 2 + Math.sqrt(count) * 1.5;
        for (int attempt = 0; attempt <= 16; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2, r = attempt == 16 ? 0 : 1.5 + rng.nextDouble() * reach;
            double x = Math.floor(at.x + Math.cos(a) * r) + 0.5, z = Math.floor(at.z + Math.sin(a) * r) + 0.5;
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos feet = BlockPos.containing(x, at.y + dy, z);
                if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) continue;
                mob.setPos(x, feet.getY(), z);
                float yaw = rng.nextFloat() * 360F;
                mob.setYRot(yaw);
                mob.setYHeadRot(yaw);
                mob.setYBodyRot(yaw);
                if (level.noCollision(mob)) return true;
            }
        }
        return false;
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
        eq(w + "typeNumber", b.typeNumber(), EntityBody.typeNumber(e.getType()));
        eq(w + "age", b.age(), e.tickCount);
        eq(w + "profileName", b.profileName(), e instanceof net.minecraft.world.entity.player.Player p ? p.getGameProfile().name() : null);
        eq(w + "uuid", b.uuid(), e.getUUID());
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
        eq(w + "eyeHeight prone", b.eyeHeight(Stance.PRONE), e.getDimensions(Pose.SWIMMING).eyeHeight());
        eq(w + "height prone", b.height(Stance.PRONE), e.getDimensions(Pose.SWIMMING).height());
        Pose pose = e.getPose();
        eq(w + "stance", b.stance(), pose == Pose.STANDING ? Stance.STAND : pose == Pose.CROUCHING ? Stance.CROUCH : pose == Pose.SWIMMING ? Stance.PRONE : (byte) -1);
        double[] tb = new double[6];
        b.typeBox(tb);
        AABB type = e.getType().getDimensions().makeBoundingBox(e.position());
        if (tb[0] != type.minX || tb[1] != type.minY || tb[2] != type.minZ || tb[3] != type.maxX || tb[4] != type.maxY || tb[5] != type.maxZ) wrong.add(w + "typeBox");
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
        // the living spread's reads (Spread): the known speed (not lastSpeed), crawling (not lying), a player's flight
        b.knownSpeed(o);
        eq3(w + "knownSpeed", o, e.getKnownSpeed());
        eq(w + "crawling", b.crawling(), e.isVisuallyCrawling());
        eq(w + "flying", b.flying(), e instanceof net.minecraft.world.entity.player.Player p && p.getAbilities().flying);
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
        eq(w + "spectator", b.spectator(), e instanceof net.minecraft.world.entity.player.Player p && p.isSpectator());
        eq(w + "creative", b.creative(), e instanceof net.minecraft.world.entity.player.Player p && p.isCreative());
        eq(w + "team", b.team(), e.getTeam() == null ? null : e.getTeam().getName());
        eq(w + "tagged", b.tagged(AiBots.TARGET_TAG), e.entityTags().contains(AiBots.TARGET_TAG));
        eq(w + "tagged gunfeel_check", b.tagged("gunfeel_check"), e.entityTags().contains("gunfeel_check"));
        if (b.level() != e.level()) wrong.add(w + "level");
        if (e instanceof LivingEntity le) {
            eq(w + "health", b.health(), le.getHealth());
            eq(w + "lastHurtTime", b.lastHurtTime(), le.getLastHurtByMobTimestamp());
            eq(w + "maxHealth", b.maxHealth(), le.getMaxHealth());
            eq(w + "climbing", b.climbing(), le.onClimbable());
            eq(w + "fallFlying", b.fallFlying(), le.isFallFlying());
            eq(w + "sleeping", b.sleeping(), le.isSleeping());
            eq(w + "lying", b.lying(), Prone.lying(le));
            eq(w + "speed", b.speed(), le.getAttributeValue(Attributes.MOVEMENT_SPEED));
            var regen = le.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
            var res = le.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
            eq(w + "regeneration", b.effectAmplifier(Body.EFFECT_REGENERATION), regen == null ? -1 : regen.getAmplifier());
            eq(w + "resistance", b.effectAmplifier(Body.EFFECT_RESISTANCE), res == null ? -1 : res.getAmplifier());
        }
        if (e instanceof Mob m) eq(w + "leftHanded", b.leftHanded(), m.isLeftHanded());
    }

    private void commanderPorts() {
        var a = acup.gunfeel.Wiring.world(level).random();
        var b = acup.gunfeel.Wiring.world(level).random();
        level.getRandom().setSeed(783L);
        int r1 = a.nextInt(41), r2 = b.nextInt(41), r3 = a.nextInt(41);
        level.getRandom().setSeed(783L);
        check.check("commander ports: two world wrappers draw from the level's one sequence",
                r1 == level.getRandom().nextInt(41) && r2 == level.getRandom().nextInt(41) && r3 == level.getRandom().nextInt(41));
        dummy.setLastHurtByMob(stand);
        Body body = EntityBody.of(dummy);
        LivingEntity credited = dummy.getKillCredit();
        check.check("commander ports: last attacker and kill credit keep the entity's one Body",
                body.lastHurtBy() == EntityBody.of(stand) && body.lastHurtBy() == body.lastHurtBy()
                && body.killCredit() == (credited == null ? null : EntityBody.of(credited)));
        check.check("medic ports: age and last attacker timestamp are the entity's reads",
                body.age() == dummy.tickCount && body.lastHurtTime() == dummy.getLastHurtByMobTimestamp());
        dummy.setLastHurtByMob(null);
        BodyControl c = CoreServices.load(BodyControl.class, BodyControl.MISSING);
        boolean ok = true;
        for (Entity e : List.of(dummy, arrow)) {
            Body eb = EntityBody.of(e);
            double x = e.getX(), yy = e.getY(), z = e.getZ();
            float yaw = e.getYRot(), pitch = e.getXRot();
            c.snap(eb, x + 2, yy + 1, z - 3, 35F, 17F);
            ok &= eb.x() == x + 2 && eb.y() == yy + 1 && eb.z() == z - 3 && eb.yRot() == 35F && eb.xRot() == 17F
                    && e.xo == eb.x() && e.yo == eb.y() && e.zo == eb.z();
            c.snap(eb, x, yy, z, yaw, pitch);
        }
        check.check("commander ports: snap sets position, turn and old position as Entity.snapTo", ok);
        var fluids = acup.gunfeel.Wiring.liquids(level);
        ok = true;
        for (int x = RX - GROUND; x <= RX + GROUND; x++) for (int z = Z - GROUND; z <= Z + GROUND; z++) {
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            var fs = level.getFluidState(new BlockPos(x, top - 1, z));
            boolean was = !fs.isEmpty() && (!fs.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(x, top - 2, z)).isEmpty());
            var f = fluids.at(x, top - 1, z);
            boolean now = f != null && (f.liquid() != acup.gunfeel.water.LiquidEvents.Liquid.WATER || fluids.at(x, top - 2, z) != null);
            ok &= now == was;
        }
        check.check("commander ports: drift's deep liquid test is the game's former expression", ok);
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

    private void players(EntityView view) {
        var game = level.players();
        Body[] out = new Body[game.size() + 3];
        int n = view.players(out);
        boolean ok = n == game.size();
        for (int i = 0; i < Math.min(n, game.size()); i++) {
            ok &= out[i] == EntityBody.of(game.get(i));
            same("player " + i, game.get(i));
        }
        if (!game.isEmpty()) ok &= view.players(new Body[game.size() - 1]) == -game.size();
        check.check("entityview: players are level.players, including spectators, in order, nothing cut", ok);
    }

    private void livingOnly() {
        Body a = EntityBody.of(arrow), s = EntityBody.of(stand);
        check.check("entityview: on the arrow (not living) the living ones throw", throwsOn(a::climbing) && throwsOn(a::speed) && throwsOn(a::lying) && throwsOn(a::sleeping)
                && throwsOn(a::leftHanded) && throwsOn(a::fallFlying) && throwsOn(a::health) && throwsOn(a::maxHealth) && throwsOn(a::lastHurtBy) && throwsOn(a::lastHurtTime) && throwsOn(a::killCredit));
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

    // AiRole's role of each of NTM's guns in the registry (a fresh stack) by the port (AiRoleBase, NtmGuns.fireTraits)
    // against AiRole.byFire as it was before it, copied below: the same, the whole table in the result
    private void roles() {
        List<String> differ = new ArrayList<>();
        java.util.Map<AiRole, Integer> count = new java.util.EnumMap<>(AiRole.class);
        net.minecraft.world.item.ItemStack before = dummy.getMainHandItem();
        Body db = EntityBody.of(dummy);
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!(item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) continue;
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            dummy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack);
            AiRole now = AiRoles.by(db, stack);
            AiRole was = oldBase(gun, stack) ? AiRole.BASE : AiGrenadier.shell(stack) != null ? AiRole.GRENADIER
                    : acup.gunfeel.PistolHold.scoped(stack) ? AiRole.MARKSMAN : AiRole.RIFLE;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            count.merge(now, 1, Integer::sum);
            check.out("     roles: " + id + " " + was + (now == was ? "" : " -> " + now));
            if (now != was) differ.add(id + " " + was + " -> " + now);
        }
        dummy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, before);
        check.check("roles: each NTM gun's role by the port is AiRole.byFire's before it, " + count + (differ.isEmpty() ? "" : ": " + differ),
                differ.isEmpty() && count.getOrDefault(AiRole.BASE, 0) > 0);
    }

    // AiRole.byFire as it was before NtmGuns.fireTraits (2026-11-20), the stand's "before"
    @SuppressWarnings("rawtypes")
    private static boolean oldBase(com.hbm.items.weapon.sedna.ItemGunBaseNT gun, net.minecraft.world.item.ItemStack stack) {
        try {
            com.hbm.items.weapon.sedna.Receiver rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
            com.hbm.items.weapon.sedna.mags.IMagazine mag = rec.getMagazine(stack);
            if (mag == null || !rec.getRefireOnHold(stack) || rec.getDelayAfterFire(stack) > 4) return false;
            if (!(mag.getType(stack, null) instanceof com.hbm.items.weapon.sedna.BulletConfig b) || b.velocity < 5F
                    || acup.gunfeel.BeamLasers.laser(b)) return false;
            int need = Math.max(1, Math.round(acup.gunfeel.ReaimTimes.number(AiRoleBase.MAG_KEY, 40)));
            return !(mag instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase) || mag.getCapacity(stack) >= need;
        } catch (RuntimeException e) {
            return false;
        }
    }

}
