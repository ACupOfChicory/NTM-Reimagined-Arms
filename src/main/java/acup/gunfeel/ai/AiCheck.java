package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The tactical map on a real server (a dev run: ./gradlew runCheckAi -PnoAssets). -Dntmgunfeel.checkAi=&lt;file&gt;:
 * once the world is up, the server builds a yard high in the air (a stone floor, a stone wall two high, a concrete
 * wall one high, glass, leaves), has the map build its cells on the background threads (the map is ticked by hand,
 * the server's own ticks wait), checks the cover of cells by the walls, breaks a hole in the wall and blows up a
 * corner and checks the cells follow, with the time it all took. One line per check to the file; the server stops
 * (exit 1 if a check failed).
 */
public final class AiCheck {
    public static final String PROPERTY = "ntmgunfeel.checkAi";
    static final String STAGE_PROPERTY = "ntmgunfeel.checkAiStage";

    /**
     * Takes every shot still in the level away between stages: a bullet that flew past a stage's field stops in chunks
     * that do not tick, and comes alive when a later stage loads them, landing by its field with a sound its AI mobs
     * hear (the unseen stage's dummies turned to such a sound and never saw the player).
     */
    static void clearShots(ServerLevel level) {
        List<net.minecraft.world.entity.Entity> shots = new ArrayList<>();
        for (var e : level.getAllEntities()) if (e instanceof net.minecraft.world.entity.projectile.Projectile) shots.add(e);
        for (var e : shots) e.discard();
    }
    private static final int Y = 100, X0 = 8, Z0 = 8, SIZE = 32;

    private AiCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            List<String> out = new ArrayList<>();
            AiCheck check = new AiCheck();
            try {
                check.run(out, e.getServer());
            } catch (Throwable t) {
                crashed(out, t);
                finish(file, out, 1);
                return;
            }
            try {
                AiCheckRecoil.run(check, check.level, java.nio.file.Path.of(file).resolveSibling("ntmgunfeel-ai-recoil.tsv"));
            } catch (Throwable t) {
                crashed(out, t);
                check.ok = false;
            }
            // how the AI takes blocks with models of their own: sandbags and the like hide (and stop bullets), glass does not
            {
                StringBuilder b = new StringBuilder("     blocks as the AI takes them (in a row along x):");
                boolean sandbagsHide = false, glassClear = true;
                for (String id : List.of("hbm:sandbags", "hbm:barricade", "minecraft:chest", "minecraft:glass", "hbm:reinforced_glass", "minecraft:iron_bars",
                        "minecraft:oak_fence")) {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(id));
                    if (block.isEmpty()) {
                        b.append(' ').append(id).append(" (none);");
                        continue;
                    }
                    // a block that joins its neighbours (sandbags, bars, fences) as in a row along x
                    var st = joined(block.get().defaultBlockState(), true, true, false, false);
                    int v = BlockClasses.of(st);
                    var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
                    double vol = 0;
                    for (var box : shape.toAabbs()) vol += box.getXsize() * box.getYsize() * box.getZsize();
                    b.append(' ').append(id).append(' ').append(BlockClasses.describe(v)).append(String.format(java.util.Locale.ROOT, " (%s, volume %.2f, occludes %s)",
                            acup.gunfeel.BulletRules.material(st).material(), vol, st.canOcclude())).append(';');
                    if (id.equals("hbm:sandbags")) sandbagsHide = Voxel.sight(v) && Voxel.protect(v) >= 1;
                    if (id.contains("glass") || id.contains("bars") || id.contains("fence")) glassClear &= !Voxel.sight(v);
                }
                check.out(b.toString());
                check.check("sandbags in a row hide and stop bullets; glass, bars and fences in a row hide nothing", sandbagsHide && glassClear);
            }
            // the rest runs with the server ticking
            AiCheckTicks ticks = new AiCheckTicks(check, check.level, Y);
            AiCheckFight fight = new AiCheckFight(check, check.level, Y);
            AiCheckCrowd crowd = new AiCheckCrowd(check, check.level, Y);
            AiCheckSpread spread = new AiCheckSpread(check, check.level, Y);
            AiCheckProne prone = new AiCheckProne(check, check.level, Y);
            AiCheckAim aim = new AiCheckAim(check, check.level, Y);
            AiCheckTeams teams = new AiCheckTeams(check, check.level, Y);
            AiCheckUnseen unseen = new AiCheckUnseen(check, check.level, Y);
            AiCheckReact react = new AiCheckReact(check, check.level, Y);
            AiCheckTeams battle = new AiCheckTeams(check, check.level, Y, true);
            AiCheckParts parts = new AiCheckParts(check, check.level, Y);
            AiCheckLean lean = new AiCheckLean(check, check.level, Y);
            AiCheckSquad squad = new AiCheckSquad(check, check.level, Y);
            AiCheckDuel duel = new AiCheckDuel(check, check.level, Y);
            AiCheckBlind blind = new AiCheckBlind(check, check.level, Y);
            AiCheckGrenade grenade = new AiCheckGrenade(check, check.level, Y);
            AiCheckIdle idle = new AiCheckIdle(check, check.level, Y);
            // walked, fought, fought in teams (on the fight's field), crowded, spread, lay down, aimed, aimed at a part, a
            // leaned round a corner, fought as a squad, fired blind, threw grenades and ran from them, a head with no one to fight, a player out of creative, a player not expected, the battle as the owner had it, squads against loners
            List<String> names = List.of("ticks", "fight", "teams", "crowd", "spread", "prone", "aim", "parts", "lean", "squad", "blind", "grenade", "idle", "unseen", "react", "battle", "duel");
            List<java.util.function.BooleanSupplier> stages = List.of(ticks::tick, fight::tick, teams::tick, crowd::tick, spread::tick, prone::tick,
                    aim::tick, parts::tick, lean::tick, squad::tick, blind::tick, grenade::tick, idle::tick, unseen::tick, react::tick, battle::tick, duel::tick);
            // -Dntmgunfeel.checkAiStage=<name> (./gradlew runCheckAi -PaiStage=<name>): that stage alone
            // (a,b,c: those stages, in the check's own order)
            List<String> only = List.of(System.getProperty(STAGE_PROPERTY, "").split(",")).stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
            int[] at = {0}, grenadesFor = {-1};
            // and one from chunks a later stage loads, saved there with its shooter long gone: kept out
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, joined -> {
                if (joined.loadedFromDisk() && joined.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile shot && shot.getOwner() == null) joined.setCanceled(true);
            });
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, t -> {
                boolean done;
                try {
                    while (at[0] < stages.size() && !only.isEmpty() && !only.contains(names.get(at[0]))) at[0]++;
                    // grenades in the grenades' own stage and the battles; the other stages count what they did before
                    if (at[0] < stages.size() && grenadesFor[0] != at[0]) {
                        grenadesFor[0] = at[0];
                        AiGrenades.trace = true;
                        AiGrenades.on = AiGrenades.dodgeOn = List.of("grenade", "battle", "duel").contains(names.get(at[0]));
                    }
                    if (at[0] < stages.size() && stages.get(at[0]).getAsBoolean()) {
                        at[0]++;
                        clearShots(check.level);
                    }
                    done = at[0] >= stages.size();
                } catch (Throwable x) {
                    crashed(out, x);
                    check.ok = false;
                    done = true;
                }
                if (!done) return;
                out.add("     " + check.map.info());
                out.add("     senses: " + Senses.info());
                out.add(check.ok ? "ALL OK" : "FAILED");
                finish(file, out, check.ok ? 0 : 1);
            });
        });
    }


    /** a block state that joins its neighbours, joined to the east and west, north and south as asked (else as it is) */
    static net.minecraft.world.level.block.state.BlockState joined(net.minecraft.world.level.block.state.BlockState st, boolean east, boolean west,
            boolean north, boolean south) {
        String[] names = {"east", "west", "north", "south"};
        boolean[] on = {east, west, north, south};
        for (int i = 0; i < 4; i++) {
            var prop = st.getBlock().getStateDefinition().getProperty(names[i]);
            if (prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty b) st = st.setValue(b, on[i]);
        }
        return st;
    }

    private static void crashed(List<String> out, Throwable t) {
        out.add("FAIL crashed: " + t);
        for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
    }

    static void finish(String file, List<String> out, int code) {
        try {
            java.nio.file.Path f = java.nio.file.Path.of(file);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
            code = 1;
        }
        out.forEach(System.out::println);
        Runtime.getRuntime().halt(code);
    }

    private List<String> out;
    boolean ok = true;
    ServerLevel level;
    TacticalMap map;

    private boolean run(List<String> out, MinecraftServer server) throws Exception {
        this.out = out;
        level = server.overworld();
        // no zombie or spider of the world's on the yards: the AI hears them, and the hearing checks count what it hears
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
        for (var e : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Mob.class),
                m -> m instanceof net.minecraft.world.entity.monster.Enemy)) e.discard();
        BlockState stone = Blocks.STONE.defaultBlockState(), concrete = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) set(x, Y - 1, z, stone);
        // a stone wall two high across the yard at z = 24, a concrete one one high at z = 32
        for (int x = X0 + 2; x < X0 + SIZE - 2; x++) {
            set(x, Y, 24, stone);
            set(x, Y + 1, 24, stone);
            set(x, Y, 32, concrete);
        }
        // glass and leaves two high, side by side at z = 14
        for (int y = Y; y < Y + 2; y++) {
            set(12, y, 14, Blocks.GLASS.defaultBlockState());
            set(16, y, 14, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }

        map = TacticalMap.of(level);
        long t0 = System.nanoTime();
        map.want(X0 + SIZE / 2, Y, Z0 + SIZE / 2, SIZE / 2 + 2, 8);
        int ticks = settle();
        out.add(String.format(Locale.ROOT, "     first build: %d ticks, %.1f ms; %s", ticks, (System.nanoTime() - t0) / 1e6, map.info()));
        check("map built", map.publishedCount() > 0 && map.queued() == 0 && map.inFlight() == 0);

        int north = cover(15, Y, 23), south = cover(15, Y, 25);
        check("stone wall: high hard cover south of the cell north of it",
                SectionCells.height(north, Dirs.S) == 2 && SectionCells.hard(north, Dirs.S));
        check("stone wall: high hard cover north of the cell south of it",
                SectionCells.height(south, Dirs.N) == 2 && SectionCells.hard(south, Dirs.N));
        check("stone wall: no cover away from it", SectionCells.height(north, Dirs.N) == 0);
        check("a cell on top of the wall", has(15, Y + 2, 24));
        check("no cell in the wall", !has(15, Y, 24));
        int low = cover(15, Y, 31);
        check("concrete wall one high: low hard cover", SectionCells.height(low, Dirs.S) == 1 && SectionCells.hard(low, Dirs.S));
        check("glass hides nothing", SectionCells.height(cover(12, Y, 13), Dirs.S) == 0);
        int leaves = cover(16, Y, 13);
        check("leaves hide but do not stop bullets", SectionCells.height(leaves, Dirs.S) == 2 && !SectionCells.hard(leaves, Dirs.S));
        int cells = 0;
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) if (has(x, Y, z)) cells++;
        out.add("     cells on the yard floor: " + cells);
        check("cells on the floor of the yard", cells > SIZE * SIZE - 3 * SIZE);

        // a hole in the wall: the cells follow
        long changesBefore = map.changes;
        t0 = System.nanoTime();
        set(15, Y, 24, Blocks.AIR.defaultBlockState());
        set(15, Y + 1, 24, Blocks.AIR.defaultBlockState());
        check("a broken block makes its section stale", map.cellsAt(15, Y, 24) != null && !map.fresh(map.cellsAt(15, Y, 24)));
        map.want(15, Y, 24, 8, 4);
        ticks = settle();
        out.add(String.format(Locale.ROOT, "     hole: %d block changes seen, rebuilt in %d ticks, %.1f ms",
                map.changes - changesBefore, ticks, (System.nanoTime() - t0) / 1e6));
        check("a cell in the hole", has(15, Y, 24));
        check("the cell before the hole lost its cover south", SectionCells.height(cover(15, Y, 23), Dirs.S) == 0);
        int beside = cover(14, Y, 23);
        check("its neighbour keeps its wall south, the hole south-east is open",
                SectionCells.height(beside, Dirs.S) == 2 && SectionCells.height(beside, Dirs.SE) == 0);
        check("a cell beside the wall is fresh again", map.fresh(map.cellsAt(15, Y, 23)));

        // a change that does not touch what the AI reads (stone to cobblestone): nothing to rebuild
        changesBefore = map.changes;
        set(20, Y + 1, 24, Blocks.COBBLESTONE.defaultBlockState());
        check("stone to cobblestone changes nothing for the AI", map.changes == changesBefore);

        // an explosion at the end of the wall
        changesBefore = map.changes;
        long builds = map.builds, snaps = map.snapshots;
        t0 = System.nanoTime();
        level.explode(null, X0 + SIZE - 6, Y + 1, 24, 6F, Level.ExplosionInteraction.BLOCK);
        long blastNanos = System.nanoTime() - t0;
        ticks = settle();
        out.add(String.format(Locale.ROOT, "     explosion: %.1f ms for the blast itself, %d block changes seen, %d snapshots, %d builds, settled in %d ticks, %.1f ms",
                blastNanos / 1e6, map.changes - changesBefore, map.snapshots - snaps, map.builds - builds, ticks, (System.nanoTime() - t0) / 1e6));
        check("the explosion was seen", map.changes > changesBefore);
        check("every section fresh after the explosion", allFresh());

        // a snapshot once every block state met is classified: what a changed section costs the server thread
        long n0 = map.snapshots, t1 = map.snapshotNanos;
        for (int i = 0; i < 20; i++) {
            set(X0 + 1 + i, Y, Z0 + 1, Blocks.STONE.defaultBlockState());
            map.want(X0 + 1 + i, Y, Z0 + 1, 2, 2);
            settle();
            set(X0 + 1 + i, Y, Z0 + 1, Blocks.AIR.defaultBlockState());
            settle();
        }
        double warm = (map.snapshotNanos - t1) / 1000.0 / Math.max(1, map.snapshots - n0);
        out.add(String.format(Locale.ROOT, "     warm snapshots: %d, avg %.1f us", map.snapshots - n0, warm));
        check("a warm snapshot under 100 us", warm < 100);

        // what /armsai debug sends: through the network codec and back
        AiCellsPayload p = AiDebug.cells(map, new BlockPos(20, Y, 20), 16);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        AiCellsPayload.CODEC.encode(buf, p);
        int bytes = buf.readableBytes();
        AiCellsPayload back = AiCellsPayload.CODEC.decode(buf);
        out.add("     debug view radius 16: " + p.count() + " cells, " + bytes + " bytes");
        check("debug cells survive the codec", p.count() > 100 && back.count() == p.count()
                && java.util.Arrays.equals(back.cover(), p.cover()) && java.util.Arrays.equals(back.offsets(), p.offsets()));
        senses();
        for (var e : made) e.discard();
        cover();
        return ok;
    }

    // ------------------------------------------------------------------ senses

    private long now = 1000;
    private final List<net.minecraft.world.entity.Entity> made = new ArrayList<>();

    /** an NTM test dummy where asked, looking yaw degrees (0 south, 180 north) */
    private net.minecraft.world.entity.Mob dummy(double x, double z, float yaw, boolean target) {
        var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"));
        var e = (net.minecraft.world.entity.Mob) type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        e.setPos(x, Y, z);
        face(e, yaw);
        if (target) e.addTag(AiBots.TARGET_TAG);
        level.addFreshEntity(e);
        made.add(e);
        return e;
    }

    private static void face(net.minecraft.world.entity.Mob e, float yaw) {
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        e.setXRot(0);
    }

    private void move(net.minecraft.world.entity.Entity e, double x, double z) {
        e.setPos(x, Y, z);
    }

    /** looks until the target is known or max looks are spent; the looks it took, or -1 */
    private int looksToNotice(AiBot bot, net.minecraft.world.entity.LivingEntity t, int max) {
        for (int i = 1; i <= max; i++) {
            now += bot.profile.period();
            bot.perceive(AiBots.voxels(level), acup.gunfeel.smoke.SmokeClouds.get(level), List.of(t), now);
            TargetMemory.Contact c = bot.memory.contact(t.getId());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return i;
        }
        return -1;
    }

    private void senses() {
        var voxels = AiBots.voxels(level);
        net.minecraft.world.entity.Mob mob = dummy(22.5, 18.5, 0, false);
        net.minecraft.world.entity.Mob target = dummy(22.5, 21.5, 180, true);
        AiBot bot = AiBots.attach(mob, AiProfile.SOLDIER);
        int notice = Senses.look(voxels, null, mob, target, bot.profile);
        int looks = looksToNotice(bot, target, 10);
        out.add("     in the open 3 blocks ahead: noticed after " + looks + " looks (" + notice + " ticks to notice, a look every "
                + bot.profile.period() + ")");
        check("noticed in the open after the reaction time", notice > 0 && looks == (notice + bot.profile.period() - 1) / bot.profile.period());

        // the tagged dummy is among the level's targets: the bot's own tick looks at it
        bot.memory.contacts().clear();
        AiBots.refreshTargets(level);
        int period = bot.profile.period();
        now += 20;
        now += Math.floorMod(-(now + mob.getId()), period);
        AiBots.think(bot, now);
        check("a living thing tagged " + AiBots.TARGET_TAG + " is a target", bot.memory.contact(target.getId()) != null);

        move(target, 22.5, 27.5);
        check("not seen behind the stone wall", Senses.look(voxels, null, mob, target, bot.profile) < 0);
        net.minecraft.world.entity.Mob glassBot = dummy(12.5, 10.5, 0, false), glassTarget = dummy(12.5, 17.5, 180, true);
        check("seen through glass", Senses.look(voxels, null, glassBot, glassTarget, bot.profile) >= 0);
        net.minecraft.world.entity.Mob leafBot = dummy(16.5, 10.5, 0, false), leafTarget = dummy(16.5, 17.5, 180, true);
        check("not seen through leaves", Senses.look(voxels, null, leafBot, leafTarget, bot.profile) < 0);
        face(glassBot, 180);
        check("not seen behind its back", Senses.look(voxels, null, glassBot, glassTarget, bot.profile) < 0);
        face(glassBot, 0);
        int ahead = Senses.look(voxels, null, glassBot, glassTarget, bot.profile);
        face(glassBot, 60);
        int aside = Senses.look(voxels, null, glassBot, glassTarget, bot.profile);
        out.add("     noticed straight ahead in " + ahead + " ticks, 60 degrees aside in " + aside);
        check("seen in the corner of the eye 60 degrees off, slower", ahead > 0 && aside > ahead);

        // lost: the memory keeps where it went out of sight, whatever the target does after
        move(target, 23.5, 21.5);
        bot.memory.contacts().clear();
        check("noticed again", looksToNotice(bot, target, 10) > 0);
        TargetMemory.Contact c = bot.memory.contact(target.getId());
        move(target, 23.5, 27.5);
        looksToNotice(bot, target, 1);
        check("out of sight: lost where it was last seen", c.state == TargetMemory.State.LOST && c.lostX == 23.5 && c.lostZ == 21.5);
        move(target, 27.5, 28.5);
        looksToNotice(bot, target, 3);
        check("the memory does not follow it behind the wall", c.lostX == 23.5 && c.lostZ == 21.5 && c.state == TargetMemory.State.LOST);
        now += bot.profile.hold() + 1;
        bot.memory.update(now);
        check("after the hold: search", c.state == TargetMemory.State.SEARCH);

        // where to search: cells near the lost place the bot cannot see from where it stands
        map.want((int) c.lostX, Y, (int) c.lostZ, 16, 6);
        try {
            settle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var eye = mob.getEyePosition();
        long t0 = System.nanoTime();
        long[] spots = SearchSpots.pick(map, voxels, eye, c.lostX, c.lostY, c.lostZ, 8, 6, bot.clearedFor(c), mob.getRandom(), 64);
        long pickNanos = System.nanoTime() - t0;
        boolean hidden = spots.length > 0, near = true;
        for (long k : spots) {
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            hidden &= !VoxelRay.clear(voxels, eye.x, eye.y, eye.z, x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT);
            near &= Math.hypot(x + 0.5 - c.lostX, z + 0.5 - c.lostZ) <= 8;
        }
        out.add(String.format(Locale.ROOT, "     search spots: %d picked in %.0f us, %d cells seen and cleared; first %s",
                spots.length, pickNanos / 1000.0, bot.cleared.size(), spots.length == 0 ? "-" : BlockPos.of(spots[0]).toShortString()));
        check("search spots are hidden from the bot and near the lost place", hidden && near);
        // a wider pick goes through more cells, some in sight (the first six may all be hidden)
        SearchSpots.pick(map, voxels, eye, c.lostX, c.lostY, c.lostZ, 8, 40, bot.clearedFor(c), mob.getRandom(), 64);
        check("cells in sight were cleared (" + bot.cleared.size() + ")", !bot.cleared.isEmpty());

        // hearing: a blast 40 blocks off is heard about where it was; a step 20 blocks off is not
        bot.memory.update(now += 2000);
        level.playSound(null, 22.5, Y, 58.5, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 4F, 1F);
        TargetMemory.Noise n = bot.memory.noise();
        check("a blast 40 blocks off is heard, loud", n != null && n.loud);
        if (n != null) out.add(String.format(Locale.ROOT, "     blast heard %.1f blocks from where it was", Math.hypot(n.x - 22.5, n.z - 58.5)));
        check("heard about where it was", n != null && Math.hypot(n.x - 22.5, n.z - 58.5) < 16);
        bot.memory.update(now += 4000);
        level.playSound(null, 22.5, Y, 38.5, net.minecraft.sounds.SoundEvents.STONE_STEP, net.minecraft.sounds.SoundSource.PLAYERS, 0.15F, 1F);
        check("a step 20 blocks off is not heard", bot.memory.noise() == null);
        level.playSound(null, 22.5, Y, 23, net.minecraft.sounds.SoundEvents.STONE_STEP, net.minecraft.sounds.SoundSource.PLAYERS, 0.15F, 1F);
        check("a step 4.5 blocks off is heard, quiet", bot.memory.noise() != null && !bot.memory.noise().loud);
        bot.memory.update(now += 4000);
        level.playSound(null, mob.getX() + 1, Y, mob.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 4F, 1F);
        check("its own sounds are not news", bot.memory.noise() == null);

        // a hit tells where from, roughly
        move(target, 22.5, 40.5);
        mob.hurtServer(level, level.damageSources().mobAttack(target), 1F);
        n = bot.memory.noise();
        check("a hit is heard as coming from the shooter", n != null && n.hurt && Math.hypot(n.x - 22.5, n.z - 40.5) < 15);

        // thick smoke between hides
        move(target, 22.5, 21.5);
        face(mob, 0);
        int before = Senses.look(voxels, acup.gunfeel.smoke.SmokeClouds.get(level), mob, target, bot.profile);
        if (acup.gunfeel.smoke.SmokeClouds.enabled()) {
            acup.gunfeel.smoke.SmokeClouds.spawn(level, new net.minecraft.world.phys.Vec3(22.5, Y + 1, 20), new net.minecraft.world.phys.Vec3(0, 0, 1),
                    acup.gunfeel.smoke.SmokeKind.SMOKE, 1F);
            // the server does not tick while the check runs: step the cloud's clock by hand
            for (int i = 1; i <= 60; i++) acup.gunfeel.smoke.SmokeClouds.of(level).tick(level.getGameTime() + i, 1);
            var smoke = acup.gunfeel.smoke.SmokeClouds.get(level);
            int after = Senses.look(voxels, smoke, mob, target, bot.profile);
            out.add(String.format(Locale.ROOT, "     smoke between: optical depth %.1f",
                    smoke == null ? -1F : smoke.opticalDepth(mob.getEyePosition(), target.position().add(0, target.getBbHeight() * 0.55, 0))));
            check("thick smoke hides a target that was in sight", before >= 0 && after < 0);
        } else out.add("     smoke is off on this server: not checked");

        // what a look costs: a target 40 blocks off in the open
        move(target, 22.5, 18.5 - 40);
        for (int z = -22; z <= 18; z += 16) level.getChunk(22 >> 4, z >> 4);
        face(mob, 180);
        acup.gunfeel.smoke.SmokeClouds.forget(level);
        long l0 = Senses.looks, n0 = Senses.nanos;
        int seen = 0;
        for (int i = 0; i < 2000; i++) if (Senses.look(voxels, null, mob, target, bot.profile) >= 0) seen++;
        double us = (Senses.nanos - n0) / 1000.0 / (Senses.looks - l0);
        out.add(String.format(Locale.ROOT, "     a look at a target 40 blocks off in the open: %.2f us (seen %d of 2000)", us, seen));
        check("a look under 20 us", us < 20 && seen == 2000);
    }

    /** cover from a threat north of the stone wall, for a mob south of it, on the real map */
    private void cover() {
        Vec3 threat = new Vec3(22.5, Y + 1.6, 16.5), self = new Vec3(22.5, Y, 28.5);
        map.want(22, Y, 26, 18, 6);
        try {
            settle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var voxels = AiBots.voxels(level);
        List<CoverQuery.Spot> spots = List.of();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            spots = CoverQuery.find(map, voxels, self, List.of(threat), new CoverQuery.Params(), k -> false, 4);
            best = Math.min(best, System.nanoTime() - t0);
        }
        out.add(String.format(Locale.ROOT, "     cover from a threat across the wall: %d spots, best %s (peek %s), %.0f us a query",
                spots.size(), spots.isEmpty() ? "-" : BlockPos.of(spots.get(0).cell()).toShortString(),
                spots.isEmpty() || !spots.get(0).hasPeek() ? "-" : BlockPos.of(spots.get(0).peek()).toShortString(), best / 1000.0));
        check("cover behind the stone wall, hidden from the threat", !spots.isEmpty() && BlockPos.getZ(spots.get(0).cell()) == 25
                && spots.get(0).height() == 2 && !VoxelRay.clear(voxels, threat.x, threat.y, threat.z,
                        BlockPos.getX(spots.get(0).cell()) + 0.5, Y + 1.6, BlockPos.getZ(spots.get(0).cell()) + 0.5, Voxel.SIGHT));
        check("a cover query under 1 ms", best < 1_000_000);
    }

    private void set(int x, int y, int z, BlockState st) {
        level.setBlock(new BlockPos(x, y, z), st, Block.UPDATE_CLIENTS);
    }

    /** ticks the map by hand until nothing is queued or building, 10 s at most */
    private int settle() throws InterruptedException {
        int ticks = 0;
        long end = System.nanoTime() + 10_000_000_000L;
        do {
            map.tick();
            ticks++;
            if (map.queued() == 0 && map.inFlight() == 0) break;
            Thread.sleep(2);
        } while (System.nanoTime() < end);
        return ticks;
    }

    private boolean allFresh() {
        boolean all = true;
        for (int x = X0; x < X0 + SIZE; x += 16)
            for (int z = Z0; z < Z0 + SIZE; z += 16) {
                SectionCells c = map.cellsAt(x, Y, z);
                if (c == null || !map.fresh(c)) all = false;
            }
        return all;
    }

    private boolean has(int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    private int cover(int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        int i = c == null ? -1 : c.find(x & 15, y & 15, z & 15);
        if (i < 0) {
            check("a cell at " + x + " " + y + " " + z, false);
            return 0;
        }
        return c.cover(i);
    }

    void out(String line) {
        out.add(line);
    }

    void check(String what, boolean good) {
        out.add((good ? "OK   " : "FAIL ") + what);
        ok &= good;
    }
}
