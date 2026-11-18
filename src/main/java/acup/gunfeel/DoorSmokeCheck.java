package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.hbm.blockentity.BlockEntityDoorGeneric;
import com.hbm.blockentity.machine.BlockEntityBlastDoor;
import com.mojang.authlib.GameProfile;

import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.SmokeMask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The doors against smoke, on a dedicated server with no client (a dev run only: ./gradlew runCheckDoors -PnoAssets).
// -Dntmgunfeel.checkDoors=<file.tsv>: every NTM door (the multiblock ones and the simple ones) and vanilla's oak and
// iron doors and trapdoors are put up, each in a lane of its own high in the air, opened and shut as a player would,
// and the cells that open and shut with the door (its panel) are found from what SmokeMask reads. A wall is built in
// the panel's plane round the door, across the whole of a cloud's grid, and a smoke cloud let out 3 blocks in front of
// the shut door is stepped by hand for 15 s (SmokeGrid over the world's cells): with the door shut nothing may get
// behind it. The same cloud over the mask as it was up to 2026-11-18 (the vanilla tags' doors only) is reported beside
// it, and with the door open the smoke must get through. One tsv line a door; exit 1 if a check failed.
public final class DoorSmokeCheck {
    public static final String PROPERTY = "ntmgunfeel.checkDoors";
    private static final String[] DOORS = {"hbm:fire_door", "hbm:secure_access_door", "hbm:qe_sliding_door", "hbm:cargo_door",
            "hbm:water_door", "hbm:vault_door", "hbm:sliding_seal_door", "hbm:round_airlock_door", "hbm:sliding_blast_door",
            "hbm:large_vehicle_door", "hbm:transition_seal", "hbm:silo_hatch", "hbm:silo_hatch_large", "hbm:blast_door",
            "hbm:door_metal", "hbm:door_office", "hbm:door_bunker", "hbm:door_red", "hbm:trapdoor_steel", "minecraft:oak_door",
            "minecraft:iron_door", "minecraft:oak_trapdoor", "minecraft:iron_trapdoor"};
    private static final int Y = 100, SPACE = 48, ROW = 6, REACH = 12;
    /** Ticks a door is given to open or shut (NTM's slowest, the transition seal, takes 480). */
    private static final int WAIT = 600;
    /** Steps of the hand-run cloud: 15 s. */
    private static final int STEPS = 300;
    /** Smoke behind an open door at least, cells times density: it gets through. */
    private static final float THROUGH = 0.5F;

    private static final class Lane {
        final String id;
        final BlockPos at;
        final Set<BlockPos> blocks = new HashSet<>();
        final Set<BlockPos> openCells = new HashSet<>();
        Set<BlockPos> panel = new HashSet<>();
        Direction.Axis axis;
        int plane;
        BlockPos cloud;
        String skip;
        float shutNew, shutOld, open;

        Lane(String id, BlockPos at) {
            this.id = id;
            this.at = at;
        }
    }

    private static final List<Lane> LANES = new ArrayList<>();

    private DoorSmokeCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            ServerLevel level = started.getServer().overworld();
            int rows = (DOORS.length + ROW - 1) / ROW;
            for (int cx = -1; cx <= (ROW * SPACE) / 16 + 1; cx++) for (int cz = -1; cz <= (rows * SPACE) / 16 + 1; cz++) level.setChunkForced(cx, cz, true);
            int[] ticks = {0};
            List<String> out = new ArrayList<>();
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                int t = ++ticks[0];
                try {
                    if (t == 40) {
                        build(level);
                        for (Lane l : LANES) if (l.skip == null) setOpen(level, l, true);
                    } else if (t == 40 + WAIT) {
                        for (Lane l : LANES) if (l.skip == null) {
                            for (BlockPos p : l.blocks) if (!SmokeMask.closed(level, p)) l.openCells.add(p);
                            setOpen(level, l, false);
                        }
                    } else if (t == 40 + 2 * WAIT) {
                        for (Lane l : LANES) if (l.skip == null) shut(level, l);
                        for (Lane l : LANES) if (l.skip == null) setOpen(level, l, true);
                    } else if (t == 40 + 3 * WAIT) {
                        for (Lane l : LANES) if (l.skip == null) l.open = behind(level, l, false);
                        BulletCheck.finish(file, out, report(out) ? 0 : 1);
                    }
                } catch (Throwable ex) {
                    out.add("FAIL\tcrashed: " + ex);
                    for (StackTraceElement s : ex.getStackTrace()) out.add("    at " + s);
                    BulletCheck.finish(file, out, 1);
                }
            });
        });
    }

    // every door in its lane, put up by a player standing 4 blocks north of it looking south
    private static void build(ServerLevel level) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("doorSmoke".getBytes()), "doorSmoke"));
        for (int i = 0; i < DOORS.length; i++) {
            BlockPos at = new BlockPos(24 + SPACE * (i % ROW), Y, 24 + SPACE * (i / ROW));
            Lane l = new Lane(DOORS[i], at);
            LANES.add(l);
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(DOORS[i]));
            if (item == null || item == Items.AIR) {
                l.skip = "no such item";
                continue;
            }
            // the flat world is empty this high up
            BlockPos support = at.below();
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            player.snapTo(at.getX() + 0.5, Y, at.getZ() - 3.5, 0F, 0F);
            ItemStack stack = new ItemStack(item);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(at.getX() + 0.5, Y, at.getZ() + 0.5), Direction.UP, support, false)));
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-REACH, -4, -REACH), at.offset(REACH, 12, REACH)))
                if (!p.equals(support) && !level.getBlockState(p).isAir()) l.blocks.add(p.immutable());
            if (l.blocks.isEmpty()) l.skip = "not put up";
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    // opened or shut as its block entity or its block does it for a player
    private static void setOpen(ServerLevel level, Lane l, boolean open) {
        for (BlockPos p : l.blocks) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof BlockEntityDoorGeneric d) {
                if (open) d.open();
                else d.close();
            } else if (be instanceof BlockEntityBlastDoor b) {
                if (open) b.open();
                else b.close();
            }
        }
        for (BlockPos p : l.blocks) {
            BlockState s = level.getBlockState(p);
            if ((s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapDoorBlock) && s.getValue(BlockStateProperties.OPEN) != open)
                level.setBlock(p, s.setValue(BlockStateProperties.OPEN, open), 3);
        }
    }

    // shut: the panel, its plane, the wall round it and the cloud in front of it
    private static void shut(ServerLevel level, Lane l) {
        for (BlockPos p : l.openCells) if (SmokeMask.closed(level, p)) l.panel.add(p);
        if (l.panel.isEmpty()) {
            l.skip = "no cell of it opens to smoke (" + l.openCells.size() + " open, " + l.blocks.size() + " blocks)";
            return;
        }
        BlockState any = level.getBlockState(l.panel.iterator().next());
        // the plane with the most of the panel in it; a trapdoor lies flat, and a tie goes by the door's facing
        Direction.Axis facing = null;
        for (BlockPos p : l.blocks) {
            BlockState s = level.getBlockState(p);
            if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) facing = s.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis();
        }
        int best = -1;
        for (Direction.Axis a : Direction.Axis.values()) {
            java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
            for (BlockPos p : l.panel) count.merge(p.get(a), 1, Integer::sum);
            for (var e : count.entrySet()) {
                boolean better = e.getValue() > best || e.getValue() == best && a == facing;
                if (better) {
                    best = e.getValue();
                    l.axis = a;
                    l.plane = e.getKey();
                }
            }
        }
        if (any.getBlock() instanceof TrapDoorBlock || any.is(BlockTags.TRAPDOORS)) {
            l.axis = Direction.Axis.Y;
            l.plane = l.panel.iterator().next().getY();
        }
        // the cloud 3 blocks before the middle of the panel's part in the plane (below a flat one: the box goes 8 up)
        double cx = 0, cy = 0, cz = 0;
        int n = 0;
        for (BlockPos p : l.panel) if (p.get(l.axis) == l.plane) {
            cx += p.getX();
            cy += p.getY();
            cz += p.getZ();
            n++;
        }
        BlockPos mid = BlockPos.containing(cx / n + 0.5, cy / n + 0.5, cz / n + 0.5);
        if (l.axis == Direction.Axis.Y) l.cloud = mid.below(3);
        else l.cloud = mid.relative(l.axis, -3).atY(Math.min(mid.getY(), minY(l.panel) + 1));
        BlockPos support = l.at.below();
        if (support.get(l.axis) != l.plane) level.setBlock(support, Blocks.AIR.defaultBlockState(), 2);
        // the wall: every empty cell of the plane over the whole grid
        int bx = l.cloud.getX() - SmokeGrid.OX, by = l.cloud.getY() - SmokeGrid.OY, bz = l.cloud.getZ() - SmokeGrid.OZ;
        for (int y = by; y < by + SmokeGrid.SY; y++) for (int z = bz; z < bz + SmokeGrid.SZ; z++) for (int x = bx; x < bx + SmokeGrid.SX; x++) {
            BlockPos p = new BlockPos(x, y, z);
            if (p.get(l.axis) != l.plane || !level.getBlockState(p).isAir()) continue;
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        l.shutNew = behind(level, l, false);
        l.shutOld = behind(level, l, true);
    }

    // smoke behind the door's plane after the cloud ran its steps, over the mask of now or of before
    private static float behind(ServerLevel level, Lane l, boolean old) {
        int bx = l.cloud.getX() - SmokeGrid.OX, by = l.cloud.getY() - SmokeGrid.OY, bz = l.cloud.getZ() - SmokeGrid.OZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        SmokeGrid.Mask mask = (x, y, z) -> {
            pos.set(bx + x, by + y, bz + z);
            return old ? oldClosed(level, pos) : SmokeMask.closed(level, pos);
        };
        Vec3 dir = new Vec3(l.axis == Direction.Axis.X ? 1 : 0, 0, l.axis == Direction.Axis.Z ? 1 : 0);
        SmokeGrid g = new SmokeGrid(SmokeKind.SMOKE, 42L, (float) dir.x, (float) dir.z, 1F);
        g.scan(mask);
        g.place();
        for (int s = 0; s < STEPS; s++) g.step();
        int side = Integer.signum(l.cloud.get(l.axis) - l.plane);
        float sum = 0F;
        for (int y = 0; y < SmokeGrid.SY; y++) for (int z = 0; z < SmokeGrid.SZ; z++) for (int x = 0; x < SmokeGrid.SX; x++) {
            pos.set(bx + x, by + y, bz + z);
            if (Integer.signum(pos.get(l.axis) - l.plane) == -side) sum += g.density(x, y, z);
        }
        return sum;
    }

    // SmokeMask as it was up to 2026-11-18: a door only in the vanilla tags, with the vanilla "open"
    private static boolean oldClosed(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return pos.getY() < level.getMinY();
        if (!level.hasChunkAt(pos)) return true;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        if ((state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) && state.hasProperty(BlockStateProperties.OPEN))
            return !state.getValue(BlockStateProperties.OPEN);
        if (state.is(SmokeMask.BLOCKS_SMOKE)) return true;
        return state.isCollisionShapeFullBlock(level, pos);
    }

    private static boolean report(List<String> out) {
        boolean ok = true;
        out.add("result\tdoor\tblocks\tpanel\tplane\tshut (now)\tshut (before)\topen");
        for (Lane l : LANES) {
            if (l.skip != null) {
                boolean fail = !l.skip.equals("no such item");
                ok &= !fail;
                out.add((fail ? "FAIL" : "SKIP") + "\t" + l.id + "\t" + l.skip);
                continue;
            }
            boolean pass = l.shutNew == 0F && l.open >= THROUGH;
            ok &= pass;
            out.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%s=%d\t%.3f\t%.3f\t%.3f", pass ? "PASS" : "FAIL", l.id, l.blocks.size(), l.panel.size(),
                    l.axis.getName(), l.plane, l.shutNew, l.shutOld, l.open));
        }
        return ok;
    }

    private static int minY(Set<BlockPos> s) {
        int m = Integer.MAX_VALUE;
        for (BlockPos p : s) m = Math.min(m, p.getY());
        return m;
    }
}
